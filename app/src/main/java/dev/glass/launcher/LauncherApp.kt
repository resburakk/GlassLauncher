package dev.glass.launcher

import android.app.Application
import dev.glass.launcher.data.AppRepository
import dev.glass.launcher.data.IconCache
import dev.glass.launcher.data.SettingsStore
import dev.glass.launcher.data.WallpaperRepository
import dev.glass.launcher.widget.WidgetHostManager

class LauncherApp : Application() {
    lateinit var appRepository: AppRepository
        private set
    lateinit var iconCache: IconCache
        private set
    lateinit var settingsStore: SettingsStore
        private set

    lateinit var wallpaperRepository: WallpaperRepository
        private set
    lateinit var widgetHost: WidgetHostManager
        private set

    override fun onCreate() {
        super.onCreate()
        appRepository = AppRepository(this)
        iconCache = IconCache(this)
        settingsStore = SettingsStore(this)
        wallpaperRepository = WallpaperRepository(this)
        widgetHost = WidgetHostManager(this)
        appRepository.start()
    }
}
