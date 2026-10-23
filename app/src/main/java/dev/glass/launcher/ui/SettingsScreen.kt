package dev.glass.launcher.ui

import androidx.compose.ui.geometry.Offset

import androidx.compose.runtime.snapshotFlow

import androidx.compose.foundation.gestures.rememberDraggableState

import androidx.compose.foundation.gestures.draggable

import androidx.compose.ui.input.nestedscroll.nestedScroll

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.ui.draw.drawWithContent

import androidx.compose.ui.graphics.drawscope.clipPath

import androidx.compose.ui.graphics.drawscope.translate

import androidx.compose.ui.graphics.graphicsLayer

import android.net.Uri

import androidx.compose.material3.LocalTextStyle
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import kotlin.math.roundToInt
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.BuildConfig
import dev.glass.launcher.data.IconStyle
import dev.glass.launcher.data.LauncherSettings
import dev.glass.launcher.data.SwipeAction
import dev.glass.launcher.data.ThemeMode
import dev.glass.launcher.service.LockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Blue = Color(0xFF0A84FF)

/** Ayarlar ekranı renkleri: telefonun açık/koyu temasına (veya seçilen temaya) göre. */
private class SettingsPalette(
    val pageBg: Color, val rowBg: Color, val divider: Color, val muted: Color, val fg: Color,
    val secondary: Color, val chip: Color, val switchOff: Color, val sliderOff: Color,
)
private val DarkPalette = SettingsPalette(
    pageBg = Color(0xFF000000), rowBg = Color(0xFF1C1C1E), divider = Color(0xFF38383A), muted = Color(0xFF8E8E93),
    fg = Color.White, secondary = Color(0xFFD1D1D6), chip = Color(0xFF3A3A3C), switchOff = Color(0xFF39393D),
    sliderOff = Color(0xFF48484A),
)
private val LightPalette = SettingsPalette(
    pageBg = Color(0xFFF2F2F7), rowBg = Color.White, divider = Color(0xFFC6C6C8), muted = Color(0xFF6D6D72),
    fg = Color.Black, secondary = Color(0xFF3C3C43), chip = Color(0xFFE5E5EA), switchOff = Color(0xFFE9E9EA),
    sliderOff = Color(0xFFD1D1D6),
)
private val LocalSettingsPalette = staticCompositionLocalOf { DarkPalette }
private val RowBg: Color @Composable get() = LocalSettingsPalette.current.rowBg
private val Divider: Color @Composable get() = LocalSettingsPalette.current.divider
private val Muted: Color @Composable get() = LocalSettingsPalette.current.muted
private val PageBg: Color @Composable get() = LocalSettingsPalette.current.pageBg
private val Fg: Color @Composable get() = LocalSettingsPalette.current.fg
private val Secondary: Color @Composable get() = LocalSettingsPalette.current.secondary
private val ChipBg: Color @Composable get() = LocalSettingsPalette.current.chip

/** Varsayılan değerler ("↺" düğmesi ve sayfa sıfırlama için). */
private val Def = LauncherSettings()

/** Açık sayfadaki ayarların "varsayılana dön" işlemleri (sayfa sıfırlama düğmesi için). */
private val LocalResetRegistry = staticCompositionLocalOf<MutableList<() -> Unit>?> { null }

/** Ayarlar alt sayfaları (iOS Ayarlar düzeni). */
private enum class SettingsPage(val title: String, val glyph: Glyph, val color: Color) {
    Home("Ana Ekran", Glyph.Grid, Color(0xFF0A84FF)),
    Icons("Simgeler", Glyph.Edit, Color(0xFF5E5CE6)),
    Dock("Dock", Glyph.Widget, Color(0xFF32ADE6)),
    Folders("Klasörler", Glyph.Folder, Color(0xFF64D2FF)),
    Fonts("Yazı Tipi", Glyph.Pencil, Color(0xFFFF9F0A)),
    Menu("Açılır Menü", Glyph.Edit, Color(0xFFAF52DE)),
    Search("Arama", Glyph.Search, Color(0xFF8E8E93)),
    Glass("Liquid Glass", Glyph.Search, Color(0xFF30B0C7)),
    Wallpaper("Duvar Kâğıdı", Glyph.Wallpaper, Color(0xFF34C759)),
    Animations("Animasyonlar", Glyph.Check, Color(0xFFFF9F0A)),
    Gestures("Hareketler", Glyph.Grid, Color(0xFF30D158)),
    Widgets("Widget'lar", Glyph.Widget, Color(0xFFFF375F)),
    Library("Uygulama Arşivi", Glyph.Grid, Color(0xFFBF5AF2)),
    Privacy("Gizli ve Kilitli Uygulamalar", Glyph.Lock, Color(0xFF5856D6)),
    Backup("Yedekleme ve Geri Yükleme", Glyph.Folder, Color(0xFF8E8E93)),
    Performance("Performans", Glyph.Info, Color(0xFFFF453A)),
    About("Hakkında", Glyph.Info, Color(0xFF636366)),
}

/**
 * Tam ekran, iOS tarzı (gruplanmış liste) Türkçe ayarlar. Değişiklikler anında uygulanır ve
 * DataStore'a yazılır; uygulama kapansa da kaybolmaz.
 */
@Composable
fun SettingsScreen(
    vm: LauncherViewModel,
    s: LauncherSettings,
    isDefaultHome: Boolean,
    onRequestDefault: () -> Unit,
    onAddWidget: () -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val onChange: ((LauncherSettings) -> LauncherSettings) -> Unit = { vm.updateSettings(it) }

    BackHandler { if (page != null) page = null else onClose() }

    val dark = when (s.settingsTheme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    // Açık temada durum/gezinme çubuğu simgeleri koyu olmalı; ayarlar kapanınca eski hâline döner.
    // Ayarlar kendi penceresinde: durum/gezinti çubuğu yazıları temaya göre (açık temada koyu).
    val barView = LocalView.current
    DisposableEffect(dark) {
        val win = (barView.context as? android.app.Activity)?.window
        if (win != null) WindowCompat.getInsetsController(win, barView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        onDispose { }
    }
    // 120 Hz: Android 15+ pencereleri varsayılan olarak 60 Hz'de çizer; ayarlar açıkken (kaydırma, sayfa geçişleri)
    // en yüksek hız istenir. Yalnızca çizim olduğunda etkilidir, boşta pil harcamaz.
    val frView = LocalView.current
    DisposableEffect(frView) {
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            frView.requestedFrameRate = android.view.View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                frView.requestedFrameRate = android.view.View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }

    CompositionLocalProvider(
        LocalSettingsPalette provides if (dark) DarkPalette else LightPalette,
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = AppFont),
    ) {
    Box(Modifier.fillMaxSize().background(PageBg)) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (targetState != null) {
                    slideInHorizontally(tween(260)) { it } togetherWith slideOutHorizontally(tween(260)) { -it / 3 }
                } else {
                    slideInHorizontally(tween(260)) { -it / 3 } togetherWith slideOutHorizontally(tween(260)) { it }
                }
            },
            label = "settingsNav",
        ) { current ->
            val p = current?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
            Column(Modifier.fillMaxSize().background(PageBg).windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Üst çubuk
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p != null) {
                        Text(
                            "‹ Ayarlar", color = Blue, fontSize = 17.sp,
                            modifier = Modifier.clickable { page = null }.padding(8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Bitti", color = Blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onClose() }.padding(8.dp),
                    )
                }
                // Kaydırırken içerik her karede baştan çizilmesin: tek bir hazır katman olarak taşınır (akıcı 120 Hz).
                val pageScroll = rememberScrollState()
                SettingsScrollStats(pageScroll)
                Column(
                    Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 16.dp)
                ) {
                Column(Modifier.fillMaxWidth().graphicsLayer { }) {
                    Text(
                        p?.title ?: "Ayarlar", color = Fg, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    val resetReg = remember(p) { mutableStateListOf<() -> Unit>() }
                    CompositionLocalProvider(LocalResetRegistry provides resetReg) {
                    when (p) {
                        null -> {
                            LayoutOrderGroup(vm)
                            GlassMasterGroup(s, onChange)
                            RootPage(isDefaultHome, onRequestDefault) { pg ->
                                // Gizli uygulamalar listesi kimlik doğrulamasıyla açılır.
                                if (pg == SettingsPage.Privacy) AppLock.authenticate(pg.title) { page = pg.name }
                                else page = pg.name
                            }
                            SettingsThemeGroup(s, onChange)
                        }
                        SettingsPage.Home -> HomePage(s, onChange)
                        SettingsPage.Icons -> IconsPage(s, onChange)
                        SettingsPage.Dock -> DockPage(s, onChange)
                        SettingsPage.Folders -> FoldersPage(s, onChange)
                        SettingsPage.Fonts -> FontsPage(s, onChange)
                        SettingsPage.Menu -> MenuPage(s, onChange)
                        SettingsPage.Search -> SearchPage(s, onChange)
                        SettingsPage.Glass -> GlassPage(vm, s, onChange)
                        SettingsPage.Wallpaper -> WallpaperPage(vm, s, onChange)
                        SettingsPage.Animations -> AnimationsPage(s, onChange)
                        SettingsPage.Gestures -> GesturesPage(s, onChange)
                        SettingsPage.Widgets -> WidgetsPage(s, onChange, onAddWidget)
                        SettingsPage.Library -> LibraryPageSettings(s, onChange)
                        SettingsPage.Privacy -> PrivacyPage(vm, s)
                        SettingsPage.Backup -> BackupPage(vm)
                        SettingsPage.Performance -> PerformancePage(s, onChange)
                        SettingsPage.About -> AboutPage()
                    }
                    }
                    if (p != null && resetReg.isNotEmpty()) {
                        Group(null) {
                            ActionRow("Bu sayfayı varsayılana döndür", destructive = true) { resetReg.toList().forEach { it() } }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
                }
            }
        }
    }
    }
}

@Composable
private fun LayoutOrderGroup(vm: LauncherViewModel) {
    var confirm by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    val hasBackup by vm.hasLayoutBackup.collectAsState()
    Group(null) {
        NavRow("Varsayılan uygulama sıralaması", Glyph.Grid, Color(0xFFFF9F0A)) { confirm = true }
        if (hasBackup) {
            RowDivider()
            ActionRow("Önceki düzene geri dön") { vm.restorePreviousLayout(); done = "Önceki düzen geri yüklendi." }
        }
    }
    Footer(
        done ?: ("Uygulamalar iOS 26'nın fabrika sırasına dizilir (karşılıklarıyla: Meet/MeeTime, Takvim, Galeri, Kamera · " +
            "Gmail, Notlar, Görevler, Saat · TV, Oyun Merkezi, Play Store, Haritalar · Sağlık, Cüzdan, Ayarlar; dock: " +
            "Telefon, tarayıcı, Mesajlar, Müzik). Diğer uygulamalar 2. sayfada türlerine göre klasörlenir. Widget'lar korunur.")
    )
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { androidx.compose.material3.Text("Varsayılan sıralama uygulansın mı?") },
            text = { androidx.compose.material3.Text("Şu anki ana ekran düzenin yedeklenir; istersen buradan \"Önceki düzene geri dön\" ile geri alabilirsin.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = false; vm.applyIosLayout(); done = "Varsayılan sıralama uygulandı. Ana ekrana dönüp bakabilirsin."
                }) { androidx.compose.material3.Text("Uygula") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false }) { androidx.compose.material3.Text("Vazgeç") }
            },
        )
    }
}

/**
 * Cam efekti (bulanıklık + kırılma) için sistem duvar kâğıdının launcher kopyası. Android 13+'ta sistem
 * duvar kâğıdını okumak "Tüm dosyalara erişim" izni ister; izin yoksa izin ekranına götüren satır gösterilir.
 */
@Composable
private fun SystemWallpaperSyncGroup(vm: LauncherViewModel, s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tick++ }
    val la = ctx.applicationContext as dev.glass.launcher.LauncherApp
    val photos = remember(tick) { la.wallpaperRepository.hasPhotosPermission() }
    val allFiles = remember(tick) { la.wallpaperRepository.hasAllFilesAccess() }
    val granted = photos && allFiles
    var asked by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        asked = true
        // İzin verilince launcher öne geldiğinde (onResume) kopyalama kendiliğinden yapılır.
    }
    Group("Duvar kâğıdı ile cam") {
        ToggleRow("Sistem duvar kâğıdını cam için kullan", s.syncSystemWallpaper, default = Def.syncSystemWallpaper) { v ->
            onChange { it.copy(syncSystemWallpaper = v) }
        }
        if (s.syncSystemWallpaper && granted) {
            RowDivider()
            val scope = rememberCoroutineScope()
            ActionRow("Duvar kâğıdını şimdi yeniden kopyala") {
                vm.syncSystemWallpaper(force = true)
                // Kopyalama arka planda sürer; durum satırı kısa bir süre sonra yenilenir.
                scope.launch { kotlinx.coroutines.delay(1500); tick++ }
            }
        }
        if (s.syncSystemWallpaper && !allFiles) {
            RowDivider()
            NavRow("İzin ver: Tüm dosyalara erişim", Glyph.Check, Color(0xFF30D158)) {
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        if (s.syncSystemWallpaper && !photos) {
            RowDivider()
            NavRow("İzin ver: Fotoğraflar", Glyph.Check, Color(0xFF30D158)) {
                if (!asked) perm.launch(android.Manifest.permission.READ_MEDIA_IMAGES)
                else runCatching {
                    // Daha önce reddedildiyse uygulama izinleri ekranı açılır.
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }
    Footer(
        when {
            !s.syncSystemWallpaper -> "Kapalı: cam yalnızca launcher'dan seçilen duvar kâğıdında bulanık ve kırılmalı görünür."
            granted -> "Açık: telefonun duvar kâğıdı launcher'a kopyalanır, değişince kendiliğinden güncellenir. Dock ve klasörler gerçek bulanıklık ve kırılmayla çizilir. Görüntü telefonda kalır, hiçbir yere gönderilmez.\n\nKopya: ${remember(tick) { la.wallpaperRepository.syncStatus }}\nCam: ${remember(tick) { GlassStatus.text }}"
            else -> "Android, launcher'ın sistem duvar kâğıdını görebilmesi için iki izin ister: Tüm dosyalara erişim ve Fotoğraflar (\"Tümüne izin ver\" seçilmeli). İzin vermezsen duvar kâğıdını launcher'dan (boşluğa basılı tut > Duvar Kâğıdı) seçebilirsin."
        }
    )
}

@Composable
private fun GlassMasterGroup(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val label = when {
        s.glassTint < 0.2f -> "Çok şeffaf"
        s.glassTint < 0.42f -> "Şeffaf"
        s.glassTint <= 0.58f -> "Dengeli"
        s.glassTint < 0.8f -> "Renkli"
        else -> "Tam renkli"
    }
    Group("Liquid Glass") {
        SliderRow("Şeffaflık", label, s.glassTint, 0f..1f, default = Def.glassTint) { v -> onChange { it.copy(glassTint = v) } }
    }
    Footer("iOS 27 ve MagicOS 11'deki gibi tüm cam yüzeylerin (dock, klasör, menü, arama, paneller) şeffaflığını birlikte ayarlar. Ayrıntılı ayarlar: Ayarlar > Liquid Glass.")
}

@Composable
private fun SettingsThemeGroup(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group("Ayarlar görünümü") {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Otomatik", "light" to "Açık", "dark" to "Koyu").forEach { (k, t) ->
                Chip(t, s.settingsTheme == k, Modifier.weight(1f)) { onChange { it.copy(settingsTheme = k) } }
            }
        }
    }
    Footer("Otomatik: telefonun açık/koyu tema ayarını izler.")
}

@Composable
private fun RootPage(isDefaultHome: Boolean, onRequestDefault: () -> Unit, open: (SettingsPage) -> Unit) {
    if (!isDefaultHome) {
        Group(null) {
            NavRow("Varsayılan ana ekran yap", Glyph.Check, Color(0xFF30D158)) { onRequestDefault() }
        }
        Footer("Home tuşunun Glass Launcher'ı açması için gereklidir. Uygulama kısayolları da yalnızca varsayılan ana ekranda çalışır.")
    }
    val groups = listOf(
        listOf(SettingsPage.Home, SettingsPage.Icons, SettingsPage.Dock, SettingsPage.Folders, SettingsPage.Fonts),
        listOf(SettingsPage.Glass, SettingsPage.Menu, SettingsPage.Search, SettingsPage.Wallpaper, SettingsPage.Animations, SettingsPage.Gestures),
        listOf(SettingsPage.Widgets, SettingsPage.Library, SettingsPage.Privacy),
        listOf(SettingsPage.Backup, SettingsPage.Performance, SettingsPage.About),
    )
    groups.forEach { g ->
        Group(null) {
            g.forEachIndexed { i, p ->
                NavRow(p.title, p.glyph, p.color) { open(p) }
                if (i < g.lastIndex) RowDivider(inset = 56.dp)
            }
        }
    }
}

@Composable
private fun HomePage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group("Düzen") {
        ChoiceRow("Sütun sayısı", listOf(3, 4, 5), s.columns) { v -> onChange { it.copy(columns = v) } }
        RowDivider()
        ChoiceRow("En çok satır", listOf(5, 6, 7), s.rows) { v -> onChange { it.copy(rows = v) } }
    }
    Footer("Satır sayısı ekran yüksekliğine göre gerekirse azaltılır.")
    Group(null) {
        ToggleRow("Ana ekranı yan çevirince döndür", s.allowRotation, default = Def.allowRotation) { v -> onChange { it.copy(allowRotation = v) } }
    }
    Footer("Kapalıyken ana ekran iOS'taki gibi hep dikey kalır (uygulamalar yine döner).")
    Group("Aralıklar") {
        SliderRow("Yatay aralık", pct(s.hSpacing), s.hSpacing, 0.8f..1.3f, default = Def.hSpacing) { v -> onChange { it.copy(hSpacing = v) } }
        RowDivider()
        SliderRow("Dikey aralık", pct(s.vSpacing), s.vSpacing, 0.8f..1.4f, default = Def.vSpacing) { v -> onChange { it.copy(vSpacing = v) } }
    }
    Footer("Uygulama adlarının yazı ayarları \"Yazı Tipi\" sayfasında.")
    Group("Gezinti çubuğu") {
        ToggleRow("Ana ekranda gizle", s.hideNavHandle, default = Def.hideNavHandle) { v -> onChange { it.copy(hideNavHandle = v) } }
    }
    Footer("Hareketle gezintide alttaki ince çubuk yalnızca ana ekrandayken gizlenir; uygulamalarda görünür. Üç tuşlu gezintide tuşlar her zaman görünür.")
    BadgeGroup(s, onChange)
}

/** Simgelerde bildirim sayıları: aç/kapat + bildirim erişimi izni. */
@Composable
private fun BadgeGroup(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val granted = remember(tick) { dev.glass.launcher.service.BadgeListener.isGranted(ctx) }
    Group("Bildirim Sayıları") {
        ToggleRow("Simgelerde bildirim sayısı", s.showBadges, default = Def.showBadges) { v -> onChange { it.copy(showBadges = v) } }
        RowDivider()
        if (granted) {
            InfoRow("Bildirim erişimi", "İzin verildi")
        } else {
            ActionRow("1. Kısıtlanmış ayarlara izin ver") {
                // Mağaza dışından yüklenen uygulamalarda Android 13+ bildirim erişimini kilitler: önce uygulama
                // bilgisi > ⋮ > "Kısıtlanmış ayarlara izin ver".
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + ctx.packageName))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            RowDivider()
            ActionRow("2. Bildirim erişimini aç") {
                // Doğrudan bildirim erişimi listesi (bazı telefonlar ayrıntı ekranı yerine uygulamanın kendi
                // bildirim ayarını açıyordu).
                val list = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(list) }
            }
        }
    }
    if (!granted) Footer(
        "Rozetler için \"Bildirim erişimi\" gerekir (uygulamanın kendi bildirim izni değil). " +
            "1) İlk satır uygulama bilgisini açar: sağ üstteki ⋮ menüsünden \"Kısıtlanmış ayarlara izin ver\" seç " +
            "(menüde yoksa bu adımı atla). 2) İkinci satırda listeden Glass Launcher'ı aç."
    )
    Footer(
        "Kırmızı rozet, uygulamanın okunmamış bildirim sayısını gösterir; klasörde içindekilerin toplamı görünür. " +
            "Launcher yalnızca uygulama başına sayıyı tutar; bildirimlerin içeriği okunmaz, saklanmaz, hiçbir yere gönderilmez."
    )
}

@Composable
private fun SearchPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val perm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }
    // Ekrana geri dönüldüğünde (ör. tüm dosyalara erişim ayarından) izin durumunu yenile.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val contactsOk = remember(tick) { dev.glass.launcher.data.DeviceSearch.canContacts(ctx) }
    val smsOk = remember(tick) { dev.glass.launcher.data.DeviceSearch.canMessages(ctx) }
    val filesOk = remember(tick) { dev.glass.launcher.data.DeviceSearch.canFiles(ctx) }
    val allFiles = remember(tick) { dev.glass.launcher.data.DeviceSearch.allFiles() }
    fun status(ok: Boolean) = if (ok) "İzin verildi" else "İzin yok"

    Group("Görünüm") {
        ToggleRow("Önerilerde uygulama adları", s.searchShowLabels, default = Def.searchShowLabels) { v -> onChange { it.copy(searchShowLabels = v) } }
        RowDivider()
        ToggleRow("Sonuç paneli: nötr renk", s.searchNeutral, default = Def.searchNeutral) { v -> onChange { it.copy(searchNeutral = v) } }
        RowDivider()
        ToggleRow("Sonuçlar cam panel içinde", s.searchResultsPanel, default = Def.searchResultsPanel) { v -> onChange { it.copy(searchResultsPanel = v) } }
        RowDivider()
        SliderRow(
            "Sonuç paneli parlaklığı",
            when { s.searchGlassBrightness > 0.01f -> "+${(s.searchGlassBrightness * 100).roundToInt()}"; s.searchGlassBrightness < -0.01f -> "${(s.searchGlassBrightness * 100).roundToInt()}"; else -> "0" },
            s.searchGlassBrightness, -1f..1f, default = Def.searchGlassBrightness,
        ) { v -> onChange { it.copy(searchGlassBrightness = v) } }
        RowDivider()
        SliderRow("Öneri paneli köşesi", pct(s.searchSuggestCorner / 0.5f), s.searchSuggestCorner, 0.05f..0.5f, default = Def.searchSuggestCorner) { v ->
            onChange { it.copy(searchSuggestCorner = v) }
        }
    }
    Footer("Öneri paneli ve arama çubuğu dock ile birebir aynı camı kullanır; Dock ayarları (saydamlık, ton) onları da değiştirir. Nötr renk ve parlaklık yalnızca sonuç panelini etkiler. Öneri paneli köşesi dock'tan bağımsızdır. Yazı ayarları: Yazı Tipi > Arama.")
    Group("Telefonda ara") {
        ToggleRow("Kişiler (${status(contactsOk)})", s.searchContacts, default = Def.searchContacts) { v ->
            onChange { it.copy(searchContacts = v) }
            if (v && !contactsOk) perm.launch(arrayOf(android.Manifest.permission.READ_CONTACTS))
        }
        RowDivider()
        ToggleRow("Mesajlar (${status(smsOk)})", s.searchMessages, default = Def.searchMessages) { v ->
            onChange { it.copy(searchMessages = v) }
            if (v && !smsOk) perm.launch(arrayOf(android.Manifest.permission.READ_SMS))
        }
        RowDivider()
        ToggleRow("Dosyalar (${status(filesOk)})", s.searchFiles, default = Def.searchFiles) { v ->
            onChange { it.copy(searchFiles = v) }
            if (v && !filesOk) perm.launch(dev.glass.launcher.data.DeviceSearch.mediaPermissions)
        }
        RowDivider()
        ActionRow(if (allFiles) "Belgeler de aranıyor (tüm dosyalar)" else "Belgeleri de ara: tüm dosyalara erişim ver") {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.packageName),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        RowDivider()
        ActionRow("Eksik izinleri iste") {
            val missing = dev.glass.launcher.data.DeviceSearch.missingPermissions(ctx, s.searchContacts, s.searchMessages, s.searchFiles)
            if (missing.isNotEmpty()) perm.launch(missing.toTypedArray())
            onChange { it.copy(searchDeviceCard = true) }
        }
    }
    Footer(
        "Kişiler, mesajlar ve dosyalar yalnızca telefonunda, çevrimdışı aranır; hiçbir veri dışarı gönderilmez. " +
            "Telefona dışarıdan kurulan uygulamalarda Android, SMS iznini \"kısıtlı ayar\" olarak engelleyebilir: " +
            "Uygulama bilgisi → ⋮ → \"Kısıtlı ayarlara izin ver\" seçip tekrar dene. Fotoğraf, video ve müzikler medya izniyle; " +
            "PDF gibi belgeler ise \"tüm dosyalara erişim\" izniyle aranır."
    )
}

@Composable
private fun MenuPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group("Görünüm") {
        ToggleRow("Koyu menü", s.menuDark, default = Def.menuDark) { v -> onChange { it.copy(menuDark = v) } }
        RowDivider()
        SliderRow("Cam açıklığı", pct(s.menuOpacity), s.menuOpacity, 0.1f..0.95f, default = Def.menuOpacity) { v -> onChange { it.copy(menuOpacity = v) } }
        RowDivider()
        SliderRow("Menü bulanıklığı", if (s.menuBlur < 0.01f) "Kapalı" else pct(s.menuBlur), s.menuBlur, 0f..2f, default = Def.menuBlur) { v -> onChange { it.copy(menuBlur = v) } }
        RowDivider()
        SliderRow("Köşe yuvarlaklığı", "${s.menuCorner.roundToInt()} pt", s.menuCorner, 10f..44f, default = Def.menuCorner) { v -> onChange { it.copy(menuCorner = v) } }
    }
    Group("Boyutlar") {
        SliderRow("Genişlik", "${s.menuWidth.roundToInt()} pt", s.menuWidth, 200f..330f, default = Def.menuWidth) { v -> onChange { it.copy(menuWidth = v) } }
        RowDivider()
        SliderRow("Satır yüksekliği", "${s.menuRowHeight.roundToInt()} pt", s.menuRowHeight, 36f..64f, default = Def.menuRowHeight) { v -> onChange { it.copy(menuRowHeight = v) } }
        RowDivider()
        SliderRow("Yazı boyutu", "${s.menuTextSize.roundToInt()} pt", s.menuTextSize, 13f..22f, default = Def.menuTextSize) { v -> onChange { it.copy(menuTextSize = v) } }
    }
    Footer("Bir simgeye basılı tutunca açılan menü. Bulanıklık, kenar çizgisi ve parlaklık Liquid Glass sayfasındaki genel ayarları izler.")
}

@Composable
private fun FontsPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group("Uygulama adları") {
        ToggleRow("Adları göster", s.showLabels, default = Def.showLabels) { v -> onChange { it.copy(showLabels = v) } }
        RowDivider()
        SliderRow("Simgeye uzaklık", "${s.labelGap.toInt()} pt", s.labelGap, 0f..14f, default = Def.labelGap) { v -> onChange { it.copy(labelGap = v) } }
        RowDivider()
        SliderRow("Yazı boyutu", "${"%.1f".format(Locale.US, s.labelSize)} pt", s.labelSize, 9f..16f, default = Def.labelSize) { v ->
            onChange { it.copy(labelSize = v) }
        }
        RowDivider()
        SliderRow("Yazı kalınlığı", fontWeightName(s.labelWeight), s.labelWeight, 300f..700f, default = Def.labelWeight) { v ->
            onChange { it.copy(labelWeight = v) }
        }
    }
    Group("Arama düğmesi (dock üstü)") {
        SliderRow("Yazı boyutu", "${s.pillTextSize.roundToInt()} pt", s.pillTextSize, 9f..24f, default = Def.pillTextSize) { v ->
            onChange { it.copy(pillTextSize = v) }
        }
        RowDivider()
        SliderRow("Yazı kalınlığı", fontWeightName(s.pillTextWeight), s.pillTextWeight, 300f..700f, default = Def.pillTextWeight) { v ->
            onChange { it.copy(pillTextWeight = v) }
        }
    }
    Group("Düzenleme düğmeleri (+, Ayarlar, Bitti)") {
        SliderRow("Yazı boyutu", "${s.editButtonTextSize.roundToInt()} pt", s.editButtonTextSize, 10f..20f, default = Def.editButtonTextSize) { v ->
            onChange { it.copy(editButtonTextSize = v) }
        }
        RowDivider()
        SliderRow("Yazı kalınlığı", fontWeightName(s.editButtonWeight), s.editButtonWeight, 300f..700f, default = Def.editButtonWeight) { v ->
            onChange { it.copy(editButtonWeight = v) }
        }
    }
    Group("Yazı gölgesi") {
        ToggleRow("Gölge", s.textShadow, default = Def.textShadow) { v -> onChange { it.copy(textShadow = v) } }
        if (s.textShadow) {
            RowDivider()
            SliderRow("Yoğunluk", pct(s.textShadowStrength / 0.35f), s.textShadowStrength, 0.05f..0.9f, default = Def.textShadowStrength) { v ->
                onChange { it.copy(textShadowStrength = v) }
            }
            RowDivider()
            SliderRow("Etrafa yayılma", pct(s.textShadowSpread), s.textShadowSpread, 0.3f..3f, default = Def.textShadowSpread) { v ->
                onChange { it.copy(textShadowSpread = v) }
            }
        }
    }
    Footer("Düzenleme düğmeleri: ana ekranda boş alana basılı tutunca üstte çıkan düğmeler. Kapalıyken yazıların etrafında çerçeve/gölge olmaz. Yayılma arttıkça gölge daha geniş ve yumuşak olur.")
    Group("Arama") {
        SliderRow("Arama kutusu yazı boyutu", "${s.searchFieldTextSize.roundToInt()} pt", s.searchFieldTextSize, 12f..22f, default = Def.searchFieldTextSize) { v ->
            onChange { it.copy(searchFieldTextSize = v) }
        }
        RowDivider()
        SliderRow("Sonuç yazı boyutu", "${s.searchResultTextSize.roundToInt()} pt", s.searchResultTextSize, 12f..22f, default = Def.searchResultTextSize) { v ->
            onChange { it.copy(searchResultTextSize = v) }
        }
        RowDivider()
        SliderRow("Sonuç yazı kalınlığı", fontWeightName(s.searchResultWeight), s.searchResultWeight, 300f..700f, default = Def.searchResultWeight) { v ->
            onChange { it.copy(searchResultWeight = v) }
        }
        RowDivider()
        SliderRow("Öneri adları yazı boyutu", "${s.searchSuggestLabelSize.roundToInt()} pt", s.searchSuggestLabelSize, 8f..18f, default = Def.searchSuggestLabelSize) { v ->
            onChange { it.copy(searchSuggestLabelSize = v) }
        }
        RowDivider()
        SliderRow("Öneri adları yazı kalınlığı", fontWeightName(s.searchSuggestLabelWeight), s.searchSuggestLabelWeight, 300f..700f, default = Def.searchSuggestLabelWeight) { v ->
            onChange { it.copy(searchSuggestLabelWeight = v) }
        }
    }
    Group("Klasörler") {
        SliderRow("Açık klasör adı boyutu", "${s.openFolderTitleSize.roundToInt()} pt", s.openFolderTitleSize, 10f..40f, default = Def.openFolderTitleSize) { v ->
            onChange { it.copy(openFolderTitleSize = v) }
        }
    }
    Footer("Yazı tipi: Inter (SF Pro'ya en yakın açık lisanslı yazı tipi).")
}

private val TintPalette = listOf(
    0xFF64D2FF, 0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158,
).map { it.toInt() }

@Composable
private fun IconsPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    // Simge paketi (Play Store'daki Nova / ADW uyumlu paketler).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val packs = remember { dev.glass.launcher.data.IconPacks.installed(ctx) }
    Group("Simge paketi") {
        PackRow("Yok (uygulamaların kendi simgeleri)", s.iconPack.isEmpty()) { onChange { it.copy(iconPack = "") } }
        packs.forEach { p ->
            RowDivider()
            PackRow(p.label, s.iconPack == p.packageName) {
                dev.glass.launcher.data.IconPacks.clear()
                onChange { it.copy(iconPack = p.packageName) }
            }
        }
    }
    Footer(
        if (packs.isEmpty()) "Yüklü simge paketi yok. Play Store'dan Nova veya ADW uyumlu bir simge paketi yükleyince burada görünür."
        else "Paketin içermediği uygulamalar kendi simgesiyle kalır. Koyu, renkli ve şeffaf görünümler paket simgelerine de uygulanır."
    )
    Group("Boyut") {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Küçük" to 0.9f, "Normal" to 1.1f, "Büyük" to 1.25f).forEach { (t, v) ->
                Chip(t, kotlin.math.abs(s.iconScale - v) < 0.02f, Modifier.weight(1f)) { onChange { it.copy(iconScale = v) } }
            }
        }
        RowDivider()
        SliderRow("Simge boyutu", pct(s.iconScale), s.iconScale, 0.7f..1.4f, default = Def.iconScale) { v -> onChange { it.copy(iconScale = v) } }
    }
    Group("Görünüm") {
        Text("Açık modda", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconStyle.entries.forEach { st ->
                Chip(st.title, s.iconStyle == st, Modifier.weight(1f)) { onChange { it.copy(iconStyle = st) } }
            }
        }
        RowDivider()
        Text("Koyu modda", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconStyle.entries.forEach { st ->
                Chip(st.title, s.iconStyleDark == st, Modifier.weight(1f)) { onChange { it.copy(iconStyleDark = st) } }
            }
        }
        if (s.iconStyle == IconStyle.Tinted || s.iconStyleDark == IconStyle.Tinted) {
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                // Otomatik: duvar kâğıdının vurgu rengi (Material You gibi), duvar kâğıdı değişince kendiliğinden.
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Brush.sweepGradient(listOf(Color(0xFFFF375F), Color(0xFFFFD60A), Color(0xFF30D158), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF375F))))
                        .then(if (s.tintColor == 0) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                        .clickable { onChange { it.copy(tintColor = 0) } },
                    contentAlignment = Alignment.Center,
                ) { Text("A", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                TintPalette.forEach { c ->
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .then(if (s.tintColor == c) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                            .clickable { onChange { it.copy(tintColor = c) } }
                    )
                }
            }
        }
    }
    Footer(
        "Telefon koyu moda geçince \"Koyu modda\" seçimi, açık moda dönünce \"Açık modda\" seçimi kendiliğinden kullanılır. " +
            "Koyu: iOS gibi düz zemin koyulaşır, beyaz semboller zeminin rengini alır, siyah yazılar açılır; fotoğraf gibi " +
            "simgeler yalnızca karartılır. Tek bir uygulamayı koyu modda orijinal bırakmak için simgesine basılı tut. " +
            "Renkli: simge tek renk çizilir."
    )
    Group("Şekil") {
        SliderRow("Köşe yuvarlaklığı", pct(s.iconCorner / 0.2237f), s.iconCorner, 0.05f..0.5f, default = Def.iconCorner) { v ->
            onChange { it.copy(iconCorner = v) }
        }
        RowDivider()
        SliderRow("Köşe yumuşaklığı (iOS)", pct(s.cornerSmoothing), s.cornerSmoothing, 0f..1f, default = Def.cornerSmoothing) { v ->
            onChange { it.copy(cornerSmoothing = v) }
        }
        RowDivider()
        SliderRow("Simge kenar parlaklığı", pct(s.iconRimGlow), s.iconRimGlow, 0f..2f, default = Def.iconRimGlow) { v ->
            onChange { it.copy(iconRimGlow = v) }
        }
        RowDivider()
        SliderRow(
            "Simge kenar çizgisi kalınlığı",
            if (s.iconRimWidth < 0.05f) "Yok" else String.format(java.util.Locale("tr"), "%.1f pt", s.iconRimWidth),
            s.iconRimWidth, 0f..3f, default = Def.iconRimWidth,
        ) { v -> onChange { it.copy(iconRimWidth = v) } }
        RowDivider()
        ToggleRow("Yuvarlak simgeleri kareye tamamla", s.iconFillRound, default = Def.iconFillRound) { v -> onChange { it.copy(iconFillRound = v) } }
        RowDivider()
        ToggleRow("Cam kenar ışığı", s.iconGlass, default = Def.iconGlass) { v -> onChange { it.copy(iconGlass = v) } }
    }
    Group("Canlı simgeler") {
        ToggleRow("Canlı saat ve takvim simgeleri", s.liveIcons, default = Def.liveIcons) { v -> onChange { it.copy(liveIcons = v) } }
        RowDivider()
        ToggleRow("Hava durumu simgesinde sıcaklık", s.weatherIcon, default = Def.weatherIcon) { v -> onChange { it.copy(weatherIcon = v) } }
        if (s.liveIcons) {
            RowDivider()
            ToggleRow("Saatte saniye ibresi", s.liveClockSeconds, default = Def.liveClockSeconds) { v -> onChange { it.copy(liveClockSeconds = v) } }
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "Otomatik", "light" to "Açık", "dark" to "Koyu").forEach { (k, t) ->
                    Chip(t, s.liveIconTheme == k, Modifier.weight(1f)) { onChange { it.copy(liveIconTheme = k) } }
                }
            }
        }
    }
    Footer("Saat simgesinin ibreleri gerçek saati gösterir, Takvim simgesi her gün bugünün tarihini gösterir. Görünüm \"Otomatik\"te seçili simge görünümüne (açık, koyu, renkli, şeffaf) uyar. Simgesi elle değiştirilmiş uygulamalarda kullanılmaz.")
    Footer("Tek bir uygulamanın simgesini değiştirmek için simgesine basılı tutup \"Simgeyi Değiştir\"i seçin.")
}

@Composable
private fun DockPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group(null) {
        SliderRow("Yükseklik", pct(s.dockHeight), s.dockHeight, 0.8f..1.3f, default = Def.dockHeight) { v -> onChange { it.copy(dockHeight = v) } }
        RowDivider()
        SliderRow("Genişlik", pct(s.dockWidth), s.dockWidth, 0.85f..1.05f, default = Def.dockWidth) { v -> onChange { it.copy(dockWidth = v) } }
        RowDivider()
        SliderRow("Cam yoğunluğu", pct(s.dockOpacity), s.dockOpacity, 0f..2f, default = Def.dockOpacity) { v -> onChange { it.copy(dockOpacity = v) } }
        RowDivider()
        SliderRow("Alt boşluk", "${s.dockBottom.toInt()} pt", s.dockBottom, 0f..60f, default = Def.dockBottom) { v -> onChange { it.copy(dockBottom = v) } }
        RowDivider()
        SliderRow("Köşe yuvarlaklığı", pct(s.dockCorner / 0.5f), s.dockCorner, 0.08f..0.5f, default = Def.dockCorner) { v -> onChange { it.copy(dockCorner = v) } }
    }
    Group("Arama düğmesi") {
        SliderRow("Boyut", pct(s.pillScale), s.pillScale, 0.7f..1.5f, default = Def.pillScale) { v -> onChange { it.copy(pillScale = v) } }
        RowDivider()
        val pos = when {
            s.pillOffset > 0.5f -> "${s.pillOffset.toInt()} pt yukarı"
            s.pillOffset < -0.5f -> "${(-s.pillOffset).toInt()} pt aşağı"
            else -> "Ortada"
        }
        SliderRow("Konum", pos, s.pillOffset, -60f..60f, default = Def.pillOffset) { v -> onChange { it.copy(pillOffset = v) } }
    }
    Footer("Konum \"Ortada\" iken arama düğmesi, simge dizisi ile dock arasında tam ortada durur.")
    Footer("Dock simgelerini değiştirmek için düzenleme modunda simgeleri dock'a sürükleyin.")
}

@Composable
private fun FoldersPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group("Uygulama arşivi") {
        ToggleRow("Uygulama arşivi sayfası", s.showLibrary, default = Def.showLibrary) { v -> onChange { it.copy(showLibrary = v) } }
    }
    Group(null) {
        SliderRow("Klasör cam yoğunluğu", pct(s.folderOpacity), s.folderOpacity, 0f..2f, default = Def.folderOpacity) { v ->
            onChange { it.copy(folderOpacity = v) }
        }
        RowDivider()
        SliderRow("Açık klasör boyutu", pct(s.folderSize), s.folderSize, 0.55f..0.95f, default = Def.folderSize) { v ->
            onChange { it.copy(folderSize = v) }
        }
        RowDivider()
        SliderRow("Açık klasör köşe yuvarlaklığı", "${s.folderCorner.roundToInt()} pt", s.folderCorner, 8f..80f, default = Def.folderCorner) { v ->
            onChange { it.copy(folderCorner = v) }
        }
    }
    Footer("Klasör oluşturmak için bir simgeyi başka bir simgenin üzerine sürükleyin. Klasör adına dokunarak değiştirebilirsiniz.")
}

@Composable
private fun GlassPage(vm: LauncherViewModel, s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group("Cam stili") {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("clear" to "Şeffaf", "frosted" to "Buzlu").forEach { (k, t) ->
                Chip(t, s.glassStyle == k, Modifier.weight(1f)) { onChange { it.copy(glassStyle = k) } }
            }
        }
    }
    Footer(
        "Şeffaf: iOS 26'daki gibi camın arkası net görünür, kenarlarda mercek gibi bükülür (düzenleme düğmeleri gibi). " +
            "Buzlu: arkası güçlü bulanık ve beyazımsı. Şeffafta yazılar okunur kalsın diye hafif yazı gölgesi kendiliğinden açılır."
    )
    Group("Arama ve Uygulama Arşivi camı") {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("general" to "Genel cam ayarları", "dock" to "Dock ile eşit").forEach { (k, t) ->
                Chip(t, s.panelGlassSync == k, Modifier.weight(1f)) { onChange { it.copy(panelGlassSync = k) } }
            }
        }
    }
    Footer(
        "Arama paneli, arama çubuğu ve arşiv kartları her iki seçenekte de aşağıdaki cam ayarlarını kullanır. " +
            "Dock ile eşit: ayrıca Dock > Cam yoğunluğu ayarı da uygulanır, hepsi dock ile aynı yoğunlukta görünür."
    )
    Group(null) {
        SliderRow("Cam yoğunluğu", pct(s.glassStrength), s.glassStrength, 0.5f..1.5f, default = Def.glassStrength) { v -> onChange { it.copy(glassStrength = v) } }
        RowDivider()
        SliderRow("Beyaz çizgi kalınlığı", "${"%.1f".format(s.glassRimWidth)} pt", s.glassRimWidth, 0f..4f, default = Def.glassRimWidth) { v ->
            onChange { it.copy(glassRimWidth = v) }
        }
        RowDivider()
        SliderRow("Bulanıklık şiddeti", pct(s.glassBlur), s.glassBlur, 0f..2f, default = Def.glassBlur) { v -> onChange { it.copy(glassBlur = v) } }
        RowDivider()
        SliderRow(
            "Parlaklık",
            when { s.glassBrightness > 0.01f -> "+${(s.glassBrightness * 100).roundToInt()}"; s.glassBrightness < -0.01f -> "${(s.glassBrightness * 100).roundToInt()}"; else -> "0" },
            s.glassBrightness, -1f..1f, default = Def.glassBrightness,
        ) { v -> onChange { it.copy(glassBrightness = v) } }
        RowDivider()
        SliderRow("Kenar ışığı", pct(s.glassRimGlow), s.glassRimGlow, 0f..2f, default = Def.glassRimGlow) { v -> onChange { it.copy(glassRimGlow = v) } }
        RowDivider()
        SliderRow("Dolgu yoğunluğu (saydamlık)", pct(s.glassOpacity), s.glassOpacity, 0f..2f, default = Def.glassOpacity) { v -> onChange { it.copy(glassOpacity = v) } }
    }
    SystemWallpaperSyncGroup(vm, s, onChange)
    Group("iOS 26 / 27 efektleri") {
        SliderRow("Kenar kırılması (mercek)", if (s.glassRefraction < 0.01f) "Kapalı" else pct(s.glassRefraction), s.glassRefraction, 0f..1f, default = Def.glassRefraction) { v ->
            onChange { it.copy(glassRefraction = v) }
        }
        RowDivider()
        SliderRow("Kenar kalınlığı", pct(s.glassEdgeWidth / 0.6f), s.glassEdgeWidth, 0.15f..0.6f, default = Def.glassEdgeWidth) { v ->
            onChange { it.copy(glassEdgeWidth = v) }
        }
        RowDivider()
        SliderRow("Işık yönü", lightDirLabel(s.glassLightAngle), s.glassLightAngle, 0f..359f, default = Def.glassLightAngle) { v ->
            onChange { it.copy(glassLightAngle = v) }
        }
        RowDivider()
        SliderRow("Karşı kenar yansıması", if (s.glassLightBack < 0.01f) "Kapalı" else pct(s.glassLightBack), s.glassLightBack, 0f..1f, default = Def.glassLightBack) { v ->
            onChange { it.copy(glassLightBack = v) }
        }
        RowDivider()
        SliderRow("Duvar kâğıdı rengi", if (s.glassAccent < 0.01f) "Kapalı" else pct(s.glassAccent), s.glassAccent, 0f..1f, default = Def.glassAccent) { v ->
            onChange { it.copy(glassAccent = v) }
        }
        RowDivider()
        SliderRow("Alt temas gölgesi", if (s.glassContactShadow < 0.01f) "Kapalı" else pct(s.glassContactShadow), s.glassContactShadow, 0f..1f, default = Def.glassContactShadow) { v ->
            onChange { it.copy(glassContactShadow = v) }
        }
        RowDivider()
        SliderRow("Koyu derinlik kenarı", if (s.glassEdgeDark < 0.01f) "Kapalı" else pct(s.glassEdgeDark), s.glassEdgeDark, 0f..1f, default = Def.glassEdgeDark) { v ->
            onChange { it.copy(glassEdgeDark = v) }
        }
    }
    Footer(
        "Kenar kırılması: camın kenarlarında arkadaki görüntü mercekteki gibi bükülür. Dock ve klasörlerde launcher " +
            "duvar kâğıdı seçiliyken, menü, arama ve açık klasörde her zaman çalışır. Koyu kenar: iOS 27'deki gibi " +
            "cam yüzeyleri zeminden ayıran ince koyu bant."
    )
    Group("Tema") {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { t -> Chip(t.title, s.theme == t, Modifier.weight(1f)) { onChange { it.copy(theme = t) } } }
        }
    }
    Footer("Otomatik: telefonun açık/koyu moduna uyar. Koyu temada cam yüzeyler koyu tonlanır.")
    Group("Bulanıklık") {
        ToggleRow("Gerçek arka plan bulanıklığı", s.realBlur, default = Def.realBlur) { v -> onChange { it.copy(realBlur = v) } }
    }
    Footer(
        "Android, başka uygulamaların (sistem duvar kâğıdı dahil) arkasını bulanıklaştırmaya izin vermez. " +
            "Gerçek bulanıklık yalnızca Duvar Kâğıdı bölümünden launcher'a bir görsel seçtiğinizde çalışır."
    )
}

@Composable
private fun WallpaperPage(vm: LauncherViewModel, s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val ctx = LocalContext.current
    val target = dev.glass.launcher.data.WallpaperTarget.of(s.wallpaperTarget)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setWallpaper(uri, target)
    }
    Group("Uygulanacak yer") {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.glass.launcher.data.WallpaperTarget.entries.forEach { t ->
                Chip(t.title, target == t, Modifier.weight(1f)) { onChange { it.copy(wallpaperTarget = t.code) } }
            }
        }
    }
    Group(null) {
        NavRow("Görsel seç", Glyph.Wallpaper, Color(0xFF34C759)) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    Footer(
        "Seçilen görsel hem launcher'da hem telefonun sistem duvar kâğıdında uygulanır. Ana ekranda boş bir yere " +
            "basılı tutup \"Duvar Kâğıdı\" ile de değiştirebilirsin; galeride bir fotoğrafı açıp ⋮ > \"Farklı ayarla\" > " +
            "\"Glass Launcher\" ile de olur."
    )
    if (s.launcherWallpaper) {
        Group(null) {
            SliderRow("Karartma", pct(s.wallpaperDim / 0.6f), s.wallpaperDim, 0f..0.6f, default = Def.wallpaperDim) { v -> onChange { it.copy(wallpaperDim = v) } }
            RowDivider()
            ActionRow("Launcher duvar kâğıdını kaldır", destructive = true) { vm.clearWallpaper() }
        }
    }
    Group(null) {
        ActionRow("Sistem duvar kâğıdı ayarlarını aç") {
            runCatching {
                ctx.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Duvar kâğıdı")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

@Composable
private fun AnimationsPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val label = when {
        s.animSpeed < 0.8f -> "Yavaş"
        s.animSpeed > 1.25f -> "Hızlı"
        else -> "Normal"
    }
    Group(null) {
        SliderRow("Animasyon hızı", label, s.animSpeed, 0.5f..2f, default = Def.animSpeed) { v -> onChange { it.copy(animSpeed = v) } }
    }
    Footer("Klasör, menü, arama ve sayfa geçişlerini etkiler.")
    Group(null) {
        ToggleRow("Simgeden açılış ve simgeye kapanış", s.appTransitions, default = Def.appTransitions) { v ->
            onChange { it.copy(appTransitions = v) }
        }
        RowDivider()
        ToggleRow("Kilit açılınca simgeler belirsin", s.unlockAnimation, default = Def.unlockAnimation) { v ->
            onChange { it.copy(unlockAnimation = v) }
        }
    }
    Footer(
        "Açıkken uygulama dokunduğun simgenin yerinden büyüyerek açılır, ana ekrana dönünce simgesinin yerine " +
            "küçülerek oturur. Kapalıyken telefonun standart açılış/kapanış animasyonu kullanılır."
    )
}

@Composable
private fun GesturesPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val ctx = LocalContext.current
    Group("Ekranın üstünden aşağı kaydırma") { SwipeChoices(s.topSwipe) { a -> onChange { it.copy(topSwipe = a) } } }
    Group("Ortadan (boş alandan) aşağı kaydırma") { SwipeChoices(s.midSwipe) { a -> onChange { it.copy(midSwipe = a) } } }
    Group("Ortadan yukarı kaydırma") {
        ToggleRow("Son kullanılanları aç", s.upSwipeRecents, default = Def.upSwipeRecents) { v -> onChange { it.copy(upSwipeRecents = v) } }
    }
    Footer("Telefonun kendi son kullanılanlar ekranı tek hareketle açılır (Erişilebilirlik izni gerekir).")
    Footer(
        "Kontrol Merkezi ve Bildirimler, telefonun KENDİ panelleridir. Güvenilir çalışması için aşağıdaki " +
            "Erişilebilirlik izni önerilir; izin kapalıyken de denenir ama bazı telefonlarda engellenebilir."
    )
    Group("Çift dokunma ve izin") {
        ToggleRow("Boş alana çift dokununca kilitle", s.doubleTapLock, default = Def.doubleTapLock) { v -> onChange { it.copy(doubleTapLock = v) } }
        RowDivider()
        val enabled = LockService.isEnabled(ctx)
        InfoRow("Erişilebilirlik izni", if (enabled) "Açık" else "Kapalı")
        if (!enabled) {
            RowDivider()
            ActionRow("İzni aç") {
                runCatching {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
    Footer(
        "Kilitleme için Android, Erişilebilirlik hizmetinin açılmasını ister: Ayarlar > Erişilebilirlik > Yüklü " +
            "uygulamalar > \"Glass Launcher – Kilit ve Kontrol Merkezi\". Hizmet ekran içeriğini okumaz; yalnızca " +
            "kilitleme ve panel açma komutlarını verir. Parmak izi/yüz tanıma ile açma çalışmaya devam eder."
    )
}

@Composable
private fun WidgetsPage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit, onAddWidget: () -> Unit) {
    Group(null) {
        NavRow("Widget ekle", Glyph.Plus, Color(0xFFFF375F)) { onAddWidget() }
    }
    Group("Widget sayfası") {
        ToggleRow("Widget sayfası (ilk sayfanın solunda)", s.showToday, default = Def.showToday) { v -> onChange { it.copy(showToday = v) } }
    }
    Footer(
        "Saat, analog saat, takvim, pil ve fotoğraf widget'ları launcher'ın kendi widget'larıdır. Diğer uygulamaların " +
            "gerçek Android widget'ları da eklenebilir; ilk eklemede sistem izin isteyebilir. Widget'ı taşımak için " +
            "sürükleyin, boyutunu değiştirmek veya kaldırmak için basılı tutup bırakın."
    )
}

@Composable
private fun LibraryPageSettings(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group(null) {
        ToggleRow("Yeni uygulamaları ana ekrana ekle", s.addNewToHome, default = Def.addNewToHome) { v -> onChange { it.copy(addNewToHome = v) } }
    }
    Footer("Kapalıyken yeni kurulan uygulamalar yalnızca Uygulama Arşivi'nde görünür. Kategoriler, uygulamanın bildirdiği türe ve adına göre tahmin edilir.")
    Group("Klasör kartları") {
        ToggleRow("Köşe yuvarlaklığı dock ile aynı", s.libraryTileCornerDock, default = Def.libraryTileCornerDock) { v ->
            onChange { it.copy(libraryTileCornerDock = v) }
        }
        if (!s.libraryTileCornerDock) {
            RowDivider()
            SliderRow("Köşe yuvarlaklığı", "${s.libraryTileCorner.roundToInt()} pt", s.libraryTileCorner, 8f..60f, default = Def.libraryTileCorner) { v ->
                onChange { it.copy(libraryTileCorner = v) }
            }
        }
    }
    Footer("Dock ile aynı açıkken arşivdeki büyük klasör kartları dock'un köşe ayarını izler (Dock > Köşe yuvarlaklığı).")
}

@Composable
private fun BackupPage(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = vm.exportBackup()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            status = if (ok) "Yedek kaydedildi." else "Yedek kaydedilemedi."
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            val ok = json != null && vm.importBackup(json)
            status = if (ok) "Yedek geri yüklendi." else "Dosya geçerli bir Glass Launcher yedeği değil."
        }
    }
    Group(null) {
        ActionRow("Ana ekranı dışa aktar (JSON)") { exporter.launch("glass-launcher-yedek.json") }
        RowDivider()
        ActionRow("Yedekten geri yükle") { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
    }
    status?.let { Footer(it) }
    Footer(
        "Yedekte yerleşim (sayfalar, klasörler, dock, widget'lar), tüm ayarlar, özel simgeler, widget fotoğrafları ve " +
            "launcher duvar kâğıdı bulunur. Sistem widget'ları (ör. Honor saat widget'ı) cihaza bağlı olduğu için yeni " +
            "telefonda yeniden eklenmelidir; geri yüklemede atlanır."
    )
}

@Composable
private fun PerformancePage(s: LauncherSettings, onChange: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Group(null) {
        ToggleRow("Otomatik kalite", s.autoQuality, default = Def.autoQuality) { v -> onChange { it.copy(autoQuality = v) } }
        RowDivider()
        ToggleRow("Düşük güç modu", s.lowPower, default = Def.lowPower) { v -> onChange { it.copy(lowPower = v) } }
    }
    Footer(
        "Otomatik kalite: kare süresi uzarsa veya telefon Pil Tasarrufu modundaysa cam efektleri (bulanıklık, " +
            "gölge, parlama) sadeleştirilir. Düşük güç modu bunları her zaman sadeleştirir ve sallanma/saniye " +
            "ibresi gibi sürekli animasyonları azaltır."
    )
    Group("Kaydırma ölçümü") {
        Text(FrameStats.text, color = Fg, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
    }
    Footer("Ana ekranda sayfa kaydırıp buraya dönün. 120 Hz ekranda akıcı kaydırmada ortalama 110–120 Hz ve az sayıda takılan kare görünmeli. Ortalama 60 Hz civarındaysa telefon launcher'ı 60 Hz'de çalıştırıyor demektir.")
}

@Composable
private fun AboutPage() {
    Group(null) {
        InfoRow("Sürüm", BuildConfig.VERSION_NAME)
        RowDivider()
        InfoRow("Lisans", "Kişisel kullanım; reklam ve takip yok")
    }
    Group("İzinler ve neden gerektikleri") {
        TextBlock(
            "• Uygulama listesi: yalnızca başlatılabilir uygulamalar sorgulanır (QUERY_ALL_PACKAGES kullanılmaz).\n" +
                "• REQUEST_DELETE_PACKAGES: \"Uygulamayı Sil\" seçildiğinde sistemin kaldırma onay penceresini açmak için. " +
                "Kaldırma kararını her zaman siz verirsiniz.\n" +
                "• SET_WALLPAPER: yalnızca \"Sistem duvar kâğıdı olarak da ayarla\" açıkken seçilen görseli uygulamak için.\n" +
                "• Erişilebilirlik hizmeti (isteğe bağlı): yalnızca boş alana çift dokununca ekranı kilitlemek için; " +
                "ekran içeriğini okumaz.\n" +
                "• İnternet izni yoktur; launcher tamamen çevrimdışı çalışır.\n" +
                "• Fotoğraf, duvar kâğıdı ve özel simge seçimi sistemin görsel seçicisiyle yapılır; depolama izni gerekmez.\n" +
                "• Widget bağlama izni Android tarafından widget eklerken bir kez sorulur."
        )
    }
    Group("Gizlilik") {
        TextBlock("Analitik, reklam veya takip yoktur. Tüm ayarlar ve yerleşim yalnızca telefonda saklanır; hiçbir veri dışarı gönderilmez.")
    }
    Group("Bilinen sınırlamalar (Android / MagicOS)") {
        TextBlock(
            "• Gerçek arka plan bulanıklığı yalnızca launcher'a seçilen duvar kâğıdı üzerinde çalışır.\n" +
                "• Bildirim gölgesi, Wi-Fi/Bluetooth anahtarları ve uygulama açılış animasyonu sisteme aittir; " +
                "üstten açılan panel bunlara kısayol verir.\n" +
                "• Uygulama kısayolları yalnızca varsayılan ana ekranken gelir.\n" +
                "• MagicOS pil yönetimi arka plandaki uygulamaları kapatabilir; launcher etkilenirse Ayarlar > Pil > " +
                "Uygulama başlatma bölümünden Glass Launcher'ı \"Elle yönet\" olarak ayarlayın.\n" +
                "• MagicOS'un kendi simge temaları bazı simgeleri plaka içinde verebilir; launcher bunu kırparak düzeltmeye çalışır."
        )
    }
}

// --- Ortak satır bileşenleri ---

private fun pct(v: Float) = "%${(v * 100).toInt()}"

@Composable
private fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) {
        Text(
            title.uppercase(Locale("tr", "TR")), color = Muted, fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp),
        )
    } else {
        Spacer(Modifier.height(20.dp))
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RowBg), content = content)
}

@Composable
private fun Footer(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
}

@Composable
private fun RowDivider(inset: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(Divider))
}

@Composable
private fun NavRow(title: String, glyph: Glyph, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(color), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, Color.White, 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text("›", color = Muted, fontSize = 22.sp)
    }
}

@Composable
private fun ActionRow(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        title, color = if (destructive) Color(0xFFFF453A) else Blue, fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
    )
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
        Text(title, color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, color = Muted, fontSize = 17.sp)
    }
}

@Composable
private fun TextBlock(text: String) {
    Text(text, color = Secondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(16.dp))
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, default: Boolean? = null, onChecked: (Boolean) -> Unit) {
    RegisterReset(default) { onChecked(it) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (default != null && checked != default) ResetButton { onChecked(default) }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF34C759),
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = LocalSettingsPalette.current.switchOff,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    default: Float? = null,
    onValue: (Float) -> Unit,
) {
    RegisterReset(default) { onValue(it) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (default != null && kotlin.math.abs(value - default) > 0.0005f) ResetButton { onValue(default) }
            Text(valueText, color = Muted, fontSize = 17.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Blue,
                inactiveTrackColor = LocalSettingsPalette.current.sliderOff,
            ),
        )
    }
}

/** "↺": ayarı varsayılan değerine döndürür. */
@Composable
private fun ResetButton(onClick: () -> Unit) {
    Text(
        "↺ Varsayılan", color = Blue, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Satırın sıfırlama işlemini açık sayfanın listesine kaydeder (sayfa sıfırlama düğmesi için). */
@Composable
private fun <T> RegisterReset(default: T?, apply: (T) -> Unit) {
    val reg = LocalResetRegistry.current ?: return
    if (default == null) return
    val latest by rememberUpdatedState(apply)
    DisposableEffect(reg, default) {
        val f: () -> Unit = { latest(default) }
        reg.add(f)
        onDispose { reg.remove(f) }
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                Box(
                    Modifier
                        .width(42.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (o == selected) Blue else ChipBg)
                        .clickable { onSelect(o) },
                    contentAlignment = Alignment.Center,
                ) { Text("$o", color = if (o == selected) Color.White else Fg, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Blue else ChipBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Color.White else Fg, fontSize = 13.sp, maxLines = 1, softWrap = false) }
}

@Composable
private fun SwipeChoices(selected: SwipeAction, onSelect: (SwipeAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwipeAction.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a -> Chip(a.title, selected == a, Modifier.weight(1f)) { onSelect(a) } }
            }
        }
    }
}


/** Ayarlardaki kaydırmanın kare ölçümü (Ayarlar > Performans'ta "Son kaydırma (Ayarlar)"). */
@Composable
private fun SettingsScrollStats(state: androidx.compose.foundation.ScrollState) {
    val view = LocalView.current
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) return@LaunchedEffect
        val refresh = view.display?.refreshRate?.takeIf { it > 1f } ?: 60f
        FrameStats.begin()
        val list = ArrayList<Long>()
        var last = 0L
        try {
            while (list.size < 600) {
                androidx.compose.runtime.withFrameNanos { t ->
                    if (last != 0L) list += t - last
                    last = t
                }
                // Android 15+: içerik hızını bildir; sistem (MagicOS dahil) kaydırmada 120 Hz'e çıkar.
                // Yalnızca istek yetmedi (ölçümde 60 Hz). Hız, gerçek kaydırma hızına yakın tutulur.
                if (android.os.Build.VERSION.SDK_INT >= 35) runCatching { view.frameContentVelocity = 4000f }
            }
        } finally {
            FrameStats.report(list.toLongArray(), refresh, "Son kaydırma (Ayarlar)")
        }
    }
}




/** Işık açısını okunur yöne çevirir (0° üst, saat yönünde). */
private fun lightDirLabel(a: Float): String {
    val names = listOf("Üst", "Sağ üst", "Sağ", "Sağ alt", "Alt", "Sol alt", "Sol", "Sol üst")
    val i = (((a + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
    return "${names[i]} (${a.toInt()}°)"
}


/**
 * Gizli ve kilitli uygulamalar. Gizli uygulama ana ekran, arşiv ve aramada görünmez; buradan açılır.
 * Kilitli uygulama launcher'dan açılırken parmak izi / yüz / ekran kilidi ister.
 */
@Composable
private fun PrivacyPage(vm: LauncherViewModel, s: LauncherSettings) {
    val all by vm.allApps.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val secure = remember { AppLock.deviceSecure(ctx as? android.app.Activity) }
    if (!secure) {
        Group(null) {
            TextBlock("Telefonunuzda ekran kilidi (PIN, desen veya şifre) yok. Kilit ve gizli uygulama koruması için önce telefon ayarlarından ekran kilidi oluşturun.")
        }
    }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    // İş profili: duraklatınca iş uygulamaları bildirim göndermez ve açılmaz (Pixel'deki gibi).
    profiles.filter { it.kind == dev.glass.launcher.data.ProfileKind.Work }.forEach { p ->
        Group("İş profili") {
            ToggleRow("İş uygulamaları açık", !p.quiet) { on -> vm.setProfileQuiet(p.user, !on) }
        }
        Footer("Kapatınca iş uygulamaları duraklatılır: bildirim gelmez, açılmaz. Arşivde \"İş\" kutusunda durur.")
    }
    // Özel Alan (Android 15+): uygulamaları yalnızca burada görünür; açmak için sistem kendi kilidini sorar.
    profiles.filter { it.kind == dev.glass.launcher.data.ProfileKind.Private }.forEach { p ->
        val privateApps = all.filter { it.profile == dev.glass.launcher.data.ProfileKind.Private && it.user == p.user }
        Group("Özel Alan") {
            ActionRow(if (p.quiet) "Özel Alanın Kilidini Aç" else "Özel Alanı Kilitle") { vm.setProfileQuiet(p.user, !p.quiet) }
            if (!p.quiet) privateApps.forEach { app ->
                RowDivider(inset = 60.dp)
                Row(
                    Modifier.fillMaxWidth().clickable { vm.launchUnlocked(app) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconImage(app, vm, 32.dp, false, 0f, badge = false)
                    Spacer(Modifier.width(12.dp))
                    Text(app.label, color = Fg, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        }
        Footer("Özel Alan uygulamaları ana ekranda, arşivde ve aramada hiç görünmez; yalnızca burada, kilit açıkken listelenir.")
    }
    val hidden = all.filter { it.key in s.hiddenApps }
    if (hidden.isNotEmpty()) {
        Group("Gizli uygulamalar") {
            hidden.forEachIndexed { i, app ->
                PrivacyAppRow(vm, app, s, openable = true)
                if (i < hidden.lastIndex) RowDivider(inset = 60.dp)
            }
        }
        Footer("Açmak için uygulamaya dokunun. Gizli uygulamalar ana ekranda, arşivde ve aramada görünmez.")
    }
    val listed = all.filter { it.profile != dev.glass.launcher.data.ProfileKind.Private }
    Group("Tüm uygulamalar") {
        listed.forEachIndexed { i, app ->
            PrivacyAppRow(vm, app, s, openable = false)
            if (i < listed.lastIndex) RowDivider(inset = 60.dp)
        }
    }
    Footer(
        "Kilitli uygulamalar launcher'dan açılırken telefonun parmak izi, yüz tanıma veya ekran kilidi sorulur. " +
            "Bildirimden ya da son kullanılanlardan açılışı yalnızca telefonun kendi Uygulama Kilidi engelleyebilir."
    )
}

@Composable
private fun PrivacyAppRow(vm: LauncherViewModel, app: dev.glass.launcher.data.AppInfo, s: LauncherSettings, openable: Boolean) {
    val isHidden = app.key in s.hiddenApps
    val isLocked = app.appKey in s.lockedApps
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable { vm.launchUnlocked(app) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconImage(app, vm, 32.dp, false, 0f, badge = false)
        Spacer(Modifier.width(12.dp))
        Text(app.label, color = Fg, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
        PrivacyChip("Gizli", isHidden) { vm.setHidden(app, !isHidden) }
        Spacer(Modifier.width(6.dp))
        PrivacyChip("Kilitli", isLocked) { vm.setLocked(app, !isLocked) }
    }
}

@Composable
private fun PrivacyChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Blue else LocalSettingsPalette.current.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (on) Color.White else Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}


@Composable
private fun PackRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1)
        if (selected) GlyphIcon(Glyph.Check, Blue, 18.dp)
    }
}
