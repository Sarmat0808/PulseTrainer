package fi.sarmat.pulsetrainer.core

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The coach learns from YOUR data — no generic formulas where your own history can answer:
 *
 * 1. Fitness / fatigue / form (Banister impulse-response model, the base of Garmin's training status
 *    and TrainingPeaks' CTL/ATL/TSB): fitness = 42-day load average, fatigue = 7-day average,
 *    form = fitness − fatigue. Forecasts when you'll be fresh again.
 * 2. Your real daily energy use (adaptive TDEE, like MacroFactor): from what you logged and how
 *    your weight actually moved — 7 700 kcal ≈ 1 kg. Replaces the formula once there's enough data,
 *    and forecasts your weight in 4 weeks.
 * 3. Personal recovery speed: every morning with a check-in compares what the model predicted with
 *    how you actually feel and your resting pulse, and nudges a personal factor (×0.75…×1.30).
 */
object Learn {
    private const val DAY = 86_400_000L

    // ======================= 1. Fitness, fatigue, form =======================

    data class Form(
        val fitness: Int,
        val fatigue: Int,
        val form: Int,
        /** Form for the next 7 days with rest (no training), today first. */
        val forecast: List<Int>,
        /** Days of rest until form ≥ −5 (fresh); 0 = fresh now. */
        val daysToFresh: Int,
        val label: String,
        val advice: String,
        /** Change of fitness over the last 4 weeks. */
        val fitnessTrend: Int,
        val hasData: Boolean,
    )

    /** [load] = daily training load (TRIMP) for consecutive days, oldest first, today last. */
    fun form(load: List<Double>): Form {
        val kF = 1 - exp(-1.0 / 42); val kA = 1 - exp(-1.0 / 7)
        var ctl = 0.0; var atl = 0.0
        val ctlHist = ArrayList<Double>()
        load.forEach { l -> ctl += (l - ctl) * kF; atl += (l - atl) * kA; ctlHist += ctl }
        val tsb = ctl - atl
        val fc = ArrayList<Int>()
        var c = ctl; var a = atl
        var toFresh = if (tsb >= -5) 0 else -1
        for (d in 0 until 7) {
            fc += (c - a).roundToInt()
            if (toFresh < 0 && c - a >= -5) toFresh = d
            c -= c * kF; a -= a * kA
        }
        if (toFresh < 0) toFresh = 7
        val trend = if (ctlHist.size > 28) (ctl - ctlHist[ctlHist.size - 29]).roundToInt() else 0
        val f = tsb.roundToInt()
        val (label, advice) = when {
            f < -30 -> "Перегрузка" to "Усталость сильно выше формы — 2–3 дня лёгких или отдыха, иначе риск травмы и болезни."
            f < -10 -> "Нагрузка" to "Идёт тренировочный эффект: организм перестраивается. Тяжёлое — когда форма поднимется выше −10."
            f <= 5 -> "Оптимально" to "Баланс нагрузки и отдыха — хорошее время для обычных тренировок."
            f <= 20 -> "Свежесть" to "Вы отдохнули — отличный день для тяжёлой тренировки или рекорда."
            else -> "Детренированность" to "Давно мало нагрузки — форма начнёт снижаться. Пора вернуться к тренировкам."
        }
        return Form(ctl.roundToInt(), atl.roundToInt(), f, fc, toFresh, label, advice, trend, load.count { it > 0 } >= 5)
    }

    // ======================= 2. Real energy use & weight forecast =======================

    data class Energy(
        /** Your real average daily energy use, kcal (null until enough data). */
        val tdee: Int?,
        val avgIntake: Int?,
        /** Days with both a full food log and weight in the window. */
        val daysUsed: Int,
        val daysNeeded: Int,
        /** Weight trend now (smoothed) and forecast in 4 weeks at the current intake. */
        val weightNow: Double?,
        val weight4w: Double?,
        /** kg per week (smoothed trend). */
        val weeklyChange: Double?,
        val text: String,
    )

    /**
     * [intake] = (day start, kcal) for days the diary was filled in (not «forgot»),
     * [weights] = (time, kg), [formulaTdee] = the textbook estimate used until the data is enough.
     */
    fun energy(intake: List<Pair<Long, Double>>, weights: List<Pair<Long, Double>>, formulaTdee: Int, now: Long = System.currentTimeMillis()): Energy {
        val need = 14
        val from = now - 28 * DAY
        val food = intake.filter { it.first >= from && it.second >= 1200 }   // a day below 1200 is an incomplete log
        val w = weights.filter { it.first >= from - 7 * DAY }.sortedBy { it.first }
        // Smoothed weight: exponential moving average (10 % per day), like Libra/MacroFactor.
        var ema: Double? = null; var lastT = 0L
        val emaSeries = ArrayList<Pair<Long, Double>>()
        w.forEach { (t, kg) ->
            ema = ema?.let { e -> val days = ((t - lastT) / DAY.toDouble()).coerceAtLeast(1.0); val k = 1 - Math.pow(0.9, days); e + (kg - e) * k } ?: kg
            lastT = t; emaSeries += t to ema!!
        }
        val inWindow = emaSeries.filter { it.first >= (food.minOfOrNull { it.first } ?: now) }
        val weekly = if (inWindow.size >= 2) {
            val (t0, a) = inWindow.first(); val (t1, b) = inWindow.last()
            val weeks = (t1 - t0) / (7.0 * DAY)
            if (weeks >= 1) (b - a) / weeks else null
        } else null
        val avgIn = food.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
        val enough = food.size >= need && weekly != null
        val tdee = if (enough) (avgIn!! - weekly!! * 7700 / 7).roundToInt().coerceIn(1500, 5500) else null
        val useT = tdee ?: formulaTdee
        val wNow = ema
        val w4 = if (wNow != null && avgIn != null) wNow + (avgIn - useT) * 28 / 7700 else null
        val text = when {
            tdee != null -> "Ваш реальный расход ≈ $tdee ккал/день (по ${food.size} дням еды и весу). Норма калорий теперь считается от него."
            else -> "Тренер учится: дней с полным дневником ${food.size} из $need и нужны взвешивания хотя бы раз в неделю. Пока расход по формуле ≈ $formulaTdee ккал."
        }
        return Energy(tdee, avgIn?.roundToInt(), food.size, need, wNow, w4, weekly, text)
    }

    // ======================= 3. Personal recovery speed =======================

    /**
     * One morning of evidence. [predictedLeft] = hours of recovery the model still showed this morning,
     * [feel] 1–5, [restDev] = resting pulse minus your norm. Returns the new personal factor.
     */
    fun updateRecovery(factor: Double, predictedLeft: Int, feel: Int, restDev: Double?): Double {
        val fresh = feel >= 4 && (restDev == null || restDev <= 1.0)
        val tired = feel <= 2 || (restDev != null && restDev >= 4.0)
        val f = when {
            predictedLeft >= 12 && fresh -> factor * 0.96   // you recover faster than the model thinks
            predictedLeft == 0 && tired -> factor * 1.04    // you recover slower
            else -> factor
        }
        return f.coerceIn(0.75, 1.30)
    }

    fun recoveryText(factor: Double): String = when {
        factor <= 0.9 -> "Вы восстанавливаетесь быстрее среднего — время восстановления уменьшено (×${"%.2f".format(factor)})."
        factor >= 1.1 -> "Вам нужно больше времени на восстановление, чем в среднем (×${"%.2f".format(factor)}) — тренер это учитывает."
        else -> "Скорость восстановления близка к средней (×${"%.2f".format(factor)}). Тренер уточняет её по утренним ответам."
    }
}
