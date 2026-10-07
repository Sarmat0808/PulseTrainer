package fi.sarmat.pulsetrainer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import fi.sarmat.pulsetrainer.core.Ecg
import fi.sarmat.pulsetrainer.core.EcgRecord
import fi.sarmat.pulsetrainer.core.Protocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 30-second single-lead ECG with the Polar H10 (raw signal over Polar's PMD service).
 * Sit still, strap on with wet electrodes. Result: pulse, rhythm regularity, HRV, and the trace
 * is sent to the phone. Not a medical device — for watching trends and showing a doctor.
 */
@Composable
fun EcgScreen() {
    KeepScreenOn()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by HrSensor.status.collectAsState()
    val has by HrSensor.supportsEcg.collectAsState()
    var stage by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(30) }
    var result by remember { mutableStateOf<EcgRecord?>(null) }
    val buf = remember { ArrayList<Int>(130 * 32) }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) { onDispose { HrSensor.stopEcg() } }

    LaunchedEffect(stage) {
        if (stage != 1) return@LaunchedEffect
        buf.clear()
        val job = launch { HrSensor.ecg.collect { a -> synchronized(buf) { a.forEach { buf += it } }; tick++ } }
        HrSensor.startEcg()
        var waited = 0
        // wait for the stream (up to 8 s), then record 30 s
        while (buf.isEmpty() && waited < 80) { delay(100); waited++ }
        if (buf.isEmpty()) { job.cancel(); HrSensor.stopEcg(); stage = 3; return@LaunchedEffect }
        synchronized(buf) { buf.clear() }
        var t = 30
        while (t > 0) { left = t; delay(1000); t-- }
        HrSensor.stopEcg(); job.cancel()
        val x = synchronized(buf) { buf.toIntArray() }
        val r = Ecg.analyse(System.currentTimeMillis(), x)
        result = r
        Storage.addEcg(r)
        try {
            val req = PutDataMapRequest.create(Protocol.PATH_ECG + r.time).apply {
                dataMap.putAsset("json", Asset.createFromBytes(Ecg.toJson(r).toByteArray()))
                dataMap.putLong("ts", r.time)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx).putDataItem(req)
        } catch (_: Exception) {}
        Haptics.ready()
        stage = 2
    }

    ListScreen {
        when (stage) {
            0 -> {
                when {
                    st != HrSensor.Status.CONNECTED -> item { ItemChip("Подключите Polar H10", "ЭКГ записывается с нагрудного ремня", Colors.wait) { HrSensor.connectSaved() } }
                    !has -> item { Line("Этот датчик не передаёт ЭКГ. Нужен Polar H10.", Colors.wait, 15) }
                    else -> item { ItemChip("▶ Записать ЭКГ · 30 с", "Сидите спокойно, не разговаривайте", Colors.action) { stage = 1 } }
                }
                item { Line("Ремень на груди, электроды смочены. Это не медицинский прибор: запись помогает следить за ритмом и показать врачу.", Colors.dim, 14) }
                Storage.ecgList().lastOrNull()?.let { r -> item { EcgResult(r) } }
            }
            1 -> {
                item { Line("$left", Color.White, 40, bold = true) }
                item { Trace(buf, tick) }
                item { Line("Записываю… не двигайтесь", Colors.dim, 15) }
                item { ItemChip("Отмена", null) { HrSensor.stopEcg(); stage = 0 } }
            }
            2 -> {
                item { ItemChip("Готово", "Запись отправлена на телефон", Colors.action) { stage = 0 } }
                result?.let { item { EcgResult(it) } }
            }
            else -> {
                item { Line("Ремень не прислал сигнал ЭКГ. Проверьте контакт и повторите.", Colors.wait, 15) }
                item { ItemChip("Назад", null) { stage = 0 } }
            }
        }
    }
}

@Composable
private fun EcgResult(r: EcgRecord) {
    val (text, lv) = Ecg.verdict(r)
    val c = when (lv) { 0 -> Colors.ready; 1 -> Colors.wait; else -> Colors.danger }
    Card {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Line("♥ ${r.hr} уд/мин", Color.White, 22, bold = true)
            Line(text, c, 15, bold = true)
            Line("ВСР ${r.rmssd.toInt()} мс · ударов ${r.beats}", Colors.dim, 14)
        }
    }
}

/** Last 3 seconds of the signal. */
@Composable
private fun Trace(buf: ArrayList<Int>, tick: Int) {
    val pts = remember(tick) { synchronized(buf) { buf.takeLast(390) } }
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        if (pts.size < 10) return@Canvas
        val lo = pts.min(); val hi = pts.max().coerceAtLeast(lo + 1)
        val path = Path()
        pts.forEachIndexed { i, v ->
            val o = Offset(i * size.width / 390f, size.height - (v - lo).toFloat() / (hi - lo) * size.height)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, Color(0xFFEB5757), style = Stroke(2.5f))
    }
}
