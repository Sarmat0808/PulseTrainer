package fi.sarmat.pulsetrainer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fi.sarmat.pulsetrainer.core.Gpx
import fi.sarmat.pulsetrainer.core.Report
import fi.sarmat.pulsetrainer.core.Workout
import fi.sarmat.pulsetrainer.core.WorkoutJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Share reports for AI analysis through the normal Android share sheet. */
object Share {
    private const val MAX_TEXT = 90_000

    private fun shareDir(ctx: Context): File =
        File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }

    private fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(t))

    private fun allJson(list: List<Workout>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject(WorkoutJson.toJson(it))) }
        val o = JSONObject().put("workouts", arr)
        PhoneStore.profile.value?.let { o.put("profile", JSONObject(WorkoutJson.profileToJson(it))) }
        o.put("morning_tests", JSONArray(WorkoutJson.hrvToJson(PhoneStore.hrv.value)))
        o.put("goal", PhoneStore.goal.value.name)
        val days = JSONArray()
        PhoneStore.days.value.forEach { d ->
            days.put(JSONObject().put("day", d.day).put("sleep_min", d.sleepMin).put("deep_min", d.deepMin).put("rem_min", d.remMin)
                .put("light_min", d.lightMin).put("awake_min", d.awakeMin).put("rest_hr", d.restHr).put("hrv_ms", d.hrvMs)
                .put("steps", d.steps).put("spo2", d.spo2).put("weight_kg", d.weightKg).put("body_fat_pct", d.bodyFatPct)
                .put("other_workout_min", d.otherWorkoutMin))
        }
        o.put("daily_watch_stats", days)
        val ws = JSONArray()
        PhoneStore.weights.value.forEach { ws.put(JSONObject().put("t", it.time).put("kg", it.kg)) }
        o.put("weight_log", ws)
        val body = JSONArray()
        PhoneStore.body.value.forEach {
            body.put(JSONObject().put("t", it.time).put("weight_kg", it.weightKg).put("waist_cm", it.waistCm).put("chest_cm", it.chestCm)
                .put("arm_cm", it.armCm).put("thigh_cm", it.thighCm).put("body_fat_pct", it.bodyFatPct))
        }
        o.put("body_measurements", body)
        val food = JSONArray()
        FoodStore.history(90).filter { it.entries.isNotEmpty() || it.forgot }.forEach { h ->
            val es = JSONArray()
            h.entries.forEach { e -> es.put(JSONObject().put("t", e.time).put("name", e.name).put("amount", e.amount).put("p", e.p).put("f", e.f).put("c", e.c).put("ml", e.drinkMl)) }
            food.put(JSONObject().put("date", h.date.toString()).put("kcal", h.totals.kcal).put("p", h.totals.p).put("f", h.totals.f).put("c", h.totals.c)
                .put("fluid_ml", h.totals.fluidMl).put("forgot", h.forgot).put("target_kcal", h.target.kcal).put("target_p", h.target.p).put("entries", es))
        }
        o.put("nutrition", food)
        return o.toString(1)
    }

    /** One workout: full Markdown report + CSV/JSON (+ GPX if there is a route). */
    fun workout(ctx: Context, w: Workout, withFiles: Boolean) {
        val text = Report.workoutText(w, PhoneStore.profile.value, PhoneStore.hrv.value, PhoneStore.days.value, PhoneStore.goal.value) +
            bodyText(w.start - 30 * 86400_000L) + nutritionText(3)
        if (!withFiles) { sendText(ctx, "PulseTrainer: ${w.title}", text); return }
        val dir = shareDir(ctx)
        val base = "pulsetrainer_${stamp(w.start)}"
        val files = mutableListOf(
            File(dir, "${base}_report.md").apply { writeText(text) },
            File(dir, "${base}_heart_rate.csv").apply { writeText(Report.csvHeartRate(listOf(w))) },
            File(dir, "${base}_sets.csv").apply { writeText(Report.csvSets(listOf(w))) },
            File(dir, "${base}_exercises.csv").apply { writeText(Report.csvWorkouts(listOf(w))) },
            File(dir, "${base}_full.json").apply { writeText(allJson(listOf(w))) },
        )
        if (w.track.size >= 2) files += File(dir, "${base}_route.gpx").apply { writeText(Gpx.build(w)) }
        send(ctx, "PulseTrainer: ${w.title}", text, files)
    }

    /** All workouts for the last [days] days. */
    fun period(ctx: Context, days: Int, withFiles: Boolean) {
        val from = System.currentTimeMillis() - days * 86400_000L
        val list = PhoneStore.workouts.value.filter { it.start >= from }.sortedBy { it.start }
        val text = Report.periodText(list, days, PhoneStore.profile.value, PhoneStore.hrv.value, PhoneStore.days.value, PhoneStore.goal.value, PhoneStore.weights.value) +
            extText(from) + nightText(from) + stressText(from) + readinessText() + bodyText(from) + nutritionText(days)
        if (!withFiles) { sendText(ctx, "PulseTrainer: $days дней", text); return }
        val dir = shareDir(ctx)
        val base = "pulsetrainer_${days}d_${stamp(System.currentTimeMillis())}"
        val files = listOf(
            File(dir, "${base}_report.md").apply { writeText(text) },
            File(dir, "${base}_exercises.csv").apply { writeText(Report.csvWorkouts(list)) },
            File(dir, "${base}_sets.csv").apply { writeText(Report.csvSets(list)) },
            File(dir, "${base}_heart_rate.csv").apply { writeText(Report.csvHeartRate(list)) },
            File(dir, "${base}_morning_tests.csv").apply { writeText(Report.csvHrv(PhoneStore.hrv.value)) },
            File(dir, "${base}_full.json").apply { writeText(allJson(list)) },
        )
        send(ctx, "PulseTrainer: $days дней", text, files)
    }

    /** Workouts from Samsung Health and other apps (with load from their real heart rate). */
    private fun extText(from: Long): String {
        val list = PhoneStore.ext.value.filter { it.start >= from }
        if (list.isEmpty()) return ""
        val f = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))
        val sb = StringBuilder("\n## Тренировки из Samsung Health и других приложений\n| Дата | Вид | Мин | Ср. пульс | Макс | Нагрузка TRIMP | Зона 2–3, мин | Зона 4–5, мин |\n|---|---|---|---|---|---|---|---|\n")
        list.forEach {
            sb.append("| ${f.format(Date(it.start))} | ${it.title} | ${it.minutes} | ${it.avgHr ?: "—"} | ${it.maxHr ?: "—"} | ${it.trimp.toInt()}${if (it.estimated) " (оценка без пульса)" else ""} | " +
                "${(it.zoneSec[2] + it.zoneSec[3]) / 60} | ${(it.zoneSec[4] + it.zoneSec[5]) / 60} |\n")
        }
        return sb.toString()
    }

    /** Night resting pulse collected in the background on the watch. */
    private fun nightText(from: Long): String {
        val list = PhoneStore.passive.value.filter { it.day >= from }
        if (list.isEmpty()) return ""
        val f = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))
        val sb = StringBuilder("\n## Ночной пульс (фоновый сбор на часах)\n| Дата | Пульс покоя | Средний ночью | Шаги |\n|---|---|---|---|\n")
        list.forEach { sb.append("| ${f.format(Date(it.day))} | ${it.restHr ?: "—"} | ${it.nightAvg ?: "—"} | ${it.steps ?: "—"} |\n") }
        return sb.toString()
    }

    private fun stressText(from: Long): String {
        val list = PhoneStore.stress.value.filter { it.time >= from }
        if (list.isEmpty()) return ""
        val f = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))
        val sb = StringBuilder("\n## Замеры стресса (0–100)\n")
        list.forEach { sb.append("- ${f.format(Date(it.time))}: ${it.score} (пульс ${it.hr}${if (it.rmssd > 0) ", ВСР ${it.rmssd.toInt()} мс" else ", по пульсу"})\n") }
        return sb.toString()
    }

    private fun readinessText(): String {
        val a = PhoneStore.advise()
        val c = PhoneStore.todayCheckIn()
        val sb = StringBuilder("\n## Готовность сегодня (оценка приложения)\n")
        sb.append("- ${a.score}/100 · ${fi.sarmat.pulsetrainer.core.Coach.levelText(a)} · ${a.headline}\n")
        if (c != null) sb.append("- Самочувствие ${c.feel}/5, боль в мышцах ${c.soreness}/2\n")
        a.reasons.forEach { sb.append("- $it\n") }
        if (a.missing.isNotEmpty()) sb.append("- Не хватает данных: ${a.missing.joinToString("; ")}\n")
        return sb.toString()
    }

    private fun bodyText(from: Long): String {
        val list = PhoneStore.body.value.filter { it.time >= from - 60 * 86400_000L }
        if (list.isEmpty()) return ""
        val f = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))
        val sb = StringBuilder("\n## Замеры тела\n| Дата | Вес | Талия | Грудь | Бицепс | Бедро | Жир % |\n|---|---|---|---|---|---|---|\n")
        fun v(x: Double?) = x?.let { "%.1f".format(Locale.US, it) } ?: "—"
        list.forEach { sb.append("| ${f.format(Date(it.time))} | ${v(it.weightKg)} | ${v(it.waistCm)} | ${v(it.chestCm)} | ${v(it.armCm)} | ${v(it.thighCm)} | ${v(it.bodyFatPct)} |\n") }
        return sb.toString()
    }

    private fun nutritionText(days: Int): String {
        val hist = FoodStore.history(days).filter { it.entries.isNotEmpty() || it.forgot }
        if (hist.isEmpty()) return ""
        val sb = StringBuilder("\n## Питание по дням (взвешенные продукты)\n")
        sb.append("| Дата | ккал | Белки | Жиры | Углеводы | Жидкость, мл | Норма (ккал/белок) | Итог |\n|---|---|---|---|---|---|---|---|\n")
        hist.sortedBy { it.date }.forEach { h ->
            val st = when {
                h.forgot -> "не внесено"
                fi.sarmat.pulsetrainer.core.Nutrition.reached(h.totals, h.target) -> "норма ✓"
                else -> "недобор"
            }
            sb.append("| ${h.date} | ${h.totals.kcal.toInt()} | ${h.totals.p.toInt()} | ${h.totals.f.toInt()} | ${h.totals.c.toInt()} | ${h.totals.fluidMl} | ${h.target.kcal}/${h.target.p} | $st |\n")
        }
        val recent = hist.sortedByDescending { it.date }.take(3).filter { it.entries.isNotEmpty() }
        recent.forEach { h ->
            sb.append("\n**${h.date}:** ")
            sb.append(h.entries.joinToString("; ") { e ->
                "${e.name} ${if (e.drinkMl > 0) "${e.drinkMl} мл" else "${e.amount.toInt()} г"} (Б ${e.p.toInt()} Ж ${e.f.toInt()} У ${e.c.toInt()})"
            })
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun clip(text: String) = if (text.length <= MAX_TEXT) text
    else text.take(MAX_TEXT) + "\n\n…(отчёт обрезан — полный текст в файле *_report.md)"

    private fun send(ctx: Context, subject: String, text: String, files: List<File>) {
        val uris = ArrayList(files.map { uri(ctx, it) })
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, clip(text))
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            val cd = ClipData.newRawUri(subject, uris.first())
            uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
            clipData = cd
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Отправить отчёт для анализа").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun sendText(ctx: Context, subject: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, clip(text))
        }
        ctx.startActivity(Intent.createChooser(i, "Отправить отчёт (текст)"))
    }

    /** Opens the route in Organic Maps (or any app that opens GPX). */
    fun openRoute(ctx: Context, w: Workout) {
        val dir = shareDir(ctx)
        val f = File(dir, "pulsetrainer_${stamp(w.start)}.gpx").apply { writeText(Gpx.build(w)) }
        val u = uri(ctx, f)
        val base = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(u, "application/gpx+xml")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val organic = Intent(base).setPackage("app.organicmaps")
        try {
            ctx.startActivity(organic)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent.createChooser(base, "Открыть маршрут"))
            } catch (_: Exception) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "Открыть маршрут"))
            }
        }
    }

    /** TCX for Strava / Garmin Connect / TrainingPeaks: save to Drive, send to the Strava app or upload on strava.com. */
    fun exportTcx(ctx: Context, w: Workout) {
        val f = File(shareDir(ctx), "pulsetrainer_${stamp(w.start)}.tcx").apply { writeText(Gpx.tcx(w)) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.garmin.tcx+xml"
            putExtra(Intent.EXTRA_STREAM, uri(ctx, f))
            putExtra(Intent.EXTRA_SUBJECT, "PulseTrainer: ${w.title}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Экспорт тренировки (TCX)"))
    }
}
