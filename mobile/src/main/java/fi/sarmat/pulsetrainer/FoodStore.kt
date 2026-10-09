package fi.sarmat.pulsetrainer

import android.content.Context
import android.content.SharedPreferences
import fi.sarmat.pulsetrainer.core.DayTotals
import fi.sarmat.pulsetrainer.core.Food
import fi.sarmat.pulsetrainer.core.FoodEntry
import fi.sarmat.pulsetrainer.core.Nutrition
import fi.sarmat.pulsetrainer.core.Meals
import fi.sarmat.pulsetrainer.core.Profile
import fi.sarmat.pulsetrainer.core.Targets
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Food diary: what was eaten each day, own products, "forgot to log" days. */
object FoodStore {
    private lateinit var prefs: SharedPreferences

    /** Bumped on every change so the UI recomposes. */
    val version = MutableStateFlow(0)
    val custom = MutableStateFlow<List<Food>>(emptyList())

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("food", Context.MODE_PRIVATE)
        FoodSync.init(ctx)
        custom.value = loadCustom()
    }

    fun foods(): List<Food> = Nutrition.BASE + custom.value

    private fun key(d: LocalDate) = "day_$d"

    fun entries(d: LocalDate): List<FoodEntry> = try {
        val a = JSONArray(prefs.getString(key(d), "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            FoodEntry(o.getLong("t"), o.getString("id"), o.getString("n"), o.getDouble("a"),
                o.getDouble("p"), o.getDouble("f"), o.getDouble("c"), o.optInt("ml"), o.optInt("m", -1))
        }
    } catch (_: Exception) { emptyList() }

    private fun saveDay(d: LocalDate, list: List<FoodEntry>) {
        val a = JSONArray()
        list.sortedBy { it.time }.forEach {
            a.put(JSONObject().put("t", it.time).put("id", it.foodId).put("n", it.name).put("a", it.amount)
                .put("p", it.p).put("f", it.f).put("c", it.c).put("ml", it.drinkMl).put("m", it.meal))
        }
        prefs.edit().putString(key(d), a.toString()).apply()
        version.value++
        FoodSync.dayChanged(d)
    }

    fun add(d: LocalDate, food: Food, amount: Double, meal: Int = -1, time: Long? = null) {
        val now = LocalDate.now()
        val t = time ?: if (meal >= 0) mealTime(d, meal)
        else if (d == now) System.currentTimeMillis()
        else d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        saveDay(d, entries(d) + Nutrition.entry(food, amount, t).copy(meal = meal))
        setForgot(d, false)
        touchRecent(food.id)
        prefs.edit().putFloat("amt_${food.id}", amount.toFloat()).apply()
    }

    // ----- Meals (breakfast / lunch / dinner / snacks) -----

    private val zone: ZoneId get() = ZoneId.systemDefault()
    fun minuteOf(t: Long): Int = Instant.ofEpochMilli(t).atZone(zone).let { it.hour * 60 + it.minute }

    /** Meal of an entry: chosen one, or by the time it was eaten. */
    fun mealOf(e: FoodEntry): Int = if (e.meal in 0..5) e.meal else Meals.fromMinute(minuteOf(e.time))

    /**
     * Time for a new entry in this meal: the time already set for the meal that day; today, "now"
     * if it's the natural time for the meal; otherwise the meal's usual time.
     */
    fun mealTime(d: LocalDate, meal: Int): Long {
        entries(d).filter { mealOf(it) == meal }.minOfOrNull { it.time }?.let { return it }
        val nowMin = minuteOf(System.currentTimeMillis())
        if (d == LocalDate.now() && Meals.fits(meal, nowMin)) return System.currentTimeMillis()
        val m = Meals.DEFAULT_MIN[meal]
        return d.atTime(m / 60, m % 60).atZone(zone).toInstant().toEpochMilli()
    }

    /** "I ate breakfast at 9:20": moves every entry of this meal on that day to that time. */
    fun setMealTime(d: LocalDate, meal: Int, minute: Int) {
        val t = d.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
        saveDay(d, entries(d).map { if (mealOf(it) == meal) it.copy(time = t, meal = meal) else it })
    }

    /** Edit one entry (time, meal, amount). */
    fun update(d: LocalDate, old: FoodEntry, new: FoodEntry) {
        val list = entries(d).toMutableList()
        val i = list.indexOf(old)
        if (i >= 0) { list[i] = new; saveDay(d, list) }
    }

    fun remove(d: LocalDate, e: FoodEntry) = saveDay(d, entries(d).filter { it != e })

    // ----- forgot to log -----
    fun forgot(d: LocalDate): Boolean = prefs.getBoolean("forgot_$d", false)
    fun setForgot(d: LocalDate, v: Boolean) {
        prefs.edit().putBoolean("forgot_$d", v).apply()
        version.value++
    }

    // ----- recent products first -----
    fun recentIds(): List<String> = (prefs.getString("recent", "") ?: "").split(',').filter { it.isNotBlank() }
    private fun touchRecent(id: String) {
        val list = (listOf(id) + recentIds().filter { it != id }).take(12)
        prefs.edit().putString("recent", list.joinToString(",")).apply()
    }

    // ----- own products -----
    private fun loadCustom(): List<Food> = try {
        val a = JSONArray(prefs.getString("custom", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Food(o.getString("id"), o.getString("n"), o.getDouble("p"), o.getDouble("f"), o.getDouble("c"), "Мои продукты",
                if (o.has("pg") && !o.isNull("pg")) o.getInt("pg") else null, o.optString("pn").takeIf { it.isNotBlank() },
                drink = o.optBoolean("drink"), custom = true)
        }
    } catch (_: Exception) { emptyList() }

    fun addCustom(name: String, p: Double, f: Double, c: Double, pieceG: Int?, drink: Boolean): Food {
        val food = Food("c_${System.currentTimeMillis()}", name, p, f, c, "Мои продукты", pieceG, if (pieceG != null) "порция" else null, drink, true)
        custom.value = custom.value + food
        saveCustom()
        // A new own dish goes straight to «Мои блюда».
        if (!drink) setMy(myIds() + food.id)
        return food
    }

    /** Edit an own product (name, БЖУ, portion). Diary entries already logged keep their numbers. */
    fun updateCustom(f: Food) {
        custom.value = custom.value.map { if (it.id == f.id) f.copy(category = "Мои продукты", custom = true) else it }
        saveCustom()
    }

    fun deleteCustom(id: String) {
        custom.value = custom.value.filter { it.id != id }
        saveCustom()
        setMy(myIds() - id)
        setDrinks(drinks().filter { it.first != id })
    }

    // ----- «Мои блюда»: your everyday foods, in your order -----

    private val DEFAULT_MY = listOf("chicken_raw", "buckwheat", "oats", "egg", "cottage5", "banana", "egg_salad", "fish_oil_moller", "coop_protein_bar")

    fun myIds(): List<String> {
        val raw = prefs.getString("my", null) ?: return DEFAULT_MY
        return raw.split(',').filter { it.isNotBlank() }
    }

    fun myFoods(): List<Food> { val all = foods().associateBy { it.id }; return myIds().mapNotNull { all[it] } }

    fun setMy(ids: List<String>) {
        prefs.edit().putString("my", ids.distinct().joinToString(",")).apply()
        version.value++
    }

    fun moveMy(id: String, delta: Int) {
        val l = myIds().toMutableList(); val i = l.indexOf(id); val j = i + delta
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        setMy(l)
    }

    /** The amount you used last time for this food (so next time it is already filled in). */
    fun lastAmount(id: String): Double? = prefs.getFloat("amt_$id", -1f).takeIf { it > 0 }?.toDouble()

    // ----- One-tap drinks: (food id, ml), your list and order -----

    private val DEFAULT_DRINKS = listOf("water" to 250, "tea" to 250, "coffee" to 200, "milk" to 250, "kefir" to 250,
        "yogurt_drink" to 290, "coop_protein_drink" to 250)

    fun drinks(): List<Pair<String, Int>> {
        val raw = prefs.getString("drinks", null) ?: return DEFAULT_DRINKS
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getInt("ml") } }
        } catch (_: Exception) { DEFAULT_DRINKS }
    }

    fun setDrinks(list: List<Pair<String, Int>>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.first).put("ml", it.second)) }
        prefs.edit().putString("drinks", a.toString()).apply()
        version.value++
    }

    private fun saveCustom() {
        val a = JSONArray()
        custom.value.forEach {
            a.put(JSONObject().put("id", it.id).put("n", it.name).put("p", it.p).put("f", it.f).put("c", it.c)
                .put("pg", it.pieceG ?: JSONObject.NULL).put("pn", it.pieceName ?: "").put("drink", it.drink))
        }
        prefs.edit().putString("custom", a.toString()).apply()
        version.value++
    }

    // ----- targets -----
    fun trainedOn(d: LocalDate): Boolean {
        val z = ZoneId.systemDefault()
        return PhoneStore.workouts.value.any { Instant.ofEpochMilli(it.start).atZone(z).toLocalDate() == d && it.activeSec > 600 }
    }

    fun targets(d: LocalDate): Targets =
        Nutrition.targets(PhoneStore.profile.value ?: Profile(), PhoneStore.goal.value, trainedOn(d), realTdee = PhoneStore.learnedTdee)

    data class DaySummary(val date: LocalDate, val totals: DayTotals, val target: Targets, val forgot: Boolean, val entries: List<FoodEntry>)

    fun history(days: Int): List<DaySummary> {
        val today = LocalDate.now()
        return (0 until days).map { today.minusDays(it.toLong()) }.map { d ->
            val e = entries(d)
            DaySummary(d, DayTotals.of(e), targets(d), forgot(d), e)
        }
    }
}
