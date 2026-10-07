package fi.sarmat.pulsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

data class CardLayout(val top: List<String>, val more: List<String>)

/**
 * Cards of a tab in your order, split into the important ones at the top and the
 * «Подробнее» block at the bottom. Recomposes when the order or placement changes.
 */
@Composable
fun rememberCardLayout(tab: String, defaults: List<String>, moreDefaults: Set<String>): CardLayout {
    val v by PhoneStore.cardsVersion.collectAsState()
    return remember(v, tab) {
        val hidden = PhoneStore.hiddenCards(tab)
        val more = PhoneStore.moreCards(tab, moreDefaults)
        val order = PhoneStore.cardOrder(tab, defaults).filter { it !in hidden }
        CardLayout(order.filter { it !in more }, order.filter { it in more })
    }
}

/** Header button that opens the order / placement dialog. */
/** Opens the profile & settings screen (set by the root). */
val LocalOpenSettings = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun ArrangeButton(tab: String, titles: Map<String, String>, moreDefaults: Set<String> = emptySet()) {
    var open by remember { mutableStateOf(false) }
    val settings = LocalOpenSettings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = true }) { Text("⇅", fontSize = 22.sp) }
        TextButton(onClick = settings) { Text("⚙", fontSize = 24.sp) }
    }
    if (open) ReorderDialog(tab, titles, moreDefaults) { open = false }
}

/**
 * Collapsible «Подробнее» block at the bottom of a tab: everything that is useful
 * but not needed every day. Closed by default.
 */
@Composable
fun MoreBlock(count: Int, title: String = "Подробнее", content: @Composable () -> Unit) {
    if (count == 0) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF232B35))
                .clickable { open = !open }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (open) "Скрыть ▲" else "$count ▼", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (open) content()
    }
}

/** In-card spoiler: a short summary stays visible, the long explanation opens on tap. */
@Composable
fun Expander(label: String = "Подробнее", content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            (if (open) "▲ Скрыть" else "▼ $label"), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)
        )
        if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * Drag-and-drop list: hold a row and drag it up or down (or use ↑ ↓).
 * The chip moves a card between «Вверху», «Подробнее» and «Скрыто».
 */
@Composable
fun ReorderDialog(tab: String, titles: Map<String, String>, moreDefaults: Set<String>, onClose: () -> Unit) {
    val ids = remember { mutableStateListOf(*PhoneStore.cardOrder(tab, titles.keys.toList()).toTypedArray()) }
    var hidden by remember { mutableStateOf(PhoneStore.hiddenCards(tab)) }
    var more by remember { mutableStateOf(PhoneStore.moreCards(tab, moreDefaults)) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 66.dp.toPx() }

    fun save() = PhoneStore.saveCardOrder(tab, ids.toList())
    fun swap(a: Int, b: Int) { val t = ids[a]; ids[a] = ids[b]; ids[b] = t }
    fun cycle(id: String) {
        when {
            id in hidden -> { PhoneStore.setCardHidden(tab, id, false); PhoneStore.setCardMore(tab, id, false, moreDefaults) }
            id in more -> { PhoneStore.setCardMore(tab, id, false, moreDefaults); PhoneStore.setCardHidden(tab, id, true) }
            else -> PhoneStore.setCardMore(tab, id, true, moreDefaults)
        }
        hidden = PhoneStore.hiddenCards(tab); more = PhoneStore.moreCards(tab, moreDefaults)
    }

    AlertDialog(
        onDismissRequest = { save(); onClose() },
        title = { Text("Настроить экран") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Удерживайте строку и перетащите. Кнопка справа: «Вверху» → «Подробнее» → «Скрыто».", color = Dim, fontSize = 14.sp)
                ids.forEach { id ->
                    key(id) {
                        val isDrag = dragging == id
                        val place = when (id) { in hidden -> "Скрыто"; in more -> "Подробнее"; else -> "Вверху" }
                        val placeColor = when (id) { in hidden -> Dim; in more -> Warn; else -> Good }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(62.dp)
                                .zIndex(if (isDrag) 1f else 0f)
                                .graphicsLayer { translationY = if (isDrag) offset else 0f; scaleX = if (isDrag) 1.03f else 1f; scaleY = scaleX }
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDrag) Color(0xFF2D3B4D) else CardBg)
                                .pointerInput(id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = id; offset = 0f },
                                        onDrag = { change, d ->
                                            change.consume()
                                            offset += d.y
                                            val i = ids.indexOf(id)
                                            if (offset > rowPx / 2 && i < ids.lastIndex) { swap(i, i + 1); offset -= rowPx }
                                            else if (offset < -rowPx / 2 && i > 0) { swap(i, i - 1); offset += rowPx }
                                        },
                                        onDragEnd = { dragging = null; offset = 0f; save() },
                                        onDragCancel = { dragging = null; offset = 0f; save() },
                                    )
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("≡", color = Dim, fontSize = 22.sp, modifier = Modifier.padding(end = 6.dp))
                            Text(titles[id] ?: id, color = if (id in hidden) Dim else Color.White, fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
                            TextButton(onClick = { val i = ids.indexOf(id); if (i > 0) { swap(i, i - 1); save() } }) { Text("↑", fontSize = 18.sp) }
                            Text(place, color = placeColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A3038)).clickable { cycle(id) }
                                    .padding(horizontal = 8.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onClose() }) { Text("Готово") } },
    )
}
