package dev.glass.launcher

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
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
        installCrashRecorder()
        dev.glass.launcher.ui.GlassLens.density = resources.displayMetrics.density
        appRepository = AppRepository(this)
        iconCache = IconCache(this)
        settingsStore = SettingsStore(this)
        wallpaperRepository = WallpaperRepository(this)
        widgetHost = WidgetHostManager(this)
        appRepository.start()
    }

    /**
     * Çökme olursa yığın izini dosyaya yazar; bir sonraki açılışta launcher bunu gösterir (kopyalanıp
     * geliştiriciye iletilebilir). Ayarlar ve yerleşim DataStore'da olduğu için çökmeden etkilenmez.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    "Glass Launcher ${BuildConfig.VERSION_NAME} — Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n\n" +
                        Log.getStackTraceString(e)
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun takeLastCrash(): String? {
        val f = File(filesDir, CRASH_FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
