package dev.glass.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.ContentScale
import dev.glass.launcher.data.ThemeMode
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glass.launcher.data.FolderItem
import kotlin.math.roundToInt

enum class Overlay { None, Search, Control, Settings, Folder, Menu, Group, Widgets }

/**
 * Ana ekran katmanları arasında köprü: bırakma işlemi ve sayfa geçişleri ana ekran içeriğinde
 * (pager'ın olduğu yerde) tanımlıdır; üst katmanlar (klasör, arşiv, Home tuşu) bunları buradan çağırır.
 */
class DropBridge {
    var home: ((String, Offset, String?) -> Unit)? = null
    var goToPage: ((Int) -> Unit)? = null
    var goToLastHomePage: (() -> Unit)? = null
    /** Şu an Uygulama Arşivi sayfasında mıyız (arama hareketi orada kapalı). */
    var onLibrary: Boolean = false
}

/**
 * Ana ekran kökü: içerik + üst katmanlar (arama, kontrol paneli, ayarlar, klasör, bağlam menüsü,
 * arşiv kategorisi) + sürüklenen simgenin hayaleti.
 * Hareketler: uzun bas = menü/düzenleme, sürükle = taşı, aşağı kaydır = arama, üstten aşağı = panel.
 */
@Composable
fun HomeScreen(
    vm: LauncherViewModel,
    isDefaultHome: Boolean,
    onRequestDefault: () -> Unit,
    openSettingsRequest: Int = 0,
) {
    val layout by vm.layout.collectAsStateWithLifecycle()
    val byKey by vm.appsByKey.collectAsStateWithLifecycle()
    val allApps by vm.apps.collectAsStateWithLifecycle()
    val recents by vm.recentApps.collectAsStateWithLifecycle()
    val groups by vm.libraryGroups.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wallpaper by vm.wallpaper.collectAsStateWithLifecycle()
    val requester = LocalWidgetRequester.current

    val drag = remember { DragState() }
    val haptic = LocalHapticFeedback.current
    val bridge = remember { DropBridge() }
    val folderPages = remember { mutableStateMapOf<String, Int>() }

    var overlay by remember { mutableStateOf(Overlay.None) }
    var editMode by remember { mutableStateOf(false) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var folderOrigin by remember { mutableStateOf(TransformOrigin.Center) }
    var menu by remember { mutableStateOf<MenuRequest?>(null) }
    var group by remember { mutableStateOf<LibraryGroup?>(null) }
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

    fun closeOverlay() {
        overlay = Overlay.None
        openFolderId = null
        menu = null
    }

    BackHandler(enabled = true) {
        if (overlay != Overlay.None) closeOverlay() else if (editMode) editMode = false
    }

    LaunchedEffect(openSettingsRequest) {
        if (openSettingsRequest > 0) {
            closeOverlay()
            overlay = Overlay.Settings
        }
    }

    // Görsel seçiciler (izin gerektirmez): özel simge ve fotoğraf widget'ı için.
    var iconTarget by remember { mutableStateOf<String?>(null) }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val app = iconTarget?.let { byKey[it] }
        if (uri != null && app != null) vm.setCustomIcon(app, uri)
        iconTarget = null
    }
    var photoTarget by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val w = photoTarget?.let { id -> vm.layout.value.widgets.firstOrNull { it.id == id } }
        if (uri != null && w != null) vm.setWidgetPhoto(w, uri)
        photoTarget = null
    }

    LaunchedEffect(vm) {
        vm.homePressed.collect {
            closeOverlay()
            editMode = false
            bridge.goToPage?.invoke(0)
        }
    }

    SideEffect {
        drag.activeLayer = if (overlay == Overlay.Folder || overlay == Overlay.Group) DragLayer.Folder else DragLayer.Home
    }

    val density = LocalDensity.current
    val topInsetPx = WindowInsets.statusBars.getTop(density)
    val topZonePx = topInsetPx + with(density) { 72.dp.toPx() }
    val suggestions = remember(recents, allApps) { (recents + allApps).distinctBy { it.key }.take(4) }

    // Kapanış animasyonu sırasında son klasör/kategori/menü içeriği ekranda kalsın diye tutulur.
    val folderHolder = remember { arrayOfNulls<FolderItem>(1) }
    val openFolder = layout.items.firstOrNull { it.id == openFolderId } as? FolderItem
    if (openFolder != null) folderHolder[0] = openFolder
    val groupHolder = remember { arrayOfNulls<LibraryGroup>(1) }
    if (group != null) groupHolder[0] = group
    val menuHolder = remember { arrayOfNulls<MenuRequest>(1) }
    if (menu != null) menuHolder[0] = menu

    fun openFolderAt(f: FolderItem, center: Offset) {
        folderOrigin = TransformOrigin(
            center.x / rootSize.width.coerceAtLeast(1),
            center.y / rootSize.height.coerceAtLeast(1),
        )
        openFolderId = f.id
        overlay = Overlay.Folder
    }

    val dark = when (settings.theme) {
        ThemeMode.Auto -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(
        LocalLauncherSettings provides settings,
        LocalGlass provides GlassLook(dark = dark, reduced = settings.lowPower),
    ) {
    Box(
        Modifier
            .fillMaxSize()
            // Uzun basma + sürükleme tek yerde, kök katmanda algılanır (bkz. detectHomeDrag).
            .pointerInput(drag) {
                detectHomeDrag(
                    drag = drag,
                    enabled = {
                        overlayNow == Overlay.None || overlayNow == Overlay.Folder || overlayNow == Overlay.Group
                    },
                    isEditing = { editNow },
                    onLongPressEmpty = {
                        if (overlayNow == Overlay.None && !editNow && !bridge.onLibrary) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            editMode = true
                        }
                    },
                    onLifted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDragStarted = {
                        editMode = true
                        val k = drag.key
                        if (k != null && (overlayNow == Overlay.Group || bridge.onLibrary)) {
                            // Arşivden sürüklenen uygulama ana ekrana taşınır (iOS gibi arşiv kayar).
                            if (overlayNow == Overlay.Group) closeOverlay()
                            drag.redirectDrop { p, h -> bridge.home?.invoke(k, p, h) }
                            bridge.goToLastHomePage?.invoke()
                        }
                    },
                    onMenu = { t ->
                        openFolderId = null
                        menu = MenuRequest(t.itemId, t.place, t.iconRect, t.cellRect, t.iconDp)
                        overlay = Overlay.Menu
                    },
                )
            }
            .onSizeChanged { rootSize = it }
            .pointerInput(topZonePx) {
                var startY = 0f
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { startY = it.y; total = 0f },
                    onDragEnd = {
                        if (overlayNow == Overlay.None && !editNow && !bridge.onLibrary && total > 80.dp.toPx()) {
                            overlay = if (startY < topZonePx) Overlay.Control else Overlay.Search
                        }
                        total = 0f
                    },
                    onDragCancel = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
    ) {
        // Launcher'ın kendi duvar kâğıdı (seçildiyse) sistem duvar kâğıdının üzerine çizilir.
        val wp = wallpaper
        if (settings.launcherWallpaper && wp != null) {
            Image(
                wp, null, Modifier.fillMaxSize().then(if (blur > 0.dp) Modifier.blur(blur) else Modifier),
                contentScale = ContentScale.Crop,
            )
            if (settings.wallpaperDim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.wallpaperDim)))
        }
        Box(Modifier.fillMaxSize().then(if (blur > 0.dp) Modifier.blur(blur) else Modifier)) {
            HomeContent(
                vm = vm,
                layout = layout,
                byKey = byKey,
                allApps = allApps,
                groups = groups,
                settings = settings,
                drag = drag,
                bridge = bridge,
                folderPages = folderPages,
                edit = editMode,
                jiggle = jiggle,
                isDefaultHome = isDefaultHome,
                onRequestDefault = onRequestDefault,
                onOpenSearch = { overlay = Overlay.Search },
                onOpenFolder = { f, c -> openFolderAt(f, c) },
                onOpenGroup = { g -> group = g; overlay = Overlay.Group },
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
            SettingsScreen(
                vm = vm, s = settings, isDefaultHome = isDefaultHome, onRequestDefault = onRequestDefault,
                onAddWidget = { overlay = Overlay.Widgets },
                onClose = { closeOverlay() },
            )
        }
        AnimatedVisibility(
            overlay == Overlay.Widgets,
            enter = slideInVertically { it } + fadeIn(tween(200)),
            exit = slideOutVertically { it } + fadeOut(tween(160)),
        ) {
            WidgetPicker(vm, settings.glassStrength, onClose = { closeOverlay() })
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
                    initialPage = folderPages[f.id] ?: 0,
                    onPageChanged = { folderPages[f.id] = it },
                    onRename = { name -> vm.editLayout { it.renameFolder(f.id, name) } },
                    onReorder = { key, idx -> vm.editLayout { it.reorderInFolder(f.id, key, idx) } },
                    onDropOutside = { key, pos, hover ->
                        // Klasör dışına bırakılan uygulama ana ekrana taşınır (bırakıldığı yere).
                        closeOverlay()
                        bridge.home?.invoke(key, pos, hover)
                    },
                    onDragLeftPanel = {
                        // iOS gibi: sürüklerken panelden çıkınca klasör kapanır, sürükleme ana ekranda sürer.
                        val k = drag.key
                        if (k != null) drag.redirectDrop { p, h -> bridge.home?.invoke(k, p, h) }
                        closeOverlay()
                    },
                    onClose = { closeOverlay() },
                )
            }
        }
        AnimatedVisibility(
            overlay == Overlay.Group,
            enter = scaleIn(tween(240), initialScale = 0.85f) + fadeIn(tween(200)),
            exit = scaleOut(tween(180), targetScale = 0.85f) + fadeOut(tween(160)),
        ) {
            groupHolder[0]?.let { g ->
                LibraryGroupOverlay(
                    group = g, vm = vm, settings = settings, drag = drag, jiggle = jiggle,
                    onDrop = { key, p, h -> bridge.home?.invoke(key, p, h) },
                    onClose = { closeOverlay() },
                )
            }
        }
        AnimatedVisibility(
            overlay == Overlay.Menu,
            enter = fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.92f),
            exit = fadeOut(tween(140)),
        ) {
            menuHolder[0]?.let { req ->
                val folder = layout.items.firstOrNull { it.id == req.itemId } as? FolderItem
                val widget = layout.items.firstOrNull { it.id == req.itemId } as? WidgetItem
                ContextMenuOverlay(
                    req = req,
                    vm = vm,
                    app = byKey[req.itemId],
                    folder = folder,
                    widget = widget,
                    hasCustomIcon = settings.customIcons.containsKey(req.itemId),
                    onHome = req.itemId in layout.homeKeys,
                    strength = settings.glassStrength,
                    iconGlass = settings.iconGlass,
                    byKey = byKey,
                    onEditHome = { editMode = true },
                    onRemoveFromHome = { vm.editLayout { it.removeFromHome(req.itemId) } },
                    onAddToHome = { vm.editLayout { it.addToHome(req.itemId) } },
                    onOpenFolder = { folder?.let { f -> openFolderAt(f, req.iconRect.center) } },
                    onDissolveFolder = { vm.editLayout { it.dissolveFolder(req.itemId) } },
                    onChangeIcon = {
                        iconTarget = req.itemId
                        iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onResetIcon = { byKey[req.itemId]?.let { vm.resetCustomIcon(it) } },
                    onResizeWidget = { sz -> vm.editLayout { it.resizeWidget(req.itemId, sz.spanX, sz.spanY) } },
                    onEditWidget = when {
                        widget == null -> null
                        widget.kind == WidgetKind.Photo -> {
                            {
                                photoTarget = widget.id
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }
                        widget.kind == WidgetKind.System && vm.widgetHost.isConfigurable(widget.appWidgetId) -> {
                            { requester?.reconfigure(widget.appWidgetId) }
                        }
                        else -> null
                    },
                    onRemoveWidget = { widget?.let { vm.removeWidget(it) } },
                    onClose = { if (overlay == Overlay.Menu) closeOverlay() },
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("+", settings.glassStrength) { overlay = Overlay.Widgets }
                    GlassButton("Ayarlar", settings.glassStrength) { overlay = Overlay.Settings }
                }
                GlassButton("Bitti", settings.glassStrength) { editMode = false }
            }
        }

        // Sürüklenen öğenin hayalet simgesi (tüm katmanların üstünde)
        val dk = drag.key
        if (dk != null) {
            val gs = drag.ghostSize
            val gw = with(density) { (gs?.width ?: drag.iconDp).toPx() }
            val gh = with(density) { (gs?.height ?: drag.iconDp).toPx() }
            Box(
                Modifier
                    .offset { IntOffset((drag.pos.x - gw / 2f).roundToInt(), (drag.pos.y - gh / 2f).roundToInt()) }
                    .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f; alpha = 0.94f }
            ) {
                val app = byKey[dk]
                if (gs != null) {
                    // Widget hayaleti: aynı boyutta cam kutu
                    Box(
                        Modifier.size(gs.width, gs.height)
                            .liquidGlass(RoundedCornerShape(widgetCorner(gs.width)), settings.glassStrength, 16.dp)
                    )
                } else if (app != null) {
                    AppIconImage(app, vm, drag.iconDp, settings.iconGlass, settings.glassStrength)
                } else {
                    (layout.items.firstOrNull { it.id == dk } as? FolderItem)?.let {
                        FolderIcon(it, byKey, vm, drag.iconDp, settings.glassStrength, folderPages[it.id] ?: 0)
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun GlassButton(text: String, strength: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .height(34.dp)
            .liquidGlass(CircleShape, strength, 4.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}
