package fi.sarmat.pulsetrainer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.StressRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stress on demand, like Samsung's "measure stress": sit still for 1 minute.
 * With the H10 strap: heart-rate variability vs your morning norm + pulse.
 * Watch only: by pulse (apps get no beat-to-beat data from the watch sensor).
 */
@Composable
fun StressScreen(open: (Scr) -> Unit) {
    KeepScreenOn()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by HrSensor.status.collectAsState()
    var stage by remember { mutableIntStateOf(0) } // 0 idle, 1 measuring, 2 result
    var left by remember { mutableIntStateOf(0) }
    var live by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<StressRecord?>(null) }
    val history = remember(stage) { Storage.stressHistory() }
    val watch = remember { WatchHr(ctx) }
    DisposableEffect(Unit) {
        watch.start()
        onDispose { watch.stop() }
    }

    LaunchedEffect(stage) {
        if (stage != 1) return@LaunchedEffect
        val strap = HrSensor.isConnected()
        val total = 60
        val settle = 10
        var sec = 0
        val rr = ArrayList<Int>()
        val hrs = ArrayList<Int>()
        val job = launch { HrSensor.rr.collect { if (strap && sec >= settle) rr += it } }
        while (sec < total) {
            left = total - sec
            val v = if (strap) HrSensor.freshBpm() ?: watch.fresh() else watch.fresh()
            live = v
            if (sec >= settle) v?.let { hrs += it }
            delay(1000)
            sec++
        }
        job.cancel()
        if (hrs.isNotEmpty()) {
            val hr = hrs.average().toInt()
            val rmssd = if (strap) Physiology.rmssd(Physiology.cleanRr(rr)) else 0.0
            val score = Physiology.stress(rmssd, hr, Storage.profile.value, Storage.hrvHistory())
            val rec = StressRecord(System.currentTimeMillis(), score, hr, rmssd)
            Storage.addStress(rec); result = rec; Haptics.ready()
        }
        stage = 2
    }

    ListScreen {
        when (stage) {
            0 -> {
                item {
                    ItemChip("▶ Измерить стресс", if (st == HrSensor.Status.CONNECTED) "1 мин · с ремнём H10 (точнее)" else "1 мин · по пульсу часов", Colors.action) { stage = 1 }
                }
                item { ItemChip("Дыхание 2 мин", "Вдох 4 с · выдох 6 с · снижает стресс", Color(0xFF2A2442)) { open(Scr.Breathe) } }
                history.lastOrNull()?.let { r -> item { StressCard(r) } }
                item { Line("Сидите спокойно, рука неподвижно, не разговаривайте.", Colors.dim, 14) }
                if (history.size > 1) item {
                    val f = SimpleDateFormat("d MMM HH:mm", Locale("ru"))
                    Card {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            history.takeLast(5).reversed().forEach { Line("${f.format(Date(it.time))} · ${it.score}", Color.White, 15) }
                        }
                    }
                }
            }
            1 -> {
                item { Line("$left", Color.White, 44, bold = true) }
                item { Line("♥ ${live ?: "--"}", Colors.zone[1], 22, bold = true) }
                item { Line("Сидите спокойно…", Colors.dim, 16) }
                item { ItemChip("Отмена", null) { stage = 0 } }
            }
            else -> {
                val r = result
                if (r != null) {
                    item { StressCard(r) }
                    if (r.score > 50) item { ItemChip("Дыхание 2 мин", "Снизить напряжение", Colors.action) { open(Scr.Breathe) } }
                } else item { Line("Не удалось получить пульс. Проверьте посадку часов.", Colors.wait, 15) }
                item { ItemChip("Готово", null, Colors.card) { stage = 0 } }
            }
        }
    }
}

@Composable
private fun StressCard(r: StressRecord) {
    val c = when (Physiology.stressLevel(r.score)) { 0 -> Colors.ready; 1 -> Colors.wait; else -> Colors.danger }
    Card {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Line("${r.score}", c, 34, bold = true)
            Line("Стресс: " + Physiology.stressLabel(r.score).lowercase(), c, 17, bold = true)
            Line("♥ ${r.hr}" + if (r.rmssd > 0) " · ВСР ${r.rmssd.toInt()} мс" else " · по пульсу", Color.White, 15)
            Line(Physiology.stressAdvice(r.score), Colors.dim, 14)
        }
    }
}

/** Guided slow breathing: 4 s in, 6 s out (6 breaths per minute), with a gentle vibration. */
@Composable
fun BreatheScreen(onDone: () -> Unit) {
    KeepScreenOn()
    var phase by remember { mutableIntStateOf(0) } // 0 in, 1 out
    var left by remember { mutableIntStateOf(120) }
    LaunchedEffect(Unit) {
        var t = 0
        while (t < 120) {
            phase = if (t % 10 < 4) 0 else 1
            if (t % 10 == 0 || t % 10 == 4) Haptics.tick()
            left = 120 - t
            delay(1000); t++
        }
        Haptics.ready()
        onDone()
    }
    val scale by animateFloatAsState(if (phase == 0) 1f else 0.45f, tween(if (phase == 0) 4000 else 6000), label = "breath")
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.size((180 * scale).dp).clip(CircleShape).background(Color(0xFF2D6CDF).copy(alpha = 0.55f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Line(if (phase == 0) "Вдох" else "Выдох", Color.White, 26, bold = true)
            Line("${left / 60}:${"%02d".format(left % 60)}", Colors.dim, 16)
        }
    }
}

/**
 * Interval timer setup (Tabata, HIIT, boxing, jump rope). Start is at the very top;
 * work / rest / rounds / cycles / rest between cycles / countdown below. Saved per exercise.
 * Classic Tabata: 20 s work, 10 s rest, 8 rounds (4 minutes).
 */
@Composable
fun IntervalSetupScreen(t: fi.sarmat.pulsetrainer.core.WorkoutType, onStart: (fi.sarmat.pulsetrainer.core.WorkoutType) -> Unit) {
    var c by remember { mutableStateOf(Storage.intervals(t)) }
    fun set(n: fi.sarmat.pulsetrainer.core.IntervalCfg) { c = n; Storage.setIntervals(t, n) }
    fun sec(v: Int) = if (v >= 60) "${v / 60}:${"%02d".format(v % 60)}" else "$v с"
    ListScreen {
        item { ItemChip("▶ Старт · ${t.short}", "Всего ${sec(c.totalSec)}", Colors.action, icon = t) { onStart(t) } }
        item { IvStepper("Работа", sec(c.work)) { d -> set(c.copy(work = (c.work + d * if (c.work >= 60) 15 else 5).coerceIn(5, 600))) } }
        item { IvStepper("Отдых", sec(c.rest)) { d -> set(c.copy(rest = (c.rest + d * if (c.rest >= 60) 15 else 5).coerceIn(0, 600))) } }
        item { IvStepper("Раунды", "${c.rounds}") { d -> set(c.copy(rounds = (c.rounds + d).coerceIn(1, 50))) } }
        item { IvStepper("Циклы", "${c.cycles}") { d -> set(c.copy(cycles = (c.cycles + d).coerceIn(1, 10))) } }
        if (c.cycles > 1) item { IvStepper("Отдых между циклами", sec(c.cycleRest)) { d -> set(c.copy(cycleRest = (c.cycleRest + d * 15).coerceIn(0, 600))) } }
        item { IvStepper("Отсчёт перед стартом", sec(c.prep)) { d -> set(c.copy(prep = (c.prep + d * 5).coerceIn(0, 30))) } }
        item {
            ItemChip("Сбросить к стандарту", if (t == fi.sarmat.pulsetrainer.core.WorkoutType.TABATA) "20 с / 10 с × 8 раундов" else null) {
                set(fi.sarmat.pulsetrainer.core.IntervalCfg.default(t))
            }
        }
        item { Line("Последние 3 секунды каждой фазы — короткие вибрации, смена фазы — длинная.", Colors.dim, 14) }
    }
}

@Composable
private fun IvStepper(label: String, value: String, onDelta: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).background(Colors.card)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundBtn("−", size = 36.dp, textSize = 20) { onDelta(-1) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.wear.compose.material.Text(label, fontSize = 14.sp, color = Colors.dim, maxLines = 1)
            androidx.wear.compose.material.Text(value, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
        }
        RoundBtn("+", size = 36.dp, textSize = 20) { onDelta(1) }
    }
}
