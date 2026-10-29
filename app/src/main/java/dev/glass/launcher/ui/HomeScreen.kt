package dev.glass.launcher.ui

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.derivedStateOf

import androidx.compose.ui.draw.drawBehind

import androidx.compose.ui.graphics.asImageBitmap

import androidx.compose.ui.graphics.asAndroidBitmap

import androidx.compose.runtime.produceState

import kotlinx.coroutines.delay

import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.ContentScale
import dev.glass.launcher.data.ThemeMode
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glass.launcher.data.FolderItem
import dev.glass.launcher.data.AppItem
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.LauncherSettings
import kotlin.math.roundToInt

enum class Overlay { None, Search, Settings, Folder, Menu, Group, Widgets, LockPrompt }

/** İzin penceresinin hangi özellik için açıldığı. */
enum class LockReason { Lock, Panels }

/** Tüm ekranın (ve sistem duvar kâğıdının) bulanıklaştığı katmanlar; küçük menüler bu listede değildir. */
// Ayarlar opak bir sayfa: arkası bulanıklaştırılmaz (boşuna GPU yükü, 120 Hz kaydırmayı bozuyordu).
private val FullBlurOverlays = setOf(Overlay.Search, Overlay.Folder, Overlay.Group, Overlay.Widgets)

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
    /** Görünen ana ekran sayfasındaki öğelerin kimlikleri ("Bu Sayfayı Sil" için). */
    var currentPageIds by mutableStateOf<List<String>>(emptyList())
    /** Ana ekran sayfa sayısı. */
    var homePageCount by mutableStateOf(1)
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
    /** Ayarlardaki "Widget ekle" ile ana ekrana dönüldü: widget galerisi açılır. */
    addWidgetRequest: Int = 0,
    /** Son uygulamalar ekranına geçiş (0..1): simgeler solar, duvar kâğıdı kalır. */
    homeHide: () -> Float = { 0f },
) {
    val layout by vm.layout.collectAsStateWithLifecycle()
    val byKey by vm.appsByKey.collectAsStateWithLifecycle()
    val allApps by vm.apps.collectAsStateWithLifecycle()
    val recents by vm.suggestedApps.collectAsStateWithLifecycle()
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
    val selection = remember { SelectionState() }
    // Düzenleme modundan çıkınca seçim de biter.
    LaunchedEffect(editMode) { if (!editMode) selection.end() }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var folderOrigin by remember { mutableStateOf(TransformOrigin.Center) }
    var menu by remember { mutableStateOf<MenuRequest?>(null) }
    // Klasör/arşiv kategorisi açıkken basılı tutulan simgenin menüsü (klasör açık kalır).
    var folderMenu by remember { mutableStateOf<MenuRequest?>(null) }
    var group by remember { mutableStateOf<LibraryGroup?>(null) }
    // Widget sayfası (iOS Bugün görünümü): ilk sayfanın solundan kayarak açılır.
    val today = remember { TodayState() }
    val todayScope = rememberCoroutineScope()
    val todayVisible by remember { derivedStateOf { today.progress > 0.001f } }
    val todayOpenNow by remember { derivedStateOf { today.progress > 0.5f } }
    LaunchedEffect(today) { snapshotFlow { today.progress > 0.5f }.collect { vm.todayOpen = it } }
    // Widget sayfası durduktan 320 ms sonra geçiş bitmiş sayılır (ana ekran sayfa kaydırmasıyla aynı bekleme).
    LaunchedEffect(today) {
        snapshotFlow { today.progress > 0.001f && today.progress < 0.999f }.collectLatest { m ->
            if (m) today.hold = true else { kotlinx.coroutines.delay(320); today.hold = false }
        }
    }
    // Arşiv kategorisi, dokunulan kartın yerinden büyüyerek açılır ve oraya küçülerek kapanır.
    var groupOrigin by remember { mutableStateOf(TransformOrigin.Center) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val overlayNow by rememberUpdatedState(overlay)
    val settingsNow by rememberUpdatedState(settings)
    val editNow by rememberUpdatedState(editMode)

    // Tam ekran bulanıklık yalnızca büyük katmanlarda; menü ve küçük pencerelerde yalnızca kendileri bulanık.
    val fullBlur = overlay in FullBlurOverlays
    // Şeffaf camda açık klasörün arkası daha az bulanır: panel kenarındaki mercek görünür olsun (iOS 26).
    // (Arşiv kategorisi panelsiz, tam ekran: arkası her zaman tam bulanık — iOS 26.)
    // Klasör açıkken de arşivdeki gibi TAM bulanıklık: şeffaf stilde %45'e düşürülüyordu; simgeler kendi
    // bulanıklıklarıyla yine çok bulanıkken duvar kâğıdı neredeyse net kalıyor, arka plan tutarsız görünüyordu.
    val blurAmount by animateFloatAsState(if (fullBlur) 1f else 0f, motion.tween(280), label = "blurAmount")
    val blurK by rememberUpdatedState(settings.glassBlur)
    // Aramaya çekme (iOS gibi parmağı izler): 0 = yok, 1 = açılma eşiği; eşikten sonra dirençle biraz daha uzar.
    val pull = remember { androidx.compose.animation.core.Animatable(0f) }
    val pullScope = rememberCoroutineScope()
    val pullOn by remember { derivedStateOf { pull.value > 0.001f } }
    LaunchedEffect(Unit) {
        snapshotFlow { today.progress > 0.001f || pullOn }.collect { GlassMotion.homeShift = it }
    }
    // (Bulanıklık yarıçapı çizim katmanında okunur: geçiş sırasında ana ekran her karede yeniden oluşturulmasın.)
    val blurPxMax = with(LocalDensity.current) { 22.dp.toPx() } * settings.glassBlur
    // Arama (iOS Spotlight) arkası çok daha bulanık: bu kat sayı son açılan tam bulanık katmana göre tutulur
    // (kapanırken birden düşmesin).
    var blurBoost by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(overlay) { if (overlay in FullBlurOverlays) blurBoost = if (overlay == Overlay.Search) 2.2f else 1f }
    val scrim by animateFloatAsState(
        when {
            // Klasör iOS'taki gibi açık tonlu: arka plan bulanık ama neredeyse karartılmaz.
            overlay == Overlay.Folder -> 0.06f
            // Arşiv kategorisi tam ekran açılır: iOS 26'daki gibi arka plan bulanık ve biraz koyulaşmış.
            overlay == Overlay.Group -> 0.22f
            // Arama (iOS Spotlight gibi): koyuluk bulanıklıktan gelir, karartma çok hafif.
            overlay == Overlay.Search -> 0.16f
            fullBlur -> 0.28f
            // Basılı tutma menüsü: karartma yok (açılıp kapanırken ekran kararıp aydınlanıyor gibi görünüyordu).
            overlay == Overlay.Menu -> 0f
            overlay == Overlay.LockPrompt -> 0.10f
            else -> 0f
        },
        // Menü kapanırken karartma menüyle aynı sürede kalkar.
        motion.tween(if (overlay == Overlay.None) 200 else 260), label = "scrim",
    )
    // Arşive geçiş oranı (HomeContent yazar); arka plan geçiş sırasında kademeli bulanıklaşır.
    val libProgress = remember { mutableFloatStateOf(0f) }
    val windowBlur = LocalWindowBlur.current
    // Launcher'ın kendi duvar kâğıdı kopyası çiziliyorsa sistem duvar kâğıdı zaten görünmez: pencere arkası
    // bulanıklığı gereksiz. Her yarıçap değişimi pencere yerleşimini yeniden tetiklediği için açılış
    // animasyonlarını 120 Hz'in altına düşürüyordu; bu durumda hiç kullanılmaz.
    val copyCovers by rememberUpdatedState(settings.launcherWallpaper && wallpaper != null)
    val homeBlurNow by rememberUpdatedState(settings.homeWallpaperBlur)
    // Arama paneli/çubuğunun arkası da cam ayarlarına bağlı: Bulanıklık şiddeti ve cam stili (şeffafta daha az).
    val strongBlurR = (if (settings.glassStyle == "clear") 28.dp else 56.dp) * settings.glassBlur
    LaunchedEffect(windowBlur) {
        // Sistem duvar kâğıdı: pencere arkası bulanıklığı (Android 12+, cihaz destekliyorsa).
        // Son uygulamalar açılırken (ana ekran soluyor) pencere bulanıklığı da kalkar: küçültülen pencerede dikdörtgen olmasın.
        snapshotFlow { if (copyCovers || homeHide() > 0.01f) 0f else maxOf(blurAmount * blurBoost, libProgress.floatValue, homeBlurNow) }
            .collect { f -> windowBlur?.invoke(f * blurK) }
    }
    var lockReason by remember { mutableStateOf(LockReason.Lock) }

    // Sallanma animasyonu yalnızca düzenleme modunda çalışır (pil).
    val jiggleRaw: State<Float> = if (editMode) {
        rememberInfiniteTransition(label = "jiggle").animateFloat(
            initialValue = -1.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
            label = "jiggleAngle",
        )
    } else remember { mutableStateOf(0f) }
    // Ana ekran bir katmanın ("+" menüsü, klasör, menü) arkasında bulanık kalırken simgeler sallanmaz: sallanan simgeler
    // arkadaki bulanıklığı her karede baştan hesaplatıyordu ("+" menüsü 59 Hz). Katman kapanınca sallanma sürer.
    val jiggle: State<Float> = remember(jiggleRaw) { derivedStateOf { if (LiveMotion.covered) 0f else jiggleRaw.value } }
    LaunchedEffect(overlay) { LiveMotion.overlay = overlay != Overlay.None }

    fun closeOverlay() {
        // Klasör kapanınca bir sonraki açılışta ve küçük simgede ilk sayfa görünür (iOS 26).
        openFolderId?.let { folderPages[it] = 0 }
        overlay = Overlay.None
        openFolderId = null
        menu = null
        folderMenu = null
    }

    var wallpaperSheet by remember { mutableStateOf(false) }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setWallpaper(uri, dev.glass.launcher.data.WallpaperTarget.of(settings.wallpaperTarget))
    }

    BackHandler(enabled = true) {
        if (wallpaperSheet) wallpaperSheet = false
        else if (folderMenu != null) folderMenu = null
        else if (overlay != Overlay.None) closeOverlay()
        else if (selection.active) selection.end()
        else if (editMode) editMode = false
        else if (today.progress > 0f) today.close(todayScope)
    }

    // Ayarlar ayrı bir uygulama ekranında açılır (alttan kaydırma/geri onu kapatır, son uygulamalarda kart olur).
    val settingsCtx = LocalContext.current
    fun openSettings() {
        runCatching {
            settingsCtx.startActivity(
                android.content.Intent(settingsCtx, dev.glass.launcher.SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    LaunchedEffect(openSettingsRequest) {
        if (openSettingsRequest > 0) {
            closeOverlay()
            openSettings()
        }
    }
    LaunchedEffect(addWidgetRequest) {
        if (addWidgetRequest > 0) {
            closeOverlay()
            overlay = Overlay.Widgets
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

    // --- Dönüş efekti: uygulamadan ana ekrana dönünce kart simgenin yerine küçülür ---
    var returnInfo by remember { mutableStateOf<ReturnInfo?>(null) }
    val returnAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumeTick) {
        if (resumeTick == 0) return@LaunchedEffect
        val rec = vm.consumeLastLaunch() ?: return@LaunchedEffect
        if (!settingsNow.appTransitions || rootSize.width == 0) return@LaunchedEffect
        val app = vm.appsByKey.value[rec.key] ?: return@LaunchedEffect
        val color = vm.iconColor(app, settingsNow)
        androidx.compose.runtime.withFrameNanos { } // konumlar güncellensin
        // Hedef: simge ana ekranda/dock'ta ise orası; bir klasördeyse klasörün simgesi; yoksa açıldığı yer.
        val folder = vm.layout.value.items.filterIsInstance<FolderItem>().firstOrNull { rec.key in it.keys }
        val r = drag.homeIconRect(rec.key) ?: folder?.let { drag.homeIconRect(it.id) } ?: rec.rect
        val w = rootSize.width.toFloat(); val h = rootSize.height.toFloat()
        val onScreen = r != null && r.left >= -2f && r.right <= w + 2f && r.top >= -2f && r.bottom <= h + 2f
        val iconPx = r?.width?.takeIf { it > 1f } ?: (w * 0.16f)
        val target = if (onScreen) r!! else androidx.compose.ui.geometry.Rect(
            w / 2f - iconPx / 2f, h / 2f - iconPx / 2f, w / 2f + iconPx / 2f, h / 2f + iconPx / 2f,
        )
        returnInfo = ReturnInfo(app, target, color, onScreen)
        returnAnim.snapTo(0f)
        returnAnim.animateTo(
            1f, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 260f * motion.speed),
        )
        returnInfo = null
    }

    LaunchedEffect(vm) {
        vm.homePressed.collect {
            closeOverlay()
            editMode = false
            today.close(todayScope)
            bridge.goToPage?.invoke(0)
        }
    }

    SideEffect {
        today.widthPx = rootSize.width.toFloat()
        drag.activeLayer = if (overlay == Overlay.Folder || overlay == Overlay.Group) DragLayer.Folder else DragLayer.Home
    }

    val density = LocalDensity.current
    val context = LocalContext.current
    val statusTopDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    // Ana ekran ölçüleri (klasör, arama ve arşiv katmanları da aynı simge boyutunu kullanır).
    val homeMetrics = if (rootSize.width > 0) with(density) {
        HomeMetrics.compute(rootSize.width.toDp(), rootSize.height.toDp(), statusTopDp, settings, navButtonsDp())
    } else null
    val topInsetPx = WindowInsets.statusBars.getTop(density)
    val topZonePx = topInsetPx + with(density) { 72.dp.toPx() }
    // Öneriler: 8'e kadar (panel aşağı çekilince ikinci sıra açılır).
    val suggestions = remember(recents, allApps) { (recents + allApps).distinctBy { it.key }.take(8) }

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
    // Pil tasarrufu / düşük güç: tüm efektler sadeleşir. Kaydırmada yavaşlık: yalnızca kırılma kapanır,
    // bulanıklık (ucuz) kalır.
    val reduced = settings.lowPower || (settings.autoQuality && powerSave)
    val noLens = settings.autoQuality && degraded
    SideEffect {
        GlassStatus.text = when {
            settings.lowPower -> "Düşük güç modu açık: cam efektleri sadeleştirildi."
            settings.autoQuality && powerSave -> "Telefonun Pil Tasarrufu açık: cam efektleri sadeleştirildi."
            noLens -> "Kaydırmada yavaşlık algılandı: bu oturumda kenar kırılması kapalı (bulanıklık açık)."
            !settings.launcherWallpaper -> "Launcher duvar kâğıdı yok: dock ve klasörlerde bulanıklık yok."
            blurred == null -> "Bulanık kopya henüz hazır değil."
            else -> "Tam: bulanıklık ve kırılma açık."
        }
    }
    // Düşük güçte de hazır (bir kez bulanıklaştırılmış) kopya kullanılır: bütün cam yüzeyler aynı kuralla, okunur
    // çizilir (gerçek zamanlı bulanıklık ve kırılma kapalı). Net kopya (şeffaf cam) düşük güçte kullanılmaz.
    val useBackdrop = settings.launcherWallpaper && settings.realBlur
    val clearGlass = settings.glassStyle == "clear"
    // Şeffaf cam: duvar kâğıdının net kopyası (doku olarak; kendi boyutuyla eşlenir).
    val sharpBackdrop = remember(wallpaper) { wallpaper?.let { dev.glass.launcher.data.Blurred(it, it.width, it.height) } }
    // Şeffaf camın "hafif yumuşak" net kopyası BİR KEZ hazırlanır (yarım ekran çözünürlüğü): önceden her karede her
    // cam pikselinde tam çözünürlüklü duvar kâğıdından 9 örnek okunuyordu; widget sayfası geçişini 60 Hz'e düşürüyordu.
    // Arşiv kartlarının hazır bulanık kopyası gibi tek okuma; görünüm aynı.
    val softPxNow = with(LocalDensity.current) { 2.5.dp.toPx() }
    val softSharp by produceState<dev.glass.launcher.data.Blurred?>(null, wallpaper, rootSize) {
        val w = wallpaper
        value = if (w == null) null else softWallpaperCopy(w, softPxNow, rootSize)?.let { dev.glass.launcher.data.Blurred(it, w.width, w.height) }
    }
    // Ana ekran bulanıklığı açıkken camların (dock, Ara, klasör simgeleri) içi de en az arkadaki duvar kâğıdı
    // kadar bulanık olmalı; yoksa cam arkasını netleştiriyormuş gibi görünüyordu. Bulanık kopya ekranla aynı
    // oranda ek bulanıklaştırılır (kaydırıcı 0,1'lik adımlarla; arka planda hazırlanır).
    val homeBlurStep = kotlin.math.round(settings.homeWallpaperBlur * 10f) / 10f
    val homeBlurScreenPx = with(density) { 28.dp.toPx() } * homeBlurStep
    val homeBackdrop by produceState<dev.glass.launcher.data.Blurred?>(null, blurred, homeBlurStep, rootSize) {
        val b = blurred
        if (b == null || homeBlurStep < 0.05f || rootSize.width == 0) { value = null; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val src = b.image.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                // Ekrandaki 1 piksel, küçük kopyada kaç piksel? (duvar kâğıdı ekranı kırparak doldurur)
                val crop = maxOf(rootSize.width.toFloat() / b.srcW, rootSize.height.toFloat() / b.srcH)
                val screenPerSmall = crop * b.srcW / src.width
                val r = (homeBlurScreenPx / screenPerSmall * 0.6f).toInt().coerceIn(1, 24)
                dev.glass.launcher.data.BlurUtil.boxBlur(src, r, 3)
                dev.glass.launcher.data.Blurred(src.asImageBitmap(), b.srcW, b.srcH)
            }.getOrNull()
        }
    }
    // Tanı kaydı (yalnızca durum değişince): kalite düşürme nedenleri ve pencere bulanıklığı desteği.
    LaunchedEffect(reduced, powerSave, degraded) {
        android.util.Log.i(
            "GlassDiag",
            "reduced=$reduced lowPower=${settings.lowPower} autoQuality=${settings.autoQuality} " +
                "powerSave=$powerSave degraded=$degraded " +
                "crossWindowBlur=${context.getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled}",
        )
    }
    LaunchedEffect(settings.textShadow, settings.textShadowStrength, settings.textShadowSpread, settings.glassStyle) {
        // Şeffaf camda yazılar net duvar kâğıdı üstünde okunur kalsın: gölge kapalıysa hafif bir gölge açılır.
        val autoShadow = settings.glassStyle == "clear" && !settings.textShadow
        TextShadowConfig.enabled = settings.textShadow || autoShadow
        TextShadowConfig.strength = if (autoShadow) 0.3f else settings.textShadowStrength
        TextShadowConfig.spread = settings.textShadowSpread
    }
    // Cam merceği ayarları: her değişimde hemen (kaydırıcı sürüklenirken de) uygulanır.
    GlassLens.edgeFrac = settings.glassEdgeWidth
    GlassLens.lightAngle = settings.glassLightAngle
    GlassLens.lightBack = settings.glassLightBack
    GlassLens.contactShadow = settings.glassContactShadow
    GlassLens.iconShadow = settings.iconShadow
    // Arşiv/arama/widget sayfasının bulanık arka planı (oradaki camlar bunu büker); aşağıda hazırlanır.
    val overlayBackdropHolder = remember { mutableStateOf<dev.glass.launcher.data.Blurred?>(null) }
    val accent by WallpaperAccent.color.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalAccent provides accent,
        LocalLauncherSettings provides settings,
        LocalOverlayBackdrop provides overlayBackdropHolder.value,
        LocalMotion provides motion,
        LocalPt provides HomeMetrics.pt(LocalConfiguration.current.screenWidthDp.dp),
        LocalHomeMetrics provides homeMetrics,
        LocalGlass provides GlassLook(
            dark = dark,
            reduced = reduced,
            noLens = noLens,
            clear = clearGlass,
            // Ana ekran bulanıklığı açıkken şeffaf cam da net kopya yerine aynı oranda bulanık kopyayı kullanır.
            sharp = if (!useBackdrop || reduced) null else homeBackdrop ?: softSharp ?: sharpBackdrop,
            sharpSoft = homeBackdrop == null && softSharp != null,
            backdrop = if (!useBackdrop) null else homeBackdrop ?: blurred,
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
                        // Widget sayfası açıkken ana ekranın uzun basma/sürükleme algılayıcısı çalışmaz (sayfa kendi işler).
                        today.progress < 0.01f &&
                            (overlayNow == Overlay.None || overlayNow == Overlay.Folder || overlayNow == Overlay.Group)
                    },
                    isEditing = { editNow },
                    // Seçim modunda yalnızca seçili öğeler parmak kaydırılınca sürüklenir; seçilmemiş bir simgenin
                    // üzerinden kaydırmak sayfayı (klasörde klasör sayfasını) çevirir.
                    canDirectDrag = { t -> !selection.active || t.itemId in selection.ids },
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
            // Boş alana dokunma: düzenleme modundaysa moddan çıkar (iOS gibi; titreme durur, düğmeler kapanır).
            // Düzenleme modu dışında çift dokunma ekranı kilitler (ayarlardan kapatılabilir).
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    // Seçim sürerken tek dokunuş seçimi kaybettirmesin; çift dokunuş her durumda çıkar.
                    if (editNow && overlayNow == Overlay.None && !selection.active) editMode = false
                }, onDoubleTap = {
                    val st = settingsNow
                    if (editNow && overlayNow == Overlay.None) { editMode = false; return@detectTapGestures }
                    if (overlayNow == Overlay.None && !editNow && !bridge.onLibrary && today.progress < 0.01f && st.doubleTapLock) {
                        if (!LockService.lock()) {
                            lockReason = LockReason.Lock
                            overlay = Overlay.LockPrompt
                        }
                    }
                })
            }
            .pointerInput(topZonePx) {
                var startY = 0f
                var startX = 0f
                var total = 0f
                // Bu çekme aramayı mı açacak (başlangıç bölgesine atanmış hareket "Arama" ise parmağı izler).
                var pullSearch = false
                val pullPx = 110.dp.toPx()
                fun pullFor(t: Float): Float {
                    val raw = (t / pullPx).coerceAtLeast(0f)
                    // Eşiğe kadar parmakla bire bir; sonra lastik bant gibi giderek artan direnç.
                    return if (raw <= 1f) raw else 1f + (1f - 1f / (1f + (raw - 1f) * 0.8f)) * 0.35f
                }
                detectVerticalDragGestures(
                    onDragStart = {
                        startY = it.y; startX = it.x; total = 0f
                        val chosen = if (startY < topZonePx) settingsNow.topSwipe else settingsNow.midSwipe
                        pullSearch = chosen == SwipeAction.Search && overlayNow == Overlay.None && !editNow &&
                            !bridge.onLibrary && today.progress < 0.01f
                    },
                    onDragEnd = {
                        if (pullSearch) {
                            val open = total > pullPx * 0.7f
                            pullScope.launch {
                                // Açılınca bulanıklık aramanın kendi geçişine devredilir; açılmazsa yaylı geri döner.
                                if (open) pull.animateTo(0f, androidx.compose.animation.core.tween(260))
                                else pull.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 380f, visibilityThreshold = 0.001f))
                            }
                            if (open) overlay = Overlay.Search
                            pullSearch = false
                            total = 0f
                            return@detectVerticalDragGestures
                        }
                        // Ortadan yukarı kaydırma: telefonun kendi son kullanılanlar ekranı (tek hareketle).
                        if (overlayNow == Overlay.None && !editNow && !bridge.onLibrary && today.progress < 0.01f &&
                            total < -80.dp.toPx() && startY > topZonePx && settingsNow.upSwipeRecents
                        ) {
                            if (!LockService.systemRecents()) {
                                lockReason = LockReason.Panels
                                overlay = Overlay.LockPrompt
                            }
                        }
                        if (overlayNow == Overlay.None && !editNow && !bridge.onLibrary && today.progress < 0.01f && total > 80.dp.toPx()) {
                            // Hangi hareketin ne açacağı Ayarlar > Hareketler'den seçilir.
                            val chosen = if (startY < topZonePx) settingsNow.topSwipe else settingsNow.midSwipe
                            // Bölünmüş: sağ yarı Kontrol Merkezi, sol yarı bildirimler.
                            val action = if (chosen == SwipeAction.Split) {
                                if (startX >= size.width / 2f) SwipeAction.ControlCenter else SwipeAction.Notifications
                            } else chosen
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
                                SwipeAction.Split, SwipeAction.None -> Unit
                            }
                        }
                        total = 0f
                    },
                    onDragCancel = {
                        total = 0f
                        if (pullSearch) { pullSearch = false; pullScope.launch { pull.animateTo(0f, androidx.compose.animation.core.spring(0.72f, 380f, 0.001f)) } }
                    },
                    onVerticalDrag = { _, dy ->
                        total += dy
                        if (pullSearch) pullScope.launch { pull.snapTo(pullFor(total)) }
                    },
                )
            }
    ) {
        // İçerik katmanı kaydedilir: üstteki menü/düğmeler bunu bulanıklaştırıp yalnızca kendi arkalarında gösterir.
        val glassRegions = remember { GlassRegions() }
        // GEÇİCİ tanı: klasör açılınca cam değerleri (Ayarlar > Performans).
        LaunchedEffect(overlay) {
            if (overlay != Overlay.Folder) return@LaunchedEffect
            delay(600)
            val st = settingsNow
            GlassDebug.status = "Bulanıklık şiddeti ${"%.2f".format(st.glassBlur)} → ana ekran bulanıklığı ${(blurPxMax * blurBoost).toInt()} px" +
                " (görünürlük ${"%.2f".format(blurAmount)})\nCam stili: ${st.glassStyle}, launcher duvar kâğıdı: ${if (st.launcherWallpaper) "açık" else "kapalı"}" +
                ", ana ekran duvar kâğıdı bulanıklığı ${"%.2f".format(st.homeWallpaperBlur)}" +
                "\nPencere arkası bulanıklığı: ${when (GlassDebug.windowBlurSupported) { true -> "destekleniyor"; false -> "desteklenmiyor"; null -> "bilinmiyor" }}, yarıçap ${GlassDebug.windowBlurPx} px" +
                "\nAna ekran saydamlığı: ${"%.2f".format(1f - homeHide())}, cam alanı: ${glassRegions.regions.size}+${glassRegions.rootRegions.size}"
        }
        // Ayarlar tamamen açılınca arkadaki ana ekran hiç çizilmez (opak sayfanın arkası); kapanırken hemen görünür.
        var settingsCovering by remember { mutableStateOf(false) }
        LaunchedEffect(overlay) {
            settingsCovering = false
            if (overlay == Overlay.Settings) { delay(450); settingsCovering = true }
        }
        val maxBlurPx = with(density) { 22.dp.toPx() }
        val homeBlurPx = with(density) { 28.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                // Klasör / menü / arama paneli açıkken (bulanık alan varken) ana ekran katmanı TEK hazır görüntü olarak
                // tutulur. Ölçüm: klasörde sayfa kaydırırken bu katmanın hiçbir parçası değişmediği halde her karede
                // baştan oynatılıyordu (alanların kenar yumuşatmalı silme yolları ve ara katman dahil), ekran kartı
                // ~11 ms. Artık yalnızca bir kez çizilir; kaydırmada tek bir tam ekran görüntü olarak birleştirilir.
                .graphicsLayer {
                    compositingStrategy = if (!GlassDebug.noRootCache && (glassRegions.regions.isNotEmpty() || glassRegions.rootRegions.isNotEmpty()))
                        CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                // Görünmez yapmak yetmiyordu: katman çizim hattında her karede yine hazırlanıyordu. Artık içerik
                // çizim listesine hiç girmez (alt katmanlar hazır kalır; kapanırken anında geri gelir).
                .drawWithContent { DrawCount.hit("Kök içerik"); if (!settingsCovering) drawContent() }
                // Menü/düğme alanlarına içeriğin bulanık kopyası çizilir (yalnızca kendi arkaları bulanık).
                .glassRegionBlur(glassRegions, (if (clearGlass) 9.dp else 30.dp) * settings.glassBlur, strongRadius = strongBlurR, menuRadius = 40.dp * settings.menuBlur, includeRoot = true)
        ) {
        // Launcher'ın kendi duvar kâğıdı (seçildiyse) sistem duvar kâğıdının üzerine çizilir.
        val wp = wallpaper
        // Kopya sistem duvar kâğıdıyla aynıysa normalde çizilmez; arkadan sistemin kendisi görünür. Böylece
        // "Son uygulamalar" kartında (pencerenin küçültülmüş görüntüsü) arka plan dikdörtgen gibi görünmez.
        // Bulanıklık gereken anlarda (arama, kütüphane, menü/klasör camı) kopya çizilir.
        val fromSync = remember(wp) { vm.wallpaperFromSync() }
        // Yalnızca eşik geçilince yeniden oluşturulur (geçiş oranı her karede değişse de).
        val transitioning by remember { derivedStateOf { blurAmount > 0.001f || libProgress.floatValue > 0.001f } }
        // Duvar kâğıdı katmanları (ekran kartı yükü en az olacak şekilde):
        // • Ana ekranda, kopya sistem duvar kâğıdıyla aynıysa hiçbir şey çizilmez: telefonun kendi duvar kâğıdı
        //   görünür (sistem bunu ekran kartını yormadan birleştirir). Her karede tam ekran bir görüntü çizmek
        //   120 Hz'de kaydırmanın bütçesinin önemli bir kısmını yiyordu.
        // • Arşive/aramaya/klasöre/widget sayfasına geçerken yalnızca hazır BULANIK kopya kademeli belirir (net kopya
        //   hiç devreye girmez → parlaklık atlaması olmaz).
        // • Ana ekran bulanıklığı açıksa: her karede yeniden bulanıklaştırmak yerine bir kez hazırlanmış bulanık kopya.
        // • Menü/düğme camı açıkken (bölgesel bulanıklık içeriğin kopyasını kullanır) net kopya çizilir.
        val homeBlurR = homeBlurPx * homeBlurStep
        val overlayBlurR = maxBlurPx * settings.glassBlur
        val overlayBlurImg by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, wp, overlayBlurR, rootSize) {
            value = blurredWallpaperCopy(wp, overlayBlurR, rootSize)
        }
        // Arama için daha güçlü bulanık duvar kâğıdı (iOS Spotlight gibi; bir kez hazırlanır).
        val searchBlurImg by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, wp, overlayBlurR, rootSize) {
            value = blurredWallpaperCopy(wp, overlayBlurR * 2.2f, rootSize)
        }
        val homeBlurImg by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, wp, homeBlurR, rootSize) {
            value = if (homeBlurR < 1f) null else blurredWallpaperCopy(wp, homeBlurR, rootSize)
        }
        // Katmanlardaki camlar için: arkada hangi bulanık kopya görünüyorsa (katman bulanıklığı ana ekranınkinden
        // güçlüyse o, değilse ana ekran bulanıklığı) aynısı.
        // Telefonun kendi duvar kâğıdı kullanılıyorsa katmanların arkası sistem PENCERE bulanıklığıdır (yarıçap
        // 90 px × bulanıklık ayarı); camın içindeki kopya da aynı güçte bulanıklaştırılır, yoksa kart çevresinden açık
        // ve daha net görünüyordu.
        val launcherDrawsWp = settings.launcherWallpaper
        val windowMatchR = 90f * settings.glassBlur
        val windowMatchImg by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, wp, windowMatchR, rootSize, launcherDrawsWp) {
            value = if (launcherDrawsWp) null else blurredWallpaperCopy(wp, windowMatchR, rootSize)
        }
        LaunchedEffect(overlayBlurImg, homeBlurImg, windowMatchImg, wp, launcherDrawsWp) {
            val img = if (launcherDrawsWp) (if (overlayBlurR > homeBlurR) overlayBlurImg else null) ?: homeBlurImg
            else windowMatchImg
            val src = wp
            overlayBackdropHolder.value =
                if (img == null || src == null) null else dev.glass.launcher.data.Blurred(img, src.width, src.height)
        }
        // Duvar kâğıdı dosyası çoğu zaman ekrandan çok büyük: net kopya gerektiğinde ekran boyutundaki hali çizilir.
        val screenWp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, wp, rootSize) {
            val src = wp
            if (src == null || rootSize.width == 0) { value = null; return@produceState }
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val full0 = src.asAndroidBitmap()
                    val k = maxOf(rootSize.width.toFloat() / full0.width, rootSize.height.toFloat() / full0.height)
                    if (k >= 0.9f) return@runCatching null // zaten ekran boyutunda
                    val full = if (full0.config == android.graphics.Bitmap.Config.HARDWARE) full0.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else full0
                    val out = android.graphics.Bitmap.createScaledBitmap(
                        full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true,
                    )
                    out.prepareToDraw()
                    out.asImageBitmap()
                }.getOrNull()
            }
        }
        val unlockAnim = remember { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(Unit) {
            UnlockAnim.events.collect { hide ->
                if (hide) unlockAnim.snapTo(0f)
                else unlockAnim.animateTo(1f, androidx.compose.animation.core.tween(560, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)))
            }
        }
        // Açılış sürerken duvar kâğıdının hazır kopyası çizilir ve uzaklaşır (sistem duvar kâğıdı hareket ettirilemez).
        val unlocking by remember { derivedStateOf { unlockAnim.value < 0.999f } }
        // Geçiş sürüyor mu (yalnızca eşik geçilince yeniden oluşturulur).
        val anyTransition by remember { derivedStateOf { blurAmount > 0.001f || libProgress.floatValue > 0.001f || today.progress > 0.001f } }
        // Arşiv / widget sayfası / bulanıklık geçişi sürerken saniye ibresi duraklar (geçişte sayfa her karede yeniden çizilmesin).
        val liveInMotion by remember { derivedStateOf { (libProgress.floatValue in 0.001f..0.999f) || today.moving || (blurAmount in 0.001f..0.999f) } }
        LaunchedEffect(liveInMotion) { LiveMotion.transition = liveInMotion; GlassMotion.transition = liveInMotion }
        // Ölçüm: widget sayfası geçişindeki gerçek kare aralıkları (Ayarlar > Performans).
        val todayMoving by remember { derivedStateOf { today.progress in 0.001f..0.999f } }
        MeasureFrames(todayMoving, "Son geçiş (widget sayfası)")
        MeasureFrames(pullOn, "Son çekme (arama)")
        val regionsOpen = glassRegions.regions.isNotEmpty() || glassRegions.rootRegions.isNotEmpty()
        if (settings.launcherWallpaper && wp != null) {
            val homeImg = homeBlurImg
            // Menü/panel alanları açıkken de telefonun kendi duvar kâğıdı kalır: alanların arkasına bulanık duvar kâğıdı
            // alan içinde çizilir (glassRegionBlur). Önceden tüm ekrana kopya çiziliyor, menü açılınca ekranın tonu değişiyordu.
            val needSharp = !fromSync || (homeBlurR >= 1f && homeImg == null) || unlocking
            when {
                // Ana ekran bulanıklığı: hazır bulanık kopya (sabit, ucuz).
                homeImg != null -> Image(
                    homeImg, null,
                    Modifier.fillMaxSize().graphicsLayer { alpha = 1f - homeHide() },
                    contentScale = ContentScale.Crop,
                )
                needSharp -> Image(
                    screenWp ?: wp, null,
                    Modifier.fillMaxSize().graphicsLayer {
                        // Son uygulamalar açılırken kopya da söner (küçültülen pencerede dikdörtgen görünmesin).
                        alpha = 1f - homeHide()
                        // Kilit açılışı: duvar kâğıdı %7 yakından uzaklaşarak yerine gelir.
                        val zw = 1f + 0.07f * (1f - unlockAnim.value)
                        scaleX = zw; scaleY = zw
                        // Bulanık kopya hazırlanırken geçici olarak eski yol.
                        renderEffect = if (homeBlurR >= 1f) BlurEffect(homeBlurR, homeBlurR, TileMode.Clamp) else null
                    },
                    contentScale = ContentScale.Crop,
                )
                else -> Unit // telefonun kendi duvar kâğıdı görünür
            }
            val blurImg = overlayBlurImg
            // Bulanık katman hep yerinde durur, yalnızca görünürlüğü geçişle değişir (görünürlük 0 iken çizilmez).
            // Önceden yalnızca geçiş varken ekleniyordu: kaydırmanın başında koşul art arda açılıp kapanınca katman
            // eklenip kaldırılıyor, eklendiği karede çizilemediği için duvar kâğıdı bir an net görünüyordu.
            if (blurImg != null && overlayBlurR > homeBlurR) {
                Image(
                    blurImg, null,
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = (1f - homeHide()) * maxOf(blurAmount, libProgress.floatValue, today.progress, pull.value).coerceIn(0f, 1f)
                    },
                    contentScale = ContentScale.Crop,
                )
            }
            val sImg = searchBlurImg
            if (sImg != null) {
                Image(
                    sImg, null,
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (blurBoost > 1.5f) (1f - homeHide()) * blurAmount.coerceIn(0f, 1f) else 0f
                    },
                    contentScale = ContentScale.Crop,
                )
            }
            if (settings.wallpaperDim > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - homeHide() }.background(Color.Black.copy(alpha = settings.wallpaperDim)))
        }
        // Widget sayfası geçişinde ana ekran da hazır görüntü olarak kayar; sayfa dışındaki camların (dock, arama
        // çubuğu) arkası bu alt katmanda her karede doğru yerden çizilir (önceden her karede tüm ana ekran çiziliyordu).
        val homeAnchor = remember { PageAnchor().apply { normalShadow = true } }
        homeAnchor.shift = { Offset(today.progress * rootSize.width, 0f) }
        homeAnchor.holesActive = { today.moving }
        Box(Modifier.fillMaxSize().drawBehind {
            if (today.moving) {
                drawPageGlassHoles(homeAnchor, today.progress * size.width, Offset.Zero)
                homeAnchor.drawExtra?.invoke(this, today.progress * size.width)
            }
        })
        Box(
            Modifier.fillMaxSize()
                // Dönüşte arka plan hafifçe yakınlaşarak yerine gelir (iOS gibi).
                .graphicsLayer {
                    compositingStrategy = if (today.moving) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        else androidx.compose.ui.graphics.CompositingStrategy.Auto
                    // Açılan katmanın arkasındaki bulanıklık aşağıda: downsampledBlur (düşük çözünürlük, sabit yarıçap).
                    if (blurAmount > 0.003f) clip = true
                    if (GlassDebug.oldHomeBlur) {
                        val br = blurPxMax * blurAmount * blurBoost
                        renderEffect = if (br > 0.5f) BlurEffect(br, br, TileMode.Clamp) else null
                    } else renderEffect = null
                    var k = 1f
                    if (returnInfo != null) k = 1f + 0.05f * (1f - returnAnim.value)
                    // Son uygulamalar ekranına geçerken simgeler solup hafifçe küçülür; duvar kâğıdı yerinde kalır.
                    val h = homeHide()
                    if (h > 0f) { k *= 1f - 0.06f * h; alpha = 1f - h }
                    // Kilit açılışı (iOS 26): ana ekran tek parça %12 büyükten yerine oturur, hızla belirir.
                    val un = unlockAnim.value
                    if (un < 1f) { k *= 1f + 0.12f * (1f - un); alpha *= (un * 2.2f).coerceIn(0f, 1f) }
                    scaleX = k; scaleY = k
                    // Widget sayfası açılırken ana ekran (dock dahil) sayfa gibi sağa kayar.
                    translationX = today.progress * size.width
                    // Aramaya çekerken ana ekran parmakla dirençli biçimde aşağı iner ve hafifçe solar (iOS gibi).
                    val pv = pull.value
                    if (pv > 0f) {
                        translationY = pv * 46.dp.toPx()
                        alpha *= 1f - 0.35f * pv.coerceAtMost(1f)
                    }
                }
                .downsampledBlur({ if (GlassDebug.oldHomeBlur) 0f else blurAmount }, { blurPxMax * blurBoost })
                .onPlaced { homeAnchor.coords = it }
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalPageAnchor provides homeAnchor) {
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
                onOpenGroup = { g, c ->
                    groupOrigin = if (c == Offset.Zero || rootSize.width == 0) TransformOrigin.Center else TransformOrigin(
                        (c.x / rootSize.width).coerceIn(0f, 1f), (c.y / rootSize.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                    group = g; overlay = Overlay.Group
                },
                libProgress = libProgress,
                selection = selection,
                today = if (settings.showToday) today else null,
                todayAllowed = { !editNow && drag.key == null && overlayNow == Overlay.None },
            )
            }
        }
        if (settings.showToday && todayVisible) {
            // iOS Bugün görünümünde arka plan bulanık ve biraz koyu.
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = 0.12f * today.progress)) })
            // Arama/menü/galeri açılınca widget sayfası da ana ekran gibi bulanıklaşır (yarıçap çizimde okunur).
            Box(Modifier.fillMaxSize().graphicsLayer { clip = blurAmount > 0.003f }.downsampledBlur({ blurAmount }, { blurPxMax * blurBoost })) {
            // Geçişte widget camlarının arkası: sayfanın altında, o anki kaymaya göre (sayfa hazır görüntü olarak kayar).
            Box(Modifier.fillMaxSize().drawBehind {
                if (today.moving) drawPageGlassHoles(today.anchor, (today.progress - 1f) * size.width, Offset.Zero)
            })
            TodayPage(
                state = today,
                widgets = layout.today,
                vm = vm,
                settings = settings,
                edit = editMode,
                jiggle = jiggle,
                onOpenSearch = { overlay = Overlay.Search },
                onAddWidget = { vm.todayOpen = true; overlay = Overlay.Widgets },
                onEdit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); editMode = true },
                onMenu = { w, r ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    openFolderId = null
                    menu = MenuRequest(w.id, CellPlace.Grid, r, r, 0.dp)
                    overlay = Overlay.Menu
                },
            )
            }
        }
        }
        // Karartma çizimde okunur (geçişte ana ekran her karede yeniden oluşturulmasın).
        Box(Modifier.fillMaxSize().drawBehind {
            // Aramaya çekerken arka plan da parmakla birlikte hafifçe kararır.
            val sc = maxOf(scrim, 0.10f * pull.value.coerceIn(0f, 1f))
            if (sc > 0.001f) drawRect(Color.Black.copy(alpha = sc))
        })

        CompositionLocalProvider(LocalGlassRegions provides glassRegions) {

        AnimatedVisibility(
            overlay == Overlay.Search,
            // iOS 26: arama ekranı küçülüp büyümez; yalnızca belirir, öneriler yukarıdan iner (SearchOverlay).
            enter = fadeIn(motion.tween(180)),
            exit = fadeOut(motion.tween(140)),
        ) {
            val searchVis by transition.animateFloat(transitionSpec = { motion.tween(170) }, label = "searchVis") {
                if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f
            }
            SearchOverlay(
                vm, allApps, suggestions, settings.panelGlassStrength, settings.iconGlass,
                onClose = { closeOverlay() }, visibility = { searchVis },
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
            MeasureFrames(transition.isRunning, "Son açılış (klasör)")
            folderHolder[0]?.let { f ->
                // Klasör katmanı da menünün arkasında bulanıklaşır (ayrı katmanda: alttaki ana ekran silinmez).
                Box(Modifier.fillMaxSize().graphicsLayer {
                    // Ayrı katman yalnızca içinde menü açıkken (bölgesel bulanıklık için) gerekir. Önceden hep açıktı:
                    // klasörde sayfa kaydırırken tüm tam ekran katman her karede baştan çiziliyordu (60 Hz).
                    compositingStrategy = if (glassRegions.regions.isNotEmpty()) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }.glassRegionBlur(glassRegions, (if (clearGlass) 9.dp else 30.dp) * settings.glassBlur, strongRadius = strongBlurR, menuRadius = 40.dp * settings.menuBlur)) {
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
                    selection = selection,
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
                    closing = !(overlay == Overlay.Folder && openFolder != null),
                )
                }
            }
        }
        AnimatedVisibility(
            overlay == Overlay.Group,
            enter = scaleIn(motion.bouncy(), initialScale = 0.2f, transformOrigin = groupOrigin) + fadeIn(motion.tween(180)),
            exit = scaleOut(motion.tween(220), targetScale = 0.2f, transformOrigin = groupOrigin) + fadeOut(motion.tween(200)),
        ) {
            groupHolder[0]?.let { g ->
                Box(Modifier.fillMaxSize().graphicsLayer {
                    // Ayrı katman yalnızca içinde menü açıkken (bölgesel bulanıklık için) gerekir. Önceden hep açıktı:
                    // klasörde sayfa kaydırırken tüm tam ekran katman her karede baştan çiziliyordu (60 Hz).
                    compositingStrategy = if (glassRegions.regions.isNotEmpty()) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }.glassRegionBlur(glassRegions, (if (clearGlass) 9.dp else 30.dp) * settings.glassBlur, strongRadius = strongBlurR, menuRadius = 40.dp * settings.menuBlur)) {
                LibraryGroupOverlay(
                    group = g, vm = vm, settings = settings, drag = drag, jiggle = jiggle,
                    onDrop = { key, p, h -> bridge.home?.invoke(key, p, h) },
                    onClose = { closeOverlay() },
                )
                }
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
            val menuVis by transition.animateFloat(transitionSpec = { motion.tween(150) }, label = "menuVis") {
                if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f
            }
            folderMenuHolder[0]?.let { req ->
                Box(Modifier.fillMaxSize()) {
                    ContextMenuOverlay(
                        visibility = { menuVis },
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
            // Tam ekran katmana ölçek/solma UYGULANMAZ: ekranın ortasından büyüyüp küçülüyor, menü önce yanlış yerde
            // beliriyor ve kapanırken ortaya kayıyordu. Açılış/kapanış yalnızca menünün kendi içinde (simgeden) yapılır;
            // menü camı ve arkasındaki bulanıklık aynı değerle (menuVis) söner.
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            val menuVis by transition.animateFloat(transitionSpec = { motion.tween(if (targetState == androidx.compose.animation.EnterExitState.Visible) 150 else 170) }, label = "menuVis") {
                if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f
            }
            menuHolder[0]?.let { req ->
                val folder = layout.items.firstOrNull { it.id == req.itemId } as? FolderItem
                val widget = (layout.items + layout.today).firstOrNull { it.id == req.itemId } as? WidgetItem
                ContextMenuOverlay(
                    visibility = { menuVis },
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
                    onResizeWidget = { sz -> vm.editLayout { it.resizeWidget(req.itemId, sz.x, sz.y) } },
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
                    onAddToStack = widget?.let { w -> { vm.beginAddToStack(w.id); overlay = Overlay.Widgets } },
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
        var pagesEditor by remember { mutableStateOf(false) }
        var customizeSheet by remember { mutableStateOf(false) }
        LaunchedEffect(editMode) { if (!editMode) { pagesEditor = false; customizeSheet = false } }
        // Sayfaları Düzenle / Özelleştir açıkken düzenleme düğmeleri gizlenir (üstlerine binmesin).
        val showEditBar = editMode && overlay == Overlay.None && !wallpaperSheet && !pagesEditor && !customizeSheet
        var plusMenu by remember { mutableStateOf(false) }
        LaunchedEffect(plusMenu, showEditBar) { LiveMotion.plus = plusMenu && showEditBar }
        LaunchedEffect(showEditBar) { if (!showEditBar) plusMenu = false }
        // "Seç" / "Bu Sayfayı Sil": düzenleme modunda "Ara" düğmesinin YERİNDE (Ara düğmesi o sırada gizlenir).
        var confirmDeletePage by remember { mutableStateOf<List<String>?>(null) }
        // Arşive geçiş oranı ekrana yansıyan bir değerden okunur (bridge.onLibrary düz değişken olduğu için arşive
        // kaydırınca düğmeler yeniden çizilmiyor, arşivin üstünde kalıyordu).
        val onLibraryNow by remember { derivedStateOf { libProgress.floatValue > 0.98f } }
        val bottomBar = showEditBar && !onLibraryNow && !todayOpenNow && drag.key == null && !selection.active
        val canDeletePage = bridge.homePageCount > 1
        homeMetrics?.let { hm ->
            AnimatedVisibility(
                bottomBar,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = hm.pillCenterY - hm.pillH / 2f)
                    // Arşive kaydırırken "Ara" ve dock ile birlikte aşağı kayıp solar.
                    .graphicsLayer {
                        val f = libProgress.floatValue
                        alpha = 1f - f
                        translationY = f * (rootSize.height - hm.pillCenterY.toPx() + hm.pillH.toPx())
                    },
                enter = scaleIn(motion.bouncy(), initialScale = 0.5f) + fadeIn(motion.tween(160)),
                exit = scaleOut(motion.tween(140), targetScale = 0.6f) + fadeOut(motion.tween(120)),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Seç", settings.glassStrength, hm.pillH) { selection.start() }
                    // iOS gibi: sayfalar önizlemelerle düzenlenir (gizle/göster, sırala, gizliyi sil).
                    GlassButton("Sayfalar", settings.glassStrength, hm.pillH) { pagesEditor = true }
                }
            }
        }
        // Çoklu seçim işlem çubuğu
        val selIds = selection.ids.toList()
        SelectionBar(
            visible = editMode && selection.active && (overlay == Overlay.None || overlay == Overlay.Folder) && drag.key == null,
            count = selIds.size,
            appCount = layout.appKeysOf(selIds).distinct().size,
            // Açık klasörün kendisi hedef listesinde gösterilmez.
            folders = layout.items.filterIsInstance<FolderItem>()
                .filter { overlay != Overlay.Folder || it.id != openFolderId }.map { it.id to it.name },
            onCreateFolder = {
                val ids = selection.ids.toList()
                if (overlay == Overlay.Folder) closeOverlay()
                vm.editLayout { l -> l.createFolderFrom(ids, vm.folderNameFor(l.appKeysOf(ids))) }
                selection.ids.clear()
            },
            onMoveInto = { fid ->
                val ids = selection.ids.toList()
                if (overlay == Overlay.Folder) closeOverlay()
                vm.editLayout { it.moveIntoFolder(fid, ids) }
                selection.ids.clear()
            },
            onRemove = {
                val ids = selection.ids.toList()
                if (overlay == Overlay.Folder) closeOverlay()
                vm.editLayout { it.removeAppsFromHome(ids) }
                selection.ids.clear()
            },
            onDone = { selection.end() },
        )
        confirmDeletePage?.let { ids ->
            val apps = ids.count { id -> layout.items.any { it.id == id && (it is AppItem || it is FolderItem) } }
            GlassAlert(
                title = "Bu sayfa silinsin mi?",
                message = if (apps == 0) "Sayfada görünen bir şey yok; sayfa kaldırılacak."
                    else "Sayfadaki uygulamalar ve klasörler silinmez, Uygulama Arşivi'nde kalır. Widget'lar kaldırılır.",
                buttons = listOf(
                    GlassAlertButton("Sayfayı Sil", destructive = true) { vm.deletePage(ids); confirmDeletePage = null },
                    GlassAlertButton("Vazgeç", bold = true) { confirmDeletePage = null },
                ),
                onDismiss = { confirmDeletePage = null },
            )
        }
        AnimatedVisibility(
            showEditBar,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 16.dp, y = btnY),
            enter = scaleIn(motion.bouncy(), initialScale = 0.4f, transformOrigin = TransformOrigin(0f, 0f)) + fadeIn(motion.tween(160)),
            exit = scaleOut(motion.tween(160), targetScale = 0.6f, transformOrigin = TransformOrigin(0f, 0f)) + fadeOut(motion.tween(140)),
        ) {
            GlassButton("+", settings.glassStrength, btnH) { plusMenu = !plusMenu }
        }
        // "+" menüsü: düğmenin altından aşağı doğru açılan Liquid Glass menü (basılı tutma menüsüyle aynı cam).
        if (plusMenu && showEditBar) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { plusMenu = false } })
        }
        AnimatedVisibility(
            plusMenu && showEditBar,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 16.dp, y = btnY + btnH + 10.dp),
            enter = scaleIn(motion.bouncy(), initialScale = 0.3f, transformOrigin = TransformOrigin(0f, 0f)) + fadeIn(motion.tween(140)),
            exit = scaleOut(motion.tween(150), targetScale = 0.5f, transformOrigin = TransformOrigin(0f, 0f)) + fadeOut(motion.tween(120)),
        ) {
            val vis by transition.animateFloat(transitionSpec = { motion.tween(150) }, label = "plusVis") {
                if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f
            }
            MeasureFrames(transition.isRunning, "Son açılış (+ menüsü)")
            val u = LocalPt.current
            val corner = u * settings.menuCorner
            val plusDark = menuDarkNow()
            val ink = menuInk(plusDark)
            val rowH = u * settings.menuRowHeight
            Box(Modifier.width(u * 230f)) {
                BlurBackdrop(corner, Modifier.matchParentSize(), shape = appShape(corner), alpha = { vis }, menuBlur = true)
                Column(
                    Modifier
                        .width(u * 230f)
                        .menuGlass(appShape(corner), settings.menuOpacity, plusDark)
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    MenuRow("Widget Ekle", ink, rowH, padTop = u * 6f, leading = { GlyphIcon(Glyph.Widget, ink, u * 22f) }) {
                        plusMenu = false; overlay = Overlay.Widgets
                    }
                    MenuRow("Özelleştir", ink, rowH, leading = { GlyphIcon(Glyph.Edit, ink, u * 22f) }) {
                        plusMenu = false; customizeSheet = true
                    }
                    MenuRow("Duvar Kâğıdı", ink, rowH, leading = { GlyphIcon(Glyph.Wallpaper, ink, u * 22f) }) {
                        plusMenu = false; wallpaperSheet = true
                    }
                    MenuRow("Ayarlar", ink, rowH, padBottom = u * 6f, leading = { GlyphIcon(Glyph.Gear, ink, u * 22f) }) {
                        plusMenu = false; openSettings()
                    }
                }
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
        // Sayfaları Düzenle (iOS): önizlemeler, gizle/göster, sırala.
        homeMetrics?.let { hm ->
            androidx.compose.animation.AnimatedVisibility(
                pagesEditor && editMode,
                enter = fadeIn(motion.tween(180)) + scaleIn(motion.bouncy(), initialScale = 0.92f),
                exit = fadeOut(motion.tween(150)) + scaleOut(motion.tween(150), targetScale = 0.94f),
            ) {
                PagesEditor(vm, layout, byKey, hm.columns, hm.rows, settings.glassStrength) { pagesEditor = false }
            }
        }
        // Özelleştir paneli (iOS): simge görünümü, boyutu ve rengi anında.
        CustomizeSheet(
            visible = customizeSheet && editMode,
            settings = settings,
            strength = settings.glassStrength,
            onChange = { f -> vm.updateSettings(f) },
            onClose = { customizeSheet = false },
        )
        }

        // Duvar kâğıdı paneli (düzenleme modunda "Duvar Kâğıdı" ile alttan açılır)
        WallpaperSheet(
            visible = wallpaperSheet,
            target = dev.glass.launcher.data.WallpaperTarget.of(settings.wallpaperTarget),
            strength = settings.glassStrength,
            hasLauncherWallpaper = settings.launcherWallpaper,
            homeBlur = settings.homeWallpaperBlur,
            onHomeBlur = { v -> vm.updateSettings { it.copy(homeWallpaperBlur = v) } },
            onTarget = { t -> vm.updateSettings { it.copy(wallpaperTarget = t.code) } },
            onPick = {
                wallpaperSheet = false
                wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onSystem = {
                wallpaperSheet = false
                runCatching {
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Duvar kâğıdı")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onRemove = { wallpaperSheet = false; vm.clearWallpaper() },
            onClose = { wallpaperSheet = false },
        )

        // Dönüş efekti kartı
        returnInfo?.let { AppReturnOverlay(it, rootSize, returnAnim.value, vm) }

        // Sürüklenen öğenin hayalet simgesi (tüm katmanların üstünde)
        val dk = drag.key
        if (dk != null && drag.moved) {
            val gs = drag.ghostSize
            val group = selection.isGroupDrag(dk)
            // Toplu sürüklemede deste ve rozet katmanın İÇİNDE kalsın diye kenar payı (saydam katman sınırda kırpar).
            val pad = if (group) 26.dp else 0.dp
            val gw = with(density) { ((gs?.width ?: drag.iconDp) + pad * 2f).toPx() }
            val gh = with(density) { ((gs?.height ?: drag.iconDp) + pad * 2f).toPx() }
            Box(
                Modifier
                    .offset { IntOffset((drag.pos.x - gw / 2f).roundToInt(), (drag.pos.y - gh / 2f).roundToInt()) }
                    .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f; alpha = 0.96f }
            ) {
                val app = byKey[dk]
                if (gs != null) {
                    // Widget hayaleti: aynı boyutta cam kutu
                    Box(
                        Modifier.size(gs.width, gs.height)
                            .liquidGlass(widgetShape(widgetCorner()), settings.glassStrength, 16.dp, iconRim = true)
                    )
                } else if (group) {
                    val icon = drag.iconDp
                    val others = selection.ids.filter { it != dk }
                    val n = selection.ids.size
                    val shape = appShape(icon * settings.iconCorner)
                    Box(Modifier.size(icon + pad * 2f)) {
                        // Belirgin deste: arkada en çok 3 simge, her biri kaydırılmış ve yelpaze gibi açılmış.
                        val back = others.take(3)
                        val fan = listOf(Triple(10f, -8f, 8f), Triple(-10f, -12f, -7f), Triple(16f, -18f, 13f))
                        for (i in back.indices.reversed()) {
                            val (dx, dy, rot) = fan[i]
                            Box(
                                Modifier
                                    .offset(x = pad + dx.dp, y = pad + dy.dp)
                                    .size(icon)
                                    .graphicsLayer { rotationZ = rot }
                                    .shadow(8.dp, shape)
                            ) { GhostIcon(back[i], byKey, layout, vm, icon, settings, folderPages) }
                        }
                        Box(Modifier.offset(x = pad, y = pad).size(icon).shadow(10.dp, shape)) {
                            GhostIcon(dk, byKey, layout, vm, icon, settings, folderPages)
                        }
                        // Sayı rozeti: ön simgenin sağ üst köşesinde, tamamı görünür.
                        Box(
                            Modifier
                                .offset(x = pad + icon - 15.dp, y = pad - 9.dp)
                                .size(24.dp)
                                .shadow(3.dp, CircleShape)
                                .background(Color(0xFFFF3B30), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("$n", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
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
    // "Ara" hapı ve dock ile aynı cam: arkadaki duvar kâğıdını büker. Önceden arkasındaki ekran içeriğini
    // bulanıklaştırıyordu; düzenleme modunda sayfa kayarken bütün içerik her karede ayrıca kaydedilip
    // bulanıklaştırılıyor, kaydırmayı 120 Hz'in altına düşürüyordu.
    Box(
        Modifier
            .height(height)
            .liquidGlass(CircleShape, strength, 6.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        val st = LocalLauncherSettings.current
        Text(
            text, color = Color.White, fontSize = st.editButtonTextSize.sp, fontWeight = fontWeightOf(st.editButtonWeight),
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

/** Sürükleme hayaletinde bir öğenin simgesi (uygulama veya klasör). */
@Composable
private fun GhostIcon(
    id: String, byKey: Map<String, AppInfo>, layout: HomeLayout, vm: LauncherViewModel, size: Dp,
    settings: LauncherSettings, folderPages: Map<String, Int>,
) {
    val app = byKey[id]
    if (app != null) {
        AppIconImage(app, vm, size, settings.iconGlass, settings.glassStrength)
    } else {
        (layout.items.firstOrNull { it.id == id } as? FolderItem)?.let {
            FolderIcon(it, byKey, vm, size, settings.glassStrength, folderPages[it.id] ?: 0)
        }
    }
}


/** Duvar kâğıdının, ekrandaki [radiusPx] Gauss bulanıklığına eşdeğer bulanık küçük kopyası (bir kez, arka planda). */
/** Net duvar kâğıdının yarım ekran çözünürlüğünde, [softPx] kadar hafif yumuşatılmış kopyası (şeffaf cam). */
private suspend fun softWallpaperCopy(
    src: androidx.compose.ui.graphics.ImageBitmap, softPx: Float, rootSize: IntSize,
): androidx.compose.ui.graphics.ImageBitmap? {
    if (rootSize.width == 0 || rootSize.height == 0) return null
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        runCatching {
            val full0 = src.asAndroidBitmap()
            val full = if (full0.config == android.graphics.Bitmap.Config.HARDWARE) full0.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else full0
            // Ekranı kaplayan ölçeğin yarısı (en çok orijinal boyut).
            val k = (maxOf(rootSize.width.toFloat() / full.width, rootSize.height.toFloat() / full.height) * 0.5f).coerceAtMost(1f)
            val small = android.graphics.Bitmap.createScaledBitmap(
                full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true,
            ).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            // Merceğin 9 örneklik yumuşatması ≈ Gauss sigma 0,9·soft (ekran px); yarım çözünürlükte yarısı.
            val r = kotlin.math.round(softPx * 0.9f * 0.5f).toInt().coerceIn(1, 12)
            dev.glass.launcher.data.BlurUtil.boxBlur(small, r, 3)
            small.prepareToDraw()
            small.asImageBitmap()
        }.getOrNull()
    }
}

private suspend fun blurredWallpaperCopy(
    src: androidx.compose.ui.graphics.ImageBitmap?, radiusPx: Float, rootSize: IntSize,
): androidx.compose.ui.graphics.ImageBitmap? {
    if (src == null || radiusPx < 1f || rootSize.width == 0) return null
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        runCatching {
            val f = 4
            // Duvar kâğıdı ekran kartı belleğinde (HARDWARE) olabilir: önce okunabilir kopyası alınır.
            val full0 = src.asAndroidBitmap()
            val full = if (full0.config == android.graphics.Bitmap.Config.HARDWARE) full0.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else full0
            val small = android.graphics.Bitmap.createScaledBitmap(
                full, (full.width / f).coerceAtLeast(1), (full.height / f).coerceAtLeast(1), true,
            ).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            // Ekrandaki Gauss bulanıklığının (sigma ≈ 0,577·r) küçük kopyadaki karşılığı; 3 geçişli kutu ≈ Gauss.
            val screenPerSrc = maxOf(rootSize.width.toFloat() / full.width, rootSize.height.toFloat() / full.height)
            val sigma = (radiusPx * 0.577f + 0.5f) / (screenPerSrc * f)
            dev.glass.launcher.data.BlurUtil.boxBlur(small, sigma.toInt().coerceIn(1, 40), 3)
            small.prepareToDraw()
            small.asImageBitmap()
        }.getOrNull()
    }
}
