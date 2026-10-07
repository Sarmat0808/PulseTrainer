package fi.sarmat.pulsetrainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Online food search: Open Food Facts — a free open database of ~3 million packaged products
 * (very good coverage of Finnish stores: K-, S-group, Lidl), values per 100 g / 100 ml from the package.
 * Search by name or by the barcode digits.
 */
object FoodOnline {
    data class Item(val name: String, val brand: String, val p: Double, val f: Double, val c: Double, val kcal: Double,
                    val drink: Boolean, val serving: Int?)

    private const val FIELDS = "code,product_name,product_name_ru,product_name_fi,brands,nutriments,quantity,serving_quantity"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "PulseTrainer/3.2 (Android)")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parse(o: JSONObject): Item? {
        val n = o.optJSONObject("nutriments") ?: return null
        fun d(k: String) = if (n.has(k)) n.optDouble(k, Double.NaN) else Double.NaN
        val p = d("proteins_100g"); val f = d("fat_100g"); val c = d("carbohydrates_100g")
        if (p.isNaN() || f.isNaN() || c.isNaN()) return null
        val name = listOf("product_name_ru", "product_name", "product_name_fi").map { o.optString(it) }.firstOrNull { it.isNotBlank() } ?: return null
        val q = o.optString("quantity").lowercase()
        val drink = q.contains("ml") || q.contains(" l") || q.endsWith("l") && !q.contains("g")
        val kcal = d("energy-kcal_100g").takeIf { !it.isNaN() } ?: (p * 4 + f * 9 + c * 4)
        val serving = o.optDouble("serving_quantity", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.toInt()
        return Item(name.trim(), o.optString("brands").split(',').first().trim(), p, f, c, kcal, drink, serving)
    }

    suspend fun search(query: String): List<Item> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length >= 8 && q.all { it.isDigit() }) {
            val o = JSONObject(get("https://world.openfoodfacts.org/api/v2/product/$q.json?fields=$FIELDS"))
            return@withContext listOfNotNull(o.optJSONObject("product")?.let { parse(it) })
        }
        val url = "https://world.openfoodfacts.org/cgi/search.pl?search_terms=" + URLEncoder.encode(q, "UTF-8") +
            "&search_simple=1&action=process&json=1&page_size=25&fields=$FIELDS"
        val a = JSONObject(get(url)).optJSONArray("products") ?: return@withContext emptyList()
        (0 until a.length()).mapNotNull { parse(a.getJSONObject(it)) }.distinctBy { it.name + it.brand }
    }
}
