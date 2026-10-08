package fi.sarmat.pulsetrainer

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import fi.sarmat.pulsetrainer.core.Mode
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.WorkoutType
import androidx.compose.foundation.layout.width
import fi.sarmat.pulsetrainer.core.fmtDuration
import fi.sarmat.pulsetrainer.core.fmtKm
import fi.sarmat.pulsetrainer.core.fmtPace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

/** Keeps the display on while this composable is shown. */
@Composable
fun KeepScreenOn() {
    val act = LocalContext.current as? Activity
    DisposableEffect(act) {
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkoutScreen(onSwitch: () -> Unit) {
    KeepScreenOn()
    // The workout layout is sized for the round screen: fixed text scale so nothing is cut off.
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 1f)
    ) { WorkoutContent(onSwitch) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkoutContent(onSwitch: () -> Unit) {
    val s by WorkoutEngine.ui.collectAsState()
    // Show the strap value the moment it arrives (not only on the 1-s tick).
    val strapBpm by HrSensor.bpm.collectAsState()
    val strapStatus by HrSensor.status.collectAsState()
    val hr = if (strapStatus == HrSensor.Status.CONNECTED && strapBpm != null && s.hrFromStrap) strapBpm else s.hr

    val pages = if (s.type.gps) 3 else 2
    val pager = rememberPagerState(pageCount = { pages })
    val scope = rememberCoroutineScope()

    var coachOpen by remember { mutableStateOf(false) }
    // Bottom (back) button = close the coach panel / open the menu, never leave the workout.
    BackHandler {
        if (coachOpen) coachOpen = false
        else scope.launch { pager.animateScrollToPage(if (pager.currentPage == 1) 0 else 1) }
    }
    LaunchedEffect(coachOpen) { if (coachOpen) WorkoutEngine.markCoachSeen() }
    LaunchedEffect(s.type) { if (pager.currentPage != 0) pager.scrollToPage(0) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !coachOpen) { page ->
            when (page) {
                0 -> MainPage(s, hr, onSwitch) { coachOpen = true }
                1 -> ControlsPage(s, onSwitch) { scope.launch { pager.animateScrollToPage(0) } }
                else -> MapPage(s)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(pages) { i ->
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (pager.currentPage == i) Color.White else Color.DarkGray))
            }
        }
        if (coachOpen) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) { CoachPanel(s, hr) { coachOpen = false } }
    }
}

@Composable
private fun MainPage(s: WorkoutEngine.Ui, hr: Int?, onSwitch: () -> Unit, onCoach: () -> Unit) {
    val zone = hr?.let { Physiology.zoneOf(it, s.bounds) } ?: 0
    val zc = Colors.zone[zone]
    var drag by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Swipe up from anywhere = the coach panel.
            androidx.compose.foundation.gestures.detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (drag < -60f) onCoach() },
            ) { _, d -> drag += d }
        }
    ) {
        ZoneRing(hr, s.bounds)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Time of day · whole workout time — big and clear
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(clockFmt.format(Date()), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                Text(
                    fmtDuration(s.elapsedSec) + when { s.paused -> " ⏸"; s.autoPaused -> " ⏸"; else -> "" },
                    fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (s.paused || s.autoPaused) Colors.wait else Colors.dim, maxLines = 1
                )
            }
            // Exercises of this workout: previous one and the current one with their own times.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.prevSegs.lastOrNull()?.let { (t, sec) ->
                    SportIcon(t, 15.dp, Colors.dim)
                    Text(fmtDuration(sec), fontSize = 15.sp, color = Colors.dim, maxLines = 1)
                    Text(" →", fontSize = 15.sp, color = Colors.dim)
                }
                SportIcon(s.type, 16.dp, Color.White)
                Text(
                    if (s.prevSegs.isEmpty()) s.type.short else fmtDuration(s.segElapsedSec),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1
                )
            }
            // Heart rate — long press = switch exercise
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { Haptics.tick(); onSwitch() }) }
            ) {
                Text(hr?.toString() ?: "--", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = zc, lineHeight = 44.sp)
                Column(Modifier.padding(start = 5.dp)) {
                    Text(if (zone > 0) "Z$zone" else "♥", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = zc, lineHeight = 18.sp)
                    Text(if (hr == null) "нет" else if (s.hrFromStrap) "H10" else "часы", fontSize = 13.sp, lineHeight = 14.sp,
                        color = if (s.hrFromStrap) Colors.dim else Colors.wait)
                }
            }
            when {
                s.type.mode == Mode.ROUNDS -> RoundsBlock(s)
                s.type.treadmill -> TreadmillBlock(s)
                else -> Grid(cells(s))
            }
            // Bottom: a new coach message lights the badge; otherwise one short helper line.
            if (s.coachUnseen) CoachBadge(onCoach)
            else s.assist?.let { line ->
                Text(
                    line, fontSize = 14.sp, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 16.sp,
                    color = when (s.assistLevel) { 1 -> Colors.ready; 2 -> Colors.wait; else -> Colors.dim },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 1.dp)
                        .pointerInput(Unit) { detectTapGestures(onTap = { onCoach() }) }
                )
            }
        }
    }
}

/** "Тренер ▲" — calm pulsing badge: there's a new tip, swipe up (or tap) to read it. */
@Composable
private fun CoachBadge(onClick: () -> Unit) {
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "coach")
    val a by tr.animateFloat(0.55f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(
        Modifier.padding(top = 3.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(Colors.wait.copy(alpha = a)).pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp, vertical = 3.dp)
    ) {
        Text("▲ Тренер: новый совет", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
    }
}

/**
 * Coach panel (swipe up): the full advice, what the numbers mean right now and what to do —
 * everything worth knowing during the workout, in one scrolling list.
 */
@Composable
private fun CoachPanel(s: WorkoutEngine.Ui, hr: Int?, onClose: () -> Unit) {
    val b = s.bounds
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ListScreen {
            item { Text("Тренер", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Colors.wait) }
            val last = s.coachLog.lastOrNull()
            if (last != null) item {
                Text(last.full, fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 8.dp))
            } else item { Line("Пока всё идёт хорошо — советов нет", Colors.ready, 15) }
            // ---- right now ----
            item { Line("Сейчас", Colors.dim, 14, bold = true) }
            hr?.let { h -> item { Line("Пульс $h · Z${Physiology.zoneOf(h, b)} · макс. ${s.maxHr}", Color.White, 15) } }
            item { Line("Зона 2 (сердце, жир): ${b[1]}–${b[2]}", Colors.zone[2], 15) }
            item { Line("Всего ${fmtDuration(s.elapsedSec)} · ${s.kcal} ккал · ср. пульс ${s.avgHr}", Color.White, 15) }
            if (s.prevSegs.isNotEmpty()) item {
                Line((s.prevSegs.map { "${it.first.short} ${fmtDuration(it.second)}" } + "${s.type.short} ${fmtDuration(s.segElapsedSec)} (сейчас, ${s.segKcal} ккал)")
                    .joinToString(" → "), Colors.dim, 14)
            }
            if (s.type.mode == Mode.SETS || s.hrrList.isNotEmpty()) {
                item { Line("Подходов всего: ${s.setsTotal}", Color.White, 15) }
                if (s.hrrList.isNotEmpty()) {
                    val first = s.hrrList.take(3).average().toInt()
                    val now = s.hrrList.takeLast(3).average().toInt()
                    item {
                        Line("Спад пульса за 1 мин отдыха: в начале −$first, сейчас −$now" +
                            if (s.hrrList.size >= 6 && now < first * 0.6) " — устали" else if (now >= 20) " — отлично" else "",
                            if (s.hrrList.size >= 6 && now < first * 0.6) Colors.wait else Color.White, 15)
                    }
                }
                item { Line("Следующий подход: пульс ниже ${s.readyHr} и отдых не меньше ${s.minRest} с", Colors.dim, 14) }
            }
            if (s.type.mode == Mode.CARDIO) item { Line("Минут в Z2–3: ${s.z23Min} · нагрузка ${s.trimp}", Color.White, 15) }
            if (s.distanceM > 0) item { Line("Дистанция ${fmtKm(s.distanceM)} км", Color.White, 15) }
            // ---- how to read ----
            item { Line("Как понимать", Colors.dim, 14, bold = true) }
            item { Line("Спад пульса — на сколько ударов пульс падает за первую минуту отдыха. 20+ отлично, 12–20 норма, меньше 12 или вдвое ниже начала — усталость.", Colors.dim, 14) }
            item { Line("Z1–Z5 — зоны пульса. Z2 — можно говорить фразами: лучшее для сердца и восстановления. Z4–5 — коротко, это тяжело.", Colors.dim, 14) }
            if (s.coachLog.size > 1) {
                item { Line("Ранее", Colors.dim, 14, bold = true) }
                s.coachLog.dropLast(1).reversed().forEach { m ->
                    item { Line(clockFmt.format(Date(m.time)) + " · " + m.full, Colors.dim, 14) }
                }
            }
            item { WideBtn("Закрыть", Colors.card) { onClose() } }
        }
    }
}

private data class Cell(val value: String, val label: String)

/** The 4 numbers that matter most for each kind of workout (what Garmin, Polar and Suunto show by default). */
private fun cells(s: WorkoutEngine.Ui): List<Cell> {
    val t = s.type
    val km = Cell(fmtKm(s.distanceM), "км")
    val pace = Cell(fmtPace(s.paceSecPerKm), "темп")
    val avgPace = Cell(fmtPace(s.avgPaceSecPerKm), "ср. темп")
    val up = Cell("${s.ascentM}", "м ↑")
    val floors = Cell("${s.floors}", "этажей")
    val cad = Cell(if (s.cadence > 0) "${s.cadence}" else "—", "шаг/мин")
    val kcal = if (s.prevSegs.isEmpty()) Cell("${s.kcal}", "ккал") else Cell("${s.segKcal}", "ккал упр.")
    return when {
        t.mode == Mode.SETS -> listOf(
            Cell("${s.setsDone}", "подходов"),
            when {
                s.warmup -> Cell(fmtDuration(s.phaseSec), "разминка")
                s.phase == WorkoutEngine.Phase.WORK -> Cell(fmtDuration(s.phaseSec), "подход")
                else -> Cell(fmtDuration(s.phaseSec), "отдых")
            },
            if (t.repCount) Cell("${s.reps}", "повт.") else kcal,
            Cell(s.lastHrr60?.let { "−$it" } ?: "—", "спад пульса"),
        )
        t == WorkoutType.STAIRS_HOME -> listOf(floors, up, Cell("${s.vSpeed}", "м/мин ↑"), Cell("${s.steps}", "шагов"))
        t == WorkoutType.STAIRS_OUTDOOR -> listOf(floors, up, Cell("${s.vSpeed}", "м/мин ↑"), km)
        t == WorkoutType.STAIRS -> listOf(floors, cad, kcal, Cell("${s.z23Min}", "мин Z2–3"))
        t == WorkoutType.BIKE_OUTDOOR -> listOf(km, Cell("%.1f".format(s.speedKmh), "км/ч"), Cell("%.1f".format(s.avgSpeedKmh), "ср. км/ч"), up)
        t == WorkoutType.RUN -> listOf(km, pace, avgPace, cad)
        t == WorkoutType.HIKING || t == WorkoutType.SKIING -> listOf(km, pace, up, Cell("${s.descentM}", "м ↓"))
        t.gps -> listOf(km, pace, cad, up)
        else -> listOf(Cell("${s.z23Min}", "мин Z2–3"), kcal, Cell("${s.avgHr}", "ср. пульс"), Cell("${s.trimp}", "нагрузка"))
    }
}

@Composable
private fun Grid(c: List<Cell>) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        c.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Metric(it.value, it.label) }
            }
        }
    }
}

@Composable
private fun RoundsBlock(s: WorkoutEngine.Ui) {
    val work = s.phase == WorkoutEngine.Phase.WORK
    val (title, color) = when {
        s.intervalsDone -> "ГОТОВО ✓" to Colors.ready
        s.prepping -> "ПРИГОТОВЬТЕСЬ" to Colors.wait
        s.betweenCycles -> "ОТДЫХ МЕЖДУ ЦИКЛАМИ" to Colors.ready
        work -> "РАБОТА" to Colors.danger
        else -> "ОТДЫХ" to Colors.ready
    }
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    if (!s.intervalsDone) Text(fmtDuration(s.roundLeft.coerceAtLeast(0)), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 36.sp)
    Text(
        "Раунд ${s.roundInCycle}/${s.roundsPerCycle}" + if (s.cycles > 1) " · цикл ${s.cycleNo}/${s.cycles}" else "",
        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White
    )
    Text("${s.kcal} ккал · ср. ${s.avgHr}", fontSize = 13.sp, color = Colors.dim)
}

@Composable
private fun TreadmillBlock(s: WorkoutEngine.Ui) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundBtn("−", size = 32.dp, textSize = 20) { WorkoutEngine.adjustTreadSpeed(-0.5) }
        Metric("%.1f".format(s.treadSpeed), "км/ч")
        RoundBtn("+", size = 32.dp, textSize = 20) { WorkoutEngine.adjustTreadSpeed(0.5) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Metric(fmtKm(s.distanceM), "км")
        if (s.prevSegs.isEmpty()) Metric("${s.kcal}", "ккал") else Metric("${s.segKcal}", "ккал упр.")
    }
}

@Composable
private fun Metric(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp)) {
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 24.sp, maxLines = 1)
        Text(unit, fontSize = 13.sp, color = Colors.dim, lineHeight = 14.sp, maxLines = 1)
    }
}

@Composable
private fun ControlsPage(s: WorkoutEngine.Ui, onSwitch: () -> Unit, back: () -> Unit) {
    var confirmFinish by remember { mutableStateOf(false) }
    var askCancel by remember { mutableStateOf(false) }
    LaunchedEffect(confirmFinish) { if (confirmFinish) { delay(4000); confirmFinish = false } }
    val strap by HrSensor.status.collectAsState()
    val batt by HrSensor.battery.collectAsState()

    if (askCancel) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            Text("Отменить тренировку без сохранения?", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = Color.White, textAlign = TextAlign.Center)
            Text("Она не попадёт в историю и Samsung Health", fontSize = 14.sp, color = Colors.dim, textAlign = TextAlign.Center)
            WideBtn("Да, отменить", Colors.danger) { askCancel = false; WorkoutEngine.discard() }
            WideBtn("Нет", Colors.card) { askCancel = false }
        }
        return
    }

    ListScreen {
        item { Line(s.segmentTitles.joinToString(" → ") + " · " + clockFmt.format(Date()), Colors.dim, 14) }
        item { WideBtn("↻ Сменить упражнение", Colors.action) { onSwitch() } }
        if (s.type.mode == Mode.SETS) item {
            WideBtn(if (s.phase == WorkoutEngine.Phase.WORK) "Отметить конец подхода" else "Отметить начало подхода", Colors.card) {
                WorkoutEngine.nextPhase(); back()
            }
        }
        if (s.type.gps) {
            item {
                WideBtn("Автопауза: " + if (s.autoPauseOn) "вкл" else "выкл", if (s.autoPauseOn) Color(0xFF1F4D33) else Colors.card) {
                    WorkoutEngine.setAutoPause(!s.autoPauseOn)
                }
            }
            item {
                WideBtn((if (s.type.lapM >= 5000) "Сигнал каждые 5 км: " else "Сигнал каждый км: ") + if (s.kmAlertOn) "вкл" else "выкл",
                    if (s.kmAlertOn) Color(0xFF1F4D33) else Colors.card) { WorkoutEngine.setKmAlert(!s.kmAlertOn) }
            }
        }
        item {
            WideBtn(if (s.paused) "▶ Продолжить" else "⏸ Пауза", Colors.card) {
                WorkoutEngine.setPaused(!s.paused); back()
            }
        }
        item {
            WideBtn(if (confirmFinish) "Сохранить и завершить?" else "■ Завершить", Colors.ready) {
                if (confirmFinish) WorkoutEngine.finish() else confirmFinish = true
            }
        }
        item { WideBtn("✕ Отменить без сохранения", Colors.danger) { askCancel = true } }
        item {
            Line(
                when (strap) {
                    HrSensor.Status.CONNECTED -> "Датчик ✓" + (batt?.let { " · $it%" } ?: "")
                    HrSensor.Status.CONNECTING, HrSensor.Status.RECONNECTING -> "Подключаю датчик…"
                    else -> if (Storage.sensorAddress() == null) "Датчик не выбран — пульс с часов" else "Датчик не найден — пульс с часов"
                },
                if (strap == HrSensor.Status.CONNECTED) Colors.ready else Colors.wait, 14
            )
        }
    }
}

@Composable
private fun MapPage(s: WorkoutEngine.Ui) {
    val pts by WorkoutEngine.track.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().padding(36.dp)) {
            if (pts.size < 2) return@Canvas
            val lat0 = pts.minOf { it.lat }; val lat1 = pts.maxOf { it.lat }
            val lon0 = pts.minOf { it.lon }; val lon1 = pts.maxOf { it.lon }
            val k = cos(Math.toRadians((lat0 + lat1) / 2))
            val w = ((lon1 - lon0) * k).coerceAtLeast(1e-5)
            val h = (lat1 - lat0).coerceAtLeast(1e-5)
            val scale = minOf(size.width / w, size.height / h).toFloat()
            val ox = (size.width - (w * scale).toFloat()) / 2
            val oy = (size.height - (h * scale).toFloat()) / 2
            fun p(lat: Double, lon: Double) = Offset(
                ox + ((lon - lon0) * k * scale).toFloat(),
                oy + ((lat1 - lat) * scale).toFloat()
            )
            val path = Path()
            pts.forEachIndexed { i, g -> val o = p(g.lat, g.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, Colors.action, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Colors.ready, 5.dp.toPx(), p(pts.first().lat, pts.first().lon))
            drawCircle(Color.White, 6.dp.toPx(), p(pts.last().lat, pts.last().lon))
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(clockFmt.format(Date()), fontSize = 15.sp, color = Colors.dim)
            Text("${fmtKm(s.distanceM)} км · ${fmtPace(s.paceSecPerKm)}", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
            if (pts.size < 2) Text(if (s.gpsFix) "Начните движение" else "Поиск GPS…", fontSize = 14.sp, color = Colors.wait)
        }
        Text("Круг ${s.lapNo} · север вверху", fontSize = 14.sp, color = Colors.dim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}
