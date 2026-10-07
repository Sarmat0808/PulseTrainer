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
}
