package fi.sarmat.pulsetrainer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import fi.sarmat.pulsetrainer.core.Mode
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.Protocol
import fi.sarmat.pulsetrainer.core.Workout
import fi.sarmat.pulsetrainer.core.WorkoutType
import fi.sarmat.pulsetrainer.core.fmtDuration
import fi.sarmat.pulsetrainer.core.fmtKm
import fi.sarmat.pulsetrainer.core.fmtPace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

val ZoneColors = arrayOf(
    Color(0xFF7F8C8D), Color(0xFF8FA3BF), Color(0xFF2D9CDB), Color(0xFF27AE60), Color(0xFFF2994A), Color(0xFFEB5757)
)
val CardBg = Color(0xFF1B2128)
val Dim = Color(0xFFBAC4CE)
val Accent = Color(0xFF2D9CDB)
val Danger = Color(0xFFEB5757)
val Good = Color(0xFF27AE60)
val Warn = Color(0xFFF2C94C)

private val dateFmt = SimpleDateFormat("EEE, d MMM, HH:mm", Locale("ru"))

class MainActivity : ComponentActivity() {
    private val openId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openId.value = intent?.getStringExtra("open")
        setContent {
            val scale by PhoneStore.fontScale.collectAsState()
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, base.fontScale * scale)
            ) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Color.Black, surface = CardBg)) {
                    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) { PhoneRoot(openId) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("open")?.let { openId.value = it }
    }
}

@Composable
fun PhoneRoot(openId: MutableState<String?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val workouts by PhoneStore.workouts.collectAsState()
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hcStatus by remember { mutableStateOf(HealthSync.sdkStatus(ctx)) }

    suspend fun syncPending() {
        var n = 0
        for (w in PhoneStore.workouts.value.filter { !it.syncedToHealth }) {
            try { if (HealthSync.write(ctx, w)) { PhoneStore.markSynced(w.id); n++ } } catch (_: Exception) {}
        }
        if (n > 0) Toast.makeText(ctx, "Записано в Health Connect: $n", Toast.LENGTH_SHORT).show()
    }

    val allPerms = HealthSync.PERMISSIONS + HealthData.READ_PERMISSIONS + HealthData.WRITE_WEIGHT + HealthData.EXTRA_PERMISSIONS
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { g ->
        granted = g
        scope.launch { syncPending(); PhoneStore.refreshDays(ctx) }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val requestHc: () -> Unit = {
        if (hcStatus == HealthConnectClient.SDK_AVAILABLE) hcLauncher.launch(allPerms)
        else try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata")))
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        hcStatus = HealthSync.sdkStatus(ctx)
        granted = try { HealthSync.granted(ctx) } catch (_: Exception) { emptySet() }
        PhoneStore.importAll(ctx)
        syncPending()
        PhoneStore.refreshDays(ctx)
    }

    val selected = openId.value?.let { id -> workouts.firstOrNull { it.id == id } }
    if (selected != null) {
        BackHandler { openId.value = null }
        DetailScreen(selected, onBack = { openId.value = null }, onSync = {
            scope.launch {
                val ok = try { HealthSync.write(ctx, selected) } catch (e: Exception) { false }
                if (ok) PhoneStore.markSynced(selected.id)
                Toast.makeText(ctx, if (ok) "Записано в Health Connect" else "Нет доступа к Health Connect", Toast.LENGTH_SHORT).show()
            }
        }, onDelete = { PhoneStore.delete(selected.id); openId.value = null })
        return
    }

    var tab by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    if (settings) {
        BackHandler { settings = false }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                TextButton(onClick = { settings = false }) { Text("← Назад", fontSize = 17.sp) }
            }
            Box(Modifier.weight(1f)) { ProfileTab() }
        }
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalOpenSettings provides { settings = true }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> TodayScreen(
                        needAccess = !granted.containsAll(HealthData.READ_PERMISSIONS),
                        onGrant = requestHc,
                        onRefresh = { scope.launch { PhoneStore.refreshDays(ctx) } },
                        openTab = { tab = if (it == 4) 0.also { settings = true } else it },
                        openWorkout = { openId.value = it },
                    )
                    1 -> CoachScreen(
                        needAccess = !granted.containsAll(HealthData.READ_PERMISSIONS),
                        onGrant = requestHc,
                        onRefresh = { scope.launch { PhoneStore.refreshDays(ctx) } },
                    )
                    2 -> NutritionScreen()
                    else -> WorkoutsScreen(
                        workouts,
                        health = { HealthCard(hcStatus, granted, onGrant = requestHc, onSync = { scope.launch { syncPending() } }) },
                        share = { ShareCard() },
                        live = { LiveCard() },
                        open = { openId.value = it },
                    )
                }
            }
            NavigationBar(containerColor = CardBg) {
                val labels = listOf("◎" to "Сегодня", "★" to "Тренер", "🍽" to "Еда", "♥" to "Тренировки")
                labels.forEachIndexed { i, (ic, l) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(ic, fontSize = 20.sp) },
                        label = { Text(l, maxLines = 1, softWrap = false, fontSize = 13.sp) })
                }
            }
        }
    }
}

@Composable
fun Section(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

// ---------------- Live remote control ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by PhoneStore.live.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val l = live
    if (l == null || !l.running || now - l.time > 15_000) return

    fun cmd(c: String) = scope.launch {
        if (!PhoneStore.sendToWatch(ctx, c)) Toast.makeText(ctx, "Часы не на связи", Toast.LENGTH_SHORT).show()
    }
    val type = WorkoutType.of(l.type)
    val zc = ZoneColors[l.zone.coerceIn(0, 5)]

    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("● Идёт тренировка" + if (l.paused) " (пауза)" else "", color = Danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(type.title + if (l.segmentNo > 1) " · упражнение ${l.segmentNo}" else "", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    fmtDuration(l.elapsedSec) + " · ${l.kcal} ккал" +
                        (if (l.distanceM > 20) " · ${fmtKm(l.distanceM)} км" else "") +
                        (if (type.mode != Mode.CARDIO) " · ${if (type.mode == Mode.ROUNDS) "раунд" else "подход"} ${l.setNo}" +
                            (if (l.phase == "REST") " (отдых)" else "") else ""),
                    color = Dim, fontSize = 15.sp
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.hr?.toString() ?: "--", color = zc, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(Physiology.ZONE_SHORT[l.zone.coerceIn(0, 5)] + " · " + if (l.sensor == "H10") "H10" else "часы", color = zc, fontSize = 15.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (type.mode != Mode.CARDIO) Button(onClick = { cmd(Protocol.CMD_NEXT) }, modifier = Modifier.weight(1f)) {
                Text(if (l.phase == "REST") "▶ Подход" else "Подход ✓")
            }
            OutlinedButton(onClick = { cmd(if (l.paused) Protocol.CMD_RESUME else Protocol.CMD_PAUSE) }, modifier = Modifier.weight(1f)) {
                Text(if (l.paused) "▶ Продолжить" else "⏸ Пауза")
            }
            Button(
                onClick = { confirm = true }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Danger)
            ) { Text("■ Стоп") }
        }
        Text("Сменить упражнение без остановки:", color = Dim, fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkoutType.entries.filter { it != type }.forEach { t ->
                AssistChip(onClick = { cmd(Protocol.CMD_SWITCH + t.name) }, label = { Text(t.short) })
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Остановить тренировку?") },
        text = { Text("«Сохранить» — тренировка попадёт в историю и Samsung Health. «Отменить» — удалить без сохранения.") },
        confirmButton = { TextButton(onClick = { confirm = false; cmd(Protocol.CMD_FINISH) }) { Text("Сохранить") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirm = false; cmd(Protocol.CMD_DISCARD) }) { Text("Отменить без сохранения", color = Danger) }
                TextButton(onClick = { confirm = false }) { Text("Назад") }
            }
        },
    )
}

// ---------------- Health Connect ----------------

@Composable
fun HealthCard(status: Int, granted: Set<String>, onGrant: () -> Unit, onSync: () -> Unit) {
    val all = granted.containsAll(HealthSync.PERMISSIONS)
    val any = granted.isNotEmpty()
    Section {
        Text("Samsung Health", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        when {
            status != HealthConnectClient.SDK_AVAILABLE -> {
                Text("Health Connect недоступен или требует обновления.", color = Warn, fontSize = 15.sp)
                Button(onClick = onGrant) { Text("Установить / обновить Health Connect") }
            }
            !any -> {
                Text("Разрешите запись тренировок в Health Connect — оттуда их забирает Samsung Health.", color = Dim, fontSize = 15.sp)
                Button(onClick = onGrant) { Text("Разрешить доступ") }
            }
            else -> {
                Text(if (all) "✓ Тренировки записываются в Health Connect" else "✓ Доступ есть (не все разрешения — например, маршрут)", color = Good, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSync) { Text("Синхронизировать") }
                    if (!all) OutlinedButton(onClick = onGrant) { Text("Разрешения") }
                }
            }
        }
        Text(
            "В Samsung Health должна быть включена синхронизация с Health Connect (раздел Health Connect в настройках Samsung Health). " +
                "Каждое упражнение мультитренировки появится там отдельной тренировкой.",
            color = Dim, fontSize = 15.sp
        )
    }
}

// ---------------- Share for AI ----------------

@Composable
fun ShareCard() {
    val ctx = LocalContext.current
    var withFiles by remember { mutableStateOf(true) }
    Section {
        Text("Поделиться для ИИ-анализа", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Полный отчёт всех тренировок за период: нагрузка по неделям, зоны, подходы, восстановление, утренние тесты + запрос к ИИ.", color = Dim, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { d ->
                Button(onClick = { Share.period(ctx, d, withFiles) }, modifier = Modifier.weight(1f)) { Text("$d дн.") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { withFiles = !withFiles }) {
            Text(if (withFiles) "☑" else "☐", fontSize = 20.sp, color = Accent)
            Spacer(Modifier.width(8.dp))
            Text("Приложить файлы CSV/JSON со всеми данными", color = Color.White, fontSize = 15.sp)
        }
    }
}

// ---------------- List ----------------

@Composable
private fun WorkoutRow(w: Workout, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (w.syncedToHealth) "✓ SH" else "⟳", color = if (w.syncedToHealth) Good else Warn, fontSize = 15.sp)
            }
            Text(
                dateFmt.format(Date(w.start)) + " · " + fmtDuration(w.activeSec) + " · ♥ ${w.avgHr}/${w.maxHr} · ${w.kcalTotal.toInt()} ккал" +
                    if (w.distanceM > 20) " · ${fmtKm(w.distanceM)} км" else "",
                color = Dim, fontSize = 15.sp
            )
            ZoneBar(w.zoneSec)
        }
    }
}

@Composable
fun ZoneBar(zs: IntArray) {
    val total = zs.sum().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        for (z in 0..5) if (zs[z] > 0) Box(Modifier.weight(zs[z].toFloat() / total).height(8.dp).background(ZoneColors[z]))
    }
}

// ---------------- Detail ----------------

@Composable
private fun DetailScreen(w: Workout, onBack: () -> Unit, onSync: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var askDelete by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Назад") }
            }
            Text(w.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(dateFmt.format(Date(w.start)) + " · " + w.hrSource, color = Dim, fontSize = 15.sp)
        }
        item { ReviewSection(w) }
        item {
            Section {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(fmtDuration(w.activeSec), "время")
                    Stat("${w.avgHr}", "ср. пульс")
                    Stat("${w.maxHr}", "макс.")
                    Stat("${w.kcalTotal.toInt()}", "ккал")
                }
                HrChart(w)
                ZoneBar(w.zoneSec)
                for (z in 5 downTo 1) if (w.zoneSec[z] > 0) Text(
                    "${Physiology.ZONE_NAMES[z]} (${w.zoneBounds.getOrNull(z - 1)}–${w.zoneBounds.getOrNull(z)}): ${fmtDuration(w.zoneSec[z])}",
                    color = ZoneColors[z], fontSize = 15.sp
                )
                Text("Нагрузка TRIMP ${w.trimp.toInt()} · отдых ~${w.recoveryHours} ч", color = Dim, fontSize = 15.sp)
                Text(Physiology.recoveryText(w.recoveryHours, w.segments), color = Warn, fontSize = 15.sp)
            }
        }
        item {
            Section {
                Text("Поделиться для ИИ-анализа", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Button(onClick = { Share.workout(ctx, w, true) }, modifier = Modifier.fillMaxWidth()) { Text("Полный отчёт + файлы") }
                OutlinedButton(onClick = { Share.workout(ctx, w, false) }, modifier = Modifier.fillMaxWidth()) { Text("Только текст") }
                if (w.track.size >= 2) OutlinedButton(onClick = { Share.openRoute(ctx, w) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть маршрут в Organic Maps")
                }
                if (!w.syncedToHealth) OutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text("Записать в Health Connect") }
            }
        }
        w.segments.forEachIndexed { i, s ->
            item {
                Section {
                    Text((if (w.segments.size > 1) "${i + 1}. " else "") + s.type.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${fmtDuration(s.activeSec)} · ♥ ${s.avgHr}/${s.maxHr} · ${s.kcalTotal.toInt()} ккал · TRIMP ${s.trimp.toInt()}", color = Dim, fontSize = 15.sp)
                    ZoneBar(s.zoneSec)
                    if (s.distanceM > 20) {
                        val pace = (s.activeSec / (s.distanceM / 1000.0)).toInt()
                        Text("${fmtKm(s.distanceM)} км · темп ${fmtPace(pace)} /км", color = Color.White, fontSize = 15.sp)
                    }
                    s.sets.forEachIndexed { n, x ->
                        val parts = mutableListOf<String>()
                        if (x.reps > 0) parts += "${x.reps} повт."
                        parts += "${fmtDuration(((x.end - x.start) / 1000).toInt())}"
                        parts += "пик ${x.peakHr}"
                        x.hrr60?.let { parts += "−$it за 60 с" }
                        x.restSec?.let { parts += "отдых ${fmtDuration(it)}" }
                        Text("${if (s.type.mode == Mode.ROUNDS) "Раунд" else "Подход"} ${n + 1}: " + parts.joinToString(" · "), color = Color.White, fontSize = 15.sp)
                    }
                    s.laps.forEachIndexed { n, l ->
                        val sec = ((l.end - l.start) / 1000).toInt()
                        val pace = if (l.distanceM > 0) (sec / (l.distanceM / 1000.0)).toInt() else null
                        Text("Круг ${n + 1}: ${l.distanceM.toInt()} м · ${fmtDuration(sec)} · ${fmtPace(pace)} /км", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }
        if (w.track.size >= 2) item {
            Section {
                Text("Маршрут", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                RouteCanvas(w)
            }
        }
        item {
            TextButton(onClick = { askDelete = true }) { Text("Удалить тренировку", color = Danger) }
        }
    }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("Удалить тренировку с телефона?") },
        text = { Text("Записи в Samsung Health останутся.") },
        confirmButton = { TextButton(onClick = { askDelete = false; onDelete() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Отмена") } },
    )
}

@Composable
private fun Stat(v: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = Dim, fontSize = 15.sp)
    }
}

/** Heart-rate curve over coloured zone bands, with exercise boundaries. */
@Composable
private fun HrChart(w: Workout) {
    if (w.hr.size < 2) return
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val minHr = (w.hr.minOf { it.bpm } - 5).coerceAtMost(w.zoneBounds.getOrElse(0) { 90 } - 5)
        val maxHr = w.hr.maxOf { it.bpm } + 5
        val t0 = w.hr.first().t
        val t1 = w.hr.last().t.coerceAtLeast(t0 + 1)
        fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
        fun y(b: Int) = size.height - (b - minHr).toFloat() / (maxHr - minHr) * size.height
        if (w.zoneBounds.size == 6) {
            for (z in 1..5) {
                val top = y(if (z == 5) maxHr else w.zoneBounds[z]).coerceIn(0f, size.height)
                val bot = y(w.zoneBounds[z - 1]).coerceIn(0f, size.height)
                if (bot > top) drawRect(ZoneColors[z].copy(alpha = 0.15f), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, bot - top))
            }
        }
        w.segments.drop(1).forEach { s ->
            val sx = x(s.start)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(sx, 0f), Offset(sx, size.height), 2f)
        }
        val step = (w.hr.size / size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)
        val path = Path()
        var first = true
        for (i in w.hr.indices step step) {
            val h = w.hr[i]
            if (first) { path.moveTo(x(h.t), y(h.bpm)); first = false } else path.lineTo(x(h.t), y(h.bpm))
        }
        drawPath(path, Color.White, style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun RouteCanvas(w: Workout) {
    val pts = w.track
    Canvas(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1216)).padding(12.dp)) {
        val lat0 = pts.minOf { it.lat }; val lat1 = pts.maxOf { it.lat }
        val lon0 = pts.minOf { it.lon }; val lon1 = pts.maxOf { it.lon }
        val k = cos(Math.toRadians((lat0 + lat1) / 2))
        val ww = ((lon1 - lon0) * k).coerceAtLeast(1e-5)
        val hh = (lat1 - lat0).coerceAtLeast(1e-5)
        val scale = minOf(size.width / ww, size.height / hh).toFloat()
        val ox = (size.width - (ww * scale).toFloat()) / 2
        val oy = (size.height - (hh * scale).toFloat()) / 2
        fun p(lat: Double, lon: Double) = Offset(ox + ((lon - lon0) * k * scale).toFloat(), oy + ((lat1 - lat) * scale).toFloat())
        val path = Path()
        pts.forEachIndexed { i, g -> val o = p(g.lat, g.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, Accent, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Good, 9f, p(pts.first().lat, pts.first().lon))
        drawCircle(Danger, 9f, p(pts.last().lat, pts.last().lon))
    }
}
