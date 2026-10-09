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
 * Food and drinks → Health Connect → Samsung Health (food diary and water), like the workouts.
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
                try { writeDay(ctx, d, FoodStore.entries(d)) } catch (_: Exception) {}
            }
        }
    }

    /** Write the last [days] days (after granting access, or on app start). */
    suspend fun syncRecent(ctx: Context, days: Int = 14): Int {
        var n = 0
        val today = LocalDate.now()
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            try { if (writeDay(ctx, d, FoodStore.entries(d))) n++ } catch (_: Exception) {}
        }
        return n
    }

    private fun hcMeal(m: Int): Int = when (m) {
        Meals.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
        Meals.LUNCH -> MealType.MEAL_TYPE_LUNCH
        Meals.DINNER -> MealType.MEAL_TYPE_DINNER
        else -> MealType.MEAL_TYPE_SNACK
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
