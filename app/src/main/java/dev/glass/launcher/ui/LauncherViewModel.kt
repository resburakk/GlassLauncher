package dev.glass.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.glass.launcher.LauncherApp
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
            repo.apps.collect { list ->
                if (list.isEmpty()) return@collect // liste henüz yüklenmedi: kayıtlı düzeni budama
                val next = HomeLayout.reconcile(base, list, repo.defaultDockPackages)
                base = next
                _layout.value = next
            }
        }
    }

    val recentApps: StateFlow<List<AppInfo>> = combine(repo.apps, settings) { all, s ->
        s.recents.mapNotNull { r -> all.firstOrNull { it.componentName.flattenToString() == r } }
    }.stateIn(viewModelScope, eager, emptyList())

    val iconCache = la.iconCache

    fun launch(app: AppInfo) {
        repo.launch(app)
        viewModelScope.launch { la.settingsStore.addRecent(app.componentName.flattenToString()) }
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
