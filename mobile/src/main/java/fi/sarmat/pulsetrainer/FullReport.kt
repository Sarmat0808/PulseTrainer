package fi.sarmat.pulsetrainer

import fi.sarmat.pulsetrainer.core.Coach
import fi.sarmat.pulsetrainer.core.Goal
import fi.sarmat.pulsetrainer.core.Health
import fi.sarmat.pulsetrainer.core.Meals
import fi.sarmat.pulsetrainer.core.Nutrition
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.Report
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * «Всё обо мне» — one report with everything the app knows, for a full review by an AI:
 * profile, how the app calculates the norms, every day (sleep, pulse, steps, training, food, water, weight),
 * weight and body history, all workouts, food by meals.
 */
object FullReport {
    private val ru = Locale("ru")
    private val dFmt = SimpleDateFormat("dd.MM.yyyy", ru)
    private val hmFmt = SimpleDateFormat("HH:mm", ru)
    private fun f1(x: Double) = "%.1f".format(Locale.US, x)

    fun text(days: Int): String {
        val now = System.currentTimeMillis()
        val from = now - days * 86400_000L
        val z = ZoneId.systemDefault()
        val p = PhoneStore.profile.value ?: Profile()
        val goal = PhoneStore.goal.value
        val sb = StringBuilder()
        sb.appendLine("# Полный отчёт обо мне за $days дней (${dFmt.format(Date(from))} — ${dFmt.format(Date(now))})")
        sb.appendLine()
        sb.appendLine("**Запрос к ИИ:** ${Report.PROMPT_FULL}")
        sb.appendLine()
        sb.append(Report.profileText(p, Physiology.zoneBounds(p), goal))
        norms(sb, p, goal)
        today(sb)
        dayByDay(sb, days, z)
        weightAndBody(sb, p)
        foodSummary(sb, days)
        foodByMeals(sb, days)
        val list = PhoneStore.workouts.value.filter { it.start >= from }.sortedBy { it.start }
        sb.append(Report.periodText(list, days, p, PhoneStore.hrv.value, PhoneStore.days.value, goal, PhoneStore.weights.value, onlyWorkouts = true))
        return sb.toString()
    }

    // ---------------- How the app calculates ----------------

    private fun norms(sb: StringBuilder, p: Profile, goal: Goal) {
        val bmr = Physiology.bmr(p).roundToInt()
        val learned = try { PhoneStore.learnEnergy() } catch (_: Exception) { null }
        val t = FoodStore.targets(LocalDate.now())
        val adj = when (goal) { Goal.HYBRID -> "+150"; Goal.MASS -> "+350"; Goal.FAT_LOSS -> "−450"; else -> "0" }
        sb.appendLine("## Как приложение считает мои нормы (проверь)")
        sb.appendLine("- Базовый обмен (Миффлин — Сан Жеор): $bmr ккал/день")
        if (learned?.tdee != null) sb.appendLine("- Реальный расход по дневнику питания и весу (адаптивный, как MacroFactor): ${learned.tdee} ккал/день (по ${learned.daysUsed} дням)")
        else sb.appendLine("- Расход по формуле: базовый × 1,45 в день без тренировки (${(bmr * 1.45).roundToInt()}) и × 1,6 в день тренировки (${(bmr * 1.6).roundToInt()}). " +
            "Реальный расход по дневнику ещё учится: ${learned?.daysUsed ?: 0} из ${learned?.daysNeeded ?: 14} дней с полным дневником.")
        learned?.let { e ->
            e.avgIntake?.let { sb.appendLine("- Средняя калорийность по дневнику (28 дней): $it ккал") }
            e.weeklyChange?.let { sb.appendLine("- Тренд веса (сглаженный): ${if (it >= 0) "+" else ""}${"%.2f".format(Locale.US, it)} кг/неделю") }
            val wn = e.weightNow; val w4 = e.weight4w
            if (wn != null && w4 != null) sb.appendLine("- Прогноз веса через 4 недели при такой еде: ${f1(w4)} кг (сейчас ${f1(wn)})")
        }
        sb.appendLine("- Надбавка под цель «${goal.title}»: $adj ккал")
        sb.appendLine("- Норма сегодня: ${t.kcal} ккал, белок ${t.p} г (${f1(t.p / p.weightKg)} г/кг), жиры ${t.f} г (${f1(t.f / p.weightKg)} г/кг), углеводы ${t.c} г")
        sb.appendLine("- Вода (напитки): база max(вес × 33 мл, 2500 мл) × 0,8 (≈20 % воды приходит с едой) + 1 мл на каждую ккал тренировки. Сегодня ${t.waterMl} мл")
        sb.appendLine("- Калории тренировок: по пульсу, формула Keytel 2005; для силовой часть выше покоя × 0,8 (пульс на силовой завышает расход)")
        sb.appendLine("- Личный коэффициент восстановления (учится по утренним ответам): ×${"%.2f".format(Locale.US, PhoneStore.recoveryFactor)}")
        sb.appendLine()
    }

    // ---------------- Today ----------------

    private fun today(sb: StringBuilder) {
        sb.appendLine("## Состояние сейчас (оценки приложения)")
        try {
            val a = PhoneStore.advise()
            sb.appendLine("- Готовность: ${a.score}/100 · ${Coach.levelText(a)} · ${a.headline}")
            a.plan.forEach { sb.appendLine("  - $it") }
        } catch (_: Exception) {}
        try {
            val r = PhoneStore.recovery()
            sb.appendLine("- Восстановление: ${if (r.hoursLeft == 0) "готов к тяжёлой" else "ещё ~${r.hoursLeft} ч"} (${r.label})")
        } catch (_: Exception) {}
        try {
            val e = PhoneStore.energy()
            sb.appendLine("- Энергия (как Body Battery): сейчас ${e.now}, утром ${e.morning} — ${e.label}")
        } catch (_: Exception) {}
        try {
            val f = PhoneStore.learnForm()
            if (f.hasData) sb.appendLine("- Форма (модель Банистера): фитнес ${f.fitness}, усталость ${f.fatigue}, баланс ${f.form} — ${f.label}")
        } catch (_: Exception) {}
        try {
            val p = PhoneStore.profile.value ?: Profile()
            Health.vo2Report(p, PhoneStore.goal.value, PhoneStore.days.value, PhoneStore.workouts.value, PhoneStore.ext.value, PhoneStore.passive.value)?.let {
                sb.appendLine("- МПК (VO₂max): ${f1(it.value)} ${if (it.measured) "(Samsung Health)" else "(оценка по пульсу покоя)"}" +
                    (it.change?.let { c -> ", изменение ${if (c >= 0) "+" else ""}${f1(c)}" } ?: ""))
            }
        } catch (_: Exception) {}
        try {
            val pic = PhoneStore.insights()
            sb.appendLine("- Разбор тренера приложения: ${pic.headline}")
            pic.items.forEach { sb.appendLine("  - [${listOf("хорошо", "внимание", "важно")[it.level]}] ${it.area}: ${it.title}. ${it.detail} → ${it.action}") }
        } catch (_: Exception) {}
        PhoneStore.todayCheckIn()?.let { sb.appendLine("- Самочувствие сегодня ${it.feel}/5, боль в мышцах ${it.soreness}/2") }
        sb.appendLine()
    }

    // ---------------- Every day in one table ----------------

    private fun dayByDay(sb: StringBuilder, days: Int, z: ZoneId) {
        val stats = PhoneStore.days.value.associateBy { Instant.ofEpochMilli(it.day).atZone(z).toLocalDate() }
        val passive = PhoneStore.passive.value.associateBy { Instant.ofEpochMilli(it.day).atZone(z).toLocalDate() }
        val food = FoodStore.history(days).associateBy { it.date }
        val loads = PhoneStore.dailyLoads(days)
        val weights = PhoneStore.weights.value.groupBy { Instant.ofEpochMilli(it.time).atZone(z).toLocalDate() }
        sb.appendLine("## День за днём")
        sb.appendLine("Сон — по Samsung Health; «Нагрузка» — TRIMP всех тренировок дня (включая Samsung Health); еда и вода — из дневника (пусто = не вносил).")
        sb.appendLine("| Дата | Сон | Глуб./REM мин | Пульс покоя | Шаги | Акт. ккал | Трен. мин | Нагрузка | Съел ккал | Б/Ж/У г | Норма ккал | Вода мл / норма | Вес |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        val today = LocalDate.now()
        for (i in days - 1 downTo 0) {
            val d = today.minusDays(i.toLong())
            val s = stats[d]; val pd = passive[d]; val h = food[d]
            val ate = h != null && h.entries.any { it.drinkMl == 0 }
            val rest = s?.restHr ?: pd?.restHr
            val load = loads.getOrNull(days - 1 - i) ?: 0.0
            val tmin = FoodStore.trainingMinutes(d)
            if (s == null && pd == null && !ate && tmin == 0 && weights[d] == null) continue
            val sleep = s?.sleepMin?.let { "%d:%02d".format(it / 60, it % 60) } ?: "—"
            val stages = if (s?.deepMin != null || s?.remMin != null) "${s.deepMin ?: "—"}/${s.remMin ?: "—"}" else "—"
            val kg = weights[d]?.lastOrNull()?.kg ?: s?.weightKg
            sb.appendLine(
                "| ${d.dayOfMonth.toString().padStart(2, '0')}.${d.monthValue.toString().padStart(2, '0')} ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, ru)} | $sleep | $stages | ${rest ?: "—"} | ${s?.steps ?: "—"} | " +
                    "${s?.activeKcal?.roundToInt() ?: "—"} | ${if (tmin > 0) tmin else "—"} | ${if (load > 0) load.roundToInt() else "—"} | " +
                    "${if (ate) h!!.totals.kcal.roundToInt() else "—"} | ${if (ate) "${h!!.totals.p.roundToInt()}/${h.totals.f.roundToInt()}/${h.totals.c.roundToInt()}" else "—"} | " +
                    "${h?.target?.kcal ?: "—"} | ${if (h != null && h.totals.fluidMl > 0) "${h.totals.fluidMl} / ${h.target.waterMl}" else "—"} | ${kg?.let { f1(it) } ?: "—"} |"
            )
        }
        sb.appendLine("_Сегодняшний день ещё не закончен._")
        sb.appendLine()
    }

    // ---------------- Weight and body ----------------

    private fun weightAndBody(sb: StringBuilder, p: Profile) {
        val ws = PhoneStore.weights.value.sortedBy { it.time }
        if (ws.isNotEmpty()) {
            sb.appendLine("## Вес — вся история")
            sb.appendLine(ws.joinToString("; ") { "${dFmt.format(Date(it.time))}: ${f1(it.kg)}" })
            val first = ws.first(); val last = ws.last()
            val weeks = (last.time - first.time) / (7 * 86400_000.0)
            if (weeks >= 1) sb.appendLine("Итого: ${if (last.kg >= first.kg) "+" else ""}${f1(last.kg - first.kg)} кг за ${weeks.roundToInt()} нед. " +
                "(${"%.2f".format(Locale.US, (last.kg - first.kg) / weeks)} кг/нед.)")
            sb.appendLine()
        }
        val body = PhoneStore.body.value.filter { it.waistCm != null || it.chestCm != null || it.armCm != null || it.bodyFatPct != null || it.neckCm != null }
            .sortedBy { it.time }
        if (body.isNotEmpty()) {
            fun v(x: Double?) = x?.let { f1(it) } ?: "—"
            sb.appendLine("## Замеры тела (см)")
            sb.appendLine("| Дата | Вес | Талия | Грудь | Бицепс | Бедро | Шея | Икры | Жир % |")
            sb.appendLine("|---|---|---|---|---|---|---|---|---|")
            body.forEach {
                val fat = it.fatOrEstimate(p.heightCm, p.male)?.let { (x, est) -> f1(x) + if (est) " (по замерам, ±3–4 %)" else "" } ?: "—"
                sb.appendLine("| ${dFmt.format(Date(it.time))} | ${v(it.weightKg)} | ${v(it.waistCm)} | ${v(it.chestCm)} | ${v(it.armCm)} | ${v(it.thighCm)} | ${v(it.neckCm)} | ${v(it.calfCm)} | $fat |")
            }
            val samsungFat = PhoneStore.days.value.filter { it.bodyFatPct != null }.takeLast(5)
            if (samsungFat.isNotEmpty()) sb.appendLine("Жир по весам/часам Samsung (биоимпеданс): " +
                samsungFat.joinToString("; ") { "${dFmt.format(Date(it.day))} ${f1(it.bodyFatPct!!)} %" })
            sb.appendLine()
        }
    }

    // ---------------- Food ----------------

    private fun foodSummary(sb: StringBuilder, days: Int) {
        val p = PhoneStore.profile.value ?: Profile()
        val full = FoodStore.history(days).filter { !it.forgot && it.date != LocalDate.now() && it.totals.kcal >= 1200 }
        if (full.isEmpty()) return
        val n = full.size
        val kcal = full.sumOf { it.totals.kcal } / n
        val pr = full.sumOf { it.totals.p } / n
        val fat = full.sumOf { it.totals.f } / n
        val c = full.sumOf { it.totals.c } / n
        val fl = full.sumOf { it.totals.fluidMl } / n
        val tg = full.sumOf { it.target.kcal } / n
        val tw = full.sumOf { it.target.waterMl } / n
        sb.appendLine("## Питание — в среднем за $n полных дней")
        sb.appendLine("- Калории ${kcal.roundToInt()} при норме ~$tg (${if (kcal >= tg) "+" else ""}${(kcal - tg).roundToInt()} ккал/день)")
        sb.appendLine("- Белок ${pr.roundToInt()} г (${f1(pr / p.weightKg)} г/кг), жиры ${fat.roundToInt()} г (${f1(fat / p.weightKg)} г/кг), углеводы ${c.roundToInt()} г (${f1(c / p.weightKg)} г/кг)")
        sb.appendLine("- Вода (напитки) ${fl} мл при норме ~$tw мл")
        sb.appendLine("- Дней с нормой (ккал и белок ≥ 95 %): ${full.count { Nutrition.reached(it.totals, it.target) }} из $n")
        // Meal timing: first and last food of the day.
        val firsts = ArrayList<Int>(); val lasts = ArrayList<Int>()
        full.forEach { h ->
            val foods = h.entries.filter { it.drinkMl == 0 }.map { FoodStore.minuteOf(it.time) }
            if (foods.isNotEmpty()) { firsts += foods.min(); lasts += foods.max() }
        }
        fun hm(m: Int) = "%d:%02d".format(m / 60, m % 60)
        if (firsts.isNotEmpty()) sb.appendLine("- Первый приём пищи в среднем в ${hm(firsts.average().roundToInt())}, последний в ${hm(lasts.average().roundToInt())}")
        sb.appendLine()
    }

    private fun foodByMeals(sb: StringBuilder, days: Int) {
        val hist = FoodStore.history(days).filter { it.entries.isNotEmpty() }.sortedBy { it.date }
        if (hist.isEmpty()) return
        sb.appendLine("## Питание по дням и приёмам пищи")
        hist.forEach { h ->
            sb.appendLine("**${h.date}** — ${h.totals.kcal.roundToInt()} ккал, Б ${h.totals.p.roundToInt()} / Ж ${h.totals.f.roundToInt()} / У ${h.totals.c.roundToInt()}, напитки ${h.totals.fluidMl} мл (норма ${h.target.kcal} ккал, белок ${h.target.p} г, вода ${h.target.waterMl} мл)")
            h.entries.groupBy { FoodStore.mealOf(it) }.toSortedMap(compareBy { Meals.ORDER.indexOf(it) }).forEach { (m, es) ->
                val items = es.sortedBy { it.time }.joinToString("; ") { e ->
                    "${hmFmt.format(Date(e.time))} ${e.name} ${if (e.drinkMl > 0) "${e.drinkMl} мл" else "${e.amount.roundToInt()} г"}" +
                        if (e.kcal >= 1) " (${e.kcal.roundToInt()} ккал, Б ${e.p.roundToInt()})" else ""
                }
                sb.appendLine("- ${Meals.NAMES.getOrElse(m) { "Другое" }}: $items")
            }
            sb.appendLine()
        }
    }

    // ---------------- CSV ----------------

    fun dailyCsv(days: Int): String {
        val z = ZoneId.systemDefault()
        val stats = PhoneStore.days.value.associateBy { Instant.ofEpochMilli(it.day).atZone(z).toLocalDate() }
        val passive = PhoneStore.passive.value.associateBy { Instant.ofEpochMilli(it.day).atZone(z).toLocalDate() }
        val food = FoodStore.history(days).associateBy { it.date }
        val loads = PhoneStore.dailyLoads(days)
        val weights = PhoneStore.weights.value.groupBy { Instant.ofEpochMilli(it.time).atZone(z).toLocalDate() }
        val sb = StringBuilder("date,sleep_min,deep_min,rem_min,awake_min,rest_hr,night_avg_hr,steps,active_kcal,distance_m,training_min,training_load_trimp," +
            "food_kcal,protein_g,fat_g,carbs_g,drinks_ml,target_kcal,target_protein_g,target_water_ml,weight_kg,body_fat_pct,spo2,vo2max\n")
        fun n(x: Any?) = x?.toString() ?: ""
        val today = LocalDate.now()
        for (i in days - 1 downTo 0) {
            val d = today.minusDays(i.toLong())
            val s = stats[d]; val pd = passive[d]; val h = food[d]
            val ate = h != null && h.entries.isNotEmpty()
            sb.append(d).append(',').append(n(s?.sleepMin)).append(',').append(n(s?.deepMin)).append(',').append(n(s?.remMin)).append(',')
                .append(n(s?.awakeMin)).append(',').append(n(s?.restHr ?: pd?.restHr)).append(',').append(n(pd?.nightAvg)).append(',')
                .append(n(s?.steps)).append(',').append(n(s?.activeKcal?.roundToInt())).append(',').append(n(s?.distanceM?.roundToInt())).append(',')
                .append(FoodStore.trainingMinutes(d)).append(',').append((loads.getOrNull(days - 1 - i) ?: 0.0).roundToInt()).append(',')
                .append(if (ate) h!!.totals.kcal.roundToInt().toString() else "").append(',')
                .append(if (ate) h!!.totals.p.roundToInt().toString() else "").append(',')
                .append(if (ate) h!!.totals.f.roundToInt().toString() else "").append(',')
                .append(if (ate) h!!.totals.c.roundToInt().toString() else "").append(',')
                .append(if (ate) h!!.totals.fluidMl.toString() else "").append(',')
                .append(n(h?.target?.kcal)).append(',').append(n(h?.target?.p)).append(',').append(n(h?.target?.waterMl)).append(',')
                .append(n((weights[d]?.lastOrNull()?.kg ?: s?.weightKg)?.let { f1(it) })).append(',').append(n(s?.bodyFatPct?.let { f1(it) })).append(',')
                .append(n(s?.spo2?.roundToInt())).append(',').append(n(s?.vo2max?.let { f1(it) })).append('\n')
        }
        return sb.toString()
    }

    fun foodCsv(days: Int): String {
        val sb = StringBuilder("date,time,meal,food,amount_g_or_ml,kcal,protein_g,fat_g,carbs_g,drink_ml\n")
        FoodStore.history(days).sortedBy { it.date }.forEach { h ->
            h.entries.sortedBy { it.time }.forEach { e ->
                sb.append(h.date).append(',').append(hmFmt.format(Date(e.time))).append(',')
                    .append(Meals.NAMES.getOrElse(FoodStore.mealOf(e)) { "" }).append(",\"").append(e.name.replace("\"", "'")).append("\",")
                    .append(e.amount.roundToInt()).append(',').append(e.kcal.roundToInt()).append(',').append(f1(e.p)).append(',')
                    .append(f1(e.f)).append(',').append(f1(e.c)).append(',').append(e.drinkMl).append('\n')
            }
        }
        return sb.toString()
    }
}
