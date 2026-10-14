package fi.sarmat.pulsetrainer.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** One food-diary day for the analysis (only days you filled in). */
data class FoodDay(
    val day: Long,
    val kcal: Double,
    val protein: Double,
    val fluidMl: Int,
    val targetKcal: Int,
    val targetWater: Int,
    /** Minute of the day of the last food (not drinks), null if none. */
    val lastMealMin: Int?,
    /** Minute of the day of the last coffee / strong tea, null if none. */
    val lastCoffeeMin: Int? = null,
)

/**
 * The whole picture: looks at ALL data together over the last 1–4 weeks — sleep, night pulse, training load
 * and its spread, strength frequency, zones, steps, food, water, weight and waist — and says in plain words
 * what is going well, what is wrong and what exactly to do. Findings are sorted by importance.
 */
object Insights {
    private const val DAY = 86_400_000L

    /** level: 0 = good, 1 = worth attention, 2 = important. */
    data class Insight(val level: Int, val area: String, val title: String, val detail: String, val action: String, val weight: Int = 0)

    data class Picture(val headline: String, val level: Int, val items: List<Insight>, val sleepPlan: SleepPlan? = null)

    /** Personal sleep schedule from your own nights. */
    data class SleepPlan(val bed: String, val wake: String, val now: String, val lines: List<String>)

    fun analyze(
        p: Profile, goal: Goal, days: List<DailyStats>, workouts: List<Workout>, ext: List<ExtWorkout>, passive: List<PassiveDay>,
        food: List<FoodDay>, weights: List<WeightEntry>, body: List<BodyEntry>, tests: List<HrvRecord>,
        weeklyWeightChange: Double?, now: Long = System.currentTimeMillis(),
    ): Picture {
        val out = ArrayList<Insight>()
        val today = now - now % DAY
        val tz = java.util.TimeZone.getDefault()
        fun localDay(t: Long): Long = java.time.Instant.ofEpochMilli(t).atZone(tz.toZoneId()).toLocalDate().toEpochDay()
        val bounds = Physiology.zoneBounds(p)
        val real = workouts.filter { Physiology.isRealWorkout(it) }
        val extNew = ext.filter { e -> real.none { w -> e.start < w.end && e.end > w.start } }

        // ---------------- Sleep ----------------
        val nights = days.filter { it.day > now - 7 * DAY && (it.sleepMin ?: 0) > 120 }.sortedBy { it.day }
        if (nights.size >= 4) {
            val avg = nights.map { it.sleepMin!! }.average()
            val short = nights.filter { it.sleepMin!! < 360 }
            val shortTxt = short.joinToString(", ") { fmtH(it.sleepMin!!) }
            when {
                avg < 390 || (short.size >= 2 && avg < 420) || short.size >= 3 -> out += Insight(2, "Сон", "Недосып мешает восстановлению",
                    "Сон за неделю в среднем ${fmtH(avg.roundToInt())}" + (if (short.isNotEmpty()) "; ночей меньше 6 ч: ${short.size} ($shortTxt)" else "") + ". Во сне растут мышцы и восстанавливается сердце — недосып съедает пользу тренировок.",
                    "Ложитесь в одно время, цель 7,5–8 ч. После ночи меньше 6 ч — только лёгкая тренировка.", 90)
                avg < 420 || short.size >= 2 -> out += Insight(1, "Сон", if (short.size >= 2) "Есть короткие ночи" else "Сна немного не хватает",
                    "В среднем ${fmtH(avg.roundToInt())} за неделю" + (if (short.isNotEmpty()) ", короткие ночи: $shortTxt" else "") + ".",
                    "Добавьте 30 минут сна — это лучший способ восстановиться для роста мышц.", 50)
                else -> out += Insight(0, "Сон", "Сон в порядке", "В среднем ${fmtH(avg.roundToInt())} за неделю.", "Так держать.", 0)
            }
        }

        // ---------------- Night pulse ----------------
        val night = passive.filter { it.nightAvg != null }.sortedBy { it.day }
        if (night.size >= 5) {
            val base = night.dropLast(1).takeLast(10).map { it.nightAvg!! }.sorted().let { it[it.size / 2] }
            val last3 = night.takeLast(3)
            val high = last3.filter { it.nightAvg!! - base >= 5 }
            val lastN = night.last()
            if (high.isNotEmpty()) {
                val recovered = lastN.nightAvg!! - base < 3
                out += Insight(if (recovered) 1 else 2, "Пульс", if (recovered) "Ночной пульс был повышен, сейчас в норме" else "Ночной пульс повышен",
                    "Обычно ночью у вас ~$base, а было ${high.joinToString(", ") { "${it.nightAvg}" }}. Так бывает после тяжёлых дней подряд, недосыпа, позднего плотного ужина, алкоголя или начала простуды.",
                    if (recovered) "Восстановились — можно тренироваться по плану, но не ставьте тяжёлые дни подряд."
                    else "Сегодня лёгкий день или отдых, пока ночной пульс не вернётся к ~$base.", if (recovered) 40 else 85)
            }
        }

        // ---------------- Training load and spread ----------------
        fun loadOf(a: Long, b: Long) = real.filter { it.start in a until b }.sumOf { it.trimp } + extNew.filter { it.start in a until b }.sumOf { it.trimp }
        val wk = loadOf(now - 7 * DAY, now + 1)
        val prev = (1..3).map { loadOf(now - (it + 1) * 7 * DAY, now - it * 7 * DAY) }.filter { it > 0 }
        if (prev.size >= 2) {
            val norm = prev.average()
            val r = wk / norm
            when {
                r > 1.6 -> out += Insight(2, "Нагрузка", "Нагрузка за неделю резко выросла",
                    "Эта неделя ${wk.roundToInt()} против обычных ${norm.roundToInt()} (×${"%.1f".format(r)}). Резкий рост — главный риск травм и перетренированности.",
                    "Следующие 3–4 дня — не больше одной тяжёлой тренировки, остальное зона 2 и прогулки.", 80)
                r > 1.4 -> out += Insight(1, "Нагрузка", "Нагрузка выше обычной",
                    "Эта неделя ${wk.roundToInt()} против обычных ${norm.roundToInt()} (+${((r - 1) * 100).roundToInt()} %). Немного — нормально, но два таких недели подряд уже риск переутомления.",
                    "Следующая неделя — не больше обычной: 3 зала, между ними прогулки или зона 2.", 55)
                r < 0.6 -> out += Insight(1, "Нагрузка", "Неделя легче обычной",
                    "${wk.roundToInt()} против обычных ${norm.roundToInt()}. Для разгрузки это хорошо, но долго так — форма начнёт падать.",
                    "Если самочувствие хорошее — возвращайтесь к обычному объёму.", 20)
                else -> out += Insight(0, "Нагрузка", "Нагрузка в безопасных пределах",
                    "Эта неделя ${wk.roundToInt()}, обычно ${norm.roundToInt()} (${if (r >= 1) "+" else ""}${((r - 1) * 100).roundToInt()} %). До +40 % за неделю — безопасный рост (как «сбалансированность» в Samsung Health: 0,8–1,5).",
                    "Продолжайте; раз в 4–6 недель делайте неделю на 30–40 % легче.", 0)
            }
        }

        // Strength: how often and how close together
        fun strengthDays(a: Long): List<Long> = (real.filter { w -> w.start >= a && w.segments.any { it.type.strength && it.activeSec >= 600 } }.map { localDay(it.start) } +
            extNew.filter { it.start >= a && it.strength && it.minutes >= 10 }.map { localDay(it.start) }).distinct().sorted()
        val sDays = strengthDays(now - 14 * DAY)
        val sWeek = sDays.count { it >= localDay(now - 6 * DAY) }
        val backToBack = sDays.zipWithNext().filter { (a, b) -> b - a == 1L }
        if (backToBack.isNotEmpty()) {
            out += Insight(1, "Силовая", "Силовые два дня подряд",
                "За 2 недели было ${backToBack.size} раз(а) силовая на следующий день после силовой. Мышца растёт во время отдыха: для одних и тех же мышц нужно ~48 ч.",
                "Между силовыми — день кардио в зоне 2 или отдых. Если тренируетесь подряд — делите: верх тела / низ тела.", 60)
        }
        if (goal != Goal.HEART) when {
            sWeek == 0 && sDays.isEmpty() && real.isNotEmpty() -> out += Insight(1, "Силовая", "Нет силовых 2 недели",
                "Для мышц и обмена веществ нужно минимум 2 силовые в неделю.", "Добавьте 2 силовые: всё тело, 5–6 упражнений по 3 подхода.", 55)
            sWeek in 2..3 -> out += Insight(0, "Силовая", "Силовых достаточно", "$sWeek силовых за 7 дней — оптимально для роста мышц.", "Добавляйте вес или повтор каждую неделю.", 0)
            sWeek >= 4 -> out += Insight(1, "Силовая", "Силовых много",
                "$sWeek силовых за 7 дней. После 40 лет восстановление медленнее — больше не значит лучше.", "3 силовые в неделю, между ними кардио или отдых.", 50)
            else -> {}
        }

        // Zones: heart training
        val cardioSegs = real.filter { it.start > now - 14 * DAY }.flatMap { it.segments }.filter { !it.type.strength }
        val z2 = cardioSegs.sumOf { it.zoneSec[2] } / 60
        val z3 = cardioSegs.sumOf { it.zoneSec[3] } / 60
        val z45all = (real.filter { it.start > now - 7 * DAY }.sumOf { it.zoneSec[4] + it.zoneSec[5] } +
            extNew.filter { it.start > now - 7 * DAY }.sumOf { it.zoneSec[4] + it.zoneSec[5] }) / 60
        val heartMin = (real.filter { it.start > now - 7 * DAY }.sumOf { it.zoneSec[2] + it.zoneSec[3] } +
            extNew.filter { it.start > now - 7 * DAY }.sumOf { it.zoneSec[2] + it.zoneSec[3] }) / 60 + z45all * 2
        if (z2 + z3 >= 40 && z3 > z2 * 1.3) out += Insight(1, "Кардио", "Кардио чаще в зоне 3, чем в зоне 2",
            "За 2 недели в кардио: зона 2 — $z2 мин, зона 3 — $z3 мин. Зона 3 утомляет сильнее, а сердце и выносливость лучше растут от длинной зоны 2.",
            "На орбитреке и дорожке держите пульс ${bounds[1]}–${bounds[2]} (можно говорить фразами). Тяжёлое — 1 раз в неделю.", 45)
        if (real.isNotEmpty() || extNew.isNotEmpty()) when {
            heartMin < 150 -> out += Insight(1, "Сердце", "Мало минут для сердца",
                "$heartMin из 150 минут в неделю (норма ВОЗ).", "Добавьте 2–3 раза по 30–40 мин зоны 2 или быстрые прогулки.", 40)
            else -> out += Insight(0, "Сердце", "Норма ВОЗ для сердца выполнена", "$heartMin минут за 7 дней (норма 150–300).", "Отлично.", 0)
        }

        // Steps
        val steps = days.filter { it.day > now - 7 * DAY && it.day < today }.mapNotNull { it.steps?.takeIf { s -> s > 500 } }
        if (steps.size >= 4) {
            val a = steps.average().roundToInt()
            if (a < 7000) out += Insight(1, "Активность", "Мало шагов", "В среднем $a шагов в день.", "Цель 8 000+: прогулка 20–30 мин после еды.", 30)
            else out += Insight(0, "Активность", "Шагов достаточно", "В среднем $a в день.", "Так держать.", 0)
        }

        // ---------------- Food, water, weight ----------------
        val fd = food.filter { it.day > now - 14 * DAY && it.day < today && it.kcal >= 1200 }
        if (fd.size >= 4) {
            val kcal = fd.map { it.kcal }.average()
            val target = fd.map { it.targetKcal }.average()
            val prot = fd.map { it.protein }.average() / p.weightKg
            val wch = weeklyWeightChange
            val (lo, hi) = when (goal) {
                Goal.FAT_LOSS -> -0.009 * p.weightKg to -0.003 * p.weightKg
                Goal.MASS -> 0.001 * p.weightKg to 0.005 * p.weightKg
                Goal.HYBRID -> 0.0 to 0.0035 * p.weightKg
                else -> -0.002 * p.weightKg to 0.002 * p.weightKg
            }
            if (wch != null) {
                val wTxt = "${if (wch >= 0) "+" else ""}${"%.2f".format(wch)} кг/нед"
                when {
                    wch > hi + 0.1 -> out += Insight(2, "Вес", "Вес растёт быстрее, чем нужно для качественной массы",
                        "Вес $wTxt при калориях ${kcal.roundToInt()} в день (норма ~${target.roundToInt()}). Для «${goal.title}» в вашем возрасте оптимально до +${"%.1f".format(hi)} кг/нед — всё, что быстрее, в основном жир.",
                        "Уберите ~${(((wch - hi) * 7700 / 7).roundToInt() / 50 * 50).coerceIn(150, 500)} ккал в день: сладкое, батончики, лишние гарниры. Белок оставьте.", 75)
                    wch < lo - 0.1 -> out += Insight(if (goal == Goal.FAT_LOSS) 2 else 1, "Вес", "Вес уходит быстрее, чем нужно",
                        "Вес $wTxt. При слишком быстром снижении уходят и мышцы.", "Добавьте ~200 ккал в день, лучше углеводами до и после тренировки.", 60)
                    else -> out += Insight(0, "Вес", "Вес меняется как надо", "Вес $wTxt — в пределах цели «${goal.title}».", "Продолжайте так питаться.", 0)
                }
            } else if (abs(kcal - target) > 300) out += Insight(1, "Питание", if (kcal > target) "Едите больше нормы" else "Едите меньше нормы",
                "В среднем ${kcal.roundToInt()} ккал при норме ~${target.roundToInt()}.", "Взвешивайтесь 2–3 раза в неделю утром — тренер подстроит норму по весу.", 35)

            when {
                prot < 1.5 -> out += Insight(2, "Питание", "Мало белка", "${"%.1f".format(prot)} г на кг веса в день — для мышц нужно 1,6–2,2.",
                    "Добавьте белок в каждый приём: курица, творог, яйца, рыба (25–40 г за раз).", 70)
                prot > 2.4 -> out += Insight(1, "Питание", "Белка больше, чем нужно", "${"%.1f".format(prot)} г/кг — польза выше 2,2 г/кг уже не растёт, а калории лишние.",
                    "Можно убрать один батончик или коктейль в день и заменить их овощами/фруктами.", 25)
                else -> out += Insight(0, "Питание", "Белка достаточно", "${"%.1f".format(prot)} г на кг веса — в норме для роста мышц.", "Так держать.", 0)
            }
            val late = fd.mapNotNull { it.lastMealMin }.filter { it >= 22 * 60 }
            if (late.size >= 3) out += Insight(1, "Питание", "Поздняя еда", "${late.size} из ${fd.size} дней последняя еда после 22:00 — сон и ночной пульс от этого хуже.",
                "Последняя плотная еда за 2–3 ч до сна; вечером — лёгкий белок (творог, кефир).", 30)
            val wat = fd.filter { it.fluidMl > 0 }
            if (wat.size >= 4) {
                val ratio = wat.map { it.fluidMl.toDouble() / it.targetWater }.average()
                if (ratio < 0.8) out += Insight(1, "Вода", "Пьёте меньше нормы",
                    "В среднем ${wat.map { it.fluidMl }.average().roundToInt()} мл при норме ~${wat.map { it.targetWater }.average().roundToInt()}.",
                    "Включите напоминания о воде на часах.", 30)
            }
        } else out += Insight(1, "Питание", "Мало данных о еде", "Внесено полных дней за 2 недели: ${fd.size}.",
            "Вносите еду хотя бы 4–5 дней в неделю — тогда тренер сможет сказать, почему меняется вес.", 20)

        // Waist
        val waists = body.filter { it.waistCm != null }.sortedBy { it.time }
        if (waists.size >= 2) {
            val first = waists.firstOrNull { it.time >= now - 35 * DAY } ?: waists.first()
            val last = waists.last()
            val d = last.waistCm!! - first.waistCm!!
            if (last.time - first.time >= 5 * DAY && d >= 1.0 && goal != Goal.MASS)
                out += Insight(1, "Тело", "Талия растёт", "Талия ${"%.0f".format(first.waistCm)} → ${"%.0f".format(last.waistCm)} см. Если талия растёт вместе с весом — прибавка идёт и в жир.",
                    "Небольшое снижение калорий (−200) и больше шагов; меряйте талию раз в неделю утром.", 45)
        }

        // Morning test
        if (tests.none { it.rmssd > 0 && it.time > now - 14 * DAY })
            out += Insight(1, "Данные", "Нет утренних тестов с ремнём",
                "Вариабельность пульса (ВСР) — самый ранний признак переутомления, раньше пульса и самочувствия.",
                "3 утра подряд: сразу после пробуждения, лёжа, ремень H10 → «Утренний тест» на часах (2 мин).", 15)

        val sleepPlan = sleepPlan(days, real, food, passive, now)

        val items = out.sortedWith(compareByDescending<Insight> { it.level }.thenByDescending { it.weight })
        val bad = items.filter { it.level == 2 }
        val headline = when {
            bad.isNotEmpty() -> "Главное: " + bad.first().title.replaceFirstChar { c -> c.lowercase() }
            items.any { it.level == 1 } -> "В целом хорошо, есть что улучшить"
            items.isNotEmpty() -> "Всё идёт хорошо ✓"
            else -> "Пока мало данных для разбора"
        }
        return Picture(headline, bad.size.coerceAtMost(1) * 2 + if (bad.isEmpty() && items.any { it.level == 1 }) 1 else 0, items, sleepPlan)
    }

    /**
     * When to go to bed and get up — from YOUR nights of the last 3 weeks:
     * - wake time = your usual wake time (a steady wake time sets the body clock best);
     * - bed time = wake − (your best sleep length + the time you usually lie awake), aiming at 7.5–8 h of sleep;
     * - which nights went best for you (long sleep, low pulse the next morning) and what was different;
     * - what in your data pushes sleep later: evening coffee, late training, late food.
     */
    private fun sleepPlan(days: List<DailyStats>, workouts: List<Workout>, food: List<FoodDay>, passive: List<PassiveDay>, now: Long): SleepPlan? {
        val zone = java.time.ZoneId.systemDefault()
        fun minOfDay(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).let { it.hour * 60 + it.minute }
        // Bed time as minutes after 18:00 so that 23:30 and 00:30 compare correctly.
        fun bedKey(t: Long) = (minOfDay(t) - 18 * 60 + 1440) % 1440
        fun hm(m: Int) = "%02d:%02d".format(((m % 1440) + 1440) % 1440 / 60, ((m % 1440) + 1440) % 1440 % 60)
        val nights = days.filter { it.day > now - 21 * DAY && it.sleepStart != null && it.sleepEnd != null && (it.sleepMin ?: 0) > 180 }
        if (nights.size < 5) return null
        val beds = nights.map { bedKey(it.sleepStart!!) }.sorted()
        val wakes = nights.map { minOfDay(it.sleepEnd!!) }.sorted()
        val medBed = beds[beds.size / 2]; val medWake = wakes[wakes.size / 2]
        val bedSpread = beds[(beds.size * 0.85).toInt().coerceAtMost(beds.size - 1)] - beds[(beds.size * 0.15).toInt()]
        val wakeSpread = wakes[(wakes.size * 0.85).toInt().coerceAtMost(wakes.size - 1)] - wakes[(wakes.size * 0.15).toInt()]
        val awake = nights.mapNotNull { it.awakeMin }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 20
        val avgSleep = nights.map { it.sleepMin!! }.average()

        // Your best nights: long enough sleep and a low pulse the next morning.
        val restMed = nights.mapNotNull { it.restHr }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val best = nights.filter { (it.sleepMin ?: 0) >= 450 && (restMed == null || (it.restHr ?: 99) <= restMed) }
        val bestBed = best.map { bedKey(it.sleepStart!!) }.sorted().let { if (it.size >= 2) it[it.size / 2] else null }

        val targetSleep = 465 // 7 h 45 min asleep: middle of the 7.5–8 h that suits strength training and the heart
        val wake = (medWake + 7) / 15 * 15
        val bed = ((wake - targetSleep - awake.coerceIn(10, 45)) / 15) * 15 + 1440
        val lines = ArrayList<String>()
        lines += "Сейчас вы обычно ложитесь в ~${hm(medBed + 18 * 60)} и встаёте в ~${hm(medWake)}, спите в среднем ${fmtH(avgSleep.roundToInt())}, просыпаетесь ночью на ~$awake мин."
        if (bestBed != null) lines += "Лучше всего вы спали (≥7,5 ч и низкий пульс утром), когда ложились около ${hm(bestBed + 18 * 60)}."
        if (bedSpread > 90) lines += "Время отбоя скачет на ${bedSpread / 60} ч ${bedSpread % 60} мин — организму трудно настроиться. Ровный режим ±30 мин даёт более глубокий сон."
        if (wakeSpread > 90) lines += "Подъём скачет на ${wakeSpread / 60} ч ${wakeSpread % 60} мин. Вставайте в одно время и в выходные — это главный «якорь» биочасов."
        // What pushes sleep later in YOUR data
        val lateCoffee = food.filter { it.day > now - 14 * DAY }.mapNotNull { it.lastCoffeeMin }.filter { it >= 15 * 60 }
        if (lateCoffee.isNotEmpty()) lines += "Кофе после 15:00 был ${lateCoffee.size} раз(а) (последний в ${hm(lateCoffee.max())}). Кофеин действует 6–8 ч — последний кофе до 14:00."
        val lateFood = food.filter { it.day > now - 14 * DAY }.mapNotNull { it.lastMealMin }.filter { m ->
            val mk = (m - 18 * 60 + 1440) % 1440; val bk = (bed - 18 * 60 + 2880) % 1440
            m >= 17 * 60 && mk in (bk - 150)..bk }
        if (lateFood.size >= 2) lines += "Плотная еда позже чем за 2,5 ч до сна была ${lateFood.size} раз(а) — ночной пульс от этого выше. Ужин до ${hm(bed - 150)}, после — лёгкий белок."
        val lateWk = workouts.filter { it.end > now - 14 * DAY && minOfDay(it.end) >= 20 * 60 + 30 }
        if (lateWk.isNotEmpty()) lines += "Тренировки, закончившиеся после 20:30: ${lateWk.size}. Интенсивное поздно вечером сдвигает засыпание — тяжёлое лучше до 19:00."
        // Night pulse after short vs long nights (your own link)
        val pairs = nights.mapNotNull { n -> passive.firstOrNull { it.day == n.day }?.nightAvg?.let { n.sleepMin!! to it } }
        val shortN = pairs.filter { it.first < 390 }.map { it.second }; val longN = pairs.filter { it.first >= 450 }.map { it.second }
        if (shortN.isNotEmpty() && longN.isNotEmpty() && shortN.average() - longN.average() >= 2)
            lines += "У вас ночной пульс после коротких ночей ~${shortN.average().roundToInt()}, после длинных ~${longN.average().roundToInt()} — длинный сон реально снижает нагрузку на сердце."
        lines += "За 1 ч до сна — без экрана и яркого света, в спальне прохладно (17–19 °C). Днём 20–30 мин на улице при дневном свете."
        return SleepPlan(hm(bed), hm(wake), "${hm(medBed + 18 * 60)} → ${hm(medWake)}", lines)
    }

    private fun fmtH(min: Int) = "${min / 60}:${"%02d".format(min % 60)}"
}
