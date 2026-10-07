package fi.sarmat.pulsetrainer.core

import kotlin.math.max
import kotlin.math.roundToInt

/** A product. Values per 100 g (or 100 ml for drinks). */
data class Food(
    val id: String,
    val name: String,
    val p: Double,
    val f: Double,
    val c: Double,
    val category: String,
    /** Weight of one piece / serving in grams (egg, banana, scoop...). */
    val pieceG: Int? = null,
    val pieceName: String? = null,
    /** Drink: amount is in ml and counts toward fluids. */
    val drink: Boolean = false,
    val custom: Boolean = false,
) {
    val kcal: Double get() = p * 4 + f * 9 + c * 4
}

data class FoodEntry(
    val time: Long,
    val foodId: String,
    val name: String,
    /** Grams (or ml for drinks). */
    val amount: Double,
    val p: Double,
    val f: Double,
    val c: Double,
    val drinkMl: Int,
) {
    val kcal: Double get() = p * 4 + f * 9 + c * 4
}

data class Targets(val kcal: Int, val p: Int, val f: Int, val c: Int, val waterMl: Int)

data class DayTotals(val kcal: Double, val p: Double, val f: Double, val c: Double, val fluidMl: Int) {
    companion object {
        fun of(list: List<FoodEntry>) = DayTotals(
            list.sumOf { it.kcal }, list.sumOf { it.p }, list.sumOf { it.f }, list.sumOf { it.c }, list.sumOf { it.drinkMl }
        )
    }
}

/**
 * Nutrition for strength + heart + lean mass, built from simple everyday products.
 * - Energy: Mifflin-St Jeor BMR × activity (1.45, 1.6 on training days) + goal adjustment.
 * - Protein 1.8 g/kg for gaining (1.6–2.2 range, Morton 2018), 2.0 g/kg when cutting.
 * - Fat ~0.9 g/kg (≈25–30 % of energy) — hormones, vitamins A/D/E/K.
 * - Carbs: the rest — fuel for training.
 * - Fluids ~35 ml/kg/day + ~500 ml per hour of training.
 */
object Nutrition {

    fun targets(p: Profile, goal: Goal, trainedToday: Boolean, workoutKcal: Double = 0.0): Targets {
        val tdee = Physiology.bmr(p) * (if (trainedToday) 1.6 else 1.45)
        val adj = when (goal) {
            Goal.HYBRID -> 250.0
            Goal.MASS -> 350.0
            Goal.FAT_LOSS -> -450.0
            else -> 0.0
        }
        val kcal = (tdee + adj).roundToInt()
        val protein = (p.weightKg * when (goal) { Goal.FAT_LOSS -> 2.0; Goal.HYBRID, Goal.MASS -> 1.8; else -> 1.6 }).roundToInt()
        val fat = (p.weightKg * 0.9).roundToInt()
        val carbs = max(100, ((kcal - protein * 4 - fat * 9) / 4.0).roundToInt())
        val water = (p.weightKg * 35 + if (trainedToday) 500 else 0).roundToInt()
        return Targets(kcal, protein, fat, carbs, water)
    }

    /** Daily norm reached = energy and protein at least 95 % of target. */
    fun reached(t: DayTotals, target: Targets) = t.kcal >= target.kcal * 0.95 && t.p >= target.p * 0.95

    fun entry(food: Food, amount: Double, time: Long = System.currentTimeMillis()): FoodEntry {
        val k = amount / 100.0
        return FoodEntry(time, food.id, food.name, amount, food.p * k, food.f * k, food.c * k, if (food.drink) amount.roundToInt() else 0)
    }

    // ---------------- Everyday products (per 100 g / 100 ml) ----------------

    val BASE: List<Food> = listOf(
        // Grains: weigh DRY before cooking (more accurate), or use the cooked line
        Food("buckwheat", "Гречка (сухая)", 12.6, 3.3, 57.1, "Крупы"),
        Food("buckwheat_c", "Гречка (варёная)", 4.5, 1.6, 20.0, "Крупы"),
        Food("bulgur", "Булгур (сухой)", 12.3, 1.3, 57.6, "Крупы"),
        Food("bulgur_c", "Булгур (варёный)", 3.1, 0.2, 14.0, "Крупы"),
        Food("rice", "Рис (сухой)", 7.0, 0.6, 77.0, "Крупы"),
        Food("rice_c", "Рис (варёный)", 2.7, 0.3, 28.0, "Крупы"),
        Food("oats", "Овсянка (хлопья)", 12.3, 6.1, 59.5, "Крупы"),
        Food("quinoa", "Киноа (сухая)", 14.1, 6.1, 57.2, "Крупы"),
        Food("pasta", "Макароны (сухие)", 11.0, 1.3, 71.0, "Крупы"),
        Food("pasta_c", "Макароны (варёные)", 5.8, 0.9, 30.0, "Крупы"),
        Food("potato", "Картофель (варёный)", 2.0, 0.4, 17.0, "Крупы"),
        Food("bread_rye", "Хлеб ржаной", 6.6, 1.2, 34.0, "Крупы", 30, "ломтик"),
        Food("bread", "Хлеб пшеничный", 8.0, 1.0, 49.0, "Крупы", 30, "ломтик"),
        Food("vertuta", "Вертута с сыром", 8.0, 14.0, 38.0, "Крупы", 100, "шт"),
        // Protein
        Food("chicken_raw", "Куриная грудка (сырая)", 23.6, 1.9, 0.4, "Белок"),
        Food("chicken", "Куриная грудка (готовая)", 29.0, 3.5, 0.0, "Белок"),
        Food("chicken_thigh", "Куриное бедро без кожи (сырое)", 18.6, 7.8, 0.0, "Белок"),
        Food("egg", "Яйцо варёное", 12.7, 11.5, 0.7, "Белок", 55, "шт"),
        Food("egg_fried", "Яичница", 13.6, 15.0, 0.8, "Белок", 60, "яйцо"),
        Food("cottage5", "Творог 5%", 17.0, 5.0, 3.0, "Белок"),
        Food("cottage9", "Творог 9%", 16.7, 9.0, 2.0, "Белок"),
        Food("cottage0", "Творог 0–1%", 18.0, 1.0, 3.3, "Белок"),
        Food("cheese", "Сыр твёрдый", 25.0, 27.0, 0.0, "Белок", 20, "ломтик"),
        Food("sausage", "Сардельки (проверьте упаковку)", 12.0, 22.0, 1.5, "Белок", 70, "шт"),
        Food("sardine", "Сардины в масле (консервы)", 24.0, 14.0, 0.0, "Белок", 120, "банка"),
        Food("tuna", "Тунец в собств. соку (консервы)", 23.0, 1.0, 0.0, "Белок", 150, "банка"),
        Food("protein", "Протеин (порошок)", 78.0, 5.0, 8.0, "Белок", 30, "мерная ложка"),
        Food("protein_bar", "Протеиновый батончик", 33.0, 13.0, 33.0, "Белок", 60, "шт"),
        // Vegetables & fruit
        Food("veg_mix", "Овощная смесь (замороженная)", 2.5, 0.3, 7.0, "Овощи и фрукты"),
        Food("broccoli", "Брокколи", 2.8, 0.4, 6.6, "Овощи и фрукты"),
        Food("green_beans", "Стручковая фасоль", 1.8, 0.2, 7.0, "Овощи и фрукты"),
        Food("peas", "Зелёный горошек", 5.0, 0.4, 14.0, "Овощи и фрукты"),
        Food("pepper", "Перец сладкий", 1.0, 0.3, 6.0, "Овощи и фрукты"),
        Food("banana", "Банан", 1.1, 0.3, 22.8, "Овощи и фрукты", 120, "шт"),
        Food("apple", "Яблоко", 0.4, 0.2, 11.8, "Овощи и фрукты", 150, "шт"),
        // Fats & extras
        Food("rapeseed_oil", "Масло рапсовое", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("olive_oil", "Масло оливковое", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("butter", "Масло сливочное", 0.5, 82.0, 0.8, "Масла и добавки", 10, "кусочек"),
        Food("peanut_butter", "Арахисовая паста", 25.0, 50.0, 20.0, "Масла и добавки", 15, "ст. ложка"),
        Food("walnuts", "Грецкие орехи", 15.0, 65.0, 14.0, "Масла и добавки", 30, "горсть"),
        Food("seeds", "Семечки подсолнечника", 20.8, 51.5, 20.0, "Масла и добавки", 30, "горсть"),
        Food("honey", "Мёд", 0.3, 0.0, 82.0, "Масла и добавки", 15, "ст. ложка"),
        Food("sugar", "Сахар", 0.0, 0.0, 100.0, "Масла и добавки", 5, "ч. ложка"),
        // Drinks (per 100 ml)
        Food("water", "Вода", 0.0, 0.0, 0.0, "Напитки", 250, "стакан", drink = true),
        Food("tea", "Чай травяной", 0.0, 0.0, 0.0, "Напитки", 250, "кружка", drink = true),
        Food("coffee", "Кофе чёрный", 0.2, 0.0, 0.0, "Напитки", 200, "чашка", drink = true),
        Food("milk", "Молоко 2,5%", 2.9, 2.5, 4.7, "Напитки", 250, "стакан", drink = true),
        Food("kefir", "Кефир 2,5%", 2.9, 2.5, 4.0, "Напитки", 250, "стакан", drink = true),
        Food("juice", "Сок", 0.7, 0.2, 10.0, "Напитки", 200, "стакан", drink = true),
    )

    val CATEGORIES = listOf("Крупы", "Белок", "Овощи и фрукты", "Масла и добавки", "Напитки", "Мои продукты")

    // ---------------- Advice ----------------

    private data class Portion(val foodId: String, val grams: Double, val label: String)

    /** Concrete "what to eat now" suggestions from everyday products to close today's gap. */
    fun whatToEat(total: DayTotals, t: Targets, foods: List<Food>): List<String> {
        val byId = foods.associateBy { it.id }
        var pGap = t.p - total.p
        var kGap = t.kcal - total.kcal
        val out = ArrayList<String>()
        if (pGap <= 5 && kGap <= 80) return listOf("Норма на сегодня набрана — дальше по самочувствию: вода, чай, овощи.")
        val proteinPortions = listOf(
            Portion("cottage5", 200.0, "Творог 5% — 200 г"),
            Portion("chicken", 150.0, "Куриная грудка (готовая) — 150 г"),
            Portion("egg", 165.0, "3 яйца"),
            Portion("tuna", 150.0, "Банка тунца — 150 г"),
            Portion("protein", 30.0, "Протеин — 1 мерная ложка (30 г)"),
            Portion("kefir", 500.0, "Кефир — 500 мл"),
            Portion("cheese", 40.0, "Сыр — 2 ломтика (40 г)"),
        )
        val carbPortions = listOf(
            Portion("buckwheat", 80.0, "Гречка — 80 г сухой"),
            Portion("rice", 80.0, "Рис — 80 г сухого"),
            Portion("oats", 80.0, "Овсянка — 80 г"),
            Portion("pasta", 90.0, "Макароны — 90 г сухих"),
            Portion("bulgur", 80.0, "Булгур — 80 г сухого"),
            Portion("banana", 120.0, "Банан"),
        )
        var i = 0
        while (pGap > 15 && i < proteinPortions.size && out.size < 3) {
            val pp = proteinPortions[i++]
            val f = byId[pp.foodId] ?: continue
            val e = entry(f, pp.grams)
            if (e.kcal > kGap + 150 && kGap < 300) continue
            out += "${pp.label}: +${e.p.roundToInt()} г белка, ${e.kcal.roundToInt()} ккал"
            pGap -= e.p; kGap -= e.kcal
        }
        var j = 0
        while (kGap > 250 && j < carbPortions.size && out.size < 5) {
            val cp = carbPortions[j++]
            val f = byId[cp.foodId] ?: continue
            val e = entry(f, cp.grams)
            out += "${cp.label}: ${e.kcal.roundToInt()} ккал, ${e.c.roundToInt()} г углеводов"
            kGap -= e.kcal; pGap -= e.p
        }
        if (kGap > 150) {
            out += "Добавьте 1 ст. ложку рапсового масла в кашу или салат: +90 ккал, полезные омега-3"
        }
        return out
    }

    data class PlanItem(val meal: String, val foodId: String, val name: String, val grams: Double, val label: String)

    /** Example day menu from everyday products, scaled to the targets. */
    fun dayPlan(t: Targets, foods: List<Food>): Pair<List<PlanItem>, DayTotals> {
        val byId = foods.associateBy { it.id }
        val fixed = mutableListOf(
            PlanItem("Завтрак", "milk", "Молоко 2,5%", 250.0, "250 мл"),
            PlanItem("Завтрак", "egg", "Яйцо варёное", 110.0, "2 шт"),
            PlanItem("Завтрак", "banana", "Банан", 120.0, "1 шт"),
            PlanItem("Обед", "chicken", "Куриная грудка (готовая)", 180.0, "180 г"),
            PlanItem("Обед", "veg_mix", "Овощная смесь", 200.0, "200 г"),
            PlanItem("Обед", "rapeseed_oil", "Масло рапсовое", 10.0, "1 ст. ложка"),
            PlanItem("Перекус", "cottage5", "Творог 5%", 200.0, "200 г"),
            PlanItem("Перекус", "kefir", "Кефир 2,5%", 250.0, "250 мл"),
            PlanItem("После тренировки", "protein", "Протеин", 30.0, "1 ложка с водой или молоком"),
            PlanItem("Ужин", "tuna", "Тунец или 3 яйца", 150.0, "1 банка"),
            PlanItem("Ужин", "broccoli", "Брокколи / фасоль", 200.0, "200 г"),
        )
        val fixedTotals = DayTotals.of(fixed.mapNotNull { byId[it.foodId]?.let { f -> entry(f, it.grams) } })
        // Fill the remaining energy with three grain portions (dry weight).
        val left = (t.kcal - fixedTotals.kcal).coerceAtLeast(300.0)
        val perPortionKcal = left / 3
        val grains = listOf(Triple("Завтрак", "oats", "Овсянка"), Triple("Обед", "buckwheat", "Гречка"), Triple("Ужин", "rice", "Рис или булгур"))
        val grainItems = grains.mapNotNull { (meal, id, name) ->
            val f = byId[id] ?: return@mapNotNull null
            val g = (perPortionKcal / f.kcal * 100).coerceIn(40.0, 160.0).let { (it / 5).roundToInt() * 5.0 }
            PlanItem(meal, id, name, g, "${g.roundToInt()} г сухой")
        }
        val all = (fixed + grainItems).sortedBy { listOf("Завтрак", "Обед", "Перекус", "После тренировки", "Ужин").indexOf(it.meal) }
        val totals = DayTotals.of(all.mapNotNull { byId[it.foodId]?.let { f -> entry(f, it.grams) } })
        return all to totals
    }

    val BEFORE_WORKOUT = listOf(
        "За 1,5–2 ч: каша (овсянка/гречка 60–80 г сухой) + творог или 2 яйца — энергия и белок без тяжести.",
        "За 30–60 мин, если голодны: банан или кефир 250 мл.",
        "Кофе за 30–60 мин до силовой может добавить сил — но не позже 15–16 ч, чтобы не мешал сну.",
        "Выпейте 300–500 мл воды за час до тренировки.",
    )

    val AFTER_WORKOUT = listOf(
        "В течение 1–2 ч: 30–40 г белка — протеин с молоком, 200 г творога или 150 г курицы.",
        "Плюс углеводы для восстановления: рис, гречка, макароны или картофель.",
        "Добавьте овощи и восполните воду: ~500 мл на каждый час тренировки.",
    )

    val SUPPLEMENTS = listOf(
        "Витамин D: в Финляндии с октября по март солнца мало. Если едите мало рыбы и обогащённых молочных продуктов — добавка 10 мкг/день; больше — только по анализу и совету врача.",
        "Омега-3: рыба 2 раза в неделю (сардины, лосось) или рыбий жир.",
        "Рапсовое масло — хороший выбор для каши и салатов: омега-3 и дешёвые калории (1 ст. ложка ≈ 90 ккал).",
        "Креатин моногидрат 3–5 г в день — самая изученная добавка для силы и массы.",
        "Магний — из гречки, овсянки, орехов и семечек; при судорогах обсудите добавку с врачом.",
        "Йодированная соль и молочные продукты — йод и кальций.",
        "Если принимаете лекарства или есть хронические болезни — согласуйте добавки с врачом.",
    )

    fun drinkAdvice(fluidMl: Int, coffeeCups: Int, target: Int): List<String> {
        val out = ArrayList<String>()
        out += if (fluidMl >= target) "Жидкости достаточно ✓" else "Выпейте ещё ~${target - fluidMl} мл: вода, травяной чай, кефир."
        if (coffeeCups >= 4) out += "Кофе уже $coffeeCups чашки — больше не стоит, особенно после 15 ч."
        return out
    }
}
