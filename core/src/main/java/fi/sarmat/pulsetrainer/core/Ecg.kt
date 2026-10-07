package fi.sarmat.pulsetrainer.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Single-lead ECG from the Polar H10 chest strap (130 Hz, µV). Not a medical device. */
data class EcgRecord(
    val time: Long,
    val hz: Int,
    val samples: IntArray,
    val hr: Int,
    /** Beat-to-beat variability, ms. */
    val rmssd: Double,
    /** Coefficient of variation of RR intervals (rhythm regularity). */
    val rrCv: Double,
    val beats: Int,
) {
    val seconds: Int get() = samples.size / hz
}

object Ecg {

    /**
     * R-peak detection (simplified Pan-Tompkins): band-pass by differencing, squaring,
     * 150 ms moving integration, adaptive threshold, 250 ms refractory period.
     */
    fun rPeaks(x: IntArray, hz: Int): List<Int> {
        if (x.size < hz * 3) return emptyList()
        val d = DoubleArray(x.size)
        for (i in 2 until x.size - 2) {
            val v = (2.0 * x[i + 1] + x[i + 2] - x[i - 2] - 2.0 * x[i - 1]) / 8.0
            d[i] = v * v
        }
        val w = (0.15 * hz).toInt().coerceAtLeast(3)
        val m = DoubleArray(x.size)
        var acc = 0.0
        for (i in x.indices) { acc += d[i]; if (i >= w) acc -= d[i - w]; m[i] = acc / w }
        val peaks = ArrayList<Int>()
        val refr = (0.25 * hz).toInt()
        val win = 2 * hz
        var i = 0
        while (i < x.size) {
            val lo = maxOf(0, i - win); val hi = minOf(x.size, i + win)
            var mx = 0.0
            for (k in lo until hi) if (m[k] > mx) mx = m[k]
            val th = mx * 0.35
            if (m[i] > th && (peaks.isEmpty() || i - peaks.last() > refr)) {
                // local max of the integrated signal, then the raw R peak nearby
                var j = i; while (j + 1 < x.size && m[j + 1] >= m[j]) j++
                val s0 = maxOf(0, j - w - 2); val s1 = minOf(x.size - 1, j + 2)
                var r = s0
                for (k in s0..s1) if (abs(x[k]) > abs(x[r])) r = k
                if (peaks.isEmpty() || r - peaks.last() > refr) peaks += r
                i = j + refr
            } else i++
        }
        return peaks
    }

    fun analyse(time: Long, x: IntArray, hz: Int = 130): EcgRecord {
        val p = rPeaks(x, hz)
        val rr = p.zipWithNext { a, b -> (b - a) * 1000.0 / hz }.filter { it in 300.0..2000.0 }
        val hr = if (rr.isEmpty()) 0 else (60000.0 / rr.average()).roundToInt()
        var sum = 0.0
        for (k in 1 until rr.size) { val dd = rr[k] - rr[k - 1]; sum += dd * dd }
        val rmssd = if (rr.size > 2) sqrt(sum / (rr.size - 1)) else 0.0
        val mean = rr.average().takeIf { !it.isNaN() } ?: 0.0
        val sd = if (rr.size > 2) sqrt(rr.sumOf { (it - mean) * (it - mean) } / (rr.size - 1)) else 0.0
        return EcgRecord(time, hz, x, hr, rmssd, if (mean > 0) sd / mean else 0.0, p.size)
    }

    fun verdict(r: EcgRecord): Pair<String, Int> = when {
        r.beats < 10 -> "Сигнал слабый — смочите электроды ремня и повторите" to 1
        r.rrCv > 0.15 -> "Ритм неравномерный. Если повторяется в покое — покажите запись врачу" to 2
        r.hr > 100 -> "Пульс в покое выше 100 — повторите после 5 минут отдыха" to 1
        r.hr in 1..49 -> "Редкий пульс (ниже 50) — у тренированных это норма; при слабости — к врачу" to 1
        else -> "Ритм ровный, пульс в обычных пределах" to 0
    }

    fun toJson(r: EcgRecord): String = JSONObject().put("t", r.time).put("hz", r.hz).put("hr", r.hr)
        .put("rmssd", r.rmssd).put("cv", r.rrCv).put("beats", r.beats)
        .put("s", JSONArray().apply { r.samples.forEach { put(it) } }).toString()

    fun fromJson(s: String): EcgRecord? = try {
        val o = JSONObject(s)
        val a = o.getJSONArray("s")
        EcgRecord(o.getLong("t"), o.optInt("hz", 130), IntArray(a.length()) { a.getInt(it) }, o.optInt("hr"),
            o.optDouble("rmssd", 0.0), o.optDouble("cv", 0.0), o.optInt("beats"))
    } catch (_: Exception) { null }
}
