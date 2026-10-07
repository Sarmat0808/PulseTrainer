package fi.sarmat.pulsetrainer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.sarmat.pulsetrainer.core.DayTotals
import fi.sarmat.pulsetrainer.core.Food
import fi.sarmat.pulsetrainer.core.FoodEntry
import fi.sarmat.pulsetrainer.core.Nutrition
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru"))
private val hmFmt = SimpleDateFormat("HH:mm", Locale("ru"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NutritionScreen() {
    val ctx = LocalContext.current
    val version by FoodStore.version.collectAsState()
    val profile by PhoneStore.profile.collectAsState()
    val goal by PhoneStore.goal.collectAsState()
    val workouts by PhoneStore.workouts.collectAsState()
    var date by remember { mutableStateOf(LocalDate.now()) }
    var addOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }

    val entries = remember(version, date) { FoodStore.entries(date) }
    val target = remember(version, date, profile, goal, workouts) { FoodStore.targets(date) }
    val total = DayTotals.of(entries)
    val forgot = remember(version, date) { FoodStore.forgot(date) }
    val reached = Nutrition.reached(total, target)
    val foods = remember(version) { FoodStore.foods() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Питание", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { date = date.minusDays(1) }) { Text("◀", fontSize = 18.sp) }
                Text(
                    if (date == LocalDate.now()) "Сегодня" else date.format(dayFmt),
                    color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
                TextButton(onClick = { if (date < LocalDate.now()) date = date.plusDays(1) }) { Text("▶", fontSize = 18.sp) }
            }
        }

        // ---------- Big status ----------
        item {
            val (bg, title, sub) = when {
                reached -> Triple(Good, "✓ ДНЕВНАЯ НОРМА НАБРАНА", "Белок и калории на месте — отлично!")
                forgot -> Triple(Color(0xFF4A4F57), "ДЕНЬ БЕЗ ЗАПИСЕЙ", "Отмечено «забыл внести» — в статистику не идёт")
                else -> Triple(
                    Danger, "ОСТАЛОСЬ: ${(target.kcal - total.kcal).coerceAtLeast(0.0).roundToInt()} ккал",
                    "Белок: ещё ${(target.p - total.p).coerceAtLeast(0.0).roundToInt()} г из ${target.p}"
                )
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(sub, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        }
        item {
            Section {
                MacroBar("Калории", total.kcal, target.kcal.toDouble(), "ккал", Color(0xFFF2994A))
                MacroBar("Белки", total.p, target.p.toDouble(), "г", Color(0xFFEB5757))
                MacroBar("Жиры", total.f, target.f.toDouble(), "г", Color(0xFFF2C94C))
                MacroBar("Углеводы", total.c, target.c.toDouble(), "г", Color(0xFF2D9CDB))
                MacroBar("Жидкость", total.fluidMl.toDouble(), target.waterMl.toDouble(), "мл", Color(0xFF56CCF2))
                Text(
                    "Норма: ${target.kcal} ккал · Б ${target.p} · Ж ${target.f} · У ${target.c} г" +
                        if (FoodStore.trainedOn(date)) " (день тренировки)" else "",
                    color = Dim, fontSize = 13.sp
                )
            }
        }

        // ---------- Actions ----------
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addOpen = true }, modifier = Modifier.weight(1f).height(52.dp)) { Text("+ Еда", fontSize = 17.sp) }
                OutlinedButton(onClick = { FoodStore.setForgot(date, !forgot) }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(if (forgot) "Отменить «забыл»" else "Забыл внести", fontSize = 15.sp)
                }
            }
        }
        item {
            Section {
                Text("Напитки — одно нажатие", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    foods.filter { it.drink && !it.custom }.forEach { f ->
                        AssistChip(onClick = {
                            FoodStore.add(date, f, (f.pieceG ?: 250).toDouble())
                            Toast.makeText(ctx, "${f.name}: +${f.pieceG ?: 250} мл", Toast.LENGTH_SHORT).show()
                        }, label = { Text("${f.name} ${f.pieceG ?: 250}", fontSize = 14.sp) })
                    }
                }
                val coffee = entries.count { it.foodId == "coffee" }
                Nutrition.drinkAdvice(total.fluidMl, coffee, target.waterMl).forEach { Text(it, color = Color.White, fontSize = 14.sp) }
            }
        }

        // ---------- Table ----------
        item {
            Section {
                Text("Съедено", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (entries.isEmpty()) Text("Пока пусто. Нажмите «+ Еда» и внесите вес продукта.", color = Dim, fontSize = 14.sp)
                else {
                    TableRow("Продукт", "Вес", "Б", "Ж", "У", "ккал", header = true)
                    entries.forEach { e ->
                        var ask by remember(e) { mutableStateOf(false) }
                        Box(Modifier.clickable { ask = true }) {
                            TableRow("${hmFmt.format(Date(e.time))} ${e.name}", amountText(e), n(e.p), n(e.f), n(e.c), n(e.kcal))
                        }
                        if (ask) AlertDialog(
                            onDismissRequest = { ask = false },
                            title = { Text("Удалить «${e.name}»?") },
                            confirmButton = { TextButton(onClick = { ask = false; FoodStore.remove(date, e) }) { Text("Удалить") } },
                            dismissButton = { TextButton(onClick = { ask = false }) { Text("Отмена") } },
                        )
                    }
                    TableRow("Итого", "", n(total.p), n(total.f), n(total.c), n(total.kcal), header = true)
                    Text("Нажмите на строку, чтобы удалить", color = Dim, fontSize = 12.sp)
                }
            }
        }

        // ---------- Advice ----------
        item {
            Section {
                Text("Что съесть сейчас", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Nutrition.whatToEat(total, target, foods).forEach { Tip(it) }
            }
        }
        item {
            Section {
                Text("До тренировки", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Nutrition.BEFORE_WORKOUT.forEach { Tip(it) }
                Text("После тренировки", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Nutrition.AFTER_WORKOUT.forEach { Tip(it) }
            }
        }
        item {
            val (plan, totals) = remember(target, version) { Nutrition.dayPlan(target, foods) }
            Section {
                Text("Пример меню на день", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Из ваших обычных продуктов, под норму ${target.kcal} ккал", color = Dim, fontSize = 13.sp)
                var meal = ""
                plan.forEach { it ->
                    if (it.meal != meal) {
                        meal = it.meal
                        Text(meal, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text("• ${it.name} — ${it.label}", color = Color.White, fontSize = 14.sp)
                }
                Text("Итого: ${totals.kcal.roundToInt()} ккал · Б ${totals.p.roundToInt()} · Ж ${totals.f.roundToInt()} · У ${totals.c.roundToInt()} г",
                    color = Good, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Гречку, рис, булгур, макароны и киноа можно менять между собой 1:1 по сухому весу.", color = Dim, fontSize = 13.sp)
            }
        }
        item {
            Section {
                Text("Витамины, минералы, масла", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Nutrition.SUPPLEMENTS.forEach { Tip(it) }
            }
        }

        // ---------- Own products ----------
        item {
            val customs by FoodStore.custom.collectAsState()
            Section {
                Text("Мои продукты", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Добавьте продукт с упаковки: белки, жиры, углеводы на 100 г.", color = Dim, fontSize = 13.sp)
                customs.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.name}: Б ${n(f.p)} · Ж ${n(f.f)} · У ${n(f.c)} · ${f.kcal.roundToInt()} ккал/100", color = Color.White,
                            fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { FoodStore.deleteCustom(f.id) }) { Text("✕", color = Danger) }
                    }
                }
                OutlinedButton(onClick = { customOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Свой продукт") }
            }
        }

        // ---------- History ----------
        item {
            val hist = remember(version, profile, goal) { FoodStore.history(7) }
            Section {
                Text("Последние 7 дней", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                TableRow("День", "ккал", "Б", "Ж", "У", "", header = true)
                hist.forEach { h ->
                    val mark = when {
                        h.forgot -> "—"
                        Nutrition.reached(h.totals, h.target) -> "✓"
                        h.entries.isEmpty() -> "?"
                        else -> "✗"
                    }
                    TableRow(
                        h.date.format(DateTimeFormatter.ofPattern("EE d.MM", Locale("ru"))),
                        n(h.totals.kcal), n(h.totals.p), n(h.totals.f), n(h.totals.c), mark
                    )
                }
                Text("✓ норма набрана · ✗ не добрал · — забыл внести", color = Dim, fontSize = 12.sp)
            }
        }
    }

    if (addOpen) AddFoodDialog(foods, onDismiss = { addOpen = false }) { f, amount ->
        FoodStore.add(date, f, amount)
        addOpen = false
        Toast.makeText(ctx, "Добавлено: ${f.name}", Toast.LENGTH_SHORT).show()
    }
    if (customOpen) CustomFoodDialog(onDismiss = { customOpen = false })
}

private fun n(v: Double) = v.roundToInt().toString()
private fun amountText(e: FoodEntry) = if (e.drinkMl > 0) "${e.drinkMl} мл" else "${e.amount.roundToInt()} г"

@Composable
private fun Tip(text: String) {
    Row { Text("•  ", color = Accent, fontSize = 15.sp); Text(text, color = Color.White, fontSize = 15.sp) }
}

@Composable
private fun MacroBar(label: String, value: Double, target: Double, unit: String, color: Color) {
    val frac = if (target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f
    val done = target > 0 && value >= target * 0.95
    Column {
        Row {
            Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()} / ${target.roundToInt()} $unit" + if (done) " ✓" else "",
                color = if (done) Good else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A3038))) {
            Box(Modifier.fillMaxWidth(frac).height(10.dp).background(if (done) Good else color))
        }
    }
}

@Composable
private fun TableRow(a: String, b: String, c: String, d: String, e: String, f: String, header: Boolean = false) {
    val col = if (header) Dim else Color.White
    val w = if (header) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(a, color = col, fontSize = 14.sp, fontWeight = w, modifier = Modifier.weight(3f))
        Text(b, color = col, fontSize = 14.sp, fontWeight = w, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End)
        Text(c, color = col, fontSize = 14.sp, fontWeight = w, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(d, color = col, fontSize = 14.sp, fontWeight = w, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(e, color = col, fontSize = 14.sp, fontWeight = w, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(f, color = col, fontSize = 14.sp, fontWeight = w, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddFoodDialog(foods: List<Food>, onDismiss: () -> Unit, onAdd: (Food, Double) -> Unit) {
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<Food?>(null) }
    var amount by remember { mutableStateOf("") }
    val recent = remember { FoodStore.recentIds() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(chosen?.name ?: "Добавить еду") },
        text = {
            val f = chosen
            if (f == null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Поиск") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Nutrition.CATEGORIES.forEach { c ->
                            FilterChip(selected = cat == c, onClick = { cat = if (cat == c) null else c }, label = { Text(c, fontSize = 13.sp) })
                        }
                    }
                    val list = foods
                        .filter { cat == null || it.category == cat }
                        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                        .sortedBy { val i = recent.indexOf(it.id); if (i < 0) 999 else i }
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(list, key = { it.id }) { item ->
                            Column(Modifier.fillMaxWidth().clickable {
                                chosen = item
                                amount = (item.pieceG ?: if (item.drink) 250 else 100).toString()
                            }.padding(vertical = 8.dp)) {
                                Text(item.name, color = Color.White, fontSize = 16.sp)
                                Text("Б ${n(item.p)} · Ж ${n(item.f)} · У ${n(item.c)} · ${item.kcal.roundToInt()} ккал на 100 ${if (item.drink) "мл" else "г"}",
                                    color = Dim, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                val a = amount.replace(',', '.').toDoubleOrNull() ?: 0.0
                val e = Nutrition.entry(f, a)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                        label = { Text(if (f.drink) "Количество, мл" else "Вес, г (взвесьте)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                    )
                    val pg = f.pieceG
                    if (pg != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1, 2, 3).forEach { k ->
                                AssistChip(onClick = { amount = (pg * k).toString() }, label = { Text("$k ${f.pieceName ?: "шт"}") })
                            }
                        }
                    }
                    Text("Б ${n(e.p)} г · Ж ${n(e.f)} г · У ${n(e.c)} г · ${n(e.kcal)} ккал", color = Good, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { chosen = null }) { Text("← Другой продукт") }
                }
            }
        },
        confirmButton = {
            val f = chosen
            val a = amount.replace(',', '.').toDoubleOrNull()
            Button(onClick = { if (f != null && a != null && a > 0) onAdd(f, a) }, enabled = f != null && a != null && a > 0) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun CustomFoodDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var f by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var piece by remember { mutableStateOf("") }
    var drink by remember { mutableStateOf(false) }
    fun d(s: String) = s.replace(',', '.').toDoubleOrNull()
    val ok = name.isNotBlank() && d(p) != null && d(f) != null && d(c) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свой продукт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("На 100 ${if (drink) "мл" else "г"} (с упаковки):", color = Dim, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumIn("Белки", p, Modifier.weight(1f)) { p = it }
                    NumIn("Жиры", f, Modifier.weight(1f)) { f = it }
                    NumIn("Углев.", c, Modifier.weight(1f)) { c = it }
                }
                NumIn("Вес 1 шт / порции, г (необязательно)", piece, Modifier.fillMaxWidth()) { piece = it }
                Row(Modifier.clickable { drink = !drink }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (drink) "☑" else "☐", color = Accent, fontSize = 20.sp)
                    Text("  Это напиток (считать в жидкость)", color = Color.White, fontSize = 14.sp)
                }
                val kc = (d(p) ?: 0.0) * 4 + (d(f) ?: 0.0) * 9 + (d(c) ?: 0.0) * 4
                Text("≈ ${kc.roundToInt()} ккал на 100", color = Good, fontSize = 14.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                FoodStore.addCustom(name.trim(), d(p)!!, d(f)!!, d(c)!!, piece.toIntOrNull(), drink)
                onDismiss()
            }, enabled = ok) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun NumIn(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
