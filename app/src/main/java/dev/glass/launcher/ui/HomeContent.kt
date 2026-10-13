package dev.glass.launcher.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

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
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        val m = HomeMetrics.compute(maxWidth, maxHeight, settings)
        val items = layout.items
        val pages = items.chunked(m.perPage)
        val dragging = drag.key != null
        val lastFull = (pages.lastOrNull()?.size ?: 0) >= m.perPage
        // Sürüklerken son sayfa doluysa yeni boş bir sayfa görünür.
        val homeCount = maxOf(pages.size, 1) + if (dragging && lastFull) 1 else 0
        val libIndex = homeCount
        val pagerState = rememberPagerState { homeCount + 1 }

        var gridOrigin by remember { mutableStateOf(Offset.Zero) }
        var dockRect by remember { mutableStateOf(Rect.Zero) }

        // 0 = ana ekran, 1 = tamamen Uygulama Arşivi
        val libFraction by remember(libIndex) {
            derivedStateOf {
                (pagerState.currentPage + pagerState.currentPageOffsetFraction - (libIndex - 1)).coerceIn(0f, 1f)
            }
        }

        // Sayfa kaydırılırken noktalar, durunca arama hapı görünür (iOS 16+ davranışı).
        var showDots by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.isScrollInProgress) {
            if (pagerState.isScrollInProgress) showDots = true else { delay(900); showDots = false }
        }
        val dotsAlpha by animateFloatAsState(if (showDots) 1f else 0f, tween(220), label = "dots")

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

            // 3) Ana ekran ızgarasında bir hücreye
            val iconPx = with(density) { m.icon.toPx() }
            val pitchXPx = with(density) { m.pitchX.toPx() }
            val pitchYPx = with(density) { m.pitchY.toPx() }
            val marginPx = with(density) { m.marginX.toPx() }
            val x = pos.x - gridOrigin.x
            val y = pos.y - gridOrigin.y
            val col = floor((x - marginPx + (pitchXPx - iconPx) / 2f) / pitchXPx).toInt().coerceIn(0, m.columns - 1)
            val row = floor(y / pitchYPx).toInt().coerceIn(0, m.rows - 1)
            val idx = pagerState.currentPage * m.perPage + row * m.columns + col
            if (topLevel) vm.editLayout { it.moveItem(id, idx) }
            else vm.editLayout { it.moveAppToGrid(id, idx) } // dock, klasör veya arşivden
        }

        SideEffect {
            bridge.home = ::handleDrop
            bridge.goToPage = { p -> scope.launch { pagerState.animateScrollToPage(p.coerceIn(0, libIndex)) } }
            bridge.goToLastHomePage = { scope.launch { pagerState.animateScrollToPage(maxOf(pages.size - 1, 0)) } }
            bridge.onLibrary = pagerState.currentPage == libIndex
        }

        val bottomReserve = HomeMetrics.IndicatorHeight + m.dockReserve

        Column(Modifier.fillMaxSize()) {
            if (!isDefaultHome) {
                Column(
                    Modifier
                        .padding(12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x66000000))
                        .padding(14.dp)
                ) {
                    Text(stringResource(R.string.make_default_hint), color = Color.White, fontSize = 13.sp)
                    Button(onClick = onRequestDefault) { Text(stringResource(R.string.make_default)) }
                }
            }

            Spacer(Modifier.height(m.topPad))

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !dragging,
                beyondViewportPageCount = 1,
                modifier = Modifier.weight(1f).onGloballyPositioned { gridOrigin = it.positionInRoot() },
            ) { page ->
                if (page == libIndex) {
                    LibraryPage(
                        vm = vm, groups = groups, allApps = allApps, settings = settings,
                        drag = drag, edit = edit, jiggle = jiggle,
                        onDropFromLibrary = { k, p, h -> handleDrop(k, p, h) },
                        onOpenGroup = onOpenGroup,
                    )
                    return@HorizontalPager
                }
                val pageItems = pages.getOrNull(page).orEmpty()
                // Üstten hizalı sabit ızgara: eksik satırlar boş kalır, simgeler yayılmaz.
                Column(Modifier.fillMaxSize().padding(start = m.marginX, bottom = bottomReserve)) {
                    pageItems.chunked(m.columns).forEach { row ->
                        Row(
                            Modifier.height(m.pitchY),
                            horizontalArrangement = Arrangement.spacedBy(m.pitchX - m.icon)
                        ) {
                            row.forEach { item ->
                                when (item) {
                                    is AppItem -> {
                                        val app = byKey[item.key]
                                        if (app != null) {
                                            DraggableCell(
                                                id = item.id, place = CellPlace.Grid,
                                                label = app.label, showLabel = settings.showLabels,
                                                icon = m.icon, pitchX = m.pitchX, cellHeight = m.pitchY,
                                                drag = drag, layer = DragLayer.Home, isApp = true,
                                                acceptsMerge = true, edit = edit, jiggle = jiggle,
                                                onClick = { if (!edit) vm.launch(app) },
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
                                        onClick = { c -> onOpenFolder(item, c) },
                                        onDrop = { p, h -> handleDrop(item.id, p, h) },
                                    ) {
                                        FolderIcon(item, byKey, vm, m.icon, settings.glassStrength, folderPages[item.id] ?: 0)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Alt bölüm: sayfa noktaları / arama hapı + dock. Arşive geçerken aşağı kayar.
        val hideShiftPx = with(density) { (bottomReserve + 24.dp).toPx() }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = libFraction * hideShiftPx
                    alpha = 1f - libFraction
                }
        ) {
            Box(
                Modifier.fillMaxWidth().height(HomeMetrics.IndicatorHeight),
                contentAlignment = Alignment.Center
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

            Box(Modifier.fillMaxWidth().height(m.dockReserve), contentAlignment = Alignment.TopCenter) {
                if (libFraction < 0.98f) {
                    Box(
                        Modifier
                            .padding(horizontal = m.dockMarginX)
                            .fillMaxWidth()
                            .height(m.dockH)
                            .onGloballyPositioned { dockRect = it.boundsInRoot() }
                            .liquidGlass(RoundedCornerShape(m.dockH * 0.38f), settings.glassStrength, 14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(start = m.dockInnerStart),
                            horizontalArrangement = Arrangement.spacedBy(m.dockPitch - m.dockIcon),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            layout.dock.forEach { key ->
                                val app = byKey[key]
                                if (app != null) {
                                    DraggableCell(
                                        id = key, place = CellPlace.Dock,
                                        label = app.label, showLabel = false,
                                        icon = m.dockIcon, pitchX = m.dockPitch, cellHeight = m.dockIcon,
                                        drag = drag, layer = DragLayer.Home, isApp = true,
                                        acceptsMerge = false, edit = edit, jiggle = jiggle,
                                        onClick = { if (!edit) vm.launch(app) },
                                        onDrop = { p, h -> handleDrop(key, p, h) },
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
        SearchGlyph(Color.White, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text("Ara", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val LabelStyle = TextStyle(
    color = Color.White,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color(0x66000000), Offset(0f, 1f), 4f),
)

@Composable
internal fun AppIconImage(app: AppInfo, vm: LauncherViewModel, icon: Dp, glass: Boolean, strength: Float) {
    val px = with(LocalDensity.current) { icon.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, app.key, px) { value = vm.iconCache.get(app, px) }
    Box(Modifier.size(icon).glassIcon(RoundedCornerShape(icon * 0.225f), glass, strength)) {
        bmp?.let { Image(it, contentDescription = app.label, modifier = Modifier.fillMaxSize()) }
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
                    style = LabelStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp).fillMaxWidth()
                )
            }
        }
    }
}
