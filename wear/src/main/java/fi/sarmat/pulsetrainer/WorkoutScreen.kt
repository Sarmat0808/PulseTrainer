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
import fi.sarmat.pulsetrainer.core.fmtDuration
import fi.sarmat.pulsetrainer.core.fmtKm
import fi.sarmat.pulsetrainer.core.fmtPace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkoutScreen(onSwitch: () -> Unit) {
    KeepScreenOn()
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
    // After switching exercise, return to the main page.
    LaunchedEffect(s.type) { if (pager.currentPage != 0) pager.scrollToPage(0) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> MainPage(s, hr, onSwitch)
                1 -> ControlsPage(s, onSwitch) { scope.launch { pager.animateScrollToPage(0) } }
                else -> MapPage(s)
            }
        }
        // Page dots
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
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
            Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Top line: time + exercise
            Text(
                "${fmtDuration(s.elapsedSec)} · ${s.type.short}" + if (s.paused) " ⏸" else "",
                fontSize = 13.sp, color = if (s.paused) Colors.wait else Colors.dim, maxLines = 1
            )
            // Big heart rate — long press = switch exercise
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { Haptics.tick(); onSwitch() }) }
            ) {
                Text(hr?.toString() ?: "--", fontSize = 58.sp, fontWeight = FontWeight.Bold, color = zc)
                Column(Modifier.padding(start = 4.dp)) {
                    Text("♥", fontSize = 16.sp, color = zc)
                    Text(if (s.hrFromStrap) "H10" else "часы", fontSize = 10.sp, color = if (s.hrFromStrap) Colors.dim else Colors.wait)
                }
            }
            Text(Physiology.ZONE_NAMES[zone], fontSize = 13.sp, color = zc, maxLines = 1)
            Spacer(4)
            when (s.type.mode) {
                Mode.SETS -> SetsBlock(s, hr)
                Mode.ROUNDS -> RoundsBlock(s)
                Mode.CARDIO -> CardioBlock(s)
            }
        }
    }
}

@Composable
private fun SetsBlock(s: WorkoutEngine.Ui, hr: Int?) {
    if (s.phase == WorkoutEngine.Phase.WORK) {
        Text("Подход ${s.setNo} · ${fmtDuration(s.phaseSec)}", fontSize = 14.sp, color = Color.White)
        if (s.type.repCount) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundBtn("−", size = 30.dp) { WorkoutEngine.adjustReps(-1) }
                Text("${s.reps}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                RoundBtn("+", size = 30.dp) { WorkoutEngine.adjustReps(1) }
            }
        }
        Spacer(2)
        WideBtn("Подход ✓", Colors.action, Modifier.padding(horizontal = 12.dp), height = 38.dp) { WorkoutEngine.nextPhase() }
    } else {
        val color = if (s.restReady) Colors.ready else Colors.wait
        Text("Отдых ${fmtDuration(s.phaseSec)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        val need = buildString {
            if (s.phaseSec < s.minRest) append("ещё ${s.minRest - s.phaseSec} с")
            if (hr != null && hr > s.readyHr) { if (isNotEmpty()) append(" · "); append("пульс ≤ ${s.readyHr}") }
        }
        Text(
            s.advice ?: if (s.restReady) "✓ Можно начинать" + (s.lastHrr60?.let { " · −$it/мин" } ?: "")
            else "Ждём: $need" + (s.lastHrr60?.let { " · −$it/мин" } ?: ""),
            fontSize = 11.sp, color = if (s.advice != null) Colors.wait else Colors.dim, textAlign = TextAlign.Center, maxLines = 2
        )
        Spacer(2)
        WideBtn("▶ Подход ${s.setNo + 1}", if (s.restReady) Colors.ready else Colors.card, Modifier.padding(horizontal = 12.dp), height = 38.dp) {
            WorkoutEngine.nextPhase()
        }
    }
}

@Composable
private fun RoundsBlock(s: WorkoutEngine.Ui) {
    val work = s.phase == WorkoutEngine.Phase.WORK
    Text(
        "Раунд ${s.roundNo} · " + if (work) "РАБОТА" else "ОТДЫХ",
        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (work) Colors.danger else Colors.ready
    )
    Text(fmtDuration(s.roundLeft.coerceAtLeast(0)), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(
        (s.lastHrr60?.let { "Восст. за отдых: −$it уд/мин · " } ?: "") + "${s.kcal} ккал",
        fontSize = 11.sp, color = Colors.dim, maxLines = 1
    )
    WideBtn(if (work) "⏭ К отдыху" else "⏭ К раунду", Colors.card, Modifier.padding(horizontal = 18.dp), height = 32.dp) {
        WorkoutEngine.nextPhase()
    }
}

@Composable
private fun CardioBlock(s: WorkoutEngine.Ui) {
    if (s.type.gps) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Metric(fmtKm(s.distanceM), "км")
            Metric(fmtPace(s.paceSecPerKm), "/км")
        }
        Text(
            (if (!s.gpsFix) "Поиск GPS… · " else "") + "${s.kcal} ккал · ср. ${s.avgHr}",
            fontSize = 11.sp, color = if (!s.gpsFix) Colors.wait else Colors.dim, maxLines = 1
        )
    } else if (s.type.treadmill) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundBtn("−", size = 30.dp) { WorkoutEngine.adjustTreadSpeed(-0.5) }
            Metric("%.1f".format(s.treadSpeed), "км/ч")
            RoundBtn("+", size = 30.dp) { WorkoutEngine.adjustTreadSpeed(0.5) }
        }
        Text("${fmtKm(s.distanceM)} км · ${s.kcal} ккал · ср. ${s.avgHr}", fontSize = 11.sp, color = Colors.dim, maxLines = 1)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Metric("${s.kcal}", "ккал")
            Metric("${s.avgHr}", "ср. пульс")
        }
        // Time in the "heart" zones Z2+Z3 of this exercise — the main goal of steady cardio.
        val heart = s.segZoneSec[2] + s.segZoneSec[3]
        Text("З2–З3: ${fmtDuration(heart)}", fontSize = 11.sp, color = Colors.zone[2], maxLines = 1)
    }
    ZoneBars(s.segZoneSec, Modifier.padding(horizontal = 30.dp, vertical = 3.dp))
}

@Composable
private fun Metric(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(unit, fontSize = 10.sp, color = Colors.dim)
    }
}

@Composable
private fun ControlsPage(s: WorkoutEngine.Ui, onSwitch: () -> Unit, back: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(4000); confirm = false } }
    val strap by HrSensor.status.collectAsState()
    val batt by HrSensor.battery.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)
    ) {
        Text(s.segmentTitles.joinToString(" → "), fontSize = 11.sp, color = Colors.dim, maxLines = 2, textAlign = TextAlign.Center)
        WideBtn("↻ Сменить упражнение", Colors.action) { onSwitch() }
        WideBtn(if (s.paused) "▶ Продолжить" else "⏸ Пауза", Colors.card) {
            WorkoutEngine.setPaused(!s.paused); back()
        }
        WideBtn(if (confirm) "Точно завершить?" else "■ Завершить", Colors.danger) {
            if (confirm) WorkoutEngine.finish() else confirm = true
        }
        Text(
            when (strap) {
                HrSensor.Status.CONNECTED -> "Датчик ✓" + (batt?.let { " · $it%" } ?: "")
                HrSensor.Status.CONNECTING, HrSensor.Status.RECONNECTING -> "Подключаю датчик…"
                else -> if (Storage.sensorAddress() == null) "Датчик не выбран — пульс с часов" else "Датчик не найден — пульс с часов"
            },
            fontSize = 11.sp, color = if (strap == HrSensor.Status.CONNECTED) Colors.ready else Colors.wait
        )
    }
}

@Composable
private fun MapPage(s: WorkoutEngine.Ui) {
    val pts by WorkoutEngine.track.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().padding(34.dp)) {
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
        Column(Modifier.align(Alignment.TopCenter).padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${fmtKm(s.distanceM)} км · ${fmtPace(s.paceSecPerKm)} /км", fontSize = 13.sp, color = Color.White)
            if (pts.size < 2) Text(if (s.gpsFix) "Начните движение" else "Поиск GPS…", fontSize = 12.sp, color = Colors.wait)
        }
        Text("Круг ${s.lapNo} · север вверху", fontSize = 11.sp, color = Colors.dim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}
