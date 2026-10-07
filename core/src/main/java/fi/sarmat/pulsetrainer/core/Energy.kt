package fi.sarmat.pulsetrainer.core

import kotlin.math.roundToInt

/**
 * Energy 0–100 through the day (the idea of Garmin Body Battery / Samsung Energy score).
 *
 * Morning charge — how well the night restored you:
 *   sleep score (duration + stages), night resting pulse vs your norm, morning HRV test, how you feel.
 * During the day — every heart-rate sample the watch records (Samsung measures all day, more often
 * in workouts) is turned into drain or recharge by intensity relative to your heart-rate reserve:
 *   calm (< 8 % of reserve) recharges slowly, everyday activity/stress drains a little,
 *   workouts drain by their intensity. Gaps without samples count as light daily activity.
 * Rates are tuned so a normal day with a 1-hour moderate workout uses ~50–70 points,
 * close to how the commercial scores behave.
 */
object Energy {

    data class Part(val label: String, val points: Int)

    data class Result(
        val now: Int,
        val morning: Int,
        /** (time, value) every ~15 min since waking, for the day chart. */
        val series: List<Pair<Long, Int>>,
        val parts: List<Part>,
        val label: String,
        val level: Int,
        val advice: String,
        val explain: List<String>,
    )

    fun morningCharge(p: Profile, night: DailyStats?, restToday: Int?, restBase: Double?, test: HrvRecord?, check: CheckIn?): Pair<Int, List<Part>> {
        val parts = ArrayList<Part>()
        val sc = Health.sleepScore(night)?.value
        var v = if (sc != null) 25 + sc * 0.6 else 60.0
        parts += Part(if (sc != null) "Сон (оценка $sc)" else "Сон: нет данных", v.roundToInt())
        if (restToday != null && restBase != null) {
            val d = restToday - restBase
            val adj = when { d >= 6 -> -15; d >= 3 -> -8; d <= -2 -> 5; else -> 0 }
            if (adj != 0) parts += Part("Ночной пульс ${if (d > 0) "выше" else "ниже"} нормы", adj)
            v += adj
        }
        if (test != null && test.status >= 0) {
            val adj = when (test.status) { 0 -> 8; 1 -> -5; else -> -15 }
            parts += Part("Утренний тест", adj); v += adj
        }
        if (check != null) {
            val adj = when (check.feel) { 1 -> -15; 2 -> -8; 3 -> 0; else -> 5 }
            if (adj != 0) parts += Part("Самочувствие", adj)
            v += adj
        }
        return v.roundToInt().coerceIn(5, 100) to parts
    }

    /** Points per minute at this heart rate (negative = drain). */
    private fun rate(hr: Int, rest: Int, max: Int): Double {
        val r = (hr - rest).toDouble() / (max - rest).coerceAtLeast(30)
        return when {
            r < 0.08 -> 0.04
            r < 0.20 -> -0.04
            r < 0.35 -> -0.12
            r < 0.55 -> -0.30
            r < 0.75 -> -0.50
            else -> -0.80
        }
    }

    fun compute(
        p: Profile, wake: Long, now: Long, samples: List<Pair<Long, Int>>, rest: Int,
        morning: Int, morningParts: List<Part>,
    ): Result {
        val max = Physiology.maxHr(p)
        val day = samples.filter { it.first in wake..now }.sortedBy { it.first }
        var v = morning.toDouble()
        var drainWork = 0.0; var drainDay = 0.0; var recharge = 0.0
        val series = ArrayList<Pair<Long, Int>>()
        series += wake to morning
        var t = wake
        var i = 0
        var lastMark = wake
        while (t < now) {
            // next sample defines the rate of this stretch (max 10 min per sample; gaps = light activity)
            while (i < day.size && day[i].first < t) i++
            val next = if (i < day.size) day[i] else null
            val stepEnd = minOf(now, next?.first?.let { if (it > t) it else t + 60_000 } ?: now, t + 10 * 60_000)
            val min = (stepEnd - t) / 60000.0
            val hr = next?.takeIf { it.first - t <= 10 * 60_000 }?.second
            val rt = if (hr != null) rate(hr, rest, max) else -0.04
            val delta = rt * min
            if (delta > 0) recharge += delta else if (rt <= -0.30) drainWork += -delta else drainDay += -delta
            v = (v + delta).coerceIn(0.0, 100.0)
            t = if (stepEnd > t) stepEnd else t + 60_000
            if (t - lastMark >= 15 * 60_000 || t >= now) { series += t to v.roundToInt(); lastMark = t }
        }
        val cur = v.roundToInt()
        val parts = morningParts.toMutableList()
        parts += Part("Утренний заряд", morning)
        if (drainWork >= 1) parts += Part("Тренировки и нагрузка", -drainWork.roundToInt())
        if (drainDay >= 1) parts += Part("Активность и стресс днём", -drainDay.roundToInt())
        if (recharge >= 1) parts += Part("Отдых днём", recharge.roundToInt())
        val (label, level) = when {
            cur >= 70 -> "Много сил" to 0
            cur >= 45 -> "Средне" to 1
            cur >= 25 -> "Мало сил" to 2
            else -> "На исходе" to 2
        }
        val advice = when {
            cur >= 70 -> "Хороший запас — подходящий день для тяжёлой или долгой тренировки."
            cur >= 45 -> "Обычная тренировка подойдёт; без работы до отказа."
            cur >= 25 -> "Сил немного: зона 2 или прогулка, и пораньше спать."
            else -> "Энергия на исходе: отдых, еда, ранний сон. Тренировку лучше перенести."
        }
        val explain = listOf(
            "Утром заряд считается по сну, ночному пульсу, утреннему тесту и самочувствию.",
            "Днём энергия тратится по пульсу: чем выше пульс относительно вашего покоя, тем быстрее расход. В спокойные минуты — медленно восстанавливается.",
            "Пульс берётся из Samsung Health (часы меряют его весь день) и из ваших тренировок PulseTrainer.",
            "Точность растёт, если носить часы ночью, делать утренний тест и отмечать самочувствие.",
        )
        return Result(cur, morning, series, parts, label, level, advice, explain)
    }
}
