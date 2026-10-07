package fi.sarmat.pulsetrainer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.sarmat.pulsetrainer.core.ExtWorkout
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.Review
import fi.sarmat.pulsetrainer.core.Workout
import fi.sarmat.pulsetrainer.core.WorkoutType
import fi.sarmat.pulsetrainer.core.fmtDuration
import fi.sarmat.pulsetrainer.core.fmtKm
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WORKOUT_CARDS = linkedMapOf(
    "fav" to "★ Избранные тренировки",
    "health" to "Samsung Health / Health Connect",
    "share" to "Поделиться для ИИ-анализа",
)
private val WORKOUT_MORE = setOf("health", "share")

private val ru = Locale("ru")
private val monthFmt = SimpleDateFormat("LLLL yyyy", ru)
private val dayFmt = SimpleDateFormat("EE, d MMM yyyy · HH:mm", ru)

private val Gold = Color(0xFFF2C94C)

/** One row of the history: our own workout or one recorded by Samsung Health. */
private sealed interface HistRow {
    val start: Long
    data class Own(val w: Workout) : HistRow { override val start get() = w.start }
    data class Ext(val e: ExtWorkout) : HistRow { override val start get() = e.start }
}

@Composable
fun WorkoutsScreen(
    workouts: List<Workout>,
    health: @Composable () -> Unit,
    share: @Composable () -> Unit,
    live: @Composable () -> Unit,
    open: (String) -> Unit,
) {
    val ext by PhoneStore.ext.collectAsState()
    val profile by PhoneStore.profile.collectAsState()
    val p = profile ?: Profile()
    val layout = rememberCardLayout("workouts", WORKOUT_CARDS.keys.toList(), WORKOUT_MORE)
    var showSamsung by remember { mutableStateOf(true) }

    val rows: List<HistRow> = remember(workouts, ext, showSamsung) {
        val own = workouts.map { HistRow.Own(it) }
        val other = if (showSamsung) ext.filter { e -> workouts.none { e.start < it.end && e.end > it.start } }.map { HistRow.Ext(it) } else emptyList()
        (own + other).sortedByDescending { it.start }
    }
    val reviews = remember(workouts, p) { workouts.associate { it.id to Review.of(it, workouts, p) } }

    val card: @Composable (String) -> Unit = { id ->
        when (id) {
            "fav" -> FavoritesCard()
            "health" -> health()
            "share" -> share()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Тренировки", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                ArrangeButton("workouts", WORKOUT_CARDS, WORKOUT_MORE)
            }
        }
        item { live() }
        layout.top.forEach { id -> item(key = id) { card(id) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("История", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (showSamsung) "+ Samsung Health ✓" else "+ Samsung Health", color = if (showSamsung) Accent else Dim, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showSamsung = !showSamsung }.padding(6.dp))
            }
        }
        if (rows.isEmpty()) item {
            Text("Пока пусто. Завершите тренировку на часах — она появится здесь автоматически.", color = Dim, fontSize = 16.sp)
        }
        var lastMonth = ""
        rows.forEach { r ->
            val m = monthFmt.format(Date(r.start)).replaceFirstChar { it.uppercase() }
            if (m != lastMonth) {
                lastMonth = m
                val monthRows = rows.filter { monthFmt.format(Date(it.start)).replaceFirstChar { c -> c.uppercase() } == m }
                val mins = monthRows.sumOf { when (it) { is HistRow.Own -> it.w.activeSec / 60; is HistRow.Ext -> it.e.minutes } }
                item(key = "m$m") {
                    Text("$m · ${monthRows.size} трен. · ${mins / 60} ч ${mins % 60} мин", color = Dim, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            when (r) {
                is HistRow.Own -> item(key = r.w.id) { OwnRow(r.w, reviews[r.w.id]) { open(r.w.id) } }
                is HistRow.Ext -> item(key = "e${r.e.start}") { ExtRow(r.e) }
            }
        }
        item(key = "more") { MoreBlock(layout.more.size, "Подробнее: Samsung Health, отчёт для ИИ") { layout.more.forEach { card(it) } } }
    }
}

private fun mainType(w: Workout): WorkoutType = w.segments.maxByOrNull { it.activeSec }?.type ?: WorkoutType.OTHER

@Composable
private fun OwnRow(w: Workout, r: Review.Result?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        SportIcon(mainType(w), 44.dp, Color.White, sportColor(mainType(w)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val name = if (w.segments.size <= 1) mainType(w).short else w.segments.joinToString(" → ") { it.type.short }
            Text(name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(dayFmt.format(Date(w.start)), color = Dim, fontSize = 14.sp, maxLines = 1)
            val parts = listOfNotNull(
                fmtDuration(w.activeSec),
                w.distanceM.takeIf { it > 50 }?.let { "${fmtKm(it)} км" },
                w.floors.takeIf { it > 0 }?.let { "$it эт." },
                if (w.avgHr > 0) "♥ ${w.avgHr}" else null,
                "${w.kcalTotal.toInt()} ккал",
            )
            Text(parts.joinToString(" · "), color = Color.White, fontSize = 15.sp, maxLines = 2)
            ZoneBar(w.zoneSec)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r != null) Text(r.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    color = when (r.level) { 0 -> Good; 1 -> Warn; 2 -> Danger; else -> Dim })
                if (w.track.size >= 2) Text("🗺 маршрут", color = Accent, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ExtRow(e: ExtWorkout) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF151A20)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SportIcon(guessType(e.title), 40.dp, Color.White, sportColor(guessType(e.title)).copy(alpha = 0.55f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(dayFmt.format(Date(e.start)) + " · Samsung Health", color = Dim, fontSize = 14.sp, maxLines = 2)
            Text("${e.minutes} мин" + (e.avgHr?.let { " · ♥ $it" } ?: "") + " · нагрузка ${e.trimp.toInt()}" + if (e.estimated) " (оценка)" else "",
                color = Dim, fontSize = 14.sp, maxLines = 2)
        }
    }
}

private fun guessType(title: String): WorkoutType {
    val t = title.lowercase()
    return when {
        "ход" in t || "walk" in t -> WorkoutType.WALK
        "бег" in t || "run" in t || "дорож" in t -> WorkoutType.RUN
        "вел" in t || "bik" in t -> WorkoutType.BIKE_OUTDOOR
        "сил" in t || "вес" in t -> WorkoutType.STRENGTH
        "орбит" in t || "ellip" in t -> WorkoutType.ELLIPTICAL
        "басс" in t || "swim" in t -> WorkoutType.SWIMMING
        "поход" in t -> WorkoutType.HIKING
        "лестн" in t || "stair" in t -> WorkoutType.STAIRS_HOME
        else -> WorkoutType.OTHER
    }
}

// ======================= Favourites ★ =======================

@Composable
private fun FavoritesCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fav by PhoneStore.favorites.collectAsState()
    var edit by remember { mutableStateOf(false) }
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("★ Избранное", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Изменить", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { edit = true }.padding(6.dp))
        }
        Text("Нажмите — тренировка начнётся на часах. Эти же тренировки — в плитках на часах.", color = Dim, fontSize = 14.sp)
        fav.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF232B35))
                            .clickable {
                                scope.launch {
                                    val ok = PhoneStore.startOnWatch(ctx, t)
                                    Toast.makeText(ctx, if (ok) "Запускаю на часах: ${t.short}" else "Часы не на связи", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SportIcon(t, 40.dp, Color.White, sportColor(t))
                        Text(t.short, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (edit) FavDialog(fav, onSave = { scope.launch { PhoneStore.setFavorites(ctx, it) } }) { edit = false }
}

@Composable
private fun FavDialog(current: List<WorkoutType>, onSave: (List<WorkoutType>) -> Unit, onClose: () -> Unit) {
    var sel by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Избранное: ${sel.size} из 6") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                WorkoutType.entries.forEach { t ->
                    val on = t in sel
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            sel = if (on) sel - t else if (sel.size < 6) sel + t else sel
                        }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SportIcon(t, 30.dp, Color.White, if (on) sportColor(t) else sportColor(t).copy(alpha = 0.35f))
                        Text(t.title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        Text(if (on) "★" else "☆", color = if (on) Gold else Dim, fontSize = 24.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(sel); onClose() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

// ======================= Review (in the workout detail) =======================

@Composable
fun ReviewSection(w: Workout) {
    val all by PhoneStore.workouts.collectAsState()
    val profile by PhoneStore.profile.collectAsState()
    val r = remember(w, all, profile) { Review.of(w, all, profile ?: Profile()) }
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SportIcon(mainType(w), 48.dp, Color.White, sportColor(mainType(w)))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Оценка тренировки", color = Dim, fontSize = 15.sp)
                Text(r.label + if (r.level >= 0) " · ${r.score}/100" else "", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = when (r.level) { 0 -> Good; 1 -> Warn; 2 -> Danger; else -> Dim })
            }
        }
        if (w.floors > 0 || w.ascentM >= 3 || w.steps > 0) {
            Row(Modifier.fillMaxWidth()) {
                if (w.floors > 0) Mini("${w.floors}", "этажей", Modifier.weight(1f))
                if (w.ascentM >= 3) Mini("${w.ascentM.toInt()} м", "подъём", Modifier.weight(1f))
                if (w.descentM >= 3) Mini("${w.descentM.toInt()} м", "спуск", Modifier.weight(1f))
                if (w.steps > 0) Mini("${w.steps}", "шагов", Modifier.weight(1f))
            }
        }
        Expander("Почему такая оценка и что улучшить") {
            if (r.good.isNotEmpty()) {
                Text("Хорошо", color = Good, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                r.good.forEach { BulletText(it, Color.White, 15) }
            }
            if (r.improve.isNotEmpty()) {
                Text("Что улучшить", color = Warn, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                r.improve.forEach { BulletText(it, Color.White, 15) }
            }
            if (r.compare.isNotEmpty()) {
                Text("По сравнению с прошлыми", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                r.compare.forEach { BulletText(it, Color.White, 15) }
            }
            Text("Оценка учитывает цель тренировки: для силовой — объём, отдых и восстановление пульса; для кардио — время в зонах, длительность и экономичность сердца по сравнению с вашими прошлыми тренировками.",
                color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
