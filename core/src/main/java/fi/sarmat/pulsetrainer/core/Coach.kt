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
)

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
)

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
    ): CoachAdvice {
        var type = DayType.CARDIO
        val bounds = Physiology.zoneBounds(p)
        val reasons = ArrayList<String>()
        var score = 100

        // ---- Sleep ----
        val sleep = today?.sleepMin
        if (sleep != null) {
            val h = sleep / 60.0
            when {
                h < 5.5 -> { score -= 25; reasons += "Мало сна: ${fmtH(sleep)} (нужно 7–9 ч)" }
                h < 6.5 -> { score -= 15; reasons += "Сон короче нормы: ${fmtH(sleep)}" }
                h < 7.0 -> { score -= 5; reasons += "Сон чуть меньше 7 ч: ${fmtH(sleep)}" }
                else -> reasons += "Сон в норме: ${fmtH(sleep)}"
            }
            val deep = today?.deepMin
            if (deep != null && sleep > 0 && deep.toDouble() / sleep < 0.10) { score -= 5; reasons += "Мало глубокого сна: $deep мин" }
        }

        // ---- Resting HR vs. 14-day baseline ----
        val restBase = days.filter { it.day < (today?.day ?: now) }.takeLast(14).mapNotNull { it.restHr }
        val restToday = today?.restHr ?: tests.lastOrNull()?.takeIf { now - it.time < 16 * 3600_000L }?.restHr
        if (restToday != null && restBase.size >= 4) {
            val diff = restToday - restBase.average()
            when {
                diff >= 6 -> { score -= 20; reasons += "Пульс покоя выше обычного на ${diff.roundToInt()} — признак усталости или болезни" }
                diff >= 3 -> { score -= 10; reasons += "Пульс покоя немного повышен (+${diff.roundToInt()})" }
                else -> reasons += "Пульс покоя в норме: $restToday"
            }
        }

        // ---- Morning test (own) or watch HRV ----
        val test = tests.lastOrNull()?.takeIf { now - it.time < 16 * 3600_000L }
        if (test != null && test.status >= 0) {
            when (test.status) {
                2 -> { score -= 30; reasons += if (test.rmssd > 0) "Утренний тест: вариабельность пульса сильно ниже нормы" else "Утренний тест: пульс покоя сильно выше нормы" }
                1 -> { score -= 15; reasons += if (test.rmssd > 0) "Утренний тест: вариабельность пульса ниже нормы" else "Утренний тест: пульс покоя выше нормы" }
                else -> reasons += "Утренний тест: восстановление хорошее"
            }
        } else {
            val hrvBase = days.filter { it.day < (today?.day ?: now) }.takeLast(14).mapNotNull { it.hrvMs }
            val hrv = today?.hrvMs
            if (hrv != null && hrvBase.size >= 4) {
                val ratio = hrv / hrvBase.average()
                when {
                    ratio < 0.75 -> { score -= 20; reasons += "Вариабельность пульса (часы) заметно ниже обычной" }
                    ratio < 0.9 -> { score -= 10; reasons += "Вариабельность пульса (часы) немного снижена" }
                }
            }
        }

        // ---- Training load: acute (7 d) vs chronic (28 d) ----
        val acute = workouts.filter { it.start > now - 7 * DAY }.sumOf { it.trimp }
        val chronicWeek = workouts.filter { it.start > now - 28 * DAY }.sumOf { it.trimp } / 4.0
        if (chronicWeek > 30) {
            val acwr = acute / chronicWeek
            when {
                acwr > 1.5 -> { score -= 15; reasons += "Нагрузка за неделю резко выросла (×${"%.1f".format(acwr)}) — риск перегрузки" }
                acwr < 0.6 -> reasons += "Нагрузка за неделю ниже обычной — можно добавить"
            }
        }

        // ---- Recovery after the last session ----
        val last = workouts.maxByOrNull { it.end }
        val hoursSince = last?.let { (now - it.end) / 3600_000.0 }
        if (last != null && hoursSince != null) {
            val left = last.recoveryHours - hoursSince
            if (left > 0) {
                score -= minOf(20, (left / 2).roundToInt() + 5)
                reasons += "После «${last.title}» прошло ${hoursSince.roundToInt()} ч из ~${last.recoveryHours} ч восстановления"
            }
        }

        score = score.coerceIn(0, 100)
        val level = when { score >= 70 -> 0; score >= 45 -> 1; else -> 2 }

        // ---- Recent history ----
        val week = workouts.filter { it.start > now - 7 * DAY }
        val z2 = week.sumOf { it.zoneSec[2] + it.zoneSec[3] } / 60
        val hard = week.sumOf { it.zoneSec[4] + it.zoneSec[5] } / 60
        val strengthDays = week.filter { w -> w.segments.any { it.type.strength && it.sets.isNotEmpty() } }
            .map { it.start / DAY }.distinct().size
        val lastStrength = workouts.filter { w -> w.segments.any { it.type.strength } }.maxOfOrNull { it.end }
        val hSinceStrength = lastStrength?.let { (now - it) / 3600_000.0 } ?: 999.0
        val lastHard = workouts.filter { it.zoneSec[4] + it.zoneSec[5] > 300 }.maxOfOrNull { it.end }
        val hSinceHard = lastHard?.let { (now - it) / 3600_000.0 } ?: 999.0
        val other = days.filter { it.day > now - 7 * DAY }.sumOf { it.otherWorkoutMin ?: 0 }

        val z = { a: Int, b: Int -> "${bounds[a - 1]}–${bounds[b]}" }
        val ready = Physiology.readyHr(p)

        // ---- Today's plan ----
        val plan = ArrayList<String>()
        val headline: String
        when {
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
                if (hSinceStrength >= 48 && goal != Goal.HEART)
                    plan += "Или облегчённая силовая: 2–3 подхода на упражнение, не до отказа (оставьте 3–4 повтора в запасе)."
                plan += "Без интервалов в зонах 4–5."
            }
            else -> {
                val strengthDue = hSinceStrength >= 48 && strengthDays < 3
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

        return CoachAdvice(type, whenText, score, level, headline, reasons, plan, weekLines, nutrition, progress, tips)
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

    fun levelText(level: Int) = when (level) { 0 -> "Отличная готовность"; 1 -> "Средняя готовность"; else -> "Нужен отдых" }

    @Suppress("unused")
    private fun maxOf0(a: Int, b: Int) = max(a, b)
}
