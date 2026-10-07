package fi.sarmat.pulsetrainer

import android.content.Context
import android.content.SharedPreferences
import fi.sarmat.pulsetrainer.core.DayTotals
import fi.sarmat.pulsetrainer.core.Food
import fi.sarmat.pulsetrainer.core.FoodEntry
import fi.sarmat.pulsetrainer.core.Nutrition
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
        custom.value = loadCustom()
    }

    fun foods(): List<Food> = Nutrition.BASE + custom.value

    private fun key(d: LocalDate) = "day_$d"

    fun entries(d: LocalDate): List<FoodEntry> = try {
        val a = JSONArray(prefs.getString(key(d), "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            FoodEntry(o.getLong("t"), o.getString("id"), o.getString("n"), o.getDouble("a"),
                o.getDouble("p"), o.getDouble("f"), o.getDouble("c"), o.optInt("ml"))
        }
    } catch (_: Exception) { emptyList() }

    private fun saveDay(d: LocalDate, list: List<FoodEntry>) {
        val a = JSONArray()
        list.sortedBy { it.time }.forEach {
            a.put(JSONObject().put("t", it.time).put("id", it.foodId).put("n", it.name).put("a", it.amount)
                .put("p", it.p).put("f", it.f).put("c", it.c).put("ml", it.drinkMl))
        }
        prefs.edit().putString(key(d), a.toString()).apply()
        version.value++
    }

    fun add(d: LocalDate, food: Food, amount: Double) {
        val now = LocalDate.now()
        val time = if (d == now) System.currentTimeMillis()
        else d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        saveDay(d, entries(d) + Nutrition.entry(food, amount, time))
        setForgot(d, false)
        touchRecent(food.id)
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
                if (o.has("pg") && !o.isNull("pg")) o.getInt("pg") else null, if (o.has("pn")) o.optString("pn") else null,
                drink = o.optBoolean("drink"), custom = true)
        }
    } catch (_: Exception) { emptyList() }

    fun addCustom(name: String, p: Double, f: Double, c: Double, pieceG: Int?, drink: Boolean) {
        val food = Food("c_${System.currentTimeMillis()}", name, p, f, c, "Мои продукты", pieceG, if (pieceG != null) "порция" else null, drink, true)
        custom.value = custom.value + food
        saveCustom()
    }

    fun deleteCustom(id: String) {
        custom.value = custom.value.filter { it.id != id }
        saveCustom()
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
        Nutrition.targets(PhoneStore.profile.value ?: Profile(), PhoneStore.goal.value, trainedOn(d))

    data class DaySummary(val date: LocalDate, val totals: DayTotals, val target: Targets, val forgot: Boolean, val entries: List<FoodEntry>)

    fun history(days: Int): List<DaySummary> {
        val today = LocalDate.now()
        return (0 until days).map { today.minusDays(it.toLong()) }.map { d ->
            val e = entries(d)
            DaySummary(d, DayTotals.of(e), targets(d), forgot(d), e)
        }
    }
}
