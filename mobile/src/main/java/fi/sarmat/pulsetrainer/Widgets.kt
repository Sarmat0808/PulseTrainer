package fi.sarmat.pulsetrainer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.sarmat.pulsetrainer.core.Health
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.StressRecord
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ======================= Common tiles (large, high-contrast text) =======================

fun levelColor(level: Int) = when (level) { 0 -> Good; 1 -> Warn; else -> Danger }

@Composable
fun Tile(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
fun TileTitle(t: String) = Text(t, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

@Composable
fun Ring(frac: Float, color: Color, text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 10.dp.toPx()
            val d = size.minDimension - st
            val tl = Offset(st / 2, st / 2)
            drawArc(Color(0xFF2A3038), 135f, 270f, false, tl, Size(d, d), style = Stroke(st, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * frac.coerceIn(0f, 1f), false, tl, Size(d, d), style = Stroke(st, cap = StrokeCap.Round))
        }
        Text(text, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Bar(frac: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A3038))) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(12.dp).background(color))
    }
}

@Composable
fun Mini(v: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Dim, fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
fun VRow(label: String, value: String?, note: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Dim, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value ?: "—", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (note != null) Text(note, color = Dim, fontSize = 14.sp)
        }
    }
}

@Composable
fun BulletText(text: String, color: Color = Color.White, size: Int = 16) {
    Row {
        Text("•  ", color = Accent, fontSize = size.sp)
        Text(text, color = color, fontSize = size.sp)
    }
}

/** Small line chart (values oldest → newest). */
@Composable
fun Sparkline(values: List<Double>, color: Color, height: Int = 60) {
    if (values.size < 2) return
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val lo = values.min() - 0.5; val hi = values.max() + 0.5
        fun pt(i: Int) = Offset(i.toFloat() / (values.size - 1) * size.width, (size.height - (values[i] - lo) / (hi - lo) * size.height).toFloat())
        val path = Path()
        values.indices.forEach { i -> val o = pt(i); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, color, style = Stroke(5f, cap = StrokeCap.Round))
        values.indices.forEach { i -> drawCircle(Color.White, 6f, pt(i)) }
    }
}

// ======================= How do you feel? =======================

private val FEEL = listOf("😫" to "Разбит", "😕" to "Устал", "😐" to "Обычно", "🙂" to "Хорошо", "💪" to "Бодро")
private val SORE = listOf("Нет", "Немного", "Сильно")

/**
 * Two quick questions (like Polar Recovery Pro): the watch cannot measure how you feel,
 * and this is often the most honest signal.
 */
@Composable
fun CheckInBlock() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c by PhoneStore.checkIn.collectAsState()
    val today = c?.takeIf { it.day == java.time.LocalDate.now().toEpochDay() }
    var editing by remember { mutableStateOf(false) }
    var feel by remember(today) { mutableStateOf(today?.feel ?: 0) }
    var sore by remember(today) { mutableStateOf(today?.soreness ?: -1) }

    if (today != null && !editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Самочувствие: ${FEEL[today.feel - 1].first} ${FEEL[today.feel - 1].second.lowercase()} · мышцы: ${SORE[today.soreness].lowercase()}",
                color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("Изменить", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { editing = true }.padding(6.dp))
        }
        return
    }
    fun save() {
        if (feel in 1..5 && sore in 0..2) {
            PhoneStore.setCheckIn(feel, sore); editing = false
            scope.launch { PhoneStore.pushCoachToWatch(ctx) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Как вы себя чувствуете?", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FEEL.forEachIndexed { i, (e, l) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (feel == i + 1) Accent else Color(0xFF2A3038))
                        .clickable { feel = i + 1; save() }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(e, fontSize = 24.sp)
                    Text(l, color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Text("Мышцы болят?", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SORE.forEachIndexed { i, l ->
                Text(l, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (sore == i) Accent else Color(0xFF2A3038))
                        .clickable { sore = i; save() }.padding(vertical = 12.dp))
            }
        }
    }
}

// ======================= VO2max =======================

@Composable
fun Vo2Content(r: Health.Vo2Report) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${"%.1f".format(r.value)}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("  ${r.level.label}", color = levelColor(r.level.level), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
    }
    val ch = r.change
    val dirColor = when { ch == null -> Dim; ch >= 0.5 -> Good; ch <= -0.5 -> Danger; else -> Color.White }
    Text((if (ch != null) "%+.1f за 2 нед · ".format(ch) else "") + r.direction, color = dirColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    if (r.nextLevel != null && r.toNext != null) Text("До уровня «${r.nextLevel}»: +${"%.1f".format(r.toNext)}", color = Dim, fontSize = 15.sp)
    Sparkline(r.series, Accent)
    Expander("Как поднять МПК — советы по вашим данным") {
        r.advice.forEach { BulletText(it) }
        Text("Что это значит", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        r.explain.forEach { BulletText(it, Dim, 15) }
    }
}

// ======================= Where am I heading =======================

@Composable
fun TrendContent(list: List<Health.Trend>) {
    Text(Health.trendVerdict(list), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    list.forEach { t ->
        val (arrow, col) = when (t.better) { true -> "▲" to Good; false -> "▼" to Danger; null -> "●" to Dim }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, color = col, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(t.note, color = Dim, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(t.now, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(t.delta, color = col, fontSize = 14.sp)
            }
        }
    }
    Expander("Как читать") {
        BulletText("Сравниваются последние 2 недели с двумя неделями до них.", Dim, 15)
        BulletText("▲ зелёный — показатель движется к вашей цели, ▼ красный — от неё, ● — без заметных изменений.", Dim, 15)
        BulletText("Если ухудшаются сразу пульс покоя и сон — это первый признак перегрузки: снизьте объём на неделю.", Dim, 15)
    }
}

// ======================= Stress =======================

private val dtFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale("ru"))

@Composable
fun StressContent(list: List<StressRecord>) {
    val last = list.lastOrNull()
    if (last == null) {
        Text("Нет замеров. На часах: «Стресс» → сидя спокойно 1 минуту. С ремнём H10 точнее.", color = Dim, fontSize = 16.sp)
        return
    }
    val lv = Physiology.stressLevel(last.score)
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${last.score}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("  ${Physiology.stressLabel(last.score)}", color = levelColor(lv), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
    }
    Text(dtFmt.format(Date(last.time)) + " · пульс ${last.hr}" + if (last.rmssd > 0) " · ВСР ${last.rmssd.roundToInt()} мс" else " · по пульсу (часы)",
        color = Dim, fontSize = 14.sp)
    Text(Physiology.stressAdvice(last.score), color = Color.White, fontSize = 16.sp)
    Expander("История и как снизить стресс") {
        list.takeLast(8).reversed().forEach {
            Text("${dtFmt.format(Date(it.time))}: ${it.score} · ${Physiology.stressLabel(it.score).lowercase()}", color = Color.White, fontSize = 15.sp)
        }
        BulletText("Медленное дыхание 6 раз в минуту (вдох 4 с, выдох 6 с) 2–5 минут — на часах есть «Дыхание».", Dim, 15)
        BulletText("Прогулка 15–20 мин на улице, особенно днём на свету.", Dim, 15)
        BulletText("Высокий стресс несколько дней подряд вместе с плохим сном — повод снизить нагрузку.", Dim, 15)
        BulletText("Замер сравнивает вариабельность пульса с вашей утренней нормой. Измеряйте сидя, молча, в одно и то же время.", Dim, 15)
    }
}

// ======================= Sport pictograms =======================

/** One-colour sport pictogram (Material Icons, Round) in an optional round badge. */
@Composable
fun SportIcon(t: fi.sarmat.pulsetrainer.core.WorkoutType, size: androidx.compose.ui.unit.Dp, color: Color = Color.White, badge: Color? = null) {
    val vec = remember(t) {
        androidx.compose.ui.graphics.vector.ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            (fi.sarmat.pulsetrainer.core.SportIcons.PATHS[fi.sarmat.pulsetrainer.core.SportIcons.of(t)] ?: emptyList()).forEach {
                addPath(pathData = androidx.compose.ui.graphics.vector.addPathNodes(it), fill = androidx.compose.ui.graphics.SolidColor(Color.White))
            }
        }.build()
    }
    val img = @Composable { s: androidx.compose.ui.unit.Dp ->
        androidx.compose.foundation.Image(
            androidx.compose.ui.graphics.vector.rememberVectorPainter(vec), contentDescription = t.title,
            modifier = Modifier.size(s), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color)
        )
    }
    if (badge == null) img(size)
    else Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(badge), contentAlignment = Alignment.Center) {
        img(size * 0.6f)
    }
}

/** Workout colour for icons and badges (see SportIcons.color). */
fun sportColor(t: fi.sarmat.pulsetrainer.core.WorkoutType) = Color(fi.sarmat.pulsetrainer.core.SportIcons.color(t))
