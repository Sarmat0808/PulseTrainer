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
) {
    STRENGTH("Силовая (зал)", "Силовая", Mode.SETS, minRestSec = 120, strength = true),
    OUTDOOR_STRENGTH("Уличные тренажёры", "Улица", Mode.SETS, minRestSec = 90, strength = true),
    PULL_UPS("Подтягивания", "Турник", Mode.SETS, repCount = true, minRestSec = 120, strength = true),
    PUSH_UPS("Отжимания", "Отжим.", Mode.SETS, repCount = true, minRestSec = 90, strength = true),
    SQUATS("Приседания", "Присед.", Mode.SETS, repCount = true, minRestSec = 90, strength = true),
    BOXING("Бокс", "Бокс", Mode.ROUNDS),
    TREADMILL("Беговая дорожка", "Дорожка", Mode.CARDIO, treadmill = true),
    ELLIPTICAL("Орбитрек", "Орбитрек", Mode.CARDIO),
    BIKE_INDOOR("Велотренажёр", "Велотр.", Mode.CARDIO),
    BIKE_OUTDOOR("Велосипед (улица)", "Велосипед", Mode.CARDIO, gps = true, lapM = 5000),
    WALK("Прогулка", "Прогулка", Mode.CARDIO, gps = true, lapM = 1000),
    RUN("Бег на улице", "Бег", Mode.CARDIO, gps = true, lapM = 1000),

    // ----- Other sports -----
    FOOTBALL("Футбол", "Футбол", Mode.CARDIO, gps = true, extra = true),
    BASKETBALL("Баскетбол", "Баскетбол", Mode.CARDIO, extra = true),
    TENNIS("Теннис (улица)", "Теннис", Mode.CARDIO, extra = true),
    TABLE_TENNIS("Настольный теннис", "Наст. теннис", Mode.CARDIO, extra = true),
    VOLLEYBALL("Волейбол", "Волейбол", Mode.CARDIO, extra = true),
    BADMINTON("Бадминтон", "Бадминтон", Mode.CARDIO, extra = true),
    SWIMMING("Бассейн", "Бассейн", Mode.CARDIO, extra = true, note = "В воде Bluetooth не работает — пульс с часов"),
    ROWING("Гребной тренажёр", "Гребля", Mode.CARDIO, extra = true),
    STAIRS("Степпер / лестница", "Степпер", Mode.CARDIO, extra = true),
    HIIT("Интервальная (HIIT)", "HIIT", Mode.ROUNDS, extra = true, roundWork = 40, roundRest = 20),
    JUMP_ROPE("Скакалка", "Скакалка", Mode.ROUNDS, extra = true, roundWork = 60, roundRest = 30),
    HIKING("Поход / скандинавская ходьба", "Поход", Mode.CARDIO, gps = true, lapM = 1000, extra = true),
    SKIING("Лыжи", "Лыжи", Mode.CARDIO, gps = true, lapM = 1000, extra = true),
    SKATING("Коньки", "Коньки", Mode.CARDIO, extra = true),
    DANCING("Танцы", "Танцы", Mode.CARDIO, extra = true),
    YOGA("Йога / растяжка", "Йога", Mode.CARDIO, extra = true),
    OTHER("Другая тренировка", "Другое", Mode.CARDIO, extra = true);

    companion object {
        fun of(name: String): WorkoutType = entries.firstOrNull { it.name == name } ?: STRENGTH
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
    val zoneSec: IntArray
        get() = IntArray(6) { i -> segments.sumOf { it.zoneSec[i] } }
    val avgHr: Int get() = if (hr.isEmpty()) 0 else hr.sumOf { it.bpm } / hr.size
    val maxHr: Int get() = hr.maxOfOrNull { it.bpm } ?: 0
}

/** Morning readiness test. */
data class HrvRecord(
    val time: Long,
    val rmssd: Double,
    val restHr: Int,
    /** 0 = green, 1 = yellow, 2 = red, -1 = baseline still building. */
    val status: Int,
)
