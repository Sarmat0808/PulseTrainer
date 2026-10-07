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
import androidx.compose.foundation.layout.size
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
fun ItemChip(
    label: String, sub: String? = null, color: Color = Colors.card, icon: WorkoutType? = null,
    star: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(color)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF2D6CDF)), contentAlignment = Alignment.Center) {
                SportIcon(icon, 22.dp, Color.White)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
            if (sub != null) Text(sub, fontSize = 15.sp, color = Colors.dim, maxLines = 3)
        }
        if (star) Text("★", fontSize = 18.sp, color = Color(0xFFF2C94C), modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Colors.card).padding(10.dp)) { content() }
}

@Composable
fun Line(text: String, color: Color = Color.White, size: Int = 16, bold: Boolean = false) {
    // Small text is hard to read on the wrist: never below 14 sp, and medium weight for crisp strokes.
    Text(text, color = color, fontSize = maxOf(size, 14).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

fun typeHint(t: WorkoutType): String = t.note ?: when {
    t.mode == Mode.SETS && t.repCount -> "Подходы · автосчёт · отдых по пульсу"
    t.mode == Mode.SETS -> "Подходы · отдых по пульсу"
    t.mode == Mode.ROUNDS -> Storage.intervals(t).let { "Работа ${it.work} с / отдых ${it.rest} с × ${it.rounds}" }
    t == WorkoutType.STAIRS_HOME -> "Этажи · высота · темп подъёма"
    t == WorkoutType.STAIRS_OUTDOOR -> "Этажи · высота · GPS-маршрут"
    t == WorkoutType.STAIRS -> "Этажи по шагам · шаг/мин"
    t.gps && t.climb -> "GPS · темп · высота · маршрут"
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
    val coachAll by Storage.coach.collectAsState()
    val types = remember { Storage.mainTypes() }
    val fav by Storage.favorites.collectAsState()
    val moreCount = remember { Storage.moreTypes().size }
    val hrv = remember { Storage.todayHrv() }
    val last = remember { Storage.lastWorkout()?.takeIf { Physiology.isRealWorkout(it) } }
    val coach = coachAll?.takeIf { System.currentTimeMillis() - it.time < 20 * 3600_000L } ?: remember { Storage.localCoach() }

    ListScreen {
        if (running.running) item {
            ItemChip("● Идёт тренировка", running.type.title, Colors.danger) { open(Scr.Workout) }
        }
        // ---- Readiness first: the most important thing of the day ----
        item {
            val lv = coach?.level ?: hrv?.status ?: -1
            val c = when (lv) { 0 -> Color(0xFF1F4D33); 1 -> Color(0xFF4D461F); 2 -> Color(0xFF4D1F1F); else -> Colors.card }
            when {
                coach != null -> ItemChip("Готовность ${coach.score} · ${coach.label}",
                    (if (coach.energy >= 0) "Энергия ${coach.energy} · " else "") + coach.headline, c) { open(Scr.Ready) }
                hrv != null -> ItemChip("Готовность сегодня", Physiology.READINESS_TEXT[hrv.status], c) { open(Scr.Hrv) }
                else -> ItemChip("Утренний тест готовности", "2,5 мин лёжа · нажмите, чтобы начать", Colors.action) { open(Scr.Hrv) }
            }
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
        if (last != null && last.recoveryHours > 0) {
            val left = ((last.end + last.recoveryHours * 3600_000L - System.currentTimeMillis()) / 3600_000L).toInt()
            if (left > 0) item {
                Card {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Line("Восстановление: ещё ~$left ч", Colors.wait, 16, bold = true)
                        Line("после «${last.title}». Лёгкое кардио и прогулка — можно.", Colors.dim, 14)
                    }
                }
            }
        }
        if (fav.isNotEmpty()) {
            item { ListHeader { Text("★ Избранное", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF2C94C)) } }
            items(fav) { t -> ItemChip(t.title, null, icon = t, star = true, onLongClick = { Haptics.tick(); open(Scr.Arrange(t)) }) { onStart(t) } }
        }
        item { ListHeader { Text("Все тренировки", fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
        items(types.filter { it !in fav }) { t ->
            ItemChip(t.title, typeHint(t), icon = t, onLongClick = { Haptics.tick(); open(Scr.Arrange(t)) }) { onStart(t) }
        }
        item { Line("Долгое нажатие — ★ избранное, порядок, убрать", Colors.dim, 14) }
        item { ItemChip("Другие виды спорта", "Футбол, бассейн, теннис и ещё $moreCount", Color(0xFF233142)) { open(Scr.More) } }
        item { ListHeader { Text("Здоровье", fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
        item { ItemChip("Стресс", "Замер 1 мин сидя · дыхание", Color(0xFF2A2442)) { open(Scr.Stress) } }
        item { ItemChip("Утренний тест готовности", "2,5 мин лёжа с датчиком") { open(Scr.Hrv) } }
        item { ListHeader { Text("Ещё", fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
        item { ItemChip("История", "Прошлые тренировки") { open(Scr.History) } }
        item { ItemChip("Профиль, зоны, шрифт", "Возраст, вес, зоны, фоновый сбор") { open(Scr.Profile) } }
        item { ItemChip("Порядок тренировок", "Настроить свой список", Color(0xFF233142)) { open(Scr.Order) } }
    }
}

/** Today's readiness and plan as calculated by the phone's coach. */
@Composable
fun ReadyScreen(open: (Scr) -> Unit) {
    val all by Storage.coach.collectAsState()
    val c = all?.takeIf { System.currentTimeMillis() - it.time < 20 * 3600_000L } ?: remember { Storage.localCoach() }
    ListScreen {
        if (c == null) {
            item { Line("Нет данных с телефона. Откройте PulseTrainer на телефоне.", Colors.dim) }
        } else {
            val col = when (c.level) { 0 -> Colors.ready; 1 -> Colors.wait; else -> Colors.danger }
            item { Line("${c.score}", col, 40, bold = true) }
            item { Line(c.label, col, 16, bold = true) }
            if (c.energy >= 0) item { Line("Энергия ${c.energy} · ${c.energyLabel}", Colors.action, 17, bold = true) }
            item { Line(c.headline, Color.White, 18, bold = true) }
            c.plan.forEach { p -> item { Card { Line(p, Color.White, 15) } } }
        }
        item { ItemChip("Утренний тест", "Уточнить готовность", Colors.action) { open(Scr.Hrv) } }
        item { Line("Оценку считает тренер на телефоне: сон, ночной пульс, тест, нагрузка, самочувствие.", Colors.dim, 14) }
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
        item { ItemChip("▶ Начать", null, Colors.action, icon = t) { onStart(t) } }
        item {
            val isFav = t in Storage.favorites.value
            ItemChip(if (isFav) "★ Убрать из избранного" else "☆ В избранное", if (isFav) null else "До 6 тренировок: плитки и телефон",
                if (isFav) Color(0xFF4D461F) else Colors.card) { Storage.toggleFavorite(t); tick++ }
        }
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
            ItemChip(t.title, t.note ?: typeHint(t), icon = t, onLongClick = { Haptics.tick(); open(Scr.Arrange(t)) }) { onStart(t) }
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
            ItemChip(t.title, typeHint(t), icon = t) { onPick(t) }
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
        item {
            val r = remember { fi.sarmat.pulsetrainer.core.Review.of(w, Storage.list(), Storage.profile.value) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SportIcon(w.segments.maxByOrNull { it.activeSec }?.type ?: WorkoutType.OTHER, 26.dp, Colors.action)
                Line(w.title, bold = true, size = 17)
                Line(r.label + if (r.level >= 0) " · ${r.score}" else "",
                    when (r.level) { 0 -> Colors.ready; 1 -> Colors.wait; 2 -> Colors.danger; else -> Colors.dim }, 15, bold = true)
            }
        }
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
                    if (w.floors > 0 || w.ascentM >= 3) Line("Этажей ${w.floors} · ↑ ${w.ascentM.toInt()} м · ↓ ${w.descentM.toInt()} м", Color.White, 15)
                    if (w.steps > 0) Line("Шагов ${w.steps}", Color.White, 15)
                    Line("Нагрузка (TRIMP): ${w.trimp.toInt()} · ${w.hrSource}", Colors.dim, 14)
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
        Text(unit, fontSize = 14.sp, color = Colors.dim)
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
            ItemChip(w.title, dateFmt.format(Date(w.start)) + " · " + fmtDuration(w.activeSec) + " · ♥ ${w.avgHr}",
                icon = w.segments.maxByOrNull { it.activeSec }?.type) { open(w.id) }
        }
    }
}

// ======================= Morning readiness (HRV) =======================

@Composable
fun HrvScreen() {
    KeepScreenOn()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by HrSensor.status.collectAsState()
    val bpm by HrSensor.bpm.collectAsState()
    var stage by remember { mutableIntStateOf(0) } // 0 idle, 1 measuring, 2 result
    var left by remember { mutableIntStateOf(0) }
    var live by remember { mutableStateOf<Int?>(null) }
    var useStrap by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<HrvRecord?>(null) }
    val today = remember { Storage.todayHrv() }
    // Watch sensor: used when the strap is not connected.
    val watch = remember { WatchHr(ctx) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        watch.start()
        onDispose { watch.stop() }
    }

    LaunchedEffect(stage) {
        if (stage != 1) return@LaunchedEffect
        val strap = useStrap
        val total = 150
        val settle = 30
        var sec = 0
        val rr = ArrayList<Int>()
        val hrs = ArrayList<Int>()
        val job = launch { HrSensor.rr.collect { if (strap && sec >= settle) rr += it } }
        while (sec < total) {
            left = total - sec
            val v = if (strap) HrSensor.freshBpm() else watch.fresh()
            live = v
            if (sec >= settle) v?.let { hrs += it }
            delay(1000)
            sec++
        }
        job.cancel()
        val rest = if (hrs.isNotEmpty()) hrs.sorted().take((hrs.size / 2).coerceAtLeast(1)).average().toInt() else 0
        if (strap) {
            val clean = Physiology.cleanRr(rr)
            val rmssd = Physiology.rmssd(clean)
            val r2 = if (rest > 0) rest else if (clean.isNotEmpty()) (60000.0 / clean.average()).toInt() else 0
            if (rmssd > 0 && r2 > 0) {
                val rec = HrvRecord(System.currentTimeMillis(), rmssd, r2, Physiology.readiness(rmssd, r2, Storage.hrvHistory()))
                Storage.addHrv(rec); result = rec; Haptics.ready()
            }
        } else if (rest > 0) {
            // Watch only: resting pulse vs your norm (rmssd = 0 marks a watch test).
            val rec = HrvRecord(System.currentTimeMillis(), 0.0, rest, Physiology.readinessByRest(rest, Storage.hrvHistory()))
            Storage.addHrv(rec); result = rec; Haptics.ready()
        }
        stage = 2
    }

    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    ListScreen {
        when (stage) {
            0 -> {
                // Start buttons first — no scrolling needed.
                if (st == HrSensor.Status.CONNECTED) item {
                    ItemChip("▶ Начать тест с H10", "Точнее: вариабельность + пульс · ♥ ${bpm ?: "--"}", Colors.action) {
                        useStrap = true; stage = 1
                    }
                }
                item {
                    ItemChip(if (st == HrSensor.Status.CONNECTED) "Тест только часами" else "▶ Начать тест (часы)",
                        "Без ремня: по пульсу покоя", if (st == HrSensor.Status.CONNECTED) Colors.card else Colors.action) {
                        useStrap = false; stage = 1
                    }
                }
                if (hour >= 11) item { Line("Сейчас день: пульс выше утреннего. Для оценки готовности тест делают утром.", Colors.wait, 14) }
                if (today != null) item { ResultCard(today) }
                item { Line("Утром, сразу после пробуждения: лягте и спокойно дышите 2,5 минуты. Не разговаривайте.", Colors.dim, 14) }
            }
            1 -> {
                item { Line(fmtDuration(left), Color.White, 40, bold = true) }
                item { Line("♥ ${live ?: "--"} · ${if (useStrap) "H10" else "часы"}", Colors.zone[1], 20, bold = true) }
                item { Line(if (left > 120) "Успокойтесь…" else "Измеряю… лежите спокойно", Colors.dim, 16) }
                item { ItemChip("Отмена", null) { stage = 0 } }
            }
            else -> {
                item { ItemChip("Готово", null, Colors.action) { stage = 0 } }
                val r = result
                if (r != null) item { ResultCard(r) } else item { Line("Не хватило данных. Проверьте посадку и повторите.", Colors.wait, 15) }
            }
        }
        item {
            Line("Тест сравнивается с вашей нормой за 7 дней (нужно 3 теста). С ремнём — по вариабельности пульса, с часами — по пульсу покоя.", Colors.dim, 14)
        }
    }
}

@Composable
private fun ResultCard(r: HrvRecord) {
    val c = when (r.status) { 0 -> Colors.ready; 1 -> Colors.wait; 2 -> Colors.danger; else -> Colors.dim }
    Card {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Line(Physiology.READINESS_TEXT[r.status] ?: "", c, 16, bold = true)
            Line(if (r.rmssd > 0) "ВСР ${r.rmssd.toInt()} мс · покой ${r.restHr} уд/мин" else "Пульс покоя ${r.restHr} уд/мин (часы)", Color.White, 15)
        }
    }
}

// ======================= Profile =======================

@Composable
fun ProfileScreen() {
    val p by Storage.profile.collectAsState()
    var auto by remember { mutableStateOf(Storage.autoRestHr) }
    val bounds = Physiology.zoneBounds(p)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val font by Storage.fontScale.collectAsState()
    var bg by remember { mutableStateOf(Passive.enabled) }
    ListScreen {
        item { ListHeader { Text("Профиль", fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
        item { Stepper("Размер шрифта", "${(font * 100).toInt()}%") { d -> Storage.setFontScale(font + d * 0.05f) } }
        item {
            ItemChip("Фоновый сбор: " + if (bg) "вкл" else "выкл",
                if (bg) "Ночной пульс и шаги → телефон. Почти не тратит батарею" else "Нажмите, чтобы включить",
                if (bg) Color(0xFF1F4D33) else Colors.card) {
                bg = !bg; Passive.enabled = bg
                if (bg) Passive.register(ctx) else Passive.unregister(ctx)
            }
        }
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
        Text(label, fontSize = 15.sp, color = Colors.dim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundBtn("−", size = 34.dp) { onDelta(-1) }
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            RoundBtn("+", size = 34.dp) { onDelta(1) }
        }
    }
}
