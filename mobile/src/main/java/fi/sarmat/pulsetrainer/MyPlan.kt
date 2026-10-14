package fi.sarmat.pulsetrainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.sarmat.pulsetrainer.core.Goal
import fi.sarmat.pulsetrainer.core.Physiology
import fi.sarmat.pulsetrainer.core.Profile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * «Мой план» — the whole coach in three short blocks: how to sleep, how to eat, how to train.
 * Every number comes from the same calculations as the detailed cards, so nothing contradicts.
 */
object MyPlan {
    data class Block(val title: String, val main: String, val lines: List<String>)

    fun build(): List<Block> {
        val p = PhoneStore.profile.value ?: Profile()
        val goal = PhoneStore.goal.value
        val pic = try { PhoneStore.insights() } catch (_: Exception) { null }
        val advice = try { PhoneStore.advise() } catch (_: Exception) { null }
        val rec = try { PhoneStore.recovery() } catch (_: Exception) { null }
        val b = Physiology.zoneBounds(p)
        val z = ZoneId.systemDefault()
        val ru = Locale("ru")
        val out = ArrayList<Block>()

        // ---- Sleep ----
        val sp = pic?.sleepPlan
        val sleepTip = pic?.items?.firstOrNull { it.area == "Сон" && it.level > 0 }?.action
            ?: sp?.lines?.firstOrNull { it.startsWith("Кофе") || it.startsWith("Плотная") || it.startsWith("Время отбоя") || it.startsWith("Подъём") }
        out += Block("Сон",
            if (sp != null) "Ложиться ${sp.bed} · вставать ${sp.wake}" else "7,5–8 ч, в одно и то же время",
            listOfNotNull(sleepTip, "Последний кофе до 14:00, ужин за 2–3 ч до сна."))

        // ---- Food ----
        val today = LocalDate.now()
        val trainDay = FoodStore.targets(today)
        val restDay = fi.sarmat.pulsetrainer.core.Nutrition.targets(p, goal, false, realTdee = PhoneStore.learnedTdee)
        val trained = FoodStore.trainedOn(today)
        val foodTip = pic?.items?.firstOrNull { (it.area == "Вес" || it.area == "Питание" || it.area == "Вода") && it.level > 0 }?.action
        out += Block("Еда",
            "${trainDay.kcal} ккал · белок ${trainDay.p} г · вода ${"%.1f".format(trainDay.waterMl / 1000.0)} л",
            listOfNotNull(
                if (trained) "Сегодня день тренировки. В день отдыха ~${restDay.kcal} ккал." else "Сегодня день без тренировки. В день тренировки норма выше.",
                "Белок в каждый приём пищи по 30–40 г: курица, яйца, творог, рыба.",
                foodTip,
            ))

        // ---- Training ----
        val now = System.currentTimeMillis()
        val lastStrength = (PhoneStore.workouts.value.filter { w -> w.segments.any { it.type.strength && it.activeSec >= 600 } }.map { it.end } +
            PhoneStore.ext.value.filter { it.strength }.map { it.end }).maxOrNull()
        fun dayName(t: Long): String {
            val d = Instant.ofEpochMilli(t).atZone(z).toLocalDate()
            return when (d) {
                today -> "сегодня"; today.plusDays(1) -> "завтра"
                else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, ru)
            }
        }
        val nextStrength = maxOf(now, (lastStrength ?: 0L) + 48 * 3600_000L, rec?.readyAt ?: 0L)
        val strengthDays = (0..2).map { nextStrength + it * 2 * 86400_000L }.joinToString(", ") { dayName(it) }
        val todayLine = advice?.headline?.let { h ->
            if (h.startsWith("Тренировка сегодня уже")) "Сегодня: отдых — тренировка уже сделана ✓" else "Сегодня: ${h.removePrefix("Сегодня — ").removePrefix("Сегодня ")}"
        } ?: "Сегодня: по самочувствию"
        val trainTip = pic?.items?.firstOrNull { (it.area == "Силовая" || it.area == "Нагрузка" || it.area == "Кардио" || it.area == "Пульс") && it.level > 0 }?.action
        out += Block("Тренировки", todayLine, listOfNotNull(
            when (goal) {
                Goal.HEART -> "Кардио зона 2 (пульс ${b[1]}–${b[2]}) 4–5 раз по 30–45 мин, 1 раз интервалы."
                Goal.FAT_LOSS -> "Силовые 2–3 раза в неделю: $strengthDays. Каждый день 8–10 тыс. шагов."
                else -> "Силовые 3 раза в неделю через день: $strengthDays."
            },
            "Между силовыми — зона 2 (пульс ${b[1]}–${b[2]}) 30–40 мин или прогулка. 1 день в неделю — полный отдых.",
            trainTip,
        ))
        return out
    }
}

@Composable
fun MyPlanContent(blocks: List<MyPlan.Block>) {
    blocks.forEachIndexed { i, b ->
        if (i > 0) Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(Color(0xFF2A3340)))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(b.title.uppercase(), color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(b.main, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            b.lines.forEach { Text("• $it", color = Color.White, fontSize = 16.sp) }
        }
    }
}
