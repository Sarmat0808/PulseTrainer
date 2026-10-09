package fi.sarmat.pulsetrainer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class Goal(val title: String) {
    HYBRID("Сердце + сила + качественная масса"),
    MASS("Набор мышечной массы"),
    FAT_LOSS("Снижение веса"),
    HEART("Сердце и выносливость"),
    FITNESS("Общая форма");

    companion object {
        fun of(s: String?): Goal = entries.firstOrNull { it.name == s } ?: HYBRID
    }
}

/** One day of watch data (from Samsung Health via Health Connect). Null = not available. */
data class DailyStats(
    /** Local midnight of the day, epoch millis. */
    val day: Long,
    val sleepMin: Int? = null,
    val deepMin: Int? = null,
    val remMin: Int? = null,
    val lightMin: Int? = null,
    val awakeMin: Int? = null,
    val restHr: Int? = null,
    val hrvMs: Double? = null,
    val steps: Long? = null,
    val spo2: Double? = null,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    /** Minutes of workouts recorded by other apps (e.g. Samsung Health itself). */
    val otherWorkoutMin: Int? = null,
    val activeKcal: Double? = null,
    val distanceM: Double? = null,
    val floors: Double? = null,
    val hydrationMl: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val hrAvg: Int? = null,
    val respRate: Double? = null,
    val bpSys: Int? = null,
    val bpDia: Int? = null,
    val vo2max: Double? = null,
    val leanKg: Double? = null,
    /** Sleep start/end (epoch millis) of the main night. */
    val sleepStart: Long? = null,
    val sleepEnd: Long? = null,
)

data class WeightEntry(val time: Long, val kg: Double)

/** Manual body measurements (any field may be empty). */
data class BodyEntry(
    val time: Long,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val chestCm: Double? = null,
    val armCm: Double? = null,
    val thighCm: Double? = null,
    val bodyFatPct: Double? = null,
    val neckCm: Double? = null,
) {
    /** % fat: entered, or estimated by the US Navy tape method (men: waist, neck, height; ±3–4 %). */
    fun fatOrEstimate(heightCm: Int, male: Boolean): Pair<Double, Boolean>? {
        bodyFatPct?.let { return it to false }
        val w = waistCm ?: return null; val n = neckCm ?: return null
        if (!male || w - n <= 0 || heightCm < 120) return null
        val v = 495.0 / (1.0324 - 0.19077 * kotlin.math.log10(w - n) + 0.15456 * kotlin.math.log10(heightCm.toDouble())) - 450.0
        return v.takeIf { it in 3.0..60.0 }?.let { it to true }
    }
}

/** What the coach recommends for today. */
enum class DayType(val title: String) {
    STRENGTH("Силовая"), CARDIO("Кардио в зоне 2"), INTERVALS("Интервалы 4×4"), WALK("Прогулка"), REST("Отдых")
}

data class CoachAdvice(
    val type: DayType,
    /** Recommended time of day text. */
    val whenText: String,
    val score: Int,
    /** 0 green, 1 yellow, 2 red */
    val level: Int,
    val headline: String,
    val reasons: List<String>,
    val plan: List<String>,
    val week: List<String>,
    val nutrition: List<String>,
    val progress: List<String>,
    val tips: List<String>,
    /** How many of the 5 inputs (sleep, resting pulse, HRV, load, your feeling) were available. */
    val confidence: Int = 5,
    /** What is missing to make the score reliable. */
    val missing: List<String> = emptyList(),
    /** Things to avoid today. */
    val avoid: List<String> = emptyList(),
)

/** One input of the readiness score, for the "why" list. [group] limits how much one area can take away. */
data class Factor(val text: String, val points: Int, val group: String = "")

/**
 * Rule-based personal coach built on established guidelines:
 * - WHO: 150–300 min/week moderate (zone 2) or 75–150 min vigorous + strength ≥2 days/week.
 * - Hypertrophy (Schoenfeld et al.): 10+ hard sets per muscle/week, each muscle 2×/week,
 *   6–12 reps (up to 30 near failure also works), 2–3 min rest between sets.
 * - Protein 1.6–2.2 g/kg/day (Morton et al. 2018); surplus ~+300 kcal for lean gain,
 *   deficit ~−400–500 kcal for fat loss (≈0.5–1 % body weight per week).
 * - Load: acute:chronic workload ratio (7 d vs 28 d), >1.5 = injury/overreach risk.
 * - "4×4" intervals (Helgerud 2007) for VO2max: 4 min at 85–95 % HRmax, 3 min easy, ×4.
 */
object Coach {

    private const val DAY = 86400_000L

    fun advise(
        p: Profile,
        goal: Goal,
        today: DailyStats?,
        days: List<DailyStats>,
        workouts: List<Workout>,
        tests: List<HrvRecord>,
        weights: List<WeightEntry>,
        body: List<BodyEntry> = emptyList(),
        now: Long = System.currentTimeMillis(),
        ext: List<ExtWorkout> = emptyList(),
        checkIn: CheckIn? = null,
        passive: List<PassiveDay> = emptyList(),
    ): CoachAdvice {
        var type = DayType.CARDIO
        // Test starts of a few seconds are not training: they must not change readiness or plans.
        @Suppress("NAME_SHADOWING") val workouts = workouts.filter { Physiology.isRealWorkout(it) }
        val bounds = Physiology.zoneBounds(p)
        val factors = ArrayList<Factor>()
        val missing = ArrayList<String>()
        val avoid = ArrayList<String>()
        var confidence = 0
        val todayKey = today?.day ?: (now - now % DAY)

        // ---- 1. Sleep: last night + last 3 nights (sleep debt) ----
        val sleep = today?.sleepMin
        if (sleep != null && sleep > 0) {
            confidence++
            val h = sleep / 60.0
            when {
                h < 5.5 -> factors += Factor("Мало сна: ${fmtH(sleep)} (нужно 7–9 ч)", -25, "sleep")
                h < 6.5 -> factors += Factor("Сон короче нормы: ${fmtH(sleep)}", -15, "sleep")
                h < 7.0 -> factors += Factor("Сон чуть меньше 7 ч: ${fmtH(sleep)}", -5, "sleep")
                else -> factors += Factor("Сон в норме: ${fmtH(sleep)}", 0, "sleep")
            }
            val deep = today?.deepMin
            if (deep != null && deep.toDouble() / sleep < 0.12) factors += Factor("Мало глубокого сна: $deep мин", -5, "sleep")
            val last3 = days.filter { it.day <= todayKey }.takeLast(3).mapNotNull { it.sleepMin?.takeIf { m -> m > 0 } }
            if (last3.size >= 3 && last3.average() < 390) factors += Factor("Недосып накапливается: в среднем ${fmtH(last3.average().roundToInt())} за 3 ночи", -10, "sleep")
        } else missing += "сон (спите с часами)"

        // ---- 2. Resting pulse vs your 14-day norm (Samsung Health or PulseTrainer's own night pulse) ----
        fun restOf(day: Long): Int? = days.firstOrNull { it.day == day }?.restHr ?: passive.firstOrNull { it.day == day }?.restHr
        val restDays = (days.map { it.day } + passive.map { it.day }).distinct().filter { it < todayKey }.sorted().takeLast(14)
        val restBase = restDays.mapNotNull { restOf(it) }
        val restToday = restOf(todayKey)
        if (restToday != null && restBase.size >= 4) {
            confidence++
            val diff = restToday - restBase.average()
            when {
                diff >= 6 -> factors += Factor("Пульс покоя ночью выше обычного на ${diff.roundToInt()} — усталость или начало болезни", -20, "rest")
                diff >= 3 -> factors += Factor("Пульс покоя немного повышен (+${diff.roundToInt()})", -10, "rest")
                diff <= -2 -> factors += Factor("Пульс покоя ниже обычного ($restToday) — хорошее восстановление", 0, "rest")
                else -> factors += Factor("Пульс покоя в норме: $restToday", 0, "rest")
            }
        } else missing += if (restToday == null) "ночной пульс покоя (носите часы ночью)" else "норма пульса покоя (ещё ${4 - restBase.size} ноч.)"

        // ---- 3. HRV: morning test (strap) or watch HRV ----
        val test = tests.lastOrNull()?.takeIf { now - it.time < 16 * 3600_000L }
        if (test != null && test.status >= 0) {
            confidence++
            val strap = test.rmssd > 0
            when (test.status) {
                2 -> factors += Factor(group = "hrv", text = if (strap) "Утренний тест: вариабельность пульса сильно ниже нормы" else "Утренний тест: пульс покоя сильно выше нормы", points = -30)
                1 -> factors += Factor(group = "hrv", text = if (strap) "Утренний тест: вариабельность пульса ниже нормы" else "Утренний тест: пульс покоя выше нормы", points = -15)
                else -> factors += Factor("Утренний тест: восстановление хорошее", 0, "hrv")
            }
        } else {
            val hrvBase = days.filter { it.day < todayKey }.takeLast(14).mapNotNull { it.hrvMs }
            val hrv = today?.hrvMs
            if (hrv != null && hrvBase.size >= 4) {
                confidence++
                val ratio = hrv / hrvBase.average()
                when {
                    ratio < 0.75 -> factors += Factor("Вариабельность пульса заметно ниже обычной", -20, "hrv")
                    ratio < 0.9 -> factors += Factor("Вариабельность пульса немного снижена", -10, "hrv")
                    else -> factors += Factor("Вариабельность пульса в норме", 0, "hrv")
                }
            } else {
                val n = tests.count { it.rmssd > 0 && it.time > now - 7 * DAY }
                missing += if (test != null && test.status < 0) "норма вариабельности: сделано $n из 3 утренних тестов" else "утренний тест с ремнём H10"
            }
        }

        // ---- 4. Training load: PulseTrainer + other apps (Samsung Health, auto-detected walks) ----
        val extNew = ext.filter { e -> workouts.none { w -> e.start < w.end && e.end > w.start } }
        fun loadBetween(a: Long, b: Long) = workouts.filter { it.start in a until b }.sumOf { it.trimp } +
            extNew.filter { it.start in a until b }.sumOf { it.trimp }
        val acute = loadBetween(now - 7 * DAY, now + 1)
        val chronicWeek = loadBetween(now - 28 * DAY, now + 1) / 4.0
        val hasLoadData = workouts.any { it.start > now - 28 * DAY } || extNew.any { it.start > now - 28 * DAY }
        if (hasLoadData) {
            confidence++
            if (chronicWeek > 30) {
                val acwr = acute / chronicWeek
                when {
                    acwr > 1.5 -> factors += Factor("Нагрузка за неделю резко выросла (×${"%.1f".format(acwr)}) — риск перегрузки", -15, "load")
                    acwr > 1.3 -> factors += Factor("Нагрузка за неделю выше обычной (×${"%.1f".format(acwr)})", -7, "load")
                    acwr < 0.6 -> factors += Factor("Нагрузка за неделю ниже обычной — можно добавить", 0, "load")
                    else -> factors += Factor("Нагрузка за неделю в норме", 0, "load")
                }
                val last2 = loadBetween(now - 2 * DAY, now + 1)
                if (last2 > chronicWeek / 7.0 * 2 * 1.6 && last2 > 60) factors += Factor("Последние 2 дня были нагруженными", -8, "load")
            } else factors += Factor("Нагрузка: собираем вашу норму (нужно ~4 недели данных)", 0, "load")
        } else missing += "тренировки за 4 недели"

        // ---- Recovery: the slowest unfinished recovery among recent sessions (own or from Samsung Health) ----
        // Uses the same hours as the «Восстановление» card, so the two never contradict each other.
        val recent = workouts.filter { Physiology.isRealWorkout(it) && it.end > now - 4 * DAY && it.end <= now }
        val worst = recent.maxByOrNull { it.end }?.let { it to 0.0 }
        val rec = Health.recovery(workouts, ext, days, passive, checkIn, emptyList(), now)
        if (rec.hoursLeft > 0) {
            val left = rec.hoursLeft
            val pts = when { left > 36 -> -40; left > 24 -> -30; left > 12 -> -20; else -> -10 }
            factors += Factor("Восстановление: ещё ~$left ч" + (worst?.let { " после «${it.first.title}»" } ?: ""), pts, "recovery")
        }
        extNew.filter { it.trimp >= 80 && it.end > (worst?.first?.end ?: 0L) && now - it.end < 24 * 3600_000L }.maxByOrNull { it.end }?.let { e ->
            factors += Factor("Вчера/сегодня: «${e.title}» ${e.minutes} мин — организм ещё восстанавливается", -8, "load")
        }

        // ---- 5. How you feel (the most honest signal — the watch cannot feel for you) ----
        if (checkIn != null) {
            confidence++
            when (checkIn.feel) {
                1 -> factors += Factor("Самочувствие: разбит", -30, "feel")
                2 -> factors += Factor("Самочувствие: устал", -18, "feel")
                3 -> factors += Factor("Самочувствие: обычное", -5, "feel")
                else -> factors += Factor("Самочувствие: бодрое", 0, "feel")
            }
            when (checkIn.soreness) {
                2 -> { factors += Factor("Сильная боль в мышцах", -10, "feel"); avoid += "Силовая на больные мышцы — дайте им 48–72 ч" }
                1 -> factors += Factor("Мышцы немного болят", -3, "feel")
            }
        } else missing += "ваше самочувствие (ответьте на 2 вопроса)"

        // Each area can take away only so much: several small minuses of one kind must not add up to "rest day".
        val caps = mapOf("sleep" to 30, "rest" to 20, "hrv" to 30, "load" to 25, "feel" to 30, "recovery" to 40)
        val lost = factors.groupBy { it.group }.map { (g, l) -> minOf(-l.sumOf { it.points }, caps[g] ?: 100) }.sum()
        var score = (100 - lost).coerceIn(0, 100)
        // Without enough data the app must not claim "excellent readiness".
        if (confidence <= 2) score = minOf(score, 74)
        var level = when { score >= 75 -> 0; score >= 40 -> 1; else -> 2 }
        if (checkIn != null && checkIn.feel <= 2 && level == 0) level = 1
        if (checkIn != null && checkIn.feel == 1) level = maxOf(level, 1)
        val reasons = factors.sortedBy { it.points }.map { (if (it.points < 0) "▼ " else "• ") + it.text }

        // ---- Recent history (PulseTrainer + other apps) ----
        val week = workouts.filter { it.start > now - 7 * DAY }
        val extWeek = extNew.filter { it.start > now - 7 * DAY }
        val z2 = (week.sumOf { it.zoneSec[2] + it.zoneSec[3] } + extWeek.sumOf { it.zoneSec[2] + it.zoneSec[3] }) / 60
        val hard = (week.sumOf { it.zoneSec[4] + it.zoneSec[5] } + extWeek.sumOf { it.zoneSec[4] + it.zoneSec[5] }) / 60
        val strengthDays = (week.filter { w -> w.segments.any { it.type.strength && it.sets.isNotEmpty() } }.map { it.start / DAY } +
            extWeek.filter { it.strength }.map { it.start / DAY }).distinct().size
        val lastStrength = (workouts.filter { w -> w.segments.any { it.type.strength } }.map { it.end } +
            extNew.filter { it.strength }.map { it.end }).maxOrNull()
        val hSinceStrength = lastStrength?.let { (now - it) / 3600_000.0 } ?: 999.0
        val lastHard = (workouts.filter { it.zoneSec[4] + it.zoneSec[5] > 300 }.map { it.end } +
            extNew.filter { it.zoneSec[4] + it.zoneSec[5] > 300 }.map { it.end }).maxOrNull()
        val hSinceHard = lastHard?.let { (now - it) / 3600_000.0 } ?: 999.0
        val other = days.filter { it.day > now - 7 * DAY }.sumOf { it.otherWorkoutMin ?: 0 }
        val sore = checkIn?.soreness == 2
        val tired = checkIn != null && checkIn.feel <= 2

        val z = { a: Int, b: Int -> "${bounds[a - 1]}–${bounds[b]}" }
        val ready = Physiology.readyHr(p)

        // ---- Today's plan ----
        val plan = ArrayList<String>()
        val headline: String
        val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val doneToday = workouts.filter { it.start >= dayStart }.sumOf { it.trimp } + extNew.filter { it.start >= dayStart }.sumOf { it.trimp }
        when {
            doneToday >= 80 -> {
                headline = "Тренировка сегодня уже сделана ✓"
                type = DayType.REST
                plan += "Сегодня больше без нагрузок — организм строит мышцы и сердце во время отдыха."
                plan += "Белок 25–40 г в ближайшие часы, вода, ужин за 2–3 ч до сна."
                plan += "Спокойная прогулка 10–20 мин можно — она ускоряет восстановление."
                plan += "Сон 7,5–9 ч — главный способ восстановиться к следующей тренировке."
            }
            level == 2 -> {
                headline = "Сегодня — восстановление"
                type = if (score < 25) DayType.REST else DayType.WALK
                plan += "Без тяжёлых нагрузок: прогулка 20–40 мин в зоне 1–2 (пульс ${z(1, 2)})."
                plan += "Растяжка или мобилизация суставов 10–15 мин."
                plan += "Ложитесь пораньше: цель 7,5–9 ч сна."
                if (restToday != null && restBase.isNotEmpty() && restToday - restBase.average() >= 6)
                    plan += "Если есть признаки простуды — полный отдых до нормализации пульса покоя."
            }
            level == 1 -> {
                headline = "Сегодня — лёгкая тренировка"
                type = DayType.CARDIO
                plan += "Кардио в зоне 2: 30–40 мин (пульс ${z(2, 2)}) — орбитрек, велотренажёр или быстрая ходьба."
                if (hSinceStrength >= 48 && goal != Goal.HEART && !tired && !sore)
                    plan += "Или облегчённая силовая: 2–3 подхода на упражнение, не до отказа (оставьте 3–4 повтора в запасе)."
                plan += "Без интервалов в зонах 4–5."
            }
            else -> {
                val strengthDue = hSinceStrength >= 48 && strengthDays < 3 && !sore
                val hardDue = hSinceHard >= 72 && hard < 20
                when (goal) {
                    Goal.HYBRID -> if (strengthDue) {
                        headline = "Сегодня — силовая (рост мышц)"
                        type = DayType.STRENGTH
                        plan += "Разминка 5–8 мин в зоне 1–2 на орбитреке или велотренажёре."
                        plan += "Всё тело или верх/низ: 5–6 базовых упражнений (жим, тяга, приседания, подтягивания, отжимания)."
                        plan += "3–4 рабочих подхода по 6–12 повторов, последние 1–2 повтора — тяжело, но технично."
                        plan += "Отдых 2–3 мин — до сигнала часов (пульс ≤ $ready)."
                        plan += "В конце 10–15 мин в зоне 2 (пульс ${z(2, 2)}) — сердце плюс восстановление. Переключение — кнопкой «Назад» на часах."
                    } else if (hardDue && hSinceStrength >= 20) {
                        headline = "Сегодня — интервалы 4×4 для сердца"
                        type = DayType.INTERVALS
                        plan += "Разминка 10 мин в зоне 2, затем 4 × 4 мин в зоне 4 (пульс ${z(4, 4)}), между ними 3 мин в зоне 1–2."
                        plan += "Подойдут орбитрек, велотренажёр, дорожка в горку. Заминка 5–10 мин."
                        plan += "Это самый эффективный способ поднять выносливость сердца (раз в неделю)."
                    } else {
                        headline = "Сегодня — кардио в зоне 2"
                        type = DayType.CARDIO
                        plan += "30–45 мин в зоне 2 (пульс ${z(2, 2)}): орбитрек, велосипед, быстрая ходьба. Можно говорить фразами."
                        plan += "Укрепляет сердце и ускоряет восстановление мышц после силовой."
                        plan += "Проверка: если не получается говорить полными фразами — сбавьте темп."
                    }
                    Goal.MASS -> if (strengthDue) {
                        headline = "Сегодня — силовая на рост мышц"
                        type = DayType.STRENGTH
                        plan += "Разминка 5–8 мин в зоне 1–2 (орбитрек/велотренажёр)."
                        plan += "5–6 упражнений на крупные группы (жим, тяга, приседания, подтягивания, отжимания на брусьях)."
                        plan += "3–4 рабочих подхода по 6–12 повторов, последние 1–2 повтора — на грани."
                        plan += "Отдых 2–3 мин — ждите сигнала часов (пульс ≤ $ready)."
                        plan += "Прогрессия: когда делаете верх диапазона во всех подходах — +2,5 кг или +1–2 повтора."
                    } else {
                        headline = "Сегодня — кардио для сердца"
                        plan += "Зона 2: 30–45 мин (пульс ${z(2, 2)}). Не мешает росту мышц и ускоряет восстановление."
                    }
                    Goal.FAT_LOSS -> if (strengthDue) {
                        headline = "Сегодня — силовая + кардио"
                        type = DayType.STRENGTH
                        plan += "Силовая: 4–5 упражнений, 3 подхода по 8–15 повторов, отдых 60–90 с (до пульса ≤ $ready)."
                        plan += "Сразу после — 20–30 мин в зоне 2 (пульс ${z(2, 2)}). Переключение — кнопкой «Назад» на часах."
                    } else if (hardDue) {
                        headline = "Сегодня — интервалы"
                        type = DayType.INTERVALS
                        plan += "Разминка 10 мин в зоне 2, затем 4×4 мин в зоне 4 (пульс ${z(4, 4)}), между ними 3 мин легко."
                    } else {
                        headline = "Сегодня — длительное кардио"
                        plan += "45–60 мин в зоне 2 (пульс ${z(2, 2)}): ходьба в гору, орбитрек, велосипед."
                    }
                    Goal.HEART -> if (hardDue) {
                        headline = "Сегодня — интервалы 4×4"
                        type = DayType.INTERVALS
                        plan += "Разминка 10 мин, затем 4 раза по 4 мин в зоне 4–5 (пульс ${bounds[3]}–${bounds[5]}), между ними 3 мин в зоне 1–2."
                        plan += "Заминка 5–10 мин. Это лучший способ поднять выносливость сердца (МПК)."
                    } else if (strengthDue && strengthDays < 2) {
                        headline = "Сегодня — силовая (2 раза в неделю)"
                        type = DayType.STRENGTH
                        plan += "Всё тело: 5 упражнений × 3 подхода по 8–12 повторов, отдых до пульса ≤ $ready."
                    } else {
                        headline = "Сегодня — зона 2"
                        plan += "40–60 мин спокойного кардио в зоне 2 (пульс ${z(2, 2)}). Должно получаться говорить фразами."
                    }
                    Goal.FITNESS -> if (strengthDue) {
                        headline = "Сегодня — силовая"
                        type = DayType.STRENGTH
                        plan += "Всё тело: 5–6 упражнений × 3 подхода по 8–12 повторов, отдых до пульса ≤ $ready."
                        plan += "Завершите 10–15 мин в зоне 2."
                    } else {
                        headline = "Сегодня — кардио"
                        plan += "30–45 мин в зоне 2 (пульс ${z(2, 2)}); раз в неделю — интервалы 4×4."
                    }
                }
            }
        }

        // ---- Week ----
        val weekLines = ArrayList<String>()
        weekLines += "Зона 2–3 за 7 дней: $z2 из 150 мин" + if (z2 >= 150) " ✓" else ""
        weekLines += "Силовых дней: $strengthDays из ${if (goal == Goal.MASS) "3" else "2"}" +
            if (strengthDays >= (if (goal == Goal.MASS) 3 else 2)) " ✓" else ""
        weekLines += "Интенсивно (зоны 4–5): $hard мин" + if (goal == Goal.HEART || goal == Goal.FAT_LOSS) " (цель 15–30)" else ""
        if (other > 0) weekLines += "Другие тренировки из Samsung Health: $other мин"
        val steps = days.filter { it.day > now - 7 * DAY }.mapNotNull { it.steps }
        if (steps.isNotEmpty()) weekLines += "Шаги в среднем: ${steps.average().roundToInt()} в день (цель 8 000+)"
        val sleeps = days.filter { it.day > now - 7 * DAY }.mapNotNull { it.sleepMin }
        if (sleeps.isNotEmpty()) weekLines += "Сон в среднем: ${fmtH(sleeps.average().roundToInt())}"

        // ---- Nutrition ----
        val w = p.weightKg
        val tdee = Physiology.bmr(p) * activityFactor(week.size, steps.average().takeIf { !it.isNaN() })
        val nutrition = ArrayList<String>()
        val proteinLo = (1.6 * w).roundToInt()
        val proteinHi = (2.2 * w).roundToInt()
        when (goal) {
            Goal.HYBRID -> {
                nutrition += "Калории: ~${(tdee + 250).roundToInt()} ккал/день (расход ~${tdee.roundToInt()} + 250 — медленный «чистый» набор)"
                nutrition += "Белок: $proteinLo–$proteinHi г/день, по 30–40 г в 4 приёма"
                nutrition += "Углеводы вокруг тренировок (каша, рис, фрукты) — энергия для силовой и кардио"
            }
            Goal.MASS -> {
                nutrition += "Калории: ~${(tdee + 300).roundToInt()} ккал/день (расход ~${tdee.roundToInt()} + 300 на рост мышц)"
                nutrition += "Белок: $proteinLo–$proteinHi г/день, по 30–40 г в 4 приёма"
            }
            Goal.FAT_LOSS -> {
                nutrition += "Калории: ~${(tdee - 450).roundToInt()} ккал/день (дефицит ~450 к расходу ~${tdee.roundToInt()})"
                nutrition += "Белок: ${(2.0 * w).roundToInt()}–$proteinHi г/день — сохраняет мышцы при похудении"
            }
            else -> {
                nutrition += "Калории: ~${tdee.roundToInt()} ккал/день (поддержание)"
                nutrition += "Белок: $proteinLo–${(2.0 * w).roundToInt()} г/день"
            }
        }
        nutrition += "Вода: ~${(w * 0.035).let { "%.1f".format(it) }} л/день + 0,5 л на каждый час тренировки"
        nutrition += "За 1–2 ч до силовой — углеводы и белок; после — 30–40 г белка"

        // ---- Progress (weight) ----
        val progress = ArrayList<String>()
        val ws = weights.sortedBy { it.time }
        val recentW = ws.lastOrNull()
        val monthAgo = ws.lastOrNull { it.time <= now - 21 * DAY } ?: ws.firstOrNull()
        if (recentW != null && monthAgo != null && recentW.time - monthAgo.time > 10 * DAY) {
            val weeks = (recentW.time - monthAgo.time) / (7.0 * DAY)
            val perWeek = (recentW.kg - monthAgo.kg) / weeks
            progress += "Вес: ${"%.1f".format(recentW.kg)} кг, изменение ${sign(perWeek)} кг/нед"
            when (goal) {
                Goal.MASS, Goal.HYBRID -> progress += when {
                    perWeek < 0.1 -> "Набор идёт медленно — добавьте ~200 ккал в день (цель +0,25…0,5 кг/нед)"
                    perWeek > 0.6 -> "Набор слишком быстрый — часть уходит в жир; уменьшите калории на ~200"
                    else -> "Темп набора правильный ✓"
                }
                Goal.FAT_LOSS -> progress += when {
                    perWeek > -0.2 -> "Снижение медленное — уменьшите калории на ~200 или добавьте 2 000 шагов в день"
                    perWeek < -0.01 * w -> "Снижение слишком быстрое — риск потери мышц; добавьте ~200 ккал"
                    else -> "Темп снижения правильный ✓"
                }
                else -> if (abs(perWeek) > 0.4) progress += "Вес заметно меняется — проверьте питание"
            }
        } else {
            progress += "Взвешивайтесь 1–2 раза в неделю утром натощак и вносите вес — тренер оценит темп."
        }
        val hrr = workouts.sortedBy { it.start }.mapNotNull { wk ->
            wk.segments.flatMap { it.sets }.mapNotNull { it.hrr60 }.takeIf { it.size >= 2 }?.average()
        }
        if (hrr.size >= 4) {
            val early = hrr.take(hrr.size / 2).average()
            val late = hrr.drop(hrr.size / 2).average()
            progress += "Восстановление пульса за минуту: было −${early.roundToInt()}, стало −${late.roundToInt()}" +
                if (late > early + 2) " — сердце тренируется ✓" else ""
        }
        val rests = days.mapNotNull { it.restHr }
        if (rests.size >= 20) {
            val a = rests.take(7).average(); val b = rests.takeLast(7).average()
            if (a - b >= 2) progress += "Пульс покоя снизился с ${a.roundToInt()} до ${b.roundToInt()} — хороший знак ✓"
        }

        val bs = body.sortedBy { it.time }
        val waistNow = bs.lastOrNull { it.waistCm != null }
        val waistThen = bs.lastOrNull { it.waistCm != null && it.time <= (waistNow?.time ?: 0) - 21 * DAY }
        if (waistNow != null && waistThen != null) {
            val dw = waistNow.waistCm!! - waistThen.waistCm!!
            val wkW = bs.lastOrNull { it.weightKg != null }?.weightKg
            progress += "Талия: ${"%.1f".format(waistNow.waistCm)} см (${sign(dw)} см за ${((waistNow.time - waistThen.time) / DAY)} дн)"
            if ((goal == Goal.MASS || goal == Goal.HYBRID) && dw > 1.5)
                progress += "Талия растёт быстро — часть набора уходит в жир; уменьшите калории на ~200 и добавьте зону 2"
            else if ((goal == Goal.MASS || goal == Goal.HYBRID) && dw <= 0.5 && wkW != null)
                progress += "Талия почти не меняется — набор «качественный» ✓"
        }
        bs.lastOrNull { it.armCm != null }?.let { a ->
            bs.firstOrNull { it.armCm != null && it.time < a.time - 21 * DAY }?.let { f ->
                progress += "Бицепс: ${"%.1f".format(a.armCm)} см (${sign(a.armCm!! - f.armCm!!)} см)"
            }
        }

        // ---- When to train ----
        val hours = workouts.takeLast(20).map { java.util.Calendar.getInstance().apply { timeInMillis = it.start }.get(java.util.Calendar.HOUR_OF_DAY) }
        val usual = if (hours.size >= 3) hours.sorted()[hours.size / 2] else null
        val whenText = when (type) {
            DayType.REST -> "Сегодня без тренировки. Короткая прогулка после еды и ранний сон."
            DayType.WALK -> "Прогулка в любое время, лучше днём на свету: 30–40 мин."
            else -> (if (usual != null) "Лучше в ваше обычное время — около $usual:00. " else "Лучше во второй половине дня (16–19 ч): сила и выносливость выше. ") +
                "Тяжёлую тренировку заканчивайте не позже чем за 3 ч до сна."
        }

        // ---- Tips (rotate daily) ----
        val all = listOf(
            "Прогрессивная перегрузка — главный двигатель роста: каждую неделю чуть больше веса или повторов.",
            "Каждые 4–6 недель делайте разгрузочную неделю: объём −40%, интенсивность та же.",
            "Сон — лучшее восстановление: ложитесь в одно время, комната прохладная и тёмная.",
            "Зона 2 укрепляет сердце и митохондрии: темп, при котором можно говорить фразами.",
            "Разминка 5–10 мин и 1–2 лёгких подхода перед рабочим весом снижают риск травмы.",
            "Падение пульса за минуту отдыха больше 20 уд — хорошее восстановление; меньше 12 — повод снизить нагрузку.",
            "Белок равномерно в течение дня усваивается лучше, чем один большой приём.",
            "Перед подтягиваниями — подвисы и лопаточные подтягивания, это бережёт плечи.",
            "Если пульс в отдыхе долго не падает — сократите подход на 1–2 повтора, а не время отдыха.",
            "Боль в груди, сильная одышка, головокружение или перебои — немедленно прекратите тренировку и обратитесь к врачу.",
        )
        val start = ((now / DAY) % all.size).toInt()
        val tips = (0 until 3).map { all[(start + it) % all.size] }

        when (level) {
            2 -> { avoid += "Интервалы, тяжёлая силовая, работа до отказа"; avoid += "Кофеин после 14:00 и плотная еда перед сном" }
            1 -> avoid += "Зоны 4–5 и подходы до отказа"
        }
        if (sleep != null && sleep < 390) avoid += "Тяжёлая тренировка позже 20:00 — ещё сильнее урежет сон"
        if (confidence <= 2) avoid += "Не ориентируйтесь только на цифру — данных пока мало, слушайте самочувствие"

        return CoachAdvice(type, whenText, score, level, headline, reasons, plan, weekLines, nutrition, progress, tips, confidence, missing, avoid)
    }

    private fun activityFactor(sessions: Int, steps: Double?): Double {
        var f = when {
            sessions >= 5 -> 1.6
            sessions >= 3 -> 1.5
            sessions >= 1 -> 1.4
            else -> 1.3
        }
        if (steps != null && steps > 10000) f += 0.1
        return f
    }

    private fun sign(v: Double) = (if (v >= 0) "+" else "") + "%.2f".format(v)

    fun fmtH(min: Int) = "${min / 60} ч ${min % 60} мин"

    fun levelText(level: Int) = when (level) { 0 -> "Высокая готовность"; 1 -> "Средняя готовность"; else -> "Нужно восстановление" }

    fun levelText(a: CoachAdvice) = levelText(a.level) + if (a.confidence <= 2) " · мало данных" else ""

    @Suppress("unused")
    private fun maxOf0(a: Int, b: Int) = max(a, b)
}
