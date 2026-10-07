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
        val dur = when { h >= 7 && h <= 9.5 -> 1.0; h < 7 -> max(0.0, (h - 4.5) / 2.5); else -> max(0.6, 1 - (h - 9.5) / 3) }
        val deep = d.deepMin?.let { min(1.0, it.toDouble() / total / 0.15) } ?: 0.8
        val rem = d.remMin?.let { min(1.0, it.toDouble() / total / 0.20) } ?: 0.8
        val awake = d.awakeMin?.let { max(0.0, 1 - max(0.0, it.toDouble() / (total + it) - 0.05) * 5) } ?: 0.8
        // Good stages cannot make up for too little sleep: short nights are capped.
        val cap = when { h < 5.5 -> 44; h < 6.0 -> 54; h < 6.5 -> 64; h < 7.0 -> 74; else -> 100 }
        val v = ((dur * 50 + deep * 20 + rem * 20 + awake * 10)).roundToInt().coerceIn(0, cap)
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
     * zones 4–5 count double. Goal 150 per week. Other apps' workouts count by their real heart rate
     * when Health Connect has it; otherwise half their minutes.
     */
    fun heartMinutes(workouts: List<Workout>, otherMin: Int, now: Long = System.currentTimeMillis(), ext: List<ExtWorkout> = emptyList()): Int {
        val week = workouts.filter { it.start > now - 7 * 86400_000L }
        val mod = week.sumOf { it.zoneSec[2] + it.zoneSec[3] } / 60
        val vig = week.sumOf { it.zoneSec[4] + it.zoneSec[5] } / 60
        if (ext.isEmpty()) return mod + 2 * vig + otherMin / 2
        val e = ext.filter { it.start > now - 7 * 86400_000L && week.none { w -> it.start < w.end && it.end > w.start } }
        val eMod = e.filter { !it.estimated }.sumOf { it.zoneSec[2] + it.zoneSec[3] } / 60
        val eVig = e.filter { !it.estimated }.sumOf { it.zoneSec[4] + it.zoneSec[5] } / 60
        val eEst = e.filter { it.estimated }.sumOf { it.minutes } / 2
        return mod + 2 * vig + eMod + 2 * eVig + eEst
    }

    /** Cardio load: this week's TRIMP vs your 4-week average (PulseTrainer + other apps). */
    fun cardioLoad(workouts: List<Workout>, now: Long = System.currentTimeMillis(), ext: List<ExtWorkout> = emptyList()): Triple<Int, Int, String> {
        val e = ext.filter { x -> workouts.none { w -> x.start < w.end && x.end > w.start } }
        fun sum(from: Long) = workouts.filter { it.start > from }.sumOf { it.trimp } + e.filter { it.start > from }.sumOf { it.trimp }
        val week = sum(now - 7 * 86400_000L).roundToInt()
        val avg = (sum(now - 28 * 86400_000L) / 4).roundToInt()
        val label = when {
            avg < 30 -> if (week > 0) "Набираем базу" else "Нет нагрузки"
            week < avg * 0.7 -> "Ниже обычного"
            week <= avg * 1.3 -> "В норме"
            week <= avg * 1.5 -> "Немного выше"
            else -> "Слишком высокая"
        }
        return Triple(week, avg, label)
    }

    /** Daily load (TRIMP) for the last 7 days, oldest first. */
    fun dailyLoad(workouts: List<Workout>, ext: List<ExtWorkout>, dayStarts: List<Long>): List<Double> {
        val e = ext.filter { x -> workouts.none { w -> x.start < w.end && x.end > w.start } }
        return dayStarts.map { d ->
            workouts.filter { it.start >= d && it.start < d + 86400_000L }.sumOf { it.trimp } +
                e.filter { it.start >= d && it.start < d + 86400_000L }.sumOf { it.trimp }
        }
    }

    // ======================= Recovery time (like Garmin) =======================

    data class Recovery(
        /** Hours until you are ready for a hard session again (0 = ready). */
        val hoursLeft: Int,
        /** Hours the last load needed in total (for the ring). */
        val hoursTotal: Int,
        /** Moment when recovery completes (epoch millis). */
        val readyAt: Long,
        val label: String,
        val level: Int,
        val factors: List<String>,
    )

    /** Base recovery need of a workout recorded by another app, by its load. */
    private fun extHours(e: ExtWorkout): Int {
        val cardio = when {
            e.trimp < 30 -> 0
            e.trimp < 60 -> 12
            e.trimp < 110 -> 24
            e.trimp < 180 -> 36
            else -> 48
        }
        return if (e.strength && e.minutes >= 30) maxOf(cardio, 36) else cardio
    }

    /**
     * Garmin-style recovery time: every workout adds its recovery need (from heart-rate load and strength volume);
     * what is still left from earlier sessions carries over (half of it stacks on top).
     * Then the remaining time is adjusted by how your body actually recovers:
     * short or poor sleep slows it down, resting pulse above your norm slows it down, high stress and "tired" slow it down;
     * good sleep and a calm night speed it up.
     */
    fun recovery(
        workouts: List<Workout>, ext: List<ExtWorkout>, days: List<DailyStats>, passive: List<PassiveDay>,
        check: CheckIn?, stress: List<StressRecord>, now: Long = System.currentTimeMillis(),
    ): Recovery {
        val h = 3600_000L
        data class Ev(val end: Long, val hours: Int)
        val own = workouts.filter { Physiology.isRealWorkout(it) && it.end > now - 5 * 86400_000L }.map { Ev(it.end, Physiology.recoveryHours(it.segments)) }
        val other = ext.filter { e -> e.end > now - 5 * 86400_000L && workouts.none { e.start < it.end && e.end > it.start } }.map { Ev(it.end, extHours(it)) }
        var readyAt = 0L
        var total = 0
        for (e in (own + other).filter { it.hours > 0 }.sortedBy { it.end }) {
            val leftBefore = ((readyAt - e.end) / h.toDouble()).coerceAtLeast(0.0)
            val add = e.hours + leftBefore * 0.5
            readyAt = maxOf(readyAt, e.end + (add * h).toLong())
            total = (add.roundToInt()).coerceAtLeast(total.takeIf { leftBefore > 0 } ?: 0)
        }
        var left = ((readyAt - now) / h.toDouble()).coerceAtLeast(0.0)
        val factors = ArrayList<String>()
        if (left > 0) {
            var k = 1.0
            val night = days.lastOrNull()?.takeIf { it.sleepMin != null }
            val sleep = night?.sleepMin
            if (sleep != null) when {
                sleep < 360 -> { k += 0.25; factors += "Сон меньше 6 ч — восстановление медленнее (+25%)" }
                sleep < 420 -> { k += 0.10; factors += "Сон меньше 7 ч (+10%)" }
                (sleepScore(night)?.value ?: 0) >= 80 -> { k -= 0.10; factors += "Хороший сон ускоряет восстановление (−10%)" }
            }
            val rest = days.lastOrNull()?.restHr ?: passive.lastOrNull()?.restHr
            val base = (days.dropLast(1).takeLast(14).mapNotNull { it.restHr } + passive.dropLast(1).takeLast(14).mapNotNull { it.restHr })
                .takeIf { it.size >= 4 }?.average()
            if (rest != null && base != null) {
                val d = rest - base
                if (d >= 5) { k += 0.25; factors += "Ночной пульс выше нормы на ${d.roundToInt()} (+25%)" }
                else if (d >= 3) { k += 0.10; factors += "Ночной пульс немного повышен (+10%)" }
                else if (d <= -2) { k -= 0.05; factors += "Ночной пульс ниже нормы (−5%)" }
            }
            stress.lastOrNull()?.takeIf { now - it.time < 12 * h && it.score > 60 }?.let { k += 0.10; factors += "Высокий стресс (+10%)" }
            if (check != null && check.feel <= 2) { k += 0.15; factors += "Самочувствие: устал (+15%)" }
            if (check != null && check.soreness == 2) { k += 0.15; factors += "Сильная боль в мышцах (+15%)" }
            left *= k
        }
        val hl = left.roundToInt()
        val (label, level) = when {
            hl == 0 -> "Восстановлен — можно тяжёлую тренировку" to 0
            hl <= 12 -> "Почти восстановлен — средняя нагрузка" to 1
            hl <= 36 -> "Восстановление — лёгкая тренировка или зона 2" to 1
            else -> "Нужен отдых — только прогулка и растяжка" to 2
        }
        return Recovery(hl, maxOf(total, hl), now + hl * h, label, level, factors)
    }

    // ======================= VO2max =======================

    data class Vo2Report(
        val value: Double,
        val measured: Boolean,
        val level: Score,
        /** Change vs 2–4 weeks ago (ml/kg/min), null if not enough history. */
        val change: Double?,
        val direction: String,
        /** Weekly values, oldest first (for a small chart). */
        val series: List<Double>,
        val nextLevel: String?,
        val toNext: Double?,
        val advice: List<String>,
        val explain: List<String>,
    )

    private fun restSeries(days: List<DailyStats>, passive: List<PassiveDay>): List<Pair<Long, Int>> {
        val map = HashMap<Long, Int>()
        passive.forEach { d -> d.restHr?.let { map[d.day] = it } }
        days.forEach { d -> d.restHr?.let { map[d.day] = it } }
        return map.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    fun vo2Report(
        p: Profile, goal: Goal, days: List<DailyStats>, workouts: List<Workout>, ext: List<ExtWorkout>,
        passive: List<PassiveDay>, now: Long = System.currentTimeMillis(),
    ): Vo2Report? {
        val day = 86400_000L
        val measuredPts = days.filter { it.vo2max != null }.map { it.day to it.vo2max!! }
        val rest = restSeries(days, passive)
        // Estimate per day from a 7-day median of resting pulse (single nights are noisy).
        val estPts = rest.map { (t, _) ->
            val win = rest.filter { it.first in (t - 6 * day)..t }.map { it.second }.sorted()
            t to 15.3 * Physiology.maxHr(p) / win[win.size / 2]
        }.filter { it.second in 15.0..80.0 }
        val pts = if (measuredPts.size >= 2) measuredPts else estPts
        val current = measuredPts.lastOrNull()?.second ?: estPts.lastOrNull()?.second ?: vo2Estimate(p, p.restHr) ?: return null
        val measured = measuredPts.isNotEmpty()
        val lv = vo2Level(p, current)

        val recent = pts.filter { it.first > now - 14 * day }.map { it.second }
        val before = pts.filter { it.first in (now - 42 * day)..(now - 14 * day) }.map { it.second }
        val change = if (recent.size >= 3 && before.size >= 3) recent.average() - before.average() else null
        val direction = when {
            change == null -> "Тренд появится через 2–4 недели данных"
            change >= 0.5 -> "Растёт ↑ — сердце становится сильнее"
            change <= -0.5 -> "Снижается ↓ — форма немного уходит"
            else -> "Стабильно → держите режим, чтобы сдвинуть вверх"
        }
        val series = (5 downTo 0).mapNotNull { k ->
            val a = now - (k + 1) * 7 * day; val b = now - k * 7 * day
            pts.filter { it.first in a until b }.map { it.second }.takeIf { it.isNotEmpty() }?.average()
        }

        // Next level threshold for your age and sex.
        val decade = (p.age / 10).coerceIn(2, 6)
        val male = when (decade) {
            2 -> intArrayOf(37, 42, 46, 51); 3 -> intArrayOf(35, 40, 44, 49); 4 -> intArrayOf(33, 38, 42, 47)
            5 -> intArrayOf(30, 35, 39, 43); else -> intArrayOf(27, 31, 35, 40)
        }
        val t = if (p.male) male else male.map { it - 6 }.toIntArray()
        val names = listOf("Ниже среднего", "Средний", "Хороший", "Отличный")
        val idx = t.indexOfFirst { current < it }
        val nextLevel = if (idx >= 0) names[idx] else null
        val toNext = if (idx >= 0) t[idx] - current else null

        // ---- Personal advice from your own data ----
        val e7 = ext.filter { it.start > now - 7 * day }
        val w7 = workouts.filter { it.start > now - 7 * day }
        val z2 = (w7.sumOf { it.zoneSec[2] + it.zoneSec[3] } + e7.filter { !it.estimated }.sumOf { it.zoneSec[2] + it.zoneSec[3] }) / 60
        val hard14 = (workouts.filter { it.start > now - 14 * day }.sumOf { it.zoneSec[4] + it.zoneSec[5] } +
            ext.filter { it.start > now - 14 * day && !it.estimated }.sumOf { it.zoneSec[4] + it.zoneSec[5] }) / 60
        val steps = days.filter { it.day > now - 7 * day }.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()
        val sleep = days.filter { it.day > now - 7 * day }.mapNotNull { it.sleepMin?.takeIf { m -> m > 0 } }.takeIf { it.isNotEmpty() }?.average()
        val b = Physiology.zoneBounds(p)
        val advice = ArrayList<String>()
        if (hard14 < 10) advice += "Главный рычаг: интервалы 4×4 раз в неделю — 4 мин в зоне 4 (${b[3]}–${b[4]}), 3 мин легко, 4 раза. За 8–10 недель МПК обычно растёт на 5–10%."
        else advice += "Интервалы есть ($hard14 мин в зонах 4–5 за 2 недели) — продолжайте 1 раз в неделю, не чаще 2."
        if (z2 < 150) advice += "Зона 2 (${b[1]}–${b[2]}): сейчас $z2 из 150 мин в неделю. Добавьте 2–3 занятия по 40–50 мин — это база, на которой растёт МПК."
        else advice += "Зона 2: $z2 мин за неделю ✓ — база есть."
        val bmi = bmi(p)
        if (bmi >= 27) {
            val perKg = current / p.weightKg
            advice += "МПК считается на 1 кг веса: каждые −2 кг жира дают ≈ +${"%.1f".format(perKg * 2)} без единой тренировки. При вашей цели — медленно убирать жир, сохраняя мышцы."
        }
        if (steps != null && steps < 8000) advice += "Шаги: в среднем ${steps.roundToInt()} в день — добавьте до 8–10 тыс., это дешёвая аэробная база."
        if (sleep != null && sleep < 420) advice += "Сон в среднем ${sleep.roundToInt() / 60} ч ${sleep.roundToInt() % 60} мин: при недосыпе сердце хуже адаптируется к нагрузке. Цель — 7–8 ч."
        if (!measured) advice += "Для точного значения: бег или быстрая ходьба на улице 20+ мин с часами — Samsung Health измерит МПК сам."
        val explain = listOf(
            "МПК (VO₂max) — сколько кислорода тело может использовать в минуту на 1 кг. Лучший показатель выносливости сердца и прогноза здоровья.",
            if (measured) "Значение измерено часами во время бега/ходьбы на улице." else "Оценка по формуле Ута: 15,3 × макс. пульс / пульс покоя. Чем ниже пульс покоя при том же макс. пульсе — тем выше МПК.",
            "Тренд сравнивает последние 2 недели с 2–6 неделями ранее. Нормальный рост — 0,5–1 единица в месяц.",
        )
        return Vo2Report(current, measured, lv, change, direction, series, nextLevel, toNext, advice, explain)
    }

    // ======================= Where am I heading =======================

    data class Trend(val name: String, val now: String, val delta: String, val better: Boolean?, val note: String)

    /** Last 14 days vs the 14 days before: is each metric moving the right way? */
    fun trends(
        p: Profile, goal: Goal, days: List<DailyStats>, workouts: List<Workout>, ext: List<ExtWorkout>,
        passive: List<PassiveDay>, weights: List<WeightEntry>, now: Long = System.currentTimeMillis(),
    ): List<Trend> {
        val day = 86400_000L
        val out = ArrayList<Trend>()
        fun <T> split(list: List<Pair<Long, T>>): Pair<List<T>, List<T>> =
            list.filter { it.first > now - 14 * day }.map { it.second } to list.filter { it.first in (now - 28 * day)..(now - 14 * day) }.map { it.second }

        val (rA, rB) = split(restSeries(days, passive))
        if (rA.size >= 3 && rB.size >= 3) {
            val d = rA.average() - rB.average()
            out += Trend("Пульс покоя", "${rA.average().roundToInt()}", "%+.1f".format(d), if (kotlin.math.abs(d) < 1) null else d < 0,
                if (d <= -1) "сердце работает экономнее" else if (d >= 1) "усталость, недосып или стресс" else "стабильно")
        }
        val (sA, sB) = split(days.mapNotNull { d -> d.sleepMin?.takeIf { it > 0 }?.let { d.day to it } })
        if (sA.size >= 3 && sB.size >= 3) {
            val d = sA.average() - sB.average()
            out += Trend("Сон", "${(sA.average() / 60).let { "%.1f".format(it) }} ч", "%+.0f мин".format(d), if (kotlin.math.abs(d) < 10) null else d > 0,
                if (sA.average() < 420) "ниже 7 ч — главный тормоз восстановления" else "в норме")
        }
        val weekLoad = { a: Long, b: Long ->
            workouts.filter { it.start in a until b }.sumOf { (it.zoneSec[2] + it.zoneSec[3] + 2 * (it.zoneSec[4] + it.zoneSec[5])) / 60 } +
                ext.filter { it.start in a until b && workouts.none { w -> it.start < w.end && it.end > w.start } }
                    .sumOf { if (it.estimated) it.minutes / 2 else (it.zoneSec[2] + it.zoneSec[3] + 2 * (it.zoneSec[4] + it.zoneSec[5])) / 60 }
        }
        val hA = weekLoad(now - 14 * day, now + 1) / 2
        val hB = weekLoad(now - 28 * day, now - 14 * day) / 2
        if (hA + hB > 0) out += Trend("Минуты для сердца", "$hA/нед", "%+d".format(hA - hB), if (kotlin.math.abs(hA - hB) < 15) null else hA > hB,
            if (hA >= 150) "норма ВОЗ выполнена" else "цель 150 в неделю")
        val (wA, wB) = split(weights.map { it.time to it.kg } + days.mapNotNull { d -> d.weightKg?.let { d.day to it } })
        if (wA.isNotEmpty() && wB.isNotEmpty()) {
            val d = wA.average() - wB.average()
            val good = when (goal) {
                Goal.MASS, Goal.HYBRID -> if (d in 0.1..1.2) true else if (d > 1.5 || d < -0.5) false else null
                Goal.FAT_LOSS -> if (d < -0.3) true else if (d > 0.3) false else null
                else -> if (kotlin.math.abs(d) < 0.7) true else null
            }
            out += Trend("Вес", "%.1f кг".format(wA.last()), "%+.1f кг".format(d), good,
                when (goal) { Goal.MASS, Goal.HYBRID -> "цель +0,5…1 кг в месяц"; Goal.FAT_LOSS -> "цель −2…4 кг в месяц"; else -> "держать стабильно" })
        }
        val (fA, fB) = split(days.mapNotNull { d -> d.bodyFatPct?.let { d.day to it } })
        if (fA.size >= 2 && fB.size >= 2) {
            val d = fA.average() - fB.average()
            out += Trend("Жир", "%.1f%%".format(fA.average()), "%+.1f".format(d), if (kotlin.math.abs(d) < 0.5) null else d < 0,
                if (d >= 0.5) "набор идёт с жиром — проверьте калории" else "весы с биоимпедансом неточны ±2–3%")
        }
        val (stA, stB) = split(days.mapNotNull { d -> d.steps?.let { d.day to it.toDouble() } })
        if (stA.size >= 5 && stB.size >= 5) {
            val d = stA.average() - stB.average()
            out += Trend("Шаги", "${stA.average().roundToInt()}", "%+d".format(d.roundToInt()), if (kotlin.math.abs(d) < 800) null else d > 0, "цель 8 000+")
        }
        return out
    }

    fun trendVerdict(list: List<Trend>): String {
        val up = list.count { it.better == true }; val down = list.count { it.better == false }
        return when {
            list.isEmpty() -> "Нужно 2–4 недели данных, чтобы увидеть направление."
            up > down -> "Вы движетесь в правильную сторону: улучшилось $up из ${list.size} показателей."
            down > up -> "Сейчас откат: ухудшилось $down из ${list.size}. Чаще всего причина — сон и перегрузка."
            else -> "Пока без явных изменений — режим держится."
        }
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
