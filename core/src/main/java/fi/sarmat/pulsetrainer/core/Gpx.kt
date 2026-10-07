package fi.sarmat.pulsetrainer.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** GPX 1.1 track with heart rate (Garmin TrackPointExtension) — opens in Organic Maps, Strava, etc. */
object Gpx {
    fun build(w: Workout): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="PulseTrainer" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""").append('\n')
        sb.append("<metadata><name>").append(esc(w.title)).append("</name><time>").append(iso.format(Date(w.start))).append("</time></metadata>\n")
        sb.append("<trk><name>").append(esc(w.title)).append("</name>\n")
        // One <trkseg> per exercise segment that has GPS.
        for (seg in w.segments) {
            val pts = w.track.filter { it.t in seg.start..seg.end }
            if (pts.isEmpty()) continue
            sb.append("<trkseg>\n")
            var hi = 0
            for (p in pts) {
                while (hi + 1 < w.hr.size && w.hr[hi + 1].t <= p.t) hi++
                val bpm = w.hr.getOrNull(hi)?.takeIf { kotlin.math.abs(it.t - p.t) < 5000 }?.bpm
                sb.append("<trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
                if (p.alt != null) sb.append("<ele>").append("%.1f".format(Locale.US, p.alt)).append("</ele>")
                sb.append("<time>").append(iso.format(Date(p.t))).append("</time>")
                if (bpm != null) sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(bpm)
                    .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
                sb.append("</trkpt>\n")
            }
            sb.append("</trkseg>\n")
        }
        sb.append("</trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Sport name in TCX (Strava maps Running/Biking; everything else imports as "Other"/workout). */
    private fun tcxSport(t: WorkoutType) = when (t) {
        WorkoutType.RUN, WorkoutType.TREADMILL -> "Running"
        WorkoutType.BIKE_OUTDOOR, WorkoutType.BIKE_INDOOR -> "Biking"
        else -> "Other"
    }

    /**
     * TCX (Garmin Training Center) — uploads to Strava, Garmin Connect, TrainingPeaks, Runalyze:
     * every heart-rate second, GPS points and cumulative distance, calories and laps (one per exercise).
     */
    fun tcx(w: Workout): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val main = w.segments.maxByOrNull { it.activeSec }?.type ?: WorkoutType.OTHER
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""").append('\n')
        sb.append("<Activities><Activity Sport=\"").append(tcxSport(main)).append("\"><Id>").append(iso.format(Date(w.start))).append("</Id>\n")
        var dist = 0.0
        var ti = 0
        var last: GeoPoint? = null
        for (seg in w.segments) {
            sb.append("<Lap StartTime=\"").append(iso.format(Date(seg.start))).append("\">")
            sb.append("<TotalTimeSeconds>").append(seg.activeSec).append("</TotalTimeSeconds>")
            sb.append("<DistanceMeters>").append("%.1f".format(Locale.US, seg.distanceM)).append("</DistanceMeters>")
            sb.append("<Calories>").append(seg.kcalTotal.toInt()).append("</Calories>")
            if (seg.avgHr > 0) sb.append("<AverageHeartRateBpm><Value>").append(seg.avgHr).append("</Value></AverageHeartRateBpm>")
            if (seg.maxHr > 0) sb.append("<MaximumHeartRateBpm><Value>").append(seg.maxHr).append("</Value></MaximumHeartRateBpm>")
            sb.append("<Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod>")
            sb.append("<Notes>").append(esc(seg.type.title)).append("</Notes><Track>\n")
            val segDistStart = dist
            val hrs = w.hr.filter { it.t in seg.start..seg.end }
            for (h in hrs) {
                while (ti < w.track.size && w.track[ti].t <= h.t) {
                    val p = w.track[ti]
                    last?.let { l -> dist += haversine(l, p) }
                    last = p; ti++
                }
                if (!seg.type.gps && seg.distanceM > 0 && seg.activeSec > 0)
                    dist = segDistStart + seg.distanceM * ((h.t - seg.start) / 1000.0 / seg.activeSec).coerceIn(0.0, 1.0)
                val p = last?.takeIf { seg.type.gps && kotlin.math.abs(it.t - h.t) < 5000 }
                sb.append("<Trackpoint><Time>").append(iso.format(Date(h.t))).append("</Time>")
                if (p != null) {
                    sb.append("<Position><LatitudeDegrees>").append(p.lat).append("</LatitudeDegrees><LongitudeDegrees>").append(p.lon).append("</LongitudeDegrees></Position>")
                    if (p.alt != null) sb.append("<AltitudeMeters>").append("%.1f".format(Locale.US, p.alt)).append("</AltitudeMeters>")
                }
                if (dist > 0) sb.append("<DistanceMeters>").append("%.1f".format(Locale.US, dist)).append("</DistanceMeters>")
                sb.append("<HeartRateBpm><Value>").append(h.bpm).append("</Value></HeartRateBpm></Trackpoint>\n")
            }
            sb.append("</Track></Lap>\n")
        }
        sb.append("<Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>PulseTrainer (Galaxy Watch)</Name></Creator>")
        sb.append("</Activity></Activities></TrainingCenterDatabase>\n")
        return sb.toString()
    }

    private fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(a.lat)) * kotlin.math.cos(Math.toRadians(b.lat)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }
}
