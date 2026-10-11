package fi.sarmat.pulsetrainer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fi.sarmat.pulsetrainer.core.DayTotals
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * «Пора выпить воды»: reminders spread evenly from your start time to your end time.
 * The daily norm comes from the profile (weight, age, sex) plus today's training.
 * Every reminder recalculates: how much you've drunk vs. how much should be drunk by now, and how much
 * to drink per reminder for the rest of the day. Ahead of schedule — it stays silent.
 * Notifications appear on the watch too (Android shows phone notifications there), with a
 * «Выпил» button that logs the water right away.
 */
object Water {
    private const val WORK = "water-reminder"
    private const val CH = "water"
    const val NOTIF_ID = 120
    const val ACTION_DRANK = "fi.sarmat.pulsetrainer.WATER_DRANK"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pt", Context.MODE_PRIVATE)
    fun on(ctx: Context) = prefs(ctx).getBoolean("waterOn", false)
    fun from(ctx: Context) = prefs(ctx).getInt("waterFrom", 9 * 60)
    fun to(ctx: Context) = prefs(ctx).getInt("waterTo", 21 * 60)
    fun every(ctx: Context) = prefs(ctx).getInt("waterEvery", 90)

    fun set(ctx: Context, on: Boolean = on(ctx), from: Int = from(ctx), to: Int = to(ctx), every: Int = every(ctx)) {
        prefs(ctx).edit().putBoolean("waterOn", on).putInt("waterFrom", from).putInt("waterTo", maxOf(to, from + 60))
            .putInt("waterEvery", every.coerceIn(30, 240)).apply()
        schedule(ctx)
    }

    fun target(): Int = FoodStore.targets(LocalDate.now()).waterMl
    fun drunk(): Int = DayTotals.of(FoodStore.entries(LocalDate.now())).fluidMl

    /**
     * Reminder times, minutes after midnight: spread evenly so the first is at the start time and the
     * last exactly at the end time (the interval is adjusted a little to fit, e.g. 90 → 93 min).
     */
    fun slots(ctx: Context): List<Int> {
        val a = from(ctx); val b = to(ctx)
        val n = (Math.round((b - a).toDouble() / every(ctx)).toInt() + 1).coerceAtLeast(2)
        return (0 until n).map { a + ((b - a) * it.toDouble() / (n - 1)).roundToInt() }
    }

    /** How much should be drunk by this minute of the day (even pace over the window). */
    fun dueBy(ctx: Context, minute: Int): Int {
        val a = from(ctx); val b = to(ctx)
        val f = ((minute - a).toDouble() / (b - a).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return (target() * f).toInt()
    }

    /** Per reminder for the rest of the day, ml (rounded to 50, 150–500). */
    fun portion(ctx: Context, minute: Int): Int {
        val left = (target() - drunk()).coerceAtLeast(0)
        val slotsLeft = slots(ctx).count { it >= minute - 5 }.coerceAtLeast(1)
        return ((left.toDouble() / slotsLeft / 10).roundToInt() * 10).coerceIn(100, 600)
    }

    fun planText(ctx: Context): String {
        val sl = slots(ctx); val n = sl.size
        val per = ((target().toDouble() / n / 10).roundToInt() * 10)
        val step = (sl.last() - sl.first()) / (n - 1)
        return "Норма ${target()} мл → $n раз по ~$per мл, каждые ~$step мин с ${Reminders.hm(from(ctx))} до ${Reminders.hm(to(ctx))}" +
            "\nРаспорядок: " + sl.joinToString(" · ") { Reminders.hm(it) }
    }

    fun schedule(ctx: Context, fromWorker: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        if (!on(ctx)) { wm.cancelUniqueWork(WORK); return }
        val now = ZonedDateTime.now()
        val nowMin = now.hour * 60 + now.minute
        val next = slots(ctx).firstOrNull { it > nowMin }
        val at = if (next != null) now.withHour(next / 60).withMinute(next % 60).withSecond(0).withNano(0)
        else now.plusDays(1).withHour(from(ctx) / 60).withMinute(from(ctx) % 60).withSecond(0).withNano(0)
        val req = OneTimeWorkRequestBuilder<WaterWorker>()
            .setInitialDelay(Duration.between(now, at).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork(WORK, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, req)
    }

    /** Where reminders go: 0 = watch (phone if the watch isn't reachable), 1 = phone, 2 = both. */
    fun where(ctx: Context) = prefs(ctx).getInt("waterWhere", 0)
    fun setWhere(ctx: Context, w: Int) { prefs(ctx).edit().putInt("waterWhere", w).apply() }
    const val ACTION_SNOOZE = "fi.sarmat.pulsetrainer.WATER_SNOOZE"
    const val SNOOZE_MIN = 30

    /** «Отложить»: one extra reminder in 30 min; its portion is recalculated then (the skipped water is added). */
    fun snooze(ctx: Context, min: Int = SNOOZE_MIN) {
        val req = OneTimeWorkRequestBuilder<WaterWorker>().setInitialDelay(min.toLong(), TimeUnit.MINUTES)
            .setInputData(androidx.work.workDataOf("snooze" to true)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("water-snooze", ExistingWorkPolicy.REPLACE, req)
    }

    /** Logs water and tells the watch how much is left. */
    suspend fun drank(ctx: Context, ml: Int) {
        val water = FoodStore.foods().firstOrNull { it.id == "water" } ?: return
        FoodStore.add(LocalDate.now(), water, ml.toDouble())
        sendState(ctx, "✓ +$ml мл")
    }

    private fun nextText(ctx: Context): String {
        val now = ZonedDateTime.now(); val m = now.hour * 60 + now.minute
        return slots(ctx).firstOrNull { it > m }?.let { "следующее в ${Reminders.hm(it)}" } ?: "на сегодня всё"
    }

    suspend fun sendState(ctx: Context, head: String) {
        val drunk = drunk(); val target = target()
        val js = org.json.JSONObject().put("head", head).put("drunk", drunk).put("target", target)
            .put("left", (target - drunk).coerceAtLeast(0)).put("next", nextText(ctx)).toString()
        try {
            PhoneStore.watchNodes(ctx).forEach {
                com.google.android.gms.wearable.Wearable.getMessageClient(ctx)
                    .sendMessage(it.id, fi.sarmat.pulsetrainer.core.Protocol.PATH_WATER_STATE, js.toByteArray()).await()
            }
        } catch (_: Exception) {}
    }

    suspend fun remind(ctx: Context, snoozed: Boolean = false) {
        val now = ZonedDateTime.now()
        val m = now.hour * 60 + now.minute
        if (!snoozed && (m < from(ctx) - 5 || m > to(ctx) + 5)) return
        val drunk = drunk(); val target = target(); val due = dueBy(ctx, m)
        if (drunk >= target) return                    // norm reached — no more reminders today
        if (!snoozed && drunk >= due + 150) return     // well ahead of schedule — stay quiet
        val ml = portion(ctx, m)
        val behind = (due - drunk).coerceAtLeast(0)
        val text = "Выпито $drunk из $target мл" + if (behind >= 100) " · отстаёте на $behind мл" else " · по графику"

        // 1) The watch: its own notification with «Выпил» and «Отложить».
        var onWatch = false
        if (where(ctx) != 1) try {
            val js = org.json.JSONObject().put("ml", ml).put("text", text).put("drunk", drunk).put("target", target)
                .put("left", target - drunk).put("snooze", SNOOZE_MIN).toString()
            PhoneStore.watchNodes(ctx).forEach {
                com.google.android.gms.wearable.Wearable.getMessageClient(ctx)
                    .sendMessage(it.id, fi.sarmat.pulsetrainer.core.Protocol.PATH_WATER, js.toByteArray()).await()
                onWatch = true
            }
        } catch (_: Exception) {}
        if (onWatch && where(ctx) == 0) return

        // 2) The phone (always if chosen, or when the watch can't be reached).
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Вода", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, NOTIF_ID, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val drank = PendingIntent.getBroadcast(ctx, NOTIF_ID + 1,
            Intent(ctx, WaterReceiver::class.java).setAction(ACTION_DRANK).putExtra("ml", ml),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val later = PendingIntent.getBroadcast(ctx, NOTIF_ID + 2,
            Intent(ctx, WaterReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("💧 Выпейте ~$ml мл воды")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\nДо ${Reminders.hm(to(ctx))} осталось ${target - drunk} мл."))
            .setContentIntent(open)
            .addAction(0, "Выпил $ml мл", drank)
            .addAction(0, "Отложить ${SNOOZE_MIN} мин", later)
            .setAutoCancel(true)
            .setLocalOnly(onWatch)   // don't show a second copy on the watch
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }
}

class WaterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val snoozed = inputData.getBoolean("snooze", false)
        try { Water.remind(applicationContext, snoozed) } catch (_: Exception) {}
        if (!snoozed) Water.schedule(applicationContext, fromWorker = true)
        return Result.success()
    }
}

/** «Выпил» / «Отложить» on the phone notification. */
class WaterReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            Water.ACTION_DRANK -> {
                val ml = intent.getIntExtra("ml", 250)
                val done = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try { Water.drank(app, ml) } catch (_: Exception) {}
                    done.finish()
                }
                Toast.makeText(ctx, "💧 +$ml мл", Toast.LENGTH_SHORT).show()
            }
            Water.ACTION_SNOOZE -> {
                Water.snooze(app)
                Toast.makeText(ctx, "Напомню через ${Water.SNOOZE_MIN} мин", Toast.LENGTH_SHORT).show()
            }
        }
        ctx.getSystemService(NotificationManager::class.java)?.cancel(Water.NOTIF_ID)
    }
}
