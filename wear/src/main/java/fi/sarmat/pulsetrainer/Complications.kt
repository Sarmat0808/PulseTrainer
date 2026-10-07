package fi.sarmat.pulsetrainer

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complications: put PulseTrainer numbers right on your watch face
 * (long-press the face → Customise → Complications → PulseTrainer).
 * Readiness, energy, recovery, sleep — all on one screen.
 */
abstract class PtComplication : SuspendingComplicationDataSourceService() {
    abstract val title: String
    abstract val open: String
    /** value, max, text */
    abstract fun read(): Triple<Float, Float, String>?

    private fun tap(): PendingIntent = PendingIntent.getActivity(
        this, open.hashCode(), Intent(this, MainActivity::class.java).putExtra("open", open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(type: ComplicationType, v: Triple<Float, Float, String>): ComplicationData? {
        val text = PlainComplicationText.Builder(v.third).build()
        val desc = PlainComplicationText.Builder("$title ${v.third}").build()
        val t = PlainComplicationText.Builder(title).build()
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(v.first.coerceIn(0f, v.second), 0f, v.second, desc)
                .setText(text).setTitle(t).setTapAction(tap()).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, desc).setTitle(t).setTapAction(tap()).build()
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, Triple(75f, 100f, "75"))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Storage.ensureInit(this); Passive.init(this)
        val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { read() } catch (_: Exception) { null } }
            ?: Triple(0f, 100f, "—")
        return build(request.complicationType, v)
    }

    companion object {
        fun refreshAll(ctx: Context) {
            listOf(ReadinessComplication::class.java, EnergyComplication::class.java,
                RecoveryComplication::class.java, SleepComplication::class.java).forEach {
                try { ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, it)).requestUpdateAll() } catch (_: Throwable) {}
            }
        }
    }
}

class ReadinessComplication : PtComplication() {
    override val title = "Готов"
    override val open = "ready"
    override fun read() = (Storage.todayCoach() ?: Storage.localCoach())?.let { Triple(it.score.toFloat(), 100f, "${it.score}") }
}

class EnergyComplication : PtComplication() {
    override val title = "Энерг"
    override val open = "ready"
    override fun read() = Storage.todayCoach()?.energy?.takeIf { it >= 0 }?.let { Triple(it.toFloat(), 100f, "$it") }
}

class RecoveryComplication : PtComplication() {
    override val title = "Восст"
    override val open = "ready"
    override fun read() = Storage.todayCoach()?.recH?.takeIf { it >= 0 }?.let {
        Triple((96 - it).coerceAtLeast(0).toFloat(), 96f, if (it == 0) "✓" else "${it}ч")
    }
}

class SleepComplication : PtComplication() {
    override val title = "Сон"
    override val open = "ready"
    override fun read(): Triple<Float, Float, String>? {
        val m = Storage.todayCoach()?.sleepMin?.takeIf { it > 0 } ?: Passive.summaries().lastOrNull()?.let { d ->
            val a = d.sleepStart; val b = d.sleepEnd
            if (a != null && b != null) ((b - a) / 60000).toInt() - (d.awakeMin ?: 0) else null
        } ?: return null
        return Triple(m.toFloat(), 540f, "${m / 60}:${"%02d".format(m % 60)}")
    }
}
