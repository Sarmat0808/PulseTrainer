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
import kotlin.math.max
import kotlin.math.sqrt

/** Backup: the watch's own optical sensor, used only when the strap is not delivering. */
class WatchHr(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    var bpm: Int? = null
        private set
    private var at = 0L

    fun start() {
        val s = sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        try { sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL) } catch (_: SecurityException) {}
    }

    fun stop() { sm?.unregisterListener(this); bpm = null }

    fun fresh(): Int? = if (SystemClock.elapsedRealtime() - at < 6000) bpm else null

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values.firstOrNull()?.toInt() ?: return
        if (v in 30..230 && e.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            bpm = v; at = SystemClock.elapsedRealtime()
        }
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
