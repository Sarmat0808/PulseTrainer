package fi.sarmat.pulsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.sarmat.pulsetrainer.core.Coach
import fi.sarmat.pulsetrainer.core.Health
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.fmtDuration
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TODAY_CARDS = linkedMapOf(
    "ready" to "Готовность, самочувствие и план",
    "recovery" to "Время восстановления",
    "energy" to "Энергия (как Body Battery)",
    "sleep" to "Сон",
    "activity" to "Активность и шаги",
    "trend" to "Куда я двигаюсь (прогресс)",
    "fitness" to "Физическая форма (VO₂max)",
    "heart" to "Укрепление сердца (неделя)",
    "load" to "Нагрузка за неделю",
    "stress" to "Стресс",
    "hr" to "Пульс сегодня",
    "vitals" to "Жизненные показатели",
    "water" to "Вода",
    "body" to "Состав тела",
    "workouts" to "Последние тренировки",
)
private val TODAY_MORE = setOf("heart", "load", "hr", "vitals", "water", "body", "workouts")

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
    val ext by PhoneStore.ext.collectAsState()
    val passive by PhoneStore.passive.collectAsState()
    val stress by PhoneStore.stress.collectAsState()
    val check by PhoneStore.checkIn.collectAsState()
    val hrRecent by PhoneStore.hrRecent.collectAsState()
    var minuteTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val foodV by FoodStore.version.collectAsState()
    val p = profile ?: Profile()
    val watchHr by PhoneStore.watchHr.collectAsState()
    val energy = remember(p, days, workouts, tests, check, passive, hrRecent, watchHr, minuteTick) { PhoneStore.energy() }
    val today = days.lastOrNull()
    val lastNight = days.lastOrNull { it.sleepMin != null }
    val advice = remember(p, goal, days, workouts, tests, weights, body, ext, check, passive) { PhoneStore.advise() }
    val vo2 = remember(p, goal, days, workouts, ext, passive) { Health.vo2Report(p, goal, days, workouts, ext, passive) }
    val trends = remember(p, goal, days, workouts, ext, passive, weights) { Health.trends(p, goal, days, workouts, ext, passive, weights) }
    val layout = rememberCardLayout("today", TODAY_CARDS.keys.toList(), TODAY_MORE)

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val stressList by PhoneStore.stress.collectAsState()
    val recovery = remember(workouts, ext, days, passive, check, stressList, minuteTick) { PhoneStore.recovery() }
    LaunchedEffect(Unit) {
        onRefresh()
        // Live: every 10 minutes ask the watch for fresh pulse and re-read the last hours from Health Connect.
        while (true) {
            PhoneStore.sendToWatch(ctx, fi.sarmat.pulsetrainer.core.Protocol.CMD_SYNC)
            PhoneStore.refreshRecent(ctx)
            minuteTick++
            kotlinx.coroutines.delay(10 * 60_000L)
        }
    }

    @Composable
    fun card(id: String) {
        when (id) {
            "ready" -> Tile {
                val c = levelColor(advice.level)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { openTab(1) }) {
                    Ring(advice.score / 100f, c, "${advice.score}", Modifier.size(90.dp))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(Coach.levelText(advice), color = c, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(advice.headline, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                advice.plan.take(2).forEach { BulletText(it) }
                CheckInBlock()
                Text("План, причины и чего избегать — вкладка «Тренер» →", color = Accent, fontSize = 15.sp,
                    modifier = Modifier.clickable { openTab(1) }.padding(top = 2.dp))
            }
            "recovery" -> Tile {
                TileTitle("Восстановление")
                val c = levelColor(recovery.level)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val frac = if (recovery.hoursTotal > 0) 1f - recovery.hoursLeft / recovery.hoursTotal.toFloat() else 1f
                    Ring(frac, c, if (recovery.hoursLeft == 0) "✓" else "${recovery.hoursLeft} ч", Modifier.size(84.dp))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(recovery.label, color = c, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (recovery.hoursLeft > 0) Text("Готов к тяжёлой тренировке: " +
                            SimpleDateFormat("EE HH:mm", Locale("ru")).format(Date(recovery.readyAt)), color = Color.White, fontSize = 15.sp)
                    }
                }
                Expander("Как считается") {
                    recovery.factors.forEach { BulletText(it, Color.White, 15) }
                    BulletText("Каждая тренировка добавляет время по нагрузке на сердце (пульс) и объёму силовой; незавершённое восстановление частично переносится.", Dim, 15)
                    BulletText("Сон, ночной пульс, стресс и самочувствие ускоряют или замедляют восстановление — как в Garmin.", Dim, 15)
                }
            }
            "energy" -> Tile {
                TileTitle("Энергия")
                val c = levelColor(energy.level)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(energy.now / 100f, c, "${energy.now}", Modifier.size(84.dp))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(energy.label, color = c, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Утром было ${energy.morning}", color = Dim, fontSize = 15.sp)
                    }
                }
                EnergyChart(energy.series)
                Text(energy.advice, color = Color.White, fontSize = 16.sp)
                Expander("Что зарядило и что потратило") {
                    energy.parts.forEach { pt ->
                        VRow(pt.label, (if (pt.points > 0 && pt.label != "Утренний заряд" && !pt.label.startsWith("Сон")) "+" else "") + "${pt.points}", null)
                    }
                    energy.explain.forEach { BulletText(it, Dim, 15) }
                }
            }
            "sleep" -> Tile {
                TileTitle("Сон")
                val sc = Health.sleepScore(lastNight)
                if (sc == null || lastNight == null) Text("Нет данных — спите с часами.", color = Dim, fontSize = 16.sp)
                else {
                    val d = lastNight
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ring(sc.value / 100f, levelColor(sc.level), "${sc.value}", Modifier.size(84.dp))
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(sc.label, color = levelColor(sc.level), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(Coach.fmtH(d.sleepMin ?: 0), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            val a = d.sleepStart; val b = d.sleepEnd
                            if (a != null && b != null) Text("${hm.format(Date(a))} – ${hm.format(Date(b))}", color = Dim, fontSize = 15.sp)
                        }
                    }
                    val parts = listOfNotNull(
                        d.deepMin?.let { Triple("Глубокий", it, Color(0xFF4C6EF5)) },
                        d.remMin?.let { Triple("REM", it, Color(0xFF9775FA)) },
                        d.lightMin?.let { Triple("Лёгкий", it, Color(0xFF74C0FC)) },
                        d.awakeMin?.let { Triple("Пробужд.", it, Color(0xFFF2994A)) },
                    )
                    if (parts.isNotEmpty()) {
                        val total = parts.sumOf { it.second }.coerceAtLeast(1)
                        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                            parts.forEach { (_, m, c) -> if (m > 0) Box(Modifier.weight(m.toFloat() / total).height(14.dp).background(c)) }
                        }
                        Expander {
                            parts.forEach { (n, m, c) -> VRow(n, "$m мин", "${(m * 100 / total)}%") }
                            val deep = d.deepMin ?: 0; val sleep = d.sleepMin ?: 0
                            BulletText(when {
                                sleep > 0 && deep.toDouble() / sleep < 0.12 -> "Глубокого сна мало: без кофеина после 14:00, тяжёлой тренировки и еды за 3 ч до сна."
                                sleep < 420 -> "Меньше 7 часов — восстановление мышц и сердца замедляется. Ложитесь на 30–45 мин раньше."
                                else -> "Хороший сон — организм готов к нагрузке."
                            }, Color.White, 15)
                            val night = passive.lastOrNull()?.takeIf { it.restHr != null }
                            if (night != null) VRow("Пульс покоя ночью (часы)", "${night.restHr}", night.nightAvg?.let { "средний $it" })
                        }
                    }
                }
            }
            "activity" -> Tile {
                TileTitle("Активность")
                val steps = today?.steps ?: passive.lastOrNull()?.takeIf { it.day == today?.day }?.steps ?: 0L
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%,d".format(steps).replace(',', ' '), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("  / 10 000 шагов", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Bar(steps / 10000f, Good)
                Row(Modifier.fillMaxWidth()) {
                    Mini(today?.activeKcal?.roundToInt()?.toString() ?: "—", "акт. ккал", Modifier.weight(1f))
                    Mini(today?.distanceM?.let { "%.1f".format(it / 1000) } ?: "—", "км", Modifier.weight(1f))
                    Mini((today?.floors?.roundToInt() ?: passive.lastOrNull()?.takeIf { it.day == today?.day }?.floors)?.toString() ?: "—", "этажей", Modifier.weight(1f))
                }
            }
            "trend" -> Tile {
                TileTitle("Куда я двигаюсь")
                TrendContent(trends)
            }
            "fitness" -> Tile {
                TileTitle("Физическая форма · VO₂max")
                if (vo2 == null) Text("Нужен пульс покоя — носите часы ночью или сделайте утренний тест.", color = Dim, fontSize = 16.sp)
                else Vo2Content(vo2)
            }
            "heart" -> Tile {
                TileTitle("Укрепление сердца")
                val other = days.filter { it.day > System.currentTimeMillis() - 7 * 86400_000L }.sumOf { it.otherWorkoutMin ?: 0 }
                val m = Health.heartMinutes(workouts, other, ext = ext)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(m / 150f, if (m >= 150) Good else Accent, "$m", Modifier.size(84.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(if (m >= 150) "Цель недели выполнена ✓" else "Ещё ${150 - m} мин до цели", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("За 7 дней, включая тренировки из Samsung Health. Зоны 2–3 — минута за минуту, зоны 4–5 — вдвойне (ВОЗ).", color = Dim, fontSize = 14.sp)
                    }
                }
            }
            "load" -> Tile {
                TileTitle("Нагрузка за неделю")
                val (week, avg, label) = Health.cardioLoad(workouts, ext = ext)
                Text(label, color = when (label) { "В норме" -> Good; "Слишком высокая" -> Danger; "Немного выше" -> Warn; else -> Accent },
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("За 7 дней: $week (обычно ~$avg в неделю)", color = Dim, fontSize = 15.sp)
                if (avg >= 30) {
                    val ratio = week.toDouble() / avg
                    Text("Острая/хроническая (ACWR): ×${"%.1f".format(ratio)} · безопасно 0,8–1,3", fontSize = 15.sp,
                        color = when { ratio > 1.5 -> Danger; ratio > 1.3 -> Warn; ratio < 0.8 -> Accent; else -> Good })
                }
                DayBars()
                val extWeek = ext.filter { it.start > System.currentTimeMillis() - 7 * 86400_000L }
                if (extWeek.isNotEmpty()) Expander("Тренировки из Samsung Health (${extWeek.size})") {
                    extWeek.reversed().forEach { e ->
                        VRow(e.title + " · " + SimpleDateFormat("EE d", Locale("ru")).format(Date(e.start)),
                            "${e.minutes} мин", if (e.estimated) "нагрузка ~${e.trimp.roundToInt()} (без пульса)" else "♥ ${e.avgHr} · нагрузка ${e.trimp.roundToInt()}")
                    }
                }
            }
            "stress" -> Tile {
                TileTitle("Стресс")
                StressContent(stress)
                StressDayChart(hrRecent, workouts, ext, p)
                OutlinedButton(onClick = {
                    scope.launch {
                        val ok = PhoneStore.sendToWatch(ctx, fi.sarmat.pulsetrainer.core.Protocol.CMD_OPEN + "stress")
                        android.widget.Toast.makeText(ctx, if (ok) "Откройте часы — замер стресса" else "Часы не на связи", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Измерить стресс на часах", fontSize = 16.sp) }
            }
            "hr" -> Tile {
                TileTitle("Пульс сегодня")
                val night = passive.lastOrNull()
                Row(Modifier.fillMaxWidth()) {
                    Mini((today?.restHr ?: night?.restHr ?: p.restHr)?.toString() ?: "—", "покой", Modifier.weight(1f))
                    Mini((today?.hrMin ?: night?.hrMin)?.toString() ?: "—", "мин.", Modifier.weight(1f))
                    Mini((today?.hrAvg ?: night?.dayAvg)?.toString() ?: "—", "средн.", Modifier.weight(1f))
                    Mini((today?.hrMax ?: night?.hrMax)?.toString() ?: "—", "макс.", Modifier.weight(1f))
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
                EcgBlock()
            }
            "water" -> Tile {
                TileTitle("Вода")
                val target = FoodStore.targets(LocalDate.now()).waterMl
                val ours = remember(foodV) { FoodStore.entries(LocalDate.now()).sumOf { it.drinkMl } }
                val sh = today?.hydrationMl ?: 0
                val total = maxOf(ours, sh)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$total", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("  / $target мл", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Bar(total / target.toFloat(), Color(0xFF56CCF2))
                Text("Добавить — во вкладке «Еда».", color = Dim, fontSize = 14.sp)
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
                Text("Замеры и вес — во вкладке «Профиль» →", color = Accent, fontSize = 15.sp)
            }
            "workouts" -> Tile {
                TileTitle("Последние тренировки")
                if (workouts.isEmpty()) Text("Пока нет.", color = Dim, fontSize = 16.sp)
                workouts.take(3).forEach { w ->
                    Column(Modifier.fillMaxWidth().clickable { openWorkout(w.id) }.padding(vertical = 4.dp)) {
                        Text(w.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale("ru")).format(Date(w.start)) +
                            " · ${fmtDuration(w.activeSec)} · ♥ ${w.avgHr} · ${w.kcalTotal.roundToInt()} ккал", color = Dim, fontSize = 14.sp)
                    }
                }
                OutlinedButton(onClick = { openTab(3) }, modifier = Modifier.fillMaxWidth()) { Text("Все тренировки", fontSize = 16.sp) }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Сегодня", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru"))), fontSize = 16.sp, color = Dim)
                }
                ArrangeButton("today", TODAY_CARDS, TODAY_MORE)
            }
        }
        if (needAccess) item {
            Section {
                Text("Подключите данные часов", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Разрешите чтение из Health Connect — сон, шаги, пульс, тренировки, вес появятся здесь.", color = Dim, fontSize = 16.sp)
                Button(onClick = onGrant) { Text("Разрешить доступ", fontSize = 16.sp) }
            }
        }
        layout.top.forEach { id -> item(key = id) { card(id) } }
        item(key = "more") {
            MoreBlock(layout.more.size) {
                layout.more.forEach { id -> card(id) }
            }
        }
        item {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Обновить данные часов", fontSize = 16.sp) }
        }
    }
}

/** Training load per day for the last 7 days (PulseTrainer + other apps). */
@Composable
private fun DayBars() {
    val workouts by PhoneStore.workouts.collectAsState()
    val ext by PhoneStore.ext.collectAsState()
    val today = LocalDate.now()
    val z = java.time.ZoneId.systemDefault()
    val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val vals = Health.dailyLoad(workouts, ext, dates.map { it.atStartOfDay(z).toInstant().toEpochMilli() })
    val max = (vals.maxOrNull() ?: 0.0).coerceAtLeast(30.0)
    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        dates.forEachIndexed { i, d ->
            val v = vals[i]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 20.dp, height = (62 * (v / max)).dp.coerceAtLeast(4.dp)).clip(RoundedCornerShape(4.dp))
                    .background(if (v > 0) Accent else Color(0xFF2A3038)))
                Text(d.format(DateTimeFormatter.ofPattern("EE", Locale("ru"))), color = Dim, fontSize = 13.sp)
            }
        }
    }
}

/** Energy through the day (0–100). */
@Composable
private fun EnergyChart(series: List<Pair<Long, Int>>) {
    if (series.size < 2) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val t0 = series.first().first; val t1 = series.last().first.coerceAtLeast(t0 + 1)
        fun pt(t: Long, v: Int) = androidx.compose.ui.geometry.Offset((t - t0).toFloat() / (t1 - t0) * size.width, size.height * (1 - v / 100f))
        val path = androidx.compose.ui.graphics.Path()
        series.forEachIndexed { i, (t, v) -> val o = pt(t, v); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        val fill = androidx.compose.ui.graphics.Path().apply {
            addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(fill, Accent.copy(alpha = 0.18f))
        drawPath(path, Accent, style = androidx.compose.ui.graphics.drawscope.Stroke(5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/**
 * Stress through the day from the pulse: pulse above your resting level while you are not
 * in a workout (workouts are excluded). 30-minute bars, 0–100.
 */
@Composable
private fun StressDayChart(samples: List<Pair<Long, Int>>, workouts: List<fi.sarmat.pulsetrainer.core.Workout>,
                           ext: List<fi.sarmat.pulsetrainer.core.ExtWorkout>, p: Profile) {
    val now = System.currentTimeMillis()
    val dayStart = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val rest = p.restHr ?: 60
    val reserve = (fi.sarmat.pulsetrainer.core.Physiology.maxHr(p) - rest).coerceAtLeast(40)
    val busy = workouts.map { it.start..it.end } + ext.map { it.start..it.end }
    val bars = (0 until 48).map { i ->
        val a = dayStart + i * 1800_000L; val b = a + 1800_000L
        if (a > now) null else {
            val v = samples.filter { it.first in a until b && busy.none { r -> it.first in r } }.map { it.second }
            if (v.size < 3) null else ((v.sorted()[v.size / 2] - rest - 3).toDouble() / (0.30 * reserve) * 100).toInt().coerceIn(0, 100)
        }
    }
    if (bars.count { it != null } < 4) return
    Text("Напряжение за день (по пульсу, без тренировок)", color = Dim, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        bars.forEach { v ->
            val c = when { v == null -> Color(0xFF2A3038); v <= 25 -> Color(0xFF2D9CDB); v <= 50 -> Good; v <= 75 -> Warn; else -> Danger }
            Box(Modifier.weight(1f).height((if (v == null) 3 else (6 + v * 0.54).toInt()).dp).background(c))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("0", "6", "12", "18", "24").forEachIndexed { i, t ->
            Text(t, color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = if (i == 4) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start)
        }
    }
}

/** ECG recordings from the Polar H10: last result, trace, history; start a new one on the watch. */
@Composable
private fun EcgBlock() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val list by PhoneStore.ecgs.collectAsState()
    var show by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<fi.sarmat.pulsetrainer.core.EcgRecord?>(null) }
    val last = list.lastOrNull()
    Text("ЭКГ (Polar H10)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    if (last != null) {
        val (v, lv) = fi.sarmat.pulsetrainer.core.Ecg.verdict(last)
        Column(Modifier.fillMaxWidth().clickable { show = last }) {
            Text(SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(last.time)) + " · ♥ ${last.hr} · ВСР ${last.rmssd.roundToInt()} мс",
                color = Color.White, fontSize = 15.sp)
            Text(v, color = levelColor(lv), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            EcgTrace(last, seconds = 6, height = 80)
            Text("Нажмите, чтобы открыть всю запись", color = Accent, fontSize = 14.sp)
        }
    } else Text("Пока нет записей. Наденьте H10 и запишите ЭКГ на часах (30 с).", color = Dim, fontSize = 15.sp)
    OutlinedButton(onClick = {
        scope.launch {
            val ok = PhoneStore.sendToWatch(ctx, fi.sarmat.pulsetrainer.core.Protocol.CMD_OPEN + "ecg")
            android.widget.Toast.makeText(ctx, if (ok) "Откройте часы — запись ЭКГ" else "Часы не на связи", android.widget.Toast.LENGTH_SHORT).show()
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Записать ЭКГ на часах", fontSize = 16.sp) }
    if (list.size > 1) Expander("Все записи (${list.size})") {
        list.reversed().forEach { r ->
            Text(SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(r.time)) + " · ♥ ${r.hr} · " + fi.sarmat.pulsetrainer.core.Ecg.verdict(r).first,
                color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { show = r }.padding(vertical = 4.dp))
        }
    }
    show?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { show = null },
            title = { Text("ЭКГ · ♥ ${r.hr} · ${r.seconds} с") },
            text = {
                Column(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    EcgTrace(r, seconds = r.seconds, height = 160, pxPerSecond = 110)
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { show = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun EcgTrace(r: fi.sarmat.pulsetrainer.core.EcgRecord, seconds: Int, height: Int, pxPerSecond: Int? = null) {
    val n = (seconds * r.hz).coerceAtMost(r.samples.size)
    val x = r.samples.copyOfRange(r.samples.size - n, r.samples.size)
    if (x.size < 10) return
    val mod = if (pxPerSecond != null) Modifier.width((seconds * pxPerSecond).dp) else Modifier.fillMaxWidth()
    androidx.compose.foundation.Canvas(mod.height(height.dp).background(Color(0xFF1E1416))) {
        // 0.2 s grid like ECG paper
        val step = size.width / (n / (r.hz * 0.2f))
        var gx = 0f
        while (gx < size.width) { drawLine(Color(0xFF3A2428), androidx.compose.ui.geometry.Offset(gx, 0f), androidx.compose.ui.geometry.Offset(gx, size.height), 1f); gx += step }
        val sorted = x.sorted()
        val lo = sorted[(sorted.size * 0.01).toInt()]; val hi = sorted[(sorted.size * 0.995).toInt()].coerceAtLeast(lo + 1)
        val path = androidx.compose.ui.graphics.Path()
        x.forEachIndexed { i, v ->
            val o = androidx.compose.ui.geometry.Offset(i * size.width / n, (size.height * 0.95f - (v - lo).toFloat() / (hi - lo) * size.height * 0.9f).coerceIn(0f, size.height))
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, Color(0xFFFF6B6B), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
    }
}
