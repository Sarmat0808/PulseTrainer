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

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "workouts").apply { mkdirs() }
        prefs = ctx.getSharedPreferences("pt", Context.MODE_PRIVATE)
        profile.value = WorkoutJson.profileFromJson(prefs.getString("profile", null))
        hrv.value = WorkoutJson.hrvFromJson(prefs.getString("hrv", null))
        goal.value = Goal.of(prefs.getString("goal", null))
        weights.value = try {
            val a = JSONArray(prefs.getString("weights", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> WeightEntry(o.getLong("t"), o.getDouble("kg")) } }
        } catch (_: Exception) { emptyList() }
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
        val a = JSONArray()
        weights.value.forEach { a.put(JSONObject().put("t", it.time).put("kg", it.kg)) }
        prefs.edit().putString("weights", a.toString()).apply()
    }

    /** Profile edited on the phone: save, log weight, send to the watch, write weight to Health Connect. */
    suspend fun updateProfile(ctx: Context, p: Profile) {
        val old = profile.value
        val json = WorkoutJson.profileToJson(p)
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
        val d = try { HealthData.loadDays(ctx) } catch (_: Exception) { emptyList() }
        if (d.isNotEmpty()) days.value = d
        // Weight from Samsung Health scales / manual entries joins the log.
        d.lastOrNull { it.weightKg != null }?.let { last ->
            val known = weights.value.lastOrNull()
            if (known == null || last.day > known.time) {
                val list = weights.value + WeightEntry(last.day + 8 * 3600_000L, last.weightKg!!)
                weights.value = list.sortedBy { it.time }
            }
        }
    }

    @Synchronized
    fun reload() {
        workouts.value = (dir.listFiles() ?: emptyArray())
            .mapNotNull { f -> try { WorkoutJson.fromJson(f.readText()) } catch (_: Exception) { null } }
            .sortedByDescending { it.start }
    }

    fun get(id: String): Workout? = workouts.value.firstOrNull { it.id == id }

    /** Returns true if this workout is new. */
    @Synchronized
    fun save(w: Workout): Boolean {
        val f = File(dir, "${w.id}.json")
        val isNew = !f.exists()
        // Keep the "synced" flag if we already have it.
        val synced = if (!isNew) (try { WorkoutJson.fromJson(f.readText()).syncedToHealth } catch (_: Exception) { false }) else false
        f.writeText(WorkoutJson.toJson(w.copy(syncedToHealth = synced || w.syncedToHealth)))
        reload()
        return isNew
    }

    fun markSynced(id: String) {
        val w = get(id) ?: return
        File(dir, "$id.json").writeText(WorkoutJson.toJson(w.copy(syncedToHealth = true)))
        reload()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        reload()
    }

    fun saveProfile(profileJson: String?, hrvJson: String?) {
        prefs.edit().putString("profile", profileJson).putString("hrv", hrvJson).apply()
        profile.value = WorkoutJson.profileFromJson(profileJson)
        hrv.value = WorkoutJson.hrvFromJson(hrvJson)
    }

    // ---------- Import from the watch ----------

    /** Handle one data item from the watch. Returns the workout if it was new. */
    fun importItem(ctx: Context, item: DataItem): Workout? {
        val path = item.uri.path ?: return null
        val map = DataMapItem.fromDataItem(item).dataMap
        if (path.startsWith(Protocol.PATH_PROFILE)) {
            saveProfile(map.getString("profile"), map.getString("hrv"))
            return null
        }
        if (!path.startsWith(Protocol.PATH_WORKOUT)) return null
        val asset = map.getAsset("json") ?: return null
        val bytes = Tasks.await(Wearable.getDataClient(ctx).getFdForAsset(asset)).inputStream.use { it.readBytes() }
        val w = WorkoutJson.fromJson(String(bytes))
        return if (save(w)) w else null
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
            try { PhoneStore.live.value = Protocol.Live.fromJson(String(event.data)) } catch (_: Exception) {}
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
