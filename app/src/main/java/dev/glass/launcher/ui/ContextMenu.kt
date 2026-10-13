package dev.glass.launcher.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.composed
import android.content.pm.ShortcutInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.FolderItem
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import kotlin.math.roundToInt

/** Uzun basma menüsünün açıldığı hücre. */
data class MenuRequest(
    val itemId: String,
    val place: CellPlace,
    val iconRect: Rect,
    val cellRect: Rect,
    val iconDp: androidx.compose.ui.unit.Dp,
)

/** Menü satırı. */
private class MenuAction(val title: String, val glyph: Glyph?, val destructive: Boolean = false, val run: () -> Unit)

private val Red = Color(0xFFFF453A)

/**
 * iOS tarzı bağlam menüsü: simge yerinde hafif büyür, yanında cam bir menü açılır.
 * Uygulamalar için: uygulama kısayolları (yalnızca varsayılan ana ekransa), Ana Ekranı Düzenle,
 * Uygulama Bilgisi, Ana Ekrandan Kaldır / Ana Ekrana Ekle, Uygulamayı Sil.
 * Klasörler için: Yeniden Adlandır, Klasörü Dağıt, Ana Ekranı Düzenle.
 */
@Composable
fun ContextMenuOverlay(
    req: MenuRequest,
    vm: LauncherViewModel,
    app: AppInfo?,
    folder: FolderItem?,
    widget: WidgetItem?,
    hasCustomIcon: Boolean,
    onHome: Boolean,
    strength: Float,
    iconGlass: Boolean,
    byKey: Map<String, AppInfo>,
    onEditHome: () -> Unit,
    onRemoveFromHome: () -> Unit,
    onAddToHome: () -> Unit,
    onOpenFolder: () -> Unit,
    onDissolveFolder: () -> Unit,
    onChangeIcon: () -> Unit,
    onResetIcon: () -> Unit,
    onResizeWidget: (WidgetSize) -> Unit,
    onEditWidget: (() -> Unit)?,
    onRemoveWidget: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current

    val shortcuts by produceState<List<Pair<ShortcutInfo, ImageBitmap?>>>(emptyList(), app?.key) {
        if (app == null) return@produceState
        val px = with(density) { 24.dp.roundToPx() }
        value = vm.shortcuts(app).map { s ->
            s to vm.shortcutIcon(s, ctx.resources.displayMetrics.densityDpi)?.toBitmap(px, px)?.asImageBitmap()
        }
    }

    val actions = buildList {
        if (widget != null) {
            onEditWidget?.let { edit ->
                add(MenuAction(if (widget.kind == WidgetKind.Photo) "Fotoğrafı Değiştir" else "Widget'ı Düzenle", Glyph.Pencil) { edit() })
            }
            WidgetSize.entries.forEach { sz ->
                if (sz.spanX != widget.spanX || sz.spanY != widget.spanY) {
                    add(MenuAction("Boyut: ${sz.title}", Glyph.Widget) { onResizeWidget(sz) })
                }
            }
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            add(MenuAction("Widget'ı Kaldır", Glyph.Minus, destructive = true) { onRemoveWidget() })
        } else if (app != null) {
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            add(MenuAction("Simgeyi Değiştir", Glyph.Pencil) { onChangeIcon() })
            if (hasCustomIcon) add(MenuAction("Varsayılan Simge", Glyph.Close) { onResetIcon() })
            add(MenuAction("Uygulama Bilgisi", Glyph.Info) { vm.openAppInfo(app) })
            if (req.place == CellPlace.Library) {
                if (!onHome) add(MenuAction("Ana Ekrana Ekle", Glyph.Plus) { onAddToHome() })
            } else {
                add(MenuAction("Ana Ekrandan Kaldır", Glyph.Minus, destructive = true) { onRemoveFromHome() })
            }
            if (!app.isSystem) add(MenuAction("Uygulamayı Sil", Glyph.Trash, destructive = true) { vm.uninstall(app) })
        } else if (folder != null) {
            add(MenuAction("Yeniden Adlandır", Glyph.Pencil) { onOpenFolder() })
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            add(MenuAction("Klasörü Dağıt", Glyph.Grid, destructive = true) { onDissolveFolder() })
        }
    }

    // Açılış: simgeden yayla büyüyerek (iOS 26 gibi esnek); simge de hafifçe kalkar.
    val motion = LocalMotion.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motion.bouncy()) }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }) {
        // Kaldırılmış simge
        Box(
            Modifier
                .offset { IntOffset(req.iconRect.left.roundToInt(), req.iconRect.top.roundToInt()) }
                .graphicsLayer {
                    val k = 1f + 0.08f * appear.value
                    scaleX = k; scaleY = k
                }
        ) {
            when {
                widget != null -> Unit // widget yerinde kalır; yalnızca menü gösterilir
                app != null -> AppIconImage(app, vm, req.iconDp, iconGlass, strength)
                folder != null -> FolderIcon(folder, byKey, vm, req.iconDp, strength)
            }
        }

        // iOS 26 menüsü: tek parça açık buzlu cam, simge solda + yazı sağda, ayırıcı yok.
        val st = LocalLauncherSettings.current
        val u = LocalPt.current
        val corner = u * st.menuCorner
        val menuW = u * st.menuWidth
        val textColor = if (st.menuDark) Color.White else Color(0xF21C1C1E)
        val red = Color(0xFFFF3B30)
        val gapPx = with(density) { 10.dp.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }
        // Simgeye yakından uzağa: kısayollar → işlemler → kaldırma. Menü simgenin üstünde açılırsa ters çevrilir.
        val rows: List<@Composable () -> Unit> = buildList {
            shortcuts.forEach { (info, icon) ->
                add {
                    MenuRow(
                        title = info.shortLabel?.toString() ?: "",
                        color = textColor,
                        leading = { if (icon != null) Image(icon, null, Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))) },
                        onClick = { vm.startShortcut(info); onClose() },
                    )
                }
            }
            actions.forEach { a ->
                val c = if (a.destructive) red else textColor
                add {
                    MenuRow(
                        title = a.title,
                        color = c,
                        leading = { a.glyph?.let { GlyphIcon(it, c, 20.dp) } },
                        onClick = { a.run(); onClose() },
                    )
                }
            }
        }
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                // İki sürüm: alta açılan (normal sıra) ve üste açılan (ters sıra). Yerleşimde biri seçilir.
                listOf(false, true).forEach { reversed ->
                    Box(Modifier.width(menuW)) {
                        BlurBackdrop(corner, Modifier.matchParentSize())
                        Column(
                            Modifier
                                .width(menuW)
                                .menuGlass(RoundedCornerShape(corner), st.menuOpacity, st.menuDark)
                                .pointerInput(Unit) { detectTapGestures { } }
                                .padding(vertical = u * 6f)
                        ) {
                            (if (reversed) rows.asReversed() else rows).forEach { it() }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val down = measurables[0].measure(loose)
            val below = req.cellRect.bottom + gapPx + down.height < constraints.maxHeight - marginPx
            val p = if (below) down else measurables[1].measure(loose)
            val maxX = (constraints.maxWidth - p.width - marginPx).coerceAtLeast(marginPx)
            val x = req.iconRect.left.coerceIn(marginPx, maxX)
            val y = if (below) req.cellRect.bottom + gapPx else req.iconRect.top - gapPx - p.height
            val py = y.coerceAtLeast(marginPx)
            // Büyüme noktası: simgenin menüye en yakın kenarı (menü altta ise üst kenar, üstte ise alt kenar).
            val ox = ((req.iconRect.center.x - x) / p.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            val oy = if (below) 0f else 1f
            layout(constraints.maxWidth, constraints.maxHeight) {
                p.placeWithLayer(x.roundToInt(), py.roundToInt()) {
                    val a = appear.value
                    transformOrigin = TransformOrigin(ox, oy)
                    scaleX = 0.25f + 0.75f * a
                    scaleY = 0.25f + 0.75f * a
                    alpha = a.coerceIn(0f, 1f)
                }
            }
        }
    }
}

/** Menü camı: açık (süt beyazı) ya da koyu buzlu cam; kenar çizgisi ve parlaklık genel cam ayarlarını izler. */
private fun Modifier.menuGlass(shape: RoundedCornerShape, opacity: Float, dark: Boolean): Modifier = composed {
    val st = LocalLauncherSettings.current
    val fill = if (dark) {
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = opacity * 0.75f), Color.Black.copy(alpha = opacity * 0.85f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = opacity), Color.White.copy(alpha = (opacity * 0.85f))))
    }
    this
        .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
        .clip(shape)
        .background(fill)
        .glassBrightness(st.glassBrightness)
        .then(
            if (st.glassRimWidth > 0f && st.glassRimGlow > 0f) Modifier.border(
                st.glassRimWidth.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = (0.7f * st.glassRimGlow).coerceAtMost(1f)),
                        Color.White.copy(alpha = (0.3f * st.glassRimGlow).coerceAtMost(1f)),
                    )
                ),
                shape,
            ) else Modifier
        )
}

@Composable
private fun MenuRow(
    title: String,
    color: Color,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val st = LocalLauncherSettings.current
    val u = LocalPt.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(u * st.menuRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = u * 18f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(u * 26f), contentAlignment = Alignment.CenterStart) { leading?.invoke() }
        Spacer(Modifier.width(u * 10f))
        Text(
            title, color = color, fontSize = (st.menuTextSize * u.value).sp, fontWeight = FontWeight.Normal,
            fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}
