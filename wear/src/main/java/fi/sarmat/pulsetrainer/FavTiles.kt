package fi.sarmat.pulsetrainer

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import fi.sarmat.pulsetrainer.core.SportIcons
import fi.sarmat.pulsetrainer.core.WorkoutType

/**
 * Watch tiles (swipe left from the watch face) with your favourite workouts — tap to start.
 * Two sizes: 4 large buttons or 6 compact ones. Add them like any Samsung tile:
 * long-press the watch face → swipe left → «+», or in the Galaxy Wearable app → Tiles.
 */
object FavTiles {
    private const val RES_VERSION = "3"

    fun refresh(ctx: Context) {
        try {
            TileService.getUpdater(ctx).requestUpdate(Fav4TileService::class.java)
            TileService.getUpdater(ctx).requestUpdate(Fav6TileService::class.java)
        } catch (_: Throwable) {}
    }

    private val ICON_RES: Map<String, Int> = mapOf(
        "fitness_center" to R.drawable.sport_fitness_center,
        "sports_gymnastics" to R.drawable.sport_sports_gymnastics,
        "accessibility_new" to R.drawable.sport_accessibility_new,
        "sports_mma" to R.drawable.sport_sports_mma,
        "directions_run" to R.drawable.sport_directions_run,
        "nordic_walking" to R.drawable.sport_nordic_walking,
        "pedal_bike" to R.drawable.sport_pedal_bike,
        "directions_bike" to R.drawable.sport_directions_bike,
        "directions_walk" to R.drawable.sport_directions_walk,
        "sports_soccer" to R.drawable.sport_sports_soccer,
        "sports_basketball" to R.drawable.sport_sports_basketball,
        "sports_tennis" to R.drawable.sport_sports_tennis,
        "sports_volleyball" to R.drawable.sport_sports_volleyball,
        "pool" to R.drawable.sport_pool,
        "rowing" to R.drawable.sport_rowing,
        "stairs" to R.drawable.sport_stairs,
        "timer" to R.drawable.sport_timer,
        "sports_handball" to R.drawable.sport_sports_handball,
        "hiking" to R.drawable.sport_hiking,
        "downhill_skiing" to R.drawable.sport_downhill_skiing,
        "ice_skating" to R.drawable.sport_ice_skating,
        "music_note" to R.drawable.sport_music_note,
        "self_improvement" to R.drawable.sport_self_improvement,
        "sports" to R.drawable.sport_sports,
    )

    private const val BLUE = 0xFF2D6CDF.toInt()
    private const val CARD = 0xFF1E2A38.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val DIM = 0xFFC8D0D8.toInt()

    private fun launch(ctx: Context, t: WorkoutType) = ModifiersBuilders.Clickable.Builder()
        .setId("start_${t.name}")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(ctx.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping("start", ActionBuilders.AndroidStringExtra.Builder().setValue(t.name).build())
                    .build()
            ).build()
        ).build()

    private fun text(s: String, size: Float, color: Int) = LayoutElementBuilders.Text.Builder()
        .setText(s)
        .setMaxLines(1)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
        .build()

    /** One button: icon in a round badge + the short name under it. */
    private fun cell(ctx: Context, t: WorkoutType, circle: Float, icon: Float, label: Float, width: Float): LayoutElementBuilders.LayoutElement {
        val badge = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(circle)).setHeight(dp(circle))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(BLUE))
                        .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(circle / 2)).build()).build())
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Image.Builder().setResourceId(SportIcons.of(t)).setWidth(dp(icon)).setHeight(dp(icon))
                    .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(WHITE)).build()).build()
            ).build()
        return LayoutElementBuilders.Column.Builder()
            .setWidth(dp(width))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch(ctx, t)).build())
            .addContent(badge)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(text(t.short, label, WHITE))
            .build()
    }

    fun layout(ctx: Context, count: Int): LayoutElementBuilders.LayoutElement {
        val fav = Storage.favorites.value.ifEmpty { Storage.DEFAULT_FAV }.take(count)
        val big = count <= 4
        val cols = if (big) 2 else 3
        val col = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        col.addContent(text("★ Избранное", 13f, DIM))
        col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
        fav.chunked(cols).forEachIndexed { i, row ->
            if (i > 0) col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(if (big) 8f else 6f)).build())
            val r = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
            row.forEach { t ->
                r.addContent(if (big) cell(ctx, t, 52f, 30f, 14f, 84f) else cell(ctx, t, 44f, 26f, 11f, 66f))
            }
            col.addContent(r.build())
        }
        if (fav.isEmpty()) col.addContent(text("Отметьте ★ в приложении", 13f, WHITE))
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(col.build())
            .build()
    }

    fun tile(ctx: Context, count: Int): TileBuilders.Tile = TileBuilders.Tile.Builder()
        .setResourcesVersion(RES_VERSION)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(ctx, count)))
        .build()

    fun resources(): ResourceBuilders.Resources {
        val b = ResourceBuilders.Resources.Builder().setVersion(RES_VERSION)
        ICON_RES.forEach { (id, res) ->
            b.addIdToImageMapping(
                id, ResourceBuilders.ImageResource.Builder().setAndroidResourceByResId(
                    ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build()
                ).build()
            )
        }
        return b.build()
    }

    @Suppress("unused") private val keepCard = CARD
}

class Fav4TileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Storage.ensureInit(this)
        return Futures.immediateFuture(FavTiles.tile(this, 4))
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(FavTiles.resources())
}

class Fav6TileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Storage.ensureInit(this)
        return Futures.immediateFuture(FavTiles.tile(this, 6))
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(FavTiles.resources())
}
