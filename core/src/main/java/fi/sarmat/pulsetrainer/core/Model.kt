package fi.sarmat.pulsetrainer.core

/** How the workout screen behaves. */
enum class Mode {
    /** Sets with rest between them; next set is allowed by time + heart rate. */
    SETS,
    /** Fixed work/rest rounds on a timer (boxing). */
    ROUNDS,
    /** Continuous cardio: zones, time in zones, distance/pace if available. */
    CARDIO
}

enum class WorkoutType(
    val title: String,
    val short: String,
    val mode: Mode,
    val gps: Boolean = false,
    val repCount: Boolean = false,
    /** Minimum rest between sets, seconds (science-based defaults). */
    val minRestSec: Int = 90,
    val treadmill: Boolean = false,
    /** Auto-lap distance in metres (0 = none). */
    val lapM: Int = 0,
    /** Muscle work: affects the "rest until next workout" advice. */
    val strength: Boolean = false,
    /** Extra sport: lives in the «Другие виды» list until you move it to the main menu. */
    val extra: Boolean = false,
    /** Special note shown in the menu. */
    val note: String? = null,
    /** Rounds mode: work / rest seconds. */
    val roundWork: Int = 180,
    val roundRest: Int = 60,
    /** Climbing matters: the barometer measures height, floors and vertical speed. */
    val climb: Boolean = false,
    /** Steps and cadence from the watch step counter. */
    val steps: Boolean = false,
) {
    STRENGTH("Силовая в зале", "Силовая", Mode.SETS, minRestSec = 120, strength = true),
    OUTDOOR_STRENGTH("Воркаут (уличные турники)", "Воркаут", Mode.SETS, minRestSec = 90, strength = true),
    PULL_UPS("Подтягивания", "Турник", Mode.SETS, repCount = true, minRestSec = 120, strength = true),
    PUSH_UPS("Отжимания", "Отжимания", Mode.SETS, repCount = true, minRestSec = 90, strength = true),
    SQUATS("Приседания", "Приседания", Mode.SETS, repCount = true, minRestSec = 90, strength = true),
    LUNGES("Выпады", "Выпады", Mode.SETS, repCount = true, minRestSec = 90, strength = true),
    PLANK("Планка", "Планка", Mode.ROUNDS, roundWork = 45, roundRest = 30,
        note = "Удержание по таймеру: время планки и отдыха настраиваются"),
    BOXING("Бокс", "Бокс", Mode.ROUNDS),
    TABATA("Табата", "Табата", Mode.ROUNDS, roundWork = 20, roundRest = 10,
        note = "Работа / отдых по таймеру, раунды и циклы — настраиваются"),
    JUMP_ROPE("Скакалка", "Скакалка", Mode.ROUNDS, roundWork = 60, roundRest = 30),
    TREADMILL("Беговая дорожка (зал)", "Дорожка", Mode.CARDIO, treadmill = true, steps = true),
    ELLIPTICAL("Орбитрек", "Орбитрек", Mode.CARDIO),
    BIKE_INDOOR("Велотренажёр", "Вело (зал)", Mode.CARDIO),
    BIKE_OUTDOOR("Велосипед (улица)", "Велосипед", Mode.CARDIO, gps = true, lapM = 5000, climb = true),
    WALK("Ходьба / прогулка", "Ходьба", Mode.CARDIO, gps = true, lapM = 1000, climb = true, steps = true),
    RUN("Бег на улице", "Бег", Mode.CARDIO, gps = true, lapM = 1000, climb = true, steps = true),
    STAIRS_HOME("Лестница в доме", "Лестница", Mode.CARDIO, climb = true, steps = true,
        note = "Подъёмы и спуски по этажам: высота, этажи, темп подъёма"),
    STAIRS_OUTDOOR("Ступеньки на улице", "Ступеньки", Mode.CARDIO, gps = true, climb = true, steps = true,
        note = "Лестницы и подъёмы на улице: этажи, высота, маршрут"),

    // ----- Other sports -----
    FOOTBALL("Футбол", "Футбол", Mode.CARDIO, gps = true, extra = true),
    BASKETBALL("Баскетбол", "Баскетбол", Mode.CARDIO, extra = true),
    TENNIS("Теннис (улица)", "Теннис", Mode.CARDIO, extra = true),
    TABLE_TENNIS("Настольный теннис", "Пинг-понг", Mode.CARDIO, extra = true),
    VOLLEYBALL("Волейбол", "Волейбол", Mode.CARDIO, extra = true),
    BADMINTON("Бадминтон", "Бадминтон", Mode.CARDIO, extra = true),
    SWIMMING("Бассейн", "Бассейн", Mode.CARDIO, extra = true, note = "В воде Bluetooth не работает — пульс с часов"),
    ROWING("Гребной тренажёр", "Гребля", Mode.CARDIO, extra = true),
    STAIRS("Степпер (тренажёр)", "Степпер", Mode.CARDIO, steps = true,
        note = "На тренажёре высота не меняется: этажи считаются по шагам"),
    HIIT("Интервальная (HIIT)", "Интервалы", Mode.ROUNDS, extra = true, roundWork = 40, roundRest = 20),
    HIKING("Поход / скандинавская ходьба", "Поход", Mode.CARDIO, gps = true, lapM = 1000, extra = true, climb = true, steps = true),
    SKIING("Лыжи", "Лыжи", Mode.CARDIO, gps = true, lapM = 1000, extra = true, climb = true),
    SKATING("Коньки", "Коньки", Mode.CARDIO, extra = true),
    DANCING("Танцы", "Танцы", Mode.CARDIO, extra = true),
    YOGA("Йога / растяжка", "Йога", Mode.CARDIO, extra = true),
    OTHER("Другая тренировка", "Другое", Mode.CARDIO, extra = true);

    companion object {
        fun of(name: String): WorkoutType = entries.firstOrNull { it.name == name } ?: STRENGTH
    }
}

/** Interval timer settings (Tabata, HIIT, boxing, jump rope). */
data class IntervalCfg(
    val work: Int,
    val rest: Int,
    val rounds: Int,
    val cycles: Int = 1,
    /** Rest between cycles, seconds. */
    val cycleRest: Int = 60,
    /** Countdown before the first round, seconds. */
    val prep: Int = 10,
) {
    val totalSec: Int get() = prep + cycles * (rounds * work + (rounds - 1) * rest) + (cycles - 1) * cycleRest

    companion object {
        fun default(t: WorkoutType) = when (t) {
            WorkoutType.TABATA -> IntervalCfg(20, 10, 8, 1, 60)
            WorkoutType.HIIT -> IntervalCfg(40, 20, 10, 1, 60)
            WorkoutType.JUMP_ROPE -> IntervalCfg(60, 30, 10, 1, 60)
            WorkoutType.BOXING -> IntervalCfg(180, 60, 6, 1, 60)
            WorkoutType.PLANK -> IntervalCfg(45, 30, 3, 1, 60, prep = 5)
            else -> IntervalCfg(t.roundWork, t.roundRest, 8)
        }
    }
}

data class Profile(
    val age: Int = 44,
    val weightKg: Double = 92.0,
    val heightCm: Int = 177,
    val male: Boolean = true,
    /** Resting HR; filled automatically by the morning test, or set manually. */
    val restHr: Int? = null,
    /** Manual max HR if known from a test; otherwise estimated (Tanaka). */
    val maxHrOverride: Int? = null,
    /** Zones from heart-rate reserve (Karvonen) instead of % of max HR. Off by default. */
    val karvonen: Boolean = false,
)

/** One heart-rate sample: epoch millis, bpm. */
data class HrSample(val t: Long, val bpm: Int)

data class GeoPoint(val t: Long, val lat: Double, val lon: Double, val alt: Double?, val acc: Float?)

/** A set (or a boxing round). Times are epoch millis. */
data class SetRecord(
    val start: Long,
    val end: Long,
    val reps: Int,
    val peakHr: Int,
    /** Heart-rate drop during the first 60 s of rest after this set. */
    val hrr60: Int?,
    /** Rest taken after this set, seconds (null if none followed). */
    val restSec: Int?,
)

data class Lap(val start: Long, val end: Long, val distanceM: Double)

/**
 * One exercise inside a session. A session can contain several segments
 * (e.g. elliptical -> strength -> pull-ups) switched without stopping.
 */
data class Segment(
    val type: WorkoutType,
    val start: Long,
    val end: Long,
    val activeSec: Int,
    val sets: List<SetRecord>,
    val laps: List<Lap>,
    val distanceM: Double,
    val kcalTotal: Double,
    val kcalActive: Double,
    /** Seconds in [below Z1, Z1, Z2, Z3, Z4, Z5]. */
    val zoneSec: IntArray,
    val trimp: Double,
    val avgHr: Int,
    val maxHr: Int,
    /** Metres climbed / descended (barometer), floors (3 m), steps. */
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val floors: Int = 0,
    val steps: Int = 0,
)

data class Workout(
    val id: String,
    val start: Long,
    val end: Long,
    val activeSec: Int,
    val hrSource: String,
    val hr: List<HrSample>,
    val track: List<GeoPoint>,
    val segments: List<Segment>,
    val zoneBounds: IntArray,
    val recoveryHours: Int,
    val syncedToHealth: Boolean = false,
) {
    val title: String
        get() = if (segments.size <= 1) (segments.firstOrNull()?.type?.title ?: "Тренировка")
        else segments.joinToString(" → ") { it.type.short }

    val distanceM: Double get() = segments.sumOf { it.distanceM }
    val kcalTotal: Double get() = segments.sumOf { it.kcalTotal }
    val kcalActive: Double get() = segments.sumOf { it.kcalActive }
    val trimp: Double get() = segments.sumOf { it.trimp }
    val ascentM: Double get() = segments.sumOf { it.ascentM }
    val descentM: Double get() = segments.sumOf { it.descentM }
    val floors: Int get() = segments.sumOf { it.floors }
    val steps: Int get() = segments.sumOf { it.steps }
    val zoneSec: IntArray
        get() = IntArray(6) { i -> segments.sumOf { it.zoneSec[i] } }
    val avgHr: Int get() = if (hr.isEmpty()) 0 else hr.sumOf { it.bpm } / hr.size
    val maxHr: Int get() = hr.maxOfOrNull { it.bpm } ?: 0
}

/**
 * A workout recorded by another app (Samsung Health, auto-detected walks...), read from Health Connect.
 * Load is computed from the heart rate during the session when available, otherwise estimated.
 */
data class ExtWorkout(
    val start: Long,
    val end: Long,
    val title: String,
    val strength: Boolean,
    val avgHr: Int?,
    val maxHr: Int?,
    val trimp: Double,
    /** Seconds in [below Z1, Z1..Z5]. */
    val zoneSec: IntArray,
    /** true = no heart rate in the session, load is a rough estimate from minutes. */
    val estimated: Boolean,
) {
    val minutes: Int get() = ((end - start) / 60000).toInt()
}

/** How you feel today (Polar Recovery Pro style questions). */
data class CheckIn(
    /** Epoch day (local). */
    val day: Long,
    /** 1 = exhausted … 5 = great. */
    val feel: Int,
    /** 0 = no soreness, 1 = some, 2 = strong. */
    val soreness: Int,
)

/** On-demand stress measurement (watch). */
data class StressRecord(
    val time: Long,
    /** 0–100. */
    val score: Int,
    val hr: Int,
    /** 0 when measured with the watch only (no beat-to-beat data). */
    val rmssd: Double,
)

/** One day of passive background data collected by PulseTrainer on the watch. */
data class PassiveDay(
    /** Local midnight, epoch millis. */
    val day: Long,
    /** Lowest 30-min average heart rate of the night. */
    val restHr: Int? = null,
    val nightAvg: Int? = null,
    val sleepStart: Long? = null,
    val sleepEnd: Long? = null,
    val steps: Long? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val dayAvg: Int? = null,
    /** Floors climbed today (watch barometer, ~3 m each). */
    val floors: Int? = null,
    /** Night: wake-ups, awake minutes, lowest pulse (PulseTrainer's own sleep tracking). */
    val wakeups: Int? = null,
    val awakeMin: Int? = null,
    val nightMin: Int? = null,
)

/** Morning readiness test. */
data class HrvRecord(
    val time: Long,
    val rmssd: Double,
    val restHr: Int,
    /** 0 = green, 1 = yellow, 2 = red, -1 = baseline still building. */
    val status: Int,
)
