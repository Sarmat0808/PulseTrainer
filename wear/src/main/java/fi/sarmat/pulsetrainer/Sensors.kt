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
