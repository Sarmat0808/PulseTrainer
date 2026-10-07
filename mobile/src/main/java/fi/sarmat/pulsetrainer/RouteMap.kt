package fi.sarmat.pulsetrainer

import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fi.sarmat.pulsetrainer.core.Workout
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Real map (OpenStreetMap) with the route, coloured by heart-rate zone, start/finish and every kilometre.
 * Pinch to zoom, drag to move.
 */
@Composable
fun RouteMap(w: Workout, height: Int = 320) {
    val ctx = LocalContext.current
    val map = remember {
        Configuration.getInstance().userAgentValue = ctx.packageName
        Configuration.getInstance().osmdroidBasePath = ctx.cacheDir
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    AndroidView(
        factory = {
            val pts = w.track.map { GeoPoint(it.lat, it.lon) }
            // Route split into pieces coloured by the pulse zone at that moment.
            val bounds = w.zoneBounds
            var hi = 0
            var piece = ArrayList<GeoPoint>()
            var pieceZone = -1
            fun flush() {
                if (piece.size >= 2) map.overlays.add(Polyline(map).apply {
                    setPoints(piece)
                    outlinePaint.color = zoneColor(pieceZone)
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                })
            }
            w.track.forEachIndexed { i, p ->
                while (hi + 1 < w.hr.size && w.hr[hi + 1].t <= p.t) hi++
                val bpm = w.hr.getOrNull(hi)?.bpm ?: 0
                val z = if (bounds.size == 6) fi.sarmat.pulsetrainer.core.Physiology.zoneOf(bpm, bounds) else 2
                if (z != pieceZone && piece.isNotEmpty()) { piece.add(pts[i]); flush(); piece = arrayListOf(pts[i]) } else piece.add(pts[i])
                pieceZone = z
            }
            flush()
            // Kilometre marks
            var dist = 0.0; var nextKm = 1000.0
            for (i in 1 until pts.size) {
                dist += pts[i - 1].distanceToAsDouble(pts[i])
                if (dist >= nextKm) {
                    map.overlays.add(Marker(map).apply { position = pts[i]; title = "${(nextKm / 1000).toInt()} км"; setTextIcon("${(nextKm / 1000).toInt()}") })
                    nextKm += 1000.0
                }
            }
            if (pts.isNotEmpty()) {
                map.overlays.add(Marker(map).apply { position = pts.first(); title = "Старт"; setTextIcon("▶") })
                map.overlays.add(Marker(map).apply { position = pts.last(); title = "Финиш"; setTextIcon("■") })
            }
            map.addOnFirstLayoutListener { _, _, _, _, _ ->
                if (pts.size >= 2) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.25f), false)
            }
            map
        },
        modifier = Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(14.dp)),
    )
}

private fun zoneColor(z: Int) = when (z) {
    0 -> AColor.rgb(127, 140, 141)
    1 -> AColor.rgb(143, 163, 191)
    2 -> AColor.rgb(45, 156, 219)
    3 -> AColor.rgb(39, 174, 96)
    4 -> AColor.rgb(242, 153, 74)
    else -> AColor.rgb(235, 87, 87)
}
