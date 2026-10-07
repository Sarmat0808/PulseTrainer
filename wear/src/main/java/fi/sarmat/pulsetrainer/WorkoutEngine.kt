package fi.sarmat.pulsetrainer

import android.content.Context
import android.location.Location
import fi.sarmat.pulsetrainer.core.GeoPoint
import fi.sarmat.pulsetrainer.core.HrSample
import fi.sarmat.pulsetrainer.core.Lap
import fi.sarmat.pulsetrainer.core.Mode
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.Protocol
import fi.sarmat.pulsetrainer.core.Segment
import fi.sarmat.pulsetrainer.core.SetRecord
import fi.sarmat.pulsetrainer.core.Workout
import fi.sarmat.pulsetrainer.core.WorkoutType
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import kotlin.math.max

/**
 * The workout brain. All calls happen on the main thread (service tick, UI, phone commands).
 *
 * A session is a chain of segments: you can switch exercise at any time
 * (elliptical -> strength -> pull-ups ...) and heart rate, time and calories keep running.
 */
object WorkoutEngine {
    enum class Phase { WORK, REST }

    const val ROUND_WORK = 180
    const val ROUND_REST = 60
    const val SLOW_RECOVERY_SEC = 240

    data class Ui(
        val running: Boolean = false,
        val paused: Boolean = false,
        val type: WorkoutType = WorkoutType.STRENGTH,
        val segmentNo: Int = 1,
        val segmentTitles: List<String> = emptyList(),
        val elapsedSec: Int = 0,
        val segElapsedSec: Int = 0,
        val hr: Int? = null,
        val hrFromStrap: Boolean = false,
        val zone: Int = 0,
        val bounds: IntArray = IntArray(6),
        val avgHr: Int = 0,
        val kcal: Int = 0,
        val phase: Phase = Phase.WORK,
        val setNo: Int = 1,
        val phaseSec: Int = 0,
        val reps: Int = 0,
        val restReady: Boolean = false,
        val readyHr: Int = 0,
        val minRest: Int = 90,
        val advice: String? = null,
        val lastHrr60: Int? = null,
        val roundNo: Int = 1,
        val roundLeft: Int = 0,
        val distanceM: Double = 0.0,
        val paceSecPerKm: Int? = null,
        val speedKmh: Double = 0.0,
        val gpsFix: Boolean = false,
        val treadSpeed: Double = 8.0,
        val lapNo: Int = 1,
        val segZoneSec: IntArray = IntArray(6),
        val finishedId: String? = null,
        val discarded: Boolean = false,
    )

    interface Hooks {
        val context: Context
        fun reconfigure(type: WorkoutType)
        fun watchBpm(): Int?
        fun resetReps()
        fun stopped()
    }

    var hooks: Hooks? = null
    val ui = MutableStateFlow(Ui())
    val track = MutableStateFlow<List<GeoPoint>>(emptyList())

    // ----- session -----
    private var running = false
    private var paused = false
    private var id = ""
    private var sessionStart = 0L
    private var activeSec = 0
    private var profile = Profile()
    private var bounds = IntArray(6)
    private var readyHr = 115
    private val hr = ArrayList<HrSample>()
    private val trackPts = ArrayList<GeoPoint>()
    private val segments = ArrayList<Segment>()
    private var usedStrap = false
    private var usedWatch = false
    private var tickNo = 0

    // ----- current segment -----
    private var type = WorkoutType.STRENGTH
    private var segStart = 0L
    private var segActive = 0
    private var segKcal = 0.0
    private var segKcalAct = 0.0
    private var segZone = IntArray(6)
    private var segTrimp = 0.0
    private var segHrSum = 0L
    private var segHrN = 0
    private var segMax = 0
    private val sets = ArrayList<SetRecord>()
    private val laps = ArrayList<Lap>()

    // ----- sets / rounds -----
    private var phase = Phase.WORK
    private var phaseSec = 0
    private var setNo = 1
    private var setStart = 0L
    private var setPeak = 0
    private var restPeak = 0
    private var reps = 0
    private var readyNotified = false
    private var adviceGiven = false
    private var advice: String? = null
    private var lastHrr60: Int? = null
    private var roundNo = 1
    private var roundLeft = 0

    // ----- distance -----
    private var segDist = 0.0
    private var lastLoc: Location? = null
    private var gpsFix = false
    private var lapStartT = 0L
    private var lapStartDist = 0.0
    private val paceWin = ArrayDeque<Pair<Long, Double>>()
    private var treadSpeed = 8.0

    private var curHr: Int? = null
    private var curFromStrap = false

    // ================= public API =================

    fun start(t: WorkoutType) {
        if (running) { switchTo(t); return }
        running = true
        paused = false
        id = UUID.randomUUID().toString()
        sessionStart = System.currentTimeMillis()
        activeSec = 0
        profile = Storage.profile.value
        bounds = Physiology.zoneBounds(profile)
        readyHr = Physiology.readyHr(profile)
        hr.clear(); trackPts.clear(); segments.clear()
        track.value = emptyList()
        usedStrap = false; usedWatch = false; tickNo = 0
        beginSegment(t, sessionStart)
        publish()
    }

    /** Seamless change of exercise. Heart rate, time and calories continue. */
    fun switchTo(t: WorkoutType) {
        if (!running || t == type) return
        val now = System.currentTimeMillis()
        closeSegment(now)
        beginSegment(t, now)
        Haptics.phase()
        publish()
    }

    fun setPaused(p: Boolean) {
        if (!running) return
        paused = p
        if (!p) lastLoc = null // do not jump distance across a pause
        Haptics.tick()
        publish()
    }

    /** Main action button: finish set / start next set / skip round phase / manual lap. */
    fun nextPhase() {
        if (!running) return
        val now = System.currentTimeMillis()
        when (type.mode) {
            Mode.SETS -> if (phase == Phase.WORK) endSet(now) else startSet(now)
            Mode.ROUNDS -> if (phase == Phase.WORK) endRound(now) else startRound(now)
            Mode.CARDIO -> {
                if (segDist - lapStartDist > 10 || type.treadmill || !type.gps) {
                    laps.add(Lap(lapStartT, now, segDist - lapStartDist))
                    lapStartT = now; lapStartDist = segDist
                    Haptics.lap()
                }
            }
        }
        publish()
    }

    fun adjustReps(d: Int) {
        reps = max(0, reps + d)
        publish()
    }

    fun onRep() {
        if (!running || paused || type.mode != Mode.SETS || phase != Phase.WORK) return
        reps++
        Haptics.tick()
        publish()
    }

    fun adjustTreadSpeed(d: Double) {
        treadSpeed = (treadSpeed + d).coerceIn(1.0, 25.0)
        publish()
    }

    fun finish() {
        if (!running) return
        val now = System.currentTimeMillis()
        closeSegment(now)
        running = false
        val w = Workout(
            id = id,
            start = sessionStart,
            end = now,
            activeSec = activeSec,
            hrSource = when {
                usedStrap && usedWatch -> "${Storage.sensorName() ?: "Нагрудный датчик"} + часы"
                usedStrap -> Storage.sensorName() ?: "Нагрудный датчик"
                else -> "Датчик часов"
            },
            hr = hr.toList(),
            track = trackPts.toList(),
            segments = segments.toList(),
            zoneBounds = bounds,
            recoveryHours = Physiology.recoveryHours(segments),
        )
        Storage.save(w)
        hooks?.let { h ->
            try { PhoneLink.sendWorkout(h.context, w) } catch (_: Exception) {}
            try { PhoneLink.sendLive(h.context, live(false)) } catch (_: Exception) {}
        }
        ui.value = Ui(running = false, finishedId = w.id)
        hooks?.stopped()
    }

    /** Cancel without saving: nothing goes to history, phone or Samsung Health. */
    fun discard() {
        if (!running) return
        running = false
        hooks?.let { h -> try { PhoneLink.sendLive(h.context, live(false)) } catch (_: Exception) {} }
        ui.value = Ui(running = false, discarded = true)
        hooks?.stopped()
    }

    fun clearFinished() {
        if (!running) ui.value = Ui()
    }

    // ================= called by the service =================

    fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        val strap = HrSensor.freshBpm()
        val watch = if (strap == null) hooks?.watchBpm() else null
        val cur = strap ?: watch
        curHr = cur
        curFromStrap = strap != null

        if (!paused) {
            activeSec++
            segActive++
            phaseSec++
            if (cur != null) {
                if (strap != null) usedStrap = true else usedWatch = true
                hr.add(HrSample(now, cur))
                val z = Physiology.zoneOf(cur, bounds)
                segZone[z]++
                segTrimp += Physiology.trimpPerSec(z)
                val k = Physiology.kcalPerMin(cur, profile) / 60.0
                segKcal += k
                segKcalAct += max(0.0, k - Physiology.restingKcalPerMin(profile) / 60.0)
                segHrSum += cur; segHrN++
                segMax = max(segMax, cur)
            } else {
                segKcal += Physiology.restingKcalPerMin(profile) / 60.0
            }
            when (type.mode) {
                Mode.SETS -> tickSets(now, cur)
                Mode.ROUNDS -> tickRounds(now, cur)
                Mode.CARDIO -> if (type.treadmill) segDist += treadSpeed / 3.6
            }
        }

        tickNo++
        if (tickNo % 2 == 0) hooks?.let { try { PhoneLink.sendLive(it.context, live(true)) } catch (_: Exception) {} }
        publish()
    }

    fun onLocation(loc: Location) {
        if (!running || paused || !type.gps) return
        if (loc.hasAccuracy() && loc.accuracy > 30f) { gpsFix = false; return }
        gpsFix = true
        val now = System.currentTimeMillis()
        val prev = lastLoc
        if (prev == null) {
            lastLoc = loc
        } else {
            val d = prev.distanceTo(loc).toDouble()
            val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            val minStep = max(3.0, (if (loc.hasAccuracy()) loc.accuracy.toDouble() else 10.0) * 0.6)
            if (dt > 0 && d / dt < 30 && d >= minStep) {
                segDist += d
                lastLoc = loc
            }
        }
        trackPts.add(GeoPoint(now, loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null,
            if (loc.hasAccuracy()) loc.accuracy else null))
        paceWin.addLast(now to segDist)
        while (paceWin.isNotEmpty() && now - paceWin.first().first > 30_000) paceWin.removeFirst()
        if (type.lapM > 0 && segDist - lapStartDist >= type.lapM) {
            laps.add(Lap(lapStartT, now, segDist - lapStartDist))
            lapStartT = now; lapStartDist = segDist
            Haptics.lap()
        }
        if (trackPts.size % 3 == 0) track.value = trackPts.toList()
    }

    // ================= internals =================

    private fun beginSegment(t: WorkoutType, now: Long) {
        type = t
        Storage.touchType(t)
        segStart = now
        segActive = 0
        segKcal = 0.0; segKcalAct = 0.0
        segZone = IntArray(6)
        segTrimp = 0.0
        segHrSum = 0; segHrN = 0; segMax = 0
        sets.clear(); laps.clear()
        phase = Phase.WORK
        phaseSec = 0
        setNo = 1
        setStart = now
        setPeak = 0
        reps = 0
        readyNotified = false; adviceGiven = false; advice = null; lastHrr60 = null
        roundNo = 1
        roundLeft = if (t.mode == Mode.ROUNDS) t.roundWork else 0
        segDist = 0.0
        lastLoc = null
        gpsFix = false
        lapStartT = now; lapStartDist = 0.0
        paceWin.clear()
        hooks?.reconfigure(t)
        hooks?.resetReps()
    }

    private fun closeSegment(now: Long) {
        when (type.mode) {
            Mode.SETS -> {
                if (phase == Phase.WORK && (now - setStart >= 5000 || reps > 0)) {
                    sets.add(SetRecord(setStart, now, reps, setPeak, null, null))
                } else if (phase == Phase.REST && sets.isNotEmpty()) {
                    sets[sets.lastIndex] = sets.last().copy(restSec = phaseSec)
                }
            }
            Mode.ROUNDS -> if (phase == Phase.WORK && roundLeft < type.roundWork - 5) {
                sets.add(SetRecord(setStart, now, 0, setPeak, null, null))
            }
            Mode.CARDIO -> if (laps.isNotEmpty() && segDist - lapStartDist > 50) {
                laps.add(Lap(lapStartT, now, segDist - lapStartDist))
            }
        }
        if (segActive < 10 && segments.isNotEmpty() && sets.isEmpty()) return // accidental switch: drop
        segments.add(
            Segment(
                type = type,
                start = segStart,
                end = now,
                activeSec = segActive,
                sets = sets.toList(),
                laps = laps.toList(),
                distanceM = segDist,
                kcalTotal = segKcal,
                kcalActive = segKcalAct,
                zoneSec = segZone.copyOf(),
                trimp = segTrimp,
                avgHr = if (segHrN > 0) (segHrSum / segHrN).toInt() else 0,
                maxHr = segMax,
            )
        )
    }

    private fun endSet(now: Long) {
        sets.add(SetRecord(setStart, now, reps, setPeak, null, null))
        phase = Phase.REST
        phaseSec = 0
        restPeak = max(setPeak, curHr ?: 0)
        readyNotified = false; adviceGiven = false; advice = null; lastHrr60 = null
        Haptics.tick()
    }

    private fun startSet(now: Long) {
        if (sets.isNotEmpty()) sets[sets.lastIndex] = sets.last().copy(restSec = phaseSec)
        phase = Phase.WORK
        phaseSec = 0
        setNo++
        setStart = now
        setPeak = 0
        reps = 0
        advice = null
        hooks?.resetReps()
        Haptics.tick()
    }

    private fun tickSets(now: Long, cur: Int?) {
        if (phase == Phase.WORK) {
            if (cur != null) setPeak = max(setPeak, cur)
            return
        }
        // REST
        if (cur != null) restPeak = max(restPeak, if (phaseSec <= 10) cur else 0)
        if (phaseSec == 60 && cur != null && sets.isNotEmpty()) {
            val drop = restPeak - cur
            lastHrr60 = drop
            sets[sets.lastIndex] = sets.last().copy(hrr60 = drop)
        }
        val ready = isRestReady(cur)
        if (ready && !readyNotified) {
            readyNotified = true
            Haptics.ready()
        }
        if (!ready && phaseSec >= SLOW_RECOVERY_SEC && !adviceGiven) {
            adviceGiven = true
            advice = "Пульс снижается медленно. Следующий подход — легче или отдохните ещё."
            Haptics.warn()
        }
    }

    private fun isRestReady(cur: Int?): Boolean =
        phaseSec >= type.minRestSec && (cur == null || cur <= readyHr)

    private fun endRound(now: Long) {
        sets.add(SetRecord(setStart, now, 0, setPeak, null, null))
        phase = Phase.REST
        phaseSec = 0
        roundLeft = type.roundRest
        restPeak = max(setPeak, curHr ?: 0)
        Haptics.phase()
    }

    private fun startRound(now: Long) {
        if (sets.isNotEmpty()) {
            val drop = curHr?.let { restPeak - it }
            sets[sets.lastIndex] = sets.last().copy(restSec = phaseSec, hrr60 = drop)
            lastHrr60 = drop
        }
        phase = Phase.WORK
        phaseSec = 0
        roundNo++
        roundLeft = type.roundWork
        setStart = now
        setPeak = 0
        Haptics.phase()
    }

    private fun tickRounds(now: Long, cur: Int?) {
        if (phase == Phase.WORK && cur != null) setPeak = max(setPeak, cur)
        roundLeft--
        if (roundLeft == 10) Haptics.tick()
        if (roundLeft <= 0) {
            if (phase == Phase.WORK) endRound(now) else startRound(now)
        }
    }

    private fun pace(): Pair<Int?, Double> {
        if (type.treadmill) {
            val sp = treadSpeed
            return (3600.0 / sp).toInt() to sp
        }
        if (paceWin.size < 2) return null to 0.0
        val (t0, d0) = paceWin.first()
        val (t1, d1) = paceWin.last()
        val dt = (t1 - t0) / 1000.0
        val dd = d1 - d0
        if (dt < 5 || dd < 5) return null to 0.0
        val mps = dd / dt
        return (1000.0 / mps).toInt() to mps * 3.6
    }

    private fun publish() {
        if (!running) return
        val cur = curHr
        val (pace, speed) = pace()
        val totalKcal = segments.sumOf { it.kcalTotal } + segKcal
        val avg = if (hr.isEmpty()) 0 else (hr.sumOf { it.bpm.toLong() } / hr.size).toInt()
        ui.value = Ui(
            running = true,
            paused = paused,
            type = type,
            segmentNo = segments.size + 1,
            segmentTitles = segments.map { it.type.short } + type.short,
            elapsedSec = activeSec,
            segElapsedSec = segActive,
            hr = cur,
            hrFromStrap = curFromStrap,
            zone = cur?.let { Physiology.zoneOf(it, bounds) } ?: 0,
            bounds = bounds,
            avgHr = avg,
            kcal = totalKcal.toInt(),
            phase = phase,
            setNo = setNo,
            phaseSec = phaseSec,
            reps = reps,
            restReady = phase == Phase.REST && isRestReady(cur),
            readyHr = readyHr,
            minRest = type.minRestSec,
            advice = advice,
            lastHrr60 = lastHrr60,
            roundNo = roundNo,
            roundLeft = roundLeft,
            distanceM = segDist,
            paceSecPerKm = pace,
            speedKmh = speed,
            gpsFix = gpsFix,
            treadSpeed = treadSpeed,
            lapNo = laps.size + 1,
            segZoneSec = segZone.copyOf(),
        )
    }

    private fun live(isRunning: Boolean) = Protocol.Live(
        running = isRunning,
        type = type.name,
        segmentNo = segments.size + 1,
        elapsedSec = activeSec,
        hr = curHr,
        zone = curHr?.let { Physiology.zoneOf(it, bounds) } ?: 0,
        paused = paused,
        phase = when (type.mode) {
            Mode.CARDIO -> "CARDIO"
            else -> phase.name
        },
        setNo = if (type.mode == Mode.ROUNDS) roundNo else setNo,
        kcal = (segments.sumOf { it.kcalTotal } + segKcal).toInt(),
        distanceM = segDist,
        sensor = if (curFromStrap) "H10" else "watch",
        time = System.currentTimeMillis(),
    )
}
