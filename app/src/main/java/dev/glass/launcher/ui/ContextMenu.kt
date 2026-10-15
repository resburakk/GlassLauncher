package dev.glass.launcher.ui

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.platform.LocalWindowInfo
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
private class MenuAction(
    val title: String,
    val glyph: Glyph?,
    val destructive: Boolean = false,
    /** true: dokununca menü kapanmaz (ör. onay penceresi açılır). */
    val keepOpen: Boolean = false,
    val run: () -> Unit,
)

/** Kısayol simgesi türü: 0 = boş/bembeyaz (uygulama simgesi kullanılır), 1 = tek renkli çizim (yazı rengine boyanır), 2 = renkli. */
private fun classifyIcon(b: android.graphics.Bitmap): Int {
    var total = 0; var opaque = 0; var white = 0; var satSum = 0f
    var y = 0
    while (y < b.height) {
        var x = 0
        while (x < b.width) {
            val c = b.getPixel(x, y)
            total++
            if (android.graphics.Color.alpha(c) >= 40) {
                opaque++
                val r = android.graphics.Color.red(c) / 255f
                val g = android.graphics.Color.green(c) / 255f
                val bl = android.graphics.Color.blue(c) / 255f
                val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
                satSum += if (mx <= 0f) 0f else (mx - mn) / mx
                if (mn > 0.9f) white++
            }
            x += 2
        }
        y += 2
    }
    if (total == 0 || opaque < total * 0.03f) return 0
    if (white > opaque * 0.9f) return 0
    val avgSat = satSum / opaque
    return if (avgSat < 0.12f && opaque < total * 0.7f) 1 else 2
}

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
    /** Dıştaki açılış/kapanış geçişinin görünürlüğü (0..1); arka plan bulanıklığı buna uyar. */
    visibility: () -> Float = { 1f },
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
    onResizeWidget: (Span) -> Unit,
    onEditWidget: (() -> Unit)?,
    onRemoveWidget: () -> Unit,
    onClose: () -> Unit,
    /** Klasörün içinden açıldıysa: uygulamayı klasörden çıkarır. */
    onRemoveFromFolder: () -> Unit = {},
    /** Menü açık bir panelin (klasör) üstünde: cam biraz daha dolgun olur, panel içi dikkat dağıtmaz. */
    overPanel: Boolean = false,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current

    val shortcuts by produceState<List<Triple<ShortcutInfo, ImageBitmap?, Int>>>(emptyList(), app?.key) {
        if (app == null) return@produceState
        val px = with(density) { 24.dp.roundToPx() }
        value = vm.shortcuts(app).map { s ->
            val bmp = vm.shortcutIcon(s, ctx.resources.displayMetrics.densityDpi)?.toBitmap(px, px)
            val kind = bmp?.let { runCatching { classifyIcon(it) }.getOrDefault(2) } ?: 0
            Triple(s, if (kind == 0) null else bmp?.asImageBitmap(), kind)
        }
    }

    // Widget boyutları: sistem widget'larında widget'ın izin verdikleri, kendi widget'larımızda iOS boyutları.
    val hm = LocalHomeMetrics.current
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    val widgetSizes: List<Span> = remember(widget?.id, hm) {
        val w = widget ?: return@remember emptyList()
        val info = if (w.kind == WidgetKind.System) vm.widgetHost.info(w.appWidgetId) else null
        if (info != null && hm != null) WidgetSizing.sizeOptions(info, hm, dens)
        else WidgetSize.entries.map { Span(it.spanX, it.spanY) }
    }

    // iOS gibi: "Uygulamayı Kaldır" önce sorar (Sil / Ana Ekrandan Kaldır / Vazgeç).
    var confirmRemove by remember { mutableStateOf(false) }

    val actions = buildList {
        if (widget != null) {
            onEditWidget?.let { edit ->
                add(MenuAction(if (widget.kind == WidgetKind.Photo) "Fotoğrafı Değiştir" else "Widget'ı Düzenle", Glyph.Pencil) { edit() })
            }
            widgetSizes.forEach { sz ->
                if (sz.x != widget.spanX || sz.y != widget.spanY) {
                    val label = if (sz.title.contains('×')) sz.title else "${sz.title} (${sz.x}×${sz.y})"
                    add(MenuAction("Boyut: $label", Glyph.Widget) { onResizeWidget(sz) })
                }
            }
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            add(MenuAction("Widget'ı Kaldır", Glyph.Minus, destructive = true) { onRemoveWidget() })
        } else if (app != null) {
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            add(MenuAction("Simgeyi Değiştir", Glyph.Pencil) { onChangeIcon() })
            if (hasCustomIcon) add(MenuAction("Varsayılan Simge", Glyph.Close) { onResetIcon() })
            add(MenuAction("Uygulama Bilgisi", Glyph.Info) { vm.openAppInfo(app) })
            when (req.place) {
                CellPlace.Library -> {
                    if (!onHome) add(MenuAction("Ana Ekrana Ekle", Glyph.Plus) { onAddToHome() })
                    if (!app.isSystem) add(MenuAction("Uygulamayı Sil", Glyph.Trash, destructive = true) { vm.uninstall(app) })
                }
                else -> {
                    if (req.place == CellPlace.Folder) add(MenuAction("Klasörden Çıkar", Glyph.Minus) { onRemoveFromFolder() })
                    add(MenuAction("Uygulamayı Kaldır", Glyph.Minus, destructive = true, keepOpen = true) { confirmRemove = true })
                }
            }
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

    val st = LocalLauncherSettings.current
    val u = LocalPt.current
    val corner = u * st.menuCorner
    val menuW = u * st.menuWidth
    val textColor = if (st.menuDark) Color.White else Color(0xF21C1C1E)
    val red = Color(0xFFFF3B30)
    val glassOpacity = (st.menuOpacity + if (overPanel) 0.2f else 0f).coerceAtMost(0.95f)

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

        if (confirmRemove && app != null) {
            RemoveConfirm(
                appName = app.label,
                canDelete = !app.isSystem,
                removeTitle = "Ana Ekrandan Kaldır",
                textColor = textColor,
                red = red,
                corner = corner,
                opacity = glassOpacity,
                dark = st.menuDark,
                onDelete = { vm.uninstall(app); onClose() },
                onRemove = { onRemoveFromHome(); onClose() },
                onCancel = { onClose() },
            )
            return@Box
        }

        // Simgeye yakından uzağa: kısayollar → işlemler → kaldırma. Menü simgenin üstünde açılırsa ters çevrilir.
        val gapPx = with(density) { 10.dp.toPx() }
        val sideMarginPx = with(density) { 12.dp.toPx() }
        val screenH = LocalWindowInfo.current.containerSize.height.toFloat()
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        // Güvenli alan: durum çubuğu ve gezinme çubuğunun içine taşmaz.
        val topLimit = statusTop + gapPx
        val bottomLimit = (if (screenH > 0f) screenH else 100000f) - navBottom - gapPx
        val n = shortcuts.size + actions.size
        val padV = with(density) { (u * 12f).toPx() }
        val rowPx = with(density) { (u * st.menuRowHeight).toPx() }
        val minRowPx = with(density) { (u * 36f).toPx() }
        val natural = padV + rowPx * n
        val spaceBelow = bottomLimit - (req.cellRect.bottom + gapPx)
        val spaceAbove = (req.iconRect.top - gapPx) - topLimit
        // Sığan tarafı seç; ikisine de sığmıyorsa geniş tarafı seç. Menü asla simgenin üstüne binmez.
        val below = natural <= spaceBelow || (natural > spaceAbove && spaceBelow >= spaceAbove)
        val avail = (if (below) spaceBelow else spaceAbove).coerceAtLeast(minRowPx * 2f)
        // Sığmıyorsa önce satırlar sıkışır (en az 36 pt), yine sığmazsa menü kendi içinde kayar.
        val effRowPx = if (natural <= avail || n == 0) rowPx else ((avail - padV) / n).coerceIn(minRowPx, rowPx)
        val rowH = with(density) { effRowPx.toDp() }
        val maxH = with(density) { avail.toDp() }
        val reversed = !below

        val rows: List<@Composable () -> Unit> = buildList {
            shortcuts.forEach { (info, icon, kind) ->
                add {
                    MenuRow(
                        title = info.shortLabel?.toString() ?: "",
                        color = textColor,
                        rowH = rowH,
                        leading = {
                            when {
                                icon == null -> AppIconImage(app!!, vm, 22.dp, false, 0f, badge = false)
                                kind == 1 -> Image(icon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(textColor))
                                else -> Image(icon, null, Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)))
                            }
                        },
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
                        rowH = rowH,
                        leading = { a.glyph?.let { GlyphIcon(it, c, 20.dp) } },
                        onClick = { a.run(); if (!a.keepOpen) onClose() },
                    )
                }
            }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(reversed, scroll.maxValue) { if (reversed) scroll.scrollTo(scroll.maxValue) }

        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Box(Modifier.width(menuW).heightIn(max = maxH)) {
                    BlurBackdrop(corner, Modifier.matchParentSize(), shape = appShape(corner), alpha = { appear.value.coerceIn(0f, 1f) * visibility() })
                    Column(
                        Modifier
                            .width(menuW)
                            .heightIn(max = maxH)
                            .menuGlass(appShape(corner), glassOpacity, st.menuDark)
                            .pointerInput(Unit) { detectTapGestures { } }
                            .verticalScroll(scroll)
                            .padding(vertical = u * 6f)
                    ) {
                        (if (reversed) rows.asReversed() else rows).forEach { it() }
                    }
                }
            },
        ) { measurables, constraints ->
            val p = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val maxX = (constraints.maxWidth - p.width - sideMarginPx).coerceAtLeast(sideMarginPx)
            val x = req.iconRect.left.coerceIn(sideMarginPx, maxX)
            val y = if (below) req.cellRect.bottom + gapPx else req.iconRect.top - gapPx - p.height
            val py = y.coerceIn(topLimit, (bottomLimit - p.height).coerceAtLeast(topLimit))
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

/** iOS tarzı onay: "Uygulamayı Sil" / "Ana Ekrandan Kaldır" / "Vazgeç". */
@Composable
private fun RemoveConfirm(
    appName: String,
    canDelete: Boolean,
    removeTitle: String,
    textColor: Color,
    red: Color,
    corner: androidx.compose.ui.unit.Dp,
    opacity: Float,
    dark: Boolean,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    val u = LocalPt.current
    val blue = if (dark) Color(0xFF64B5FF) else Color(0xFF0A84FF)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Box(Modifier.width(u * 280f)) {
            BlurBackdrop(corner, Modifier.matchParentSize(), shape = appShape(corner))
            Column(
                Modifier
                    .width(u * 280f)
                    .menuGlass(appShape(corner), (opacity + 0.15f).coerceAtMost(0.95f), dark)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(top = u * 18f, bottom = u * 6f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "\u201C$appName\u201D kaldırılsın mı?",
                    color = textColor, fontSize = (17f * u.value).sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = AppFont, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = u * 18f),
                )
                Spacer(Modifier.height(u * 6f))
                Text(
                    if (canDelete) "Uygulamayı silmek tüm verilerini de siler. Ana ekrandan kaldırırsanız uygulama telefonda kalır."
                    else "Bu bir sistem uygulaması; yalnızca ana ekrandan kaldırılabilir.",
                    color = textColor.copy(alpha = 0.75f), fontSize = (13f * u.value).sp,
                    fontFamily = AppFont, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = u * 18f),
                )
                Spacer(Modifier.height(u * 12f))
                if (canDelete) ConfirmButton("Uygulamayı Sil", red, FontWeight.Normal, onDelete)
                ConfirmButton(removeTitle, blue, FontWeight.Normal, onRemove)
                ConfirmButton("Vazgeç", blue, FontWeight.SemiBold, onCancel)
            }
        }
    }
}

@Composable
private fun ConfirmButton(title: String, color: Color, weight: FontWeight, onClick: () -> Unit) {
    val u = LocalPt.current
    Box(
        Modifier.fillMaxWidth().height(u * 46f).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = color, fontSize = (17f * u.value).sp, fontWeight = weight, fontFamily = AppFont)
    }
}

/** Menü camı: açık (süt beyazı) ya da koyu buzlu cam; kenar çizgisi ve parlaklık genel cam ayarlarını izler. */
internal fun Modifier.menuGlass(shape: androidx.compose.ui.graphics.Shape, opacity0: Float, dark: Boolean): Modifier = composed {
    val st = LocalLauncherSettings.current
    // Genel şeffaflık kaydırıcısı menüye de uygulanır (okunabilirlik için üst sınır var).
    val opacity = (opacity0 * glassTintFactor(st.glassTint)).coerceIn(0.05f, 0.97f)
    val fill = if (dark) {
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = opacity * 0.75f), Color.Black.copy(alpha = opacity * 0.85f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = opacity), Color.White.copy(alpha = (opacity * 0.85f))))
    }
    this
        .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
        .smoothClip(shape)
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
internal fun MenuRow(
    title: String,
    color: Color,
    rowH: androidx.compose.ui.unit.Dp,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val st = LocalLauncherSettings.current
    val u = LocalPt.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowH)
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
