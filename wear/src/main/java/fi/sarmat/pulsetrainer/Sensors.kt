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
 * Repetition counter (beta) from wrist acceleration.
 * Smoothed movement magnitude + adaptive threshold with hysteresis and a minimum
 * time between reps. Pull-ups, push-ups and squats all move the wrist rhythmically.
 * The count can always be corrected with +/− on screen.
 */
class RepCounter(ctx: Context, private val onRep: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var smooth = 0.0
    private var level = 1.0
    private var armed = true
    private var lastRep = 0L

    fun start() {
        reset()
        val s = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() { sm?.unregisterListener(this) }

    fun reset() { smooth = 0.0; level = 1.0; armed = true; lastRep = 0L }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
        val m = sqrt(x * x + y * y + z * z)
        smooth += 0.25 * (m - smooth)
        level += 0.01 * (smooth - level)          // slow average of activity
        val hi = max(1.6, level * 1.7)
        val lo = hi * 0.45
        val now = SystemClock.elapsedRealtime()
        if (armed && smooth > hi && now - lastRep > 900) {
            armed = false
            lastRep = now
            onRep()
        } else if (!armed && smooth < lo) {
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
 * Watch heart rate during a workout via Health Services *exercise* mode.
 * Unlike the measure client and the raw sensor, an active exercise keeps delivering
 * heart rate when the app is in the background (watch face shown, a call answered, the
 * screen off), because the system itself keeps the sensor running for the workout.
 */
class ExerciseHr(private val ctx: Context) {
    private val client = try { HealthServices.getClient(ctx).exerciseClient } catch (_: Throwable) { null }
    @Volatile private var bpm: Int? = null
    @Volatile private var at = 0L
    private var active = false

    private val callback = object : androidx.health.services.client.ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: androidx.health.services.client.data.ExerciseUpdate) {
            val v = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            val r = v.roundToInt()
            if (r in 30..230) { bpm = r; at = SystemClock.elapsedRealtime() }
        }
        override fun onLapSummaryReceived(lapSummary: androidx.health.services.client.data.ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    fun start(type: fi.sarmat.pulsetrainer.core.WorkoutType) {
        val c = client ?: return
        if (active) return
        try {
            c.setUpdateCallback(callback)
            val config = androidx.health.services.client.data.ExerciseConfig(
                exerciseType = exType(type),
                dataTypes = setOf(DataType.HEART_RATE_BPM),
                isAutoPauseAndResumeEnabled = false,
                isGpsEnabled = false,
            )
            c.startExerciseAsync(config)
            active = true
        } catch (_: Throwable) {}
    }

    fun stop() {
        val c = client ?: return
        if (!active) return
        try { c.endExerciseAsync() } catch (_: Throwable) {}
        try { c.clearUpdateCallbackAsync(callback) } catch (_: Throwable) {}
        active = false
        bpm = null
    }

    fun fresh(): Int? = if (SystemClock.elapsedRealtime() - at < 10_000) bpm else null

    private fun exType(t: fi.sarmat.pulsetrainer.core.WorkoutType): androidx.health.services.client.data.ExerciseType {
        val E = androidx.health.services.client.data.ExerciseType
        return when (t) {
            fi.sarmat.pulsetrainer.core.WorkoutType.WALK -> E.WALKING
            fi.sarmat.pulsetrainer.core.WorkoutType.RUN -> E.RUNNING
            fi.sarmat.pulsetrainer.core.WorkoutType.TREADMILL -> E.RUNNING_TREADMILL
            fi.sarmat.pulsetrainer.core.WorkoutType.BIKE_OUTDOOR -> E.BIKING
            fi.sarmat.pulsetrainer.core.WorkoutType.BIKE_INDOOR -> E.BIKING_STATIONARY
            fi.sarmat.pulsetrainer.core.WorkoutType.ELLIPTICAL -> E.ELLIPTICAL
            fi.sarmat.pulsetrainer.core.WorkoutType.BOXING -> E.BOXING
            fi.sarmat.pulsetrainer.core.WorkoutType.HIKING -> E.HIKING
            else -> E.STRENGTH_TRAINING
        }
    }
}

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
