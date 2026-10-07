package fi.sarmat.pulsetrainer

import androidx.compose.foundation.background
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
import androidx.compose.material3.Switch
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

/** Visible card ids of a tab in your order. Recomposes when the order changes. */
@Composable
fun rememberCards(tab: String, defaults: List<String>): List<String> {
    val v by PhoneStore.cardsVersion.collectAsState()
    return remember(v, tab) {
        val hidden = PhoneStore.hiddenCards(tab)
        PhoneStore.cardOrder(tab, defaults).filter { it !in hidden }
    }
}

/** Small "⚙ Порядок" button for a tab header. */
@Composable
fun ArrangeButton(tab: String, titles: Map<String, String>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("⇅ Порядок плашек", fontSize = 15.sp) }
    if (open) ReorderDialog(tab, titles) { open = false }
}

/**
 * Drag-and-drop list: hold a row and drag it up or down (or use ↑ ↓).
 * The switch shows or hides the card.
 */
@Composable
fun ReorderDialog(tab: String, titles: Map<String, String>, onClose: () -> Unit) {
    val ids = remember { mutableStateListOf(*PhoneStore.cardOrder(tab, titles.keys.toList()).toTypedArray()) }
    var hidden by remember { mutableStateOf(PhoneStore.hiddenCards(tab)) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 62.dp.toPx() }

    fun save() = PhoneStore.saveCardOrder(tab, ids.toList())
    fun swap(a: Int, b: Int) { val t = ids[a]; ids[a] = ids[b]; ids[b] = t }

    AlertDialog(
        onDismissRequest = { save(); onClose() },
        title = { Text("Порядок плашек") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Удерживайте строку и перетащите. Переключатель — показать или скрыть.", color = Dim, fontSize = 13.sp)
                ids.forEach { id ->
                    key(id) {
                        val isDrag = dragging == id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(58.dp)
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
                            Text("≡", color = Dim, fontSize = 22.sp, modifier = Modifier.padding(end = 8.dp))
                            Text(titles[id] ?: id, color = if (id in hidden) Dim else Color.White, fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
                            TextButton(onClick = { val i = ids.indexOf(id); if (i > 0) { swap(i, i - 1); save() } }) { Text("↑", fontSize = 18.sp) }
                            TextButton(onClick = { val i = ids.indexOf(id); if (i < ids.lastIndex) { swap(i, i + 1); save() } }) { Text("↓", fontSize = 18.sp) }
                            Switch(checked = id !in hidden, onCheckedChange = { on ->
                                PhoneStore.setCardHidden(tab, id, !on); hidden = PhoneStore.hiddenCards(tab)
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(); onClose() }) { Text("Готово") } },
    )
}
