package fi.sarmat.pulsetrainer

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import fi.sarmat.pulsetrainer.core.DailyStats
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Reads what Samsung Health shares through Health Connect: sleep (with stages),
 * resting HR, HRV, steps, SpO2, weight, body fat and workouts recorded by other apps.
 */
object HealthData {

    val READ_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
    )
    val WRITE_WEIGHT: String = HealthPermission.getWritePermission(WeightRecord::class)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun dayStart(t: Instant): Long = t.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private suspend fun <T : Record> read(c: HealthConnectClient, type: KClass<T>, from: Instant, to: Instant): List<T> = try {
        val out = ArrayList<T>()
        var token: String? = null
        var pages = 0
        do {
            val resp = c.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = token))
            out += resp.records
            token = resp.pageToken
            pages++
        } while (token != null && pages < 10)
        out
    } catch (_: Exception) { emptyList() }

    suspend fun loadDays(ctx: Context, days: Int = 35): List<DailyStats> {
        if (!HealthSync.available(ctx)) return emptyList()
        val c = HealthConnectClient.getOrCreate(ctx)
        val granted = try { c.permissionController.getGrantedPermissions() } catch (_: Exception) { return emptyList() }
        val today = LocalDate.now(zone)
        val fromDate = today.minusDays(days.toLong())
        val from = fromDate.atStartOfDay(zone).toInstant()
        val to = Instant.now()

        val map = LinkedHashMap<Long, DailyStats>()
        var d = fromDate
        while (!d.isAfter(today)) {
            val k = d.atStartOfDay(zone).toInstant().toEpochMilli()
            map[k] = DailyStats(k)
            d = d.plusDays(1)
        }
        fun upd(day: Long, f: (DailyStats) -> DailyStats) { map[day]?.let { map[day] = f(it) } }

        // Sleep: assigned to the day you woke up.
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            val sessions = read(c, SleepSessionRecord::class, from.minusSeconds(86400), to)
            sessions.groupBy { dayStart(it.endTime) }.forEach { (day, list) ->
                var total = 0L; var deep = 0L; var rem = 0L; var light = 0L; var awake = 0L
                var hasStages = false
                list.forEach { s ->
                    val dur = (s.endTime.toEpochMilli() - s.startTime.toEpochMilli()) / 60000
                    if (s.stages.isEmpty()) total += dur
                    s.stages.forEach { st ->
                        hasStages = true
                        val m = (st.endTime.toEpochMilli() - st.startTime.toEpochMilli()) / 60000
                        when (st.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP -> { deep += m; total += m }
                            SleepSessionRecord.STAGE_TYPE_REM -> { rem += m; total += m }
                            SleepSessionRecord.STAGE_TYPE_LIGHT -> { light += m; total += m }
                            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += m
                            else -> total += m
                        }
                    }
                }
                val main = list.maxByOrNull { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
                upd(day) {
                    it.copy(
                        sleepStart = main?.startTime?.toEpochMilli(),
                        sleepEnd = main?.endTime?.toEpochMilli(),
                        sleepMin = total.toInt(),
                        deepMin = if (hasStages) deep.toInt() else null,
                        remMin = if (hasStages) rem.toInt() else null,
                        lightMin = if (hasStages) light.toInt() else null,
                        awakeMin = if (hasStages) awake.toInt() else null,
                    )
                }
            }
        }

        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
            read(c, RestingHeartRateRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                upd(day) { it.copy(restHr = l.map { r -> r.beatsPerMinute }.average().toInt()) }
            }
        }
        if (HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) in granted) {
            read(c, HeartRateVariabilityRmssdRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                upd(day) { it.copy(hrvMs = l.map { r -> r.heartRateVariabilityMillis }.average()) }
            }
        }
        if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted) {
            read(c, OxygenSaturationRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                upd(day) { it.copy(spo2 = l.map { r -> r.percentage.value }.average()) }
            }
        }
        if (HealthPermission.getReadPermission(WeightRecord::class) in granted) {
            read(c, WeightRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                upd(day) { it.copy(weightKg = l.maxBy { r -> r.time }.weight.inKilograms) }
            }
        }
        if (HealthPermission.getReadPermission(BodyFatRecord::class) in granted) {
            read(c, BodyFatRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                upd(day) { it.copy(bodyFatPct = l.maxBy { r -> r.time }.percentage.value) }
            }
        }
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
            read(c, ExerciseSessionRecord::class, from, to)
                .filter { it.metadata.dataOrigin.packageName != ctx.packageName }
                .groupBy { dayStart(it.startTime) }.forEach { (day, l) ->
                    upd(day) { it.copy(otherWorkoutMin = l.sumOf { r -> (r.endTime.toEpochMilli() - r.startTime.toEpochMilli()) / 60000 }.toInt()) }
                }
        }
        fun has(k: KClass<out Record>) = HealthPermission.getReadPermission(k) in granted
        if (has(RespiratoryRateRecord::class)) {
            read(c, RespiratoryRateRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                upd(day) { it.copy(respRate = l.map { r -> r.rate }.average()) }
            }
        }
        if (has(BloodPressureRecord::class)) {
            read(c, BloodPressureRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                val last = l.maxBy { r -> r.time }
                upd(day) { it.copy(bpSys = last.systolic.inMillimetersOfMercury.toInt(), bpDia = last.diastolic.inMillimetersOfMercury.toInt()) }
            }
        }
        if (has(Vo2MaxRecord::class)) {
            read(c, Vo2MaxRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                upd(day) { it.copy(vo2max = l.maxBy { r -> r.time }.vo2MillilitersPerMinuteKilogram) }
            }
        }
        if (has(LeanBodyMassRecord::class)) {
            read(c, LeanBodyMassRecord::class, from, to).groupBy { dayStart(it.time) }.forEach { (day, l) ->
                upd(day) { it.copy(leanKg = l.maxBy { r -> r.time }.mass.inKilograms) }
            }
        }
        // Daily totals (Health Connect removes duplicates between apps).
        try {
            val metrics = buildSet {
                if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
                if (has(FloorsClimbedRecord::class)) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
                if (has(HydrationRecord::class)) add(HydrationRecord.VOLUME_TOTAL)
                if (has(HeartRateRecord::class)) { add(HeartRateRecord.BPM_MIN); add(HeartRateRecord.BPM_MAX); add(HeartRateRecord.BPM_AVG) }
            }
            if (metrics.isNotEmpty()) {
                val res = c.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = metrics,
                        timeRangeFilter = TimeRangeFilter.between(fromDate.atStartOfDay(), java.time.LocalDateTime.now()),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
                res.forEach { g ->
                    val day = g.startTime.atZone(zone).toInstant().toEpochMilli()
                    val r = g.result
                    upd(day) {
                        it.copy(
                            activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: it.activeKcal,
                            distanceM = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: it.distanceM,
                            floors = r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL] ?: it.floors,
                            hydrationMl = r[HydrationRecord.VOLUME_TOTAL]?.inMilliliters?.toInt() ?: it.hydrationMl,
                            hrMin = r[HeartRateRecord.BPM_MIN]?.toInt() ?: it.hrMin,
                            hrMax = r[HeartRateRecord.BPM_MAX]?.toInt() ?: it.hrMax,
                            hrAvg = r[HeartRateRecord.BPM_AVG]?.toInt() ?: it.hrAvg,
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            try {
                val res = c.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(fromDate.atStartOfDay(), java.time.LocalDateTime.now()),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
                res.forEach { g ->
                    val day = g.startTime.atZone(zone).toInstant().toEpochMilli()
                    g.result[StepsRecord.COUNT_TOTAL]?.let { s -> upd(day) { it.copy(steps = s) } }
                }
            } catch (_: Exception) {}
        }
        return map.values.toList()
    }

    /** Store a new body weight in Health Connect (so Samsung Health sees it too). */
    suspend fun writeWeight(ctx: Context, kg: Double): Boolean = try {
        if (!HealthSync.available(ctx)) false else {
            val c = HealthConnectClient.getOrCreate(ctx)
            if (WRITE_WEIGHT !in c.permissionController.getGrantedPermissions()) false else {
                val now = Instant.now()
                c.insertRecords(listOf(WeightRecord(time = now, zoneOffset = zone.rules.getOffset(now), weight = Mass.kilograms(kg), metadata = Metadata())))
                true
            }
        }
    } catch (_: Exception) { false }
}
