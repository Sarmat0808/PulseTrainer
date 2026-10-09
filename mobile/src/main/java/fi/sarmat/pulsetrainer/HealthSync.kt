package fi.sarmat.pulsetrainer

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import fi.sarmat.pulsetrainer.core.Mode
import fi.sarmat.pulsetrainer.core.Segment
import fi.sarmat.pulsetrainer.core.Workout
import fi.sarmat.pulsetrainer.core.WorkoutType
import fi.sarmat.pulsetrainer.core.fmtDuration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.max

/**
 * Writes sessions to Health Connect. Samsung Health reads them from there.
 * Each exercise of a multi-exercise session becomes its own Samsung Health workout
 * with the right type; heart rate, calories, distance and the GPS route go along.
 */
object HealthSync {
    const val ROUTE_PERM = "android.permission.health.WRITE_EXERCISE_ROUTE"

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        ROUTE_PERM,
    )

    fun sdkStatus(ctx: Context): Int = HealthConnectClient.getSdkStatus(ctx)
    fun available(ctx: Context) = sdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Set<String> =
        if (!available(ctx)) emptySet() else HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()

    fun hcType(t: WorkoutType): Int = when (t) {
        WorkoutType.STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        WorkoutType.OUTDOOR_STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
        WorkoutType.PULL_UPS -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
        WorkoutType.PUSH_UPS -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
        WorkoutType.SQUATS, WorkoutType.LUNGES -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
        WorkoutType.PLANK -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
        WorkoutType.BOXING -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING
        WorkoutType.TREADMILL -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        WorkoutType.ELLIPTICAL -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        WorkoutType.BIKE_INDOOR -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
        WorkoutType.BIKE_OUTDOOR -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        WorkoutType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        WorkoutType.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        WorkoutType.FOOTBALL -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        WorkoutType.BASKETBALL -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
        WorkoutType.TENNIS -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
        WorkoutType.TABLE_TENNIS -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
        WorkoutType.VOLLEYBALL -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL
        WorkoutType.BADMINTON -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
        WorkoutType.SWIMMING -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        WorkoutType.ROWING -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
        WorkoutType.STAIRS -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
        WorkoutType.STAIRS_HOME, WorkoutType.STAIRS_OUTDOOR -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING
        WorkoutType.HIIT, WorkoutType.TABATA -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        WorkoutType.JUMP_ROPE -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        WorkoutType.HIKING -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        WorkoutType.SKIING -> ExerciseSessionRecord.EXERCISE_TYPE_SKIING
        WorkoutType.SKATING -> ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING
        WorkoutType.DANCING -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        WorkoutType.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        WorkoutType.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    private fun notes(s: Segment): String {
        if (s.sets.isEmpty()) return "PulseTrainer · Polar H10"
        val label = if (s.type.mode == Mode.ROUNDS) "Раунд" else "Подход"
        return s.sets.mapIndexed { i, x ->
            buildString {
                append("$label ${i + 1}")
                if (x.reps > 0) append(": ${x.reps} повт.")
                append(", пик ${x.peakHr}")
                x.hrr60?.let { append(", −$it уд/мин за 60 с") }
                x.restSec?.let { append(", отдых ${fmtDuration(it)}") }
            }
        }.joinToString("\n")
    }

    private fun meta(id: String) = Metadata(clientRecordId = id, clientRecordVersion = 1)

    /**
     * Removes PulseTrainer's own workouts shorter than 3 minutes (test starts) from Health Connect,
     * so they disappear from Samsung Health too. Other apps' data is never touched.
     */
    suspend fun cleanupShort(ctx: Context, days: Int = 30): Int {
        if (!available(ctx)) return 0
        val c = HealthConnectClient.getOrCreate(ctx)
        if (HealthPermission.getWritePermission(ExerciseSessionRecord::class) !in c.permissionController.getGrantedPermissions()) return 0
        val from = Instant.now().minusSeconds(days * 86400L)
        val resp = c.readRecords(androidx.health.connect.client.request.ReadRecordsRequest(ExerciseSessionRecord::class,
            androidx.health.connect.client.time.TimeRangeFilter.between(from, Instant.now()), pageSize = 1000))
        val short = resp.records.filter { it.metadata.dataOrigin.packageName == ctx.packageName &&
            java.time.Duration.between(it.startTime, it.endTime).seconds < 180 }
        if (short.isEmpty()) return 0
        c.deleteRecords(ExerciseSessionRecord::class, short.map { it.metadata.id }, emptyList())
        short.forEach { s ->
            val r = androidx.health.connect.client.time.TimeRangeFilter.between(s.startTime, s.endTime)
            try { c.deleteRecords(HeartRateRecord::class, r) } catch (_: Exception) {}
            try { c.deleteRecords(ActiveCaloriesBurnedRecord::class, r) } catch (_: Exception) {}
            try { c.deleteRecords(TotalCaloriesBurnedRecord::class, r) } catch (_: Exception) {}
            try { c.deleteRecords(DistanceRecord::class, r) } catch (_: Exception) {}
        }
        return short.size
    }

    private fun off(t: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(t)

    /** Returns true if written. */
    suspend fun write(ctx: Context, w: Workout, force: Boolean = false): Boolean {
        // Test starts and accidental taps (< 3 min) don't go to Samsung Health (counted as done).
        if (!force && w.activeSec < 180) return true
        if (!available(ctx)) return false
        val client = HealthConnectClient.getOrCreate(ctx)
        val g = client.permissionController.getGrantedPermissions()
        if (HealthPermission.getWritePermission(ExerciseSessionRecord::class) !in g) return false
        val canHr = HealthPermission.getWritePermission(HeartRateRecord::class) in g
        val canActive = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class) in g
        val canTotal = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class) in g
        val canDist = HealthPermission.getWritePermission(DistanceRecord::class) in g
        val canRoute = ROUTE_PERM in g

        val records = ArrayList<Record>()
        w.segments.forEachIndexed { i, s ->
            val st = Instant.ofEpochMilli(s.start)
            val en = Instant.ofEpochMilli(max(s.end, s.start + 1000))
            val key = "pt-${w.id}-$i"

            val route = if (canRoute && s.type.gps) {
                val pts = w.track.filter { it.t >= s.start && it.t <= s.end }
                if (pts.size >= 2) ExerciseRoute(pts.map {
                    ExerciseRoute.Location(
                        time = Instant.ofEpochMilli(it.t),
                        latitude = it.lat,
                        longitude = it.lon,
                        horizontalAccuracy = it.acc?.let { a -> Length.meters(a.toDouble()) },
                        altitude = it.alt?.let { a -> Length.meters(a) },
                    )
                }) else null
            } else null

            val laps = s.laps.filter { it.end > it.start && it.start >= s.start && it.end <= max(s.end, s.start + 1000) }.map {
                ExerciseLap(Instant.ofEpochMilli(it.start), Instant.ofEpochMilli(it.end), Length.meters(it.distanceM))
            }

            val session = try {
                ExerciseSessionRecord(
                    startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                    exerciseType = hcType(s.type),
                    title = "PulseTrainer · ${s.type.title}",
                    notes = notes(s),
                    metadata = meta("$key-ex"),
                    laps = laps,
                    exerciseRoute = route,
                )
            } catch (e: Exception) {
                ExerciseSessionRecord(
                    startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                    exerciseType = hcType(s.type),
                    title = "PulseTrainer · ${s.type.title}",
                    notes = notes(s),
                    metadata = meta("$key-ex"),
                )
            }
            records += session

            if (canHr) {
                val samples = w.hr.filter { it.t >= s.start && it.t <= s.end }
                samples.chunked(600).forEachIndexed { c, chunk ->
                    if (chunk.isEmpty()) return@forEachIndexed
                    val a = Instant.ofEpochMilli(chunk.first().t)
                    val b = Instant.ofEpochMilli(chunk.last().t + 1000)
                    records += HeartRateRecord(
                        startTime = a, startZoneOffset = off(a), endTime = b, endZoneOffset = off(b),
                        samples = chunk.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.t), it.bpm.toLong()) },
                        metadata = meta("$key-hr-$c"),
                    )
                }
            }
            if (canActive && s.kcalActive > 0) records += ActiveCaloriesBurnedRecord(
                startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                energy = Energy.kilocalories(s.kcalActive), metadata = meta("$key-akcal"),
            )
            if (canTotal && s.kcalTotal > 0) records += TotalCaloriesBurnedRecord(
                startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                energy = Energy.kilocalories(s.kcalTotal), metadata = meta("$key-tkcal"),
            )
            if (canDist && s.distanceM > 1) records += DistanceRecord(
                startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                distance = Length.meters(s.distanceM), metadata = meta("$key-dist"),
            )
        }
        if (records.isEmpty()) return false
        client.insertRecords(records)
        return true
    }
}
