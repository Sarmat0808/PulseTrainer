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
    /** Usual wake-up time (median of the last 7 nights), minutes after midnight; 7:00 if unknown. */
    fun usualWake(): Int {
        val z = java.time.ZoneId.systemDefault()
        val ends = PhoneStore.days.value.takeLast(7).mapNotNull { it.sleepEnd }
            .map { java.time.Instant.ofEpochMilli(it).atZone(z).let { t -> t.hour * 60 + t.minute } }.sorted()
        return if (ends.size >= 3) ends[ends.size / 2] else 7 * 60
    }

    /**
     * Recommended bedtime: usual wake-up minus 8 h of sleep (+15 min to fall asleep);
     * after a short night (< 6.5 h) — 30 minutes earlier to pay back the debt.
     */
    fun bedtime(): Int {
        val last = PhoneStore.days.value.lastOrNull { it.sleepMin != null }?.sleepMin
        var m = usualWake() - 8 * 60 - 15
        if (last != null && last < 390) m -= 30
        return ((m % 1440) + 1440) % 1440
    }

    fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** The evening report comes 45 minutes before bedtime (re-planned every day). */
    fun scheduleBedtime(ctx: Context, fromWorker: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        if (!PhoneStore.remindBedtime) { wm.cancelUniqueWork("bedtime"); return }
        val now = ZonedDateTime.now()
        val at = (bedtime() - 45 + 1440) % 1440
        var next = now.withHour(at / 60).withMinute(at % 60).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val req = androidx.work.OneTimeWorkRequestBuilder<BedtimeWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build()
        // From inside the worker REPLACE would cancel the running job itself; append the next one instead.
        wm.enqueueUniqueWork("bedtime",
            if (fromWorker) androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE else androidx.work.ExistingWorkPolicy.REPLACE, req)
    }

    fun schedule(ctx: Context, keep: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        scheduleBedtime(ctx)
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
            // Morning report: how you slept, what it means, what to do today.
            val night = PhoneStore.days.value.lastOrNull { it.sleepMin != null }
            val sc = fi.sarmat.pulsetrainer.core.Health.sleepScore(night)
            val en = try { PhoneStore.energy() } catch (_: Exception) { null }
            val rec = try { PhoneStore.recovery() } catch (_: Exception) { null }
            val sleepLine = night?.sleepMin?.let { m ->
                "Сон ${m / 60} ч ${m % 60} мин" + (sc?.let { " · оценка ${it.value} (${it.label.lowercase()})" } ?: "") +
                    (night.deepMin?.let { " · глубокий $it мин" } ?: "") + (night.restHr?.let { " · пульс ночью $it" } ?: "")
            } ?: "Сон: нет данных — спите с часами"
            val lines = listOf(sleepLine,
                "Готовность ${a.score}/100 · ${Coach.levelText(a)}" + (en?.let { " · энергия ${it.now}" } ?: ""),
                rec?.takeIf { it.hoursLeft > 0 }?.let { "Восстановление: ещё ${it.hoursLeft} ч" } ?: "Восстановлен — можно тренироваться") +
                a.plan.take(2) + a.whenText +
                (if (PhoneStore.todayCheckIn() == null) listOf("Отметьте самочувствие в приложении — оценка станет точнее.") else emptyList())
            Reminders.notify(ctx, 101, "Доброе утро · ${a.headline}", lines)
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


/**
 * Evening report 45 min before the recommended bedtime: when to go to sleep, how the day went,
 * what helps tonight's sleep. Re-schedules itself for the next day.
 */
class BedtimeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try { PhoneStore.refreshDays(ctx) } catch (_: Exception) {}
        val bed = Reminders.bedtime()
        val today = PhoneStore.days.value.lastOrNull()
        val day0 = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val trained = PhoneStore.workouts.value.filter { it.start >= day0 && fi.sarmat.pulsetrainer.core.Physiology.isRealWorkout(it) }
        val en = try { PhoneStore.energy() } catch (_: Exception) { null }
        val food = fi.sarmat.pulsetrainer.core.DayTotals.of(FoodStore.entries(java.time.LocalDate.now()))
        val target = FoodStore.targets(java.time.LocalDate.now())
        val lines = ArrayList<String>()
        lines += "Лечь в ${Reminders.hm(bed)}, подъём ~${Reminders.hm(Reminders.usualWake())} → 8 ч сна"
        lines += "День: ${today?.steps ?: 0} шагов" + (if (trained.isNotEmpty()) " · тренировка ${trained.sumOf { it.activeSec } / 60} мин" else " · без тренировки") +
            (en?.let { " · энергия ${it.now}" } ?: "")
        if (food.p < target.p - 20) lines += "Белка не хватает ${(target.p - food.p).toInt()} г — лёгкий белковый перекус (творог, кефир)"
        lines += "Без кофеина и тяжёлой еды до сна, экран — потише, в комнате прохладно"
        Reminders.notify(ctx, 105, "Скоро спать — в ${Reminders.hm(bed)}", lines)
        Reminders.scheduleBedtime(ctx, fromWorker = true)
        return Result.success()
    }
}
