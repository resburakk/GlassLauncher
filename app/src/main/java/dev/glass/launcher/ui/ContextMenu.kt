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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
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
    /** "Diğer" alt menüsündeki satır (biraz içeriden, soluk). */
    val sub: Boolean = false,
    /** Satırın sağındaki işaret (ör. alt menü oku). */
    val trailing: String? = null,
    /** Widget boyutları: tek satırda boyut düğmeleri olarak çizilir. */
    val sizes: List<Span> = emptyList(),
    val run: () -> Unit,
)

/**
 * Kısayol simgesinden menü sembolü (yalnızca şekil; renk sonra yazı rengiyle verilir). Uyarlanabilir simgede tek renk
 * katmanı (Android 13+) ya da ön katman kullanılır. Simgede en çok bulunan renk geniş bir alanı kaplıyorsa zemindir
 * (beyaz kare/daire): her pikselin bu zeminden farkı şeklin saydamlığı olur. Bulunan şekil kırpılıp hep aynı boyuta
 * getirilir. (Önceden simgeler zeminleriyle çiziliyordu; menüde etraflarında beyaz kutular görünüyordu.)
 */
private fun menuGlyph(d: android.graphics.drawable.Drawable, px: Int): android.graphics.Bitmap? {
    val big = px * 3
    val layer: android.graphics.drawable.Drawable = if (d is android.graphics.drawable.AdaptiveIconDrawable) {
        (if (android.os.Build.VERSION.SDK_INT >= 33) d.monochrome else null) ?: d.foreground ?: d
    } else d
    val src = android.graphics.Bitmap.createBitmap(big, big, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(src)
    layer.setBounds(0, 0, big, big)
    layer.draw(c)
    val mask = shapeMask(src) ?: return null
    return fitGlyph(mask, px)
}

private fun shapeMask(b: android.graphics.Bitmap): IntArray? {
    val w = b.width; val h = b.height
    val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
    // En sık renk (4 bitlik kovalarla); yalnızca tam opak pikseller.
    val counts = HashMap<Int, Int>()
    var opaque = 0
    for (c in px) {
        if (android.graphics.Color.alpha(c) < 200) continue
        opaque++
        val k = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
        counts[k] = (counts[k] ?: 0) + 1
    }
    if (opaque < w * h * 0.02f) {
        // Neredeyse tamamen yarı saydam: doğrudan saydamlık.
        return px.map { (android.graphics.Color.alpha(it) shl 24) or 0xFFFFFF }.toIntArray()
    }
    val top = counts.maxByOrNull { it.value }!!
    val out = IntArray(px.size)
    if (top.value > opaque * 0.35f) {
        var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
        for (c in px) {
            if (android.graphics.Color.alpha(c) < 200) continue
            val k = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
            if (k != top.key) continue
            sr += android.graphics.Color.red(c); sg += android.graphics.Color.green(c); sb += android.graphics.Color.blue(c); n++
        }
        sr /= n; sg /= n; sb /= n
        val dist = FloatArray(px.size); var maxD = 1f
        for (i in px.indices) {
            val c = px[i]; val a = android.graphics.Color.alpha(c)
            if (a < 30) continue
            val dr = android.graphics.Color.red(c) - sr; val dg = android.graphics.Color.green(c) - sg; val db = android.graphics.Color.blue(c) - sb
            val dd = kotlin.math.sqrt(dr * dr + dg * dg + db * db) * (a / 255f)
            dist[i] = dd; if (dd > maxD) maxD = dd
        }
        if (maxD < 40f) return null
        for (i in px.indices) {
            val al = ((dist[i] / maxD - 0.12f) / 0.55f).coerceIn(0f, 1f)
            out[i] = ((al * 255f).toInt() shl 24) or 0xFFFFFF
        }
    } else {
        for (i in px.indices) out[i] = (android.graphics.Color.alpha(px[i]) shl 24) or 0xFFFFFF
    }
    return out
}

/** Şeklin dolu kısmını kırpar, [px] kutusuna %88 doldurarak ortalar (tüm semboller aynı boyutta görünür). */
private fun fitGlyph(mask: IntArray, px: Int): android.graphics.Bitmap? {
    val big = px * 3
    var l = big; var t = big; var r = -1; var bt = -1
    for (y in 0 until big) for (x in 0 until big) {
        if ((mask[y * big + x] ushr 24) > 50) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
        }
    }
    if (r < 0 || (r - l) < 4 || (bt - t) < 4) return null
    val full = android.graphics.Bitmap.createBitmap(mask, big, big, android.graphics.Bitmap.Config.ARGB_8888)
    val cw = r - l + 1; val ch = bt - t + 1
    val scale = px * 0.88f / maxOf(cw, ch)
    val dw = cw * scale; val dh = ch * scale
    val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(
        full, android.graphics.Rect(l, t, r + 1, bt + 1),
        android.graphics.RectF((px - dw) / 2f, (px - dh) / 2f, (px + dw) / 2f, (px + dh) / 2f),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG),
    )
    return out
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
    /** Widget yığınına başka bir widget eklemek için seçiciyi açar. */
    onAddToStack: (() -> Unit)? = null,
    onClose: () -> Unit,
    /** Klasörün içinden açıldıysa: uygulamayı klasörden çıkarır. */
    onRemoveFromFolder: () -> Unit = {},
    /** Menü açık bir panelin (klasör) üstünde: cam biraz daha dolgun olur, panel içi dikkat dağıtmaz. */
    overPanel: Boolean = false,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current

    val shortcuts by produceState<List<Triple<ShortcutInfo, ImageBitmap?, Int>>>(emptyList(), app?.key) {
        if (app == null || app.isShortcut) return@produceState
        val px = with(density) { 24.dp.roundToPx() }
        value = vm.shortcuts(app).map { s ->
            // iOS gibi tek renk sembol: simgenin zemini (beyaz kare/daire) atılır, yalnızca şekli yazı rengine boyanır.
            val d = vm.shortcutIcon(s, ctx.resources.displayMetrics.densityDpi)
            val glyph = d?.let { runCatching { menuGlyph(it, px) }.getOrNull() }
            Triple(s, glyph?.asImageBitmap(), if (glyph == null) 0 else 1)
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

    val menuSettings = LocalLauncherSettings.current
    // iOS 26 gibi sade menü: sık kullanılanlar görünür, az kullanılanlar "Diğer" alt menüsünde (menünün içinde açılır).
    var moreOpen by remember { mutableStateOf(false) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    fun MutableList<MenuAction>.addMore(more: List<MenuAction>) {
        if (more.isEmpty()) return
        add(MenuAction("Diğer", Glyph.Grid, keepOpen = true, trailing = if (moreOpen) "⌃" else "›") { moreOpen = !moreOpen })
        if (moreOpen) more.forEach { m -> add(MenuAction(m.title, m.glyph, m.destructive, m.keepOpen, sub = true, run = m.run)) }
    }
    val actions = buildList {
        if (widget != null) {
            onEditWidget?.let { edit ->
                add(MenuAction(if (widget.kind == WidgetKind.Photo) "Fotoğrafı Değiştir" else "Widget'ı Düzenle", Glyph.Pencil) { edit() })
            }
            // Boyutlar tek satırda (iOS gibi küçük düğmeler); önceden her boyut ayrı bir satırdı.
            if (widgetSizes.size > 1) add(MenuAction("", null, sizes = widgetSizes) {})
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            onAddToStack?.let { add(MenuAction("Yığına Widget Ekle", Glyph.Plus) { it() }) }
            if (widget.stack.isNotEmpty()) add(MenuAction("Yığını Dağıt", Glyph.Grid) { vm.editLayout { l -> l.unstack(widget.id) } })
            add(MenuAction(if (widget.stack.isNotEmpty()) "Yığını Kaldır" else "Widget'ı Kaldır", Glyph.Minus, destructive = true) { onRemoveWidget() })
        } else if (app != null && app.isShortcut) {
            // Sabitlenmiş kısayol (ör. Chrome "Ana ekrana ekle"): silmek yalnızca kısayolu kaldırır, uygulama kalır.
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            if (req.place == CellPlace.Folder) add(MenuAction("Klasörden Çıkar", Glyph.Minus) { onRemoveFromFolder() })
            addMore(buildList {
                add(MenuAction("Simgeyi Değiştir", Glyph.Pencil) { onChangeIcon() })
                if (hasCustomIcon) add(MenuAction("Varsayılan Simge", Glyph.Close) { onResetIcon() })
            })
            add(MenuAction("Kısayolu Sil", Glyph.Trash, destructive = true) { vm.removeShortcut(app) })
        } else if (app != null) {
            val locked = app.appKey in menuSettings.lockedApps
            val lock = MenuAction(if (locked) "Kilidi Kaldır" else "Uygulamayı Kilitle", Glyph.Lock) {
                if (locked) AppLock.authenticate("Kilidi kaldır", app.label) { vm.setLocked(app, false) } else vm.setLocked(app, true)
            }
            val info = MenuAction("Uygulama Bilgisi", Glyph.Info) { vm.openAppInfo(app) }
            val more = buildList {
                add(MenuAction("Simgeyi Değiştir", Glyph.Pencil) { onChangeIcon() })
                if (hasCustomIcon) add(MenuAction("Varsayılan Simge", Glyph.Close) { onResetIcon() })
                // Koyu modda simge görünümü değişiyorsa: bu uygulamayı koyu modda orijinal bırak / yeniden koyulaştır.
                if (menuSettings.iconStyleDark != dev.glass.launcher.data.IconStyle.Original) {
                    val keep = app.key in menuSettings.darkOriginalApps
                    add(MenuAction(if (keep) "Koyu Modda Koyulaştır" else "Koyu Modda Orijinal Kalsın", Glyph.Pencil) { vm.toggleDarkOriginal(app) })
                }
                add(MenuAction("Uygulamayı Gizle", Glyph.Hide) { vm.setHidden(app, true) })
            }
            when (req.place) {
                CellPlace.Library -> {
                    if (!onHome) add(MenuAction("Ana Ekrana Ekle", Glyph.Plus) { onAddToHome() })
                    add(lock); add(info)
                    addMore(more)
                    if (!app.isSystem) add(MenuAction("Uygulamayı Sil", Glyph.Trash, destructive = true) { vm.uninstall(app) })
                }
                else -> {
                    add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
                    if (req.place == CellPlace.Folder) add(MenuAction("Klasörden Çıkar", Glyph.Minus) { onRemoveFromFolder() })
                    add(lock); add(info)
                    addMore(more)
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
    // Ölçüm: menü açılışındaki gerçek kare aralıkları (Ayarlar > Performans).
    MeasureFrames(appear.isRunning, "Son açılış (menü)")
    // Menünün son konumu (yerleşimde hesaplanır): arkasındaki bulanık alan açılışta büyümez, baştan bu boyutta durur.
    val menuRect = remember { arrayOfNulls<Rect>(1) }

    val st = LocalLauncherSettings.current
    val u = LocalPt.current
    val corner = u * st.menuCorner
    val menuW = u * st.menuWidth
    val dark = menuDarkNow()
    val textColor = menuInk(dark)
    val red = Color(0xFFFF3B30)
    val glassOpacity = (st.menuOpacity + if (overPanel) 0.2f else 0f).coerceAtMost(0.95f)

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }) {
        // Kaldırılmış simge
        Box(
            Modifier
                .offset { IntOffset(req.iconRect.left.roundToInt(), req.iconRect.top.roundToInt()) }
                .graphicsLayer {
                    val k = 1f + 0.08f * appear.value * visibility()
                    scaleX = k; scaleY = k
                }
        ) {
            when {
                // iOS: onay penceresi açılınca kalkan simge kaybolur (yarı saydam pencerenin üstündeymiş gibi görünüyordu).
                confirmRemove -> Unit
                widget != null -> Unit // widget yerinde kalır; yalnızca menü gösterilir
                app != null -> AppIconImage(app, vm, req.iconDp, iconGlass, strength)
                folder != null -> FolderIcon(folder, byKey, vm, req.iconDp, strength)
            }
        }

        if (confirmRemove && app != null) {
            // Dış katman artık solmadığı için onay penceresi kendi içinde söner.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = visibility() }) { RemoveConfirm(
                appName = app.label,
                canDelete = !app.isSystem,
                removeTitle = "Ana Ekrandan Kaldır",
                textColor = textColor,
                red = red,
                corner = corner,
                opacity = glassOpacity,
                dark = dark,
                onDelete = { vm.uninstall(app); onClose() },
                onRemove = { onRemoveFromHome(); onClose() },
                onCancel = { onClose() },
            ) }
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
        // Son bildirimler (Pixel / One UI gibi): menünün simgeye en yakın ucunda, en çok 2 tane.
        val previewMap by dev.glass.launcher.service.Badges.previews.collectAsState()
        val notifs = if (app != null && !app.isShortcut && st.showBadges) previewMap[app.packageName].orEmpty().take(2) else emptyList()
        val n = (if (shortcuts.isEmpty()) 0 else 1 + if (shortcutsOpen) shortcuts.size else 0) + actions.size
        val padV = with(density) { (u * 12f).toPx() }
        val rowPx0 = with(density) { (u * st.menuRowHeight).toPx() }
        // Bildirim satırı iki satır yazı taşır (~1,5 satır yüksekliği): toplam yükseklik hesabına eklenir.
        val notifPx = rowPx0 * 1.5f * notifs.size
        val rowPx = rowPx0
        val minRowPx = with(density) { (u * 36f).toPx() }
        val natural = padV + rowPx * n + notifPx
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

        // Her satır (üst boşluk, alt boşluk) alır: ilk/son satırın basılı vurgusu menünün kenarına kadar uzansın.
        val rows: List<@Composable (androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit> = buildList {
            notifs.forEachIndexed { i, nt ->
                add { pt, pb ->
                    NotifRow(nt, textColor, rowH * 1.5f, pt, pb, divider = i == notifs.lastIndex, dividerOnTop = reversed) {
                        if (!dev.glass.launcher.service.Badges.open(nt)) vm.launch(app!!)
                        onClose()
                    }
                }
            }
            // Uygulamanın kısayolları tek satırda toplanır ("Diğer" gibi): dokununca menünün içinde açılır.
            if (shortcuts.isNotEmpty()) {
                add { pt, pb ->
                    MenuRow(
                        title = "Kısayollar",
                        color = textColor,
                        rowH = rowH, padTop = pt, padBottom = pb,
                        leading = { GlyphIcon(Glyph.Grid, textColor, 20.dp) },
                        trailing = if (shortcutsOpen) "⌃" else "›",
                        onClick = { shortcutsOpen = !shortcutsOpen },
                    )
                }
                if (shortcutsOpen) shortcuts.forEach { (info, icon, _) ->
                    add { pt, pb ->
                        MenuRow(
                            title = info.shortLabel?.toString() ?: "",
                            color = textColor.copy(alpha = 0.85f),
                            rowH = rowH, padTop = pt, padBottom = pb,
                            leading = {
                                if (icon == null) GlyphIcon(Glyph.Grid, textColor.copy(alpha = 0.85f), 18.dp)
                                else Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(textColor.copy(alpha = 0.85f)))
                            },
                            indent = u * 14f,
                            onClick = { vm.startShortcut(info); onClose() },
                        )
                    }
                }
            }
            actions.forEach { a ->
                val c = when { a.destructive -> red; a.sub -> textColor.copy(alpha = 0.85f); else -> textColor }
                add { pt, pb ->
                    if (a.sizes.isNotEmpty() && widget != null) {
                        SizeRow(a.sizes, widget.spanX, widget.spanY, textColor, rowH, pt, pb) { sz -> onResizeWidget(sz); onClose() }
                    } else MenuRow(
                        title = a.title,
                        color = c,
                        rowH = rowH, padTop = pt, padBottom = pb,
                        leading = { a.glyph?.let { GlyphIcon(it, c, if (a.sub) 18.dp else 20.dp) } },
                        indent = if (a.sub) u * 14f else 0.dp,
                        trailing = a.trailing,
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
                    BlurBackdrop(corner, Modifier.matchParentSize(), shape = appShape(corner), alpha = { appear.value.coerceIn(0f, 1f) * visibility() }, menuBlur = true)
                    Column(
                        Modifier
                            .width(menuW)
                            .heightIn(max = maxH)
                            .menuGlass(appShape(corner), glassOpacity, dark)
                            .pointerInput(Unit) { detectTapGestures { } }
                            .verticalScroll(scroll)
                    ) {
                        val ordered = if (reversed) rows.asReversed() else rows
                        ordered.forEachIndexed { i, r ->
                            r(if (i == 0) u * 6f else 0.dp, if (i == ordered.lastIndex) u * 6f else 0.dp)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val p = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val maxX = (constraints.maxWidth - p.width - sideMarginPx).coerceAtLeast(sideMarginPx)
            val x = req.iconRect.left.coerceIn(sideMarginPx, maxX)
            val y = if (below) req.cellRect.bottom + gapPx else req.iconRect.top - gapPx - p.height
            val py = y.coerceIn(topLimit, (bottomLimit - p.height).coerceAtLeast(topLimit))
            menuRect[0] = Rect(x.toFloat(), py, x + p.width.toFloat(), py + p.height)
            // Büyüme noktası: simgenin menüye en yakın kenarı (menü altta ise üst kenar, üstte ise alt kenar).
            val ox = ((req.iconRect.center.x - x) / p.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            val oy = if (below) 0f else 1f
            layout(constraints.maxWidth, constraints.maxHeight) {
                p.placeWithLayer(x.roundToInt(), py.roundToInt()) {
                    val a = appear.value
                    val v = visibility()
                    transformOrigin = TransformOrigin(ox, oy)
                    // Kapanışta simgeye doğru küçülerek söner (iOS); açılışta simgeden büyür.
                    val s = (0.25f + 0.75f * a) * (0.6f + 0.4f * v)
                    scaleX = s
                    scaleY = s
                    alpha = a.coerceIn(0f, 1f) * v
                }
            }
        }
    }
}

/** Menüler koyu mu: varsayılan olarak telefonun koyu/açık modu (Ayarlar > Menü'den elle de seçilebilir). */
@Composable
internal fun menuDarkNow(): Boolean {
    val st = LocalLauncherSettings.current
    return if (st.menuAuto) androidx.compose.foundation.isSystemInDarkTheme() else st.menuDark
}

/** Menü yazı rengi (koyu menüde beyaz, açıkta iOS koyu grisi). */
internal fun menuInk(dark: Boolean): Color = if (dark) Color.White else Color(0xF21C1C1E)

/**
 * Ortak cam onay penceresi (iOS uyarısı gibi): başlık, açıklama ve alt alta düğmeler. Bütün onaylar (uygulama
 * kaldırma, sayfa silme) aynı görünümde; telefonun koyu/açık moduna uyar.
 */
@Composable
internal fun GlassAlert(
    title: String,
    message: String,
    /** Düğmeler: (yazı, kırmızı mı, kalın mı, iş). */
    buttons: List<GlassAlertButton>,
    onDismiss: () -> Unit,
) {
    val st = LocalLauncherSettings.current
    val u = LocalPt.current
    val dark = menuDarkNow()
    val ink = menuInk(dark)
    val corner = u * st.menuCorner
    val blue = if (dark) Color(0xFF64B5FF) else Color(0xFF0A84FF)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)).pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(u * 280f)) {
            BlurBackdrop(corner, Modifier.matchParentSize(), shape = appShape(corner), menuBlur = true)
            Column(
                Modifier
                    .width(u * 280f)
                    .menuGlass(appShape(corner), (st.menuOpacity + 0.15f).coerceAtMost(0.95f), dark)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(top = u * 18f, bottom = u * 6f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, color = ink, fontSize = (17f * u.value).sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = AppFont, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = u * 18f))
                Spacer(Modifier.height(u * 6f))
                Text(message, color = ink.copy(alpha = 0.75f), fontSize = (13f * u.value).sp,
                    fontFamily = AppFont, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = u * 18f))
                Spacer(Modifier.height(u * 12f))
                val sep = ink.copy(alpha = 0.16f)
                buttons.forEach { b ->
                    ConfirmDivider(sep)
                    ConfirmButton(b.title, if (b.destructive) Color(0xFFFF3B30) else blue,
                        if (b.bold) FontWeight.SemiBold else FontWeight.Normal, ink, b.onClick)
                }
            }
        }
    }
}

internal class GlassAlertButton(val title: String, val destructive: Boolean = false, val bold: Boolean = false, val onClick: () -> Unit)

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
            BlurBackdrop(corner, Modifier.matchParentSize(), shape = appShape(corner), menuBlur = true)
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
                // iOS uyarısı gibi: düğmeler ince çizgilerle ayrılır.
                val sep = textColor.copy(alpha = 0.16f)
                if (canDelete) { ConfirmDivider(sep); ConfirmButton("Uygulamayı Sil", red, FontWeight.Normal, textColor, onDelete) }
                ConfirmDivider(sep)
                ConfirmButton(removeTitle, blue, FontWeight.Normal, textColor, onRemove)
                ConfirmDivider(sep)
                ConfirmButton("Vazgeç", blue, FontWeight.SemiBold, textColor, onCancel)
            }
        }
    }
}

@Composable
private fun ConfirmDivider(color: Color) {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(color))
}

/**
 * Onay düğmesi. Android'in varsayılan basma efekti yok: pencereyi açan dokunuş aynı noktadaki düğmede gri, köşesiz
 * bir şerit bırakıyordu. Yalnızca parmak üzerindeyken hafif vurgu; pencere açılır açılmaz gelen dokunuş yok sayılır.
 */
@Composable
private fun ConfirmButton(title: String, color: Color, weight: FontWeight, textColor: Color, onClick: () -> Unit) {
    val u = LocalPt.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shownAt = remember { android.os.SystemClock.uptimeMillis() }
    Box(
        Modifier.fillMaxWidth().height(u * 46f)
            .background(if (pressed) textColor.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) {
                if (android.os.SystemClock.uptimeMillis() - shownAt > 350) onClick()
            },
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
        // Düşük güçte arkası hazır bulanık kopya (gerçek zamanlı bulanıklık kapalıyken menü okunur kalsın).
        .reducedBackdrop()
        .background(fill)
        .accentTint(st.glassAccent)
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
    /** İlk/son satırda menünün iç boşluğu satırın içine alınır: basılı vurgu kenara (ve köşelere) kadar dolar. */
    padTop: androidx.compose.ui.unit.Dp = 0.dp,
    padBottom: androidx.compose.ui.unit.Dp = 0.dp,
    /** Alt menü satırı: soldan biraz içeride. */
    indent: androidx.compose.ui.unit.Dp = 0.dp,
    /** Sağdaki işaret (alt menü oku). */
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val st = LocalLauncherSettings.current
    val u = LocalPt.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowH + padTop + padBottom)
            .clickable(onClick = onClick)
            .padding(top = padTop, bottom = padBottom)
            .padding(start = u * 18f + indent, end = u * 18f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(u * 26f), contentAlignment = Alignment.CenterStart) { leading?.invoke() }
        Spacer(Modifier.width(u * 10f))
        Text(
            title, color = color, fontSize = (st.menuTextSize * u.value).sp, fontWeight = FontWeight.Normal,
            fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, color = color.copy(alpha = 0.5f), fontSize = (st.menuTextSize * 1.1f * u.value).sp, fontFamily = AppFont)
    }
}

/** Widget boyutları tek satırda (iOS 26 gibi): her boyut küçük bir düğme, geçerli boyut dolu görünür. */
@Composable
private fun SizeRow(
    sizes: List<Span>, curX: Int, curY: Int, color: Color, rowH: androidx.compose.ui.unit.Dp,
    padTop: androidx.compose.ui.unit.Dp, padBottom: androidx.compose.ui.unit.Dp, onPick: (Span) -> Unit,
) {
    val st = LocalLauncherSettings.current
    val u = LocalPt.current
    Row(
        Modifier.fillMaxWidth().height(rowH + padTop + padBottom).padding(top = padTop, bottom = padBottom).padding(horizontal = u * 12f),
        horizontalArrangement = Arrangement.spacedBy(u * 6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sizes.forEach { sz ->
            val cur = sz.x == curX && sz.y == curY
            Box(
                Modifier.weight(1f).height(rowH * 0.72f).clip(RoundedCornerShape(50))
                    .background(if (cur) color.copy(alpha = 0.18f) else color.copy(alpha = 0.06f))
                    .clickable(enabled = !cur) { onPick(sz) },
                contentAlignment = Alignment.Center,
            ) {
                Text(sz.title, color = color, fontSize = (st.menuTextSize * 0.82f * u.value).sp, fontFamily = AppFont,
                    fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}


/** Basılı tutma menüsünde bir bildirim: başlık (kalın) ve iki satıra kadar metin, sağda zamanı. */
@Composable
private fun NotifRow(
    nt: dev.glass.launcher.service.NotifPreview,
    color: Color,
    h: androidx.compose.ui.unit.Dp,
    padTop: androidx.compose.ui.unit.Dp,
    padBottom: androidx.compose.ui.unit.Dp,
    divider: Boolean,
    /** Menü ters (simgenin üstünde) açıldıysa ayraç bildirimlerin üstünde durur. */
    dividerOnTop: Boolean,
    onClick: () -> Unit,
) {
    val u = LocalPt.current
    val st = LocalLauncherSettings.current
    val ago = remember(nt.time) {
        val m = ((System.currentTimeMillis() - nt.time) / 60_000L).coerceAtLeast(0)
        when { m < 1 -> "şimdi"; m < 60 -> "$m dk"; m < 1440 -> "${m / 60} sa"; else -> "${m / 1440} g" }
    }
    Column(Modifier.fillMaxWidth()) {
        if (divider && dividerOnTop) Box(Modifier.fillMaxWidth().height(u * 6f).background(color.copy(alpha = 0.08f)))
        Column(
            Modifier
                .fillMaxWidth()
                .height(h + padTop + padBottom)
                .clickable(onClick = onClick)
                .padding(top = padTop, bottom = padBottom)
                .padding(horizontal = u * 18f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    nt.title.ifBlank { nt.text }, color = color, fontSize = (st.menuTextSize * u.value).sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(ago, color = color.copy(alpha = 0.5f), fontSize = (st.menuTextSize * 0.8f * u.value).sp, fontFamily = AppFont)
            }
            if (nt.title.isNotBlank() && nt.text.isNotBlank()) {
                Text(
                    nt.text, color = color.copy(alpha = 0.75f), fontSize = (st.menuTextSize * 0.88f * u.value).sp,
                    fontFamily = AppFont, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (divider && !dividerOnTop) Box(Modifier.fillMaxWidth().height(u * 6f).background(color.copy(alpha = 0.08f)))
    }
}
