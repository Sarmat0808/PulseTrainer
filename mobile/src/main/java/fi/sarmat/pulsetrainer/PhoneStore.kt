package fi.sarmat.pulsetrainer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import fi.sarmat.pulsetrainer.core.BodyEntry
import fi.sarmat.pulsetrainer.core.CheckIn
import fi.sarmat.pulsetrainer.core.Coach
import fi.sarmat.pulsetrainer.core.CoachAdvice
import fi.sarmat.pulsetrainer.core.ExtWorkout
import fi.sarmat.pulsetrainer.core.PassiveDay
import fi.sarmat.pulsetrainer.core.StressRecord
import fi.sarmat.pulsetrainer.core.DailyStats
import fi.sarmat.pulsetrainer.core.Goal
import fi.sarmat.pulsetrainer.core.HrvRecord
import fi.sarmat.pulsetrainer.core.WeightEntry
import org.json.JSONArray
import org.json.JSONObject
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.Protocol
import fi.sarmat.pulsetrainer.core.Workout
import fi.sarmat.pulsetrainer.core.WorkoutJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.io.File

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneStore.init(this)
        FoodStore.init(this)
        try { Reminders.schedule(this, keep = true) } catch (_: Exception) {}
    }
}

object PhoneStore {
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences

    val workouts = MutableStateFlow<List<Workout>>(emptyList())
    val profile = MutableStateFlow<Profile?>(null)
    val hrv = MutableStateFlow<List<HrvRecord>>(emptyList())
    val live = MutableStateFlow<Protocol.Live?>(null)
    val goal = MutableStateFlow(Goal.MASS)
    val weights = MutableStateFlow<List<WeightEntry>>(emptyList())
    /** Daily watch data from Health Connect (sleep, resting HR, HRV, steps...). */
    val days = MutableStateFlow<List<DailyStats>>(emptyList())
    val body = MutableStateFlow<List<BodyEntry>>(emptyList())
    /** Workouts from other apps (Samsung Health, auto-detected walks) with load from their heart rate. */
    val ext = MutableStateFlow<List<ExtWorkout>>(emptyList())
    /** Background data PulseTrainer collects on the watch (night pulse, steps). */
    val passive = MutableStateFlow<List<PassiveDay>>(emptyList())
    val stress = MutableStateFlow<List<StressRecord>>(emptyList())
    /** Today's answer to "how do you feel?". */
    val checkIn = MutableStateFlow<CheckIn?>(null)
    val lastPassive = MutableStateFlow(0L)
    /** Heart-rate samples of the last 36 h from Health Connect (for the energy curve). */
    val hrRecent = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())

    fun recovery(): fi.sarmat.pulsetrainer.core.Health.Recovery =
        fi.sarmat.pulsetrainer.core.Health.recovery(workouts.value, ext.value, days.value, passive.value, todayCheckIn(), stress.value)

    /** ECG recordings from the Polar H10 (via the watch). */
    val ecgs = MutableStateFlow<List<fi.sarmat.pulsetrainer.core.EcgRecord>>(emptyList())
    private fun ecgDir() = File(appCtx!!.filesDir, "ecg").apply { mkdirs() }
    fun loadEcgs() {
        ecgs.value = (ecgDir().listFiles() ?: emptyArray()).sortedBy { it.name }
            .mapNotNull { fi.sarmat.pulsetrainer.core.Ecg.fromJson(it.readText()) }
    }

    /** Watch's own 5-minute pulse averages of the last day (sent with the background data). */
    val watchHr = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())

    /** Light refresh for the live energy curve: only the last hours of pulse from Health Connect. */
    suspend fun refreshRecent(ctx: Context) {
        try { HealthData.recentHr(ctx)?.let { if (it.isNotEmpty()) hrRecent.value = it } } catch (_: Exception) {}
    }

    /** Energy 0–100 now, with the day curve and what charged / drained it. */
    fun energy(now: Long = System.currentTimeMillis()): fi.sarmat.pulsetrainer.core.Energy.Result {
        val p = profile.value ?: fi.sarmat.pulsetrainer.core.Profile()
        val today = days.value.lastOrNull()
        val zone = java.time.ZoneId.systemDefault()
        val dayStart = java.time.LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val pToday = passive.value.firstOrNull { it.day == dayStart }
        val wake = (today?.sleepEnd ?: pToday?.sleepEnd)?.takeIf { it in dayStart..now } ?: (dayStart + 7 * 3600_000L).coerceAtMost(now)
        val restToday = today?.restHr ?: pToday?.restHr
        val base = (days.value.filter { it.day < dayStart }.takeLast(14).mapNotNull { it.restHr } +
            passive.value.filter { it.day < dayStart }.takeLast(14).mapNotNull { it.restHr }).takeIf { it.size >= 4 }?.average()
        val rest = restToday ?: base?.toInt() ?: p.restHr ?: 60
        val test = hrv.value.lastOrNull()?.takeIf { it.time >= dayStart }
        val (m, parts) = fi.sarmat.pulsetrainer.core.Energy.morningCharge(p, today?.takeIf { it.sleepMin != null }, restToday, base, test, todayCheckIn())
        // Strap/watch samples from PulseTrainer workouts fill gaps in Samsung's all-day pulse.
        val own = workouts.value.filter { it.end >= wake }.flatMap { w -> w.hr.filterIndexed { i, _ -> i % 30 == 0 }.map { it.t to it.bpm } }
        val samples = (hrRecent.value + own + watchHr.value).sortedBy { it.first }
        return fi.sarmat.pulsetrainer.core.Energy.compute(p, wake, now, samples, rest, m, parts)
    }

    /** Favourite workouts (★, up to 6), shared with the watch tiles. */
    val favorites = MutableStateFlow<List<fi.sarmat.pulsetrainer.core.WorkoutType>>(emptyList())

    private fun parseFav(s: String?): List<fi.sarmat.pulsetrainer.core.WorkoutType> =
        (s ?: "").split(',').mapNotNull { n -> fi.sarmat.pulsetrainer.core.WorkoutType.entries.firstOrNull { it.name == n } }.take(6)

    suspend fun setFavorites(ctx: Context, list: List<fi.sarmat.pulsetrainer.core.WorkoutType>) {
        val l = list.distinct().take(6)
        favorites.value = l
        prefs.edit().putString("fav", l.joinToString(",") { it.name }).apply()
        try {
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
            nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, Protocol.PATH_FAV, l.joinToString(",") { t -> t.name }.toByteArray()).await() }
        } catch (_: Exception) {}
    }

    /** Start a workout on the watch from the phone. */
    suspend fun startOnWatch(ctx: Context, t: fi.sarmat.pulsetrainer.core.WorkoutType): Boolean = sendToWatch(ctx, Protocol.CMD_START + t.name)
    /** Last time anything arrived from the watch (epoch millis, 0 = never). */
    val lastWatchContact = MutableStateFlow(0L)
    val lastWorkoutReceived = MutableStateFlow(0L)

    // ---------- Your own order of cards on each tab ----------
    val cardsVersion = MutableStateFlow(0)

    fun cardOrder(tab: String, defaults: List<String>): List<String> {
        val saved = (prefs.getString("cards_$tab", "") ?: "").split(',').filter { it in defaults }.toMutableList()
        if (saved.isEmpty()) return defaults
        // A card added in a new version goes next to its neighbour from the default order, not to the very end.
        defaults.forEachIndexed { i, id ->
            if (id !in saved) {
                val prevIdx = defaults.take(i).lastOrNull { it in saved }?.let { saved.indexOf(it) } ?: -1
                saved.add(prevIdx + 1, id)
            }
        }
        return saved
    }

    fun saveCardOrder(tab: String, list: List<String>) {
        prefs.edit().putString("cards_$tab", list.joinToString(",")).apply()
        cardsVersion.value++
    }

    /** Cards that live in the «Подробнее» block at the bottom of the tab. */
    fun moreCards(tab: String, defaults: Set<String>): Set<String> {
        val raw = prefs.getString("more_$tab", null) ?: return defaults
        return raw.split(',').filter { it.isNotBlank() }.toSet()
    }

    fun setCardMore(tab: String, id: String, more: Boolean, defaults: Set<String>) {
        val set = moreCards(tab, defaults).toMutableSet()
        if (more) set += id else set -= id
        prefs.edit().putString("more_$tab", set.joinToString(",")).apply()
        cardsVersion.value++
    }

    fun hiddenCards(tab: String): Set<String> =
        (prefs.getString("hidden_$tab", "") ?: "").split(',').filter { it.isNotBlank() }.toSet()

    fun setCardHidden(tab: String, id: String, hidden: Boolean) {
        val set = hiddenCards(tab).toMutableSet()
        if (hidden) set += id else set -= id
        prefs.edit().putString("hidden_$tab", set.joinToString(",")).apply()
        cardsVersion.value++
    }

    /** Text size in the phone app (1.0 = system size). */
    val fontScale = MutableStateFlow(1.15f)
    fun setFontScale(v: Float) {
        fontScale.value = v.coerceIn(0.85f, 1.6f)
        prefs.edit().putFloat("fontScale", fontScale.value).apply()
    }

    /** Resting pulse taken automatically from night pulse / morning tests. */
    var autoRest: Boolean
        get() = prefs.getBoolean("autoRest", true)
        set(v) { prefs.edit().putBoolean("autoRest", v).apply() }

    /** Median of the last 7 nights' resting pulse (Samsung Health or PulseTrainer's own night pulse), else morning tests. */
    fun autoRestValue(): Int? {
        val nights = (days.value.mapNotNull { d -> d.restHr?.let { d.day to it } } + passive.value.mapNotNull { d -> d.restHr?.let { d.day to it } })
            .groupBy { it.first }.map { it.value.first().second }.takeLast(7)
        val src = nights.ifEmpty { hrv.value.takeLast(7).map { it.restHr }.filter { it in 35..100 } }
        if (src.size < 3) return null
        return src.sorted()[src.size / 2]
    }

    var remindMorningHour: Int
        get() = prefs.getInt("remindHour", 8)
        set(v) { prefs.edit().putInt("remindHour", v).apply() }
    var remindMorning: Boolean
        get() = prefs.getBoolean("remindOn", true)
        set(v) { prefs.edit().putBoolean("remindOn", v).apply() }
    var remindBedtime: Boolean
        get() = prefs.getBoolean("bedtimeOn", true)
        set(v) { prefs.edit().putBoolean("bedtimeOn", v).apply() }
    var remindEvening: Boolean
        get() = prefs.getBoolean("eveningOn", true)
        set(v) { prefs.edit().putBoolean("eveningOn", v).apply() }

    fun setCheckIn(feel: Int, soreness: Int) {
        val day = java.time.LocalDate.now().toEpochDay()
        val c = CheckIn(day, feel, soreness)
        checkIn.value = c
        prefs.edit().putString("checkin", "${c.day},${c.feel},${c.soreness}").apply()
    }

    private fun loadCheckIn(): CheckIn? = try {
        val v = (prefs.getString("checkin", "") ?: "").split(',').map { it.toLong() }
        CheckIn(v[0], v[1].toInt(), v[2].toInt()).takeIf { it.day == java.time.LocalDate.now().toEpochDay() }
    } catch (_: Exception) { null }

    /** Today's check-in (it expires at midnight). */
    fun todayCheckIn(): CheckIn? = checkIn.value?.takeIf { it.day == java.time.LocalDate.now().toEpochDay() }

    /** The coach's advice from everything we know. One place, used by all screens and reminders. */
    fun advise(): CoachAdvice = Coach.advise(
        profile.value ?: fi.sarmat.pulsetrainer.core.Profile(), goal.value, days.value.lastOrNull(), days.value,
        workouts.value, hrv.value, weights.value, body.value,
        ext = ext.value, checkIn = todayCheckIn(), passive = passive.value,
    )

    /** Send today's readiness and plan to the watch (shown at the top of the watch app). */
    suspend fun pushCoachToWatch(ctx: Context) {
        try {
            val a = advise()
            val en = try { energy() } catch (_: Exception) { null }
            val json = JSONObject().put("score", a.score).put("level", a.level).put("label", Coach.levelText(a))
                .put("energy", en?.now ?: -1).put("energyLabel", en?.label ?: "")
                .put("recH", try { recovery().hoursLeft } catch (_: Exception) { -1 })
                .also { o ->
                    val night = days.value.lastOrNull { it.sleepMin != null }
                    val sc = fi.sarmat.pulsetrainer.core.Health.sleepScore(night)
                    o.put("sleepMin", night?.sleepMin ?: -1).put("sleepScore", sc?.value ?: -1).put("sleepLabel", sc?.label ?: "")
                    o.put("sleepStart", night?.sleepStart ?: 0L).put("sleepEnd", night?.sleepEnd ?: 0L)
                    o.put("deep", night?.deepMin ?: -1).put("rem", night?.remMin ?: -1).put("awake", night?.awakeMin ?: -1)
                    o.put("rest", night?.restHr ?: days.value.lastOrNull()?.restHr ?: -1)
                    o.put("steps", days.value.lastOrNull()?.steps ?: -1L)
                }
                .put("headline", a.headline).put("plan", JSONArray(a.plan.take(3))).put("t", System.currentTimeMillis()).toString()
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
            nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, Protocol.PATH_COACH, json.toByteArray()).await() }
        } catch (_: Exception) {}
    }

    fun touchWatch(workout: Boolean = false) {
        val now = System.currentTimeMillis()
        lastWatchContact.value = now
        prefs.edit().putLong("lastWatch", now).apply()
        if (workout) { lastWorkoutReceived.value = now; prefs.edit().putLong("lastWorkoutRx", now).apply() }
    }

    private fun bodyToJson(list: List<BodyEntry>): String {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("t", it.time).put("w", it.weightKg).put("waist", it.waistCm).put("chest", it.chestCm)
                .put("arm", it.armCm).put("thigh", it.thighCm).put("fat", it.bodyFatPct).put("neck", it.neckCm))
        }
        return a.toString()
    }

    private fun JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null

    fun addBody(e: BodyEntry) {
        body.value = (body.value + e).sortedBy { it.time }.takeLast(500)
        prefs.edit().putString("body", bodyToJson(body.value)).apply()
        e.weightKg?.let { addWeight(it) }
    }

    fun deleteBody(e: BodyEntry) {
        body.value = body.value.filter { it != e }
        prefs.edit().putString("body", bodyToJson(body.value)).apply()
    }

    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        Backup.loadInfo(ctx)
        dir = File(ctx.filesDir, "workouts").apply { mkdirs() }
        try { loadEcgs() } catch (_: Exception) {}
        prefs = ctx.getSharedPreferences("pt", Context.MODE_PRIVATE)
        profile.value = WorkoutJson.profileFromJson(prefs.getString("profile", null))
        hrv.value = WorkoutJson.hrvFromJson(prefs.getString("hrv", null))
        goal.value = Goal.of(prefs.getString("goal", null))
        weights.value = try {
            val a = JSONArray(prefs.getString("weights", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> WeightEntry(o.getLong("t"), o.getDouble("kg")) } }
        } catch (_: Exception) { emptyList() }
        body.value = try {
            val a = JSONArray(prefs.getString("body", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                BodyEntry(o.getLong("t"), o.d("w"), o.d("waist"), o.d("chest"), o.d("arm"), o.d("thigh"), o.d("fat"), o.d("neck"))
            }
        } catch (_: Exception) { emptyList() }
        lastWatchContact.value = prefs.getLong("lastWatch", 0L)
        lastHcImport = prefs.getLong("hcImport", 0L)
        stress.value = WorkoutJson.stressFromJson(prefs.getString("stress", null))
        passive.value = WorkoutJson.passiveFromJson(prefs.getString("passive", null))
        lastPassive.value = prefs.getLong("lastPassive", 0L)
        checkIn.value = loadCheckIn()
        favorites.value = parseFav(prefs.getString("fav", null)).ifEmpty {
            listOf("STRENGTH", "ELLIPTICAL", "WALK", "TREADMILL", "STAIRS_HOME", "PULL_UPS").map { fi.sarmat.pulsetrainer.core.WorkoutType.valueOf(it) }
        }
        fontScale.value = prefs.getFloat("fontScale", 1.15f)
        lastWorkoutReceived.value = prefs.getLong("lastWorkoutRx", 0L)
        reload()
    }

    fun setGoal(g: Goal) {
        goal.value = g
        prefs.edit().putString("goal", g.name).apply()
    }

    fun addWeight(kg: Double) {
        val now = System.currentTimeMillis()
        // One entry per day: replace today's.
        val day = now / 86400_000L
        val list = weights.value.filter { it.time / 86400_000L != day } + WeightEntry(now, kg)
        weights.value = list.sortedBy { it.time }.takeLast(400)
        saveWeights()
    }

    /** Profile edited on the phone: save, log weight, send to the watch, write weight to Health Connect. */
    suspend fun updateProfile(ctx: Context, p: Profile) {
        val old = profile.value
        val json = JSONObject(WorkoutJson.profileToJson(p)).put("autoRest", autoRest).toString()
        prefs.edit().putString("profile", json).apply()
        profile.value = p
        if (old == null || kotlin.math.abs(old.weightKg - p.weightKg) > 0.05) {
            addWeight(p.weightKg)
            HealthData.writeWeight(ctx, p.weightKg)
        }
        try {
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
            nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, Protocol.PATH_PROFILE_SET, json.toByteArray()).await() }
        } catch (_: Exception) {}
    }

    suspend fun refreshDays(ctx: Context) {
        val snap = try { HealthData.load(ctx, profile.value ?: fi.sarmat.pulsetrainer.core.Profile()) } catch (_: Exception) { null }
        // A background read can come back empty (Health Connect locked / no permission in background):
        // never let that wipe what we already have.
        val hasData = snap != null && snap.days.any {
            it.sleepMin != null || it.restHr != null || it.steps != null || it.hrvMs != null || it.weightKg != null
        }
        if (hasData) { rawDays = snap!!.days; lastHcImport = System.currentTimeMillis(); prefs.edit().putLong("hcImport", lastHcImport).apply() }
        days.value = mergeWatch(rawDays)
        if (snap != null && (hasData || snap.ext.isNotEmpty())) {
            ext.value = snap.ext; if (snap.hrRecent.isNotEmpty()) hrRecent.value = snap.hrRecent
        }
        // Every weight from Samsung Health scales / manual entries joins the log (one per day).
        val fromHc = (snap?.days ?: emptyList()).filter { it.weightKg != null }.map { WeightEntry(it.day + 8 * 3600_000L, it.weightKg!!) }
        if (fromHc.isNotEmpty()) {
            val known = weights.value.map { it.time / 86400_000L }.toSet()
            val add = fromHc.filter { it.time / 86400_000L !in known }
            if (add.isNotEmpty()) {
                weights.value = (weights.value + add).sortedBy { it.time }.takeLast(400)
                saveWeights()
            }
        }
        // Auto resting pulse follows your nights.
        if (autoRest) autoRestValue()?.let { r ->
            val cur = profile.value
            if (cur != null && cur.restHr != r) updateProfile(ctx, cur.copy(restHr = r))
        }
        pushCoachToWatch(ctx)
    }

    /**
     * Works without Samsung Health: where Health Connect has no sleep / resting pulse / steps for a day,
     * PulseTrainer's own background data from the watch fills them in.
     */
    fun mergeWatch(list: List<DailyStats>): List<DailyStats> {
        val pd = passive.value.associateBy { it.day }
        val own = watchOnly
        fun sleepOf(p: PassiveDay): Int? {
            val a = p.sleepStart ?: return null; val b = p.sleepEnd ?: return null
            return if (b > a) (((b - a) / 60000).toInt() - (p.awakeMin ?: 0)).coerceAtLeast(0) else null
        }
        val merged = list.map { d ->
            val p = pd[d.day] ?: return@map if (own) d.copy(sleepMin = null, deepMin = null, remMin = null, lightMin = null, awakeMin = null) else d
            if (own) d.copy(
                // «Только PulseTrainer»: sleep and resting pulse from the watch's own tracking.
                sleepMin = sleepOf(p), sleepStart = p.sleepStart, sleepEnd = p.sleepEnd,
                deepMin = null, remMin = null, lightMin = null, awakeMin = p.awakeMin,
                restHr = p.restHr ?: d.restHr, steps = p.steps ?: d.steps,
                hrMin = p.hrMin ?: d.hrMin, hrMax = p.hrMax ?: d.hrMax, hrAvg = p.dayAvg ?: d.hrAvg,
                floors = p.floors?.toDouble() ?: d.floors,
            ) else d.copy(
                sleepMin = d.sleepMin ?: sleepOf(p),
                sleepStart = d.sleepStart ?: p.sleepStart, sleepEnd = d.sleepEnd ?: p.sleepEnd,
                awakeMin = d.awakeMin ?: p.awakeMin.takeIf { d.sleepMin == null },
                restHr = d.restHr ?: p.restHr, steps = d.steps ?: p.steps,
                hrMin = d.hrMin ?: p.hrMin, hrMax = d.hrMax ?: p.hrMax, hrAvg = d.hrAvg ?: p.dayAvg,
                floors = d.floors ?: p.floors?.toDouble(),
            )
        }
        val known = merged.map { it.day }.toSet()
        val extra = pd.values.filter { it.day !in known }.map { p ->
            DailyStats(p.day, sleepMin = sleepOf(p), awakeMin = p.awakeMin,
                restHr = p.restHr, steps = p.steps, sleepStart = p.sleepStart, sleepEnd = p.sleepEnd, floors = p.floors?.toDouble())
        }
        return (merged + extra).sortedBy { it.day }
    }

    /**
     * Data source: false = Samsung Health first (stages, SpO2…), the watch fills gaps;
     * true = only PulseTrainer's own watch tracking (works with Samsung Health removed or switched off).
     */
    var watchOnly: Boolean
        get() = prefs.getBoolean("watchOnly", false)
        set(v) { prefs.edit().putBoolean("watchOnly", v).apply(); days.value = mergeWatch(rawDays); }

    /** Days as read from Health Connect, before merging with the watch. */
    private var rawDays: List<DailyStats> = emptyList()

    /** When fresh data last came from Samsung Health / Health Connect. */
    var lastHcImport: Long = 0L
        private set

    private fun saveWeights() {
        val a = JSONArray()
        weights.value.forEach { a.put(JSONObject().put("t", it.time).put("kg", it.kg)) }
        prefs.edit().putString("weights", a.toString()).apply()
    }

    @Synchronized
    fun reload() {
        workouts.value = (dir.listFiles() ?: emptyArray())
            .mapNotNull { f -> try { WorkoutJson.fromJson(f.readText()) } catch (_: Exception) { null } }
            .sortedByDescending { it.start }
    }

    fun get(id: String): Workout? = workouts.value.firstOrNull { it.id == id }

    /** Returns true if this workout is new. */
    /** Returns true if this workout is new. Updates the list in memory (no re-reading of every file). */
    @Synchronized
    fun save(w: Workout): Boolean {
        appCtx?.let { Backup.changed(it) }
        val f = File(dir, "${w.id}.json")
        val old = get(w.id)
        val isNew = old == null && !f.exists()
        val keep = w.copy(syncedToHealth = (old?.syncedToHealth ?: false) || w.syncedToHealth)
        f.writeText(WorkoutJson.toJson(keep))
        workouts.value = (workouts.value.filter { it.id != w.id } + keep).sortedByDescending { it.start }
        return isNew
    }

    @Synchronized
    fun markSynced(id: String) {
        val w = get(id) ?: return
        val nw = w.copy(syncedToHealth = true)
        workouts.value = workouts.value.map { if (it.id == id) nw else it }
        val f = File(dir, "$id.json")
        Thread { try { f.writeText(WorkoutJson.toJson(nw)) } catch (_: Exception) {} }.start()
    }

    @Synchronized
    fun delete(id: String) {
        workouts.value = workouts.value.filter { it.id != id }
        val f = File(dir, "$id.json")
        Thread { f.delete() }.start()
    }

    fun saveProfile(profileJson: String?, hrvJson: String?, stressJson: String? = null) {
        prefs.edit().putString("profile", profileJson).putString("hrv", hrvJson).apply()
        profile.value = WorkoutJson.profileFromJson(profileJson)
        hrv.value = WorkoutJson.hrvFromJson(hrvJson)
        if (stressJson != null) {
            prefs.edit().putString("stress", stressJson).apply()
            stress.value = WorkoutJson.stressFromJson(stressJson)
        }
    }

    fun savePassive(json: String?, hrJson: String? = null) {
        if (hrJson != null) try {
            val a = JSONArray(hrJson)
            watchHr.value = (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getLong(0) to x.getInt(1) } }
        } catch (_: Exception) {}
        val list = WorkoutJson.passiveFromJson(json)
        if (list.isEmpty()) return
        // Merge with what we have (the watch keeps only a week).
        val merged = (passive.value.filter { old -> list.none { it.day == old.day } } + list).sortedBy { it.day }.takeLast(120)
        passive.value = merged
        val now = System.currentTimeMillis()
        lastPassive.value = now
        prefs.edit().putString("passive", WorkoutJson.passiveToJson(merged)).putLong("lastPassive", now).apply()
        days.value = mergeWatch(rawDays)
    }

    // ---------- Import from the watch ----------

    /** Handle one data item from the watch. Returns the workout if it was new. */
    fun importItem(ctx: Context, item: DataItem): Workout? {
        val path = item.uri.path ?: return null
        val map = DataMapItem.fromDataItem(item).dataMap
        if (path.startsWith(Protocol.PATH_PROFILE)) {
            touchWatch()
            saveProfile(map.getString("profile"), map.getString("hrv"), map.getString("stress"))
            map.getString("fav")?.let { f -> parseFav(f).takeIf { it.isNotEmpty() }?.let { favorites.value = it; prefs.edit().putString("fav", f).apply() } }
            return null
        }
        if (path.startsWith(Protocol.PATH_ECG)) {
            touchWatch()
            val asset = map.getAsset("json") ?: return null
            val bytes = Tasks.await(Wearable.getDataClient(ctx).getFdForAsset(asset)).inputStream.use { it.readBytes() }
            val r = fi.sarmat.pulsetrainer.core.Ecg.fromJson(String(bytes)) ?: return null
            File(ecgDir(), "${r.time}.json").writeBytes(bytes)
            loadEcgs()
            Backup.changed(ctx)
            return null
        }
        if (path.startsWith(Protocol.PATH_PASSIVE)) {
            touchWatch()
            savePassive(map.getString("days"), map.getString("hr"))
            return null
        }
        if (!path.startsWith(Protocol.PATH_WORKOUT)) return null
        val asset = map.getAsset("json") ?: return null
        val bytes = Tasks.await(Wearable.getDataClient(ctx).getFdForAsset(asset)).inputStream.use { it.readBytes() }
        val w = WorkoutJson.fromJson(String(bytes))
        return if (save(w)) { touchWatch(workout = true); w } else null
    }

    /** Catch up on anything sent while the app was not running. */
    suspend fun importAll(ctx: Context) {
        try {
            val buf = Wearable.getDataClient(ctx).dataItems.await()
            val items = buf.map { it.freeze() }
            buf.release()
            for (it in items) {
                try {
                    val w = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { importItem(ctx, it) }
                    if (w != null) try { if (HealthSync.write(ctx, w)) markSynced(w.id) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ---------- Commands to the watch ----------

    suspend fun sendToWatch(ctx: Context, cmd: String): Boolean = try {
        val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
        nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, Protocol.PATH_CONTROL, cmd.toByteArray()).await() }
        nodes.isNotEmpty()
    } catch (_: Exception) { false }
}

/** Receives finished workouts, profile and live state from the watch. */
class WatchListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        for (item in items) {
            try {
                val w = PhoneStore.importItem(this, item) ?: continue
                val synced = try { runBlocking { HealthSync.write(this@WatchListenerService, w) } } catch (_: Exception) { false }
                if (synced) PhoneStore.markSynced(w.id)
                notifyNew(w, synced)
            } catch (_: Exception) {}
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == Protocol.PATH_LIVE) {
            try { PhoneStore.live.value = Protocol.Live.fromJson(String(event.data)); PhoneStore.touchWatch() } catch (_: Exception) {}
        }
    }

    private fun notifyNew(w: Workout, synced: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("workouts", "Тренировки", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("open", w.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "workouts")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Тренировка получена: ${w.title}")
            .setContentText(if (synced) "Записана в Health Connect → Samsung Health" else "Откройте, чтобы записать в Health Connect")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(w.id.hashCode(), n) } catch (_: SecurityException) {}
    }
}
