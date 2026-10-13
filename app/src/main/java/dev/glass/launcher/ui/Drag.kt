package dev.glass.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shadow
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.FolderItem

enum class DragSource { Grid, Dock, Folder }

/** Sürüklenen öğenin durumu. Konum, pencere (kök) koordinatlarındadır. */
@Stable
class DragState {
    var key by mutableStateOf<String?>(null)
        private set
    var pos by mutableStateOf(Offset.Zero)
    var iconDp by mutableStateOf(60.dp)
        private set

    /** Bırakıldığında çağrılacak işlem; sürükleme başlarken hücre tarafından verilir. */
    private var dropHandler: ((Offset) -> Unit)? = null

    fun start(id: String, icon: Dp, at: Offset, onDrop: (Offset) -> Unit) {
        key = id
        iconDp = icon
        pos = at
        dropHandler = onDrop
    }

    /** Parmak kalkınca: önce hayalet kaldırılır, sonra bırakma işlemi uygulanır. */
    fun finish() {
        val p = pos
        val h = dropHandler
        clear()
        h?.invoke(p)
    }

    fun clear() {
        key = null
        dropHandler = null
    }
}

private val CellLabelStyle = TextStyle(
    color = Color.White,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color(0x66000000), Offset(0f, 1f), 4f),
)

/**
 * Uzun basınca sürüklenebilen hücre (uygulama veya klasör). Düzenleme modunda hafifçe sallanır.
 * Üzerine başka bir öğe getirilince büyür (klasör oluşturma/ekleme ipucu).
 */
@Composable
internal fun DraggableCell(
    id: String,
    label: String,
    showLabel: Boolean,
    icon: Dp,
    pitchX: Dp,
    cellHeight: Dp,
    drag: DragState,
    acceptsMerge: Boolean,
    edit: Boolean,
    jiggle: State<Float>,
    onClick: (Offset) -> Unit,
    onDragStart: () -> Unit,
    onDrop: (Offset) -> Unit,
    iconContent: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) } // sütunun sol-üst köşesi (kök koordinat)
    val density = LocalDensity.current
    val iconPx = with(density) { icon.toPx() }
    val pitchPx = with(density) { pitchX.toPx() }
    val center = Offset(pitchPx / 2f, iconPx / 2f)

    val hovered by remember(iconPx, pitchPx, acceptsMerge) {
        derivedStateOf {
            val k = drag.key
            acceptsMerge && k != null && k != id && (drag.pos - (origin + center)).getDistance() < iconPx * 0.5f
        }
    }
    val hoverScale by animateFloatAsState(if (hovered) 1.14f else 1f, label = "hover")
    val sign = remember(id) { if ((id.hashCode() and 1) == 0) 1f else -1f }
    val onDropNow by rememberUpdatedState(onDrop)
    val onStartNow by rememberUpdatedState(onDragStart)
    val onClickNow by rememberUpdatedState(onClick)

    Box(Modifier.width(icon).height(cellHeight), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .requiredWidth(pitchX)
                .onGloballyPositioned { origin = it.positionInRoot() }
                .graphicsLayer {
                    alpha = if (drag.key == id) 0f else 1f
                    scaleX = hoverScale
                    scaleY = hoverScale
                    rotationZ = if (edit) jiggle.value * sign else 0f
                }
                // Dokunma ve uzun basma tek dedektörde; sürükleme sonrası hareketi kök katman izler.
                .pointerInput(id) {
                    detectTapGestures(
                        onTap = { onClickNow(origin + center) },
                        onLongPress = { local ->
                            drag.start(id, icon, origin + local) { p -> onDropNow(p) }
                            onStartNow()
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            iconContent()
            if (showLabel) {
                Text(
                    label,
                    style = CellLabelStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** Kapalı klasör: 3×3 ızgarada ilk sayfanın en çok 9 küçük simgesi. */
@Composable
internal fun FolderIcon(
    folder: FolderItem,
    byKey: Map<String, AppInfo>,
    vm: LauncherViewModel,
    icon: Dp,
    strength: Float,
) {
    val pad = icon * 0.10f
    val gap = icon * 0.04f
    val mini = (icon - pad * 2f - gap * 2f) / 3f
    Box(Modifier.size(icon).liquidGlass(RoundedCornerShape(icon * 0.225f), strength * 0.8f, 2.dp)) {
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
            folder.keys.take(9).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { k ->
                        val app = byKey[k]
                        if (app != null) AppIconImage(app, vm, mini, false, 0f)
                        else Spacer(Modifier.size(mini))
                    }
                }
            }
        }
    }
}
