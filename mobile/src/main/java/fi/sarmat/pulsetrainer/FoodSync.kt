package fi.sarmat.pulsetrainer

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import fi.sarmat.pulsetrainer.core.FoodEntry
import fi.sarmat.pulsetrainer.core.Meals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Food and drinks → Health Connect → Samsung Health (food diary and water), like the workouts;
 * and back: what you log in Samsung Health comes into the diary ([readExternal]).
 *
 * Each day is written as a whole: first the day's records written by PulseTrainer are removed
 * (Health Connect only lets an app delete its own data), then the current diary is written again.
 * So edits and deletions in the app always end up correct in Samsung Health, with no duplicates.
 */
object FoodSync {
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<LocalDate, Job>()
    private var appCtx: Context? = null

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    /** The diary of this day changed: write it 3 s later (several quick taps = one write). */
    fun dayChanged(d: LocalDate) {
        val ctx = appCtx ?: return
        synchronized(pending) {
            pending[d]?.cancel()
            pending[d] = scope.launch {
                delay(3000)
                try { writeDay(ctx, d, FoodStore.ownEntries(d)) } catch (_: Exception) {}
            }
        }
    }

    /** Write the last [days] days (after granting access, or on app start). */
    suspend fun syncRecent(ctx: Context, days: Int = 14): Int {
        var n = 0
        val today = LocalDate.now()
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            try { if (writeDay(ctx, d, FoodStore.ownEntries(d))) n++ } catch (_: Exception) {}
        }
        return n
    }

    private fun hcMeal(m: Int): Int = when (m) {
        Meals.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
        Meals.LUNCH -> MealType.MEAL_TYPE_LUNCH
        Meals.DINNER -> MealType.MEAL_TYPE_DINNER
        else -> MealType.MEAL_TYPE_SNACK
    }

    /** Diary id prefix of entries that came from Samsung Health (they are shown, never written back). */
    const val EXT = "hc:"

    private fun ourMeal(t: Int): Int = when (t) {
        MealType.MEAL_TYPE_BREAKFAST -> Meals.BREAKFAST
        MealType.MEAL_TYPE_LUNCH -> Meals.LUNCH
        MealType.MEAL_TYPE_DINNER -> Meals.DINNER
        else -> -1   // snack / unknown: taken from the time
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(
        c: HealthConnectClient, type: kotlin.reflect.KClass<T>, from: Instant, to: Instant,
    ): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        var pages = 0
        do {
            val resp = c.readRecords(androidx.health.connect.client.request.ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = token))
            out += resp.records
            token = resp.pageToken
            pages++
        } while (token != null && pages < 10)
        return out
    }

    /**
     * Food and water you logged in Samsung Health itself (on the watch or the phone), last [days] days.
     * They join the diary marked «Samsung Health» and count in calories, БЖУ and water. PulseTrainer's own
     * records are skipped here — they are in the diary already. null = no access (nothing changes).
     */
    suspend fun readExternal(ctx: Context, days: Int = 14): Map<LocalDate, List<FoodEntry>>? {
        if (!HealthSync.available(ctx)) return null
        val c = HealthConnectClient.getOrCreate(ctx)
        val g = c.permissionController.getGrantedPermissions()
        val canFood = HealthPermission.getReadPermission(NutritionRecord::class) in g
        val canWater = HealthPermission.getReadPermission(HydrationRecord::class) in g
        if (!canFood && !canWater) return null
        val zone = ZoneId.systemDefault()
        val from = LocalDate.now().minusDays(days - 1L).atStartOfDay(zone).toInstant()
        val to = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant()
        val out = ArrayList<FoodEntry>()
        if (canFood) readAll(c, NutritionRecord::class, from, to).filter { it.metadata.dataOrigin.packageName != ctx.packageName }.forEach { r ->
            val kcal = r.energy?.inKilocalories ?: 0.0
            val p = r.protein?.inGrams ?: 0.0
            val f = r.totalFat?.inGrams ?: 0.0
            // Only calories without БЖУ (quick entry): counted as carbohydrates so the day's calories are right.
            val cb = if (p + f + (r.totalCarbohydrate?.inGrams ?: 0.0) < 0.5 && kcal > 0) kcal / 4 else r.totalCarbohydrate?.inGrams ?: 0.0
            if (p * 4 + f * 9 + cb * 4 < 1) return@forEach
            val name = r.name?.takeIf { it.isNotBlank() } ?: "Еда"
            out += FoodEntry(r.startTime.toEpochMilli(), EXT + r.metadata.id, "$name · Samsung Health", 0.0, p, f, cb, 0, ourMeal(r.mealType))
        }
        if (canWater) readAll(c, HydrationRecord::class, from, to).filter { it.metadata.dataOrigin.packageName != ctx.packageName }.forEach { r ->
            val ml = r.volume.inMilliliters
            if (ml < 1) return@forEach
            out += FoodEntry(r.startTime.toEpochMilli(), EXT + r.metadata.id, "Вода · Samsung Health", ml, 0.0, 0.0, 0.0, Math.round(ml).toInt())
        }
        return out.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
    }

    /** Returns true when something was written. */
    suspend fun writeDay(ctx: Context, d: LocalDate, entries: List<FoodEntry>): Boolean {
        if (!HealthSync.available(ctx)) return false
        val c = HealthConnectClient.getOrCreate(ctx)
        val g = c.permissionController.getGrantedPermissions()
        val canFood = HealthPermission.getWritePermission(NutritionRecord::class) in g
        val canWater = HealthPermission.getWritePermission(HydrationRecord::class) in g
        if (!canFood && !canWater) return false
        val zone = ZoneId.systemDefault()
        val from = d.atStartOfDay(zone).toInstant()
        val to = d.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(from, to)
        // Only PulseTrainer's own records of this day are removed.
        if (canFood) c.deleteRecords(NutritionRecord::class, range)
        if (canWater) c.deleteRecords(HydrationRecord::class, range)

        val records = ArrayList<androidx.health.connect.client.records.Record>()
        entries.forEachIndexed { i, e ->
            val st = Instant.ofEpochMilli(e.time).coerceIn(from, to.minusSeconds(61))
            val en = st.plusSeconds(60)
            val off = zone.rules.getOffset(st)
            if (canFood && e.kcal >= 1) records += NutritionRecord(
                startTime = st, startZoneOffset = off, endTime = en, endZoneOffset = off,
                name = e.name,
                mealType = hcMeal(FoodStore.mealOf(e)),
                energy = Energy.kilocalories(e.kcal),
                protein = Mass.grams(e.p),
                totalFat = Mass.grams(e.f),
                totalCarbohydrate = Mass.grams(e.c),
                metadata = Metadata(clientRecordId = "ptf-$d-$i-${e.time}", clientRecordVersion = 1),
            )
            if (canWater && e.drinkMl > 0) records += HydrationRecord(
                startTime = st, startZoneOffset = off, endTime = en, endZoneOffset = off,
                volume = Volume.milliliters(e.drinkMl.toDouble()),
                metadata = Metadata(clientRecordId = "ptw-$d-$i-${e.time}", clientRecordVersion = 1),
            )
        }
        if (records.isNotEmpty()) c.insertRecords(records)
        return records.isNotEmpty()
    }
}
