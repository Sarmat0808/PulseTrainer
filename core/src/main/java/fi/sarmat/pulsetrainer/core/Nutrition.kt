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
    /** Meal (see [Meals]); -1 = not chosen, taken from the time. */
    val meal: Int = -1,
) {
    val kcal: Double get() = p * 4 + f * 9 + c * 4
}

/**
 * Meals like in Samsung Health: breakfast, lunch, dinner and three snacks.
 * The time of each meal is real (you can set it later, when you remember) — it is used for
 * protein spread over the day, food after a workout and late eating before sleep.
 */
object Meals {
    const val BREAKFAST = 0; const val LUNCH = 1; const val DINNER = 2
    const val SNACK_AM = 3; const val SNACK_PM = 4; const val SNACK_EVE = 5
    /** Display order. */
    val ORDER = listOf(BREAKFAST, SNACK_AM, LUNCH, SNACK_PM, DINNER, SNACK_EVE)
    val NAMES = listOf("Завтрак", "Обед", "Ужин", "Утренний перекус", "Дневной перекус", "Вечерний перекус")
    /** Typical time, minutes of the day. */
    val DEFAULT_MIN = listOf(8 * 60, 13 * 60, 19 * 60, 10 * 60 + 30, 16 * 60, 21 * 60)

    /** Meal from the time of day (for entries added without a meal). */
    fun fromMinute(m: Int): Int = when (m) {
        in 300 until 600 -> BREAKFAST
        in 600 until 720 -> SNACK_AM
        in 720 until 900 -> LUNCH
        in 900 until 1080 -> SNACK_PM
        in 1080 until 1260 -> DINNER
        else -> SNACK_EVE
    }

    /** Is "now" a natural time for this meal (±1.5 h of its window)? */
    fun fits(meal: Int, minuteNow: Int): Boolean = kotlin.math.abs(minuteNow - DEFAULT_MIN[meal]) <= 90

    data class Note(val text: String, val good: Boolean)

    /**
     * What the timing of food says. [minuteOf] converts an epoch time to minutes of the day (local),
     * [bedMin] = recommended bedtime (minutes of the day), [workoutEnds] = ends of today's workouts.
     */
    fun timing(entries: List<FoodEntry>, target: Targets, bedMin: Int, minuteOf: (Long) -> Int,
               workoutEnds: List<Long>, isToday: Boolean, nowMin: Int): List<Note> {
        val out = ArrayList<Note>()
        val food = entries.filter { it.kcal >= 30 }
        if (food.isEmpty()) return out
        // Meals = entries grouped within 45 minutes.
        val groups = ArrayList<MutableList<FoodEntry>>()
        food.sortedBy { it.time }.forEach { e ->
            val g = groups.lastOrNull()
            if (g != null && e.time - g.last().time <= 45 * 60_000L) g.add(e) else groups.add(mutableListOf(e))
        }
        val perMeal = (target.p / 4.0).coerceIn(20.0, 40.0)
        val proteinMeals = groups.count { g -> g.sumOf { it.p } >= perMeal * 0.8 }
        val dayDone = !isToday || nowMin >= 20 * 60
        if (dayDone && groups.size >= 2) {
            if (proteinMeals >= 3) out += Note("Белок распределён хорошо: $proteinMeals приёма по ~${perMeal.roundToInt()} г и больше — так мышцы растут лучше.", true)
            else out += Note("Белок лучше делить на 3–4 приёма по ${perMeal.roundToInt()}–40 г. Сегодня таких приёмов: $proteinMeals.", false)
        }
        // Late heavy eating before sleep.
        val last = groups.last()
        val lastMin = minuteOf(last.first().time)
        val beforeBed = ((bedMin - lastMin) + 1440) % 1440
        val lastKcal = last.sumOf { it.kcal }
        if (lastMin >= 17 * 60 && beforeBed < 120 && lastKcal >= 400)
            out += Note("Плотная еда (${lastKcal.roundToInt()} ккал) меньше чем за 2 ч до сна — сон и утренняя готовность могут быть хуже. Ужинайте за 2–3 ч до сна.", false)
        // After a workout: protein within 2 h.
        workoutEnds.forEach { end ->
            val after = food.filter { it.time in end..(end + 2 * 3600_000L) }.sumOf { it.p }
            if (after >= 20) out += Note("После тренировки белок был вовремя (${after.roundToInt()} г за 2 ч) ✓", true)
            else if (!isToday || (minuteOf(end) + 120) <= nowMin)
                out += Note("После тренировки за 2 ч белка было ${after.roundToInt()} г — лучше 20–40 г (напиток, творог, яйца).", false)
        }
        // A long gap during the day.
        val gaps = groups.zipWithNext { a, b -> (b.first().time - a.last().time) / 3600_000.0 }
        gaps.maxOrNull()?.takeIf { it >= 6 }?.let {
            out += Note("Перерыв без еды ${it.roundToInt()} ч — к вечеру тянет переесть. Небольшой белковый перекус посередине помогает.", false)
        }
        val firstMin = minuteOf(groups.first().first().time)
        if (dayDone && firstMin >= 12 * 60) out += Note("Первая еда после 12:00 — если тренируетесь утром, хотя бы лёгкий завтрак даст силы.", false)
        return out
    }
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
    ) + MORE

    /** Extended everyday products (typical values per 100 g / 100 ml; check the package for exact numbers). */
    private val MORE: List<Food> get() = listOf(
        // ----- Oils and fish oil -----
        // Cod liver oil: 5 ml ≈ 4.6 g fat (density 0.92). Möller: 5 ml = 1130 mg omega-3, D 10 µg, A 250 µg.
        Food("fish_oil_moller", "Рыбий жир Möller (жидкий)", 0.0, 92.0, 0.0, "Масла и добавки", 5, "ложка 5 мл"),
        Food("omega3_caps", "Омега-3 в капсулах", 0.0, 100.0, 0.0, "Масла и добавки", 1, "капсула 1 г"),
        Food("flax_oil", "Масло льняное", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("coconut_oil", "Масло кокосовое", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("ghee", "Масло топлёное (гхи)", 0.3, 99.5, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("sesame_oil", "Масло кунжутное", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("avocado_oil", "Масло авокадо", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("pumpkin_oil", "Масло тыквенное", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("mct_oil", "Масло MCT", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("margarine", "Маргарин / спред 60%", 0.2, 60.0, 0.5, "Масла и добавки", 10, "кусочек"),

        // ----- Home dishes -----
        Food("egg_salad", "Яичный салат (яйца, майонез, авокадо)", 10.0, 17.0, 2.0, "Готовые блюда", 150, "порция"),

        // ----- Store products (values from the package) -----
        Food("coop_protein_drink", "Coop Protein Drink (клубника)", 8.7, 1.5, 7.9, "Белок", 250, "пакет 250 мл", drink = true),
        Food("coop_protein_bar", "Coop Protein Bar Choco", 32.0, 12.0, 38.0, "Белок", 50, "батончик 50 г"),

        // ----- Dairy -----
        Food("yogurt_drink", "Йогурт питьевой 1,5–2,5%", 3.0, 2.0, 11.0, "Молочное", 290, "бутылка", drink = true),
        Food("yogurt_drink_protein", "Йогурт питьевой протеиновый", 6.5, 0.5, 6.5, "Молочное", 330, "бутылка", drink = true),
        Food("yogurt_greek", "Йогурт греческий 2%", 9.0, 2.0, 4.0, "Молочное", 150, "стаканчик"),
        Food("yogurt_greek10", "Йогурт греческий 10%", 6.0, 10.0, 4.0, "Молочное", 150, "стаканчик"),
        Food("yogurt_natural", "Йогурт натуральный 2,5%", 4.0, 2.5, 5.5, "Молочное", 150, "стаканчик"),
        Food("yogurt_fruit", "Йогурт фруктовый", 3.5, 2.5, 13.0, "Молочное", 150, "стаканчик"),
        Food("skyr", "Скир / кварк натуральный", 11.0, 0.3, 4.0, "Молочное", 150, "стаканчик"),
        Food("rahka", "Кварк (rahka) 0%", 12.0, 0.2, 3.5, "Молочное", 250, "пачка"),
        Food("rahka_flavour", "Кварк ароматизированный", 10.0, 1.5, 9.0, "Молочное", 200, "стаканчик"),
        Food("protein_pudding", "Протеиновый пудинг", 10.0, 1.5, 7.0, "Молочное", 200, "стаканчик"),
        Food("cottage_grain", "Творог зернистый (в сливках)", 12.0, 4.5, 2.5, "Молочное", 200, "банка"),
        Food("cottage2", "Творог 2%", 18.0, 2.0, 3.3, "Молочное"),
        Food("syrniki", "Сырники", 16.0, 10.0, 18.0, "Молочное", 60, "шт"),
        Food("milk_1", "Молоко 1%", 3.0, 1.0, 4.8, "Молочное", 250, "стакан", drink = true),
        Food("milk_32", "Молоко 3,2%", 2.9, 3.2, 4.7, "Молочное", 250, "стакан", drink = true),
        Food("milk_lactfree", "Молоко безлактозное 1,5%", 3.4, 1.5, 3.0, "Молочное", 250, "стакан", drink = true),
        Food("ryazhenka", "Ряженка 4%", 2.9, 4.0, 4.2, "Молочное", 250, "стакан", drink = true),
        Food("kefir1", "Кефир 1%", 3.0, 1.0, 4.0, "Молочное", 250, "стакан", drink = true),
        Food("buttermilk", "Пахта / пиимя", 3.3, 0.5, 4.0, "Молочное", 250, "стакан", drink = true),
        Food("sour_cream", "Сметана 15%", 2.6, 15.0, 3.6, "Молочное", 20, "ст. ложка"),
        Food("cream_cheese", "Сливочный сыр", 6.0, 24.0, 3.5, "Молочное", 20, "ст. ложка"),
        Food("mozzarella", "Моцарелла", 18.0, 20.0, 1.0, "Молочное", 125, "шарик"),
        Food("feta", "Фета / брынза", 14.0, 21.0, 1.0, "Молочное", 30, "кусочек"),
        Food("cheese_light", "Сыр лёгкий 10–17%", 30.0, 15.0, 0.0, "Молочное", 20, "ломтик"),
        Food("cheese_processed", "Сыр плавленый", 11.0, 20.0, 5.0, "Молочное", 20, "ломтик"),
        Food("ice_cream", "Мороженое пломбир", 3.5, 15.0, 22.0, "Сладкое и снеки", 80, "порция"),

        // ----- Meat, fish, eggs -----
        Food("turkey", "Индейка филе (сырое)", 22.0, 1.5, 0.0, "Мясо и рыба"),
        Food("turkey_c", "Индейка филе (готовое)", 29.0, 2.0, 0.0, "Мясо и рыба"),
        Food("chicken_thigh_c", "Куриное бедро (готовое)", 24.0, 10.0, 0.0, "Мясо и рыба"),
        Food("chicken_drum", "Голень куриная (готовая, без кожи)", 25.0, 8.0, 0.0, "Мясо и рыба", 80, "шт"),
        Food("chicken_mince", "Фарш куриный", 17.5, 8.0, 0.0, "Мясо и рыба"),
        Food("chicken_strips", "Куриные полоски (готовые, упак.)", 22.0, 2.0, 1.0, "Мясо и рыба"),
        Food("beef_mince10", "Фарш говяжий 10%", 20.0, 10.0, 0.0, "Мясо и рыба"),
        Food("beef_mince17", "Фарш говяжий 17–20%", 18.0, 17.0, 0.0, "Мясо и рыба"),
        Food("mince_mixed", "Фарш смешанный (свин./гов.)", 16.0, 20.0, 0.0, "Мясо и рыба"),
        Food("beef", "Говядина (вырезка, сырая)", 21.0, 6.0, 0.0, "Мясо и рыба"),
        Food("beef_stew", "Говядина тушёная", 25.0, 12.0, 0.0, "Мясо и рыба"),
        Food("pork_lean", "Свинина постная (сырая)", 21.0, 7.0, 0.0, "Мясо и рыба"),
        Food("pork_neck", "Свиная шея (сырая)", 16.0, 21.0, 0.0, "Мясо и рыба"),
        Food("liver_chicken", "Печень куриная", 19.0, 6.0, 0.7, "Мясо и рыба"),
        Food("liver_beef", "Печень говяжья", 20.0, 4.0, 4.0, "Мясо и рыба"),
        Food("meatballs", "Фрикадельки / тефтели", 14.0, 15.0, 6.0, "Мясо и рыба", 20, "шт"),
        Food("cutlet", "Котлета домашняя", 15.0, 14.0, 9.0, "Мясо и рыба", 90, "шт"),
        Food("ham", "Ветчина / грудка нарезка", 18.0, 3.0, 1.5, "Мясо и рыба", 15, "ломтик"),
        Food("sausage_boiled", "Колбаса варёная", 12.0, 25.0, 1.5, "Мясо и рыба", 20, "ломтик"),
        Food("salami", "Колбаса копчёная / салями", 20.0, 40.0, 1.0, "Мясо и рыба", 10, "ломтик"),
        Food("bacon", "Бекон", 15.0, 35.0, 0.5, "Мясо и рыба", 15, "полоска"),
        Food("salmon", "Лосось (сырой)", 20.0, 13.0, 0.0, "Мясо и рыба"),
        Food("salmon_salted", "Лосось слабосолёный", 22.0, 12.0, 0.0, "Мясо и рыба"),
        Food("cod", "Треска / минтай", 18.0, 0.7, 0.0, "Мясо и рыба"),
        Food("pike_perch", "Судак / окунь", 19.0, 1.0, 0.0, "Мясо и рыба"),
        Food("herring", "Сельдь", 18.0, 15.0, 0.0, "Мясо и рыба"),
        Food("mackerel", "Скумбрия", 18.0, 13.0, 0.0, "Мясо и рыба"),
        Food("mackerel_tomato", "Скумбрия в томате (консервы)", 13.0, 12.0, 3.0, "Мясо и рыба", 170, "банка"),
        Food("shrimp", "Креветки варёные", 20.0, 1.5, 0.0, "Мясо и рыба"),
        Food("crab_sticks", "Крабовые палочки", 6.0, 1.0, 10.0, "Мясо и рыба", 20, "шт"),
        Food("fish_fingers", "Рыбные палочки", 12.0, 9.0, 20.0, "Мясо и рыба", 30, "шт"),
        Food("egg_white", "Яичный белок", 11.0, 0.2, 0.7, "Мясо и рыба", 33, "белок"),
        Food("omelette", "Омлет (2 яйца + молоко)", 10.0, 12.0, 2.0, "Мясо и рыба", 150, "порция"),
        Food("tofu", "Тофу", 13.0, 7.0, 2.0, "Мясо и рыба"),

        // ----- Grains, bread, legumes -----
        Food("millet", "Пшено (сухое)", 11.0, 3.3, 66.0, "Крупы"),
        Food("barley", "Перловка (сухая)", 9.3, 1.1, 66.0, "Крупы"),
        Food("couscous", "Кускус (сухой)", 12.8, 0.6, 72.0, "Крупы"),
        Food("rice_brown", "Рис бурый (сухой)", 7.5, 2.7, 72.0, "Крупы"),
        Food("oat_porridge", "Овсяная каша на воде (готовая)", 3.0, 1.5, 12.0, "Крупы"),
        Food("muesli", "Мюсли без сахара", 10.0, 7.0, 60.0, "Крупы", 50, "порция"),
        Food("granola", "Гранола", 9.0, 15.0, 62.0, "Крупы", 50, "порция"),
        Food("cornflakes", "Кукурузные хлопья", 7.0, 1.0, 84.0, "Крупы", 30, "порция"),
        Food("potato_baked", "Картофель запечённый", 2.5, 3.0, 20.0, "Крупы"),
        Food("potato_mash", "Пюре картофельное", 2.0, 4.0, 14.0, "Крупы"),
        Food("fries", "Картофель фри", 3.4, 15.0, 41.0, "Крупы"),
        Food("sweet_potato", "Батат (запечённый)", 2.0, 0.2, 21.0, "Крупы"),
        Food("bread_whole", "Хлеб цельнозерновой", 9.0, 3.5, 41.0, "Крупы", 35, "ломтик"),
        Food("ruisleipa", "Ржаной хлеб финский (ruisleipä)", 9.0, 2.5, 40.0, "Крупы", 40, "шт"),
        Food("crispbread", "Хлебцы ржаные (näkkileipä)", 10.0, 2.0, 62.0, "Крупы", 12, "шт"),
        Food("lavash", "Лаваш", 8.0, 1.0, 50.0, "Крупы", 60, "лист"),
        Food("tortilla", "Тортилья пшеничная", 8.5, 7.0, 50.0, "Крупы", 60, "шт"),
        Food("bun", "Булочка", 8.0, 5.0, 50.0, "Крупы", 70, "шт"),
        Food("karelian_pie", "Карельский пирожок", 6.0, 7.0, 32.0, "Крупы", 70, "шт"),
        Food("lentils", "Чечевица (сухая)", 24.0, 1.5, 48.0, "Крупы"),
        Food("lentils_c", "Чечевица (варёная)", 9.0, 0.4, 20.0, "Крупы"),
        Food("chickpeas_c", "Нут (варёный / консерв.)", 8.5, 2.6, 21.0, "Крупы"),
        Food("beans_c", "Фасоль (консервированная)", 7.0, 0.5, 15.0, "Крупы"),
        Food("pelmeni", "Пельмени", 11.0, 12.0, 26.0, "Крупы", 12, "шт"),
        Food("pancakes", "Блины", 6.0, 8.0, 30.0, "Крупы", 50, "шт"),

        // ----- Vegetables, fruit, berries -----
        Food("tomato", "Помидор", 0.9, 0.2, 3.9, "Овощи и фрукты", 120, "шт"),
        Food("cucumber", "Огурец", 0.7, 0.1, 3.6, "Овощи и фрукты", 150, "шт"),
        Food("carrot", "Морковь", 0.9, 0.2, 9.6, "Овощи и фрукты", 80, "шт"),
        Food("cabbage", "Капуста белокочанная", 1.3, 0.1, 5.8, "Овощи и фрукты"),
        Food("sauerkraut", "Капуста квашеная", 1.0, 0.1, 4.0, "Овощи и фрукты"),
        Food("beet", "Свёкла варёная", 1.7, 0.2, 10.0, "Овощи и фрукты"),
        Food("onion", "Лук репчатый", 1.1, 0.1, 9.3, "Овощи и фрукты"),
        Food("zucchini", "Кабачок", 1.2, 0.3, 3.1, "Овощи и фрукты"),
        Food("cauliflower", "Цветная капуста", 1.9, 0.3, 5.0, "Овощи и фрукты"),
        Food("spinach", "Шпинат", 2.9, 0.4, 3.6, "Овощи и фрукты"),
        Food("salad_leaves", "Салат листовой", 1.4, 0.2, 2.9, "Овощи и фрукты"),
        Food("corn", "Кукуруза (консерв.)", 3.0, 1.2, 16.0, "Овощи и фрукты"),
        Food("mushrooms", "Шампиньоны", 3.1, 0.3, 3.3, "Овощи и фрукты"),
        Food("avocado", "Авокадо", 2.0, 15.0, 9.0, "Овощи и фрукты", 140, "шт"),
        Food("orange", "Апельсин", 0.9, 0.1, 12.0, "Овощи и фрукты", 180, "шт"),
        Food("mandarin", "Мандарин", 0.8, 0.3, 13.0, "Овощи и фрукты", 80, "шт"),
        Food("pear", "Груша", 0.4, 0.1, 15.0, "Овощи и фрукты", 170, "шт"),
        Food("kiwi", "Киви", 1.1, 0.5, 15.0, "Овощи и фрукты", 75, "шт"),
        Food("grapes", "Виноград", 0.7, 0.2, 17.0, "Овощи и фрукты"),
        Food("blueberries", "Черника / голубика", 0.7, 0.3, 14.0, "Овощи и фрукты"),
        Food("lingonberries", "Брусника", 0.7, 0.5, 12.0, "Овощи и фрукты"),
        Food("strawberries", "Клубника", 0.7, 0.3, 7.7, "Овощи и фрукты"),
        Food("berries_frozen", "Ягодная смесь (замороженная)", 1.0, 0.4, 9.0, "Овощи и фрукты"),
        Food("dried_fruit", "Сухофрукты (курага, изюм)", 3.0, 0.5, 63.0, "Овощи и фрукты", 30, "горсть"),
        Food("dates", "Финики", 2.5, 0.4, 75.0, "Овощи и фрукты", 8, "шт"),

        // ----- Nuts, oils, sauces -----
        Food("almonds", "Миндаль", 21.0, 50.0, 22.0, "Масла и добавки", 30, "горсть"),
        Food("peanuts", "Арахис", 26.0, 49.0, 16.0, "Масла и добавки", 30, "горсть"),
        Food("cashews", "Кешью", 18.0, 44.0, 30.0, "Масла и добавки", 30, "горсть"),
        Food("flax", "Семена льна", 18.0, 42.0, 29.0, "Масла и добавки", 10, "ст. ложка"),
        Food("chia", "Семена чиа", 17.0, 31.0, 42.0, "Масла и добавки", 10, "ст. ложка"),
        Food("sunflower_oil", "Масло подсолнечное", 0.0, 100.0, 0.0, "Масла и добавки", 10, "ст. ложка"),
        Food("mayo", "Майонез", 1.0, 67.0, 3.0, "Масла и добавки", 15, "ст. ложка"),
        Food("mayo_light", "Майонез лёгкий", 0.5, 30.0, 6.0, "Масла и добавки", 15, "ст. ложка"),
        Food("ketchup", "Кетчуп", 1.5, 0.2, 24.0, "Масла и добавки", 15, "ст. ложка"),
        Food("pesto", "Песто", 5.0, 45.0, 6.0, "Масла и добавки", 15, "ст. ложка"),
        Food("tomato_sauce", "Томатный соус / паста", 2.0, 1.0, 8.0, "Масла и добавки", 50, "порция"),
        Food("hummus", "Хумус", 7.0, 17.0, 14.0, "Масла и добавки", 30, "ст. ложка"),
        Food("jam", "Варенье / джем", 0.4, 0.0, 60.0, "Масла и добавки", 20, "ст. ложка"),

        // ----- Sweets & snacks -----
        Food("chocolate_dark", "Шоколад горький 70%", 8.0, 42.0, 34.0, "Сладкое и снеки", 10, "долька"),
        Food("chocolate_milk", "Шоколад молочный", 7.0, 32.0, 56.0, "Сладкое и снеки", 10, "долька"),
        Food("cookies", "Печенье", 6.5, 18.0, 70.0, "Сладкое и снеки", 12, "шт"),
        Food("wafers", "Вафли", 5.0, 28.0, 63.0, "Сладкое и снеки", 15, "шт"),
        Food("cereal_bar", "Злаковый батончик", 6.0, 12.0, 65.0, "Сладкое и снеки", 25, "шт"),
        Food("chips", "Чипсы", 6.0, 33.0, 53.0, "Сладкое и снеки", 30, "горсть"),
        Food("popcorn", "Попкорн", 9.0, 20.0, 58.0, "Сладкое и снеки", 30, "горсть"),
        Food("rice_cakes", "Хлебцы рисовые", 8.0, 3.0, 79.0, "Сладкое и снеки", 9, "шт"),
        Food("cake", "Торт / пирожное", 5.0, 20.0, 45.0, "Сладкое и снеки", 100, "кусок"),
        Food("pulla", "Булочка сладкая (pulla)", 7.0, 12.0, 52.0, "Сладкое и снеки", 70, "шт"),

        // ----- Ready meals -----
        Food("pizza", "Пицца", 11.0, 10.0, 28.0, "Готовые блюда", 120, "кусок"),
        Food("burger", "Бургер", 13.0, 12.0, 24.0, "Готовые блюда", 220, "шт"),
        Food("shawarma", "Шаурма / кебаб в лаваше", 11.0, 10.0, 18.0, "Готовые блюда", 350, "шт"),
        Food("borscht", "Борщ", 1.5, 2.5, 5.0, "Готовые блюда", 300, "тарелка"),
        Food("soup_chicken", "Суп куриный с лапшой", 3.0, 1.5, 5.0, "Готовые блюда", 300, "тарелка"),
        Food("salmon_soup", "Уха / лососевый суп (lohikeitto)", 5.0, 5.0, 5.0, "Готовые блюда", 300, "тарелка"),
        Food("plov", "Плов с курицей", 8.0, 7.0, 22.0, "Готовые блюда", 300, "тарелка"),
        Food("pasta_bolognese", "Макароны болоньезе", 8.0, 6.0, 20.0, "Готовые блюда", 350, "тарелка"),
        Food("lasagna", "Лазанья", 8.0, 8.0, 13.0, "Готовые блюда", 300, "порция"),
        Food("salad_olivier", "Салат оливье", 5.0, 15.0, 7.0, "Готовые блюда", 150, "порция"),
        Food("salad_veg_oil", "Салат овощной с маслом", 1.0, 5.0, 4.0, "Готовые блюда", 150, "порция"),
        Food("sushi", "Суши / роллы", 6.0, 3.0, 28.0, "Готовые блюда", 30, "шт"),
        Food("sandwich", "Бутерброд (хлеб + сыр/ветчина)", 12.0, 10.0, 30.0, "Готовые блюда", 80, "шт"),

        // ----- Drinks -----
        Food("cocoa", "Какао на молоке", 3.2, 3.0, 10.0, "Напитки", 250, "кружка", drink = true),
        Food("latte", "Кофе латте / капучино", 3.0, 2.0, 4.5, "Напитки", 300, "чашка", drink = true),
        Food("tea_sugar", "Чай с сахаром (2 ч. л.)", 0.0, 0.0, 4.0, "Напитки", 250, "кружка", drink = true),
        Food("protein_shake", "Протеиновый коктейль (готовый)", 8.0, 0.5, 3.0, "Напитки", 330, "бутылка", drink = true),
        Food("smoothie", "Смузи фруктовый", 0.7, 0.3, 12.0, "Напитки", 250, "стакан", drink = true),
        Food("kvass", "Квас", 0.2, 0.0, 6.0, "Напитки", 330, "стакан", drink = true),
        Food("soda", "Газировка сладкая", 0.0, 0.0, 10.6, "Напитки", 330, "банка", drink = true),
        Food("soda_zero", "Газировка без сахара", 0.0, 0.0, 0.0, "Напитки", 330, "банка", drink = true),
        Food("mineral", "Минеральная вода", 0.0, 0.0, 0.0, "Напитки", 500, "бутылка", drink = true),
        Food("sports_drink", "Изотоник", 0.0, 0.0, 6.0, "Напитки", 500, "бутылка", drink = true),
        Food("oat_drink", "Овсяный напиток", 1.0, 1.5, 6.5, "Напитки", 250, "стакан", drink = true),
        Food("kissel", "Кисель / компот", 0.1, 0.0, 12.0, "Напитки", 250, "стакан", drink = true),
    )

    /** Drinks shown as one-tap buttons (the full list is in «+ Еда»). */
    val QUICK_DRINKS = listOf("water", "tea", "coffee", "milk", "kefir", "yogurt_drink", "coop_protein_drink")

    /** Packaged snacks added with one tap (a whole bar). */
    val QUICK_SNACKS = listOf("coop_protein_bar")

    val CATEGORIES = listOf("Белок", "Мясо и рыба", "Молочное", "Крупы", "Овощи и фрукты", "Масла и добавки", "Готовые блюда", "Сладкое и снеки", "Напитки", "Мои продукты")

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

    /** One ready-to-add option: this product, this many grams. */
    data class Suggestion(val food: Food, val grams: Double, val text: String, val enough: Boolean)

    /** "Now is a good time for …" — the protein drink or bar, when it actually helps. */
    data class SnackTip(val food: Food, val grams: Double, val title: String, val why: String)

    /**
     * When to take the store protein drink / bar:
     * - within ~2 h after a workout (muscle protein synthesis; 20–40 g protein — Morton 2018, ISSN 2017);
     * - ≥ 3 h since the last protein meal and protein for the day is behind schedule
     *   (spread 4–5 portions of ~0.4 g/kg through the day);
     * - evening, protein still short by ≥ 20 g — finish the day's norm.
     * The drink is preferred after training (fast, liquid, carbs for glycogen); the bar between meals.
     */
    fun snackTip(
        foods: List<Food>, entries: List<FoodEntry>, target: Targets, now: Long, lastWorkoutEnd: Long?,
        hourOfDay: Int,
    ): SnackTip? {
        val byId = foods.associateBy { it.id }
        val drink = byId["coop_protein_drink"] ?: return null
        val bar = byId["coop_protein_bar"] ?: return null
        val t = DayTotals.of(entries)
        val pGap = target.p - t.p
        val kGap = target.kcal - t.kcal
        if (pGap < 12 || kGap < 120) return null
        val lastProtein = entries.filter { it.p >= 10 }.maxOfOrNull { it.time }
        val hSinceProtein = lastProtein?.let { (now - it) / 3600_000.0 } ?: 99.0
        val afterWorkout = lastWorkoutEnd != null && now - lastWorkoutEnd in 0..(2 * 3600_000L) && hSinceProtein > 0.75
        // Share of the day's protein that should be eaten by now (07:00 → 21:00).
        val expected = ((hourOfDay - 7) / 14.0).coerceIn(0.0, 1.0)
        val behind = t.p < target.p * expected - 15
        val dg = drink.pieceG ?: 250; val bg = bar.pieceG ?: 50
        val dp = (drink.p * dg / 100).roundToInt(); val bp = (bar.p * bg / 100).roundToInt()
        return when {
            afterWorkout -> SnackTip(drink, dg.toDouble(), "Самое время: протеиновый напиток",
                "После тренировки прошло меньше 2 ч — $dp г белка и углеводы сейчас лучше всего пойдут в мышцы. Осталось белка: ${pGap.roundToInt()} г.")
            hourOfDay in 19..22 && pGap >= 20 -> SnackTip(if (pGap >= 28) drink else bar, (if (pGap >= 28) dg else bg).toDouble(),
                "Вечером добрать белок", "До нормы не хватает ${pGap.roundToInt()} г белка. ${if (pGap >= 28) "Напиток даст $dp г" else "Батончик даст $bp г"} — и норма почти закрыта.")
            hSinceProtein >= 3 && behind && hourOfDay in 10..18 -> SnackTip(bar, bg.toDouble(), "Перекус: протеиновый батончик",
                "${hSinceProtein.roundToInt().coerceAtMost(12)} ч без белка, а по плану дня вы отстаёте на ${(target.p * expected - t.p).roundToInt()} г. Батончик — $bp г белка, удобно между приёмами пищи.")
            else -> null
        }
    }

    private data class Src(val id: String, val maxG: Double)

    private val PROTEIN_SRC = listOf(Src("cottage5", 400.0), Src("chicken", 300.0), Src("egg", 220.0), Src("tuna", 300.0),
        Src("protein", 60.0), Src("kefir", 750.0), Src("cheese", 80.0), Src("cottage0", 400.0))
    private val CARB_SRC = listOf(Src("buckwheat", 200.0), Src("rice", 200.0), Src("oats", 150.0), Src("pasta", 200.0),
        Src("bulgur", 200.0), Src("potato", 600.0), Src("banana", 360.0), Src("bread_rye", 200.0))
    private val FAT_SRC = listOf(Src("rapeseed_oil", 30.0), Src("peanut_butter", 50.0), Src("walnuts", 50.0),
        Src("cheese", 80.0), Src("seeds", 50.0), Src("butter", 30.0))

    /**
     * For one macro gap: how much of each everyday product covers it.
     * Example: protein gap 40 g → "Творог 5% — 235 г → +40 г белка, 280 ккал".
     */
    fun cover(macro: Char, gap: Double, foods: List<Food>): List<Suggestion> {
        if (gap < 3) return emptyList()
        val byId = foods.associateBy { it.id }
        val src = when (macro) { 'p' -> PROTEIN_SRC; 'c' -> CARB_SRC; else -> FAT_SRC }
        return src.mapNotNull { s ->
            val f = byId[s.id] ?: return@mapNotNull null
            val per100 = when (macro) { 'p' -> f.p; 'c' -> f.c; else -> f.f }
            if (per100 <= 0) return@mapNotNull null
            val need = gap / per100 * 100
            val pg = f.pieceG
            val g = if (pg != null && f.pieceName == "шт") ((minOf(need, s.maxG) / pg).roundToInt().coerceAtLeast(1) * pg).toDouble()
            else (minOf(need, s.maxG) / 5).roundToInt().coerceAtLeast(1) * 5.0
            val e = entry(f, g)
            val got = when (macro) { 'p' -> e.p; 'c' -> e.c; else -> e.f }
            val amount = amountLabel(f, g)
            val unit = when (macro) { 'p' -> "белка"; 'c' -> "углеводов"; else -> "жиров" }
            val enough = got >= gap * 0.9
            Suggestion(f, g, "${f.name} — $amount → +${got.roundToInt()} г $unit, ${e.kcal.roundToInt()} ккал" +
                if (!enough) " (часть нормы)" else "", enough)
        }.take(5)
    }

    fun amountLabel(f: Food, g: Double): String {
        val pg = f.pieceG
        val unit = if (f.drink) "мл" else "г"
        return if (pg != null && f.pieceName == "шт" && g >= pg * 0.8) {
            val n = (g / pg).roundToInt().coerceAtLeast(1)
            "$n шт (~${g.roundToInt()} $unit)"
        } else "${g.roundToInt()} $unit"
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
