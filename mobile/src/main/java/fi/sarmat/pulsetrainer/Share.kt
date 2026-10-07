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
        return o.toString(1)
    }

    /** One workout: full Markdown report + CSV/JSON (+ GPX if there is a route). */
    fun workout(ctx: Context, w: Workout, withFiles: Boolean) {
        val text = Report.workoutText(w, PhoneStore.profile.value, PhoneStore.hrv.value, PhoneStore.days.value, PhoneStore.goal.value)
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
        val text = Report.periodText(list, days, PhoneStore.profile.value, PhoneStore.hrv.value, PhoneStore.days.value, PhoneStore.goal.value, PhoneStore.weights.value)
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
}
