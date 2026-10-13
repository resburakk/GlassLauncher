package dev.glass.launcher.ui

import android.app.Application
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
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

    init {
        viewModelScope.launch {
            base = la.settingsStore.layoutJson.first()?.let { HomeLayout.fromJson(it) }
            val addNew = la.settingsStore.settings.map { it.addNewToHome }.distinctUntilChanged()
            combine(repo.apps, addNew) { list, add -> list to add }.collect { (list, add) ->
                if (list.isEmpty()) return@collect // liste henüz yüklenmedi: kayıtlı düzeni budama
                val next = HomeLayout.reconcile(base, list, repo.defaultDockPackages, add)
                val changed = next != base
                base = next
                _layout.value = next
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
