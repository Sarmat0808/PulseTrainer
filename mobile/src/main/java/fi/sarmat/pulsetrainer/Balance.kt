package fi.sarmat.pulsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.sarmat.pulsetrainer.core.DailyStats
import fi.sarmat.pulsetrainer.core.DayTotals
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.Workout
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Calories of today, live: what you ate vs what you burned so far, and where the day will end.
 * Burned = resting (basal metabolism for the hours passed) + active (Samsung Health / your workouts),
 * the same way Samsung Health and Garmin count «total calories».
 */
object Balance {
    data class Day(
        val eaten: Int,
        val restSoFar: Int,
        val active: Int,
        val burned: Int,
        /** Expected total burn by the end of the day (rest of the day at your usual activity). */
        val endBurn: Int,
        val ateSomething: Boolean,
    )

    fun today(p: Profile, days: List<DailyStats>, workouts: List<Workout>): Day {
        val z = ZoneId.systemDefault()
        val d = LocalDate.now()
        val start = d.atStartOfDay(z).toInstant().toEpochMilli()
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val bmr = Physiology.bmr(p)
        val rest = bmr * nowMin / 1440.0
        val hc = days.lastOrNull { it.day == start }?.activeKcal ?: 0.0
        val own = workouts.filter { it.start >= start }.sumOf { it.kcalActive }
        val active = max(hc, own)
        // Your usual active calories (last 7 full days) for the hours still ahead (awake until ~23:00).
        val usual = days.filter { it.day < start && it.day >= start - 7 * 86400_000L }.mapNotNull { it.activeKcal?.takeIf { k -> k > 50 } }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val awakeLeft = ((23 * 60 - nowMin) / (16.0 * 60)).coerceIn(0.0, 1.0)
        val end = bmr + active + usual * awakeLeft
        val e = FoodStore.entries(d)
        return Day(DayTotals.of(e).kcal.roundToInt(), rest.roundToInt(), active.roundToInt(), (rest + active).roundToInt(),
            end.roundToInt(), e.any { it.drinkMl == 0 })
    }
}

@Composable
fun BalanceContent(b: Balance.Day, appTarget: Int) {
    val diff = b.eaten - b.burned
    val (word, col) = when {
        !b.ateSomething -> "Еда сегодня не внесена" to Dim
        diff > 150 -> "Профицит +$diff" to Warn
        diff < -150 -> "Дефицит −${abs(diff)}" to Good
        else -> "Баланс ≈ 0" to Accent
    }
    Text(word, color = col, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text("сейчас: съедено минус потрачено", color = Dim, fontSize = 15.sp)

    val scale = maxOf(b.eaten, b.endBurn, 1).toFloat()
    // Eaten
    BarRow("Съедено", "${b.eaten} ккал") {
        Box(Modifier.fillMaxHeight().fillMaxWidth(b.eaten / scale).background(Color(0xFF2D9CDB)))
    }
    // Burned: resting + active, faint part = expected until the evening
    BarRow("Потрачено", "${b.burned} ккал") {
        Row(Modifier.fillMaxHeight().fillMaxWidth()) {
            val r = b.restSoFar / scale; val a = b.active / scale; val rest = ((b.endBurn - b.burned).coerceAtLeast(0)) / scale
            if (r > 0f) Box(Modifier.fillMaxHeight().weight(r).background(Color(0xFF8FA3BF)))
            if (a > 0f) Box(Modifier.fillMaxHeight().weight(a).background(Color(0xFFF2994A)))
            if (rest > 0f) Box(Modifier.fillMaxHeight().weight(rest).background(Color(0x33FFFFFF)))
            val empty = 1f - r - a - rest
            if (empty > 0.001f) Box(Modifier.fillMaxHeight().weight(empty))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Mini("${b.restSoFar}", "в покое", Modifier.weight(1f))
        Mini("${b.active}", "активные", Modifier.weight(1f))
        Mini("~${b.endBurn}", "за весь день", Modifier.weight(1f))
    }

    // What to eat for the rest of the day.
    val keep = b.endBurn - b.eaten
    val lose = b.endBurn - 400 - b.eaten
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("До конца дня можно съесть:", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(if (lose > 0) "• лёгкий дефицит (−400): ещё ~$lose ккал" else "• лёгкий дефицит (−400): уже съедено больше",
            color = if (lose > 0) Good else Warn, fontSize = 17.sp)
        Text(if (keep > 0) "• держать вес: ещё ~$keep ккал" else "• держать вес: уже профицит ${-keep} ккал",
            color = if (keep > 0) Color.White else Warn, fontSize = 17.sp)
        Text("• норма приложения под цель: $appTarget ккал", color = Dim, fontSize = 16.sp)
    }
    Expander("Как считается") {
        Text("Потрачено = обмен в покое за прошедшие часы (формула Миффлина) + активные калории за день (Samsung Health и ваши тренировки) — так же, как «всего калорий» в Samsung Health.",
            color = Dim, fontSize = 15.sp)
        Text("«За весь день» — прогноз: полный обмен + активность до вечера, как в ваши обычные дни. Светлая часть полосы — то, что ещё потратите.",
            color = Dim, fontSize = 15.sp)
        Text("Лёгкий дефицит 300–500 ккал ≈ −0,3…−0,5 кг в неделю без потери мышц (при белке 1,8–2 г/кг и силовых). Точность расчёта ±10 %, поэтому смотрите на вес за 2–3 недели.",
            color = Dim, fontSize = 15.sp)
    }
}

@Composable
private fun BarRow(label: String, value: String, bar: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF2A3340))) { bar() }
    }
}
