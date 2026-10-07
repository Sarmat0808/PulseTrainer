package fi.sarmat.pulsetrainer.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reports for AI analysis: a readable Markdown text (with a ready request to the AI)
 * plus CSV files with every number, so the AI can calculate precisely.
 */
object Report {

    private val ru = Locale("ru")
    private fun dt(t: Long) = SimpleDateFormat("d MMMM yyyy, HH:mm", ru).format(Date(t))
    private fun d(t: Long) = SimpleDateFormat("dd.MM.yyyy", ru).format(Date(t))
    private fun hm(t: Long) = SimpleDateFormat("HH:mm:ss", ru).format(Date(t))
    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
    private fun min(sec: Int) = "%.1f".format(Locale.US, sec / 60.0)

    const val PROMPT_ONE =
        "Ты — опытный тренер и спортивный физиолог. Проанализируй мою тренировку ниже: " +
            "оцени интенсивность и распределение по зонам пульса, качество отдыха и восстановление пульса между подходами " +
            "(падение пульса за 60 с), правильность нагрузки для моих целей, ошибки и риски. " +
            "Дай конкретные рекомендации: что изменить в следующий раз (подходы, отдых, зоны, длительность) " +
            "и сколько отдыхать до следующей тренировки. Мои цели: набор мышечной массы и укрепление сердца."

    const val PROMPT_MANY =
        "Ты — опытный тренер и спортивный физиолог. Проанализируй все мои тренировки ниже: " +
            "динамику нагрузки по неделям, баланс силовых и кардио, время в зонах (особенно зона 2 для сердца), " +
            "прогресс восстановления пульса между подходами, утреннюю готовность (ВСР и пульс покоя), " +
            "признаки перетренированности или недовосстановления. Составь план на следующую неделю по дням " +
            "с конкретными упражнениями, подходами, отдыхом и целевыми зонами пульса. " +
            "Мои цели: набор мышечной массы и укрепление сердца."

    // ---------------- Single workout ----------------

    fun workoutText(w: Workout, p: Profile?, hrv: List<HrvRecord>, daily: List<DailyStats> = emptyList(), goal: Goal? = null): String {
        val sb = StringBuilder()
        sb.appendLine("# Тренировка: ${w.title}")
        sb.appendLine()
        sb.appendLine("**Запрос к ИИ:** $PROMPT_ONE")
        sb.appendLine()
        profile(sb, p, w.zoneBounds, goal)
        session(sb, w, detailed = true)
        dailyTable(sb, daily.filter { it.day > w.start - 7 * 86400_000L && it.day <= w.end }, "Сон и показатели часов за 7 дней до тренировки")
        morning(sb, hrv.filter { it.time > w.start - 10 * 86400_000L && it.time <= w.end }, "Утренние тесты готовности (последние 10 дней)")
        sb.appendLine("_Полные данные (пульс каждую секунду, подходы, круги, маршрут) — в приложенных файлах CSV/JSON._")
        return sb.toString()
    }

    // ---------------- Period ----------------

    fun periodText(all: List<Workout>, days: Int, p: Profile?, hrv: List<HrvRecord>, daily: List<DailyStats> = emptyList(), goal: Goal? = null, weights: List<WeightEntry> = emptyList()): String {
        val now = System.currentTimeMillis()
        val from = now - days * 86400_000L
        val list = all.filter { it.start >= from }.sortedBy { it.start }
        val sb = StringBuilder()
        sb.appendLine("# Сводка тренировок за $days дней (${d(from)} — ${d(now)})")
        sb.appendLine()
        sb.appendLine("**Запрос к ИИ:** $PROMPT_MANY")
        sb.appendLine()
        profile(sb, p, list.lastOrNull()?.zoneBounds ?: p?.let { Physiology.zoneBounds(it) }, goal)
        val wInPeriod = weights.filter { it.time >= from }.sortedBy { it.time }
        if (wInPeriod.isNotEmpty()) {
            sb.appendLine("## Вес")
            sb.appendLine(wInPeriod.joinToString("; ") { "${d(it.time)}: ${"%.1f".format(Locale.US, it.kg)} кг" })
            sb.appendLine()
        }
        dailyTable(sb, daily.filter { it.day >= from - 86400_000L }, "Сон и показатели часов по дням")

        if (list.isEmpty()) {
            sb.appendLine("Тренировок за период нет.")
            morning(sb, hrv.filter { it.time >= from }, "Утренние тесты готовности")
            return sb.toString()
        }

        val zs = IntArray(6) { i -> list.sumOf { it.zoneSec[i] } }
        sb.appendLine("## Итого")
        sb.appendLine("- Тренировок: ${list.size}, активное время: ${fmtDuration(list.sumOf { it.activeSec })}")
        sb.appendLine("- Калории: ${list.sumOf { it.kcalTotal }.roundToInt()} ккал (активные ${list.sumOf { it.kcalActive }.roundToInt()})")
        sb.appendLine("- Нагрузка TRIMP: ${list.sumOf { it.trimp }.roundToInt()}")
        sb.appendLine("- Время в зонах, мин: " + (1..5).joinToString(", ") { "Зона $it ${min(zs[it])}" } + ", ниже З1 ${min(zs[0])}")
        val segs = list.flatMap { it.segments }
        val byType = segs.groupBy { it.type }
        sb.appendLine("- По упражнениям:")
        byType.forEach { (t, ss) ->
            val sets = ss.sumOf { it.sets.size }
            val reps = ss.sumOf { s -> s.sets.sumOf { it.reps } }
            val dist = ss.sumOf { it.distanceM }
            sb.append("  - ${t.title}: ${ss.size} раз, ${fmtDuration(ss.sumOf { it.activeSec })}")
            if (sets > 0) sb.append(", подходов $sets")
            if (reps > 0) sb.append(", повторов $reps")
            if (dist > 50) sb.append(", ${fmtKm(dist)} км")
            sb.appendLine()
        }
        sb.appendLine()

        // Weekly table
        sb.appendLine("## По неделям")
        sb.appendLine("| Неделя с | Трен. | Время | TRIMP | Зона 2, мин | Зона 4–5, мин | Подходов | ккал |")
        sb.appendLine("|---|---|---|---|---|---|---|---|")
        list.groupBy { weekStart(it.start) }.toSortedMap().forEach { (ws, ws_list) ->
            val z = IntArray(6) { i -> ws_list.sumOf { it.zoneSec[i] } }
            sb.appendLine(
                "| ${d(ws)} | ${ws_list.size} | ${fmtDuration(ws_list.sumOf { it.activeSec })} | ${ws_list.sumOf { it.trimp }.roundToInt()} | " +
                    "${min(z[2])} | ${min(z[4] + z[5])} | ${ws_list.sumOf { w -> w.segments.sumOf { it.sets.size } }} | ${ws_list.sumOf { it.kcalTotal }.roundToInt()} |"
            )
        }
        sb.appendLine()

        // Recovery trend
        val hrrRows = list.mapNotNull { w ->
            val v = w.segments.flatMap { it.sets }.mapNotNull { it.hrr60 }
            if (v.isEmpty()) null else Triple(w.start, v.average(), v.size)
        }
        if (hrrRows.isNotEmpty()) {
            sb.appendLine("## Восстановление пульса между подходами (падение за 60 с)")
            hrrRows.forEach { (t, avg, n) -> sb.appendLine("- ${d(t)}: в среднем −${avg.roundToInt()} уд/мин ($n подходов)") }
            sb.appendLine()
        }

        morning(sb, hrv.filter { it.time >= from }, "Утренние тесты готовности")

        sb.appendLine("## Все тренировки подробно")
        sb.appendLine()
        list.forEach { session(sb, it, detailed = false) }
        sb.appendLine("_Пульс каждую секунду, все подходы и круги — в приложенных файлах CSV/JSON._")
        return sb.toString()
    }

    // ---------------- Sections ----------------

    private fun dailyTable(sb: StringBuilder, list: List<DailyStats>, title: String) {
        val rows = list.filter { it.sleepMin != null || it.restHr != null || it.steps != null || it.hrvMs != null }
        if (rows.isEmpty()) return
        sb.appendLine("## $title")
        sb.appendLine("| Дата | Сон | Глубокий | REM | Пробужд. | Пульс покоя | ВСР часов, мс | Шаги | SpO2 % | Вес | Жир % | Др. трен., мин |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        fun <T> v(x: T?) = x?.toString() ?: "—"
        rows.sortedBy { it.day }.forEach {
            sb.appendLine(
                "| ${d(it.day)} | ${it.sleepMin?.let { m -> "%d:%02d".format(m / 60, m % 60) } ?: "—"} | ${v(it.deepMin)} | ${v(it.remMin)} | ${v(it.awakeMin)} | " +
                    "${v(it.restHr)} | ${it.hrvMs?.roundToInt() ?: "—"} | ${v(it.steps)} | ${it.spo2?.let { s -> "%.0f".format(s) } ?: "—"} | " +
                    "${it.weightKg?.let { k -> "%.1f".format(Locale.US, k) } ?: "—"} | ${it.bodyFatPct?.let { f -> "%.1f".format(Locale.US, f) } ?: "—"} | ${v(it.otherWorkoutMin)} |"
            )
        }
        sb.appendLine()
    }

    private fun profile(sb: StringBuilder, p: Profile?, bounds: IntArray?, goal: Goal? = null) {
        sb.appendLine("## Профиль")
        if (goal != null) sb.appendLine("- Цель: ${goal.title}")
        if (p != null) {
            val bmi = p.weightKg / ((p.heightCm / 100.0) * (p.heightCm / 100.0))
            sb.appendLine("- ${if (p.male) "Мужчина" else "Женщина"}, ${p.age} лет, ${p.weightKg.roundToInt()} кг, ${p.heightCm} см, ИМТ ${"%.1f".format(Locale.US, bmi)}")
            sb.appendLine("- Пульс покоя: ${p.restHr?.let { "$it уд/мин" } ?: "не измерен"}; макс. пульс: ${Physiology.maxHr(p)} " +
                if (p.maxHrOverride == null) "(формула Танаки 208 − 0,7 × возраст)" else "(задан вручную)")
            sb.appendLine("- Порог для начала следующего подхода: пульс ≤ ${Physiology.readyHr(p)}")
        }
        if (bounds != null && bounds.size == 6) {
            sb.appendLine("- Зоны: " + (1..5).joinToString("; ") { "Зона $it ${bounds[it - 1]}–${bounds[it]}" } +
                if (p?.karvonen == true) " (метод Карвонена)" else " (% от макс. пульса, как в Polar/Samsung; зона 2 = можно говорить фразами)")
        }
        sb.appendLine("- Датчик: нагрудный Polar H10 (если не указано иное), часы Galaxy Watch Ultra 2")
        sb.appendLine()
    }

    private fun session(sb: StringBuilder, w: Workout, detailed: Boolean) {
        sb.appendLine("## ${if (detailed) "Сессия" else dt(w.start) + " — " + w.title}")
        if (detailed) sb.appendLine("- Начало: ${dt(w.start)}, конец ${hm(w.end)}")
        sb.appendLine("- Активное время: ${fmtDuration(w.activeSec)}; источник пульса: ${w.hrSource}")
        sb.appendLine("- Пульс: средний ${w.avgHr}, максимальный ${w.maxHr}; калории ${w.kcalTotal.roundToInt()} (активные ${w.kcalActive.roundToInt()}); TRIMP ${w.trimp.roundToInt()}")
        val z = w.zoneSec
        sb.appendLine("- Время в зонах, мин: " + (1..5).joinToString(", ") { "Зона $it ${min(z[it])}" } + ", ниже З1 ${min(z[0])}")
        sb.appendLine("- Рекомендованный отдых после сессии: ~${w.recoveryHours} ч")
        if (w.segments.size > 1) sb.appendLine("- Упражнения по порядку: " + w.segments.joinToString(" → ") { it.type.title })
        sb.appendLine()

        w.segments.forEachIndexed { i, s ->
            sb.appendLine("### ${i + 1}. ${s.type.title} (${hm(s.start)}–${hm(s.end)})")
            sb.appendLine("- Время ${fmtDuration(s.activeSec)}, пульс ср. ${s.avgHr} / макс. ${s.maxHr}, ${s.kcalTotal.roundToInt()} ккал, TRIMP ${s.trimp.roundToInt()}")
            sb.appendLine("- Зоны, мин: " + (1..5).joinToString(", ") { "Зона $it ${min(s.zoneSec[it])}" })
            if (s.distanceM > 20) {
                val pace = if (s.distanceM > 0) (s.activeSec / (s.distanceM / 1000.0)).toInt() else null
                sb.appendLine("- Дистанция ${fmtKm(s.distanceM)} км, средний темп ${fmtPace(pace)} /км")
            }
            if (s.sets.isNotEmpty()) {
                val rounds = s.type.mode == Mode.ROUNDS
                sb.appendLine()
                sb.appendLine("| ${if (rounds) "Раунд" else "Подход"} | Время | Повторы | Пик пульса | Падение за 60 с | Отдых после |")
                sb.appendLine("|---|---|---|---|---|---|")
                s.sets.forEachIndexed { n, x ->
                    sb.appendLine("| ${n + 1} | ${fmtDuration(((x.end - x.start) / 1000).toInt())} | ${if (x.reps > 0) x.reps else "—"} | ${x.peakHr} | " +
                        "${x.hrr60?.let { "−$it" } ?: "—"} | ${x.restSec?.let { fmtDuration(it) } ?: "—"} |")
                }
            }
            if (s.laps.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("| Круг | Дистанция, м | Время | Темп /км | Ср. пульс |")
                sb.appendLine("|---|---|---|---|---|")
                s.laps.forEachIndexed { n, l ->
                    val sec = ((l.end - l.start) / 1000).toInt()
                    val pace = if (l.distanceM > 0) (sec / (l.distanceM / 1000.0)).toInt() else null
                    val hrs = w.hr.filter { it.t in l.start..l.end }
                    val avg = if (hrs.isEmpty()) "—" else (hrs.sumOf { it.bpm } / hrs.size).toString()
                    sb.appendLine("| ${n + 1} | ${l.distanceM.roundToInt()} | ${fmtDuration(sec)} | ${fmtPace(pace)} | $avg |")
                }
            }
            sb.appendLine()
        }

        if (detailed && w.hr.isNotEmpty()) {
            sb.appendLine("### Пульс по минутам (средний/максимальный)")
            val byMin = w.hr.groupBy { ((it.t - w.start) / 60_000).toInt() }.toSortedMap()
            val line = byMin.entries.joinToString(" ") { (m, v) ->
                "${m + 1}:${v.sumOf { it.bpm } / v.size}/${v.maxOf { it.bpm }}"
            }
            sb.appendLine(line)
            sb.appendLine()
        }
    }

    private fun morning(sb: StringBuilder, list: List<HrvRecord>, title: String) {
        if (list.isEmpty()) return
        sb.appendLine("## $title")
        sb.appendLine("| Дата | ВСР RMSSD, мс | Пульс покоя | Оценка |")
        sb.appendLine("|---|---|---|---|")
        list.sortedBy { it.time }.forEach {
            val st = when (it.status) { 0 -> "зелёный"; 1 -> "жёлтый"; 2 -> "красный"; else -> "база" }
            sb.appendLine("| ${d(it.time)} | ${it.rmssd.roundToInt()} | ${it.restHr} | $st |")
        }
        sb.appendLine()
    }

    private fun weekStart(t: Long): Long {
        val c = Calendar.getInstance(ru)
        c.timeInMillis = t
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return c.timeInMillis
    }

    // ---------------- CSV ----------------

    fun csvWorkouts(list: List<Workout>): String {
        val sb = StringBuilder("workout_id,start,end,segment_no,exercise,active_sec,avg_hr,max_hr,kcal_total,kcal_active,trimp,distance_m,z0_sec,z1_sec,z2_sec,z3_sec,z4_sec,z5_sec,sets,reps,hr_source\n")
        list.forEach { w ->
            w.segments.forEachIndexed { i, s ->
                sb.append(w.id).append(',').append(iso(s.start)).append(',').append(iso(s.end)).append(',').append(i + 1).append(',')
                    .append(s.type.name).append(',').append(s.activeSec).append(',').append(s.avgHr).append(',').append(s.maxHr).append(',')
                    .append("%.1f".format(Locale.US, s.kcalTotal)).append(',').append("%.1f".format(Locale.US, s.kcalActive)).append(',')
                    .append("%.1f".format(Locale.US, s.trimp)).append(',').append(s.distanceM.roundToInt()).append(',')
                    .append(s.zoneSec.joinToString(",")).append(',').append(s.sets.size).append(',').append(s.sets.sumOf { it.reps }).append(',')
                    .append('"').append(w.hrSource).append('"').append('\n')
            }
        }
        return sb.toString()
    }

    fun csvSets(list: List<Workout>): String {
        val sb = StringBuilder("workout_id,exercise,set_no,start,end,duration_sec,reps,peak_hr,hr_drop_60s,rest_after_sec\n")
        list.forEach { w ->
            w.segments.forEach { s ->
                s.sets.forEachIndexed { n, x ->
                    sb.append(w.id).append(',').append(s.type.name).append(',').append(n + 1).append(',')
                        .append(iso(x.start)).append(',').append(iso(x.end)).append(',').append((x.end - x.start) / 1000).append(',')
                        .append(x.reps).append(',').append(x.peakHr).append(',').append(x.hrr60 ?: "").append(',').append(x.restSec ?: "").append('\n')
                }
            }
        }
        return sb.toString()
    }

    fun csvHeartRate(list: List<Workout>): String {
        val sb = StringBuilder("workout_id,time,sec_from_start,exercise,bpm\n")
        list.forEach { w ->
            var si = 0
            w.hr.forEach { h ->
                while (si + 1 < w.segments.size && h.t >= w.segments[si + 1].start) si++
                sb.append(w.id).append(',').append(iso(h.t)).append(',').append((h.t - w.start) / 1000).append(',')
                    .append(w.segments.getOrNull(si)?.type?.name ?: "").append(',').append(h.bpm).append('\n')
            }
        }
        return sb.toString()
    }

    fun csvHrv(list: List<HrvRecord>): String {
        val sb = StringBuilder("time,rmssd_ms,rest_hr,status\n")
        list.forEach { sb.append(iso(it.time)).append(',').append("%.1f".format(Locale.US, it.rmssd)).append(',').append(it.restHr).append(',').append(it.status).append('\n') }
        return sb.toString()
    }
}
