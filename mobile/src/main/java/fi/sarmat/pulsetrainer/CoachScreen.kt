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
import kotlin.math.roundToInt

private val COACH_CARDS = linkedMapOf(
    "plan" to "План на сегодня и готовность",
    "week" to "Неделя",
    "progress" to "Прогресс (вес, талия)",
    "sync" to "Синхронизация",
    "sleep" to "Сон",
    "stats" to "Показатели часов",
    "nutrition" to "Питание под цель",
    "tips" to "Советы тренера",
)
private val COACH_MORE = setOf("progress", "sync", "sleep", "stats", "nutrition", "tips")

@Composable
fun CoachScreen(needAccess: Boolean, onGrant: () -> Unit, onRefresh: () -> Unit) {
    val profile by PhoneStore.profile.collectAsState()
    val goal by PhoneStore.goal.collectAsState()
    val days by PhoneStore.days.collectAsState()
    val workouts by PhoneStore.workouts.collectAsState()
    val tests by PhoneStore.hrv.collectAsState()
    val weights by PhoneStore.weights.collectAsState()
    val body by PhoneStore.body.collectAsState()
    val ext by PhoneStore.ext.collectAsState()
    val passive by PhoneStore.passive.collectAsState()
    val check by PhoneStore.checkIn.collectAsState()
    val p = profile ?: Profile()

    val today = days.lastOrNull()
    val advice = remember(p, goal, days, workouts, tests, weights, body, ext, check, passive) { PhoneStore.advise() }
    val color = levelColor(advice.level)
    val layout = rememberCardLayout("coach", COACH_CARDS.keys.toList(), COACH_MORE)

    @Composable
    fun card(id: String) {
        when (id) {
            "plan" -> Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(advice.score / 100f, color, "${advice.score}", Modifier.size(92.dp))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(Coach.levelText(advice), color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(advice.headline, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(advice.type.title, color = Accent, fontSize = 15.sp)
                    }
                }
                advice.plan.forEach { BulletText(it) }
                CheckInBlock()
                if (advice.avoid.isNotEmpty()) {
                    Text("Сегодня избегайте", color = Danger, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    advice.avoid.forEach { BulletText(it, Color.White, 15) }
                }
                Expander("Почему такая оценка (${advice.confidence} из 5 показателей)") {
                    advice.reasons.forEach { Text(it, color = if (it.startsWith("▼")) Warn else Color.White, fontSize = 15.sp) }
                    if (advice.missing.isNotEmpty()) {
                        Text("Не хватает для точности:", color = Dim, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        advice.missing.forEach { BulletText(it, Dim, 15) }
                    }
                    Text("🕒 " + advice.whenText, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("Как считается: сон и недосып, пульс покоя к вашей норме, вариабельность пульса (утренний тест), нагрузка за неделю к обычной (включая Samsung Health) и ваше самочувствие. Пока данных мало, оценка не поднимается выше «средней».",
                        color = Dim, fontSize = 15.sp)
                }
            }
            "sync" -> SyncCard(needAccess, days)
            "sleep" -> SleepCard(today)
            "stats" -> StatsCard(days, p)
            "week" -> Section {
                Text("Неделя", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                advice.week.forEach { BulletText(it) }
            }
            "progress" -> Section {
                Text("Прогресс", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                WeightChart(weights.map { it.time to it.kg })
                advice.progress.forEach { BulletText(it) }
            }
            "nutrition" -> Section {
                Text("Питание под цель", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                advice.nutrition.forEach { BulletText(it) }
            }
            "tips" -> Section {
                Text("Советы тренера", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                advice.tips.forEach { BulletText(it) }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Тренер", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                ArrangeButton("coach", COACH_CARDS, COACH_MORE)
            }
            Text("Цель: ${goal.title}", fontSize = 16.sp, color = Dim)
        }
        if (needAccess) item {
            Section {
                Text("Подключите данные часов", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Разрешите чтение сна, пульса, тренировок, шагов и веса из Health Connect — тренер станет точнее.", color = Dim, fontSize = 16.sp)
                Button(onClick = onGrant) { Text("Разрешить доступ", fontSize = 16.sp) }
            }
        }
        layout.top.forEach { id -> item(key = id) { card(id) } }
        item(key = "more") { MoreBlock(layout.more.size) { layout.more.forEach { card(it) } } }
        item {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Обновить данные часов", fontSize = 16.sp) }
            Text(
                "Рекомендации основаны на общепринятых руководствах (ВОЗ, исследования по гипертрофии и интервальным тренировкам) и не заменяют врача.",
                color = Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SleepCard(today: fi.sarmat.pulsetrainer.core.DailyStats?) {
    Section {
        Text("Сон прошлой ночью", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val t = today
        val sleep = t?.sleepMin
        if (t == null || sleep == null) {
            Text("Нет данных. Спите с часами — Samsung Health передаст сон в Health Connect.", color = Dim, fontSize = 15.sp)
            return@Section
        }
        Text(Coach.fmtH(sleep), color = if (sleep >= 420) Good else if (sleep >= 360) Warn else Danger, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        val parts = listOfNotNull(
            t.deepMin?.let { Triple("Глубокий", it, Color(0xFF4C6EF5)) },
            t.remMin?.let { Triple("REM", it, Color(0xFF9775FA)) },
            t.lightMin?.let { Triple("Лёгкий", it, Color(0xFF74C0FC)) },
            t.awakeMin?.let { Triple("Пробужд.", it, Color(0xFFF2994A)) },
        )
        if (parts.isNotEmpty()) {
            val total = parts.sumOf { it.second }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                parts.forEach { (_, m, c) -> if (m > 0) Box(Modifier.weight(m.toFloat() / total).height(14.dp).background(c)) }
            }
            Text(parts.joinToString(" · ") { "${it.first} ${it.second} мин" }, color = Dim, fontSize = 15.sp)
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
        Text("Показатели часов", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        StatRow("Пульс покоя", today?.restHr?.toString() ?: "—", avg { it.restHr }?.let { "ср. 7 дн: %.0f".format(it) })
        StatRow("Вариабельность пульса", today?.hrvMs?.let { "%.0f мс".format(it) } ?: "—", avg { it.hrvMs }?.let { "ср. 7 дн: %.0f".format(it) })
        StatRow("Шаги сегодня", today?.steps?.toString() ?: "—", avg { it.steps }?.let { "ср. 7 дн: %.0f".format(it) })
        StatRow("Кислород (SpO2)", today?.spo2?.let { "%.0f%%".format(it) } ?: "—", null)
        StatRow("Вес", "%.1f кг".format(p.weightKg), days.lastOrNull { it.bodyFatPct != null }?.bodyFatPct?.let { "жир %.1f%%".format(it) })
        StatRow("Зона 2 (сердце)", Physiology.zoneBounds(p).let { "${it[1]}–${it[2]}" }, "макс. пульс ${Physiology.maxHr(p)}")
    }
}

@Composable
private fun StatRow(label: String, value: String, sub: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Dim, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = Dim, fontSize = 15.sp)
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
    var autoRest by remember { mutableStateOf(PhoneStore.autoRest) }
    val changed = p != (stored ?: Profile()) || autoRest != PhoneStore.autoRest

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Профиль и настройки", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White) }
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
                    Text("Пол", color = Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(if (p.male) "мужской" else "женский", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Stepper(if (autoRest) "Пульс покоя · авто" else "Пульс покоя", p.restHr?.toString() ?: "—", 1.0) { d ->
                    autoRest = false
                    p = p.copy(restHr = ((p.restHr ?: 60) + d.toInt()).coerceIn(35, 100))
                }
                Row(Modifier.fillMaxWidth().clickable { autoRest = !autoRest }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Пульс покоя автоматически", color = Color.White, fontSize = 15.sp)
                        Text("По ночному пульсу и утренним тестам (медиана 7 дней)", color = Dim, fontSize = 14.sp)
                    }
                    androidx.compose.material3.Switch(checked = autoRest, onCheckedChange = { autoRest = it })
                }
                Stepper(if (p.maxHrOverride == null) "Макс. пульс · формула" else "Макс. пульс · свой", "${Physiology.maxHr(p)}", 1.0) { d ->
                    p = p.copy(maxHrOverride = (Physiology.maxHr(p) + d.toInt()).coerceIn(120, 220))
                }
                if (p.maxHrOverride != null) Text("Сбросить макс. пульс к формуле (208 − 0,7 × возраст)", color = Accent, fontSize = 15.sp,
                    modifier = Modifier.clickable { p = p.copy(maxHrOverride = null) })
                val b = Physiology.zoneBounds(p)
                Text("Ваши зоны пульса", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                for (z in 1..5) {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Text("${Physiology.ZONE_NAMES[z]}: ${b[z - 1]}–${b[z]}", color = ZoneColors[z], fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(Physiology.ZONE_FEEL[z], color = Dim, fontSize = 15.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { p = p.copy(karvonen = !p.karvonen) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Метод расчёта зон", color = Color.White, fontSize = 15.sp)
                        Text(if (p.karvonen) "Карвонен (от резерва пульса) — зоны выше, для тренированных"
                            else "% от макс. пульса — как в Polar и Samsung (рекомендуется)", color = Dim, fontSize = 15.sp)
                    }
                    androidx.compose.material3.Switch(checked = p.karvonen, onCheckedChange = { p = p.copy(karvonen = it) })
                }
                Text("Проверка зоны 2: можете говорить полными фразами. Если только отдельными словами — вы выше зоны 2, сбавьте темп.",
                    color = Warn, fontSize = 15.sp)
                Button(
                    onClick = {
                        val toSave = p
                        scope.launch {
                            PhoneStore.autoRest = autoRest
                            PhoneStore.updateProfile(ctx, if (autoRest) toSave.copy(restHr = PhoneStore.autoRestValue() ?: toSave.restHr) else toSave)
                            Toast.makeText(ctx, "Сохранено и отправлено на часы", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = changed, modifier = Modifier.fillMaxWidth()
                ) { Text(if (changed) "Сохранить и отправить на часы" else "Сохранено") }
                if (stored == null) Text("Профиль ещё не пришёл с часов — откройте PulseTrainer на часах.", color = Warn, fontSize = 15.sp)
            }
        }
        item { BackupCard() }
        item { FontCard() }
        item { BodyCard() }
        item { RemindersCard() }
        item {
            Section {
                Text("История веса", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                WeightChart(weights.map { it.time to it.kg })
                if (weights.isEmpty()) Text("Измените вес выше и сохраните — запись появится здесь и в Samsung Health.", color = Dim, fontSize = 15.sp)
                weights.takeLast(8).reversed().forEach {
                    Text("${dFmt.format(Date(it.time))}: ${"%.1f".format(it.kg)} кг", color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, step: Double, onDelta: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
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


// ======================= Sync status =======================

@Composable
fun SyncCard(needAccess: Boolean, days: List<fi.sarmat.pulsetrainer.core.DailyStats>) {
    val watch by PhoneStore.lastWatchContact.collectAsState()
    val lastRx by PhoneStore.lastWorkoutReceived.collectAsState()
    val live by PhoneStore.live.collectAsState()
    val now = System.currentTimeMillis()
    fun ago(t: Long): String {
        val m = (now - t) / 60000
        return when { m < 2 -> "только что"; m < 60 -> "$m мин назад"; m < 48 * 60 -> "${m / 60} ч назад"; else -> "${m / 1440} дн назад" }
    }
    val lastData = days.lastOrNull { it.sleepMin != null || it.steps != null || it.restHr != null }
    Section {
        Text("Синхронизация", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Часы → (Bluetooth) → PulseTrainer на телефоне → Health Connect ⇄ Samsung Health", color = Dim, fontSize = 15.sp)
        SyncLine(watch > 0 && now - watch < 3 * 86400_000L, "Часы PulseTrainer",
            if (watch > 0) "на связи ${ago(watch)}" else "ещё не было связи — откройте приложение на часах")
        live?.takeIf { now - it.time < 15_000 }?.let {
            SyncLine(it.sensor == "H10", "Polar H10", if (it.sensor == "H10") "пульс идёт с ремня" else "пульс с часов — проверьте ремень")
        }
        SyncLine(lastRx > 0, "Тренировки с часов", if (lastRx > 0) "последняя получена ${ago(lastRx)}" else "пока не было")
        SyncLine(!needAccess, "Health Connect", if (needAccess) "нужен доступ — нажмите «Разрешить» выше" else "доступ есть")
        val lastP by PhoneStore.lastPassive.collectAsState()
        SyncLine(lastP > 0 && now - lastP < 2 * 86400_000L, "Фоновый сбор на часах",
            if (lastP > 0) "ночной пульс и шаги получены ${ago(lastP)}" else "включите на часах: Профиль → «Фоновый сбор»")
        SyncLine(lastData != null, "Данные Samsung Health",
            if (lastData != null) "сон/шаги/пульс покоя до ${SimpleDateFormat("d MMM", Locale("ru")).format(Date(lastData.day))}"
            else "нет — включите синхронизацию Samsung Health с Health Connect")
    }
}

@Composable
private fun SyncLine(ok: Boolean, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "!", color = if (ok) Good else Warn, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(sub, color = Dim, fontSize = 15.sp)
        }
    }
}


// ======================= Body measurements =======================

@Composable
private fun BodyCard() {
    val entries by PhoneStore.body.collectAsState()
    var weight by remember { mutableStateOf("") }
    var waist by remember { mutableStateOf("") }
    var chest by remember { mutableStateOf("") }
    var arm by remember { mutableStateOf("") }
    var thigh by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Section {
        Text("Замеры тела", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Раз в 1–2 недели, утром. По талии тренер отличит рост мышц от жира.", color = Dim, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Вес, кг", weight, Modifier.weight(1f)) { weight = it }
            NumField("Талия, см", waist, Modifier.weight(1f)) { waist = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Грудь, см", chest, Modifier.weight(1f)) { chest = it }
            NumField("Бицепс, см", arm, Modifier.weight(1f)) { arm = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Бедро, см", thigh, Modifier.weight(1f)) { thigh = it }
            NumField("Жир, %", fat, Modifier.weight(1f)) { fat = it }
        }
        Button(
            onClick = {
                val e = fi.sarmat.pulsetrainer.core.BodyEntry(System.currentTimeMillis(), num(weight), num(waist), num(chest), num(arm), num(thigh), num(fat))
                if (listOf(e.weightKg, e.waistCm, e.chestCm, e.armCm, e.thighCm, e.bodyFatPct).all { it == null }) {
                    Toast.makeText(ctx, "Введите хотя бы одно значение", Toast.LENGTH_SHORT).show()
                } else {
                    PhoneStore.addBody(e)
                    e.weightKg?.let { w ->
                        scope.launch { PhoneStore.profile.value?.let { p -> PhoneStore.updateProfile(ctx, p.copy(weightKg = w)) } }
                    }
                    weight = ""; waist = ""; chest = ""; arm = ""; thigh = ""; fat = ""
                    Toast.makeText(ctx, "Замер сохранён", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить замер") }
        entries.takeLast(6).reversed().forEach { e ->
            val parts = listOfNotNull(
                e.weightKg?.let { "вес %.1f".format(it) }, e.waistCm?.let { "талия %.1f".format(it) }, e.chestCm?.let { "грудь %.1f".format(it) },
                e.armCm?.let { "бицепс %.1f".format(it) }, e.thighCm?.let { "бедро %.1f".format(it) }, e.bodyFatPct?.let { "жир %.1f%%".format(it) },
            )
            Text("${dFmt.format(Date(e.time))}: " + parts.joinToString(", "), color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(label, fontSize = 15.sp) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
        modifier = modifier,
    )
}

// ======================= Reminders =======================

@Composable
private fun RemindersCard() {
    val ctx = LocalContext.current
    var morning by remember { mutableStateOf(PhoneStore.remindMorning) }
    var evening by remember { mutableStateOf(PhoneStore.remindEvening) }
    var bedtime by remember { mutableStateOf(PhoneStore.remindBedtime) }
    var hour by remember { mutableStateOf(PhoneStore.remindMorningHour) }
    var watchOnly by remember { mutableStateOf(PhoneStore.watchOnly) }
    fun apply() {
        PhoneStore.remindMorning = morning; PhoneStore.remindEvening = evening; PhoneStore.remindMorningHour = hour
        PhoneStore.remindBedtime = bedtime
        Reminders.schedule(ctx, keep = false)
    }
    Section {
        Text("Отчёты и напоминания", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Toggle("Утренний отчёт: как спал, готовность, энергия, план дня", morning) { morning = it; apply() }
        if (morning) Stepper("Время утреннего отчёта", "%02d:00".format(hour), 1.0) { d -> hour = (hour + d.toInt() + 24) % 24; apply() }
        Toggle("Вечерний отчёт: во сколько лечь спать и итоги дня (за 45 мин до сна)", bedtime) { bedtime = it; apply() }
        if (bedtime) Text("Рекомендуемое время сна сегодня: ${Reminders.hm(Reminders.bedtime())} (подъём ~${Reminders.hm(Reminders.usualWake())})",
            color = Good, fontSize = 15.sp)
        Toggle("Вечером (${Reminders.EVENING_HOUR}:00): напомнить, если тренировки ещё не было", evening) { evening = it; apply() }
        OutlinedButton(onClick = {
            val a = Reminders.advice()
            Reminders.notify(ctx, 101, "Тренер: ${a.headline}", listOf("Готовность ${a.score}/100") + a.plan.take(3) + a.whenText)
        }) { Text("Показать совет сейчас") }
    }
    Section {
        Text("Источник данных сна и пульса", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Toggle("Только PulseTrainer (без Samsung Health)", watchOnly) { watchOnly = it; PhoneStore.watchOnly = it }
        Text(if (watchOnly)
            "Сон, пульс покоя, шаги и пульс за день — с часов через PulseTrainer (фоновый сбор должен быть включён на часах). Фаз сна, SpO₂, давления и состава тела без Samsung Health нет."
        else
            "Сначала данные Samsung Health (фазы сна, SpO₂, вес с весов), пропуски дополняются часами PulseTrainer.",
            color = Dim, fontSize = 14.sp)
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp)) {
        Text(text, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = on, onCheckedChange = onChange)
    }
}


// ======================= Font size =======================

@Composable
private fun FontCard() {
    val scale by PhoneStore.fontScale.collectAsState()
    Section {
        Text("Размер шрифта", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Stepper("Масштаб", "${(scale * 100).roundToInt()}%", 0.05) { d -> PhoneStore.setFontScale(scale + d.toFloat()) }
        Text("Так будет выглядеть обычный текст в приложении.", color = Color.White, fontSize = 15.sp)
    }
}


// ======================= Backup & other services =======================

@Composable
private fun BackupCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf(Backup.lastManual) }
    var askRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val stamp = SimpleDateFormat("ddMMyyyy_HHmm", Locale.US).format(Date())
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Backup.export(ctx, uri) }.getOrDefault(-1) }
            last = Backup.lastManual
            Toast.makeText(ctx, if (n > 0) "Копия сохранена ($n файлов)" else "Не удалось сохранить", Toast.LENGTH_LONG).show()
        }
    }
    val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) askRestore = uri }
    val tcxLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Backup.exportAllTcx(ctx, uri) }.getOrDefault(-1) }
            Toast.makeText(ctx, if (n >= 0) "Экспортировано тренировок: $n" else "Не удалось экспортировать", Toast.LENGTH_LONG).show()
        }
    }
    Section {
        Text("Резервная копия и синхронизация", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("✓ Автоматически: копия в ваш Google-аккаунт (Google Диск) раз в сутки, когда телефон заряжается по Wi-Fi. На новом телефоне данные вернутся при установке.",
            color = Good, fontSize = 15.sp)
        Text("Последняя ручная копия: " + if (last > 0) dFmt.format(Date(last)) else "ещё не было", color = Dim, fontSize = 15.sp)
        androidx.compose.material3.Button(onClick = { saveLauncher.launch("PulseTrainer_$stamp.zip") }, modifier = Modifier.fillMaxWidth()) {
            Text("Сохранить копию (Google Диск / файлы)", fontSize = 16.sp)
        }
        OutlinedButton(onClick = { openLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Восстановить из копии", fontSize = 16.sp)
        }
        Expander("Strava, Garmin Connect и другие сервисы") {
            BulletText("Samsung Health — автоматически через Health Connect (а оттуда в Samsung Cloud).", Color.White, 15)
            BulletText("Strava / Garmin Connect / TrainingPeaks: в каждой тренировке кнопка «Экспорт (TCX)» — пульс, маршрут и дистанция. В Strava: strava.com → «+» → «Загрузить активность» → файл.", Color.White, 15)
            OutlinedButton(onClick = { tcxLauncher.launch("PulseTrainer_TCX_$stamp.zip") }, modifier = Modifier.fillMaxWidth()) {
                Text("Экспорт всех тренировок (TCX, zip)")
            }
            BulletText("Часы работают и без телефона: тренировки, тесты и ночной пульс хранятся на часах и досылаются, когда телефон снова рядом.", Dim, 15)
        }
    }
    askRestore?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askRestore = null },
            title = { Text("Восстановить из копии?") },
            text = { Text("Тренировки из копии добавятся к текущим, профиль, тесты и питание заменятся данными из копии. Приложение перезапустится.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    askRestore = null
                    scope.launch {
                        val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Backup.import(ctx, uri) }.getOrDefault(-1) }
                        if (n <= 0) { Toast.makeText(ctx, "В файле нет данных PulseTrainer", Toast.LENGTH_LONG).show(); return@launch }
                        Toast.makeText(ctx, "Восстановлено ($n). Перезапуск…", Toast.LENGTH_LONG).show()
                        kotlinx.coroutines.delay(1200)
                        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (i != null) ctx.startActivity(i)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }) { Text("Восстановить") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askRestore = null }) { Text("Отмена") } },
        )
    }
}
