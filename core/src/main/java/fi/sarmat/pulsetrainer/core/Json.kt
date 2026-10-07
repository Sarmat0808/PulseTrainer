package fi.sarmat.pulsetrainer.core

import org.json.JSONArray
import org.json.JSONObject

object WorkoutJson {

    fun toJson(w: Workout): String {
        val o = JSONObject()
        o.put("v", 2)
        o.put("id", w.id)
        o.put("start", w.start)
        o.put("end", w.end)
        o.put("activeSec", w.activeSec)
        o.put("hrSource", w.hrSource)
        o.put("recoveryHours", w.recoveryHours)
        o.put("synced", w.syncedToHealth)
        o.put("zoneBounds", JSONArray(w.zoneBounds.toList()))
        // Compact arrays: [t, bpm, t, bpm, ...]
        val hr = JSONArray()
        w.hr.forEach { hr.put(it.t); hr.put(it.bpm) }
        o.put("hr", hr)
        val tr = JSONArray()
        w.track.forEach {
            tr.put(JSONArray().put(it.t).put(it.lat).put(it.lon).put(it.alt ?: JSONObject.NULL).put(it.acc?.toDouble() ?: JSONObject.NULL))
        }
        o.put("track", tr)
        val segs = JSONArray()
        w.segments.forEach { s ->
            val so = JSONObject()
            so.put("type", s.type.name)
            so.put("start", s.start)
            so.put("end", s.end)
            so.put("activeSec", s.activeSec)
            so.put("distanceM", s.distanceM)
            so.put("kcalTotal", s.kcalTotal)
            so.put("kcalActive", s.kcalActive)
            so.put("zoneSec", JSONArray(s.zoneSec.toList()))
            so.put("trimp", s.trimp)
            so.put("avgHr", s.avgHr)
            so.put("maxHr", s.maxHr)
            val sets = JSONArray()
            s.sets.forEach {
                sets.put(
                    JSONObject().put("start", it.start).put("end", it.end).put("reps", it.reps)
                        .put("peakHr", it.peakHr).put("hrr60", it.hrr60 ?: JSONObject.NULL)
                        .put("restSec", it.restSec ?: JSONObject.NULL)
                )
            }
            so.put("sets", sets)
            val laps = JSONArray()
            s.laps.forEach { laps.put(JSONObject().put("start", it.start).put("end", it.end).put("d", it.distanceM)) }
            so.put("laps", laps)
            segs.put(so)
        }
        o.put("segments", segs)
        return o.toString()
    }

    private fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    private fun JSONArray.ints(): IntArray = IntArray(length()) { optInt(it) }

    fun fromJson(s: String): Workout {
        val o = JSONObject(s)
        val hrArr = o.optJSONArray("hr") ?: JSONArray()
        val hr = ArrayList<HrSample>(hrArr.length() / 2)
        var i = 0
        while (i + 1 < hrArr.length()) {
            hr.add(HrSample(hrArr.getLong(i), hrArr.getInt(i + 1))); i += 2
        }
        val trArr = o.optJSONArray("track") ?: JSONArray()
        val track = (0 until trArr.length()).map {
            val a = trArr.getJSONArray(it)
            GeoPoint(
                a.getLong(0), a.getDouble(1), a.getDouble(2),
                if (a.isNull(3)) null else a.getDouble(3),
                if (a.isNull(4)) null else a.getDouble(4).toFloat()
            )
        }
        val segArr = o.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until segArr.length()).map { idx ->
            val so = segArr.getJSONObject(idx)
            val setsArr = so.optJSONArray("sets") ?: JSONArray()
            val sets = (0 until setsArr.length()).map {
                val x = setsArr.getJSONObject(it)
                SetRecord(x.getLong("start"), x.getLong("end"), x.optInt("reps"), x.optInt("peakHr"),
                    x.optIntOrNull("hrr60"), x.optIntOrNull("restSec"))
            }
            val lapsArr = so.optJSONArray("laps") ?: JSONArray()
            val laps = (0 until lapsArr.length()).map {
                val x = lapsArr.getJSONObject(it)
                Lap(x.getLong("start"), x.getLong("end"), x.optDouble("d"))
            }
            Segment(
                type = WorkoutType.of(so.getString("type")),
                start = so.getLong("start"),
                end = so.getLong("end"),
                activeSec = so.optInt("activeSec"),
                sets = sets,
                laps = laps,
                distanceM = so.optDouble("distanceM", 0.0),
                kcalTotal = so.optDouble("kcalTotal", 0.0),
                kcalActive = so.optDouble("kcalActive", 0.0),
                zoneSec = (so.optJSONArray("zoneSec") ?: JSONArray()).ints().let { if (it.size == 6) it else IntArray(6) },
                trimp = so.optDouble("trimp", 0.0),
                avgHr = so.optInt("avgHr"),
                maxHr = so.optInt("maxHr"),
            )
        }
        return Workout(
            id = o.getString("id"),
            start = o.getLong("start"),
            end = o.getLong("end"),
            activeSec = o.optInt("activeSec"),
            hrSource = o.optString("hrSource"),
            hr = hr,
            track = track,
            segments = segments,
            zoneBounds = (o.optJSONArray("zoneBounds") ?: JSONArray()).ints(),
            recoveryHours = o.optInt("recoveryHours"),
            syncedToHealth = o.optBoolean("synced"),
        )
    }

    fun profileToJson(p: Profile): String = JSONObject()
        .put("age", p.age).put("weight", p.weightKg).put("height", p.heightCm).put("male", p.male)
        .put("restHr", p.restHr ?: JSONObject.NULL).put("maxHr", p.maxHrOverride ?: JSONObject.NULL)
        .put("karvonen", p.karvonen)
        .toString()

    fun profileFromJson(s: String?): Profile? {
        if (s.isNullOrBlank()) return null
        return try {
            val o = JSONObject(s)
            Profile(
                age = o.optInt("age", 44),
                weightKg = o.optDouble("weight", 92.0),
                heightCm = o.optInt("height", 177),
                male = o.optBoolean("male", true),
                restHr = if (o.isNull("restHr")) null else o.optInt("restHr"),
                maxHrOverride = if (o.isNull("maxHr")) null else o.optInt("maxHr"),
                karvonen = o.optBoolean("karvonen", false),
            )
        } catch (_: Exception) { null }
    }

    fun hrvToJson(list: List<HrvRecord>): String {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("t", it.time).put("rmssd", it.rmssd).put("rest", it.restHr).put("st", it.status))
        }
        return a.toString()
    }

    fun hrvFromJson(s: String?): List<HrvRecord> {
        if (s.isNullOrBlank()) return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).map {
            val x = a.getJSONObject(it)
            HrvRecord(x.getLong("t"), x.getDouble("rmssd"), x.getInt("rest"), x.getInt("st"))
        }
    }
}

/** Watch <-> phone messages. */
object Protocol {
    const val PATH_WORKOUT = "/workout/"          // DataItem with the finished session
    const val PATH_LIVE = "/live"                 // watch -> phone, every 2 s during a session
    const val PATH_CONTROL = "/control"           // phone -> watch commands
    const val PATH_PROFILE = "/profile"           // DataItem: profile + morning tests (for reports)
    const val PATH_PROFILE_SET = "/control/profile" // phone -> watch: profile edited on the phone

    const val CMD_PAUSE = "pause"
    const val CMD_RESUME = "resume"
    const val CMD_FINISH = "finish"
    const val CMD_DISCARD = "discard"
    const val CMD_SWITCH = "switch:"              // + WorkoutType.name
    const val CMD_NEXT = "next"                   // finish set / start next set

    data class Live(
        val running: Boolean,
        val type: String,
        val segmentNo: Int,
        val elapsedSec: Int,
        val hr: Int?,
        val zone: Int,
        val paused: Boolean,
        val phase: String,
        val setNo: Int,
        val kcal: Int,
        val distanceM: Double,
        val sensor: String,
        val time: Long,
    ) {
        fun toJson(): String = JSONObject()
            .put("running", running).put("type", type).put("seg", segmentNo).put("el", elapsedSec)
            .put("hr", hr ?: JSONObject.NULL).put("zone", zone).put("paused", paused).put("phase", phase)
            .put("set", setNo).put("kcal", kcal).put("dist", distanceM).put("sensor", sensor).put("time", time)
            .toString()

        companion object {
            fun fromJson(s: String): Live {
                val o = JSONObject(s)
                return Live(
                    o.optBoolean("running"), o.optString("type"), o.optInt("seg"), o.optInt("el"),
                    if (o.isNull("hr")) null else o.optInt("hr"), o.optInt("zone"), o.optBoolean("paused"),
                    o.optString("phase"), o.optInt("set"), o.optInt("kcal"), o.optDouble("dist", 0.0),
                    o.optString("sensor"), o.optLong("time"),
                )
            }
        }
    }
}
