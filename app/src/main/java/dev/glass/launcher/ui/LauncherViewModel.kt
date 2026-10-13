package dev.glass.launcher.ui

import android.app.Application
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import dev.glass.launcher.data.IconLook
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.glass.launcher.LauncherApp
import dev.glass.launcher.data.AppCategory
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Uygulama Arşivi'nde bir kategori kutusu. */
data class LibraryGroup(val title: String, val apps: List<AppInfo>)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val la = app as LauncherApp
    private val repo = la.appRepository
    private val eager = SharingStarted.WhileSubscribed(5_000)

    val apps: StateFlow<List<AppInfo>> = repo.apps

    val appsByKey: StateFlow<Map<String, AppInfo>> = repo.apps
        .map { list -> list.associateBy { it.key } }
        .stateIn(viewModelScope, eager, emptyMap())

    val settings: StateFlow<LauncherSettings> = la.settingsStore.settings
        .stateIn(viewModelScope, eager, LauncherSettings())

    private val _layout = MutableStateFlow(HomeLayout())
    /** Tek doğruluk kaynağı: düzenlemeler anında buraya yazılır, ardından diske kaydedilir. */
    val layout: StateFlow<HomeLayout> = _layout

    private var base: HomeLayout? = null
    /** Kayıtlı yerleşim yüklü uygulamalarla en az bir kez uzlaştırıldı mı? */
    @Volatile private var layoutReady = false

    init {
        reloadWallpaper()
        viewModelScope.launch {
            base = la.settingsStore.layoutJson.first()?.let { HomeLayout.fromJson(it) }
            val addNew = la.settingsStore.settings.map { it.addNewToHome }.distinctUntilChanged()
            combine(repo.apps, addNew) { list, add -> list to add }.collect { (list, add) ->
                if (list.isEmpty()) return@collect // liste henüz yüklenmedi: kayıtlı düzeni budama
                val next = HomeLayout.reconcile(base, list, repo.defaultDockPackages, add)
                val changed = next != base
                base = next
                _layout.value = next
                layoutReady = true
                if (changed) la.settingsStore.saveLayout(next.toJson())
            }
        }
    }

    val recentApps: StateFlow<List<AppInfo>> = combine(repo.apps, settings) { all, s ->
        s.recents.mapNotNull { r -> all.firstOrNull { it.componentName.flattenToString() == r } }
    }.stateIn(viewModelScope, eager, emptyList())

    /**
     * Uygulama Arşivi grupları: Öneriler (son kullanılanlar), Son Eklenenler, ardından kategoriler.
     * Boş kategoriler gösterilmez.
     */
    val libraryGroups: StateFlow<List<LibraryGroup>> = combine(repo.apps, recentApps) { all, recent ->
        val groups = ArrayList<LibraryGroup>()
        if (recent.isNotEmpty()) groups += LibraryGroup("Öneriler", recent)
        val newest = all.filter { it.installTime > 0 }.sortedByDescending { it.installTime }.take(8)
        if (newest.isNotEmpty()) groups += LibraryGroup("Son Eklenenler", newest)
        val byCat = all.groupBy { it.category }
        AppCategory.entries.forEach { c ->
            byCat[c]?.takeIf { it.isNotEmpty() }?.let { groups += LibraryGroup(c.title, it) }
        }
        groups
    }.stateIn(viewModelScope, eager, emptyList())

    val iconCache = la.iconCache

    private val _homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Launcher öndeyken Home tuşuna basıldı: katmanları kapat, ilk sayfaya dön. */
    val homePressed: SharedFlow<Unit> = _homePressed
    fun onHomePressed() { _homePressed.tryEmit(Unit) }

    fun launch(app: AppInfo, bounds: Rect? = null) {
        repo.launch(app, bounds)
        viewModelScope.launch { la.settingsStore.addRecent(app.componentName.flattenToString()) }
    }

    fun openAppInfo(app: AppInfo) = repo.openAppInfo(app)
    fun uninstall(app: AppInfo) = repo.uninstall(app)
    suspend fun shortcuts(app: AppInfo): List<ShortcutInfo> = repo.shortcuts(app)
    suspend fun shortcutIcon(info: ShortcutInfo, density: Int): Drawable? = repo.shortcutIcon(info, density)
    fun startShortcut(info: ShortcutInfo) = repo.startShortcut(info)

    // --- Simgeler ---

    fun iconLook(app: AppInfo, s: LauncherSettings): IconLook =
        IconLook(s.iconStyle, s.tintColor, s.customIcons[app.key])

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

    fun reloadWallpaper() {
        viewModelScope.launch { _wallpaper.value = la.wallpaperRepository.load() }
    }

    fun setWallpaper(uri: Uri, alsoSystem: Boolean) {
        viewModelScope.launch {
            if (la.wallpaperRepository.save(uri)) {
                _wallpaper.value = la.wallpaperRepository.load()
                la.settingsStore.update { it.copy(launcherWallpaper = true) }
                if (alsoSystem) la.wallpaperRepository.applyToSystem()
            }
        }
    }

    fun clearWallpaper() {
        viewModelScope.launch {
            la.wallpaperRepository.clear()
            _wallpaper.value = null
            la.settingsStore.update { it.copy(launcherWallpaper = false) }
        }
    }

    // --- Widget'lar ---

    val widgetHost get() = la.widgetHost

    /** Ana ekranda görünen sayfanın ilk öğesinin listedeki sırası (yeni widget'lar buraya eklenir). */
    var currentPageStart: Int = 0

    fun addWidget(w: WidgetItem) = editLayout { it.addWidget(w, currentPageStart) }

    fun removeWidget(w: WidgetItem) {
        if (w.kind == WidgetKind.System && w.appWidgetId >= 0) la.widgetHost.delete(w.appWidgetId)
        if (w.kind == WidgetKind.Photo && w.data.isNotEmpty()) la.wallpaperRepository.deletePhoto(w.data)
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
            items = imported.items.filter { it !is WidgetItem || it.kind != WidgetKind.System || host.info(it.appWidgetId) != null }
        )
        val next = HomeLayout.reconcile(cleaned, repo.apps.value, repo.defaultDockPackages, settings.value.addNewToHome)
        base = next
        _layout.value = next
        la.settingsStore.saveLayout(next.toJson())
        return true
    }

    fun updateSettings(block: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch { la.settingsStore.update(block) }
    }

    fun editLayout(block: (HomeLayout) -> HomeLayout) {
        val next = block(_layout.value)
        if (next == _layout.value) return
        base = next
        _layout.value = next
        viewModelScope.launch { la.settingsStore.saveLayout(next.toJson()) }
    }
}
