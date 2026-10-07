package fi.sarmat.pulsetrainer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import fi.sarmat.pulsetrainer.core.HrvRecord
import fi.sarmat.pulsetrainer.core.Mode
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.WorkoutType
import fi.sarmat.pulsetrainer.core.fmtDuration
import fi.sarmat.pulsetrainer.core.fmtKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        state = state,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemChip(label: String, sub: String? = null, color: Color = Colors.card, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(color)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
        if (sub != null) Text(sub, fontSize = 13.sp, color = Colors.dim, maxLines = 2)
    }
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Colors.card).padding(10.dp)) { content() }
}

@Composable
fun Line(text: String, color: Color = Color.White, size: Int = 15, bold: Boolean = false) {
    Text(text, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

fun typeHint(t: WorkoutType): String = t.note ?: when {
    t.mode == Mode.SETS && t.repCount -> "Подходы · автосчёт · отдых по пульсу"
    t.mode == Mode.SETS -> "Подходы · отдых по пульсу"
    t.mode == Mode.ROUNDS -> "Раунды ${fmtDuration(t.roundWork)} / ${fmtDuration(t.roundRest)}"
    t.gps -> "GPS · дистанция · темп · карта"
    t.treadmill -> "Скорость · зоны · калории"
    else -> "Зоны пульса · калории"
}

// ======================= Home =======================

@Composable
fun HomeScreen(onStart: (WorkoutType) -> Unit, open: (Scr) -> Unit) {
    val st by HrSensor.status.collectAsState()
    val bpm by HrSensor.bpm.collectAsState()
    val running by WorkoutEngine.ui.collectAsState()
    val types = remember { Storage.mainTypes() }
    val moreCount = remember { Storage.moreTypes().size }
    val hrv = remember { Storage.todayHrv() }
    val last = remember { Storage.lastWorkout() }

    ListScreen {
        item { Line("PulseTrainer", Colors.dim, 14) }
        if (running.running) item {
            ItemChip("● Идёт тренировка", running.type.title, Colors.danger) { open(Scr.Workout) }
        }
        item {
            val name = Storage.sensorName()
            val (label, color) = when (st) {
                HrSensor.Status.CONNECTED -> "♥ ${bpm ?: "--"} · датчик подключён" to Color(0xFF1F4D33)
                HrSensor.Status.CONNECTING, HrSensor.Status.RECONNECTING -> "Подключаю датчик…" to Colors.card
                else -> (if (name == null) "Подключить Polar H10" else "Датчик не подключён") to Color(0xFF4D3A1F)
            }
            ItemChip(label, name ?: "Нажмите для настройки", color) { open(Scr.Sensor) }
        }
        if (hrv != null) item {
            val c = when (hrv.status) { 0 -> Color(0xFF1F4D33); 1 -> Color(0xFF4D461F); 2 -> Color(0xFF4D1F1F); else -> Colors.card }
            ItemChip("Готовность сегодня", Physiology.READINESS_TEXT[hrv.status], c) { open(Scr.Hrv) }
        }
        if (last != null && last.recoveryHours > 0) {
            val left = ((last.end + last.recoveryHours * 3600_000L - System.currentTimeMillis()) / 3600_000L).toInt()
            if (left > 0) item {
                Card {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Line("Восстановление: ещё ~$left ч", Colors.wait, 15, bold = true)
                        Line("после «${last.title}». Лёгкое кардио и прогулка — можно.", Colors.dim, 13)
                    }
                }
            }
        }
        item { ListHeader { Text("Тренировки", fontSize = 16.sp) } }
        items(types) { t ->
            ItemChip(t.title, typeHint(t), onLongClick = { Haptics.tick(); open(Scr.Arrange(t)) }) { onStart(t) }
        }
        item { Line("Долгое нажатие — переместить или убрать", Colors.dim, 12) }
        item { ItemChip("Другие виды спорта", "Футбол, бассейн, теннис и ещё $moreCount", Color(0xFF233142)) { open(Scr.More) } }
        item { ItemChip("Порядок тренировок", "Настроить свой список", Color(0xFF233142)) { open(Scr.Order) } }
        item { ListHeader { Text("Ещё", fontSize = 16.sp) } }
        item { ItemChip("Утренний тест готовности", "2,5 мин лёжа с датчиком") { open(Scr.Hrv) } }
        item { ItemChip("История", "Прошлые тренировки") { open(Scr.History) } }
        item { ItemChip("Профиль и зоны пульса", "Возраст, вес, зоны") { open(Scr.Profile) } }
    }
}

// ======================= Arrange one exercise =======================

@Composable
fun ArrangeScreen(t: WorkoutType, onStart: (WorkoutType) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val main = remember(tick) { Storage.mainTypes() }
    val inMain = t in main
    ListScreen {
        item { Line(t.title, Color.White, 17, bold = true) }
        if (inMain) item { Line("Место ${main.indexOf(t) + 1} из ${main.size}", Colors.dim, 14) }
        item { ItemChip("▶ Начать", null, Colors.action) { onStart(t) } }
        if (inMain) {
            item { ItemChip("↑ Выше", null) { Storage.move(t, -1); tick++ } }
            item { ItemChip("↓ Ниже", null) { Storage.move(t, 1); tick++ } }
            item { ItemChip("⤒ В самый верх", null) { Storage.moveToTop(t); tick++ } }
            item { ItemChip("Убрать в «Другие виды»", null, Color(0xFF4D3A1F)) { Storage.setHidden(t, true); tick++ } }
        } else {
            item { ItemChip("+ В главное меню", null, Color(0xFF1F4D33)) { Storage.setHidden(t, false); Storage.moveToTop(t); tick++ } }
        }
    }
}

// ======================= Other sports =======================

@Composable
fun MoreScreen(onStart: (WorkoutType) -> Unit, open: (Scr) -> Unit) {
    val list = remember { Storage.moreTypes() }
    ListScreen {
        item { ListHeader { Text("Другие виды спорта", fontSize = 16.sp) } }
        item { Line("Нажмите — начать. Долгое нажатие — добавить в главное меню.", Colors.dim, 13) }
        items(list) { t ->
            ItemChip(t.title, t.note ?: typeHint(t), onLongClick = { Haptics.tick(); open(Scr.Arrange(t)) }) { onStart(t) }
        }
    }
}

// ======================= Order of the main list =======================

@Composable
fun OrderScreen() {
    var tick by remember { mutableIntStateOf(0) }
    val main = remember(tick) { Storage.mainTypes() }
    ListScreen {
        item { ListHeader { Text("Порядок тренировок", fontSize = 16.sp) } }
        items(main) { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Colors.card).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(t.short, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                RoundBtn("↑", size = 34.dp) { Storage.move(t, -1); tick++ }
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                RoundBtn("↓", size = 34.dp) { Storage.move(t, 1); tick++ }
            }
        }
        item { Line("Убрать или вернуть тренировку — долгим нажатием в главном меню или в «Других видах».", Colors.dim, 13) }
    }
}

// ======================= Sensor =======================

@Composable
fun SensorScreen() {
    val st by HrSensor.status.collectAsState()
    val bpm by HrSensor.bpm.collectAsState()
    val batt by HrSensor.battery.collectAsState()
    val found by HrSensor.found.collectAsState()
    var saved by remember { mutableStateOf(Storage.sensorName()) }

    LaunchedEffect(Unit) { if (Storage.sensorAddress() == null) HrSensor.startScan() }
    DisposableEffect(Unit) { onDispose { HrSensor.stopScan() } }

    ListScreen {
        item { ListHeader { Text("Датчик пульса") } }
        item {
            Card {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Line(saved ?: "Датчик не выбран", bold = true)
                    Line(
                        when (st) {
                            HrSensor.Status.CONNECTED -> "Подключён ♥ ${bpm ?: "--"}" + (batt?.let { " · батарея $it%" } ?: "")
                            HrSensor.Status.CONNECTING -> "Подключение…"
                            HrSensor.Status.RECONNECTING -> "Переподключение…"
                            HrSensor.Status.SCANNING -> "Поиск…"
                            HrSensor.Status.OFF -> "Не подключён"
                        },
                        if (st == HrSensor.Status.CONNECTED) Colors.ready else Colors.wait, 12
                    )
                }
            }
        }
        if (saved != null && st != HrSensor.Status.CONNECTED) item {
            ItemChip("Подключить снова", saved) { HrSensor.connectSaved() }
        }
        item { ItemChip(if (st == HrSensor.Status.SCANNING) "Ищу датчики…" else "Искать датчики", "Наденьте и смочите ремень", Colors.action) { HrSensor.startScan() } }
        items(found) { f ->
            ItemChip(f.name, "Сигнал ${f.rssi} dBm · нажмите, чтобы выбрать") {
                HrSensor.connect(f.address, f.name); saved = f.name
            }
        }
        if (saved != null) item {
            ItemChip("Забыть датчик", null, Color(0xFF4D1F1F)) { HrSensor.disconnect(forget = true); saved = null }
        }
        item {
            Line("Датчик запоминается и дальше подключается сам при запуске тренировки. H10 может одновременно работать с телефоном.", Colors.dim, 13)
        }
    }
}

// ======================= Switch exercise =======================

@Composable
fun SwitchScreen(onPick: (WorkoutType) -> Unit) {
    val ui by WorkoutEngine.ui.collectAsState()
    val types = remember { Storage.orderedTypes() }
    ListScreen {
        item { ListHeader { Text(if (ui.running) "Сменить на…" else "Начать") } }
        if (ui.running) item { Line("Сейчас: ${ui.type.title}", Colors.dim, 13) }
        items(types.filter { !ui.running || it != ui.type }) { t ->
            ItemChip(t.title, typeHint(t)) { onPick(t) }
        }
    }
}

// ======================= Summary =======================

private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))

@Composable
fun SummaryScreen(id: String, onDone: () -> Unit) {
    val w = remember { Storage.load(id) }
    if (w == null) {
        ListScreen { item { Line("Тренировка не найдена") }; item { ItemChip("Готово") { onDone() } } }
        return
    }
    ListScreen {
        item { Line(w.title, bold = true, size = 15) }
        item { Line(dateFmt.format(Date(w.start)) + " · " + fmtDuration(w.activeSec), Colors.dim, 14) }
        item {
            Card {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Big("${w.avgHr}", "ср. пульс")
                        Big("${w.maxHr}", "макс.")
                        Big("${w.kcalTotal.toInt()}", "ккал")
                    }
                    Spacer(6)
                    ZoneBars(w.zoneSec)
                    Spacer(4)
                    val zs = w.zoneSec
                    for (z in 5 downTo 1) if (zs[z] > 0) Line("${Physiology.ZONE_NAMES[z]}: ${fmtDuration(zs[z])}", Colors.zone[z], 13)
                    Line("Нагрузка (TRIMP): ${w.trimp.toInt()} · ${w.hrSource}", Colors.dim, 12)
                }
            }
        }
        w.segments.forEachIndexed { i, s ->
            item {
                Card {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Line((if (w.segments.size > 1) "${i + 1}. " else "") + s.type.title, bold = true)
                        Line("${fmtDuration(s.activeSec)} · ср. ${s.avgHr} · макс. ${s.maxHr} · ${s.kcalTotal.toInt()} ккал", Colors.dim, 13)
                        if (s.distanceM > 20) Line("${fmtKm(s.distanceM)} км" + if (s.laps.isNotEmpty()) " · кругов ${s.laps.size}" else "", Colors.dim, 13)
                        s.sets.forEachIndexed { n, set ->
                            val parts = mutableListOf<String>()
                            if (set.reps > 0) parts += "${set.reps} повт."
                            parts += "пик ${set.peakHr}"
                            set.hrr60?.let { parts += "−$it/мин" }
                            set.restSec?.let { parts += "отдых ${fmtDuration(it)}" }
                            Line("${if (s.type.mode == Mode.ROUNDS) "Р" else "П"}${n + 1}: " + parts.joinToString(" · "), Color.White, 13)
                        }
                    }
                }
            }
        }
        item { Line(Physiology.recoveryText(w.recoveryHours, w.segments), Colors.wait, 13) }
        item { Line("Тренировка отправлена на телефон → Health Connect → Samsung Health", Colors.dim, 12) }
        item { ItemChip("Готово", null, Colors.action) { onDone() } }
    }
}

@Composable
private fun Big(v: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(unit, fontSize = 12.sp, color = Colors.dim)
    }
}

// ======================= History =======================

@Composable
fun HistoryScreen(open: (String) -> Unit) {
    val list = remember { Storage.list() }
    ListScreen {
        item { ListHeader { Text("История") } }
        if (list.isEmpty()) item { Line("Пока нет тренировок", Colors.dim) }
        items(list) { w ->
            ItemChip(w.title, dateFmt.format(Date(w.start)) + " · " + fmtDuration(w.activeSec) + " · ♥ ${w.avgHr}") { open(w.id) }
        }
    }
}

// ======================= Morning readiness (HRV) =======================

@Composable
fun HrvScreen() {
    KeepScreenOn()
    val st by HrSensor.status.collectAsState()
    val bpm by HrSensor.bpm.collectAsState()
    var stage by remember { mutableIntStateOf(0) } // 0 idle, 1 measuring, 2 result
    var left by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<HrvRecord?>(null) }
    val today = remember { Storage.todayHrv() }

    LaunchedEffect(stage) {
        if (stage != 1) return@LaunchedEffect
        val total = 150
        val settle = 30
        var sec = 0
        val rr = ArrayList<Int>()
        val hrs = ArrayList<Int>()
        val job = launch { HrSensor.rr.collect { if (sec >= settle) rr += it } }
        while (sec < total) {
            left = total - sec
            if (sec >= settle) HrSensor.freshBpm()?.let { hrs += it }
            delay(1000)
            sec++
        }
        job.cancel()
        val clean = Physiology.cleanRr(rr)
        val rmssd = Physiology.rmssd(clean)
        val rest = if (hrs.isNotEmpty()) hrs.sorted().take((hrs.size / 2).coerceAtLeast(1)).average().toInt()
        else if (clean.isNotEmpty()) (60000.0 / clean.average()).toInt() else 0
        if (rmssd > 0 && rest > 0) {
            val status = Physiology.readiness(rmssd, rest, Storage.hrvHistory())
            val rec = HrvRecord(System.currentTimeMillis(), rmssd, rest, status)
            Storage.addHrv(rec)
            result = rec
            Haptics.ready()
        }
        stage = 2
    }

    ListScreen {
        item { ListHeader { Text("Готовность") } }
        when (stage) {
            0 -> {
                if (today != null) item { ResultCard(today) }
                item {
                    Line("Утром, сразу после пробуждения: наденьте H10, лягте и спокойно дышите 2,5 минуты. Не разговаривайте.", Colors.dim, 13)
                }
                if (st == HrSensor.Status.CONNECTED) item {
                    ItemChip("Начать тест", "♥ ${bpm ?: "--"}", Colors.action) { stage = 1 }
                } else item {
                    ItemChip("Сначала подключите датчик", "Нужны интервалы между ударами", Color(0xFF4D3A1F)) { HrSensor.connectSaved() }
                }
            }
            1 -> {
                item { Line(fmtDuration(left), Color.White, 34, bold = true) }
                item { Line("♥ ${bpm ?: "--"}", Colors.zone[1], 18) }
                item { Line(if (left > 120) "Успокойтесь…" else "Измеряю… лежите спокойно", Colors.dim, 14) }
                item { ItemChip("Отмена", null) { stage = 0 } }
            }
            else -> {
                val r = result
                if (r != null) item { ResultCard(r) } else item { Line("Не хватило данных. Проверьте посадку ремня и повторите.", Colors.wait, 14) }
                item { ItemChip("Готово", null, Colors.action) { stage = 0 } }
            }
        }
        item {
            Line("Вариабельность пульса (RMSSD) сравнивается с вашей нормой за 7 дней. Пульс покоя из теста уточняет ваши зоны.", Colors.dim, 12)
        }
    }
}

@Composable
private fun ResultCard(r: HrvRecord) {
    val c = when (r.status) { 0 -> Colors.ready; 1 -> Colors.wait; 2 -> Colors.danger; else -> Colors.dim }
    Card {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Line(Physiology.READINESS_TEXT[r.status] ?: "", c, 13, bold = true)
            Line("ВСР ${r.rmssd.toInt()} мс · покой ${r.restHr} уд/мин", Colors.dim, 13)
        }
    }
}

// ======================= Profile =======================

@Composable
fun ProfileScreen() {
    val p by Storage.profile.collectAsState()
    var auto by remember { mutableStateOf(Storage.autoRestHr) }
    val bounds = Physiology.zoneBounds(p)
    ListScreen {
        item { ListHeader { Text("Профиль") } }
        item { Stepper("Возраст", "${p.age}") { d -> Storage.saveProfile(p.copy(age = (p.age + d).coerceIn(14, 90))) } }
        item { Stepper("Вес, кг", "${p.weightKg.toInt()}") { d -> Storage.saveProfile(p.copy(weightKg = (p.weightKg + d).coerceIn(35.0, 200.0))) } }
        item { Stepper("Рост, см", "${p.heightCm}") { d -> Storage.saveProfile(p.copy(heightCm = (p.heightCm + d).coerceIn(120, 220))) } }
        item { ItemChip("Пол: " + if (p.male) "мужской" else "женский", "Нажмите, чтобы изменить") { Storage.saveProfile(p.copy(male = !p.male)) } }
        item {
            Stepper("Пульс покоя", p.restHr?.toString() ?: "—") { d ->
                Storage.autoRestHr = false; auto = false
                Storage.saveProfile(p.copy(restHr = ((p.restHr ?: 60) + d).coerceIn(35, 100)))
            }
        }
        item {
            ItemChip("Пульс покоя из утреннего теста: " + if (auto) "вкл" else "выкл", null) {
                auto = !auto; Storage.autoRestHr = auto
            }
        }
        item {
            Stepper("Макс. пульс" + if (p.maxHrOverride == null) " (формула)" else "", "${Physiology.maxHr(p)}") { d ->
                Storage.saveProfile(p.copy(maxHrOverride = (Physiology.maxHr(p) + d).coerceIn(120, 220)))
            }
        }
        if (p.maxHrOverride != null) item {
            ItemChip("Макс. пульс по формуле", "208 − 0,7 × возраст") { Storage.saveProfile(p.copy(maxHrOverride = null)) }
        }
        item { ListHeader { Text("Ваши зоны", fontSize = 16.sp) } }
        for (z in 5 downTo 1) item {
            Card {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Line("${Physiology.ZONE_NAMES[z]}", Colors.zone[z], 15, bold = true)
                    Line("${bounds[z - 1]}–${bounds[z]} уд/мин · ${Physiology.ZONE_FEEL[z]}", Color.White, 13)
                }
            }
        }
        item {
            ItemChip("Метод зон: " + if (p.karvonen) "Карвонен" else "% от макс. пульса",
                if (p.karvonen) "Выше зоны; для тренированных" else "Как в Polar и Samsung (рекомендуется)") {
                Storage.saveProfile(p.copy(karvonen = !p.karvonen))
            }
        }
        item {
            Line(
                "Зона 2 — когда можете говорить полными фразами. Если не можете — вы выше зоны 2." +
                    " Новый подход — когда пульс ≤ ${Physiology.readyHr(p)}.",
                Colors.dim, 10
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onDelta: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Colors.card).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 13.sp, color = Colors.dim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundBtn("−", size = 34.dp) { onDelta(-1) }
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            RoundBtn("+", size = 34.dp) { onDelta(1) }
        }
    }
}
