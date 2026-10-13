package dev.glass.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glass.launcher.R
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.AppItem
import dev.glass.launcher.data.FolderItem
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

enum class Overlay { None, Search, Control, Settings, Folder }

/**
 * AŞAMA 3c: düzenleme modu (sallanma), sürükle-bırak, klasörler (3×3), kalıcı yerleşim.
 * Hareketler: boş alanda uzun bas = düzenleme modu, aşağı kaydır = arama, üst bölgeden aşağı = panel.
 */
@Composable
fun HomeScreen(vm: LauncherViewModel, isDefaultHome: Boolean, onRequestDefault: () -> Unit) {
    val layout by vm.layout.collectAsStateWithLifecycle()
    val byKey by vm.appsByKey.collectAsStateWithLifecycle()
    val allApps by vm.apps.collectAsStateWithLifecycle()
    val recents by vm.recentApps.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val drag = remember { DragState() }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var editMode by remember { mutableStateOf(false) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var folderOrigin by remember { mutableStateOf(TransformOrigin.Center) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val overlayNow by rememberUpdatedState(overlay)
    val editNow by rememberUpdatedState(editMode)

    val blur by animateDpAsState(if (overlay == Overlay.None) 0.dp else 22.dp, tween(260), label = "blur")
    val scrim by animateFloatAsState(if (overlay == Overlay.None) 0f else 0.35f, tween(260), label = "scrim")

    // Sallanma animasyonu yalnızca düzenleme modunda çalışır (pil).
    val jiggle: State<Float> = if (editMode) {
        rememberInfiniteTransition(label = "jiggle").animateFloat(
            initialValue = -1.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
            label = "jiggleAngle",
        )
    } else remember { mutableStateOf(0f) }

    BackHandler(enabled = true) {
        if (overlay != Overlay.None) {
            overlay = Overlay.None
            openFolderId = null
        } else if (editMode) editMode = false
    }

    val density = LocalDensity.current
    val topInsetPx = WindowInsets.statusBars.getTop(density)
    val topZonePx = topInsetPx + with(density) { 72.dp.toPx() }
    val suggestions = remember(recents, allApps) { (recents + allApps).distinctBy { it.key }.take(4) }

    val folderHolder = remember { arrayOfNulls<FolderItem>(1) }
    val openFolder = layout.items.firstOrNull { it.id == openFolderId } as? FolderItem
    if (openFolder != null) folderHolder[0] = openFolder

    fun closeOverlay() {
        overlay = Overlay.None
        openFolderId = null
    }

    Box(
        Modifier
            .fillMaxSize()
            // Sürükleme sırasında tüm hareketi EN ÜST katmanda (Initial geçişi) alır ve tüketir;
            // böylece sayfa kaydırıcı ve diğer dedektörler hareketi çalamaz.
            .pointerInput(drag) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (drag.key == null) continue
                        val change = event.changes.first()
                        drag.pos = change.position
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) drag.finish()
                    }
                }
            }
            .onSizeChanged { rootSize = it }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { if (overlayNow == Overlay.None && !editNow) editMode = true })
            }
            .pointerInput(topZonePx) {
                var startY = 0f
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { startY = it.y; total = 0f },
                    onDragEnd = {
                        if (overlayNow == Overlay.None && !editNow && total > 80.dp.toPx()) {
                            overlay = if (startY < topZonePx) Overlay.Control else Overlay.Search
                        }
                        total = 0f
                    },
                    onDragCancel = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
    ) {
        Box(Modifier.fillMaxSize().then(if (blur > 0.dp) Modifier.blur(blur) else Modifier)) {
            HomeContent(
                vm = vm,
                layout = layout,
                byKey = byKey,
                settings = settings,
                drag = drag,
                edit = editMode,
                jiggle = jiggle,
                isDefaultHome = isDefaultHome,
                onRequestDefault = onRequestDefault,
                onOpenSearch = { overlay = Overlay.Search },
                onStartEdit = { editMode = true },
                onOpenFolder = { f, c ->
                    folderOrigin = TransformOrigin(
                        c.x / rootSize.width.coerceAtLeast(1),
                        c.y / rootSize.height.coerceAtLeast(1),
                    )
                    openFolderId = f.id
                    overlay = Overlay.Folder
                },
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))

        AnimatedVisibility(
            overlay == Overlay.Search,
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.96f),
            exit = fadeOut(tween(140)),
        ) {
            SearchOverlay(vm, allApps, suggestions, settings.glassStrength, settings.iconGlass, onClose = { closeOverlay() })
        }
        AnimatedVisibility(
            overlay == Overlay.Control,
            enter = slideInVertically { -it / 2 } + fadeIn(tween(200)),
            exit = slideOutVertically { -it / 2 } + fadeOut(tween(160)),
        ) {
            ControlPanel(settings.glassStrength, onClose = { closeOverlay() })
        }
        AnimatedVisibility(
            overlay == Overlay.Settings,
            enter = slideInVertically { it } + fadeIn(tween(200)),
            exit = slideOutVertically { it } + fadeOut(tween(160)),
        ) {
            SettingsSheet(settings, onChange = { vm.updateSettings(it) }, onClose = { closeOverlay() })
        }
        AnimatedVisibility(
            overlay == Overlay.Folder && openFolder != null,
            enter = scaleIn(tween(260), initialScale = 0.25f, transformOrigin = folderOrigin) + fadeIn(tween(200)),
            exit = scaleOut(tween(200), targetScale = 0.25f, transformOrigin = folderOrigin) + fadeOut(tween(160)),
        ) {
            folderHolder[0]?.let { f ->
                FolderOverlay(
                    folder = f,
                    byKey = byKey,
                    vm = vm,
                    settings = settings,
                    drag = drag,
                    jiggle = jiggle,
                    edit = editMode,
                    onStartEdit = { editMode = true },
                    onRename = { name -> vm.editLayout { it.renameFolder(f.id, name) } },
                    onReorder = { key, idx -> vm.editLayout { it.reorderInFolder(f.id, key, idx) } },
                    onMoveOut = { key ->
                        vm.editLayout { it.moveAppToGrid(key, it.items.size) }
                        closeOverlay()
                    },
                    onClose = { closeOverlay() },
                )
            }
        }

        // Düzenleme modu üst çubuğu
        AnimatedVisibility(
            editMode && overlay == Overlay.None,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                GlassButton("Ayarlar", settings.glassStrength) { overlay = Overlay.Settings }
                GlassButton("Bitti", settings.glassStrength) { editMode = false }
            }
        }

        // Sürüklenen öğenin hayalet simgesi (tüm katmanların üstünde)
        val dk = drag.key
        if (dk != null) {
            val px = with(density) { drag.iconDp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((drag.pos.x - px / 2f).roundToInt(), (drag.pos.y - px / 2f).roundToInt()) }
                    .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = 0.94f }
            ) {
                val app = byKey[dk]
                if (app != null) {
                    AppIconImage(app, vm, drag.iconDp, settings.iconGlass, settings.glassStrength)
                } else {
                    (layout.items.firstOrNull { it.id == dk } as? FolderItem)?.let {
                        FolderIcon(it, byKey, vm, drag.iconDp, settings.glassStrength)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassButton(text: String, strength: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .height(34.dp)
            .liquidGlass(CircleShape, strength, 4.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun HomeContent(
    vm: LauncherViewModel,
    layout: HomeLayout,
    byKey: Map<String, AppInfo>,
    settings: LauncherSettings,
    drag: DragState,
    edit: Boolean,
    jiggle: State<Float>,
    isDefaultHome: Boolean,
    onRequestDefault: () -> Unit,
    onOpenSearch: () -> Unit,
    onStartEdit: () -> Unit,
    onOpenFolder: (FolderItem, Offset) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        val m = HomeMetrics.compute(maxWidth, maxHeight, settings)
        val items = layout.items
        val pages = items.chunked(m.perPage)
        val dragging = drag.key != null
        val lastFull = (pages.lastOrNull()?.size ?: 0) >= m.perPage
        // Sürüklerken son sayfa doluysa yeni boş bir sayfa görünür.
        val pageCount = maxOf(pages.size, 1) + if (dragging && lastFull) 1 else 0
        val pagerState = rememberPagerState { pageCount }

        var gridOrigin by remember { mutableStateOf(Offset.Zero) }
        var dockRect by remember { mutableStateOf(Rect.Zero) }

        // Sayfa kaydırılırken noktalar, durunca arama hapı görünür (iOS 16+ davranışı).
        var showDots by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.isScrollInProgress) {
            if (pagerState.isScrollInProgress) showDots = true else { delay(900); showDots = false }
        }
        val dotsAlpha by animateFloatAsState(if (showDots) 1f else 0f, tween(220), label = "dots")

        // Sürüklerken ekran kenarında bekleyince sayfa değişir.
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
        LaunchedEffect(edge) {
            if (edge != 0) {
                while (true) {
                    delay(650)
                    val target = pagerState.currentPage + edge
                    if (target in 0 until pagerState.pageCount) pagerState.animateScrollToPage(target)
                }
            }
        }

        fun handleDrop(id: String, pos: Offset) {
            val cur = vm.layout.value
            val isApp = byKey.containsKey(id)
            val iconPx = with(density) { m.icon.toPx() }

            // 1) Dock bölgesi
            if (dockRect.height > 0f && pos.y >= dockRect.top - with(density) { 12.dp.toPx() }) {
                if (!isApp) return
                val dockPitchPx = with(density) { m.dockPitch.toPx() }
                val dockIconPx = with(density) { m.dockIcon.toPx() }
                val first = dockRect.left + with(density) { m.dockInnerStart.toPx() }
                val slot = ((pos.x - first + (dockPitchPx - dockIconPx) / 2f) / dockPitchPx)
                    .let { floor(it).toInt() }.coerceIn(0, 3)
                vm.editLayout { it.moveToDock(id, slot) }
                return
            }

            // 2) Ana ekran ızgarası
            val pitchXPx = with(density) { m.pitchX.toPx() }
            val pitchYPx = with(density) { m.pitchY.toPx() }
            val marginPx = with(density) { m.marginX.toPx() }
            val x = pos.x - gridOrigin.x
            val y = pos.y - gridOrigin.y
            val col = floor((x - marginPx + (pitchXPx - iconPx) / 2f) / pitchXPx).toInt().coerceIn(0, m.columns - 1)
            val row = floor(y / pitchYPx).toInt().coerceIn(0, m.rows - 1)
            val idx = pagerState.currentPage * m.perPage + row * m.columns + col

            val target = cur.items.getOrNull(idx)
            val cx = gridOrigin.x + marginPx + col * pitchXPx + iconPx / 2f
            val cy = gridOrigin.y + row * pitchYPx + iconPx / 2f
            val inCenter = (pos - Offset(cx, cy)).getDistance() < iconPx * 0.5f

            if (target != null && target.id != id && inCenter && isApp) {
                vm.editLayout { it.mergeInto(target.id, id) }
            } else if (cur.dock.contains(id)) {
                vm.editLayout { it.moveAppToGrid(id, idx) }
            } else {
                vm.editLayout { it.moveItem(id, idx) }
            }
        }

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
                modifier = Modifier.weight(1f).onGloballyPositioned { gridOrigin = it.positionInRoot() },
            ) { page ->
                val pageItems = pages.getOrNull(page).orEmpty()
                // Üstten hizalı sabit ızgara: eksik satırlar boş kalır, simgeler yayılmaz.
                Column(Modifier.fillMaxSize().padding(start = m.marginX)) {
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
                                                id = item.id, label = app.label, showLabel = settings.showLabels,
                                                icon = m.icon, pitchX = m.pitchX, cellHeight = m.pitchY,
                                                drag = drag, acceptsMerge = true, edit = edit, jiggle = jiggle,
                                                onClick = { if (!edit) vm.launch(app) },
                                                onDragStart = onStartEdit,
                                                onDrop = { p -> handleDrop(item.id, p) },
                                            ) { AppIconImage(app, vm, m.icon, settings.iconGlass, settings.glassStrength) }
                                        }
                                    }
                                    is FolderItem -> DraggableCell(
                                        id = item.id, label = item.name, showLabel = settings.showLabels,
                                        icon = m.icon, pitchX = m.pitchX, cellHeight = m.pitchY,
                                        drag = drag, acceptsMerge = true, edit = edit, jiggle = jiggle,
                                        onClick = { c -> onOpenFolder(item, c) },
                                        onDragStart = onStartEdit,
                                        onDrop = { p -> handleDrop(item.id, p) },
                                    ) { FolderIcon(item, byKey, vm, m.icon, settings.glassStrength) }
                                }
                            }
                        }
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth().height(HomeMetrics.IndicatorHeight),
                contentAlignment = Alignment.Center
            ) {
                Row(Modifier.graphicsLayer { alpha = dotsAlpha }) {
                    repeat(pagerState.pageCount) { i ->
                        Box(
                            Modifier.padding(3.dp).size(7.dp).clip(CircleShape)
                                .background(if (i == pagerState.currentPage) Color.White else Color(0x66FFFFFF))
                        )
                    }
                }
                SearchPill(m, settings.glassStrength, 1f - dotsAlpha, onOpenSearch)
            }

            Box(Modifier.fillMaxWidth().height(m.dockReserve), contentAlignment = Alignment.TopCenter) {
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
                                    id = key, label = app.label, showLabel = false,
                                    icon = m.dockIcon, pitchX = m.dockPitch, cellHeight = m.dockIcon,
                                    drag = drag, acceptsMerge = false, edit = edit, jiggle = jiggle,
                                    onClick = { if (!edit) vm.launch(app) },
                                    onDragStart = onStartEdit,
                                    onDrop = { p -> handleDrop(key, p) },
                                ) { AppIconImage(app, vm, m.dockIcon, settings.iconGlass, settings.glassStrength) }
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
