package fi.sarmat.pulsetrainer

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import fi.sarmat.pulsetrainer.core.Protocol
import fi.sarmat.pulsetrainer.core.Workout
import fi.sarmat.pulsetrainer.core.WorkoutJson
import fi.sarmat.pulsetrainer.core.WorkoutType

/** Watch -> phone. */
object PhoneLink {
    private var nodes: List<String> = emptyList()
    private var nodesAt = 0L

    /** Finished session as a DataItem: delivered even if the phone is out of range right now. */
    fun sendWorkout(ctx: Context, w: Workout) {
        val req = PutDataMapRequest.create(Protocol.PATH_WORKOUT + w.id).apply {
            dataMap.putAsset("json", Asset.createFromBytes(WorkoutJson.toJson(w).toByteArray()))
            dataMap.putLong("ts", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req)
    }

    /** Profile + morning tests, so phone reports contain the full picture. */
    fun sendProfile(ctx: Context) {
        try {
            val req = PutDataMapRequest.create(Protocol.PATH_PROFILE).apply {
                dataMap.putString("profile", WorkoutJson.profileToJson(Storage.profile.value))
                dataMap.putString("hrv", WorkoutJson.hrvToJson(Storage.hrvHistory()))
                dataMap.putString("stress", WorkoutJson.stressToJson(Storage.stressHistory()))
                dataMap.putString("fav", Storage.favorites.value.joinToString(",") { it.name })
                dataMap.putLong("ts", System.currentTimeMillis())
            }.asPutDataRequest()
            Wearable.getDataClient(ctx).putDataItem(req)
        } catch (_: Exception) {}
    }

    /** Live state for the phone's remote-control card. Fire-and-forget. */
    fun sendLive(ctx: Context, live: Protocol.Live) {
        val now = System.currentTimeMillis()
        val client = Wearable.getMessageClient(ctx)
        val bytes = live.toJson().toByteArray()
        if (now - nodesAt > 30_000) {
            nodesAt = now
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { list ->
                nodes = list.map { it.id }
                nodes.forEach { client.sendMessage(it, Protocol.PATH_LIVE, bytes) }
            }
        } else {
            nodes.forEach { client.sendMessage(it, Protocol.PATH_LIVE, bytes) }
        }
    }
}

/** Phone -> watch: switch exercise, pause, finish, next set. */
class ControlListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == Protocol.PATH_FAV) {
            val list = String(event.data).split(',').mapNotNull { n -> WorkoutType.entries.firstOrNull { it.name == n } }
            Handler(Looper.getMainLooper()).post { Storage.setFavorites(list, fromPhone = true) }
            return
        }
        if (event.path == Protocol.PATH_COACH) {
            val json = String(event.data)
            Handler(Looper.getMainLooper()).post { Storage.saveCoach(json) }
            return
        }
        if (event.path == Protocol.PATH_PROFILE_SET) {
            val p = WorkoutJson.profileFromJson(String(event.data)) ?: return
            Handler(Looper.getMainLooper()).post {
                if (p.restHr != Storage.profile.value.restHr) Storage.autoRestHr = false
                Storage.saveProfile(p)
            }
            return
        }
        if (event.path != Protocol.PATH_CONTROL) return
        val cmd = String(event.data)
        if (cmd.startsWith(Protocol.CMD_START)) {
            // Start a workout from the phone: open the app on the watch and begin.
            val t = WorkoutType.of(cmd.removePrefix(Protocol.CMD_START))
            startActivity(android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("start", t.name))
            return
        }
        Handler(Looper.getMainLooper()).post {
            val e = WorkoutEngine
            if (!e.ui.value.running) return@post
            when {
                cmd == Protocol.CMD_PAUSE -> e.setPaused(true)
                cmd == Protocol.CMD_RESUME -> e.setPaused(false)
                cmd == Protocol.CMD_FINISH -> e.finish()
                cmd == Protocol.CMD_DISCARD -> e.discard()
                cmd == Protocol.CMD_NEXT -> e.nextPhase()
                cmd.startsWith(Protocol.CMD_SWITCH) -> e.switchTo(WorkoutType.of(cmd.removePrefix(Protocol.CMD_SWITCH)))
            }
        }
    }
}
