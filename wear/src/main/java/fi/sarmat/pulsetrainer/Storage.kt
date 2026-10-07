package fi.sarmat.pulsetrainer

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import fi.sarmat.pulsetrainer.core.HrvRecord
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.StressRecord
import fi.sarmat.pulsetrainer.core.Workout
import fi.sarmat.pulsetrainer.core.WorkoutJson
import fi.sarmat.pulsetrainer.core.WorkoutType
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Storage.init(this)
        HrSensor.init(this)
        Haptics.init(this)
        Passive.init(this)
        Passive.register(this)
    }
}

object Storage {
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private lateinit var appCtx: Context

    val profile = MutableStateFlow(Profile())

    fun ensureInit(ctx: Context) { if (!::prefs.isInitialized) init(ctx.applicationContext) }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = ctx.getSharedPreferences("pt", Context.MODE_PRIVATE)
        dir = File(ctx.filesDir, "workouts").apply { mkdirs() }
        profile.value = loadProfile()
        coach.value = parseCoach(prefs.getString("coach", null))
        favorites.value = loadFav()
        fontScale.value = prefs.getFloat("font", 1.15f)
        PhoneLink.sendProfile(appCtx)
    }

    // ---------- Profile ----------

    private fun loadProfile() = Profile(
        age = prefs.getInt("age", 44),
        weightKg = prefs.getFloat("weight", 92f).toDouble(),
        heightCm = prefs.getInt("height", 177),
        male = prefs.getBoolean("male", true),
        restHr = prefs.getInt("restHr", 0).takeIf { it > 0 },
        maxHrOverride = prefs.getInt("maxHr", 0).takeIf { it > 0 },
        karvonen = prefs.getBoolean("karvonen", false),
    )

    fun saveProfile(p: Profile) {
        prefs.edit()
            .putInt("age", p.age).putFloat("weight", p.weightKg.toFloat()).putInt("height", p.heightCm)
            .putBoolean("male", p.male).putInt("restHr", p.restHr ?: 0).putInt("maxHr", p.maxHrOverride ?: 0)
            .putBoolean("karvonen", p.karvonen)
            .apply()
        profile.value = p
        PhoneLink.sendProfile(appCtx)
    }

    /** Whether resting HR is updated automatically by the morning test. */
    var autoRestHr: Boolean
        get() = prefs.getBoolean("autoRest", true)
        set(v) { prefs.edit().putBoolean("autoRest", v).apply() }

    // ---------- Sensor ----------

    fun sensorAddress(): String? = prefs.getString("sensorAddr", null)
    fun sensorName(): String? = prefs.getString("sensorName", null)
    fun saveSensor(addr: String?, name: String?) {
        prefs.edit().putString("sensorAddr", addr).putString("sensorName", name).apply()
    }

    // ---------- Recent exercise types (shown first in the switch list) ----------

    fun recentTypes(): List<WorkoutType> =
        (prefs.getString("recent", "") ?: "").split(',').filter { it.isNotBlank() }.map { WorkoutType.of(it) }.distinct()

    fun touchType(t: WorkoutType) {
        val list = (listOf(t) + recentTypes().filter { it != t }).take(6)
        prefs.edit().putString("recent", list.joinToString(",") { it.name }).apply()
    }

    /** Switch list: recently used first, then the rest in your order. */
    fun orderedTypes(): List<WorkoutType> {
        val r = recentTypes()
        return r + typeOrder().filter { it !in r }
    }

    // ---------- Your own order of exercises + main menu / «Другие виды» ----------

    fun typeOrder(): List<WorkoutType> {
        val saved = (prefs.getString("order", "") ?: "").split(',').filter { it.isNotBlank() }
            .mapNotNull { n -> WorkoutType.entries.firstOrNull { it.name == n } }.distinct()
        return saved + WorkoutType.entries.filter { it !in saved }
    }

    private fun saveOrder(list: List<WorkoutType>) {
        prefs.edit().putString("order", list.joinToString(",") { it.name }).apply()
    }

    fun hiddenTypes(): Set<WorkoutType> {
        val raw = prefs.getString("hidden", null) ?: return WorkoutType.entries.filter { it.extra }.toSet()
        return raw.split(',').filter { it.isNotBlank() }.mapNotNull { n -> WorkoutType.entries.firstOrNull { it.name == n } }.toSet()
    }

    fun setHidden(t: WorkoutType, hidden: Boolean) {
        val set = hiddenTypes().toMutableSet()
        if (hidden) set += t else set -= t
        prefs.edit().putString("hidden", set.joinToString(",") { it.name }).apply()
    }

    fun mainTypes(): List<WorkoutType> { val h = hiddenTypes(); return typeOrder().filter { it !in h } }
    fun moreTypes(): List<WorkoutType> { val h = hiddenTypes(); return typeOrder().filter { it in h } }

    /** Move an exercise up (delta = -1) or down (+1) among the main-menu items. */
    fun move(t: WorkoutType, delta: Int) {
        val order = typeOrder().toMutableList()
        val visible = mainTypes()
        val i = visible.indexOf(t)
        val j = i + delta
        if (i < 0 || j !in visible.indices) return
        val other = visible[j]
        val a = order.indexOf(t); val b = order.indexOf(other)
        order[a] = other; order[b] = t
        saveOrder(order)
    }

    fun moveToTop(t: WorkoutType) {
        val order = typeOrder().toMutableList()
        order.remove(t); order.add(0, t)
        saveOrder(order)
    }

    // ---------- Workouts ----------

    fun save(w: Workout) {
        File(dir, "${w.id}.json").writeText(WorkoutJson.toJson(w))
    }

    fun load(id: String): Workout? = try {
        WorkoutJson.fromJson(File(dir, "$id.json").readText())
    } catch (_: Exception) { null }

    fun list(): List<Workout> = (dir.listFiles() ?: emptyArray())
        .sortedByDescending { it.lastModified() }
        .take(40)
        .mapNotNull { f -> try { WorkoutJson.fromJson(f.readText()) } catch (_: Exception) { null } }

    fun lastWorkoutEnd(): Long? = list().maxOfOrNull { it.end }
    fun lastWorkout(): Workout? = list().maxByOrNull { it.end }

    // ---------- Morning HRV ----------

    fun hrvHistory(): List<HrvRecord> = WorkoutJson.hrvFromJson(prefs.getString("hrv", null))

    fun addHrv(r: HrvRecord) {
        val list = (hrvHistory() + r).sortedBy { it.time }.takeLast(60)
        prefs.edit().putString("hrv", WorkoutJson.hrvToJson(list)).commit()
        if (autoRestHr) {
            val last = list.takeLast(7).map { it.restHr }
            val rest = last.average().toInt()
            saveProfile(profile.value.copy(restHr = rest))
        } else {
            PhoneLink.sendProfile(appCtx)
        }
    }

    // ---------- Favourites (★, up to 6) — shown in the watch tiles and on the phone ----------

    val DEFAULT_FAV = listOf(WorkoutType.STRENGTH, WorkoutType.ELLIPTICAL, WorkoutType.WALK, WorkoutType.TREADMILL, WorkoutType.STAIRS_HOME, WorkoutType.PULL_UPS)

    val favorites = MutableStateFlow<List<WorkoutType>>(emptyList())

    private fun loadFav(): List<WorkoutType> {
        val raw = prefs.getString("fav", null) ?: return DEFAULT_FAV
        return raw.split(',').filter { it.isNotBlank() }.mapNotNull { n -> WorkoutType.entries.firstOrNull { it.name == n } }.take(6)
    }

    fun setFavorites(list: List<WorkoutType>, fromPhone: Boolean = false) {
        val l = list.distinct().take(6)
        prefs.edit().putString("fav", l.joinToString(",") { it.name }).apply()
        favorites.value = l
        FavTiles.refresh(appCtx)
        if (!fromPhone) PhoneLink.sendProfile(appCtx)
    }

    fun toggleFavorite(t: WorkoutType) {
        val cur = favorites.value
        setFavorites(if (t in cur) cur - t else (cur + t).takeLast(6))
    }

    // ---------- Per-exercise settings ----------

    /** Auto-pause (GPS workouts): stops time and distance when you stand still. Off by default. */
    fun autoPause(t: WorkoutType): Boolean = prefs.getBoolean("ap_${t.name}", false)
    fun setAutoPause(t: WorkoutType, on: Boolean) { prefs.edit().putBoolean("ap_${t.name}", on).apply() }

    /** A short vibration every kilometre (every 5 km on the bike). On by default. */
    fun kmAlert(t: WorkoutType): Boolean = prefs.getBoolean("km_${t.name}", true)
    fun setKmAlert(t: WorkoutType, on: Boolean) { prefs.edit().putBoolean("km_${t.name}", on).apply() }

    // ---------- Stress ----------

    fun stressHistory(): List<StressRecord> = WorkoutJson.stressFromJson(prefs.getString("stress", null))

    fun addStress(r: StressRecord) {
        val list = (stressHistory() + r).sortedBy { it.time }.takeLast(60)
        prefs.edit().putString("stress", WorkoutJson.stressToJson(list)).commit()
        PhoneLink.sendProfile(appCtx)
    }

    // ---------- Readiness and plan from the phone's coach ----------

    data class CoachInfo(val score: Int, val level: Int, val label: String, val headline: String, val plan: List<String>, val time: Long,
                         val energy: Int = -1, val energyLabel: String = "")

    val coach = MutableStateFlow<CoachInfo?>(null)

    fun saveCoach(json: String) {
        prefs.edit().putString("coach", json).apply()
        coach.value = parseCoach(json)
    }

    private fun parseCoach(json: String?): CoachInfo? = try {
        val o = org.json.JSONObject(json ?: "")
        val a = o.optJSONArray("plan")
        CoachInfo(o.getInt("score"), o.getInt("level"), o.optString("label"), o.optString("headline"),
            (0 until (a?.length() ?: 0)).map { a!!.getString(it) }, o.optLong("t"), o.optInt("energy", -1), o.optString("energyLabel"))
    } catch (_: Exception) { null }

    /** Today's coach info (older than 20 h is not shown). */
    fun todayCoach(): CoachInfo? = coach.value?.takeIf { System.currentTimeMillis() - it.time < 20 * 3600_000L }

    // ---------- Text size on the watch ----------

    val fontScale = MutableStateFlow(1.15f)
    fun setFontScale(v: Float) {
        fontScale.value = v.coerceIn(1.0f, 1.45f)
        prefs.edit().putFloat("font", fontScale.value).apply()
    }

    fun todayHrv(): HrvRecord? {
        val r = hrvHistory().lastOrNull() ?: return null
        return if (System.currentTimeMillis() - r.time < 14 * 3600_000L) r else null
    }
}
