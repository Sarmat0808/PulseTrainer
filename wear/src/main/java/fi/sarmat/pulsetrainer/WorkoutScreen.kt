package fi.sarmat.pulsetrainer

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    // No forced screen-on: the screen dims normally and, with Always-On Display, shows the low-power view.
    val amb by MainActivity.ambient.collectAsState()
    // The workout layout is sized for the round screen: fixed text scale so nothing is cut off.
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 1f)
    ) { if (amb) AmbientWorkout() else WorkoutContent(onSwitch) }
}

/**
 * Always-On view: black screen, thin grey/white text, no coloured ring, no animations.
 * Heart rate refreshes every 5 s (not every second), and the content shifts a few pixels
 * every minute so the OLED screen doesn't burn in.
 */
@Composable
private fun AmbientWorkout() {
    val tick by MainActivity.ambientTick.collectAsState()
    var snap by remember { mutableStateOf(WorkoutEngine.ui.value) }
    LaunchedEffect(Unit) { while (true) { snap = WorkoutEngine.ui.value; delay(5000) } }
    val s = snap
    val strap = HrSensor.bpm.value
    val hr = if (HrSensor.status.value == HrSensor.Status.CONNECTED && strap != null && s.hrFromStrap) strap else s.hr
    val shift = ((tick % 3) - 1) * 4
    val dim = Color(0xFFB8B8B8)
    Box(Modifier.fillMaxSize().background(Color.Black).padding(start = (14 + shift).dp, top = (12 + shift).dp, end = 14.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center) {
        // Same order as the bright screen, just thinner and grey: clock, workout time, pulse, two numbers.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(clockFmt.format(Date()), fontSize = 26.sp, color = dim, maxLines = 1, lineHeight = 28.sp)
            Text(fmtDuration(s.elapsedSec), fontSize = 32.sp, color = Color.White, maxLines = 1, lineHeight = 34.sp)
            if (s.prevSegs.isNotEmpty())
                Text(s.type.short + " " + fmtDuration(s.segElapsedSec), fontSize = 17.sp, color = dim, maxLines = 1, lineHeight = 19.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hr?.toString() ?: "--", fontSize = 64.sp, color = Color.White, lineHeight = 64.sp)
                val z = hr?.let { Physiology.zoneOf(it, s.bounds) } ?: 0
                if (z > 0) Text(" Z$z", fontSize = 24.sp, color = dim)
            }
            val c = cells(s).take(2)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                c.forEach { cell ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(cell.value, fontSize = 30.sp, color = Color.White, maxLines = 1, lineHeight = 31.sp)
                        Text(cell.label, fontSize = 15.sp, color = dim, maxLines = 1, lineHeight = 16.sp)
                    }
                }
            }
            if (s.coachUnseen || s.endAdvice != null) Text("▲ Тренер", fontSize = 16.sp, color = dim)
        }
    }
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
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (drag < -60f) onCoach() },
            ) { _, d -> drag += d }
        }
    ) {
        ZoneRing(hr, s.bounds)
        val c = cells(s)
        Column(
            Modifier.fillMaxSize().padding(start = 13.dp, end = 13.dp, top = 14.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 1) Time of day — on its own line at the top, big.
            Text(clockFmt.format(Date()), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, lineHeight = 25.sp)
            // 2) Whole workout time — the biggest number after the pulse, with the sport icon.
            val paused = s.paused || s.autoPaused
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SportIcon(s.type, 22.dp, if (paused) Colors.wait else Colors.dim)
                Text(
                    fmtDuration(s.elapsedSec) + if (paused) " ⏸" else "",
                    fontSize = 31.sp, fontWeight = FontWeight.Bold, color = if (paused) Colors.wait else Color.White,
                    maxLines = 1, lineHeight = 32.sp
                )
            }
            // 2b) Several exercises in one workout: the current exercise and its own time.
            if (s.prevSegs.isNotEmpty())
                Text("${s.type.short} ${fmtDuration(s.segElapsedSec)}", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    color = Colors.dim, maxLines = 1, lineHeight = 18.sp)
            // 3) Pulse in the middle, a number on each side (the round screen is widest here).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SideMetric(c.getOrNull(0), Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // long press on the pulse = switch exercise
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { Haptics.tick(); onSwitch() }) }
                ) {
                    Text(hr?.toString() ?: "--", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = zc, lineHeight = 56.sp, maxLines = 1)
                    Text(
                        (if (zone > 0) "Z$zone" else "♥") + " · " + (if (hr == null) "нет" else if (s.hrFromStrap) "H10" else "часы"),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, maxLines = 1,
                        color = if (hr != null && !s.hrFromStrap) Colors.wait else zc
                    )
                }
                SideMetric(c.getOrNull(1), Modifier.weight(1f))
            }
            // 4) Bottom row: the next two numbers, or the interval timer / treadmill speed.
            when {
                s.type.mode == Mode.ROUNDS -> RoundsBlock(s)
                s.type.treadmill -> TreadmillBlock(s)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    c.drop(2).take(2).forEach { Metric(it.value, it.label) }
                }
            }
            // Bottom: orange plate = time to finish; green plate = a new tip (swipe up to read);
            // strength shows nothing else here, cardio keeps one short zone hint.
            if (s.endAdvice != null) CoachBadge("▲ Пора заканчивать", Color(0xFFF2994A), onCoach)
            else if (s.coachUnseen) CoachBadge("▲ Совет", Colors.ready, onCoach)
            else if (s.type.mode != Mode.SETS) s.assist?.takeIf { !it.startsWith("Тренер") }?.let { line ->
                Text(
                    line, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center, lineHeight = 17.sp,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
private fun CoachBadge(label: String, color: Color, onClick: () -> Unit) {
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "coach")
    val a by tr.animateFloat(0.55f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(
        Modifier.padding(top = 3.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(color.copy(alpha = a)).pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp, vertical = 3.dp)
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
    }
}

/** One line of the coach panel: big, bold, easy to read on the move. */
@Composable
private fun Big(text: String, color: Color = Color.White, size: Int = 19, bold: Boolean = true) {
    Text(text, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = color,
        textAlign = TextAlign.Center, lineHeight = (size + 3).sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
}

/**
 * Coach panel (swipe up): the advice in a few big words, then the key numbers.
 * Short explanations are folded under «Справка».
 */
@Composable
private fun CoachPanel(s: WorkoutEngine.Ui, hr: Int?, onClose: () -> Unit) {
    val b = s.bounds
    var help by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ListScreen {
            item { Big("Тренер", Colors.wait, 20) }
            val last = s.coachLog.lastOrNull()
            item {
                if (last != null) Big(last.short, if (last.level == 2) Colors.wait else Color.White, 22)
                else Big("Всё хорошо ✓", Colors.ready, 22)
            }
            if (last != null) item { Big(last.full, Colors.dim, 16, bold = false) }
            hr?.let { h -> item { Big("♥ $h · Z${Physiology.zoneOf(h, b)}", Colors.zone[Physiology.zoneOf(h, b)], 22) } }
            item { Big("Z2: ${b[1]}–${b[2]}", Colors.zone[2]) }
            if (s.type.mode == Mode.CARDIO) item { Big("В Z2–3: ${s.z23Min} мин") }
            item { Big("${fmtDuration(s.elapsedSec)} · ${s.kcal} ккал") }
            if (s.prevSegs.isNotEmpty()) {
                s.prevSegs.forEach { (t, sec) -> item { Big("${t.short} ${fmtDuration(sec)}", Colors.dim, 17) } }
                item { Big("${s.type.short} ${fmtDuration(s.segElapsedSec)} ← сейчас", Color.White, 17) }
            }
            if (s.type.mode == Mode.SETS) item { Big("Z4–5: ${(s.segZoneSec[4] + s.segZoneSec[5]) / 60} мин · ср. ♥ ${s.avgHr}") }
            if (s.distanceM > 0) item { Big("${fmtKm(s.distanceM)} км") }
            if (s.coachLog.size > 1) {
                item { Big("Ранее", Colors.dim, 16) }
                s.coachLog.dropLast(1).reversed().take(5).forEach { m ->
                    item { Big(clockFmt.format(Date(m.time)) + " " + m.short, Colors.dim, 16, bold = false) }
                }
            }
            item { WideBtn(if (help) "Скрыть справку" else "Справка", Colors.card) { help = !help } }
            if (help) {
                item { Big("Оранжевая плашка внизу — пора заканчивать. Зелёная — новый совет.", Colors.dim, 16, bold = false) }
                item { Big("Z2 — говорите фразами. Лучшее для сердца.", Colors.dim, 16, bold = false) }
                item { Big("Z4–5 — тяжело, недолго.", Colors.dim, 16, bold = false) }
            }
            item { WideBtn("Закрыть", Colors.action) { onClose() } }
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
    val kcal = if (s.prevSegs.isEmpty()) Cell("${s.kcal}", "ккал") else Cell("${s.segKcal}", "ккал упр")
    return when {
        // Sets found from the pulse alone are unreliable, so strength shows what is measured exactly.
        t.mode == Mode.SETS -> listOf(
            kcal,
            Cell("${s.trimp}", "нагрузка"),
            Cell("${s.avgHr}", "ср. пульс"),
            Cell("${(s.segZoneSec[4] + s.segZoneSec[5]) / 60}", "мин Z4–5"),
        )
        // Treadmill: distance and calories beside the pulse; speed buttons go underneath.
        t.treadmill -> listOf(km, kcal, Cell("${s.z23Min}", "мин Z2–3"), Cell("${s.avgHr}", "ср. пульс"))
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
private fun RoundsBlock(s: WorkoutEngine.Ui) {
    val work = s.phase == WorkoutEngine.Phase.WORK
    val (title, color) = when {
        s.intervalsDone -> "ГОТОВО ✓" to Colors.ready
        s.prepping -> "ГОТОВЬТЕСЬ" to Colors.wait
        s.betweenCycles -> "ОТДЫХ" to Colors.ready
        work -> "РАБОТА" to Colors.danger
        else -> "ОТДЫХ" to Colors.ready
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        if (!s.intervalsDone) Text(fmtDuration(s.roundLeft.coerceAtLeast(0)), fontSize = 30.sp, fontWeight = FontWeight.Bold,
            color = Color.White, lineHeight = 31.sp, maxLines = 1)
    }
    Text(
        "Раунд ${s.roundInCycle}/${s.roundsPerCycle}" + if (s.cycles > 1) " · цикл ${s.cycleNo}/${s.cycles}" else "",
        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Colors.dim, lineHeight = 17.sp, maxLines = 1
    )
}

@Composable
private fun TreadmillBlock(s: WorkoutEngine.Ui) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 2.dp)) {
        RoundBtn("−", size = 38.dp, textSize = 22) { WorkoutEngine.adjustTreadSpeed(-0.5) }
        Metric("%.1f".format(s.treadSpeed), "км/ч")
        RoundBtn("+", size = 38.dp, textSize = 22) { WorkoutEngine.adjustTreadSpeed(0.5) }
    }
}

/** Bottom-row number: big value, short label under it. */
@Composable
private fun Metric(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp)) {
        Text(value, fontSize = if (value.length > 5) 23.sp else 28.sp, fontWeight = FontWeight.Bold, color = Color.White,
            lineHeight = 29.sp, maxLines = 1)
        Text(unit, fontSize = 14.sp, color = Colors.dim, lineHeight = 15.sp, maxLines = 1)
    }
}

/** Number beside the pulse: shrinks a little for long values so it never touches the ring. */
@Composable
private fun SideMetric(c: Cell?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (c == null) return@Column
        val size = when { c.value.length >= 5 -> 18; c.value.length == 4 -> 21; else -> 25 }
        Text(c.value, fontSize = size.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = (size + 1).sp, maxLines = 1)
        Text(c.label, fontSize = 13.sp, color = Colors.dim, lineHeight = 14.sp, maxLines = 1, textAlign = TextAlign.Center)
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
