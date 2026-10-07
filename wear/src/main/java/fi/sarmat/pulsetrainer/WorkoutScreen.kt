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

    // Bottom (back) button = open the menu, never leave the workout.
    BackHandler {
        scope.launch { pager.animateScrollToPage(if (pager.currentPage == 1) 0 else 1) }
    }
    LaunchedEffect(s.type) { if (pager.currentPage != 0) pager.scrollToPage(0) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> MainPage(s, hr, onSwitch)
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
    }
}

@Composable
private fun MainPage(s: WorkoutEngine.Ui, hr: Int?, onSwitch: () -> Unit) {
    val zone = hr?.let { Physiology.zoneOf(it, s.bounds) } ?: 0
    val zc = Colors.zone[zone]
    Box(Modifier.fillMaxSize()) {
        ZoneRing(hr, s.bounds)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Time of day · sport icon · workout time
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(clockFmt.format(Date()), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                SportIcon(s.type, 16.dp, Colors.dim)
                Text(
                    fmtDuration(s.elapsedSec) + when { s.paused -> " ⏸"; s.autoPaused -> " авто⏸"; else -> "" },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (s.paused || s.autoPaused) Colors.wait else Colors.dim, maxLines = 1
                )
            }
            // Big heart rate — long press = switch exercise
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { Haptics.tick(); onSwitch() }) }
            ) {
                Text(hr?.toString() ?: "--", fontSize = 50.sp, fontWeight = FontWeight.Bold, color = zc, lineHeight = 52.sp)
                Column(Modifier.padding(start = 4.dp)) {
                    Text("♥ " + if (zone > 0) "З$zone" else "", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = zc)
                    Text(if (hr == null) "нет пульса" else if (s.hrFromStrap) "H10" else "часы", fontSize = 13.sp,
                        color = if (s.hrFromStrap) Colors.dim else Colors.wait)
                }
            }
            when {
                s.type.mode == Mode.ROUNDS -> RoundsBlock(s)
                s.type.treadmill -> TreadmillBlock(s)
                else -> Grid(cells(s))
            }
            // Quiet coach line
            val line = s.assist
            if (line != null) Text(
                line, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 15.sp,
                color = when (s.assistLevel) { 1 -> Colors.ready; 2 -> Colors.wait; else -> Colors.dim },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp)
            )
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
    val kcal = Cell("${s.kcal}", "ккал")
    return when {
        t.mode == Mode.SETS -> listOf(
            Cell("${s.setsDone}", "подходов"),
            when {
                s.warmup -> Cell(fmtDuration(s.phaseSec), "разминка")
                s.phase == WorkoutEngine.Phase.WORK -> Cell(fmtDuration(s.phaseSec), "подход")
                else -> Cell(fmtDuration(s.phaseSec), "отдых")
            },
            if (t.repCount) Cell("${s.reps}", "повт.") else kcal,
            Cell(s.lastHrr60?.let { "−$it" } ?: "—", "восст./мин"),
        )
        t == WorkoutType.STAIRS_HOME -> listOf(floors, up, Cell("${s.vSpeed}", "м/мин ↑"), Cell("${s.steps}", "шагов"))
        t == WorkoutType.STAIRS_OUTDOOR -> listOf(floors, up, Cell("${s.vSpeed}", "м/мин ↑"), km)
        t == WorkoutType.STAIRS -> listOf(floors, cad, kcal, Cell("${s.z23Min}", "мин З2–3"))
        t == WorkoutType.BIKE_OUTDOOR -> listOf(km, Cell("%.1f".format(s.speedKmh), "км/ч"), Cell("%.1f".format(s.avgSpeedKmh), "ср. км/ч"), up)
        t == WorkoutType.RUN -> listOf(km, pace, avgPace, cad)
        t == WorkoutType.HIKING || t == WorkoutType.SKIING -> listOf(km, pace, up, Cell("${s.descentM}", "м ↓"))
        t.gps -> listOf(km, pace, cad, up)
        else -> listOf(Cell("${s.z23Min}", "мин З2–3"), kcal, Cell("${s.avgHr}", "ср. пульс"), Cell("${s.trimp}", "нагрузка"))
    }
}

@Composable
private fun Grid(c: List<Cell>) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        c.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { Metric(it.value, it.label) }
            }
        }
    }
}

@Composable
private fun RoundsBlock(s: WorkoutEngine.Ui) {
    val work = s.phase == WorkoutEngine.Phase.WORK
    Text(
        "Раунд ${s.roundNo} · " + if (work) "РАБОТА" else "ОТДЫХ",
        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (work) Colors.danger else Colors.ready
    )
    Text(fmtDuration(s.roundLeft.coerceAtLeast(0)), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text("${s.kcal} ккал · ср. ${s.avgHr}", fontSize = 14.sp, color = Colors.dim)
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
        Metric("${s.kcal}", "ккал")
    }
}

@Composable
private fun Metric(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 23.sp, maxLines = 1)
        Text(unit, fontSize = 12.sp, color = Colors.dim, lineHeight = 13.sp, maxLines = 1)
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
