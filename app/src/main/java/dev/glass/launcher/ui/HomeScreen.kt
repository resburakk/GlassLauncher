package dev.glass.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import dev.glass.launcher.service.SystemPanels
import android.provider.Settings
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import dev.glass.launcher.data.SwipeAction
import dev.glass.launcher.service.LockService
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
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

enum class Overlay { None, Search, Settings, Folder, Menu, Group, Widgets, LockPrompt }

/** İzin penceresinin hangi özellik için açıldığı. */
enum class LockReason { Lock, Panels }

/** Tüm ekranın (ve sistem duvar kâğıdının) bulanıklaştığı katmanlar; küçük menüler bu listede değildir. */
private val FullBlurOverlays = setOf(Overlay.Search, Overlay.Settings, Overlay.Folder, Overlay.Group, Overlay.Widgets)

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
    val blurred by vm.blurredWallpaper.collectAsStateWithLifecycle()
    val degraded by vm.degraded.collectAsStateWithLifecycle()
    val powerSave = rememberPowerSaveMode()
    val motion = Motion(if (settings.lowPower) maxOf(settings.animSpeed, 1.4f) else settings.animSpeed)
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
    // Klasör/arşiv kategorisi açıkken basılı tutulan simgenin menüsü (klasör açık kalır).
    var folderMenu by remember { mutableStateOf<MenuRequest?>(null) }
    var group by remember { mutableStateOf<LibraryGroup?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val overlayNow by rememberUpdatedState(overlay)
    val settingsNow by rememberUpdatedState(settings)
    val editNow by rememberUpdatedState(editMode)

    // Tam ekran bulanıklık yalnızca büyük katmanlarda; menü ve küçük pencerelerde yalnızca kendileri bulanık.
    val fullBlur = overlay in FullBlurOverlays
    val blurAmount by animateFloatAsState(if (fullBlur) 1f else 0f, motion.tween(280), label = "blurAmount")
    val blurK by rememberUpdatedState(settings.glassBlur)
    val blur = 22.dp * blurAmount * settings.glassBlur
    val scrim by animateFloatAsState(
        when {
            // Klasör iOS'taki gibi açık tonlu: arka plan bulanık ama neredeyse karartılmaz.
            overlay == Overlay.Folder || overlay == Overlay.Group -> 0.06f
            fullBlur -> 0.28f
            overlay == Overlay.Menu -> 0.18f
            overlay == Overlay.LockPrompt -> 0.10f
            else -> 0f
        },
        motion.tween(260), label = "scrim",
    )
    // Arşive geçiş oranı (HomeContent yazar); arka plan geçiş sırasında kademeli bulanıklaşır.
    val libProgress = remember { mutableFloatStateOf(0f) }
    val windowBlur = LocalWindowBlur.current
    LaunchedEffect(windowBlur) {
        // Sistem duvar kâğıdı: pencere arkası bulanıklığı (Android 12+, cihaz destekliyorsa).
        snapshotFlow { maxOf(blurAmount, libProgress.floatValue) }.collect { f -> windowBlur?.invoke(f * blurK) }
    }
    var lockReason by remember { mutableStateOf(LockReason.Lock) }

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
        folderMenu = null
    }

    BackHandler(enabled = true) {
        if (folderMenu != null) folderMenu = null
        else if (overlay != Overlay.None) closeOverlay() else if (editMode) editMode = false
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
    val context = LocalContext.current
    val statusTopDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    // Ana ekran ölçüleri (klasör, arama ve arşiv katmanları da aynı simge boyutunu kullanır).
    val homeMetrics = if (rootSize.width > 0) with(density) {
        HomeMetrics.compute(rootSize.width.toDp(), rootSize.height.toDp(), statusTopDp, settings)
    } else null
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
    // Otomatik kalite: Pil Tasarrufu açıksa veya kaydırmada kare süreleri uzunsa efektler sadeleşir.
    val reduced = settings.lowPower || (settings.autoQuality && (powerSave || degraded))
    val useBackdrop = settings.launcherWallpaper && settings.realBlur && !reduced
    // Tanı kaydı (yalnızca durum değişince): kalite düşürme nedenleri ve pencere bulanıklığı desteği.
    LaunchedEffect(reduced, powerSave, degraded) {
        android.util.Log.i(
            "GlassDiag",
            "reduced=$reduced lowPower=${settings.lowPower} autoQuality=${settings.autoQuality} " +
                "powerSave=$powerSave degraded=$degraded " +
                "crossWindowBlur=${context.getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled}",
        )
    }
    CompositionLocalProvider(
        LocalLauncherSettings provides settings,
        LocalMotion provides motion,
        LocalPt provides HomeMetrics.pt(LocalConfiguration.current.screenWidthDp.dp),
        LocalHomeMetrics provides homeMetrics,
        LocalGlass provides GlassLook(
            dark = dark,
            reduced = reduced,
            backdrop = if (useBackdrop) blurred else null,
            rootW = rootSize.width.toFloat(),
            rootH = rootSize.height.toFloat(),
        ),
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
                    onDragStarted = {
                        // Menü açıkken parmak kaydırıldı: menü kapanır, sürükleme başlar (iOS gibi).
                        if (overlayNow == Overlay.Menu) closeOverlay()
                        folderMenu = null
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
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val req = MenuRequest(t.itemId, t.place, t.iconRect, t.cellRect, t.iconDp)
                        if (overlayNow == Overlay.Folder || overlayNow == Overlay.Group) {
                            // Klasör açık kalır; menü onun üstünde açılır.
                            folderMenu = req
                        } else {
                            openFolderId = null
                            menu = req
                            overlay = Overlay.Menu
                        }
                    },
                )
            }
            .onSizeChanged { rootSize = it }
            // Boş alana çift dokunma: ekranı kilitle (ayarlardan kapatılabilir).
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    val st = settingsNow
                    if (overlayNow == Overlay.None && !editNow && !bridge.onLibrary && st.doubleTapLock) {
                        if (!LockService.lock()) {
                            lockReason = LockReason.Lock
                            overlay = Overlay.LockPrompt
                        }
                    }
                })
            }
            .pointerInput(topZonePx) {
                var startY = 0f
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { startY = it.y; total = 0f },
                    onDragEnd = {
                        if (overlayNow == Overlay.None && !editNow && !bridge.onLibrary && total > 80.dp.toPx()) {
                            // Hangi hareketin ne açacağı Ayarlar > Hareketler'den seçilir.
                            val action = if (startY < topZonePx) settingsNow.topSwipe else settingsNow.midSwipe
                            when (action) {
                                // Telefonun KENDİ Kontrol Merkezi / bildirimleri.
                                SwipeAction.ControlCenter -> if (!SystemPanels.openControlCenter(context)) {
                                    lockReason = LockReason.Panels
                                    overlay = Overlay.LockPrompt
                                }
                                SwipeAction.Notifications -> if (!SystemPanels.openNotifications(context)) {
                                    lockReason = LockReason.Panels
                                    overlay = Overlay.LockPrompt
                                }
                                SwipeAction.Search -> overlay = Overlay.Search
                                SwipeAction.None -> Unit
                            }
                        }
                        total = 0f
                    },
                    onDragCancel = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
    ) {
        // İçerik katmanı kaydedilir: üstteki menü/düğmeler bunu bulanıklaştırıp yalnızca kendi arkalarında gösterir.
        val glassRegions = remember { GlassRegions() }
        val maxBlurPx = with(density) { 22.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                // Menü/düğme alanlarına içeriğin bulanık kopyası çizilir (yalnızca kendi arkaları bulanık).
                .glassRegionBlur(glassRegions, 30.dp * settings.glassBlur)
        ) {
        // Launcher'ın kendi duvar kâğıdı (seçildiyse) sistem duvar kâğıdının üzerine çizilir.
        val wp = wallpaper
        if (settings.launcherWallpaper && wp != null) {
            Image(
                wp, null,
                Modifier.fillMaxSize().graphicsLayer {
                    val r = maxBlurPx * maxOf(blurAmount, libProgress.floatValue) * settings.glassBlur
                    renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                },
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
                libProgress = libProgress,
            )
        }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))

        CompositionLocalProvider(LocalGlassRegions provides glassRegions) {

        AnimatedVisibility(
            overlay == Overlay.Search,
            enter = fadeIn(motion.tween(180)) + scaleIn(motion.spring(), initialScale = 0.96f),
            exit = fadeOut(motion.tween(140)),
        ) {
            SearchOverlay(vm, allApps, suggestions, settings.glassStrength, settings.iconGlass, onClose = { closeOverlay() })
        }
        AnimatedVisibility(
            overlay == Overlay.Settings,
            enter = slideInVertically(motion.spring(0.9f, 400f)) { it } + fadeIn(motion.tween(200)),
            exit = slideOutVertically(motion.tween(220)) { it } + fadeOut(motion.tween(160)),
        ) {
            SettingsScreen(
                vm = vm, s = settings, isDefaultHome = isDefaultHome, onRequestDefault = onRequestDefault,
                onAddWidget = { overlay = Overlay.Widgets },
                onClose = { closeOverlay() },
            )
        }
        AnimatedVisibility(
            overlay == Overlay.Widgets,
            enter = slideInVertically(motion.spring(0.9f, 400f)) { it } + fadeIn(motion.tween(200)),
            exit = slideOutVertically(motion.tween(220)) { it } + fadeOut(motion.tween(160)),
        ) {
            WidgetPicker(vm, settings.glassStrength, onClose = { closeOverlay() })
        }
        AnimatedVisibility(
            overlay == Overlay.Folder && openFolder != null,
            // iOS gibi: klasör simgesinin yerinden yayla büyüyerek açılır, kapanırken oraya küçülür.
            enter = scaleIn(motion.bouncy(), initialScale = 0.2f, transformOrigin = folderOrigin) + fadeIn(motion.tween(180)),
            exit = scaleOut(motion.tween(220), targetScale = 0.2f, transformOrigin = folderOrigin) + fadeOut(motion.tween(200)),
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
            enter = scaleIn(motion.bouncy(), initialScale = 0.85f) + fadeIn(motion.tween(200)),
            exit = scaleOut(motion.tween(180), targetScale = 0.85f) + fadeOut(motion.tween(160)),
        ) {
            groupHolder[0]?.let { g ->
                LibraryGroupOverlay(
                    group = g, vm = vm, settings = settings, drag = drag, jiggle = jiggle,
                    onDrop = { key, p, h -> bridge.home?.invoke(key, p, h) },
                    onClose = { closeOverlay() },
                )
            }
        }
        // Klasörün üstünde açılan menü: arka plan hafifçe kararır, simge klasördeki yerinde kalır.
        val folderMenuHolder = remember { arrayOfNulls<MenuRequest>(1) }
        if (folderMenu != null) folderMenuHolder[0] = folderMenu
        AnimatedVisibility(
            folderMenu != null,
            enter = fadeIn(motion.tween(160)),
            exit = fadeOut(motion.tween(140)),
        ) {
            folderMenuHolder[0]?.let { req ->
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                    ContextMenuOverlay(
                        req = req,
                        vm = vm,
                        app = byKey[req.itemId],
                        folder = null,
                        widget = null,
                        hasCustomIcon = settings.customIcons.containsKey(req.itemId),
                        onHome = req.itemId in layout.homeKeys,
                        strength = settings.glassStrength,
                        iconGlass = settings.iconGlass,
                        byKey = byKey,
                        onEditHome = { editMode = true },
                        onRemoveFromHome = { vm.editLayout { it.removeFromHome(req.itemId) } },
                        onAddToHome = { vm.editLayout { it.addToHome(req.itemId) } },
                        onOpenFolder = {},
                        onDissolveFolder = {},
                        onChangeIcon = {
                            iconTarget = req.itemId
                            iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onResetIcon = { byKey[req.itemId]?.let { vm.resetCustomIcon(it) } },
                        onResizeWidget = {},
                        onEditWidget = null,
                        onRemoveWidget = {},
                        onClose = { folderMenu = null },
                        onRemoveFromFolder = { vm.editLayout { it.takeOutOfFolder(req.itemId) } },
                        overPanel = true,
                    )
                }
            }
        }
        AnimatedVisibility(
            overlay == Overlay.Menu,
            enter = fadeIn(motion.tween(160)) + scaleIn(motion.bouncy(), initialScale = 0.9f),
            exit = fadeOut(motion.tween(140)),
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

        AnimatedVisibility(
            overlay == Overlay.LockPrompt,
            enter = fadeIn(motion.tween(160)) + scaleIn(motion.bouncy(), initialScale = 0.9f),
            exit = fadeOut(motion.tween(140)),
        ) {
            LockPrompt(
                reason = lockReason,
                strength = settings.glassStrength,
                onOpenSettings = {
                    closeOverlay()
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onDisable = {
                    closeOverlay()
                    vm.updateSettings { it.copy(doubleTapLock = false) }
                },
                onClose = { closeOverlay() },
            )
        }

        // Düzenleme modu düğmeleri: durum çubuğu ile ilk simge satırının TAM ortasında, köşelerinden
        // yayla açılır; arkaları yalnızca kendileri bulanık cam.
        val btnH = 30.dp
        val btnY = homeMetrics?.let { (statusTopDp + it.firstTop) / 2f - btnH / 2f } ?: (statusTopDp + 8.dp)
        val showEditBar = editMode && overlay == Overlay.None
        AnimatedVisibility(
            showEditBar,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 16.dp, y = btnY),
            enter = scaleIn(motion.bouncy(), initialScale = 0.4f, transformOrigin = TransformOrigin(0f, 0f)) + fadeIn(motion.tween(160)),
            exit = scaleOut(motion.tween(160), targetScale = 0.6f, transformOrigin = TransformOrigin(0f, 0f)) + fadeOut(motion.tween(140)),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("+", settings.glassStrength, btnH) { overlay = Overlay.Widgets }
                GlassButton("Ayarlar", settings.glassStrength, btnH) { overlay = Overlay.Settings }
            }
        }
        AnimatedVisibility(
            showEditBar,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = btnY),
            enter = scaleIn(motion.bouncy(), initialScale = 0.4f, transformOrigin = TransformOrigin(1f, 0f)) + fadeIn(motion.tween(160)),
            exit = scaleOut(motion.tween(160), targetScale = 0.6f, transformOrigin = TransformOrigin(1f, 0f)) + fadeOut(motion.tween(140)),
        ) {
            GlassButton("Bitti", settings.glassStrength, btnH) { editMode = false }
        }
        }

        // Sürüklenen öğenin hayalet simgesi (tüm katmanların üstünde)
        val dk = drag.key
        if (dk != null && drag.moved) {
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
internal fun GlassButton(text: String, strength: Float, height: Dp = 30.dp, onClick: () -> Unit) {
    Box(Modifier.height(height), contentAlignment = Alignment.Center) {
    BlurBackdrop(height / 2f, Modifier.matchParentSize())
    Box(
        Modifier
            .height(height)
            .liquidGlass(CircleShape, strength, 6.dp, panel = true)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            style = LocalTextStyle.current.copy(shadow = IosTextShadow),
        )
    }
    }
}

/** Telefonun Pil Tasarrufu modu (değişince güncellenir; yalnızca launcher ekrandayken dinlenir). */
@Composable
private fun rememberPowerSaveMode(): Boolean {
    val ctx = LocalContext.current
    val pm = remember { ctx.getSystemService(PowerManager::class.java) }
    var on by remember { mutableStateOf(pm.isPowerSaveMode) }
    DisposableEffect(ctx) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { on = pm.isPowerSaveMode }
        }
        ctx.registerReceiver(r, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return on
}

/** Çift dokunarak kilitleme için Erişilebilirlik izni yoksa gösterilen açıklama penceresi. */
@Composable
private fun LockPrompt(
    reason: LockReason,
    strength: Float,
    onOpenSettings: () -> Unit,
    onDisable: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .liquidGlass(RoundedCornerShape(28.dp), strength, 18.dp, panel = true)
                .background(Color(0x40000000))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
        ) {
            Text(
                if (reason == LockReason.Lock) "Çift dokunarak kilitle" else "Kontrol Merkezi",
                color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                (if (reason == LockReason.Lock)
                    "Telefonu kilitleyebilmek için Android, Glass Launcher'ın Erişilebilirlik hizmetini açmanızı istiyor.\n\n"
                else
                    "Telefonun kendi Kontrol Merkezi'ni açabilmek için Android, Glass Launcher'ın Erişilebilirlik hizmetini açmanızı istiyor.\n\n") +
                    "Ayarlar > Erişilebilirlik > Yüklü uygulamalar (veya İndirilen uygulamalar) bölümünde " +
                    "\"Glass Launcher – Kilit ve Kontrol Merkezi\"ni açın.\n\n" +
                    "Bu hizmet ekran içeriğini okumaz ve veri toplamaz; yalnızca kilitleme ve panel açma komutlarını verir. " +
                    "Parmak izi ve yüz tanıma ile açma çalışmaya devam eder.",
                color = Color(0xFFE5E5EA), fontSize = 14.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A84FF))
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) { Text("Erişilebilirlik ayarlarını aç", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bu özelliği kapat", color = Color(0xFFFF453A), fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDisable).padding(8.dp))
                Text("Vazgeç", color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            }
        }
    }
}
