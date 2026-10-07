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
        /** Completed sets in this exercise. */
        val setsDone: Int = 0,
        /** Quiet coach line at the bottom of the screen. */
        val assist: String? = null,
        /** 0 info, 1 good (ready), 2 warning. */
        val assistLevel: Int = 0,
        /** The coach suggests finishing the workout (shown until dismissed by finishing). */
        val endAdvice: String? = null,
        val autoPaused: Boolean = false,
        val autoPauseOn: Boolean = false,
        val kmAlertOn: Boolean = true,
        val avgPaceSecPerKm: Int? = null,
        val avgSpeedKmh: Double = 0.0,
        val ascentM: Int = 0,
        val lastLapSec: Int? = null,
        val z23Min: Int = 0,
        val trimp: Int = 0,
        val warmup: Boolean = false,
        val floors: Int = 0,
        val descentM: Int = 0,
        val steps: Int = 0,
        /** Steps per minute over the last minute. */
        val cadence: Int = 0,
        /** Metres up per minute over the last minute (stairs). */
        val vSpeed: Int = 0,
        // ----- interval timer (Tabata, HIIT, boxing, rope) -----
        val roundsPerCycle: Int = 0,
        val roundInCycle: Int = 1,
        val cycleNo: Int = 1,
        val cycles: Int = 1,
        val prepping: Boolean = false,
        val betweenCycles: Boolean = false,
        val intervalsDone: Boolean = false,
    )

    interface Hooks {
        val context: Context
        fun reconfigure(type: WorkoutType)
        fun watchBpm(): Int?
        /** Barometer/steps of the current segment (null when the sensor is off). */
        fun climb(): ClimbSensor?
        /** «Location» is switched off in the watch settings. */
        fun gpsOff(): Boolean
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
    private var cfg = fi.sarmat.pulsetrainer.core.IntervalCfg(180, 60, 8)
    private var roundInCycle = 1
    private var cycleNo = 1
    private var prepping = false
    private var betweenCycles = false
    private var intervalsDone = false

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

    // ----- automatic sets (by heart rate) -----
    private val smooth = ArrayDeque<Int>()           // last 5 raw values
    private val sHist = ArrayDeque<Pair<Long, Double>>() // smoothed HR, last 20 s
    private var peakS = 0.0
    private var peakT = 0L
    private var troughS = 999.0
    private var warmup = true
    private var slowRests = 0
    private var restReadyAt = 0

    // ----- coach -----
    private var endAdvice: String? = null
    private var lastSafety = 0L
    private var adviceAt = 0
    private var nearMaxSec = 0

    // ----- auto-pause / km -----
    private var autoPauseOn = false
    private var kmAlertOn = true
    private var autoPaused = false
    private val moveWin = ArrayDeque<Pair<Long, Location>>()
    private var slowSec = 0
    private var lastLapSec: Int? = null
    private var ascent = 0.0
    private var lastAlt: Double? = null
    private val climbHist = ArrayDeque<Triple<Long, Double, Int>>() // time, ascent, steps

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
        endAdvice = null; lastSafety = 0L; nearMaxSec = 0
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

    fun setAutoPause(on: Boolean) {
        autoPauseOn = on
        Storage.setAutoPause(type, on)
        if (!on) autoPaused = false
        publish()
    }

    fun setKmAlert(on: Boolean) {
        kmAlertOn = on
        Storage.setKmAlert(type, on)
        publish()
    }

    fun adjustReps(d: Int) {
        reps = max(0, reps + d)
        publish()
    }

    /**
     * Rep exercises (pull-ups, push-ups, squats): sets come from the reps themselves.
     * 2 reps within 6 s start a set; 8 s without a rep ends it (at the last rep).
     */
    fun onRep(t: Long) {
        if (!running || paused || type.mode != Mode.SETS) return
        if (phase == Phase.REST) {
            if (t - lastRepT <= 6000) {
                startSet(lastRepT - 1500)
                reps = 2
            }
            lastRepT = t
            publish(); return
        }
        reps++
        lastRepT = t
        publish()
    }
    private var lastRepT = 0L
    private var restReps = 0

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

        if (type.gps && autoPauseOn) checkAutoPause()
        if (!paused && !autoPaused) {
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

        if (!paused) coachCheck(cur)
        tickNo++
        if (tickNo % 2 == 0) hooks?.let { try { PhoneLink.sendLive(it.context, live(true)) } catch (_: Exception) {} }
        publish()
    }

    fun onLocation(loc: Location) {
        if (!running || paused || !type.gps) return
        if (loc.hasAccuracy() && loc.accuracy > 40f) { gpsFix = false; return }
        gpsFix = true
        val now = System.currentTimeMillis()
        moveWin.addLast(now to loc)
        while (moveWin.isNotEmpty() && now - moveWin.first().first > 12_000) moveWin.removeFirst()
        if (autoPaused) { lastLoc = null; return }
        if (loc.hasAltitude() && hooks?.climb()?.hasBaro != true) {
            val a = loc.altitude
            val prevA = lastAlt
            if (prevA == null) lastAlt = a
            else if (a - prevA >= 3) { ascent += a - prevA; lastAlt = a }
            else if (prevA - a >= 3) lastAlt = a
        }
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
        val lapEvery = if (type.lapM > 0) type.lapM else 1000
        if (segDist - lapStartDist >= lapEvery) {
            laps.add(Lap(lapStartT, now, segDist - lapStartDist))
            lastLapSec = ((now - lapStartT) / 1000).toInt()
            lapActiveMark = activeSec
            lapStartT = now; lapStartDist = segDist
            if (kmAlertOn) Haptics.lap()
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
        roundInCycle = 1; cycleNo = 1; betweenCycles = false; intervalsDone = false
        if (t.mode == Mode.ROUNDS) {
            cfg = Storage.intervals(t)
            prepping = cfg.prep > 0
            phase = if (prepping) Phase.REST else Phase.WORK
            roundLeft = if (prepping) cfg.prep else cfg.work
        } else { prepping = false; roundLeft = 0 }
        segDist = 0.0
        lastLoc = null
        gpsFix = false
        lapStartT = now; lapStartDist = 0.0
        paceWin.clear()
        moveWin.clear(); slowSec = 0; autoPaused = false; lastLapSec = null; ascent = 0.0; lastAlt = null
        climbHist.clear()
        autoPauseOn = Storage.autoPause(t)
        kmAlertOn = Storage.kmAlert(t)
        smooth.clear(); sHist.clear(); peakS = 0.0; troughS = 999.0; warmup = true; slowRests = 0; lastRepT = 0L
        // Strength: start "resting" — the first set is detected by heart rate (no button needed).
        if (t.mode == Mode.SETS) { phase = Phase.REST; phaseSec = 0; setNo = 0 }
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
            Mode.ROUNDS -> if (phase == Phase.WORK && roundLeft < cfg.work - 3) {
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
                ascentM = climbNow().first,
                descentM = climbNow().second,
                floors = floorsOf(climbNow().first, climbNow().third),
                steps = climbNow().third,
            )
        )
    }

    private fun endSet(end: Long) {
        val now = System.currentTimeMillis()
        val e = maxOf(minOf(end, now), minOf(setStart + 5000, now))
        sets.add(SetRecord(setStart, e, reps, setPeak, null, null))
        phase = Phase.REST
        phaseSec = ((now - e) / 1000).toInt()
        restPeak = max(setPeak, curHr ?: 0)
        troughS = 999.0
        readyNotified = false; adviceGiven = false; advice = null; lastHrr60 = null
    }

    private fun startSet(start: Long) {
        val now = System.currentTimeMillis()
        if (sets.isNotEmpty()) sets[sets.lastIndex] = sets.last().copy(restSec = (phaseSec - ((now - start) / 1000).toInt()).coerceAtLeast(0))
        if (sets.isNotEmpty() && !readyNotified && phaseSec >= SLOW_RECOVERY_SEC) slowRests++ else if (readyNotified) slowRests = 0
        warmup = false
        phase = Phase.WORK
        phaseSec = ((now - start) / 1000).toInt()
        setNo++
        setStart = start
        setPeak = 0
        peakS = 0.0
        reps = 0
        advice = null
        hooks?.resetReps()
    }

    /**
     * Sets are found from the pulse, no button needed:
     * - a set started when the smoothed pulse climbs ≥7 bpm above the rest trough and keeps rising
     *   (it lags the effort by ~10 s, so the set start is moved back);
     * - a set ended when the pulse drops ≥6 bpm below its peak (it peaks ~10 s after the last rep).
     * The rep counter (pull-ups, push-ups, squats) also starts a set after 3 reps.
     */
    private fun autoDetect(now: Long, cur: Int?) {
        if (cur == null) return
        smooth.addLast(cur); while (smooth.size > 5) smooth.removeFirst()
        val sv = smooth.average()
        sHist.addLast(now to sv); while (sHist.isNotEmpty() && now - sHist.first().first > 20_000) sHist.removeFirst()
        val ago5 = sHist.firstOrNull { now - it.first <= 6_000 }?.second ?: sv
        if (phase == Phase.WORK) {
            if (sv > peakS) { peakS = sv; peakT = now }
            if (phaseSec >= 15 && peakS - sv >= 6 && sv < ago5) {
                endSet(peakT - 8_000)
            } else if (phaseSec >= 240) {
                // Long steady effort without a drop: treat as one long set.
                endSet(now)
            }
        } else {
            if (sv < troughS) troughS = sv
            val minGap = if (warmup) 20 else 30
            if (phaseSec >= minGap && sv - troughS >= 7 && sv - ago5 >= 3) startSet(now - 10_000)
        }
    }

    private fun tickSets(now: Long, cur: Int?) {
        if (type.repCount) {
            // Sets by reps, not by pulse.
            if (phase == Phase.WORK) {
                if (reps > 0 && now - lastRepT > 8000) endSet(lastRepT + 1000)
                else if (reps == 0 && phaseSec > 25) { phase = Phase.REST; phaseSec = 0; setNo = (setNo - 1).coerceAtLeast(0) }
            }
            if (phase == Phase.WORK) { if (cur != null) setPeak = max(setPeak, cur); return }
            if (warmup) return
        } else autoDetect(now, cur)
        if (phase == Phase.WORK) {
            if (cur != null) setPeak = max(setPeak, cur)
            return
        }
        if (warmup) return
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
            restReadyAt = phaseSec
            Haptics.tick() // one short, gentle tick — not an alarm
        }
        if (!ready && phaseSec >= SLOW_RECOVERY_SEC && !adviceGiven) {
            adviceGiven = true
            advice = "Пульс падает медленно — отдохните ещё или облегчите подход"
        }
    }

    private fun isRestReady(cur: Int?): Boolean =
        phaseSec >= type.minRestSec && (cur == null || cur <= readyHr)

    private fun endRound(now: Long) {
        if (prepping) { prepping = false; startRound(now); return }
        sets.add(SetRecord(setStart, now, 0, setPeak, null, null))
        phase = Phase.REST
        phaseSec = 0
        restPeak = max(setPeak, curHr ?: 0)
        when {
            roundInCycle >= cfg.rounds && cycleNo >= cfg.cycles -> {
                intervalsDone = true; roundLeft = 0
                Haptics.ready()
                return
            }
            roundInCycle >= cfg.rounds -> { betweenCycles = true; roundLeft = cfg.cycleRest }
            else -> roundLeft = cfg.rest
        }
        Haptics.phase()
        if (roundLeft <= 0) startRound(now)
    }

    private fun startRound(now: Long) {
        if (intervalsDone) return
        if (sets.isNotEmpty() && !prepping) {
            val drop = curHr?.let { restPeak - it }
            sets[sets.lastIndex] = sets.last().copy(restSec = phaseSec, hrr60 = drop)
            lastHrr60 = drop
        }
        if (prepping) prepping = false
        else if (betweenCycles) { betweenCycles = false; cycleNo++; roundInCycle = 1; roundNo++ }
        else if (phase == Phase.REST && sets.isNotEmpty()) { roundInCycle++; roundNo++ }
        phase = Phase.WORK
        phaseSec = 0
        roundLeft = cfg.work
        setStart = now
        setPeak = 0
        Haptics.phase()
    }

    private fun tickRounds(now: Long, cur: Int?) {
        if (intervalsDone) return
        if (phase == Phase.WORK && cur != null) setPeak = max(setPeak, cur)
        roundLeft--
        if (roundLeft in 1..3) Haptics.tick()
        if (roundLeft <= 0) {
            if (phase == Phase.WORK) endRound(now) else startRound(now)
        }
    }

    private fun climbNow(): Triple<Double, Double, Int> {
        val c = hooks?.climb()
        return if (c != null && c.hasBaro) Triple(c.ascent, c.descent, c.steps) else Triple(ascent, 0.0, c?.steps ?: 0)
    }

    /** Floors: 3 m of climbing; on a stair machine (no height change) 16 steps = 1 floor. */
    private fun floorsOf(asc: Double, steps: Int) = if (type == WorkoutType.STAIRS) steps / 16 else (asc / 3.0).toInt()

    private fun checkAutoPause() {
        if (moveWin.size < 2) return
        val (t0, a) = moveWin.first(); val (t1, b) = moveWin.last()
        val dt = (t1 - t0) / 1000.0
        if (dt < 5) return
        val kmh = a.distanceTo(b) / dt * 3.6
        val stopAt = if (type == WorkoutType.BIKE_OUTDOOR) 3.0 else 1.2
        if (!autoPaused) {
            if (kmh < stopAt) slowSec++ else slowSec = 0
            if (slowSec >= 8) { autoPaused = true; slowSec = 0; Haptics.tick() }
        } else if (kmh > stopAt + 1.0) {
            autoPaused = false; lastLoc = null; Haptics.tick()
        }
    }

    /**
     * A personal coach in the background: quiet hints, and one vibration when it is time to stop.
     * Based on: 60–75 min as the useful limit for a strength session; slowing heart-rate recovery
     * between sets as a sign of fatigue; staying near max heart rate as a safety signal.
     */
    private fun coachCheck(cur: Int?) {
        val now = System.currentTimeMillis()
        val max = Physiology.maxHr(profile)
        if (cur != null && cur >= max * 0.97) nearMaxSec++ else nearMaxSec = 0
        if (nearMaxSec >= 45 && now - lastSafety > 5 * 60_000L) {
            lastSafety = now
            advice = "Пульс у максимума ($cur) — сбавьте темп и подышите"
            adviceAt = activeSec
            Haptics.warn()
        }
        if (endAdvice != null || activeSec % 30 != 0) return
        val min = activeSec / 60
        val strength = type.mode == Mode.SETS
        val hrr = (segments.flatMap { it.sets } + sets).mapNotNull { it.hrr60 }
        val msg = when {
            strength && min >= 75 -> "Уже $min мин силовой — пора заканчивать: дальше качество подходов падает"
            strength && hrr.size >= 6 && hrr.takeLast(3).average() < hrr.take(3).average() * 0.6 ->
                "Восстановление пульса упало почти вдвое — на сегодня достаточно"
            strength && slowRests >= 2 -> "Пульс уже два раза долго не восстанавливается — лучше закончить"
            !strength && type == WorkoutType.WALK && min >= 120 -> "2 часа ходьбы — отличный объём, можно заканчивать"
            !strength && type != WorkoutType.WALK && type.mode == Mode.CARDIO && min >= 90 -> "$min мин — хороший объём. Можно заканчивать, выпейте воды"
            else -> null
        }
        if (msg != null) { endAdvice = msg; Haptics.warn() }
    }

    private fun assist(cur: Int?): Pair<String?, Int> {
        endAdvice?.let { return "Тренер: $it" to 2 }
        if (advice != null && type.mode != Mode.SETS && activeSec - adviceAt > 40) advice = null
        advice?.let { return it to 2 }
        if (autoPaused) return "Автопауза — начните движение" to 0
        if (HrSensor.isConnected() && HrSensor.contactLost.value) return "Ремень: нет контакта — смочите электроды, пульс с часов" to 2
        HrSensor.battery.value?.takeIf { it in 0..10 && activeSec < 120 }?.let { return "Батарея ремня $it% — скоро заменить (CR2025)" to 2 }
        if (type.gps && !gpsFix) return (if (hooks?.gpsOff() == true) "Включите «Местоположение» в настройках часов" else "Поиск GPS… лучше на открытом месте") to 2
        return when (type.mode) {
            Mode.SETS -> when {
                warmup && phase == Phase.REST -> (if (type.repCount) "Начните — повторы и подходы посчитаются сами" else "Разминка. Подходы отметятся сами по пульсу") to 0
                phase == Phase.WORK -> "Подход ${setNo}: ${fmtDurationShort(phaseSec)}" to 0
                isRestReady(cur) -> "✓ Можно подход" + (lastHrr60?.let { " · пульс −$it за мин" } ?: "") to 1
                else -> {
                    val parts = ArrayList<String>()
                    if (phaseSec < type.minRestSec) parts += "ещё ${type.minRestSec - phaseSec} с"
                    if (cur != null && cur > readyHr) parts += "пульс до $readyHr"
                    "Отдых: " + parts.joinToString(" · ") to 0
                }
            }
            Mode.ROUNDS -> when {
                intervalsDone -> "✓ Готово! Завершите или смените упражнение" to 1
                prepping -> "Приготовьтесь…" to 0
                else -> null to 0
            }
            Mode.CARDIO -> {
                val z = cur?.let { Physiology.zoneOf(it, bounds) } ?: 0
                when {
                    lastLapSec != null && type.gps && (activeSec - lapEndActive) < 40 ->
                        "Км ${laps.size}: ${fmtDurationShort(lastLapSec!!)}" to 1
                    z >= 4 && type != WorkoutType.RUN -> "Зона $z — тяжело. Для сердца держите ${bounds[1]}–${bounds[2]}" to 2
                    z <= 1 && activeSec > 300 -> "Ниже зоны 2 — можно прибавить до ${bounds[1]}+" to 0
                    z in 2..3 -> "Зона $z — то, что нужно для сердца" to 1
                    else -> null to 0
                }
            }
        }
    }
    private val lapEndActive: Int get() = lapActiveMark
    private var lapActiveMark = 0

    private fun fmtDurationShort(sec: Int) = fi.sarmat.pulsetrainer.core.fmtDuration(sec)

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
        val a = assist(cur)
        val (asc, desc, stp) = climbNow()
        val nowT = System.currentTimeMillis()
        climbHist.addLast(Triple(nowT, asc, stp))
        while (climbHist.isNotEmpty() && nowT - climbHist.first().first > 60_000) climbHist.removeFirst()
        val h0 = climbHist.first()
        val win = ((nowT - h0.first) / 60000.0).coerceAtLeast(1.0 / 60)
        val cad = if (nowT - h0.first >= 20_000) ((stp - h0.third) / win).toInt() else 0
        val vs = if (nowT - h0.first >= 20_000) ((asc - h0.second) / win).toInt() else 0
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
            restReady = phase == Phase.REST && !warmup && isRestReady(cur),
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
            setsDone = sets.size,
            assist = a.first,
            assistLevel = a.second,
            endAdvice = endAdvice,
            autoPaused = autoPaused,
            autoPauseOn = autoPauseOn,
            kmAlertOn = kmAlertOn,
            avgPaceSecPerKm = if (segDist > 50) (segActive / (segDist / 1000.0)).toInt() else null,
            avgSpeedKmh = if (segActive > 0) segDist / segActive * 3.6 else 0.0,
            ascentM = asc.toInt(),
            descentM = desc.toInt(),
            floors = floorsOf(asc, stp),
            steps = stp,
            cadence = cad,
            vSpeed = vs,
            lastLapSec = lastLapSec,
            z23Min = (segZone[2] + segZone[3]) / 60,
            trimp = (segments.sumOf { it.trimp } + segTrimp).toInt(),
            warmup = warmup && type.mode == Mode.SETS,
            roundsPerCycle = cfg.rounds,
            roundInCycle = roundInCycle,
            cycleNo = cycleNo,
            cycles = cfg.cycles,
            prepping = prepping && type.mode == Mode.ROUNDS,
            betweenCycles = betweenCycles,
            intervalsDone = intervalsDone,
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
