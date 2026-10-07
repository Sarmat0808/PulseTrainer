package fi.sarmat.pulsetrainer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import fi.sarmat.pulsetrainer.core.WorkoutType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the workout alive: foreground service + ongoing activity (icon on the watch face),
 * 1-second tick, strap connection, GPS and the rep counter.
 */
class WorkoutService : LifecycleService(), WorkoutEngine.Hooks, LocationListener {

    companion object {
        private const val CH = "workout"
        private const val NOTIF_ID = 7
        private const val EXTRA_TYPE = "type"

        fun start(ctx: Context, type: WorkoutType) {
            val i = Intent(ctx, WorkoutService::class.java).putExtra(EXTRA_TYPE, type.name)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    override val context: Context get() = this

    private var tickJob: Job? = null
    private lateinit var watchHr: WatchHr
    private lateinit var exerciseHr: ExerciseHr
    private lateinit var climbSensor: ClimbSensor
    private var climbOn = false
    private lateinit var repCounter: RepCounter
    private var gpsOn = false
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        watchHr = WatchHr(this)
        exerciseHr = ExerciseHr(this) { loc -> WorkoutEngine.onLocation(loc) }
        climbSensor = ClimbSensor(this)
        repCounter = RepCounter(this) { t -> WorkoutEngine.onRep(t) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = WorkoutType.of(intent?.getStringExtra(EXTRA_TYPE) ?: WorkoutType.STRENGTH.name)
        goForeground(type)
        WorkoutEngine.hooks = this
        HrSensor.connectSaved()
        watchHr.start()
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseTrainer:workout").apply { acquire(6 * 3600_000L) }
        }
        WorkoutEngine.start(type)
        if (tickJob == null) {
            tickJob = lifecycleScope.launch {
                var next = System.currentTimeMillis() + 1000
                while (isActive) {
                    delay((next - System.currentTimeMillis()).coerceAtLeast(10))
                    next += 1000
                    WorkoutEngine.tick()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(type: WorkoutType) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Тренировка", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("PulseTrainer")
            .setContentText(type.title)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
        try {
            OngoingActivity.Builder(applicationContext, NOTIF_ID, builder)
                .setStaticIcon(R.drawable.ic_launcher)
                .setTouchIntent(open)
                .setStatus(Status.Builder().addTemplate("Тренировка").build())
                .build()
                .apply(applicationContext)
        } catch (_: Exception) {}
        val n: Notification = builder.build()

        if (Build.VERSION.SDK_INT >= 29) {
            var types = 0
            val bt = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
            if (bt) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.BODY_SENSORS)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            try {
                startForeground(NOTIF_ID, n, types)
            } catch (e: Exception) {
                startForeground(NOTIF_ID, n)
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ----- Hooks -----

    override fun reconfigure(type: WorkoutType) {
        exerciseHr.ensure(type)
        if (type.gps) startGps()
        if (type.repCount) repCounter.start(type) else repCounter.stop()
        if (type.climb || type.steps || type.gps) { climbSensor.start(); climbOn = true } else { climbSensor.stop(); climbOn = false }
        climbSensor.reset()
    }

    // Exercise mode first: it keeps running in the background; the others are backups.
    override fun watchBpm(): Int? = exerciseHr.fresh() ?: watchHr.fresh()

    override fun resetReps() = repCounter.reset()

    override fun climb(): ClimbSensor? = if (climbOn) climbSensor else null

    override fun stopped() {
        tickJob?.cancel(); tickJob = null
        stopGps()
        repCounter.stop()
        watchHr.stop()
        exerciseHr.stop()
        climbSensor.stop(); climbOn = false
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        WorkoutEngine.hooks = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ----- GPS -----

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsOn || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            gpsOn = true
            gpsDisabled = !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {}
    }

    private fun stopGps() {
        if (!gpsOn) return
        getSystemService(LocationManager::class.java)?.removeUpdates(this)
        gpsOn = false
    }

    // Backup GPS: used only when Health Services sends no points (some firmware / no permission there).
    override fun onLocationChanged(location: Location) {
        if (android.os.SystemClock.elapsedRealtime() - exerciseHr.lastLocationAt > 10_000) WorkoutEngine.onLocation(location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) { gpsDisabled = false }
    override fun onProviderDisabled(provider: String) { gpsDisabled = true }

    override fun gpsOff(): Boolean = gpsDisabled
    private var gpsDisabled = false

    override fun onDestroy() {
        if (WorkoutEngine.ui.value.running) WorkoutEngine.finish()
        super.onDestroy()
    }
}
