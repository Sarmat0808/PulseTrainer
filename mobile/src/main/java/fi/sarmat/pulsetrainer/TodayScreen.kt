package fi.sarmat.pulsetrainer

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.sarmat.pulsetrainer.core.Coach
import fi.sarmat.pulsetrainer.core.DailyStats
import fi.sarmat.pulsetrainer.core.Health
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.fmtDuration
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TODAY_CARDS = linkedMapOf(
    "ready" to "Готовность и план дня",
    "activity" to "Активность и шаги",
    "sleep" to "Сон",
    "heart" to "Укрепление сердца (неделя)",
    "load" to "Кардионагрузка",
    "hr" to "Пульс сегодня",
    "vitals" to "Жизненные показатели",
    "water" to "Вода",
    "body" to "Состав тела",
    "fitness" to "Физическая форма (VO₂max)",
    "workouts" to "Последние тренировки",
)

private val hm = SimpleDateFormat("HH:mm", Locale("ru"))

@Composable
fun TodayScreen(needAccess: Boolean, onGrant: () -> Unit, onRefresh: () -> Unit, openTab: (Int) -> Unit, openWorkout: (String) -> Unit) {
    val profile by PhoneStore.profile.collectAsState()
    val goal by PhoneStore.goal.collectAsState()
    val days by PhoneStore.days.collectAsState()
    val workouts by PhoneStore.workouts.collectAsState()
    val tests by PhoneStore.hrv.collectAsState()
    val weights by PhoneStore.weights.collectAsState()
    val body by PhoneStore.body.collectAsState()
    val foodV by FoodStore.version.collectAsState()
    val p = profile ?: Profile()
    val today = days.lastOrNull()
    val yesterdayOrToday = days.lastOrNull { it.sleepMin != null }
    val advice = remember(p, goal, days, workouts, tests, weights, body) { Coach.advise(p, goal, today, days, workouts, tests, weights, body) }
    val cards = rememberCards("today", TODAY_CARDS.keys.toList())

    LaunchedEffect(Unit) { onRefresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Сегодня", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru"))), fontSize = 14.sp, color = Dim)
                }
                ArrangeButton("today", TODAY_CARDS)
            }
        }
        if (needAccess) item {
            Section {
                Text("Подключите данные часов", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Разрешите чтение из Health Connect — сон, шаги, пульс, SpO2, вес и другое появятся здесь.", color = Dim, fontSize = 14.sp)
                Button(onClick = onGrant) { Text("Разрешить доступ") }
            }
        }
        cards.forEach { id ->
            item(key = id) {
                when (id) {
                    "ready" -> Tile(onClick = { openTab(1) }) {
                        val c = when (advice.level) { 0 -> Good; 1 -> Warn; else -> Danger }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ring(advice.score / 100f, c, "${advice.score}", Modifier.size(84.dp))
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text("Готовность", color = Dim, fontSize = 14.sp)
                                Text(Coach.levelText(advice.level), color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(advice.headline, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("Подробнее во вкладке «Тренер» →", color = Accent, fontSize = 13.sp)
                            }
                        }
                    }
                    "activity" -> Tile {
                        TileTitle("Активность")
                        val steps = today?.steps ?: 0L
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%,d".format(steps).replace(',', ' '), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                            Text("  / 10 000 шагов", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Bar(steps / 10000f, Good)
                        Row(Modifier.fillMaxWidth()) {
                            Mini(today?.activeKcal?.roundToInt()?.toString() ?: "—", "акт. ккал", Modifier.weight(1f))
                            Mini(today?.distanceM?.let { "%.1f".format(it / 1000) } ?: "—", "км", Modifier.weight(1f))
                            Mini(today?.floors?.roundToInt()?.toString() ?: "—", "этажей", Modifier.weight(1f))
                        }
                    }
                    "sleep" -> Tile {
                        TileTitle("Сон")
                        val sc = Health.sleepScore(yesterdayOrToday)
                        if (sc == null || yesterdayOrToday == null) Text("Нет данных — спите с часами.", color = Dim, fontSize = 14.sp)
                        else {
                            val d = yesterdayOrToday
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Ring(sc.value / 100f, levelColor(sc.level), "${sc.value}", Modifier.size(80.dp))
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text(sc.label, color = levelColor(sc.level), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(Coach.fmtH(d.sleepMin ?: 0), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    val a = d.sleepStart; val b = d.sleepEnd
                                    if (a != null && b != null) Text("${hm.format(Date(a))} – ${hm.format(Date(b))}", color = Dim, fontSize = 14.sp)
                                }
                            }
                            val parts = listOfNotNull(
                                d.deepMin?.let { Triple("Глубокий", it, Color(0xFF3B5BDB)) },
                                d.remMin?.let { Triple("REM", it, Color(0xFF9775FA)) },
                                d.lightMin?.let { Triple("Лёгкий", it, Color(0xFF74C0FC)) },
                                d.awakeMin?.let { Triple("Пробужд.", it, Color(0xFFF2994A)) },
                            )
                            if (parts.isNotEmpty()) {
                                val total = parts.sumOf { it.second }.coerceAtLeast(1)
                                Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                                    parts.forEach { (_, m, c) -> if (m > 0) Box(Modifier.weight(m.toFloat() / total).height(12.dp).background(c)) }
                                }
                                Text(parts.joinToString(" · ") { "${it.first} ${it.second} мин" }, color = Dim, fontSize = 13.sp)
                            }
                        }
                    }
                    "heart" -> Tile {
                        TileTitle("Укрепление сердца")
                        val other = days.filter { it.day > System.currentTimeMillis() - 7 * 86400_000L }.sumOf { it.otherWorkoutMin ?: 0 }
                        val m = Health.heartMinutes(workouts, other)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ring(m / 150f, if (m >= 150) Good else Accent, "$m", Modifier.size(80.dp))
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(if (m >= 150) "Цель недели выполнена ✓" else "Ещё ${150 - m} мин до цели", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("За 7 дней. Зоны 2–3 — минута за минуту, зоны 4–5 — вдвойне (как у ВОЗ).", color = Dim, fontSize = 13.sp)
                            }
                        }
                    }
                    "load" -> Tile {
                        TileTitle("Кардионагрузка")
                        val (week, avg, label) = Health.cardioLoad(workouts)
                        Text(label, color = when (label) { "В норме" -> Good; "Слишком высокая" -> Danger; "Немного выше" -> Warn; else -> Accent },
                            fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Нагрузка за 7 дней: $week (обычно ~$avg в неделю)", color = Dim, fontSize = 14.sp)
                        DayBars(workouts)
                    }
                    "hr" -> Tile {
                        TileTitle("Пульс сегодня")
                        Row(Modifier.fillMaxWidth()) {
                            Mini((today?.restHr ?: p.restHr)?.toString() ?: "—", "покой", Modifier.weight(1f))
                            Mini(today?.hrMin?.toString() ?: "—", "мин.", Modifier.weight(1f))
                            Mini(today?.hrAvg?.toString() ?: "—", "средн.", Modifier.weight(1f))
                            Mini(today?.hrMax?.toString() ?: "—", "макс.", Modifier.weight(1f))
                        }
                    }
                    "vitals" -> Tile {
                        TileTitle("Жизненные показатели")
                        val lastSpo2 = days.lastOrNull { it.spo2 != null }?.spo2
                        val lastResp = days.lastOrNull { it.respRate != null }?.respRate
                        val lastBp = days.lastOrNull { it.bpSys != null }
                        val lastHrv = days.lastOrNull { it.hrvMs != null }?.hrvMs
                        val test = tests.lastOrNull()
                        VRow("Кислород (SpO₂)", lastSpo2?.let { "%.0f%%".format(it) }, lastSpo2?.let { Health.spo2Label(it) })
                        VRow("Дыхание во сне", lastResp?.let { "%.0f /мин".format(it) }, null)
                        VRow("Давление", lastBp?.let { "${it.bpSys}/${it.bpDia}" }, lastBp?.let { Health.bpLabel(it.bpSys!!, it.bpDia!!) })
                        VRow("Вариабельность пульса", lastHrv?.let { "%.0f мс".format(it) } ?: test?.takeIf { it.rmssd > 0 }?.let { "${it.rmssd.roundToInt()} мс (H10)" }, null)
                    }
                    "water" -> Tile {
                        TileTitle("Вода")
                        val target = FoodStore.targets(LocalDate.now()).waterMl
                        val ours = remember(foodV) { FoodStore.entries(LocalDate.now()).sumOf { it.drinkMl } }
                        val sh = today?.hydrationMl ?: 0
                        val total = maxOf(ours, sh)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("$total", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                            Text("  / $target мл", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Bar(total / target.toFloat(), Color(0xFF56CCF2))
                        Text("PulseTrainer: $ours мл · Samsung Health: $sh мл. Добавить — во вкладке «Еда».", color = Dim, fontSize = 13.sp)
                    }
                    "body" -> Tile(onClick = { openTab(4) }) {
                        TileTitle("Состав тела")
                        val lastFat = days.lastOrNull { it.bodyFatPct != null }?.bodyFatPct ?: body.lastOrNull { it.bodyFatPct != null }?.bodyFatPct
                        val lean = days.lastOrNull { it.leanKg != null }?.leanKg
                        val bmi = Health.bmi(p)
                        VRow("Вес", "%.1f кг".format(p.weightKg), weights.takeIf { it.size >= 2 }?.let { val d = it.last().kg - it[it.size - 2].kg; "%+.1f кг".format(d) })
                        VRow("Жир", lastFat?.let { "%.1f%%".format(it) }, null)
                        VRow("Безжировая масса", lean?.let { "%.1f кг".format(it) }, null)
                        VRow("ИМТ", "%.1f".format(bmi), Health.bmiLabel(bmi))
                        val waist = body.lastOrNull { it.waistCm != null }?.waistCm
                        if (waist != null) VRow("Талия", "%.1f см".format(waist), null)
                        Text("Замеры и вес — во вкладке «Профиль» →", color = Accent, fontSize = 13.sp)
                    }
                    "fitness" -> Tile {
                        TileTitle("Физическая форма")
                        val measured = days.lastOrNull { it.vo2max != null }?.vo2max
                        val v = measured ?: Health.vo2Estimate(p, today?.restHr)
                        if (v == null) Text("Нужен пульс покоя — сделайте утренний тест на часах.", color = Dim, fontSize = 14.sp)
                        else {
                            val lv = Health.vo2Level(p, v)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${lv.value}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                Text("  VO₂max · ${lv.label}", color = levelColor(lv.level), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            Text(if (measured != null) "Измерено часами (бег на улице)." else "Оценка по пульсу покоя и макс. пульсу. Растёт от зоны 2 и интервалов 4×4.",
                                color = Dim, fontSize = 13.sp)
                        }
                    }
                    "workouts" -> Tile {
                        TileTitle("Последние тренировки")
                        if (workouts.isEmpty()) Text("Пока нет.", color = Dim, fontSize = 14.sp)
                        workouts.take(3).forEach { w ->
                            Column(Modifier.fillMaxWidth().clickable { openWorkout(w.id) }.padding(vertical = 4.dp)) {
                                Text(w.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale("ru")).format(Date(w.start)) +
                                    " · ${fmtDuration(w.activeSec)} · ♥ ${w.avgHr} · ${w.kcalTotal.roundToInt()} ккал", color = Dim, fontSize = 13.sp)
                            }
                        }
                        OutlinedButton(onClick = { openTab(3) }, modifier = Modifier.fillMaxWidth()) { Text("Все тренировки") }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Обновить данные часов") }
            Text("Данные часов приходят через Samsung Health → Health Connect. Samsung Health должен оставаться установленным, но открывать его не нужно.",
                color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun levelColor(level: Int) = when (level) { 0 -> Good; 1 -> Warn; else -> Danger }

@Composable
private fun Tile(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
private fun TileTitle(t: String) = Text(t, color = Dim, fontSize = 15.sp)

@Composable
private fun Ring(frac: Float, color: Color, text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 9.dp.toPx()
            val d = size.minDimension - st
            val tl = Offset(st / 2, st / 2)
            drawArc(Color(0xFF2A3038), 135f, 270f, false, tl, Size(d, d), style = Stroke(st, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * frac.coerceIn(0f, 1f), false, tl, Size(d, d), style = Stroke(st, cap = StrokeCap.Round))
        }
        Text(text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Bar(frac: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A3038))) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(10.dp).background(color))
    }
}

@Composable
private fun Mini(v: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Dim, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VRow(label: String, value: String?, note: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value ?: "—", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (note != null) Text(note, color = Dim, fontSize = 12.sp)
        }
    }
}

/** Training load per day for the last 7 days. */
@Composable
private fun DayBars(workouts: List<fi.sarmat.pulsetrainer.core.Workout>) {
    val today = LocalDate.now()
    val z = java.time.ZoneId.systemDefault()
    val vals = (6 downTo 0).map { k ->
        val d = today.minusDays(k.toLong())
        d to workouts.filter { java.time.Instant.ofEpochMilli(it.start).atZone(z).toLocalDate() == d }.sumOf { it.trimp }
    }
    val max = vals.maxOf { it.second }.coerceAtLeast(30.0)
    Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        vals.forEach { (d, v) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 18.dp, height = (60 * (v / max)).dp.coerceAtLeast(4.dp)).clip(RoundedCornerShape(4.dp))
                    .background(if (v > 0) Accent else Color(0xFF2A3038)))
                Text(d.format(DateTimeFormatter.ofPattern("EE", Locale("ru"))), color = Dim, fontSize = 12.sp)
            }
        }
    }
}
