package fi.sarmat.pulsetrainer.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * All training science lives here.
 *
 * - Max HR: Tanaka et al. 2001, HRmax = 208 - 0.7 * age (unless measured).
 * - Zones: 5 zones at 50/60/70/80/90/100 %. If resting HR is known, Karvonen
 *   (heart-rate reserve) is used, which fits the individual better.
 * - Calories: Keytel et al. 2005 heart-rate equations.
 * - Load: Edwards TRIMP (minutes in zone x zone number).
 * - Readiness: ln(RMSSD) vs. personal 7-day baseline (Plews et al. 2012 approach).
 */
object Physiology {

    val ZONE_NAMES = arrayOf("Покой", "З1 Восстановление", "З2 Сердце / жир", "З3 Аэробная", "З4 Порог", "З5 Максимум")
    val ZONE_SHORT = arrayOf("—", "З1", "З2", "З3", "З4", "З5")

    fun maxHr(p: Profile): Int = p.maxHrOverride ?: (208.0 - 0.7 * p.age).roundToInt()

    /** 6 bounds: start of Z1..Z5 and the max. */
    fun zoneBounds(p: Profile): IntArray {
        val max = maxHr(p)
        val rest = p.restHr
        val pct = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
        return IntArray(6) { i ->
            if (rest != null && rest in 30..100) (rest + pct[i] * (max - rest)).roundToInt()
            else (pct[i] * max).roundToInt()
        }
    }

    /** 0 = below Z1, 1..5 = zone. */
    fun zoneOf(hr: Int, bounds: IntArray): Int {
        if (hr < bounds[0]) return 0
        for (z in 4 downTo 1) if (hr >= bounds[z]) return z + 1
        return 1
    }

    /**
     * HR below which the next set may start. ~65 % HRmax (or 50 % of heart-rate
     * reserve) = the heart has largely recovered from the set; combined with a
     * minimum rest time so muscles (ATP/PCr) also recover.
     */
    fun readyHr(p: Profile): Int {
        val max = maxHr(p)
        val rest = p.restHr
        return if (rest != null && rest in 30..100) (rest + 0.5 * (max - rest)).roundToInt()
        else (0.65 * max).roundToInt()
    }

    /** Basal metabolic rate, kcal/day (Mifflin-St Jeor). */
    fun bmr(p: Profile): Double =
        10 * p.weightKg + 6.25 * p.heightCm - 5 * p.age + if (p.male) 5 else -161

    /** Total energy expenditure, kcal per minute, at this heart rate (Keytel 2005). */
    fun kcalPerMin(hr: Int, p: Profile): Double {
        val k = if (p.male)
            (-55.0969 + 0.6309 * hr + 0.1988 * p.weightKg + 0.2017 * p.age) / 4.184
        else
            (-20.4022 + 0.4472 * hr - 0.1263 * p.weightKg + 0.074 * p.age) / 4.184
        // The equation is not valid at low HR; never go below light activity (~1.5x BMR).
        return max(k, bmr(p) / 1440.0 * 1.5)
    }

    fun restingKcalPerMin(p: Profile): Double = bmr(p) / 1440.0

    /** Edwards TRIMP weight for one second in this zone. */
    fun trimpPerSec(zone: Int): Double = zone / 60.0

    /**
     * Hours to rest before the next hard session.
     * Muscles: 48 h between sessions for the same muscle group (72 h after very hard ones).
     * Heart: scales with the cardio load (TRIMP).
     */
    fun recoveryHours(segments: List<Segment>): Int {
        val trimp = segments.sumOf { it.trimp }
        val strengthSets = segments.filter { it.type.strength }.sumOf { it.sets.size }
        val cardio = when {
            trimp < 40 -> 12
            trimp < 90 -> 24
            trimp < 160 -> 36
            trimp < 250 -> 48
            else -> 72
        }
        val muscle = when {
            strengthSets == 0 -> 0
            strengthSets < 6 -> 36
            strengthSets < 16 -> 48
            else -> 72
        }
        return max(cardio, muscle)
    }

    fun recoveryText(hours: Int, segments: List<Segment>): String {
        val strength = segments.any { it.type.strength }
        val sb = StringBuilder()
        sb.append("Отдых до следующей тяжёлой тренировки: ~$hours ч.")
        if (strength) sb.append(" Те же группы мышц — не раньше чем через 48 ч; для массы 2 раза в неделю на группу.")
        sb.append(" Лёгкое кардио в зоне 2 (20–40 мин) можно уже завтра — оно ускоряет восстановление и укрепляет сердце.")
        return sb.toString()
    }

    // ---------- Morning readiness (HRV) ----------

    /** Clean RR intervals (ms): plausible range and <20 % jump from previous (artefacts/ectopics). */
    fun cleanRr(rr: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        for (v in rr) {
            if (v !in 300..2000) continue
            val prev = out.lastOrNull()
            if (prev != null && kotlin.math.abs(v - prev) > prev * 0.2) continue
            out.add(v)
        }
        return out
    }

    fun rmssd(rr: List<Int>): Double {
        if (rr.size < 3) return 0.0
        var sum = 0.0
        for (i in 1 until rr.size) {
            val d = (rr[i] - rr[i - 1]).toDouble()
            sum += d * d
        }
        return sqrt(sum / (rr.size - 1))
    }

    /**
     * Readiness vs. the last 7 tests. z-score of ln(RMSSD):
     * >= -0.5 SD green, >= -1.5 SD yellow, otherwise red.
     * Resting HR 5+ bpm above usual lowers the status by one step.
     */
    fun readiness(today: Double, restHr: Int, history: List<HrvRecord>): Int {
        val base = history.sortedByDescending { it.time }.take(7)
        if (base.size < 3 || today <= 0) return -1
        val logs = base.map { ln(max(it.rmssd, 1.0)) }
        val mean = logs.average()
        val sd = max(sqrt(logs.sumOf { (it - mean) * (it - mean) } / (logs.size - 1)), 0.05)
        val z = (ln(today) - mean) / sd
        var status = when {
            z >= -0.5 -> 0
            z >= -1.5 -> 1
            else -> 2
        }
        val avgRest = base.map { it.restHr }.average()
        if (restHr - avgRest >= 5 && status < 2) status++
        return status
    }

    val READINESS_TEXT = mapOf(
        -1 to "Собираем вашу норму: нужно 3 утренних теста.",
        0 to "Готов: можно тяжёлую тренировку.",
        1 to "Средне: тренируйтесь легче, без максимумов.",
        2 to "Организм устал: сегодня отдых или лёгкая прогулка.",
    )
}

fun fmtDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fmtPace(secPerKm: Int?): String = if (secPerKm == null || secPerKm <= 0 || secPerKm > 3600) "--:--"
else "%d:%02d".format(secPerKm / 60, secPerKm % 60)

fun fmtKm(m: Double): String = "%.2f".format(m / 1000.0)
