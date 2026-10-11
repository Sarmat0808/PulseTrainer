package fi.sarmat.pulsetrainer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import fi.sarmat.pulsetrainer.core.Protocol
import org.json.JSONObject

/**
 * «Выпейте воды» on the watch itself: a notification with «Выпил N мл» and «Отложить».
 * The buttons send the answer to the phone, which logs the water, recalculates the rest of the day
 * and answers with how much is left (shown as a short confirmation).
 */
object WaterNotify {
    private const val CH = "water"
    private const val ID = 4120
    const val ACTION = "fi.sarmat.pulsetrainer.WATER_ACTION"

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Вода", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 120, 80, 120)
        })
        return nm
    }

    fun show(ctx: Context, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val ml = o.optInt("ml", 250)
        val snooze = o.optInt("snooze", 30)
        fun action(cmd: String, req: Int) = PendingIntent.getBroadcast(ctx, req,
            Intent(ctx, WaterActionReceiver::class.java).setAction(ACTION).putExtra("cmd", cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("💧 Выпейте ~$ml мл")
            .setContentText(o.optString("text"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(o.optString("text") + "\nОсталось на сегодня ${o.optInt("left")} мл"))
            .addAction(0, "Выпил $ml мл", action("drank:$ml", 1))
            .addAction(0, "Отложить $snooze мин", action("snooze:$snooze", 2))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try { channel(ctx).notify(ID, n) } catch (_: SecurityException) {}
    }

    /** Short confirmation after the phone logged it: «✓ +300 мл · выпито 1500 из 3450 · следующее в 13:20». */
    fun state(ctx: Context, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("water_state", "Вода — итог", NotificationManager.IMPORTANCE_LOW))
        val text = "Выпито ${o.optInt("drunk")} из ${o.optInt("target")} мл · осталось ${o.optInt("left")} · ${o.optString("next")}"
        val n = NotificationCompat.Builder(ctx, "water_state")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("💧 ${o.optString("head")}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setTimeoutAfter(15_000)
            .setAutoCancel(true)
            .build()
        try { nm.cancel(ID); nm.notify(ID + 1, n) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
}

/** «Выпил» / «Отложить» pressed on the watch → the phone does the bookkeeping. */
class WaterActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        WaterNotify.cancel(ctx)
        val done = goAsync()
        val app = ctx.applicationContext
        Wearable.getCapabilityClient(app).getCapability(Protocol.CAP_PHONE, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val nodes = info.nodes
                if (nodes.isEmpty()) { done.finish(); return@addOnSuccessListener }
                var left = nodes.size
                nodes.forEach { n ->
                    Wearable.getMessageClient(app).sendMessage(n.id, Protocol.PATH_WATER_ACTION, cmd.toByteArray())
                        .addOnCompleteListener { if (--left == 0) done.finish() }
                }
            }
            .addOnFailureListener { done.finish() }
    }
}
