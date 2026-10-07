package fi.sarmat.pulsetrainer.core

import kotlin.math.roundToInt

/**
 * "How productive was this workout?" — judged against the session's purpose and your own history.
 *
 * Strength (hypertrophy research: Schoenfeld; ACSM): 8–20 hard sets per session, 60–180 s rest,
 * 30–75 min; heart-rate recovery between sets (−20+ bpm in the first minute is good).
 * Cardio (WHO, Seiler polarized model): ≥20 min, most time in zones 2–3 for base sessions,
 * zones 4–5 only in intervals; efficiency = speed per heartbeat vs your previous sessions.
 */
object Review {

    data class Result(
        val score: Int,
        /** Short label for the list. */
        val label: String,
        /** 0 green, 1 yellow, 2 red, -1 grey (test / too short). */
        val level: Int,
        val good: List<String>,
        val improve: List<String>,
        /** Comparison with your previous sessions of the same kind. */
        val compare: List<String>,
    )

    private fun main(w: Workout): WorkoutType =
        w.segments.maxByOrNull { it.activeSec }?.type ?: WorkoutType.OTHER

    fun of(w: Workout, history: List<Workout>, p: Profile): Result {
        if (!Physiology.isRealWorkout(w)) {
            return Result(0, "Тест / слишком коротко", -1, emptyList(),
                listOf("Тренировка короче 5 минут — не учитывается в нагрузке и готовности."), emptyList())
        }
        val type = main(w)
        val bounds = w.zoneBounds.takeIf { it.size == 6 } ?: Physiology.zoneBounds(p)
        val good = ArrayList<String>()
        val improve = ArrayList<String>()
        val compare = ArrayList<String>()
        var score = 60
        val min = w.activeSec / 60
        val zs = w.zoneSec
        val total = zs.sum().coerceAtLeast(1)
        val prev = history.filter { it.id != w.id && it.start < w.start && Physiology.isRealWorkout(it) && main(it) == type }
            .sortedByDescending { it.start }.take(5)

        val strengthSegs = w.segments.filter { it.type.strength }
        if (strengthSegs.isNotEmpty() && strengthSegs.sumOf { it.activeSec } >= w.activeSec / 2) {
            val sets = strengthSegs.flatMap { it.sets }
            val rests = sets.mapNotNull { it.restSec }
            val hrr = sets.mapNotNull { it.hrr60 }
            when {
                sets.size >= 12 -> { score += 15; good += "Объём: ${sets.size} подходов — достаточно для роста мышц" }
                sets.size >= 8 -> { score += 8; good += "Объём: ${sets.size} подходов — рабочий" }
                else -> { score -= 10; improve += "Подходов мало (${sets.size}). Для роста мышц нужно 10–20 рабочих подходов за тренировку" }
            }
            if (rests.isNotEmpty()) {
                val avg = rests.average().roundToInt()
                when {
                    avg in 75..200 -> { score += 8; good += "Отдых между подходами ~${fmtDuration(avg)} — в норме для массы" }
                    avg < 75 -> { score -= 5; improve += "Отдых короткий (~${fmtDuration(avg)}): для силы и массы лучше 1,5–3 мин" }
                    else -> { score -= 5; improve += "Отдых длинный (~${fmtDuration(avg)}): тренировка растягивается, пульс успевает упасть ниже нужного" }
                }
            }
            if (hrr.size >= 2) {
                val a = hrr.average().roundToInt()
                if (a >= 20) { score += 7; good += "Пульс восстанавливается хорошо: −$a уд/мин за минуту отдыха" }
                else if (a < 12) { score -= 7; improve += "Пульс восстанавливается медленно (−$a за минуту): признак усталости — сократите объём или добавьте сон" }
                if (hrr.size >= 6) {
                    val first = hrr.take(3).average(); val last = hrr.takeLast(3).average()
                    if (last < first * 0.6) improve += "К концу восстановление упало с −${first.roundToInt()} до −${last.roundToInt()}: последние подходы уже не так полезны"
                }
            }
            when {
                min in 30..75 -> { score += 5; good += "Длительность $min мин — оптимально" }
                min > 90 -> { score -= 8; improve += "Тренировка $min мин: после 60–75 мин качество подходов падает" }
                min < 20 -> { score -= 5; improve += "Коротко ($min мин): добавьте 1–2 упражнения" }
            }
            prev.firstOrNull()?.let { pw ->
                val pSets = pw.segments.filter { it.type.strength }.sumOf { it.sets.size }
                val pReps = pw.segments.flatMap { it.sets }.sumOf { it.reps }
                val reps = sets.sumOf { it.reps }
                compare += "Прошлая такая тренировка: $pSets подходов, ${pw.activeSec / 60} мин" + if (pReps > 0) ", $pReps повт." else ""
                if (reps > 0 && pReps > 0) {
                    val d = reps - pReps
                    compare += if (d > 0) "Повторов больше на $d — прогресс ✓" else if (d < 0) "Повторов меньше на ${-d}" else "Повторов столько же"
                }
            }
        } else {
            // ----- Cardio -----
            val z23 = (zs[2] + zs[3]) * 100 / total
            val z45 = (zs[4] + zs[5]) * 100 / total
            when {
                min >= 30 -> { score += 10; good += "Длительность $min мин — хорошая аэробная нагрузка" }
                min >= 20 -> { score += 5; good += "Длительность $min мин" }
                else -> { score -= 8; improve += "Коротко ($min мин): для сердца эффективнее от 20–30 мин" }
            }
            if (z45 >= 30) {
                if (w.zoneSec[4] + w.zoneSec[5] in 600..1800) { score += 10; good += "Интенсивная работа: ${(zs[4] + zs[5]) / 60} мин в зонах 4–5 — тренирует МПК" }
                else { score -= 5; improve += "$z45% времени в зонах 4–5 — тяжело. Для базы держите зону 2 (${bounds[1]}–${bounds[2]})" }
            } else if (z23 >= 60) {
                score += 15; good += "$z23% времени в зонах 2–3 — то, что нужно для сердца"
            } else if (zs[0] + zs[1] > total / 2) {
                score -= 5; improve += "Пульс в основном ниже зоны 2: для тренировки сердца прибавьте темп до ${bounds[1]}+"
            }
            if (w.distanceM > 300 && w.activeSec > 0) {
                val speed = w.distanceM / w.activeSec * 3.6
                val pace = (w.activeSec / (w.distanceM / 1000)).toInt()
                good += if (type == WorkoutType.BIKE_OUTDOOR) "Средняя скорость ${"%.1f".format(speed)} км/ч" else "Средний темп ${fmtPace(pace)} /км"
                // Efficiency: metres per heartbeat (higher = fitter heart) vs your previous sessions.
                val eff = if (w.avgHr > 0) w.distanceM / (w.activeSec / 60.0) / w.avgHr else 0.0
                val effPrev = prev.filter { it.distanceM > 300 && it.avgHr > 0 }.map { it.distanceM / (it.activeSec / 60.0) / it.avgHr }
                if (eff > 0 && effPrev.size >= 2) {
                    val d = (eff / effPrev.average() - 1) * 100
                    compare += when {
                        d >= 3 -> "Экономичность: на ${d.roundToInt()}% больше метров на удар сердца, чем обычно — сердце крепнет ✓"
                        d <= -3 -> "Экономичность ниже обычной на ${(-d).roundToInt()}% — усталость, жара или недосып"
                        else -> "Экономичность как обычно"
                    }
                    if (d >= 3) score += 5
                }
            }
            prev.firstOrNull()?.let { pw ->
                compare += "Прошлая: ${pw.activeSec / 60} мин, ср. пульс ${pw.avgHr}" + if (pw.distanceM > 300) ", ${fmtKm(pw.distanceM)} км" else ""
            }
        }
        val maxHr = Physiology.maxHr(p)
        if (w.maxHr >= maxHr * 0.97) improve += "Пульс доходил до ${w.maxHr} (≈ максимум). Если это не интервалы — снизьте интенсивность"
        if (w.recoveryHours >= 48) good += "Хорошая нагрузка: восстановление ~${w.recoveryHours} ч"
        score = score.coerceIn(0, 100)
        val (label, level) = when {
            score >= 80 -> "Продуктивная" to 0
            score >= 60 -> "Хорошая" to 0
            score >= 45 -> "Средняя" to 1
            else -> "Малоэффективная" to 2
        }
        return Result(score, label, level, good, improve, compare)
    }
}
