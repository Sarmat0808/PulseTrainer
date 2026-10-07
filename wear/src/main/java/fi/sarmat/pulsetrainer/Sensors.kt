package fi.sarmat.pulsetrainer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Backup: the watch's own optical sensor, used when the strap is not delivering
 * (forgotten at home, flat battery, out of range).
 *
 * Two sources at once for reliability during movement:
 * - Health Services (Samsung's own processed heart rate — the same value the watch face shows);
 * - the raw heart-rate sensor.
 * Readings flagged "unreliable" during movement are still used (only "no skin contact" is dropped),
 * and the last value is held for a few seconds so the screen never blinks to "--".
 */
class WatchHr(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val measure = try { HealthServices.getClient(ctx).measureClient } catch (_: Throwable) { null }
    @Volatile var bpm: Int? = null
        private set
    @Volatile private var at = 0L
    private var measuring = false

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}
        override fun onDataReceived(data: DataPointContainer) {
            val v = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            set(v.roundToInt())
        }
    }

    private fun set(v: Int) {
        if (v in 30..230) { bpm = v; at = SystemClock.elapsedRealtime() }
    }

    fun start() {
        sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            try { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) } catch (_: SecurityException) {}
        }
        if (!measuring && measure != null) {
            try { measure.registerMeasureCallback(DataType.HEART_RATE_BPM, callback); measuring = true } catch (_: Throwable) {}
        }
    }

    fun stop() {
        sm?.unregisterListener(this)
        if (measuring) {
            try { measure?.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback) } catch (_: Throwable) {}
            measuring = false
        }
        bpm = null
    }

    /** Last value if it is not older than 10 s. */
    fun fresh(): Int? = if (SystemClock.elapsedRealtime() - at < 10_000) bpm else null

    override fun onSensorChanged(e: SensorEvent) {
        if (e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) return
        val v = e.values.firstOrNull()?.toInt() ?: return
        if (v > 0) set(v)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Repetition counter from the wrist (pull-ups, push-ups, squats).
 *
 * Why the old one counted walking: it reacted to any rhythmic wrist movement. Now:
 * - only movement ALONG GRAVITY counts (the body goes up and down; arm swings while walking are mostly sideways);
 * - a rep needs a clear up-and-back cycle, with a minimum time per rep for the exercise
 *   (pull-up ≥ 1.3 s, squat ≥ 1.1 s, push-up ≥ 0.9 s);
 * - while you are walking (the step detector saw a step in the last 2.5 s) nothing is counted;
 * - pull-ups and push-ups also need the forearm close to vertical (hanging on the bar / hands on the floor).
 */
class RepCounter(ctx: Context, private val onRep: (Long) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val g = DoubleArray(3)
    private var gInit = false
    private var v = 0.0
    private var level = 1.0
    private var armed = true
    private var lastRep = 0L
    private var lastStep = 0L
    private var minGap = 1100L
    private var needVertical = false

    fun start(type: fi.sarmat.pulsetrainer.core.WorkoutType) {
        reset()
        minGap = when (type) {
            fi.sarmat.pulsetrainer.core.WorkoutType.PULL_UPS -> 1300L
            fi.sarmat.pulsetrainer.core.WorkoutType.PUSH_UPS -> 900L
            else -> 1100L
        }
        needVertical = type == fi.sarmat.pulsetrainer.core.WorkoutType.PULL_UPS || type == fi.sarmat.pulsetrainer.core.WorkoutType.PUSH_UPS
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            try { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) } catch (_: SecurityException) {}
        }
    }

    fun stop() { sm?.unregisterListener(this) }

    fun reset() { v = 0.0; level = 1.0; armed = true; lastRep = 0L; gInit = false }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) { lastStep = now; return }
        val a = e.values
        if (!gInit) { g[0] = a[0].toDouble(); g[1] = a[1].toDouble(); g[2] = a[2].toDouble(); gInit = true; return }
        for (i in 0..2) g[i] += 0.04 * (a[i] - g[i])            // slow low-pass = gravity
        val gn = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(1.0)
        // motion along gravity (up/down), m/s²
        val vert = ((a[0] - g[0]) * g[0] + (a[1] - g[1]) * g[1] + (a[2] - g[2]) * g[2]) / gn
        v += 0.2 * (vert - v)
        level += 0.01 * (kotlin.math.abs(v) - level)
        if (now - lastStep < 2500) { armed = true; return }      // walking: not reps
        if (needVertical && kotlin.math.abs(g[0]) / gn < 0.7) { armed = true; return } // forearm not vertical
        val hi = max(1.2, level * 1.8)
        if (armed && v > hi && now - lastRep > minGap) {
            armed = false
            lastRep = now
            onRep(System.currentTimeMillis())
        } else if (!armed && v < -hi * 0.4) {
            armed = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

object Haptics {
    private lateinit var vib: Vibrator

    fun init(ctx: Context) {
        vib = if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }

    private fun play(timings: LongArray) {
        try { vib.vibrate(VibrationEffect.createWaveform(timings, -1)) } catch (_: Exception) {}
    }

    fun tick() = play(longArrayOf(0, 40))
    /** Ready for the next set: two firm pulses. */
    fun ready() = play(longArrayOf(0, 250, 150, 250))
    /** Phase change (round start/end). */
    fun phase() = play(longArrayOf(0, 500))
    fun warn() = play(longArrayOf(0, 80, 80, 80, 80, 80))
    fun lap() = play(longArrayOf(0, 150, 100, 150))
}

/**
 * Health Services *exercise* mode — the same engine Samsung Health uses for workouts:
 * - heart rate keeps flowing in the background (watch face shown, a call answered, screen off);
 * - GPS points come from the system's own GPS handling (power-optimised, works when the
 *   app is not on screen). They are passed to the workout engine for distance, pace, laps and auto-pause.
 * GPS is switched on only when a GPS exercise starts and then stays on for the rest of the session
 * (so walking → pull-ups → walking does not lose the fix).
 */
class ExerciseHr(private val ctx: Context, private val onLocation: (android.location.Location) -> Unit) {
    private val client = try { HealthServices.getClient(ctx).exerciseClient } catch (_: Throwable) { null }
    @Volatile private var bpm: Int? = null
    @Volatile private var at = 0L
    @Volatile var lastLocationAt = 0L; private set
    private var active = false
    private var gpsOn = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val callback = object : androidx.health.services.client.ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: androidx.health.services.client.data.ExerciseUpdate) {
            update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.let {
                val r = it.roundToInt()
                if (r in 30..230) { bpm = r; at = SystemClock.elapsedRealtime() }
            }
            val boot = Passive.bootInstant()
            update.latestMetrics.getData(DataType.LOCATION).forEach { p ->
                val d = p.value
                val loc = android.location.Location("exercise").apply {
                    latitude = d.latitude; longitude = d.longitude
                    if (!d.altitude.isNaN()) altitude = d.altitude
                    time = p.getTimeInstant(boot).toEpochMilli()
                    elapsedRealtimeNanos = p.timeDurationFromBoot.toNanos()
                    val acc = (p.accuracy as? androidx.health.services.client.data.LocationAccuracy)?.horizontalPositionErrorMeters
                    accuracy = (acc ?: 10.0).toFloat()
                }
                lastLocationAt = SystemClock.elapsedRealtime()
                main.post { onLocation(loc) }
            }
        }
        override fun onLapSummaryReceived(lapSummary: androidx.health.services.client.data.ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    /** Start (or upgrade to GPS) for this exercise. */
    fun ensure(type: fi.sarmat.pulsetrainer.core.WorkoutType) {
        val c = client ?: return
        val wantGps = type.gps || gpsOn
        if (active && (gpsOn || !wantGps)) return
        val start = {
            try {
                val et = exType(type)
                val caps = c.getCapabilitiesAsync()
                caps.addListener({
                    try {
                        val supported = try { caps.get().getExerciseTypeCapabilities(et).supportedDataTypes } catch (_: Throwable) { emptySet() }
                        val gps = wantGps && DataType.LOCATION in supported && hasLocationPermission()
                        val types = HashSet<DataType<*, *>>()
                        if (DataType.HEART_RATE_BPM in supported || supported.isEmpty()) types.add(DataType.HEART_RATE_BPM)
                        if (gps) types.add(DataType.LOCATION)
                        c.setUpdateCallback(callback)
                        c.startExerciseAsync(
                            androidx.health.services.client.data.ExerciseConfig(
                                exerciseType = et, dataTypes = types,
                                isAutoPauseAndResumeEnabled = false, isGpsEnabled = gps,
                            )
                        )
                        active = true; gpsOn = gps
                    } catch (_: Throwable) {}
                }, ContextCompat_mainExecutor(ctx))
            } catch (_: Throwable) {}
        }
        if (active) {
            // Restart with GPS.
            try {
                val f = c.endExerciseAsync()
                f.addListener({ active = false; start() }, ContextCompat_mainExecutor(ctx))
            } catch (_: Throwable) { start() }
        } else start()
    }

    private fun hasLocationPermission() = androidx.core.content.ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun stop() {
        val c = client ?: return
        if (!active) return
        try { c.endExerciseAsync() } catch (_: Throwable) {}
        try { c.clearUpdateCallbackAsync(callback) } catch (_: Throwable) {}
        active = false; gpsOn = false
        bpm = null
    }

    fun fresh(): Int? = if (SystemClock.elapsedRealtime() - at < 10_000) bpm else null

    private fun exType(t: fi.sarmat.pulsetrainer.core.WorkoutType): androidx.health.services.client.data.ExerciseType {
        val E = androidx.health.services.client.data.ExerciseType
        return when (t) {
            fi.sarmat.pulsetrainer.core.WorkoutType.WALK, fi.sarmat.pulsetrainer.core.WorkoutType.STAIRS_OUTDOOR -> E.WALKING
            fi.sarmat.pulsetrainer.core.WorkoutType.RUN -> E.RUNNING
            fi.sarmat.pulsetrainer.core.WorkoutType.TREADMILL -> E.RUNNING_TREADMILL
            fi.sarmat.pulsetrainer.core.WorkoutType.BIKE_OUTDOOR -> E.BIKING
            fi.sarmat.pulsetrainer.core.WorkoutType.BIKE_INDOOR -> E.BIKING_STATIONARY
            fi.sarmat.pulsetrainer.core.WorkoutType.ELLIPTICAL -> E.ELLIPTICAL
            fi.sarmat.pulsetrainer.core.WorkoutType.BOXING -> E.BOXING
            fi.sarmat.pulsetrainer.core.WorkoutType.HIKING -> E.HIKING
            else -> if (t.gps) E.WALKING else E.STRENGTH_TRAINING
        }
    }
}

private fun ContextCompat_mainExecutor(ctx: Context) = androidx.core.content.ContextCompat.getMainExecutor(ctx)

/**
 * Barometer + step counter for climbing workouts and walking/running.
 * Height comes from air pressure (much more precise for floors than GPS altitude):
 * smoothed, with a 1.5 m dead band so breathing and doors do not count as climbing.
 * Sensors are batched by the system (values delivered every few seconds) to save battery.
 */
class ClimbSensor(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var on = false
    private var alt: Double? = null
    private var ref: Double? = null
    private var stepBase = -1f

    @Volatile var ascent = 0.0; private set
    @Volatile var descent = 0.0; private set
    @Volatile var steps = 0; private set
    @Volatile var hasBaro = false; private set
    @Volatile var altitude: Double? = null; private set

    fun start() {
        if (on || sm == null) return
        on = true
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, 500_000, 4_000_000); hasBaro = true }
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            try { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 5_000_000) } catch (_: SecurityException) {}
        }
    }

    fun stop() {
        if (!on) return
        sm?.unregisterListener(this)
        on = false
    }

    /** New exercise segment: counting starts from zero. */
    fun reset() { ascent = 0.0; descent = 0.0; steps = 0; stepBase = -1f; ref = alt }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val a = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]).toDouble()
                val s = alt?.let { it + (a - it) * 0.15 } ?: a
                alt = s; altitude = s
                val r = ref
                if (r == null) ref = s
                else if (s - r >= 1.5) { ascent += s - r; ref = s }
                else if (r - s >= 1.5) { descent += r - s; ref = s }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val v = e.values[0]
                if (stepBase < 0) stepBase = v
                steps = (v - stepBase).toInt()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
