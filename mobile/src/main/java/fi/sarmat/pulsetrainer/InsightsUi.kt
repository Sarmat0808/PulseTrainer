package fi.sarmat.pulsetrainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.sarmat.pulsetrainer.core.Insights

private fun levelCol(l: Int) = when (l) { 2 -> Color(0xFFF2994A); 1 -> Warn; else -> Good }

/** «Разбор тренера»: headline, the most important findings big, the rest folded. */
@Composable
fun InsightsContent(pic: Insights.Picture, show: Int = 3, compact: Boolean = false) {
    Text(pic.headline, color = levelCol(pic.level), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val important = pic.items.filter { it.level > 0 }
    val good = pic.items.filter { it.level == 0 }
    important.take(show).forEach { InsightRow(it, compact) }
    val rest = important.drop(show)
    if (!compact && rest.isNotEmpty()) Expander("Ещё ${rest.size}") { rest.forEach { InsightRow(it, false) } }
    if (!compact && good.isNotEmpty()) Expander("Что получается хорошо (${good.size})") {
        good.forEach { Text("✓ ${it.area}: ${it.title} — ${it.detail}", color = Color.White, fontSize = 15.sp) }
    }
}

@Composable
private fun InsightRow(i: Insights.Insight, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
        Row {
            Text("● ", color = levelCol(i.level), fontSize = 17.sp)
            Text("${i.area}: ${i.title}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        if (!compact) Text(i.detail, color = Dim, fontSize = 15.sp)
        Text("→ ${i.action}", color = Accent, fontSize = 16.sp)
    }
}
