package fi.sarmat.pulsetrainer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import fi.sarmat.pulsetrainer.core.PassiveDay
import fi.sarmat.pulsetrainer.core.Protocol
import fi.sarmat.pulsetrainer.core.WorkoutJson
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Background data on the watch with almost no battery cost.
 *
 * Health Services "passive monitoring" does not switch on any sensor by itself: it hands us
 * the heart rate and steps the watch already measures all day (Samsung's own 24/7 tracking),
 * in batches, a few times per hour, plus "asleep / awake" changes. Nothing runs between batches.
 *
 * From that we keep 5-minute heart-rate buckets for ~36 h and, when you wake up, compute the
 * night's resting pulse (lowest 30-min average) and send one small summary to the phone.
 * Sending is limited to once per hour (and right after waking up).
 */
object Passive {
    private const val BUCKET = 5 * 60_000L
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("passive", Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = prefs.getBoolean("on", true)
        set(v) { prefs.edit().putBoolean("on", v).apply() }

    fun hasPermissions(ctx: Context): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < 33 || ok("android.permission.BODY_SENSORS_BACKGROUND")
        return ok(Manifest.permission.BODY_SENSORS) && bg
    }

    /** Register (or refresh) the background listener. Safe to call often. */
    fun register(ctx: Context) {
        if (!enabled) { unregister(ctx); return }
        try {
            val client = HealthServices.getClient(ctx).passiveMonitoringClient
            val caps = client.getCapabilitiesAsync()
            caps.addListener({
                try {
                    val supported = caps.get().supportedDataTypesPassiveMonitoring
                    val actOk = Build.VERSION.SDK_INT < 29 ||
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                    val types = HashSet<DataType<*, *>>()
                    if (DataType.HEART_RATE_BPM in supported && hasPermissions(ctx)) types.add(DataType.HEART_RATE_BPM)
                    if (DataType.STEPS_DAILY in supported && actOk) types.add(DataType.STEPS_DAILY)
                    if (DataType.FLOORS_DAILY in supported && actOk) types.add(DataType.FLOORS_DAILY)
                    if (types.isEmpty() && !actOk) return@addListener
                    val config = PassiveListenerConfig.builder()
                        .setDataTypes(types)
                        .setShouldUserActivityInfoBeRequested(actOk)
                        .build()
                    client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config)
                    prefs.edit().putLong("registered", System.currentTimeMillis()).apply()
                } catch (_: Throwable) {}
            }, ContextCompat.getMainExecutor(ctx))
        } catch (_: Throwable) {}
    }

    fun unregister(ctx: Context) {
        try { HealthServices.getClient(ctx).passiveMonitoringClient.clearPassiveListenerServiceAsync() } catch (_: Throwable) {}
    }

    fun lastData(): Long = prefs.getLong("lastData", 0L)

    // ---------- Heart-rate buckets: key = start of 5-min bucket, value = "sum,count,min,max" ----------

    private fun buckets(): MutableMap<Long, IntArray> {
        val out = HashMap<Long, IntArray>()
        try {
            val o = JSONObject(prefs.getString("hr", "{}") ?: "{}")
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                out[k.toLong()] = intArrayOf(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveBuckets(b: Map<Long, IntArray>) {
        val cut = System.currentTimeMillis() - 36 * 3600_000L
        val o = JSONObject()
        b.filterKeys { it >= cut }.forEach { (k, v) -> o.put(k.toString(), JSONArray().put(v[0]).put(v[1]).put(v[2]).put(v[3])) }
        prefs.edit().putString("hr", o.toString()).apply()
    }

    @Synchronized
    fun addHeartRate(samples: List<Pair<Long, Int>>) {
        if (samples.isEmpty()) return
        val b = buckets()
        samples.forEach { (t, v) ->
            if (v !in 30..230) return@forEach
            val k = t - t % BUCKET
            val a = b.getOrPut(k) { intArrayOf(0, 0, 999, 0) }
            a[0] += v; a[1]++; a[2] = minOf(a[2], v); a[3] = maxOf(a[3], v)
        }
        saveBuckets(b)
        prefs.edit().putLong("lastData", System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun setFloors(day: Long, floors: Double) {
        prefs.edit().putFloat("floors_$day", floors.toFloat()).apply()
    }

    @Synchronized
    fun setSteps(day: Long, steps: Long) {
        prefs.edit().putLong("steps_$day", steps).putLong("lastData", System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun onActivity(state: UserActivityState, at: Long, ctx: Context) {
        if (state == UserActivityState.USER_ACTIVITY_ASLEEP) {
            if (prefs.getLong("asleep", 0L) == 0L) prefs.edit().putLong("asleep", at).apply()
            return
        }
        val since = prefs.getLong("asleep", 0L)
        if (since == 0L) return
        prefs.edit().putLong("asleep", 0L).apply()
        if (at - since < 2 * 3600_000L) return // a nap or a false "asleep"
        val day = dayStart(at)
        prefs.edit().putLong("night_${day}_s", since).putLong("night_${day}_e", at).apply()
        send(ctx, force = true)
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun dayStart(t: Long): Long = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Lowest 30-minute (6 buckets) average between a and b. */
    private fun lowest30(b: Map<Long, IntArray>, a: Long, e: Long): Int? {
        val keys = b.keys.filter { it in a..e }.sorted()
        if (keys.size < 12) return null
        var best = Double.MAX_VALUE
        for (i in 0..keys.size - 6) {
            if (keys[i + 5] - keys[i] > 40 * 60_000L) continue
            var sum = 0; var cnt = 0
            for (j in i until i + 6) { val v = b[keys[j]]!!; sum += v[0]; cnt += v[1] }
            if (cnt > 0) best = minOf(best, sum.toDouble() / cnt)
        }
        return if (best == Double.MAX_VALUE) null else best.roundToInt()
    }

    /** Summaries of the last days (kept on the watch for a week, merged on the phone). */
    @Synchronized
    fun summaries(): List<PassiveDay> {
        val b = buckets()
        val days = ArrayList<PassiveDay>()
        val today = LocalDate.now(zone)
        for (k in 6 downTo 0) {
            val d = today.minusDays(k.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val old = WorkoutJson.passiveFromJson(prefs.getString("days", null)).firstOrNull { it.day == d }
            val s0 = prefs.getLong("night_${d}_s", 0L).takeIf { it > 0 }
            val e0 = prefs.getLong("night_${d}_e", 0L).takeIf { it > 0 }
            // No sleep info from the watch: use the classic night window 00:00–06:00.
            val fallback = s0 == null && k == 0 && System.currentTimeMillis() - d > 7 * 3600_000L
            val s: Long? = if (fallback) d else s0
            val e: Long? = if (fallback) d + 6 * 3600_000L else e0
            val rest = if (s != null && e != null) lowest30(b, s, e) else null
            val night = if (s != null && e != null) b.filterKeys { it in s..e }.values.let { v -> if (v.isEmpty()) null else v.sumOf { it[0] } / v.sumOf { it[1] }.coerceAtLeast(1) } else null
            val dayB = b.filterKeys { it >= d && it < d + 86400_000L }.values
            val steps = prefs.getLong("steps_$d", -1L).takeIf { it >= 0 }
            val p = PassiveDay(
                day = d,
                restHr = rest ?: old?.restHr,
                nightAvg = night ?: old?.nightAvg,
                sleepStart = s ?: old?.sleepStart,
                sleepEnd = e ?: old?.sleepEnd,
                steps = steps ?: old?.steps,
                hrMin = dayB.minOfOrNull { it[2] } ?: old?.hrMin,
                hrMax = dayB.maxOfOrNull { it[3] } ?: old?.hrMax,
                dayAvg = if (dayB.isEmpty()) old?.dayAvg else dayB.sumOf { it[0] } / dayB.sumOf { it[1] }.coerceAtLeast(1),
                floors = prefs.getFloat("floors_$d", -1f).takeIf { it >= 0 }?.toInt() ?: old?.floors,
            )
            if (p.restHr != null || p.steps != null || p.hrMin != null || p.floors != null) days += p
        }
        prefs.edit().putString("days", WorkoutJson.passiveToJson(days)).apply()
        return days
    }

    /** 5-minute pulse averages of the last 24 h: [[time, bpm], ...] — for the live energy curve on the phone. */
    @Synchronized
    fun recentHrJson(): String {
        val cut = System.currentTimeMillis() - 24 * 3600_000L
        val a = JSONArray()
        buckets().filterKeys { it >= cut }.toSortedMap().forEach { (k, v) -> if (v[1] > 0) a.put(JSONArray().put(k + BUCKET / 2).put(v[0] / v[1])) }
        return a.toString()
    }

    /** Push the summary to the phone: at most once per hour unless [force]. */
    fun send(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("sent", 0L) < 30 * 60_000L) return
        prefs.edit().putLong("sent", now).apply()
        try {
            val req = PutDataMapRequest.create(Protocol.PATH_PASSIVE).apply {
                dataMap.putString("days", WorkoutJson.passiveToJson(summaries()))
                dataMap.putString("hr", recentHrJson())
                dataMap.putLong("ts", now)
            }.asPutDataRequest()
            Wearable.getDataClient(ctx).putDataItem(req)
        } catch (_: Exception) {}
    }

    fun bootInstant(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
}

/** Receives batches from Health Services. Runs for a moment, then the system stops it. */
class PassiveDataService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        try {
            Passive.init(this)
            val boot = Passive.bootInstant()
            val hr = dataPoints.getData(DataType.HEART_RATE_BPM).map { it.getTimeInstant(boot).toEpochMilli() to it.value.roundToInt() }
            Passive.addHeartRate(hr)
            dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let { s ->
                val end = s.getEndInstant(boot).toEpochMilli()
                val day = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (end >= day) Passive.setSteps(day, s.value)
            }
            dataPoints.getData(DataType.FLOORS_DAILY).lastOrNull()?.let { f ->
                val day = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (f.getEndInstant(boot).toEpochMilli() >= day) Passive.setFloors(day, f.value)
            }
            Passive.send(this)
        } catch (_: Throwable) {}
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        try {
            Passive.init(this)
            Passive.onActivity(info.userActivityState, info.stateChangeTime.toEpochMilli(), this)
        } catch (_: Throwable) {}
    }
}
