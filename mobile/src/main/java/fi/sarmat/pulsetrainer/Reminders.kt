package fi.sarmat.pulsetrainer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fi.sarmat.pulsetrainer.core.Coach
import fi.sarmat.pulsetrainer.core.CoachAdvice
import fi.sarmat.pulsetrainer.core.DayType
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Daily coach notifications: morning plan + evening nudge if the plan was not done. */
object Reminders {
    private const val MORNING = "coach-morning"
    private const val EVENING = "coach-evening"
    const val EVENING_HOUR = 18

    fun advice(): CoachAdvice = PhoneStore.advise()

    /** keep = true on app start (do not disturb an existing schedule); false after settings change. */
    fun schedule(ctx: Context, keep: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        // Hourly check: "now is a good time for the protein drink / bar".
        wm.enqueueUniquePeriodicWork(
            "snack-tip", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SnackWorker>(1, TimeUnit.HOURS).build()
        )
        if (PhoneStore.remindMorning) enqueue(ctx, MORNING, PhoneStore.remindMorningHour, keep) else wm.cancelUniqueWork(MORNING)
        if (PhoneStore.remindEvening) enqueue(ctx, EVENING, EVENING_HOUR, keep) else wm.cancelUniqueWork(EVENING)
    }

    private fun enqueue(ctx: Context, name: String, hour: Int, keep: Boolean) {
        val now = ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("kind" to name))
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            name, if (keep) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE, req
        )
    }

    fun isMorning(kind: String?) = kind == MORNING

    fun notify(ctx: Context, id: Int, title: String, lines: List<String>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("coach", "Советы тренера", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, "coach")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try { PhoneStore.refreshDays(ctx) } catch (_: Exception) {}
        val a = Reminders.advice()
        val kind = inputData.getString("kind")
        if (Reminders.isMorning(kind)) {
            val lines = listOf("Готовность ${a.score}/100 · ${Coach.levelText(a)}") + a.plan.take(3) + a.whenText +
                (if (PhoneStore.todayCheckIn() == null) listOf("Откройте приложение и отметьте самочувствие — оценка станет точнее.") else emptyList())
            Reminders.notify(ctx, 101, "Тренер: ${a.headline}", lines)
        } else {
            val dayStart = ZonedDateTime.now().toLocalDate().atStartOfDay(ZonedDateTime.now().zone).toInstant().toEpochMilli()
            val trained = PhoneStore.workouts.value.any { it.start >= dayStart }
            val steps = PhoneStore.days.value.lastOrNull()?.steps ?: 0L
            when {
                trained -> {}
                a.type == DayType.STRENGTH || a.type == DayType.CARDIO || a.type == DayType.INTERVALS ->
                    Reminders.notify(
                        ctx, 102, "Сегодня ещё не было тренировки",
                        listOf("План: ${a.type.title}. ${a.plan.firstOrNull() ?: ""}", "Если времени мало — хотя бы 20–30 мин быстрой ходьбы в зоне 2.")
                    )
                a.type == DayType.WALK && steps < 6000 ->
                    Reminders.notify(ctx, 103, "Время прогулки", listOf("Сегодня $steps шагов. 30 мин спокойной ходьбы ускорят восстановление."))
            }
        }
        return Result.success()
    }
}


/** Reminds when the protein drink or bar would help to reach the day's norm (max once per 3 h, 09–22). */
class SnackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val hour = java.time.LocalTime.now().hour
        if (hour !in 9..22) return Result.success()
        val prefs = ctx.getSharedPreferences("snack", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0L) < 3 * 3600_000L) return Result.success()
        val today = java.time.LocalDate.now()
        val lastEnd = (PhoneStore.workouts.value.filter { fi.sarmat.pulsetrainer.core.Physiology.isRealWorkout(it) }.map { it.end } +
            PhoneStore.ext.value.filter { it.minutes >= 20 }.map { it.end }).maxOrNull()
        val tip = fi.sarmat.pulsetrainer.core.Nutrition.snackTip(
            FoodStore.foods(), FoodStore.entries(today), FoodStore.targets(today), now, lastEnd, hour,
        ) ?: return Result.success()
        prefs.edit().putLong("last", now).apply()
        Reminders.notify(ctx, 104, tip.title, listOf(tip.why, "Откройте «Еда» → «Выпил / съел — добавить»."))
        return Result.success()
    }
}
