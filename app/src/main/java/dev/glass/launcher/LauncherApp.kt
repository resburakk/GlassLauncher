package dev.glass.launcher

import android.app.Application
import dev.glass.launcher.data.AppRepository
import dev.glass.launcher.data.IconCache
import dev.glass.launcher.data.SettingsStore
import dev.glass.launcher.data.WallpaperRepository

class LauncherApp : Application() {
    lateinit var appRepository: AppRepository
        private set
    lateinit var iconCache: IconCache
        private set
    lateinit var settingsStore: SettingsStore
        private set

    lateinit var wallpaperRepository: WallpaperRepository
        private set

    override fun onCreate() {
        super.onCreate()
        appRepository = AppRepository(this)
        iconCache = IconCache()
        settingsStore = SettingsStore(this)
        wallpaperRepository = WallpaperRepository(this)
        appRepository.start()
    }
}
