package dev.glass.launcher.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.glass.launcher.data.LauncherSettings
import dev.glass.launcher.data.WidgetItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Widget sayfasının (iOS "Bugün" görünümü) açılma oranı: 0 = kapalı (ana ekran), 1 = tamamen açık.
 * İlk ana ekran sayfasında sağa kaydırınca parmakla birlikte açılır, bırakınca yayla yerine oturur.
 */
class TodayState {
    var progress by mutableFloatStateOf(0f)
        private set
    /** Ekran genişliği (px); kaydırma miktarını orana çevirmek için. */
    var widthPx = 1f
    private var anim: Job? = null

    fun dragBy(dx: Float) {
        anim?.cancel()
        progress = (progress + dx / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** Parmak kalkınca: hızlı fırlatmada yönüne, yavaşta yarıyı geçip geçmediğine göre açılır/kapanır. */
    fun settle(scope: CoroutineScope, velocityX: Float) {
        val target = when {
            velocityX > 700f -> 1f
            velocityX < -700f -> 0f
            else -> if (progress > 0.5f) 1f else 0f
        }
        animateTo(scope, target, velocityX / widthPx.coerceAtLeast(1f))
    }

    fun open(scope: CoroutineScope) = animateTo(scope, 1f, 0f)
    fun close(scope: CoroutineScope) = animateTo(scope, 0f, 0f)

    /** Kapatmayı beklemeden hemen kapatır (ör. Home tuşu). */
    fun snapClosed() { anim?.cancel(); progress = 0f }

    private fun animateTo(scope: CoroutineScope, target: Float, velocity: Float) {
        anim?.cancel()
        if (progress == target) return
        anim = scope.launch {
            animate(progress, target, velocity, spring(dampingRatio = 1f, stiffness = 420f)) { v, _ -> progress = v }
        }
    }
}

/**
 * Widget sayfası (iOS Bugün görünümü): bulanık duvar kâğıdı üstünde arama çubuğu, büyük tarih ve alt alta
 * widget'lar (küçükler yan yana iki tane, orta/büyükler tam genişlik). Altta "Düzenle".
 * Düzenleme modunda widget'lar sallanır, köşede "−" ile kaldırılır, sürüklenerek sıralanır.
 */
@Composable
fun TodayPage(
    state: TodayState,
    widgets: List<WidgetItem>,
    vm: LauncherViewModel,
    settings: LauncherSettings,
    edit: Boolean,
    jiggle: State<Float>,
    onOpenSearch: () -> Unit,
    onAddWidget: () -> Unit,
    onEdit: () -> Unit,
    onMenu: (WidgetItem, Rect) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val u = LocalPt.current
    val hm = LocalHomeMetrics.current ?: return
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val editNow by rememberUpdatedState(edit)
    // Widget'ların ekrandaki alanları (sürükleyerek sıralama ve uzun basmanın widget'a mı boşluğa mı olduğu için).
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    androidx.compose.runtime.LaunchedEffect(widgets) {
        val ids = widgets.map { it.id }.toSet()
        bounds.keys.retainAll(ids)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = (state.progress - 1f) * size.width }
            // Sola kaydırınca sayfa kapanır (dikey kaydırma içerikte kalır).
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { dx -> state.dragBy(dx) },
                onDragStopped = { v -> state.settle(scope, v) },
            )
            // Boş yere uzun basınca düzenleme modu (iOS gibi).
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { p ->
                    // Widget'ın üstündeyse onun menüsü açılır; düzenleme modu yalnızca boşlukta.
                    if (!editNow && bounds.values.none { it.contains(p) }) onEdit()
                })
            }
    ) {
        val columns = hm.columns
        val contentW = hm.widgetWidth(columns)
        val smallW = hm.widgetWidth(2)
        val gapX = contentW - smallW * 2f
        val gapY = (hm.pitchX - hm.icon).coerceAtLeast(12.dp)
        var dragId by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = statusTop + u * 14f, bottom = navBottom + u * 28f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Arama çubuğu (dokununca launcher araması açılır).
            Row(
                Modifier
                    .width(contentW)
                    .height(u * 43f)
                    .liquidGlass(CircleShape, settings.panelGlassStrength, 0.dp, frosted = true)
                    .clickable(remember { MutableInteractionSource() }, null) { onOpenSearch() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph(Color(0xCCFFFFFF), u * 17f)
                Spacer(Modifier.width(8.dp))
                Text("Ara", color = Color(0xB3FFFFFF), fontSize = 17.sp, fontFamily = AppFont, style = TextStyle(shadow = IosTextShadow))
            }
            // Bugünün tarihi (büyük): üstte gün adı, altında gün ve ay.
            val today = remember { LocalDate.now() }
            val tr = remember { Locale("tr", "TR") }
            Column(Modifier.width(contentW).padding(top = u * 22f, bottom = u * 18f)) {
                Text(
                    today.format(DateTimeFormatter.ofPattern("EEEE", tr)).replaceFirstChar { it.titlecase(tr) },
                    color = Color(0xD9FFFFFF), fontSize = (u.value * 17f).sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = AppFont, style = TextStyle(shadow = IosTextShadow),
                )
                Text(
                    today.format(DateTimeFormatter.ofPattern("d MMMM", tr)),
                    color = Color.White, fontSize = (u.value * 34f).sp, fontWeight = FontWeight.Bold,
                    fontFamily = AppFont, style = TextStyle(shadow = IosTextShadow),
                )
            }

            if (widgets.isEmpty()) {
                // Boş sayfa: kısa açıklama ve "Widget Ekle".
                Column(
                    Modifier
                        .width(contentW)
                        .liquidGlass(RoundedCornerShape(widgetCorner(contentW)), settings.panelGlassStrength, 0.dp, frosted = true)
                        .padding(u * 20f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Henüz widget yok", color = Color.White, fontSize = (u.value * 17f).sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                    )
                    Spacer(Modifier.height(u * 6f))
                    Text(
                        "Saat, takvim, hava durumu gibi widget'ları buraya ekleyebilirsin.",
                        color = Color(0xCCFFFFFF), fontSize = (u.value * 14f).sp, fontFamily = AppFont,
                    )
                    Spacer(Modifier.height(u * 14f))
                    TodayButton("Widget Ekle", settings) { onAddWidget() }
                }
            } else {
                // Satırlar: küçük (≤2 sütun) widget'lar ikişer yan yana, diğerleri tam genişlikte.
                val rows = remember(widgets) {
                    val out = ArrayList<List<WidgetItem>>()
                    var pending: WidgetItem? = null
                    for (w in widgets) {
                        if (w.spanX <= 2) {
                            if (pending == null) pending = w else { out += listOf(pending, w); pending = null }
                        } else {
                            pending?.let { out += listOf(it); pending = null }
                            out += listOf(w)
                        }
                    }
                    pending?.let { out += listOf(it) }
                    out
                }
                rows.forEachIndexed { ri, row ->
                    if (ri > 0) Spacer(Modifier.height(gapY))
                    Row(Modifier.width(contentW), horizontalArrangement = Arrangement.spacedBy(gapX)) {
                        row.forEach { w ->
                            val ww = if (w.spanX <= 2) smallW else contentW
                            val wh = hm.widgetHeight(w.spanY.coerceAtLeast(1))
                            val sign = remember(w.id) { if ((w.id.hashCode() and 1) == 0) 1f else -1f }
                            val dragging = dragId == w.id
                            Box(
                                Modifier
                                    .zIndex(if (dragging) 1f else 0f)
                                    .onGloballyPositioned { bounds[w.id] = it.boundsInRoot() }
                                    .graphicsLayer {
                                        if (dragging) { translationX = dragOffset.x; translationY = dragOffset.y; scaleX = 1.04f; scaleY = 1.04f }
                                        rotationZ = if (edit && !dragging) jiggle.value * sign * 0.5f else 0f
                                    }
                                    .then(
                                        if (edit) Modifier.pointerInput(w.id) {
                                            // Düzenleme modunda doğrudan sürükleyerek sıralama.
                                            detectDragGestures(
                                                onDragStart = { dragId = w.id; dragOffset = Offset.Zero },
                                                onDrag = { c, d -> c.consume(); dragOffset += d },
                                                onDragEnd = {
                                                    val from = bounds[w.id]
                                                    if (from != null) {
                                                        val drop = from.center + dragOffset
                                                        val target = widgets.minByOrNull { o ->
                                                            val b = bounds[o.id] ?: return@minByOrNull Float.MAX_VALUE
                                                            (b.center - drop).getDistance()
                                                        }
                                                        val to = widgets.indexOfFirst { it.id == target?.id }
                                                        if (to >= 0 && target?.id != w.id) vm.editLayout { it.moveTodayWidget(w.id, to) }
                                                    }
                                                    dragId = null; dragOffset = Offset.Zero
                                                },
                                                onDragCancel = { dragId = null; dragOffset = Offset.Zero },
                                            )
                                        } else Modifier.pointerInput(w.id) {
                                            // Basılı tutunca widget menüsü (boyut, düzenle, kaldır).
                                            detectTapGestures(onLongPress = { bounds[w.id]?.let { r -> onMenu(w, r) } })
                                        }
                                    ),
                            ) {
                                WidgetBody(w, vm, ww, wh, settings.glassStrength)
                                if (edit) {
                                    // iOS: sol üst köşede "−" ile kaldırma.
                                    Box(
                                        Modifier
                                            .offset(x = (-8).dp, y = (-8).dp)
                                            .size(24.dp)
                                            .background(Color(0xE6E5E5EA), CircleShape)
                                            .clickable { vm.removeWidget(w) },
                                        contentAlignment = Alignment.Center,
                                    ) { GlyphIcon(Glyph.Minus, Color(0xFF1C1C1E), 14.dp) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(u * 26f))
                if (!edit) TodayButton("Düzenle", settings) { onEdit() }
                else TodayButton("Widget Ekle", settings) { onAddWidget() }
            }
        }
    }
}

@Composable
private fun TodayButton(text: String, settings: LauncherSettings, onClick: () -> Unit) {
    val u = LocalPt.current
    Box(
        Modifier
            .height(u * 36f)
            .liquidGlass(CircleShape, settings.panelGlassStrength, 0.dp, frosted = true)
            .clickable(onClick = onClick)
            .padding(horizontal = u * 18f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = Color.White, fontSize = (u.value * 15f).sp, fontWeight = FontWeight.SemiBold,
            fontFamily = AppFont, style = TextStyle(shadow = IosTextShadow),
        )
    }
}

/** Yardımcı: dp genişliği için widget köşesi (Widgets.kt ile aynı kural). */
@Suppress("unused") private fun todayCorner(w: Dp): Dp = widgetCorner(w)
