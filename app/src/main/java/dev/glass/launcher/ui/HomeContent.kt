package dev.glass.launcher.ui

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import android.app.ActivityOptions
import dev.glass.launcher.data.HomeItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.R
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.AppItem
import dev.glass.launcher.data.FolderItem
import dev.glass.launcher.data.GridPacker
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.PageBreak
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Izgara hücresi; page = -1 dock'u, col o durumda dock yuvasını gösterir. */
private data class Cell(val page: Int, val row: Int, val col: Int)

/**
 * Ana ekran içeriği: sayfalı ızgara + en sonda Uygulama Arşivi sayfası, sayfa noktaları/arama hapı ve dock.
 * Arşive geçerken dock ve arama hapı aşağı kayarak kaybolur (iOS gibi).
 */
@Composable
internal fun HomeContent(
    vm: LauncherViewModel,
    layout: HomeLayout,
    byKey: Map<String, AppInfo>,
    allApps: List<AppInfo>,
    groups: List<LibraryGroup>,
    settings: LauncherSettings,
    drag: DragState,
    bridge: DropBridge,
    folderPages: Map<String, Int>,
    edit: Boolean,
    jiggle: State<Float>,
    isDefaultHome: Boolean,
    onRequestDefault: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFolder: (FolderItem, Offset) -> Unit,
    onOpenGroup: (LibraryGroup) -> Unit,
    /** Arşive geçiş oranı (0 = ana ekran, 1 = arşiv); arka plan bulanıklığı için üst bileşen okur. */
    libProgress: MutableFloatState,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Tam ekran: konumlar iOS'taki gibi ekranın sol-üst köşesinden ölçülür (durum çubuğu dahil).
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val m = HomeMetrics.compute(maxWidth, maxHeight, statusTop, settings)
        val firstTopPx = with(density) { m.firstTop.toPx() }
        val motion = LocalMotion.current
        val view = LocalView.current
        val items = layout.items
        // Uygulamalar 1×1, widget'lar N×M hücre: sayfalara yerleştir.
        // Görünmeyen öğeler (kaldırılmış/erişilemeyen uygulama) sayfa tutmaz: boş sayfalar otomatik silinir.
        val visible: (HomeItem) -> Boolean = { it !is AppItem || byKey.containsKey(it.key) }
        val placements = remember(items, m.columns, m.rows, byKey) { GridPacker.pack(items, m.columns, m.rows, visible) }
        val pageTotal = if (items.isEmpty()) 1 else GridPacker.pageCount(placements)
        val dragging = drag.key != null
        val lastFull = GridPacker.isPageFull(placements, pageTotal - 1, m.columns, m.rows)
        // Sürüklerken son sayfa doluysa yeni boş bir sayfa görünür.
        val homeCount = pageTotal + if (dragging && lastFull) 1 else 0
        val libIndex = homeCount
        val pagerState = rememberPagerState { homeCount + if (settings.showLibrary) 1 else 0 }

        var gridOrigin by remember { mutableStateOf(Offset.Zero) }
        var dockRect by remember { mutableStateOf(Rect.Zero) }

        // --- Canlı yer değiştirme (iOS gibi): sürüklerken diğer simgeler kayarak yer açar ---
        var preview by remember { mutableStateOf<List<HomeItem>?>(null) }
        var dockPreview by remember { mutableStateOf<List<String>?>(null) }
        val targetCell by remember(m, libIndex) {
            derivedStateOf {
                val k = drag.key
                when {
                    k == null || drag.hoverId != null -> null
                    pagerState.currentPage >= libIndex -> null
                    dockRect.height > 0f && drag.pos.y >= dockRect.top - with(density) { 12.dp.toPx() } -> {
                        val dockPitchPx = with(density) { m.dockPitch.toPx() }
                        val dockIconPx = with(density) { m.dockIcon.toPx() }
                        val first = dockRect.left + with(density) { m.dockInnerStart.toPx() }
                        val slot = floor((drag.pos.x - first + (dockPitchPx - dockIconPx) / 2f) / dockPitchPx).toInt().coerceIn(0, 3)
                        Cell(-1, 0, slot)
                    }
                    else -> {
                        val iconPx = with(density) { m.icon.toPx() }
                        val pitchXPx = with(density) { m.pitchX.toPx() }
                        val pitchYPx = with(density) { m.pitchY.toPx() }
                        val marginPx = with(density) { m.marginX.toPx() }
                        val col = floor((drag.pos.x - gridOrigin.x - marginPx + (pitchXPx - iconPx) / 2f) / pitchXPx)
                            .toInt().coerceIn(0, m.columns - 1)
                        val row = floor((drag.pos.y - gridOrigin.y - firstTopPx) / pitchYPx).toInt().coerceIn(0, m.rows - 1)
                        Cell(pagerState.currentPage, row, col)
                    }
                }
            }
        }
        val dragKey = drag.key
        LaunchedEffect(targetCell, dragKey) {
            if (dragKey == null) {
                preview = null
                dockPreview = null
                return@LaunchedEffect
            }
            val cell = targetCell ?: return@LaunchedEffect // klasör hedefi üzerinde: düzen sabit kalır
            // Kısa bekleme: simge merkezine (klasör hedefine) yönelen parmak düzeni hemen bozmasın.
            delay((260 / motion.speed).toLong())
            val cur = vm.layout.value
            val without = cur.items.filter { it.id != dragKey }
            if (cell.page < 0) {
                // Dock üzerinde: ızgaradaki boşluk kapanır; dock'ta yer varsa simgeler kayar.
                preview = without
                val isApp = byKey.containsKey(dragKey)
                dockPreview = if (isApp && (dragKey in cur.dock || cur.dock.size < 4)) {
                    (cur.dock - dragKey).toMutableList().apply { add(cell.col.coerceIn(0, size), dragKey) }
                } else null
                return@LaunchedEffect
            }
            dockPreview = cur.dock - dragKey
            val idx = GridPacker.insertionIndex(GridPacker.pack(without, m.columns, m.rows, visible), cell.page, cell.row, cell.col, m.columns)
            val dragged = cur.items.firstOrNull { it.id == dragKey }
                ?: if (byKey.containsKey(dragKey)) AppItem(dragKey) else return@LaunchedEffect
            preview = without.toMutableList().apply { add(idx.coerceIn(0, size), dragged) }
        }
        val shownItems = preview ?: items
        val shownPlacements = remember(shownItems, m.columns, m.rows, byKey) { GridPacker.pack(shownItems, m.columns, m.rows, visible) }
        val byPage = remember(shownPlacements) { shownPlacements.groupBy { it.page } }
        val shownDock = dockPreview ?: layout.dock

        // Otomatik kalite: kaydırma sırasında kare sürelerini örnekle (ek kare istemez; zaten çizilen kareler).
        LaunchedEffect(pagerState.isScrollInProgress, settings.autoQuality) {
            if (!settings.autoQuality || !pagerState.isScrollInProgress) return@LaunchedEffect
            val refresh = view.display?.refreshRate?.takeIf { it > 1f } ?: 60f
            val budget = (1_000_000_000L / refresh).toLong()
            var last = 0L
            var n = 0
            var slow = 0
            while (n < 40) {
                withFrameNanos { t ->
                    if (last != 0L) {
                        n++
                        if (t - last > budget * 18 / 10) slow++
                    }
                    last = t
                }
            }
            if (slow > n / 3) vm.reportSlowFrames()
        }

        /** Uygulamayı, simgesinden büyüyen sistem animasyonuyla açar. */
        fun launchFrom(app: AppInfo, r: Rect) {
            val opts = runCatching {
                ActivityOptions.makeScaleUpAnimation(view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt()).toBundle()
            }.getOrNull()
            vm.launch(app, android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()), opts)
        }

        // 0 = ana ekran, 1 = tamamen Uygulama Arşivi
        val libFraction by remember(libIndex) {
            derivedStateOf {
                (pagerState.currentPage + pagerState.currentPageOffsetFraction - (libIndex - 1)).coerceIn(0f, 1f)
            }
        }

        LaunchedEffect(libIndex) {
            snapshotFlow { libFraction }.collect { libProgress.floatValue = it }
        }

        // Sayfa kaydırılırken noktalar, durunca arama hapı görünür (iOS 16+ davranışı).
        var showDots by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.isScrollInProgress) {
            if (pagerState.isScrollInProgress) showDots = true else { delay(900); showDots = false }
        }
        val dotsAlpha by animateFloatAsState(if (showDots) 1f else 0f, motion.tween(220), label = "dots")

        // Sürüklerken ekran kenarında bekleyince sayfa değişir (arşive geçmez).
        val widthPx = with(density) { maxWidth.toPx() }
        val edgePx = with(density) { 36.dp.toPx() }
        val edge by remember(widthPx, edgePx) {
            derivedStateOf {
                when {
                    drag.key == null -> 0
                    drag.pos.x < edgePx -> -1
                    drag.pos.x > widthPx - edgePx -> 1
                    else -> 0
                }
            }
        }
        LaunchedEffect(edge, libIndex) {
            if (edge != 0) {
                while (true) {
                    delay(650)
                    val target = pagerState.currentPage + edge
                    if (target in 0 until libIndex) pagerState.animateScrollToPage(target)
                }
            }
        }

        fun handleDrop(id: String, pos: Offset, hover: String?) {
            if (pagerState.currentPage >= libIndex) return // arşiv sayfasına bırakma: değişiklik yok
            val cur = vm.layout.value
            val isApp = byKey.containsKey(id)
            val topLevel = cur.items.any { it.id == id }

            // 1) Dock bölgesi
            if (dockRect.height > 0f && pos.y >= dockRect.top - with(density) { 12.dp.toPx() }) {
                if (!isApp) return // klasörler dock'a konmaz
                val dockPitchPx = with(density) { m.dockPitch.toPx() }
                val dockIconPx = with(density) { m.dockIcon.toPx() }
                val first = dockRect.left + with(density) { m.dockInnerStart.toPx() }
                val slot = floor((pos.x - first + (dockPitchPx - dockIconPx) / 2f) / dockPitchPx)
                    .toInt().coerceIn(0, 3)
                vm.editLayout { it.moveToDock(id, slot) }
                return
            }

            // 2) Bir simgenin üzerine bırakıldı: klasör oluştur / klasöre ekle
            if (hover != null && isApp) {
                vm.editLayout { it.mergeInto(hover, id) }
                return
            }

            // 3) Ana ekran ızgarasında bir hücreye (widget'lar hesaba katılarak)
            val iconPx = with(density) { m.icon.toPx() }
            val pitchXPx = with(density) { m.pitchX.toPx() }
            val pitchYPx = with(density) { m.pitchY.toPx() }
            val marginPx = with(density) { m.marginX.toPx() }
            val x = pos.x - gridOrigin.x
            val y = pos.y - gridOrigin.y - firstTopPx
            val col = floor((x - marginPx + (pitchXPx - iconPx) / 2f) / pitchXPx).toInt().coerceIn(0, m.columns - 1)
            val row = floor(y / pitchYPx).toInt().coerceIn(0, m.rows - 1)
            val without = cur.items.filter { it.id != id }
            val idx = GridPacker.insertionIndex(
                GridPacker.pack(without, m.columns, m.rows, visible), pagerState.currentPage, row, col, m.columns,
            )
            if (topLevel) vm.editLayout { it.moveItem(id, idx) }
            else vm.editLayout { it.moveAppToGrid(id, idx) } // dock, klasör veya arşivden
        }

        SideEffect {
            bridge.home = ::handleDrop
            bridge.goToPage = { p -> scope.launch { pagerState.animateScrollToPage(p.coerceIn(0, pagerState.pageCount - 1)) } }
            bridge.goToLastHomePage = { scope.launch { pagerState.animateScrollToPage(maxOf(pageTotal - 1, 0)) } }
            bridge.onLibrary = pagerState.currentPage == libIndex
            bridge.homePageCount = pageTotal
            bridge.currentPageIds = placements.filter { it.page == pagerState.currentPage }.map { it.item.id }
            vm.currentPageStart = placements.indexOfFirst { it.page == pagerState.currentPage }
                .let { if (it < 0) items.size else it }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !dragging,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().onGloballyPositioned { gridOrigin = it.positionInRoot() },
        ) { page ->
            if (page == libIndex) {
                // Arama çubuğu, ana ekrandaki ilk simge satırıyla AYNI üst çizgiden başlar.
                Box(Modifier.fillMaxSize().padding(top = m.firstTop)) {
                    LibraryPage(
                        vm = vm, groups = groups, allApps = allApps, settings = settings,
                        drag = drag, edit = edit, jiggle = jiggle,
                        onDropFromLibrary = { k, p, h -> handleDrop(k, p, h) },
                        onOpenGroup = onOpenGroup,
                    )
                }
                return@HorizontalPager
            }
            val pagePl = byPage[page].orEmpty()
            // Üstten hizalı sabit ızgara: boş hücreler boş kalır, simgeler yayılmaz.
            Box(Modifier.fillMaxSize()) {
                pagePl.forEach { pl ->
                  key(pl.item.id) {
                    val x = m.marginX + m.pitchX * pl.col.toFloat()
                    val y = m.firstTop + m.pitchY * pl.row.toFloat()
                    // Yer değişince simge yeni konumuna yayla kayar.
                    val ax by animateDpAsState(x, motion.reflow(), label = "x")
                    val ay by animateDpAsState(y, motion.reflow(), label = "y")
                    Box(Modifier.offset { IntOffset(ax.roundToPx(), ay.roundToPx()) }) {
                        when (val item = pl.item) {
                            is AppItem -> {
                                val app = byKey[item.key]
                                if (app != null) {
                                    DraggableCell(
                                        id = item.id, place = CellPlace.Grid,
                                        label = app.label, showLabel = settings.showLabels,
                                        icon = m.icon, pitchX = m.pitchX, cellHeight = m.pitchY,
                                        drag = drag, layer = DragLayer.Home, isApp = true,
                                        acceptsMerge = true, edit = edit, jiggle = jiggle,
                                        onClick = { r -> if (!edit) launchFrom(app, r) },
                                        onDrop = { p, h -> handleDrop(item.id, p, h) },
                                    ) { AppIconImage(app, vm, m.icon, settings.iconGlass, settings.glassStrength) }
                                }
                            }
                            is FolderItem -> DraggableCell(
                                id = item.id, place = CellPlace.Grid,
                                label = item.name, showLabel = settings.showLabels,
                                icon = m.icon, pitchX = m.pitchX, cellHeight = m.pitchY,
                                drag = drag, layer = DragLayer.Home, isApp = false,
                                acceptsMerge = true, edit = edit, jiggle = jiggle,
                                onClick = { r -> onOpenFolder(item, r.center) },
                                onDrop = { p, h -> handleDrop(item.id, p, h) },
                            ) {
                                FolderIcon(
                                    item, byKey, vm, m.icon, settings.glassStrength * settings.folderOpacity,
                                    folderPages[item.id] ?: 0,
                                )
                            }
                            is WidgetItem -> {
                                val w = m.widgetWidth(pl.spanX)
                                val h = m.widgetHeight(pl.spanY)
                                WidgetCell(
                                    id = item.id, width = w, height = h, drag = drag, edit = edit, jiggle = jiggle,
                                    onDrop = { p, hv -> handleDrop(item.id, p, hv) },
                                ) { WidgetBody(item, vm, w, h, settings.glassStrength) }
                            }
                            is PageBreak -> Unit // görünmez sayfa sonu
                        }
                    }
                  }
                }
            }
        }

        if (!isDefaultHome) {
            Column(
                Modifier
                    .padding(top = statusTop + 4.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x99000000))
                    .padding(14.dp)
            ) {
                Text(stringResource(R.string.make_default_hint), color = Color.White, fontSize = 13.sp)
                Button(onClick = onRequestDefault) { Text(stringResource(R.string.make_default)) }
            }
        }

        // Alt bölüm: arama hapı / sayfa noktaları ve dock. Arşive geçerken aşağı kayar.
        val hideShiftPx = with(density) { (maxHeight - m.pillCenterY + m.pillH).toPx() }
        val dockW = maxWidth - m.dockMarginX * 2f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = libFraction * hideShiftPx
                    alpha = 1f - libFraction
                }
        ) {
            // Hap: simge dizisinin altı ile dock'un üstü arasında tam ortada.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(m.pillH)
                    .offset(y = m.pillCenterY - m.pillH / 2f),
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.graphicsLayer { alpha = dotsAlpha }) {
                    repeat(homeCount) { i ->
                        Box(
                            Modifier.padding(3.dp).size(7.dp).clip(CircleShape)
                                .background(if (i == pagerState.currentPage) Color.White else Color(0x66FFFFFF))
                        )
                    }
                    // Arşiv sayfası için küçük, soluk bir gösterge
                    Box(
                        Modifier.padding(3.dp).size(7.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (pagerState.currentPage == libIndex) Color.White else Color(0x40FFFFFF))
                    )
                }
                SearchPill(m, settings.glassStrength, (1f - dotsAlpha) * (1f - libFraction), onOpenSearch)
            }

            if (libFraction < 0.98f) {
                Box(
                    Modifier
                        .offset(x = m.dockMarginX, y = m.dockTop)
                        .width(dockW)
                        .height(m.dockH)
                        .onGloballyPositioned { dockRect = it.boundsInRoot() }
                        .liquidGlass(appShape(m.dockCorner), settings.glassStrength * settings.dockOpacity, 14.dp)
                ) {
                    shownDock.forEachIndexed { i, dk ->
                      key(dk) {
                        val app = byKey[dk]
                        if (app != null) {
                            val dx by animateDpAsState(m.dockInnerStart + m.dockPitch * i.toFloat(), motion.reflow(), label = "dx")
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .offset { IntOffset(dx.roundToPx(), 0) }
                            ) {
                                DraggableCell(
                                    id = dk, place = CellPlace.Dock,
                                    label = app.label, showLabel = false,
                                    icon = m.dockIcon, pitchX = m.dockPitch, cellHeight = m.dockIcon,
                                    drag = drag, layer = DragLayer.Home, isApp = true,
                                    acceptsMerge = false, edit = edit, jiggle = jiggle,
                                    onClick = { r -> if (!edit) launchFrom(app, r) },
                                    onDrop = { p, h -> handleDrop(dk, p, h) },
                                ) { AppIconImage(app, vm, m.dockIcon, settings.iconGlass, settings.glassStrength) }
                            }
                        }
                      }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPill(m: HomeMetrics, strength: Float, alpha: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .graphicsLayer { this.alpha = alpha }
            .width(m.pillW)
            .height(m.pillH)
            .liquidGlass(CircleShape, strength * 0.8f, 6.dp)
            .then(if (alpha > 0.5f) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pt = m.pt.value
        val k = m.pillH / (m.pt * 29f) // arama düğmesi boyut çarpanı
        SearchGlyph(Color.White, m.pt * 13f * k)
        Spacer(Modifier.width(m.pt * 5f * k))
        Text(
            "Ara", color = Color.White,
            fontSize = (LocalLauncherSettings.current.pillTextSize * pt * k).sp,
            fontWeight = fontWeightOf(LocalLauncherSettings.current.pillTextWeight),
            style = LocalTextStyle.current.copy(shadow = IosTextShadow),
        )
    }
}

private val LabelStyle: TextStyle get() = TextStyle(
    color = Color.White,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    textAlign = TextAlign.Center,
    fontFamily = AppFont,
    shadow = IosTextShadow,
)

/**
 * Uygulama simgesi: seçili görünüm (orijinal/açık/koyu/renkli veya özel görsel) ve ayarlanabilir köşe
 * yuvarlaklığıyla. Bitmap ekrandaki boyutta, arka planda üretilir ve önbelleğe alınır.
 */
@Composable
internal fun AppIconImage(app: AppInfo, vm: LauncherViewModel, icon: Dp, glass: Boolean, strength: Float) {
    val st = LocalLauncherSettings.current
    val px = with(LocalDensity.current) { icon.roundToPx() }
    val look = vm.iconLook(app, st)
    val live = vm.liveKind(app, st)
    // Kenar çizgisi kalınlığı: ana ekran boyutunda ayardaki değer, küçük simgelerde orantılı incelir.
    val rimW = st.iconRimWidth.dp * (icon / 60.dp).coerceAtMost(1f)
    if (live != null) {
        Box(Modifier.size(icon).glassIcon(appShape(icon * st.iconCorner), glass, strength, st.iconRimGlow, rimW)) {
            LiveIcon(live, icon, st.liveClockSeconds)
        }
        return
    }
    val bmp by produceState<ImageBitmap?>(null, app.key, px, look) { value = vm.iconCache.get(app, px, look) }
    Box(Modifier.size(icon).glassIcon(appShape(icon * st.iconCorner), glass, strength, st.iconRimGlow, rimW)) {
        bmp?.let { Image(it, contentDescription = app.label, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.High) }
    }
}

/** Sürüklenmeyen basit hücre (arama sonuçları vb.). */
@Composable
internal fun AppCell(
    app: AppInfo, vm: LauncherViewModel,
    icon: Dp, pitchX: Dp, cellHeight: Dp, showLabel: Boolean,
    glass: Boolean, strength: Float, onClick: () -> Unit,
) {
    Box(Modifier.width(icon).height(cellHeight), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.requiredWidth(pitchX).clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppIconImage(app, vm, icon, glass, strength)
            if (showLabel) {
                Text(
                    app.label,
                    style = LabelStyle.copy(fontWeight = fontWeightOf(LocalLauncherSettings.current.labelWeight)),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp).fillMaxWidth()
                )
            }
        }
    }
}
