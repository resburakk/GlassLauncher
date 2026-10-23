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

class LauncherApp : Application(), androidx.lifecycle.ViewModelStoreOwner {
    /** Ana ekran ve ayarlar AYNI ViewModel'i kullanır (ayrı ekranlarda iki kopya, birbirinin düzenini ezmesin). */
    override val viewModelStore = androidx.lifecycle.ViewModelStore()

    val launcherViewModel: dev.glass.launcher.ui.LauncherViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(
            this, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(this),
        )[dev.glass.launcher.ui.LauncherViewModel::class.java]
    }

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
        // Bildirim sayıları: izin verilmişse dinleyiciyi bağla (bazı telefonlar kendiliğinden bağlamıyor).
        runCatching {
            if (dev.glass.launcher.service.BadgeListener.isGranted(this)) {
                android.service.notification.NotificationListenerService.requestRebind(
                    dev.glass.launcher.service.BadgeListener.component(this)
                )
            }
        }
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
            // Başka bir uygulamanın widget kodu (ör. Honor Takvim'in kendi görünümü) bu süreçte çalışır.
            // O kod arka plandaki kendi iş parçacığında hata verirse launcher çökmez: hata kaydedilir,
            // widget cama/launcher'a uyumsuz sayılır (galeride gizlenir).
            val foreign = foreignWidgetClass(e)
            if (foreign != null) {
                val main = t === android.os.Looper.getMainLooper().thread
                runCatching {
                    val p = getSharedPreferences("widget_glass", MODE_PRIVATE)
                    val set = p.getStringSet(if (main) "crashMain" else "crash", emptySet())!!.toMutableSet()
                    set.add(foreign)
                    p.edit().putStringSet(if (main) "crashMain" else "crash", set).commit()
                }
                Log.w("GlassDiag", "widget kodu hata verdi (${t.name}): $foreign", e)
                if (!main) return@setDefaultUncaughtExceptionHandler
            }
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    "Glass Launcher ${BuildConfig.VERSION_NAME} — Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n\n" +
                        Log.getStackTraceString(e)
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Hatanın kaynağı başka bir uygulamanın (widget) sınıfı mı? Öyleyse o sınıfın adı. */
    private fun foreignWidgetClass(e: Throwable): String? {
        val own = listOf("android.", "java.", "javax.", "kotlin", "dalvik.", "libcore.", "sun.", "androidx.", "com.android.internal.", "com.android.server.", "dev.glass.", "org.json.", "jdk.")
        var c: Throwable? = e
        while (c != null) {
            c.stackTrace.firstOrNull { f -> own.none { f.className.startsWith(it) } }?.let { return it.className }
            c = c.cause
        }
        return null
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
