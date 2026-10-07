package fi.sarmat.pulsetrainer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object Colors {
    val zone = arrayOf(
        Color(0xFF7F8C8D), // below Z1
        Color(0xFF8FA3BF), // Z1 recovery
        Color(0xFF2D9CDB), // Z2 heart / fat
        Color(0xFF27AE60), // Z3 aerobic
        Color(0xFFF2994A), // Z4 threshold
        Color(0xFFEB5757), // Z5 max
    )
    val ready = Color(0xFF27AE60)
    val wait = Color(0xFFF2C94C)
    val danger = Color(0xFFEB5757)
    val action = Color(0xFF2D9CDB)
    val card = Color(0xFF1E2329)
    val dim = Color(0xFF9AA4AE)
}

/**
 * Coloured zone ring around the round display with a white marker at the current heart rate.
 * The current zone is drawn bright, the others dimmed.
 */
@Composable
fun ZoneRing(hr: Int?, bounds: IntArray, modifier: Modifier = Modifier) {
    if (bounds.size < 6 || bounds[5] <= bounds[0]) return
    Canvas(modifier.fillMaxSize()) {
        val stroke = 10.dp.toPx()
        val pad = stroke / 2 + 2.dp.toPx()
        val d = min(size.width, size.height) - 2 * pad
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        val lo = bounds[0] - (bounds[1] - bounds[0])
        val hi = bounds[5]
        fun ang(v: Int) = 135f + 270f * ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        val cur = hr?.let { fi.sarmat.pulsetrainer.core.Physiology.zoneOf(it, bounds) } ?: -1
        drawArc(Colors.zone[0].copy(alpha = if (cur == 0) 1f else 0.35f), ang(lo), ang(bounds[0]) - ang(lo) - 1.5f,
            false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Butt))
        for (z in 1..5) {
            val a0 = ang(bounds[z - 1])
            val a1 = ang(bounds[z])
            drawArc(
                Colors.zone[z].copy(alpha = if (cur == z) 1f else 0.35f), a0 + 0.75f, a1 - a0 - 1.5f,
                false, tl, Size(d, d), style = Stroke(if (cur == z) stroke * 1.3f else stroke, cap = StrokeCap.Butt)
            )
        }
        if (hr != null) {
            val a = Math.toRadians(ang(hr).toDouble())
            val r = d / 2
            val c = Offset(tl.x + r, tl.y + r)
            val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
            drawCircle(Color.Black, stroke * 0.95f, p)
            drawCircle(Color.White, stroke * 0.7f, p)
        }
    }
}

@Composable
fun RoundBtn(label: String, color: Color = Colors.card, size: Dp = 40.dp, textSize: Int = 18, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = textSize.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
fun WideBtn(label: String, color: Color, modifier: Modifier = Modifier, height: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** Horizontal bar of time spent in each zone. */
@Composable
fun ZoneBars(zoneSec: IntArray, modifier: Modifier = Modifier) {
    val total = zoneSec.sum().coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        for (z in 0..5) {
            val w = zoneSec[z].toFloat() / total
            if (w > 0f) Box(Modifier.weight(w).height(10.dp).background(Colors.zone[z]))
        }
    }
}

@Composable
fun Spacer(h: Int) = androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp).width(1.dp))
