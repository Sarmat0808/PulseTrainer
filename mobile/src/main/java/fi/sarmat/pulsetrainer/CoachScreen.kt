package fi.sarmat.pulsetrainer

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import fi.sarmat.pulsetrainer.core.Coach
import fi.sarmat.pulsetrainer.core.Goal
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.Profile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CoachScreen(needAccess: Boolean, onGrant: () -> Unit, onRefresh: () -> Unit) {
    val profile by PhoneStore.profile.collectAsState()
    val goal by PhoneStore.goal.collectAsState()
    val days by PhoneStore.days.collectAsState()
    val workouts by PhoneStore.workouts.collectAsState()
    val tests by PhoneStore.hrv.collectAsState()
    val weights by PhoneStore.weights.collectAsState()
    val p = profile ?: Profile()

    val todayKey = days.lastOrNull()?.day
    val today = days.lastOrNull()
    val advice = remember(p, goal, days, workouts, tests, weights) {
        Coach.advise(p, goal, today, days, workouts, tests, weights)
    }
    val color = when (advice.level) { 0 -> Good; 1 -> Warn; else -> Danger }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Личный тренер", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Цель: ${goal.title}", fontSize = 13.sp, color = Dim)
        }
        if (needAccess) item {
            Section {
                Text("Подключите данные часов", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Разрешите чтение сна, пульса покоя, вариабельности пульса, шагов, SpO2 и веса из Health Connect — тренер станет точнее.", color = Dim, fontSize = 13.sp)
                Button(onClick = onGrant) { Text("Разрешить доступ") }
            }
        }
        item {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(advice.score, color)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(Coach.levelText(advice.level), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(advice.headline, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                }
                advice.plan.forEach { Bullet(it, Color.White) }
                if (advice.reasons.isNotEmpty()) {
                    Text("Почему:", color = Dim, fontSize = 12.sp)
                    advice.reasons.forEach { Bullet(it, Dim, 13) }
                }
            }
        }
        item { SleepCard(today) }
        item { StatsCard(days, p) }
        item {
            Section {
                Text("Неделя", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                advice.week.forEach { Bullet(it, Color.White) }
            }
        }
        item {
            Section {
                Text("Прогресс", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                WeightChart(weights.map { it.time to it.kg })
                advice.progress.forEach { Bullet(it, Color.White) }
            }
        }
        item {
            Section {
                Text("Питание под цель", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                advice.nutrition.forEach { Bullet(it, Color.White) }
            }
        }
        item {
            Section {
                Text("Советы тренера", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                advice.tips.forEach { Bullet(it, Color.White) }
            }
        }
        item {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Обновить данные часов") }
            Text(
                "Рекомендации основаны на общепринятых руководствах (ВОЗ, исследования по гипертрофии и интервальным тренировкам) и не заменяют врача.",
                color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (todayKey == null) item { }
    }
}

@Composable
private fun Bullet(text: String, color: Color, size: Int = 14) {
    Row {
        Text("•  ", color = Accent, fontSize = size.sp)
        Text(text, color = color, fontSize = size.sp)
    }
}

@Composable
private fun ScoreRing(score: Int, color: Color) {
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 9.dp.toPx()
            val d = size.minDimension - st
            val tl = Offset(st / 2, st / 2)
            drawArc(Color(0xFF2A3038), 135f, 270f, false, tl, Size(d, d), style = Stroke(st, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * score / 100f, false, tl, Size(d, d), style = Stroke(st, cap = StrokeCap.Round))
        }
        Text("$score", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SleepCard(today: fi.sarmat.pulsetrainer.core.DailyStats?) {
    Section {
        Text("Сон прошлой ночью", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val t = today
        val sleep = t?.sleepMin
        if (t == null || sleep == null) {
            Text("Нет данных. Спите с часами — Samsung Health передаст сон в Health Connect.", color = Dim, fontSize = 13.sp)
            return@Section
        }
        Text(Coach.fmtH(sleep), color = if (sleep >= 420) Good else if (sleep >= 360) Warn else Danger, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        val parts = listOfNotNull(
            t.deepMin?.let { Triple("Глубокий", it, Color(0xFF3B5BDB)) },
            t.remMin?.let { Triple("REM", it, Color(0xFF9775FA)) },
            t.lightMin?.let { Triple("Лёгкий", it, Color(0xFF74C0FC)) },
            t.awakeMin?.let { Triple("Пробужд.", it, Color(0xFFF2994A)) },
        )
        if (parts.isNotEmpty()) {
            val total = parts.sumOf { it.second }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                parts.forEach { (_, m, c) -> if (m > 0) Box(Modifier.weight(m.toFloat() / total).height(12.dp).background(c)) }
            }
            Text(parts.joinToString(" · ") { "${it.first} ${it.second} мин" }, color = Dim, fontSize = 13.sp)
            val deep = t.deepMin ?: 0
            if (sleep > 0) Text(
                when {
                    deep.toDouble() / sleep < 0.1 -> "Глубокого сна мало: избегайте тяжёлых тренировок и кофеина поздно вечером."
                    sleep < 420 -> "Меньше 7 часов — восстановление мышц и сердца замедляется."
                    else -> "Хороший сон — организм готов к нагрузке."
                }, color = Color.White, fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun StatsCard(days: List<fi.sarmat.pulsetrainer.core.DailyStats>, p: Profile) {
    val last7 = days.takeLast(7)
    fun <T : Number> avg(f: (fi.sarmat.pulsetrainer.core.DailyStats) -> T?): Double? =
        last7.mapNotNull(f).map { it.toDouble() }.takeIf { it.isNotEmpty() }?.average()
    val today = days.lastOrNull()
    Section {
        Text("Показатели часов", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        StatRow("Пульс покоя", today?.restHr?.toString() ?: "—", avg { it.restHr }?.let { "ср. 7 дн: %.0f".format(it) })
        StatRow("Вариабельность пульса", today?.hrvMs?.let { "%.0f мс".format(it) } ?: "—", avg { it.hrvMs }?.let { "ср. 7 дн: %.0f".format(it) })
        StatRow("Шаги сегодня", today?.steps?.toString() ?: "—", avg { it.steps }?.let { "ср. 7 дн: %.0f".format(it) })
        StatRow("Кислород (SpO2)", today?.spo2?.let { "%.0f%%".format(it) } ?: "—", null)
        StatRow("Вес", "%.1f кг".format(p.weightKg), days.lastOrNull { it.bodyFatPct != null }?.bodyFatPct?.let { "жир %.1f%%".format(it) })
        StatRow("Зоны пульса", Physiology.zoneBounds(p).let { "З2 ${it[1]}–${it[2]}" }, "макс. ${Physiology.maxHr(p)}")
    }
}

@Composable
private fun StatRow(label: String, value: String, sub: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = Dim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun WeightChart(points: List<Pair<Long, Double>>) {
    if (points.size < 2) return
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val t0 = points.first().first; val t1 = points.last().first.coerceAtLeast(t0 + 1)
        val lo = points.minOf { it.second } - 0.5; val hi = points.maxOf { it.second } + 0.5
        fun pt(t: Long, v: Double) = Offset((t - t0).toFloat() / (t1 - t0) * size.width, (size.height - (v - lo) / (hi - lo) * size.height).toFloat())
        val path = Path()
        points.forEachIndexed { i, (t, v) -> val o = pt(t, v); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, Accent, style = Stroke(4f, cap = StrokeCap.Round))
        points.forEach { (t, v) -> drawCircle(Color.White, 5f, pt(t, v)) }
    }
}

// ======================= Profile tab =======================

private val dFmt = SimpleDateFormat("d MMM yyyy", Locale("ru"))

@Composable
fun ProfileTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stored by PhoneStore.profile.collectAsState()
    val goal by PhoneStore.goal.collectAsState()
    val weights by PhoneStore.weights.collectAsState()
    var p by remember(stored) { mutableStateOf(stored ?: Profile()) }
    val changed = p != (stored ?: Profile())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Профиль", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        item {
            Section {
                Text("Цель", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Goal.entries.forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { PhoneStore.setGoal(g) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(if (g == goal) Accent else Color(0xFF2A3038)))
                        Text(g.title, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
        item {
            Section {
                Text("Данные", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Stepper("Вес, кг", "%.1f".format(p.weightKg), 0.5) { d -> p = p.copy(weightKg = (p.weightKg + d).coerceIn(35.0, 250.0)) }
                Stepper("Возраст", "${p.age}", 1.0) { d -> p = p.copy(age = (p.age + d.toInt()).coerceIn(14, 90)) }
                Stepper("Рост, см", "${p.heightCm}", 1.0) { d -> p = p.copy(heightCm = (p.heightCm + d.toInt()).coerceIn(120, 230)) }
                Row(Modifier.fillMaxWidth().clickable { p = p.copy(male = !p.male) }.padding(vertical = 6.dp)) {
                    Text("Пол", color = Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if (p.male) "мужской" else "женский", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Stepper("Пульс покоя", p.restHr?.toString() ?: "авто", 1.0) { d -> p = p.copy(restHr = ((p.restHr ?: 60) + d.toInt()).coerceIn(35, 100)) }
                Stepper("Макс. пульс", "${Physiology.maxHr(p)}" + if (p.maxHrOverride == null) " (формула)" else "", 1.0) { d ->
                    p = p.copy(maxHrOverride = (Physiology.maxHr(p) + d.toInt()).coerceIn(120, 220))
                }
                if (p.maxHrOverride != null) Text("Сбросить макс. пульс к формуле", color = Accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { p = p.copy(maxHrOverride = null) })
                val b = Physiology.zoneBounds(p)
                Text((1..5).joinToString("\n") { "${Physiology.ZONE_NAMES[it]}: ${b[it - 1]}–${b[it]}" }, color = Dim, fontSize = 13.sp)
                Button(
                    onClick = {
                        val toSave = p
                        scope.launch {
                            PhoneStore.updateProfile(ctx, toSave)
                            Toast.makeText(ctx, "Сохранено и отправлено на часы", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = changed, modifier = Modifier.fillMaxWidth()
                ) { Text(if (changed) "Сохранить и отправить на часы" else "Сохранено") }
                if (stored == null) Text("Профиль ещё не пришёл с часов — откройте PulseTrainer на часах.", color = Warn, fontSize = 12.sp)
            }
        }
        item {
            Section {
                Text("История веса", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                WeightChart(weights.map { it.time to it.kg })
                if (weights.isEmpty()) Text("Измените вес выше и сохраните — запись появится здесь и в Samsung Health.", color = Dim, fontSize = 13.sp)
                weights.takeLast(8).reversed().forEach {
                    Text("${dFmt.format(Date(it.time))}: ${"%.1f".format(it.kg)} кг", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, step: Double, onDelta: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        SmallBtn("−") { onDelta(-step) }
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp))
        SmallBtn("+") { onDelta(step) }
    }
}

@Composable
private fun SmallBtn(t: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF2A3038)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(t, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
