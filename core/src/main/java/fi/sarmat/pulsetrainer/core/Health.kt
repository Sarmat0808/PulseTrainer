package fi.sarmat.pulsetrainer.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PulseTrainer's own scores (Samsung's proprietary scores are not shared with other apps).
 */
object Health {

    data class Score(val value: Int, val label: String, val level: Int /* 0 good, 1 mid, 2 low */)

    /**
     * Sleep score 0–100: duration (50 %), deep sleep share (20 %), REM share (20 %), awake time (10 %).
     * Targets: 7–9 h, deep ≥15 %, REM ≥20 %, awake ≤5 %.
     */
    fun sleepScore(d: DailyStats?): Score? {
        if (d == null) return null
        val total = d.sleepMin ?: return null
        if (total <= 0) return null
        val h = total / 60.0
        val dur = when { h >= 7 && h <= 9.5 -> 1.0; h < 7 -> max(0.0, (h - 4) / 3); else -> max(0.6, 1 - (h - 9.5) / 3) }
        val deep = d.deepMin?.let { min(1.0, it.toDouble() / total / 0.15) } ?: 0.8
        val rem = d.remMin?.let { min(1.0, it.toDouble() / total / 0.20) } ?: 0.8
        val awake = d.awakeMin?.let { max(0.0, 1 - max(0.0, it.toDouble() / (total + it) - 0.05) * 5) } ?: 0.8
        val v = ((dur * 50 + deep * 20 + rem * 20 + awake * 10)).roundToInt().coerceIn(0, 100)
        return Score(v, when { v >= 80 -> "Отлично"; v >= 65 -> "Хорошо"; v >= 50 -> "Удовлетворительно"; else -> "Плохо" },
            when { v >= 75 -> 0; v >= 55 -> 1; else -> 2 })
    }

    /** VO2max estimate from resting and max heart rate (Uth et al. 2004): 15.3 × HRmax / HRrest. */
    fun vo2Estimate(p: Profile, restHr: Int?): Double? {
        val r = restHr ?: p.restHr ?: return null
        if (r !in 35..100) return null
        return 15.3 * Physiology.maxHr(p) / r
    }

    /** Fitness level for age and sex (rough ACSM-style norms). */
    fun vo2Level(p: Profile, v: Double): Score {
        val decade = (p.age / 10).coerceIn(2, 6)
        val male = when (decade) {
            2 -> intArrayOf(37, 42, 46, 51); 3 -> intArrayOf(35, 40, 44, 49); 4 -> intArrayOf(33, 38, 42, 47)
            5 -> intArrayOf(30, 35, 39, 43); else -> intArrayOf(27, 31, 35, 40)
        }
        val t = if (p.male) male else male.map { it - 6 }.toIntArray()
        val (label, level) = when {
            v < t[0] -> "Низкий" to 2
            v < t[1] -> "Ниже среднего" to 2
            v < t[2] -> "Средний" to 1
            v < t[3] -> "Хороший" to 0
            else -> "Отличный" to 0
        }
        return Score(v.roundToInt(), label, level)
    }

    /**
     * Heart strengthening for the last 7 days, WHO style: minutes in zones 2–3 count once,
     * zones 4–5 count double. Goal 150 per week.
     */
    fun heartMinutes(workouts: List<Workout>, otherMin: Int, now: Long = System.currentTimeMillis()): Int {
        val week = workouts.filter { it.start > now - 7 * 86400_000L }
        val mod = week.sumOf { it.zoneSec[2] + it.zoneSec[3] } / 60
        val vig = week.sumOf { it.zoneSec[4] + it.zoneSec[5] } / 60
        return mod + 2 * vig + otherMin / 2
    }

    /** Cardio load: this week's TRIMP vs your 4-week average. */
    fun cardioLoad(workouts: List<Workout>, now: Long = System.currentTimeMillis()): Triple<Int, Int, String> {
        val week = workouts.filter { it.start > now - 7 * 86400_000L }.sumOf { it.trimp }.roundToInt()
        val avg = (workouts.filter { it.start > now - 28 * 86400_000L }.sumOf { it.trimp } / 4).roundToInt()
        val label = when {
            avg < 30 -> if (week > 0) "Набираем базу" else "Нет нагрузки"
            week < avg * 0.7 -> "Ниже обычного"
            week <= avg * 1.3 -> "В норме"
            week <= avg * 1.5 -> "Немного выше"
            else -> "Слишком высокая"
        }
        return Triple(week, avg, label)
    }

    fun bmi(p: Profile): Double = p.weightKg / ((p.heightCm / 100.0) * (p.heightCm / 100.0))

    fun bmiLabel(b: Double) = when { b < 18.5 -> "Недостаток веса"; b < 25 -> "Норма"; b < 30 -> "Избыток веса"; else -> "Ожирение" }

    fun spo2Label(v: Double) = when { v >= 95 -> "Норма"; v >= 92 -> "Немного снижен"; else -> "Низкий — обратитесь к врачу при симптомах" }

    fun bpLabel(s: Int, d: Int) = when {
        s >= 140 || d >= 90 -> "Высокое — обсудите с врачом"
        s >= 130 || d >= 85 -> "Повышенное"
        s < 90 || d < 60 -> "Пониженное"
        else -> "Норма"
    }
}
