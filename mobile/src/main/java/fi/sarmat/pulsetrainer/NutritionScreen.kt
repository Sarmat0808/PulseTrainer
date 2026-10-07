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
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru"))
private val hmFmt = SimpleDateFormat("HH:mm", Locale("ru"))

private val NUTRITION_CARDS = linkedMapOf(
    "status" to "Статус дня (норма набрана / осталось)",
    "actions" to "Кнопки: + Еда / Забыл внести",
    "my" to "Мои блюда (быстрое добавление)",
    "macros" to "Белки, жиры, углеводы, жидкость",
    "drinks" to "Напитки и перекусы — одно нажатие",
    "table" to "Съедено сегодня (список)",
    "fill" to "Чем добрать норму",
    "workout" to "До и после тренировки",
    "plan" to "Пример меню на день",
    "supplements" to "Витамины, минералы, масла",
    "custom" to "Мои продукты",
    "history" to "Последние 7 дней",
)
private val NUTRITION_MORE = setOf("table", "fill", "workout", "plan", "supplements", "custom", "history")

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
    var drinksEdit by remember { mutableStateOf(false) }
    var confirmAdd by remember { mutableStateOf<Nutrition.Suggestion?>(null) }

    val entries = remember(version, date) { FoodStore.entries(date) }
    val target = remember(version, date, profile, goal, workouts) { FoodStore.targets(date) }
    val total = DayTotals.of(entries)
    val forgot = remember(version, date) { FoodStore.forgot(date) }
    val reached = Nutrition.reached(total, target)
    val foods = remember(version) { FoodStore.foods() }
    val layout = rememberCardLayout("nutrition", NUTRITION_CARDS.keys.toList(), NUTRITION_MORE)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Питание", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                ArrangeButton("nutrition", NUTRITION_CARDS, NUTRITION_MORE)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { date = date.minusDays(1) }) { Text("◀", fontSize = 18.sp) }
                Text(
                    if (date == LocalDate.now()) "Сегодня" else date.format(dayFmt),
                    color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
                TextButton(onClick = { if (date < LocalDate.now()) date = date.plusDays(1) }) { Text("▶", fontSize = 18.sp) }
            }
        }
        val renderCard: @Composable (String) -> Unit = { id ->
                when (id) {
                    "status" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SnackTipCard(date, foods, entries, target)
                        StatusBox(reached, forgot, target, total)
                    }
                    "macros" -> Section {
                        MacroBar("Калории", total.kcal, target.kcal.toDouble(), "ккал", Color(0xFFF2994A))
                        MacroBar("Белки", total.p, target.p.toDouble(), "г", Color(0xFFEB5757))
                        MacroBar("Жиры", total.f, target.f.toDouble(), "г", Color(0xFFF2C94C))
                        MacroBar("Углеводы", total.c, target.c.toDouble(), "г", Color(0xFF2D9CDB))
                        MacroBar("Жидкость", total.fluidMl.toDouble(), target.waterMl.toDouble(), "мл", Color(0xFF56CCF2))
                        Text(
                            "Норма: ${target.kcal} ккал · Б ${target.p} · Ж ${target.f} · У ${target.c} г" +
                                if (FoodStore.trainedOn(date)) " (день тренировки)" else "",
                            color = Dim, fontSize = 15.sp
                        )
                    }
                    "actions" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { addOpen = true }, modifier = Modifier.weight(1f).height(52.dp)) { Text("+ Еда", fontSize = 17.sp) }
                        OutlinedButton(onClick = { FoodStore.setForgot(date, !forgot) }, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text(if (forgot) "Отменить «забыл»" else "Забыл внести", fontSize = 15.sp)
                        }
                    }
                    "fill" -> Section {
                        Text("Чем добрать норму", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        val gp = target.p - total.p
                        val gf = target.f - total.f
                        val gc = target.c - total.c
                        val gk = target.kcal - total.kcal
                        if (gp <= 5 && gk <= 80) {
                            Text("✓ Норма набрана. Дальше — вода, чай, овощи по желанию.", color = Good, fontSize = 15.sp)
                        } else {
                            Text("Осталось добрать:", color = Dim, fontSize = 15.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GapBox("Белки", gp, "г", Color(0xFFEB5757), Modifier.weight(1f))
                                GapBox("Жиры", gf, "г", Color(0xFFF2C94C), Modifier.weight(1f))
                                GapBox("Углев.", gc, "г", Color(0xFF2D9CDB), Modifier.weight(1f))
                                GapBox("Ккал", gk, "", Color(0xFFF2994A), Modifier.weight(1f))
                            }
                            Text("Нажмите на вариант — он добавится в дневник.", color = Dim, fontSize = 15.sp)
                            listOf(Triple('p', gp, "Белок — выберите одно:"), Triple('c', gc, "Углеводы — выберите одно:"), Triple('f', gf, "Жиры — выберите одно:"))
                                .forEach { (m, gap, title) ->
                                    val opts = Nutrition.cover(m, gap, foods)
                                    if (opts.isNotEmpty()) {
                                        Text(title, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                                        opts.forEach { o ->
                                            Text("•  ${o.text}", color = if (o.enough) Color.White else Dim, fontSize = 15.sp,
                                                modifier = Modifier.fillMaxWidth().clickable { confirmAdd = o }.padding(vertical = 3.dp))
                                        }
                                    }
                                }
                            Text("Совет: белок закройте в первую очередь — он важнее всего для мышц.", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    "my" -> MyFoodsCard(date)
                    "drinks" -> Section {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Напитки — одно нажатие", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("Изменить", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { drinksEdit = true }.padding(6.dp))
                        }
                        val byId = foods.associateBy { it.id }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FoodStore.drinks().mapNotNull { (id, ml) -> byId[id]?.let { it to ml } }.forEach { (f, ml) ->
                                AssistChip(onClick = {
                                    FoodStore.add(date, f, ml.toDouble())
                                    Toast.makeText(ctx, "${f.name}: +$ml мл", Toast.LENGTH_SHORT).show()
                                }, label = { Text("${shortName(f.name)} $ml", fontSize = 15.sp, maxLines = 1) })
                            }
                        }
                        val coffee = entries.count { it.foodId == "coffee" }
                        Nutrition.drinkAdvice(total.fluidMl, coffee, target.waterMl).forEach { Text(it, color = Color.White, fontSize = 15.sp) }
                    }
                    "table" -> Section {
                        Text("Съедено (${entries.size})", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (entries.isEmpty()) Text("Пока пусто. Нажмите «+ Еда» и внесите вес продукта.", color = Dim, fontSize = 15.sp)
                        else {
                            entries.forEach { e ->
                                var ask by remember(e) { mutableStateOf(false) }
                                Column(Modifier.fillMaxWidth().clickable { ask = true }.padding(vertical = 4.dp)) {
                                    Row {
                                        Text(e.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        Text(hmFmt.format(Date(e.time)), color = Dim, fontSize = 15.sp)
                                    }
                                    Text("${amountText(e)} · Б ${n(e.p)} · Ж ${n(e.f)} · У ${n(e.c)} · ${n(e.kcal)} ккал", color = Dim, fontSize = 15.sp)
                                }
                                if (ask) AlertDialog(
                                    onDismissRequest = { ask = false },
                                    title = { Text("Удалить «${e.name}»?") },
                                    confirmButton = { TextButton(onClick = { ask = false; FoodStore.remove(date, e) }) { Text("Удалить") } },
                                    dismissButton = { TextButton(onClick = { ask = false }) { Text("Отмена") } },
                                )
                            }
                            Text("Итого за день", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                            Row(Modifier.fillMaxWidth()) {
                                TotalCell(n(total.kcal), "ккал", Modifier.weight(1f))
                                TotalCell(n(total.p), "белки, г", Modifier.weight(1f))
                                TotalCell(n(total.f), "жиры, г", Modifier.weight(1f))
                                TotalCell(n(total.c), "углев., г", Modifier.weight(1f))
                            }
                            Text("Нажмите на продукт, чтобы удалить", color = Dim, fontSize = 15.sp)
                        }
                    }
                    "workout" -> Section {
                        Text("До тренировки", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Nutrition.BEFORE_WORKOUT.forEach { Tip(it) }
                        Text("После тренировки", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Nutrition.AFTER_WORKOUT.forEach { Tip(it) }
                    }
                    "plan" -> {
                        val (plan, totals) = remember(target, version) { Nutrition.dayPlan(target, foods) }
                        Section {
                            Text("Пример меню на день", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Из ваших обычных продуктов, под норму ${target.kcal} ккал", color = Dim, fontSize = 15.sp)
                            var meal = ""
                            plan.forEach { it ->
                                if (it.meal != meal) {
                                    meal = it.meal
                                    Text(meal, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                                }
                                Text("• ${it.name} — ${it.label}", color = Color.White, fontSize = 15.sp)
                            }
                            Text("Итого: ${totals.kcal.roundToInt()} ккал · Б ${totals.p.roundToInt()} · Ж ${totals.f.roundToInt()} · У ${totals.c.roundToInt()} г",
                                color = Good, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("Гречку, рис, булгур, макароны и киноа можно менять между собой 1:1 по сухому весу.", color = Dim, fontSize = 15.sp)
                        }
                    }
                    "supplements" -> Section {
                        Text("Витамины, минералы, масла", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Nutrition.SUPPLEMENTS.forEach { Tip(it) }
                    }
                    "custom" -> {
                        val customs by FoodStore.custom.collectAsState()
                        Section {
                            Text("Мои продукты", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Добавьте продукт с упаковки: белки, жиры, углеводы на 100 г.", color = Dim, fontSize = 15.sp)
                            customs.forEach { f ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${f.name}: Б ${n(f.p)} · Ж ${n(f.f)} · У ${n(f.c)} · ${f.kcal.roundToInt()} ккал/100", color = Color.White,
                                        fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    var editF by remember(f) { mutableStateOf(false) }
                                    TextButton(onClick = { editF = true }) { Text("✎") }
                                    TextButton(onClick = { FoodStore.deleteCustom(f.id) }) { Text("✕", color = Danger) }
                                    if (editF) CustomFoodDialog(edit = f, onDismiss = { editF = false })
                                }
                            }
                            OutlinedButton(onClick = { customOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Свой продукт") }
                        }
                    }
                    "history" -> {
                        val hist = remember(version, profile, goal) { FoodStore.history(7) }
                        Section {
                            Text("Последние 7 дней", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            hist.forEach { h ->
                                val (mark, col) = when {
                                    h.forgot -> "забыл" to Dim
                                    Nutrition.reached(h.totals, h.target) -> "✓ норма" to Good
                                    h.entries.isEmpty() -> "нет записей" to Dim
                                    else -> "✗ недобор" to Danger
                                }
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(h.date.format(DateTimeFormatter.ofPattern("EEEE, d.MM", Locale("ru"))), color = Color.White, fontSize = 15.sp)
                                        Text("${n(h.totals.kcal)} ккал · Б ${n(h.totals.p)} · Ж ${n(h.totals.f)} · У ${n(h.totals.c)}", color = Dim, fontSize = 15.sp)
                                    }
                                    Text(mark, color = col, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
        }
        layout.top.forEach { id -> item(key = id) { renderCard(id) } }
        item(key = "more") { MoreBlock(layout.more.size, "Подробнее: съедено, меню, советы") { layout.more.forEach { renderCard(it) } } }
    }

    if (addOpen) AddFoodDialog(foods, onDismiss = { addOpen = false }) { f, amount ->
        FoodStore.add(date, f, amount)
        addOpen = false
        Toast.makeText(ctx, "Добавлено: ${f.name}", Toast.LENGTH_SHORT).show()
    }
    if (customOpen) CustomFoodDialog(onDismiss = { customOpen = false })
    if (drinksEdit) DrinksEditor(foods) { drinksEdit = false }
    confirmAdd?.let { o ->
        AlertDialog(
            onDismissRequest = { confirmAdd = null },
            title = { Text("Добавить в дневник?") },
            text = { Text("${o.food.name} — ${Nutrition.amountLabel(o.food, o.grams)}", fontSize = 16.sp) },
            confirmButton = {
                TextButton(onClick = {
                    FoodStore.add(date, o.food, o.grams); confirmAdd = null
                    Toast.makeText(ctx, "Добавлено: ${o.food.name}", Toast.LENGTH_SHORT).show()
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { confirmAdd = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun GapBox(label: String, gap: Double, unit: String, color: Color, modifier: Modifier) {
    val done = gap <= 2
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF232A33)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (done) "✓" else "${gap.roundToInt()}", color = if (done) Good else color, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(if (done) label else "$label${if (unit.isNotEmpty()) ", $unit" else ""}", color = Dim, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun TotalCell(v: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Text(label, color = Dim, fontSize = 15.sp, maxLines = 1)
    }
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
        Text(a, color = col, fontSize = 15.sp, fontWeight = w, modifier = Modifier.weight(3f))
        Text(b, color = col, fontSize = 15.sp, fontWeight = w, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End)
        Text(c, color = col, fontSize = 15.sp, fontWeight = w, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(d, color = col, fontSize = 15.sp, fontWeight = w, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(e, color = col, fontSize = 15.sp, fontWeight = w, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(f, color = col, fontSize = 15.sp, fontWeight = w, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddFoodDialog(foods: List<Food>, onDismiss: () -> Unit, pickOnly: Boolean = false, onAdd: (Food, Double) -> Unit) {
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<Food?>(null) }
    var amount by remember { mutableStateOf("") }
    val recent = remember { FoodStore.recentIds() }
    fun choose(item: Food) {
        if (pickOnly) { onAdd(item, 0.0); return }
        chosen = item
        amount = (FoodStore.lastAmount(item.id) ?: (item.pieceG ?: if (item.drink) 250 else 100).toDouble()).roundToInt().toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(chosen?.name ?: if (pickOnly) "Выберите продукт" else "Добавить еду") },
        text = {
            val f = chosen
            if (f == null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Поиск") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    // Categories in one scrolling line — the list stays visible.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Nutrition.CATEGORIES.forEach { c ->
                            FilterChip(selected = cat == c, onClick = { cat = if (cat == c) null else c }, label = { Text(c, fontSize = 13.sp) })
                        }
                    }
                    // Search by word beginnings too: «кура» finds «Куриная грудка», «греч» — «Гречка».
                    fun matches(name: String, w: String): Boolean {
                        if (name.contains(w, ignoreCase = true)) return true
                        val stem = w.lowercase().take(maxOf(3, w.length - 1))
                        return name.lowercase().split(' ', '(', ')', ',', '/').any { it.startsWith(stem) }
                    }
                    val list = foods
                        .filter { cat == null || it.category == cat }
                        .filter { query.isBlank() || query.trim().split(' ').filter { w -> w.isNotBlank() }.all { w -> matches(it.name, w) || it.category.contains(w, ignoreCase = true) } }
                        .sortedBy { val i = recent.indexOf(it.id); if (i < 0) 999 else i }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.id }) { item ->
                            Column(Modifier.fillMaxWidth().clickable { choose(item) }.padding(vertical = 8.dp)) {
                                Text(item.name, color = Color.White, fontSize = 16.sp)
                                Text("Б ${n(item.p)} · Ж ${n(item.f)} · У ${n(item.c)} · ${item.kcal.roundToInt()} ккал на 100 ${if (item.drink) "мл" else "г"}",
                                    color = Dim, fontSize = 14.sp)
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1, 2, 3).forEach { k ->
                                AssistChip(onClick = { amount = (pg * k).toString() }, label = { Text("$k × ${f.pieceName ?: "шт"}") })
                            }
                        }
                    }
                    Text("Б ${n(e.p)} г · Ж ${n(e.f)} г · У ${n(e.c)} г · ${n(e.kcal)} ккал", color = Good, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (f.id !in FoodStore.myIds()) TextButton(onClick = { FoodStore.setMy(FoodStore.myIds() + f.id) }) { Text("☆ В «Мои блюда»") }
                    TextButton(onClick = { chosen = null }) { Text("← Другой продукт") }
                }
            }
        },
        confirmButton = {
            val f = chosen
            val a = amount.replace(',', '.').toDoubleOrNull()
            if (!pickOnly) Button(onClick = { if (f != null && a != null && a > 0) onAdd(f, a) }, enabled = f != null && a != null && a > 0) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Create or edit your own dish / product (БЖУ per 100 g from the package or your recipe). */
@Composable
fun CustomFoodDialog(edit: Food? = null, onSaved: (Food) -> Unit = {}, onDismiss: () -> Unit) {
    fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v).replace(',', '.')
    var name by remember { mutableStateOf(edit?.name ?: "") }
    var p by remember { mutableStateOf(edit?.p?.let(::fmt) ?: "") }
    var f by remember { mutableStateOf(edit?.f?.let(::fmt) ?: "") }
    var c by remember { mutableStateOf(edit?.c?.let(::fmt) ?: "") }
    var piece by remember { mutableStateOf(edit?.pieceG?.toString() ?: "") }
    var drink by remember { mutableStateOf(edit?.drink ?: false) }
    fun d(s: String) = s.replace(',', '.').toDoubleOrNull()
    val ok = name.isNotBlank() && d(p) != null && d(f) != null && d(c) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit != null) "Изменить блюдо" else "Своё блюдо / продукт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("На 100 ${if (drink) "мл" else "г"} (с упаковки или по рецепту):", color = Dim, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumIn("Белки", p, Modifier.weight(1f)) { p = it }
                    NumIn("Жиры", f, Modifier.weight(1f)) { f = it }
                    NumIn("Углев.", c, Modifier.weight(1f)) { c = it }
                }
                NumIn("Обычная порция, ${if (drink) "мл" else "г"} (необязательно)", piece, Modifier.fillMaxWidth()) { piece = it }
                Row(Modifier.clickable { drink = !drink }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (drink) "☑" else "☐", color = Accent, fontSize = 22.sp)
                    Text("  Это напиток (считать в жидкость)", color = Color.White, fontSize = 15.sp)
                }
                val kc = (d(p) ?: 0.0) * 4 + (d(f) ?: 0.0) * 9 + (d(c) ?: 0.0) * 4
                Text("≈ ${kc.roundToInt()} ккал на 100", color = Good, fontSize = 15.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                val pg = piece.toIntOrNull()?.takeIf { it > 0 }
                val saved = if (edit != null) {
                    val nf = edit.copy(name = name.trim(), p = d(p)!!, f = d(f)!!, c = d(c)!!, pieceG = pg, pieceName = if (pg != null) "порция" else null, drink = drink)
                    FoodStore.updateCustom(nf); nf
                } else FoodStore.addCustom(name.trim(), d(p)!!, d(f)!!, d(c)!!, pg, drink)
                onSaved(saved)
                onDismiss()
            }, enabled = ok) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun shortName(n: String) = n.substringBefore(" (").let { if (it.length > 22) it.take(20) + "…" else it }

/** Your everyday foods: tap → grams → added. Order, add and edit as you like. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyFoodsCard(date: LocalDate) {
    val ctx = LocalContext.current
    val v by FoodStore.version.collectAsState()
    val my = remember(v) { FoodStore.myFoods() }
    var pick by remember { mutableStateOf<Food?>(null) }
    var edit by remember { mutableStateOf(false) }
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Мои блюда", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Изменить", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { edit = true }.padding(6.dp))
        }
        if (my.isEmpty()) Text("Добавьте свои обычные блюда — потом только вводите граммы.", color = Dim, fontSize = 15.sp)
        my.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { pick = f }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text("Б ${n(f.p)} · Ж ${n(f.f)} · У ${n(f.c)} · ${f.kcal.roundToInt()} ккал на 100", color = Dim, fontSize = 14.sp)
                }
                Text("+", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    pick?.let { f ->
        AmountDialog(f, onDismiss = { pick = null }) { amount ->
            FoodStore.add(date, f, amount); pick = null
            Toast.makeText(ctx, "Добавлено: ${f.name}", Toast.LENGTH_SHORT).show()
        }
    }
    if (edit) MyFoodsEditor { edit = false }
}

/** Enter only the amount: grams (or ml), with quick buttons for portions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountDialog(f: Food, onDismiss: () -> Unit, onAdd: (Double) -> Unit) {
    var amount by remember { mutableStateOf((FoodStore.lastAmount(f.id) ?: (f.pieceG ?: if (f.drink) 250 else 100).toDouble()).roundToInt().toString()) }
    val a = amount.replace(',', '.').toDoubleOrNull() ?: 0.0
    val e = Nutrition.entry(f, a)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                    label = { Text(if (f.drink) "Количество, мл" else "Вес, г") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                val pg = f.pieceG
                if (pg != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3).forEach { k -> AssistChip(onClick = { amount = (pg * k).toString() }, label = { Text("$k × ${f.pieceName ?: "$pg г"}") }) }
                }
                Text("Б ${n(e.p)} г · Ж ${n(e.f)} г · У ${n(e.c)} г · ${n(e.kcal)} ккал", color = Good, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = { Button(onClick = { if (a > 0) onAdd(a) }, enabled = a > 0) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun MyFoodsEditor(onClose: () -> Unit) {
    val v by FoodStore.version.collectAsState()
    val my = remember(v) { FoodStore.myFoods() }
    var adding by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Food?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Мои блюда") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("↑ ↓ — порядок, ✎ — изменить своё блюдо, ✕ — убрать из списка.", color = Dim, fontSize = 14.sp)
                my.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).padding(4.dp)) {
                        Text(f.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        TextButton(onClick = { FoodStore.moveMy(f.id, -1) }) { Text("↑", fontSize = 18.sp) }
                        TextButton(onClick = { FoodStore.moveMy(f.id, 1) }) { Text("↓", fontSize = 18.sp) }
                        if (f.custom) TextButton(onClick = { editing = f }) { Text("✎", fontSize = 18.sp) }
                        TextButton(onClick = { FoodStore.setMy(FoodStore.myIds() - f.id) }) { Text("✕", color = Danger, fontSize = 16.sp) }
                    }
                }
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Новое блюдо (своё БЖУ)") }
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Из базы продуктов") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
    )
    if (creating) CustomFoodDialog(onDismiss = { creating = false })
    editing?.let { CustomFoodDialog(edit = it, onDismiss = { editing = null }) }
    if (adding) AddFoodDialog(FoodStore.foods(), onDismiss = { adding = false }, pickOnly = true) { f, _ ->
        FoodStore.setMy(FoodStore.myIds() + f.id); adding = false
    }
}

/** One-tap drinks: your list, order and amount (ml). */
@Composable
private fun DrinksEditor(foods: List<Food>, onClose: () -> Unit) {
    var list by remember { mutableStateOf(FoodStore.drinks()) }
    var adding by remember { mutableStateOf(false) }
    fun save(l: List<Pair<String, Int>>) { list = l; FoodStore.setDrinks(l) }
    val byId = foods.associateBy { it.id }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Напитки — одно нажатие") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEachIndexed { i, (id, ml) ->
                    val f = byId[id] ?: return@forEachIndexed
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).padding(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2)
                            TextButton(onClick = { if (i > 0) save(list.toMutableList().also { it[i] = it[i - 1]; it[i - 1] = list[i] }) }) { Text("↑") }
                            TextButton(onClick = { if (i < list.lastIndex) save(list.toMutableList().also { it[i] = it[i + 1]; it[i + 1] = list[i] }) }) { Text("↓") }
                            TextButton(onClick = { save(list.filterIndexed { k, _ -> k != i }) }) { Text("✕", color = Danger) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { save(list.toMutableList().also { it[i] = id to (ml - 50).coerceAtLeast(50) }) }) { Text("− 50", fontSize = 16.sp) }
                            Text("$ml мл", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            TextButton(onClick = { save(list.toMutableList().also { it[i] = id to (ml + 50).coerceAtMost(2000) }) }) { Text("+ 50", fontSize = 16.sp) }
                        }
                    }
                }
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить напиток") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
    )
    if (adding) AddFoodDialog(foods.filter { it.drink }, onDismiss = { adding = false }, pickOnly = true) { f, _ ->
        save(list + (f.id to (f.pieceG ?: 250))); adding = false
    }
}

@Composable
private fun NumIn(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(label, fontSize = 15.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}


@Composable
private fun StatusBox(reached: Boolean, forgot: Boolean, target: fi.sarmat.pulsetrainer.core.Targets, total: DayTotals) {
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

/** «Сейчас самое время …» — protein drink / bar with one tap. */
@Composable
private fun SnackTipCard(date: LocalDate, foods: List<Food>, entries: List<FoodEntry>, target: fi.sarmat.pulsetrainer.core.Targets) {
    if (date != LocalDate.now()) return
    val ctx = LocalContext.current
    val workouts by PhoneStore.workouts.collectAsState()
    val ext by PhoneStore.ext.collectAsState()
    val lastEnd = (workouts.filter { fi.sarmat.pulsetrainer.core.Physiology.isRealWorkout(it) }.map { it.end } +
        ext.filter { it.minutes >= 20 }.map { it.end }).maxOrNull()
    val tip = Nutrition.snackTip(foods, entries, target, System.currentTimeMillis(), lastEnd,
        java.time.LocalTime.now().hour) ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF233A2C)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("💡 " + tip.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tip.why, color = Color.White, fontSize = 15.sp)
        Button(onClick = {
            FoodStore.add(date, tip.food, tip.grams)
            Toast.makeText(ctx, "Добавлено: ${tip.food.name}", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("Выпил / съел — добавить", fontSize = 16.sp) }
    }
}
