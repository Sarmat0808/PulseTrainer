package fi.sarmat.pulsetrainer

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import fi.sarmat.pulsetrainer.core.Physiology
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Health tiles (swipe left from the watch face), like Samsung's:
 * «Сегодня» (readiness, energy, recovery, sleep, steps), «Сон», «Стресс».
 * Data come from the phone's coach; without the phone — from the watch's own tracking.
 * Tiles refresh when new data arrive and at least every 30 minutes.
 */
object HealthTiles {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val DIM = 0xFFC8D0D8.toInt()
    private const val GREEN = 0xFF27AE60.toInt()
    private const val YELLOW = 0xFFF2C94C.toInt()
    private const val RED = 0xFFEB5757.toInt()
    private const val BLUE = 0xFF2D9CDB.toInt()
    private const val PURPLE = 0xFF9775FA.toInt()

    fun refresh(ctx: Context) {
        try {
            val u = TileService.getUpdater(ctx)
            u.requestUpdate(TodayTileService::class.java)
            u.requestUpdate(SleepTileService::class.java)
            u.requestUpdate(StressTileService::class.java)
        } catch (_: Throwable) {}
        PtComplication.refreshAll(ctx)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) = LayoutElementBuilders.Text.Builder()
        .setText(s).setMaxLines(lines)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
            .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build())
        .build()

    private fun open(ctx: Context, screen: String) = ModifiersBuilders.Modifiers.Builder().setClickable(
        ModifiersBuilders.Clickable.Builder().setId("open_$screen").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(ctx.packageName).setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping("open", ActionBuilders.AndroidStringExtra.Builder().setValue(screen).build()).build()
            ).build()
        ).build()
    ).build()

    private fun box(ctx: Context, screen: String, content: LayoutElement) = LayoutElementBuilders.Box.Builder()
        .setWidth(expand()).setHeight(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(open(ctx, screen))
        .addContent(content).build()

    private fun col(vararg e: LayoutElement): LayoutElement {
        val c = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        e.forEach { c.addContent(it) }
        return c.build()
    }

    private fun gap(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    /** Two numbers side by side: value + label under it. */
    private fun pair(a: Pair<String, String>, ca: Int, b: Pair<String, String>, cb: Int): LayoutElement {
        fun cell(v: Pair<String, String>, c: Int) = LayoutElementBuilders.Column.Builder().setWidth(dp(74f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(v.first, 22f, c, bold = true)).addContent(text(v.second, 12f, DIM)).build()
        return LayoutElementBuilders.Row.Builder().addContent(cell(a, ca)).addContent(cell(b, cb)).build()
    }

    private fun lvColor(l: Int) = when (l) { 0 -> GREEN; 1 -> YELLOW; else -> RED }

    private fun fmtH(min: Int) = "${min / 60}:${"%02d".format(min % 60)}"

    fun todayLayout(ctx: Context): LayoutElement {
        val c = Storage.todayCoach() ?: Storage.localCoach()
        val p = Passive.summaries().lastOrNull()
        val sleep = c?.sleepMin?.takeIf { it > 0 } ?: p?.let { d -> val a = d.sleepStart; val b = d.sleepEnd; if (a != null && b != null) ((b - a) / 60000).toInt() - (d.awakeMin ?: 0) else null }
        val steps = c?.steps?.takeIf { it >= 0 } ?: p?.steps
        val ready = c?.score?.toString() ?: "—"
        val energy = c?.energy?.takeIf { it >= 0 }
        val rec = c?.recH?.takeIf { it >= 0 }
        return box(ctx, "ready", col(
            text("Сегодня", 13f, DIM),
            gap(2f),
            pair(ready to "готовность", lvColor(c?.level ?: 1), (energy?.toString() ?: "—") to "энергия", BLUE),
            gap(4f),
            pair((if (rec == null) "—" else if (rec == 0) "✓" else "$rec ч") to "восстановл.", if ((rec ?: 0) == 0) GREEN else YELLOW,
                (sleep?.let { fmtH(it) } ?: "—") to "сон", PURPLE),
            gap(4f),
            text("Шаги ${steps ?: "—"}", 14f, WHITE, bold = true),
        ))
    }

    fun sleepLayout(ctx: Context): LayoutElement {
        val c = Storage.todayCoach()
        val p = Passive.summaries().lastOrNull { it.sleepStart != null }
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        val min = c?.sleepMin?.takeIf { it > 0 } ?: p?.let { ((it.sleepEnd!! - it.sleepStart!!) / 60000).toInt() - (it.awakeMin ?: 0) }
        val score = c?.sleepScore?.takeIf { it >= 0 }
        val start = c?.sleepStart?.takeIf { it > 0 } ?: p?.sleepStart
        val end = c?.sleepEnd?.takeIf { it > 0 } ?: p?.sleepEnd
        val lines = ArrayList<LayoutElement>()
        lines += text("Сон", 13f, DIM)
        lines += text(min?.let { "${it / 60} ч ${it % 60} мин" } ?: "Нет данных", 22f, WHITE, bold = true)
        // Short word so the line fits the round screen.
        if (score != null) lines += text("Оценка $score · " + when { score >= 80 -> "отлично"; score >= 65 -> "хорошо"; score >= 50 -> "средне"; else -> "плохо" }, 14f, if (score >= 75) GREEN else if (score >= 55) YELLOW else RED, bold = true)
        if (start != null && end != null) lines += text("${hm.format(Date(start))} – ${hm.format(Date(end))}", 14f, DIM)
        val deep = c?.deep?.takeIf { it >= 0 }; val rem = c?.rem?.takeIf { it >= 0 }
        if (deep != null && rem != null) lines += text("Глубокий $deep · REM $rem мин", 13f, PURPLE)
        else p?.wakeups?.let { lines += text("Пробуждений $it · пульс ночью ${p.restHr ?: "—"}", 13f, DIM) }
        val rest = c?.rest?.takeIf { it > 0 } ?: p?.restHr
        if (rest != null && deep != null) lines += text("Пульс покоя $rest", 13f, DIM)
        return box(ctx, "ready", col(*lines.toTypedArray()))
    }

    fun stressLayout(ctx: Context): LayoutElement {
        val last = Storage.stressHistory().lastOrNull()
        val ago = last?.let { ((System.currentTimeMillis() - it.time) / 60000).toInt() }
        val lines = ArrayList<LayoutElement>()
        lines += text("Стресс", 13f, DIM)
        if (last != null) {
            val col = when (Physiology.stressLevel(last.score)) { 0 -> GREEN; 1 -> YELLOW; else -> RED }
            lines += text("${last.score}", 34f, col, bold = true)
            lines += text(Physiology.stressLabel(last.score), 15f, col, bold = true)
            lines += text(if (ago!! < 60) "$ago мин назад" else "${ago / 60} ч назад", 13f, DIM)
        } else lines += text("Ещё не измеряли", 16f, WHITE, bold = true)
        lines += gap(4f)
        lines += text("Нажмите — измерить", 13f, BLUE, bold = true)
        return box(ctx, "stress", col(*lines.toTypedArray()))
    }

    fun tile(layout: LayoutElement): TileBuilders.Tile = TileBuilders.Tile.Builder()
        .setResourcesVersion("1")
        .setFreshnessIntervalMillis(30 * 60_000L)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
        .build()

    val resources: ResourceBuilders.Resources get() = ResourceBuilders.Resources.Builder().setVersion("1").build()
}

class TodayTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Storage.ensureInit(this); Passive.init(this)
        return Futures.immediateFuture(HealthTiles.tile(HealthTiles.todayLayout(this)))
    }
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(HealthTiles.resources)
}

class SleepTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Storage.ensureInit(this); Passive.init(this)
        return Futures.immediateFuture(HealthTiles.tile(HealthTiles.sleepLayout(this)))
    }
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(HealthTiles.resources)
}

class StressTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Storage.ensureInit(this); Passive.init(this)
        return Futures.immediateFuture(HealthTiles.tile(HealthTiles.stressLayout(this)))
    }
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(HealthTiles.resources)
}
