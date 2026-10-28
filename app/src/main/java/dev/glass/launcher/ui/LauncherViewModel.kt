package dev.glass.launcher.ui

import dev.glass.launcher.data.withPlaced

import dev.glass.launcher.data.LaunchStats
import dev.glass.launcher.data.HomeItem

import android.app.Application
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Bundle
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.glass.launcher.data.Blurred
import dev.glass.launcher.data.IconLook
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.glass.launcher.LauncherApp
import dev.glass.launcher.data.AppCategory
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.IosDefaultLayout
import dev.glass.launcher.data.WallpaperApplier
import dev.glass.launcher.data.WallpaperTarget
import kotlinx.coroutines.flow.drop
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Uygulama Arşivi'nde bir kategori kutusu. */
data class LibraryGroup(val title: String, val apps: List<AppInfo>)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val la = app as LauncherApp
    private val repo = la.appRepository
    private val eager = SharingStarted.WhileSubscribed(5_000)

    /** Gizli uygulamalar çıkarılmış liste: ana ekran, arşiv ve arama bunu kullanır. */
    private val shownApps: StateFlow<List<AppInfo>> = combine(
        repo.apps, la.settingsStore.settings.map { it.hiddenApps }.distinctUntilChanged(),
    ) { list, hidden ->
        // Özel Alan uygulamaları hiçbir yerde görünmez (yalnızca Ayarlar > Gizli ve Kilitli Uygulamalar içinde).
        list.filter { it.profile != dev.glass.launcher.data.ProfileKind.Private && (hidden.isEmpty() || it.key !in hidden) }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val apps: StateFlow<List<AppInfo>> = shownApps
    /** Gizliler dahil tüm uygulamalar (yalnızca Ayarlar > Gizlilik). */
    val allApps: StateFlow<List<AppInfo>> = repo.apps
    /** İş profili ve Özel Alan durumları. */
    val profiles = repo.profiles
    fun setProfileQuiet(user: android.os.UserHandle, quiet: Boolean) = repo.setQuiet(user, quiet)

    /** Uygulamalar + ana ekrana sabitlenmiş kısayollar (anahtar → giriş). Gizli uygulamanın kısayolu da gizlenir. */
    val appsByKey: StateFlow<Map<String, AppInfo>> = combine(shownApps, repo.pinned) { list, pins ->
        HashMap<String, AppInfo>(list.size + pins.size).apply {
            list.forEach { put(it.key, it) }
            val shown = list.mapTo(HashSet()) { it.appKey }
            pins.forEach { if (it.appKey in shown) put(it.key, it) }
        }
    }.stateIn(viewModelScope, eager, emptyMap())

    /** Düzen uzlaştırmasında geçerli sayılan girişler (uygulamalar + sabitlenmiş kısayollar). */
    private fun entries(): List<AppInfo> = shownApps.value + repo.pinned.value

    /** Kısayol listesi güvenilir değilken (launcher varsayılan değil) kayıtlı kısayollar korunur. */
    private fun keepShortcutKeys(b: HomeLayout?): Set<String> =
        if (repo.pinnedReliable || b == null) emptySet() else b.homeKeys.filterTo(HashSet()) { AppInfo.isShortcutKey(it) }

    /** Ana ekrandan kaldırılan kısayolları sistemde de bırakır (sabitlemeyi kaldırır). */
    private fun unpinOrphans(next: HomeLayout) {
        if (!repo.pinnedReliable) return
        val home = next.homeKeys
        repo.pinned.value.filter { it.key !in home }.forEach { repo.unpin(it) }
    }

    /** Sabitlenmiş kısayolu ana ekrandan siler. */
    fun removeShortcut(app: AppInfo) {
        editLayout { it.dropKey(app.key) }
    }

    fun refreshPinned() = repo.refreshPinned()

    // --- Gizleme ve kilit ---

    fun setHidden(app: AppInfo, hidden: Boolean) = updateSettings { s ->
        s.copy(hiddenApps = if (hidden) s.hiddenApps + app.appKey else s.hiddenApps - app.appKey)
    }

    fun setLocked(app: AppInfo, locked: Boolean) = updateSettings { s ->
        s.copy(lockedApps = if (locked) s.lockedApps + app.appKey else s.lockedApps - app.appKey)
    }

    /** Gizli uygulamayı açar (Ayarlar > Gizlilik içinden; kimlik zaten doğrulandı). */
    fun launchUnlocked(app: AppInfo) {
        AppLock.markUnlocked(app.appKey)
        doLaunch(app, null, null)
    }

    val settings: StateFlow<LauncherSettings> = la.settingsStore.settings
        .stateIn(viewModelScope, eager, LauncherSettings())

    private val _layout = MutableStateFlow(HomeLayout())
    /** Tek doğruluk kaynağı: düzenlemeler anında buraya yazılır, ardından diske kaydedilir. */
    val layout: StateFlow<HomeLayout> = _layout

    private var base: HomeLayout? = null
    /** Kayıtlı yerleşim yüklü uygulamalarla en az bir kez uzlaştırıldı mı? */
    @Volatile private var layoutReady = false

    init {
        viewModelScope.launch {
            base = la.settingsStore.layoutJson.first()?.let { HomeLayout.fromJson(it) }
            val addNew = la.settingsStore.settings.map { it.addNewToHome }.distinctUntilChanged()
            combine(shownApps, repo.pinned, addNew) { list, pins, add -> Triple(list, pins, add) }.collect { (list, pins, add) ->
                if (list.isEmpty()) return@collect // liste henüz yüklenmedi: kayıtlı düzeni budama
                // İlk kurulum: iOS 26 fabrika dizilimi (karşılıklar + türe göre klasörler).
                if (base == null) base = IosDefaultLayout.build(la, list)
                // Son kontrolden SONRA yüklenen uygulamalar, Arşiv'de eski bir kaydı kalmış olsa bile (ör. daha önce
                // ana ekrandan kaldırılmış, launcher kapalıyken silinip yeniden yüklenmiş) ana ekranın sonuna gelir.
                val meta = la.getSharedPreferences("layout_meta", android.content.Context.MODE_PRIVATE)
                val seenAt = meta.getLong("appsSeenAt", 0L)
                val b0 = base
                if (add && seenAt > 0L && b0 != null) {
                    val fresh = list.filter { it.installTime > seenAt && it.key in b0.libraryOnly }.map { it.key }
                    if (fresh.isNotEmpty()) {
                        base = b0.copy(
                            items = b0.items.withPlaced(fresh.map { dev.glass.launcher.data.AppItem(it) }),
                            libraryOnly = b0.libraryOnly - fresh.toSet(),
                        )
                    }
                }
                meta.edit().putLong("appsSeenAt", maxOf(System.currentTimeMillis(), seenAt)).apply()
                val next = HomeLayout.reconcile(base, list + pins, repo.defaultDockPackages, add, keepShortcutKeys(base))
                val changed = next != b0
                base = next
                _layout.value = next
                layoutReady = true
                if (changed) la.settingsStore.saveLayout(next.toJson())
            }
        }
    }

    val recentApps: StateFlow<List<AppInfo>> = combine(shownApps, settings) { all, s ->
        s.recents.mapNotNull { r -> all.firstOrNull { it.componentName.flattenToString() == r } }
    }.stateIn(viewModelScope, eager, emptyList())

    /** Saat değiştikçe önerilerin yenilenmesi için (15 dakikada bir). */
    private val clockTick = kotlinx.coroutines.flow.flow {
        while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(15 * 60_000L) }
    }

    /**
     * Akıllı öneriler (iOS Siri önerileri gibi): bu saatte ve bu gün türünde (hafta içi/sonu) en çok açılan
     * uygulamalar; geçmiş azsa son kullanılanlarla tamamlanır. En çok 8 uygulama.
     */
    val suggestedApps: StateFlow<List<AppInfo>> = combine(shownApps, recentApps, LaunchStats.version, clockTick) { all, recent, _, _ ->
        val scores = LaunchStats.scores(la)
        val byComp = all.associateBy { it.componentName.flattenToString() }
        val ranked = scores.entries.sortedByDescending { it.value }.mapNotNull { byComp[it.key] }
        (ranked.take(8) + recent).distinctBy { it.key }.take(8)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, eager, emptyList())

    /**
     * Uygulama Arşivi grupları: Öneriler (son kullanılanlar), Son Eklenenler, ardından kategoriler.
     * Boş kategoriler gösterilmez.
     */
    val libraryGroups: StateFlow<List<LibraryGroup>> = combine(shownApps, suggestedApps) { all, recent ->
        val groups = ArrayList<LibraryGroup>()
        if (recent.isNotEmpty()) groups += LibraryGroup("Öneriler", recent)
        val newest = all.filter { it.installTime > 0 }.sortedByDescending { it.installTime }.take(8)
        if (newest.isNotEmpty()) groups += LibraryGroup("Son Eklenenler", newest)
        val (work, personal) = all.partition { it.profile == dev.glass.launcher.data.ProfileKind.Work }
        val byCat = personal.groupBy { it.category }
        AppCategory.entries.forEach { c ->
            byCat[c]?.takeIf { it.isNotEmpty() }?.let { groups += LibraryGroup(c.title, it) }
        }
        // İş profili uygulamaları kendi kutusunda (simgelerinde çanta rozeti).
        if (work.isNotEmpty()) groups += LibraryGroup("İş", work)
        groups
    }.stateIn(viewModelScope, eager, emptyList())

    val iconCache = la.iconCache

    private val _homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Launcher öndeyken Home tuşuna basıldı: katmanları kapat, ilk sayfaya dön. */
    val homePressed: SharedFlow<Unit> = _homePressed
    fun onHomePressed() { _homePressed.tryEmit(Unit) }

    /** Son açılan uygulama ve simgesinin ekrandaki yeri (dönüşte simgeye küçülme efekti için). */
    class LaunchRecord(val key: String, val rect: androidx.compose.ui.geometry.Rect?, val at: Long)

    @Volatile var lastLaunch: LaunchRecord? = null
        private set

    /** Simgenin ortalama rengi (dönüş efektinde kartın rengi). Saydam pikseller sayılmaz. */
    suspend fun iconColor(app: AppInfo, s: LauncherSettings): androidx.compose.ui.graphics.Color =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val bmp = la.iconCache.get(app, 64, iconLook(app, s))?.asAndroidBitmap()
                    ?: return@runCatching androidx.compose.ui.graphics.Color.White
                var r = 0L; var g = 0L; var b = 0L; var n = 0L
                val step = maxOf(1, bmp.width / 16)
                for (y in 0 until bmp.height step step) for (x in 0 until bmp.width step step) {
                    val c = bmp.getPixel(x, y)
                    if ((c ushr 24) < 200) continue
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                if (n == 0L) androidx.compose.ui.graphics.Color.White
                else androidx.compose.ui.graphics.Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            }.getOrDefault(androidx.compose.ui.graphics.Color.White)
        }

    /** Dönüş efekti bir kez oynatılır: kaydı alır ve siler (çok eskiyse yok sayar). */
    fun consumeLastLaunch(): LaunchRecord? {
        val r = lastLaunch ?: return null
        lastLaunch = null
        return r.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < 30 * 60_000L }
    }

    /**
     * Uygulamayı, dokunulan simgenin ekrandaki yerinden ([rect], kök koordinatlarında) büyüyerek açar.
     * Ayar kapalıysa veya konum yoksa sistemin varsayılan animasyonu kullanılır.
     */
    fun launchFrom(app: AppInfo, view: android.view.View, rect: androidx.compose.ui.geometry.Rect?) {
        val on = settings.value.appTransitions
        val r = rect?.takeIf { !it.isEmpty && it.width > 1f }
        val opts = if (on && r != null) runCatching {
            android.app.ActivityOptions.makeScaleUpAnimation(
                view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt(),
            ).toBundle()
        }.getOrNull() else null
        lastLaunch = if (on) LaunchRecord(app.key, r, android.os.SystemClock.elapsedRealtime()) else null
        launch(app, r?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }, opts)
    }

    /**
     * Launcher'ın kendi widget'ına dokununca ilgili uygulamayı açar (iOS gibi): saatler → Saat, takvim → Takvim,
     * hava durumu → telefonun hava durumu uygulaması, fotoğraf → galeri, pil → pil ayarları.
     * Uygulama widget'ın yerinden büyüyerek açılır.
     */
    fun openWidget(w: WidgetItem, view: android.view.View, rect: androidx.compose.ui.geometry.Rect?) {
        val ctx = getApplication<Application>()
        val pm = ctx.packageManager
        val list = apps.value
        fun byIntent(i: android.content.Intent): AppInfo? {
            val pkgs = runCatching { pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
            // Sistem/üretici uygulaması önce (ör. Honor Saat), sonra diğerleri.
            return pkgs.firstNotNullOfOrNull { p -> list.firstOrNull { it.packageName == p && !it.isShortcut && it.isSystem } }
                ?: pkgs.firstNotNullOfOrNull { p -> list.firstOrNull { it.packageName == p && !it.isShortcut } }
        }
        fun byName(vararg names: String): AppInfo? = list.firstOrNull { a ->
            !a.isShortcut && names.any { n -> dev.glass.launcher.data.AppCategorizer.normalize(a.label).contains(n) }
        }
        val app: AppInfo? = when (w.kind) {
            WidgetKind.DigitalClock, WidgetKind.AnalogClock, WidgetKind.GlassClock ->
                byIntent(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) ?: byName("saat", "clock")
            WidgetKind.Calendar ->
                byIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_APP_CALENDAR))
                    ?: byIntent(android.content.Intent(android.content.Intent.ACTION_VIEW, android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
                    ?: byName("takvim", "calendar")
            WidgetKind.Photo ->
                byIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_APP_GALLERY))
                    ?: byName("galeri", "gallery", "fotograf", "photos")
            WidgetKind.Weather -> byName("hava durumu", "weather")
            else -> null
        }
        if (app != null) { launchFrom(app, view, rect); return }
        // Uygulama karşılığı olmayanlar: pil → pil ayarları (ya da Ayarlar).
        val intent = when (w.kind) {
            WidgetKind.Battery -> android.content.Intent(android.content.Intent.ACTION_POWER_USAGE_SUMMARY)
            else -> null
        } ?: return
        val r = rect?.takeIf { !it.isEmpty }
        val opts = if (settings.value.appTransitions && r != null) runCatching {
            android.app.ActivityOptions.makeScaleUpAnimation(view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt()).toBundle()
        }.getOrNull() else null
        runCatching { ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), opts) }
            .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), opts) } }
    }

    fun launch(app: AppInfo, bounds: Rect? = null, options: Bundle? = null) {
        // Kilitli uygulama: önce parmak izi / yüz / ekran kilidi (kısayolu da sahibi olan uygulamaya göre).
        if (app.appKey in settings.value.lockedApps && !AppLock.recentlyUnlocked(app.appKey)) {
            AppLock.authenticate("${app.label} kilitli", "Açmak için kimliğinizi doğrulayın") {
                AppLock.markUnlocked(app.appKey)
                doLaunch(app, bounds, options)
            }
            return
        }
        doLaunch(app, bounds, options)
    }

    private fun doLaunch(app: AppInfo, bounds: Rect?, options: Bundle?) {
        repo.launch(app, bounds, options)
        if (app.isShortcut) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { LaunchStats.record(la, app.componentName.flattenToString()) }
        viewModelScope.launch { la.settingsStore.addRecent(app.componentName.flattenToString()) }
    }

    fun openAppInfo(app: AppInfo) = repo.openAppInfo(app)
    fun uninstall(app: AppInfo) = repo.uninstall(app)
    suspend fun shortcuts(app: AppInfo): List<ShortcutInfo> = repo.shortcuts(app)
    suspend fun shortcutIcon(info: ShortcutInfo, density: Int): Drawable? = repo.shortcutIcon(info, density)
    fun startShortcut(info: ShortcutInfo) = repo.startShortcut(info)

    // --- Simgeler ---

    /** Bu uygulama için canlı simge türü (ayar kapalıysa veya özel simge seçilmişse null). */
    fun liveKind(app: AppInfo, s: LauncherSettings): LiveIconKind? = when {
        !s.liveIcons || s.customIcons.containsKey(app.key) || app.isShortcut -> null
        app.packageName in repo.clockPackages -> LiveIconKind.Clock
        app.packageName in repo.calendarPackages -> LiveIconKind.Calendar
        // Hava durumu uygulaması: simgede o anki sıcaklık ve gökyüzü (veri varsa).
        s.weatherIcon && (app.packageName.contains("weather", true) || app.label.contains("hava durumu", true) || app.label.contains("weather", true)) -> LiveIconKind.Weather
        else -> null
    }

    /** [dark]: telefon koyu modda mı. Açık ve koyu mod için ayrı ayrı seçilen simge görünümü kullanılır. */
    fun iconLook(app: AppInfo, s: LauncherSettings, dark: Boolean = isSystemDark()): IconLook {
        val style = when {
            !dark -> s.iconStyle
            app.key in s.darkOriginalApps -> dev.glass.launcher.data.IconStyle.Original
            else -> s.iconStyleDark
        }
        // Renk 0 = otomatik: duvar kâğıdının vurgu rengi (Material You gibi).
        val tint = if (s.tintColor == 0) WallpaperAccent.color.value else s.tintColor
        return IconLook(style, tint, s.customIcons[app.key], s.iconFillRound, s.iconPack)
    }

    private fun isSystemDark(): Boolean =
        (la.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Bu uygulama koyu modda orijinal mi kalsın (basılı tutma menüsü). */
    fun toggleDarkOriginal(app: AppInfo) {
        updateSettings { s ->
            s.copy(darkOriginalApps = if (app.key in s.darkOriginalApps) s.darkOriginalApps - app.key else s.darkOriginalApps + app.key)
        }
    }

    fun setCustomIcon(app: AppInfo, uri: Uri) {
        viewModelScope.launch {
            val name = la.iconCache.importCustomIcon(app.key, uri) ?: return@launch
            la.settingsStore.update { st ->
                st.customIcons[app.key]?.let { old -> la.iconCache.deleteCustomIcon(old) }
                st.copy(customIcons = st.customIcons + (app.key to name))
            }
        }
    }

    fun resetCustomIcon(app: AppInfo) {
        viewModelScope.launch {
            la.settingsStore.update { st ->
                st.customIcons[app.key]?.let { old -> la.iconCache.deleteCustomIcon(old) }
                st.copy(customIcons = st.customIcons - app.key)
            }
        }
    }

    // --- Duvar kâğıdı (launcher'ın kendi kopyası; gerçek bulanıklık için) ---

    private val _wallpaper = MutableStateFlow<ImageBitmap?>(null)
    val wallpaper: StateFlow<ImageBitmap?> = _wallpaper

    private val _blurred = MutableStateFlow<Blurred?>(null)
    /** Cam yüzeylerin arkasında gösterilen bulanık duvar kâğıdı (yalnızca launcher duvar kâğıdı seçiliyse). */
    val blurredWallpaper: StateFlow<Blurred?> = _blurred

    /**
     * Sistem duvar kâğıdını (izin varsa ve ayar açıksa) launcher kopyası yapar; değişmediyse bir şey yapmaz.
     * Açılışta, öne gelişte ve sistem duvar kâğıdı değişince çağrılır.
     */
    fun syncSystemWallpaper(force: Boolean = false) {
        viewModelScope.launch {
            val s = la.settingsStore.settings.first()
            if (!s.syncSystemWallpaper) return@launch
            if (la.wallpaperRepository.syncFromSystem(force) && !s.launcherWallpaper) {
                la.settingsStore.update { it.copy(launcherWallpaper = true) }
            }
        }
    }

    /** Launcher duvar kâğıdı, sistemdekinin birebir kopyası mı? */
    fun wallpaperFromSync(): Boolean = la.wallpaperRepository.isFromSync()

    fun reloadWallpaper() {
        viewModelScope.launch {
            _wallpaper.value = la.wallpaperRepository.load()
            _blurred.value = la.wallpaperRepository.loadBlurred()
        }
    }

    // --- Otomatik kalite ---

    private val _degraded = MutableStateFlow(false)
    /** Kaydırma sırasında kare süreleri uzun ölçüldüyse true (bu oturum boyunca cam efektleri sadeleşir). */
    val degraded: StateFlow<Boolean> = _degraded
    fun reportSlowFrames() { _degraded.value = true }

    /** Görseli seçilen yere (ana ekran / kilit / ikisi) uygular; launcher ve sistem duvar kâğıdı birlikte değişir. */
    fun setWallpaper(uri: Uri, target: WallpaperTarget) {
        viewModelScope.launch {
            val msg = WallpaperApplier.apply(la, uri, target)
            android.widget.Toast.makeText(la, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun clearWallpaper() {
        viewModelScope.launch {
            la.wallpaperRepository.clear()
            _wallpaper.value = null
            _blurred.value = null
            la.settingsStore.update { it.copy(launcherWallpaper = false) }
        }
    }

    // --- Widget'lar ---

    val widgetHost get() = la.widgetHost

    /** Widget galerisi bir kez yüklenir (sayfa ölçüsü anahtarıyla); sonraki açılışlar anında olur. */
    internal var widgetGalleryCache: Pair<String, List<GalleryApp>>? = null

    /** Ana ekranda görünen sayfanın ilk öğesinin listedeki sırası (yeni widget'lar buraya eklenir). */
    var currentPageStart: Int = 0

    /** Widget sayfası açıkken eklenen widget'lar oraya gider (HomeScreen günceller). */
    @Volatile var todayOpen: Boolean = false

    /** "Yığına Widget Ekle" seçildi: sonraki eklenen widget bu yığına girer (3 dakika geçerli). */
    private var stackTarget: Pair<String, Long>? = null
    fun beginAddToStack(widgetId: String) { stackTarget = widgetId to android.os.SystemClock.elapsedRealtime() }

    fun addWidget(w: WidgetItem) {
        val t = stackTarget
        stackTarget = null
        if (t != null && android.os.SystemClock.elapsedRealtime() - t.second < 180_000L && _layout.value.widgets.any { it.id == t.first }) {
            editLayout { it.addToStack(t.first, w) }
            return
        }
        editLayout { if (todayOpen) it.addTodayWidget(w) else it.addWidget(w, currentPageStart) }
    }

    /** Yeni klasör adı: uygulamaların hepsi aynı türdeyse o türün adı (ör. "Sosyal"), değilse "Klasör". */
    fun folderNameFor(keys: List<String>): String {
        val cats = keys.mapNotNull { k -> shownApps.value.firstOrNull { it.key == k }?.category }.distinct()
        return cats.singleOrNull()?.takeIf { it != AppCategory.Other }?.title ?: "Klasör"
    }

    /** Ana ekranın bir sayfasını siler ([ids]: o sayfadaki öğeler). Uygulamalar arşivde kalır. */
    fun deletePage(ids: List<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        _layout.value.widgets.filter { it.id in set || _layout.value.homeWidgets.any { h -> h.id in set && h.stack.any { s -> s.id == it.id } } }.forEach { w ->
            if (w.kind == WidgetKind.System && w.appWidgetId >= 0) la.widgetHost.delete(w.appWidgetId)
            if (w.kind == WidgetKind.Photo && w.data.isNotEmpty()) la.wallpaperRepository.deletePhoto(w.data)
        }
        editLayout { it.removeItems(set) }
    }

    /**
     * Sayfaları Düzenle sonucu: [pages] (öğeler, gizli mi) yeni sıra; [deleted] silinen sayfaların öğeleri.
     * Silinen sayfadaki uygulama ve klasörler arşive geçer, widget'lar kaldırılır.
     */
    fun applyPages(pages: List<Pair<List<HomeItem>, Boolean>>, deleted: List<HomeItem>) {
        deleted.filterIsInstance<WidgetItem>().flatMap { listOf(it) + it.stack }.forEach { w ->
            if (w.kind == WidgetKind.System && w.appWidgetId >= 0) la.widgetHost.delete(w.appWidgetId)
            if (w.kind == WidgetKind.Photo && w.data.isNotEmpty()) la.wallpaperRepository.deletePhoto(w.data)
        }
        val keys = deleted.flatMap { when (it) { is dev.glass.launcher.data.AppItem -> listOf(it.key); is dev.glass.launcher.data.FolderItem -> it.keys; else -> emptyList() } }
        editLayout { l ->
            val n = l.withPages(pages)
            n.copy(libraryOnly = (n.libraryOnly + keys.filter { !AppInfo.isShortcutKey(it) }).distinct())
        }
    }

    fun removeWidget(w: WidgetItem) {
        // Yığın kaldırılırsa içindeki bütün widget'lar da gider.
        (listOf(w) + w.stack).forEach { x ->
            if (x.kind == WidgetKind.System && x.appWidgetId >= 0) la.widgetHost.delete(x.appWidgetId)
            if (x.kind == WidgetKind.Photo && x.data.isNotEmpty()) la.wallpaperRepository.deletePhoto(x.data)
        }
        editLayout { it.removeItem(w.id) }
    }

    fun setWidgetPhoto(w: WidgetItem, uri: Uri) {
        viewModelScope.launch {
            val name = la.wallpaperRepository.savePhoto(uri) ?: return@launch
            if (w.data.isNotEmpty()) la.wallpaperRepository.deletePhoto(w.data)
            editLayout { it.setWidgetData(w.id, name) }
        }
    }

    suspend fun loadPhoto(name: String, maxSide: Int): ImageBitmap? = la.wallpaperRepository.loadPhoto(name, maxSide)

    /** Bağlantısı kopmuş sistem widget'larını (ör. uygulaması kaldırılmış) yerleşimden çıkarır. */
    fun pruneWidgets(pendingId: Int?) {
        // Yerleşim henüz yüklenmediyse hiçbir şeyi silme (kullanıcının widget'ları yanlışlıkla gitmesin).
        if (!layoutReady) return
        val host = la.widgetHost
        val bad = _layout.value.widgets.filter { it.kind == WidgetKind.System && host.info(it.appWidgetId) == null }
        if (bad.isNotEmpty()) editLayout { l -> bad.fold(l) { acc, w -> acc.removeItem(w.id) } }
        val keep = _layout.value.widgets.map { it.appWidgetId }.filter { it >= 0 }.toMutableSet()
        // Ekleme akışı sürerken (izin/yapılandırma ekranı açıkken) ayrılmış kimlik silinmemeli.
        pendingId?.let { keep += it }
        host.deleteOrphans(keep)
    }

    // --- Yedekleme ---

    suspend fun exportBackup(): String = la.settingsStore.exportJson(_layout.value, settings.value)

    /** Başarılıysa true. Bu cihaza bağlı olmayan sistem widget'ları geri yüklenemez ve atlanır. */
    suspend fun importBackup(json: String): Boolean {
        val imported = la.settingsStore.importJson(json) ?: return false
        val host = la.widgetHost
        val cleaned = imported.copy(
            items = imported.items.filter { it !is WidgetItem || it.kind != WidgetKind.System || host.info(it.appWidgetId) != null },
            today = imported.today.filter { it.kind != WidgetKind.System || host.info(it.appWidgetId) != null },
        )
        val next = HomeLayout.reconcile(cleaned, entries(), repo.defaultDockPackages, settings.value.addNewToHome, keepShortcutKeys(cleaned))
        base = next
        _layout.value = next
        la.settingsStore.saveLayout(next.toJson())
        // Yedekten gelen simge ve duvar kâğıdı dosyaları hemen görünsün.
        la.iconCache.clear()
        reloadWallpaper()
        return true
    }

    /** Önceki düzene dönülebilir mi (iOS dizilimi uygulandıktan sonra). */
    val hasLayoutBackup: StateFlow<Boolean> = la.settingsStore.layoutBackupJson
        .map { it != null }
        .stateIn(viewModelScope, eager, false)

    /** Mevcut düzeni yedekleyip iOS 26 fabrika dizilimini uygular. Widget'lar korunur (en sona eklenir). */
    fun applyIosLayout() {
        val list = shownApps.value
        if (list.isEmpty()) return
        val cur = _layout.value
        viewModelScope.launch {
            la.settingsStore.saveLayoutBackup(cur.toJson())
            val fresh = IosDefaultLayout.build(la, list)
            val withWidgets = fresh.copy(items = fresh.items + cur.homeWidgets, today = cur.today)
            editLayout { HomeLayout.reconcile(withWidgets, entries(), repo.defaultDockPackages, true, keepShortcutKeys(cur)) }
        }
    }

    /** iOS dizilimi uygulanmadan önceki düzene döner. */
    fun restorePreviousLayout() {
        viewModelScope.launch {
            val json = la.settingsStore.layoutBackupJson.first() ?: return@launch
            val prev = HomeLayout.fromJson(json) ?: return@launch
            la.settingsStore.saveLayoutBackup(null)
            editLayout { HomeLayout.reconcile(prev, entries(), repo.defaultDockPackages, settings.value.addNewToHome, keepShortcutKeys(prev)) }
        }
    }

    fun updateSettings(block: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch { la.settingsStore.update(block) }
    }

    fun editLayout(block: (HomeLayout) -> HomeLayout) {
        val next = block(_layout.value)
        if (next == _layout.value) return
        base = next
        _layout.value = next
        unpinOrphans(next)
        viewModelScope.launch { la.settingsStore.saveLayout(next.toJson()) }
    }

    // Sınıfın EN SONUNDA olmalı: tüm alanlar (ör. _wallpaper) ilklendirildikten sonra çalışır.
    // (Daha önce başta çağrılıyordu; coroutine ilklendirilmemiş alanı yakalayıp açılışta çöküyordu.)
    init {
        reloadWallpaper()
        // Duvar kâğıdı başka yerden (galeri "Farklı ayarla", ayarlar) değişince yeniden yükle.
        viewModelScope.launch { la.wallpaperRepository.version.drop(1).collect { reloadWallpaper() } }
    }
}
