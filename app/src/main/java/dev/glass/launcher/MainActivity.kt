package dev.glass.launcher

import kotlinx.coroutines.launch

import androidx.lifecycle.lifecycleScope

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import dev.glass.launcher.ui.LocalWindowBlur
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.glass.launcher.ui.CrashScreen
import dev.glass.launcher.ui.AppFont
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import dev.glass.launcher.ui.HomeScreen
import dev.glass.launcher.ui.LauncherViewModel
import dev.glass.launcher.ui.LocalWidgetRequester
import dev.glass.launcher.ui.WidgetRequester
import java.util.UUID

class MainActivity : ComponentActivity(), WidgetRequester {
    private val vm: LauncherViewModel get() = (application as LauncherApp).launcherViewModel
    private var isDefault by mutableStateOf(false)
    /**
     * Sistemin "Son uygulamalar" ekranı açılırken ana ekran görüntüsü yarı saydam bir kart gibi görünüyordu
     * (ana ekranın kendi zemini yok, arkası duvar kâğıdı). Launcher kendi açmadığı bir şeyle arka plana
     * geçerken içerik solar; kart yalnızca duvar kâğıdı olarak kalır. Dönüşte iOS gibi büyüyerek gelir.
     */
    private var homeHidden by mutableStateOf(false)

    /** "Glass Ayarları" kısayoluyla açıldıysa ayarlar doğrudan gösterilir. */
    private var openSettingsRequest by mutableStateOf(0)
    private var addWidgetRequest by mutableStateOf(0)

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateDefault() }

    private val wallpaperManager by lazy { getSystemService(WallpaperManager::class.java) }
    private val colorsListener = WallpaperManager.OnColorsChangedListener { colors, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) {
            applyBarTone(colors)
            // Sistem duvar kâğıdı değişti: cam efekti için launcher kopyasını güncelle.
            vm.syncSystemWallpaper()
        }
    }

    private val widgetHost get() = (application as LauncherApp).widgetHost

    // --- Sistem widget'ı ekleme akışı ---
    private class PendingWidget(val id: Int, val info: AppWidgetProviderInfo, val spanX: Int, val spanY: Int)
    private var pending: PendingWidget? = null

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = pending ?: return@registerForActivityResult
        if (r.resultCode == Activity.RESULT_OK) configureOrFinish(p) else cancelPending()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.glass.launcher.ui.BarTone.apply = { runOnUiThread { applyCurrentBarTone() } }
        // Ana ekranda gezinti çubuğunu gizleme ayarı değişince hemen uygulanır.
        lifecycleScope.launch {
            vm.settings.collect { s ->
                if (s.hideNavHandle != hideNavHandle) { hideNavHandle = s.hideNavHandle; applyNavHandle() }
                // Ana ekran yönü: kapalıyken hep dikey (iOS gibi), açıkken telefonun otomatik döndürmesine uyar.
                val want = if (s.allowRotation) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                if (requestedOrientation != want) requestedOrientation = want
            }
        }
        // iOS kilit açılışı: ekran kapanınca simgeler gizlenir, kilit açılınca yerine oturarak belirir.
        runCatching {
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: Intent?) {
                    if (!vm.settings.value.unlockAnimation) return
                    when (i?.action) {
                        Intent.ACTION_SCREEN_OFF -> dev.glass.launcher.ui.UnlockAnim.hide()
                        Intent.ACTION_USER_PRESENT -> dev.glass.launcher.ui.UnlockAnim.play()
                    }
                }
            }, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        enableEdgeToEdge()
        requestHighestRefreshRate()
        updateDefault()
        // Durum çubuğu tonu onResume'da arka planda hesaplanır (renk hesabı yavaş olabilir).
        // Kurulum/güncellemeden sonra "Aç" ile gelinirse (ilk açılış) ayarlar değil ana ekran gösterilir.
        val firstRun = consumeFirstRunAfterUpdate()
        if (isSettingsEntry(intent) && !firstRun) openSettingsRequest++
        val lastCrash = (application as LauncherApp).takeLastCrash()
        setContent {
            var crash by remember { mutableStateOf(lastCrash) }
            crash?.let { text ->
                CrashScreen(text, onContinue = { crash = null })
                return@setContent
            }
            CompositionLocalProvider(
                LocalWidgetRequester provides this,
                LocalWindowBlur provides ::setWallpaperBlur,
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = AppFont),
            ) {
                val hide by androidx.compose.animation.core.animateFloatAsState(
                    if (homeHidden) 1f else 0f,
                    if (homeHidden) androidx.compose.animation.core.tween(110)
                    else androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f),
                    label = "homeHide",
                )
                HomeScreen(
                    vm, isDefault,
                    onRequestDefault = ::requestHomeRole,
                    openSettingsRequest = openSettingsRequest,
                    addWidgetRequest = addWidgetRequest,
                    homeHide = { hide },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        wallpaperManager.addOnColorsChangedListener(colorsListener, Handler(Looper.getMainLooper()))
        widgetHost.startListening()
        vm.pruneWidgets(pending?.id)
    }

    override fun onStop() {
        wallpaperManager.removeOnColorsChangedListener(colorsListener)
        widgetHost.stopListening()
        super.onStop()
        coveredByApp = true
    }

    /** Launcher başka bir uygulamanın arkasında kaldı mı (onStop)? Home ile dönüşte sayfa korunur. */
    private var coveredByApp = false

    /** Kare süresi ölçümü (Ayarlar > Performans) için arka plan iş parçacığı. */
    private val metricsHandler by lazy {
        android.os.Handler(android.os.HandlerThread("frameMetrics").apply { start() }.looper)
    }

    override fun onPause() {
        super.onPause()
        dev.glass.launcher.ui.AppLock.paused(this)
        runCatching { window.removeOnFrameMetricsAvailableListener(dev.glass.launcher.ui.FrameStats.listener) }
        // Launcher bir uygulama/pencere açmadan arka plana geçiyor: büyük olasılıkla son uygulamalar ekranı.
        if (!LaunchTracker.recent() && !isChangingConfigurations) homeHidden = true
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        LaunchTracker.mark()
        super.startActivity(intent, options)
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        LaunchTracker.mark()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onResume() {
        super.onResume()
        applyNavHandle()
        // Launcher varsayılan ana ekran yapılınca kısayollar okunabilir hale gelir; ön plana her gelişte tazele.
        vm.refreshPinned()
        dev.glass.launcher.ui.AppLock.attach(this)
        // Kilit yoksa (veya USER_PRESENT kaçtıysa) simgeler gizli kalmasın.
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true) dev.glass.launcher.ui.UnlockAnim.play()
        runCatching { window.addOnFrameMetricsAvailableListener(dev.glass.launcher.ui.FrameStats.listener, metricsHandler) }
        homeHidden = false
        coveredByApp = false
        // Sistem çağrıları (bazıları yavaş: duvar kâğıdı renk hesabı) ana iş parçacığını meşgul etmesin;
        // uygulamadan alttan kaydırarak dönerken sistemin geçişi launcher'ı beklerken takılıyordu.
        bg.execute {
            // Her öne gelişte değil, yalnızca gerektiğinde (ilk açılış / "Glass Ayarları" kısayolu): her seferinde görev
            // değiştirmek, son uygulamalar ekranı açılırken sistemin görev listesini karıştırıp ilk kaydırmayı boşa
            // çıkarıyor olabilir (Honor/MagicOS).
            if (needHideFromRecents) { needHideFromRecents = false; hideFromRecents() }
            val held = runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }.getOrNull()
            val colors = runCatching { wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (held != null) isDefault = held
                checkHomeTask(held == true)
                applyBarTone(colors)
            }
        }
        vm.syncSystemWallpaper()
    }

    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Launcher sistemin ANA EKRAN görevinde mi çalışıyor? Sıradan bir uygulama görevinde kalırsa (ör. eski sürümde
     * simgeden / "Aç" ile açıldıysa) sistem ana ekrandayken bile uygulamadaymışız gibi davranır: ana ekran ve son
     * uygulamalar tuşları, son uygulamalarda boşluğa dokunma çalışmaz, son uygulamalar ancak ikinci kaydırmada açılır.
     * Öyleyse launcher kendini bir kez kapatıp ana ekran isteğiyle yeniden açar. Durum Ayarlar > Performans'ta görünür.
     */
    private fun checkHomeTask(isDefaultHome: Boolean) {
        val base = runCatching {
            getSystemService(android.app.ActivityManager::class.java).appTasks
                .map { it.taskInfo }.firstOrNull { it.taskId == taskId }?.baseIntent
        }.getOrNull()
        val homeTask = base != null && base.action == Intent.ACTION_MAIN && base.hasCategory(Intent.CATEGORY_HOME)
        dev.glass.launcher.ui.FrameStats.homeTask = when {
            !isDefaultHome -> "Ana ekran görevi: launcher varsayılan ana ekran değil."
            homeTask -> "Ana ekran görevi: doğru (launcher sistemin ana ekranı olarak çalışıyor)."
            else -> "Ana ekran görevi: YANLIŞ — launcher sıradan bir uygulama görevinde " +
                "(açılış: ${base?.component?.shortClassName ?: "?"} ${base?.categories?.joinToString() ?: ""})." +
                if (healedOnce) " Kendini onarma denendi ama düzelmedi." else ""
        }
        if (isDefaultHome && base != null && !homeTask && !healedOnce && !isFinishing) {
            healedOnce = true
            val app = applicationContext
            finishAndRemoveTask()
            runCatching {
                app.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
    @Volatile private var needHideFromRecents = true

    /**
     * Launcher "Son uygulamalar" ekranında kart olarak görünmesin. Manifestteki excludeFromRecents, uygulama
     * listesindeki "Glass Ayarları" kısayolundan (takma ad) açılan görevde uygulanmıyor; bu yüzden uygulamanın
     * tüm görevleri burada ayrıca gizlenir. Yalnızca kendi görevlerimize dokunur.
     */
    private fun hideFromRecents() {
        runCatching {
            getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.setExcludeFromRecents(true) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ADD_WIDGET, false)) {
            addWidgetRequest++
            return
        }
        if (isSettingsEntry(intent)) {
            needHideFromRecents = true
            openSettingsRequest++
            return
        }
        // Launcher zaten öndeyken Home tuşu: iOS gibi ilk sayfaya dön, açık katmanları kapat.
        // Bir uygulamadan dönülürken (launcher henüz önde değil) sayfa korunur; dönüş efekti simgeye oturur.
        // Android, öndeki launcher'a Home iletirken onu yalnızca kısa süre duraklatır (onStop çağrılmaz);
        // bir uygulamanın arkasında kalmışsa onStop çağrılmıştır.
        val alreadyInFront = !coveredByApp
        if (alreadyInFront && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) vm.onHomePressed()
    }

    /**
     * 120 Hz: ekranın mevcut çözünürlükteki en yüksek yenileme hızına sahip modunu ister. Animasyonlar
     * otomatik olarak bu hızda çizilir. Not: MagicOS'ta Ayarlar > Ekran > Ekran yenileme hızı "Yüksek" veya
     * "Dinamik" değilse sistem yine sınırlayabilir.
     */
    private fun requestHighestRefreshRate() {
        runCatching {
            val d = display ?: return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
                preferredRefreshRate = best.refreshRate
            }
        }
        // Android 15 QPR1+/16 "uyarlanabilir yenileme hızı": görünümler varsayılan olarak ≈60 Hz ister; parmak
        // kalkınca sayfa kaydırma/yerine oturma animasyonu 60 Hz'e düşüyordu. Launcher penceresi için kapatılır:
        // pencere yukarıda istenen en yüksek hızda (120 Hz) çizilir.
        if (android.os.Build.VERSION.SDK_INT >= 35) runCatching {
            window.isFrameRatePowerSavingsBalanced = false
        }
    }

    private var lastBlur = -1

    /**
     * Sistem duvar kâğıdını gerçekten bulanıklaştırır: pencere arkası bulanıklığı (Android 12+). Launcher
     * penceresi duvar kâğıdını gösterdiği için arkasındaki duvar kâğıdı bulanıklaşır. Sık güncellemeyi
     * önlemek için yarıçap 6 px adımlarla değiştirilir. Cihaz/ayar desteklemiyorsa sessizce etkisizdir.
     */
    private val blurRelayout = Runnable {
        runCatching {
            window.attributes = window.attributes
            window.decorView.requestLayout()
            window.decorView.invalidate()
        }
    }

    fun setWallpaperBlur(fraction: Float) {
        if (!windowManager.isCrossWindowBlurEnabled && lastBlur <= 0) return
        val r = ((fraction.coerceIn(0f, 2f) * MAX_WALLPAPER_BLUR) / 6f).toInt() * 6
        if (r == lastBlur) return
        lastBlur = r
        runCatching {
            window.attributes = window.attributes.apply {
                if (r > 0) {
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = r
                } else {
                    flags = flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    blurBehindRadius = 0
                }
            }
            // Bazı cihazlarda/emülatörde yeni yarıçap ancak bir sonraki pencere yerleşiminde uygulanır;
            // hareket bittikten sonra yerleşimi tetikleyerek bulanıklığın kesin uygulanmasını sağla.
            val d = window.decorView
            d.removeCallbacks(blurRelayout)
            d.postDelayed(blurRelayout, 120)
        }
    }

    /** Uygulama kurulduktan veya güncellendikten sonraki ilk açılış mı? (Bir kez true döner.) */
    private fun consumeFirstRunAfterUpdate(): Boolean = runCatching {
        val stamp = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val prefs = getSharedPreferences("launch", MODE_PRIVATE)
        val seen = prefs.getLong("seenUpdate", 0L)
        prefs.edit().putLong("seenUpdate", stamp).apply()
        seen != stamp
    }.getOrDefault(false)

    private fun isSettingsEntry(i: Intent?): Boolean =
        i?.getBooleanExtra(EXTRA_SETTINGS, false) == true || i?.component?.className?.endsWith(".SettingsEntry") == true

    // --- WidgetRequester ---

    override fun addSystemWidget(info: AppWidgetProviderInfo, spanX: Int, spanY: Int) {
        cancelPending()
        val id = runCatching { widgetHost.allocate() }.getOrNull() ?: return
        val p = PendingWidget(id, info, spanX, spanY)
        pending = p
        if (widgetHost.bindIfAllowed(id, info)) {
            configureOrFinish(p)
        } else {
            // Sistem, launcher'ın bu widget'ı bağlamasına izin vermesi için kullanıcıya sorar.
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            runCatching { bindLauncher.launch(i) }.onFailure { cancelPending() }
        }
    }

    override fun reconfigure(appWidgetId: Int) {
        runCatching {
            widgetHost.host.startAppWidgetConfigureActivityForResult(this, appWidgetId, 0, REQ_RECONFIGURE, null)
        }
    }

    private fun configureOrFinish(p: PendingWidget) {
        val needsConfig = p.info.configure != null &&
            (p.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0
        if (needsConfig) {
            val started = runCatching {
                widgetHost.host.startAppWidgetConfigureActivityForResult(this, p.id, 0, REQ_CONFIGURE, null)
            }.isSuccess
            if (!started) finishPending(p)
        } else {
            finishPending(p)
        }
    }

    private fun finishPending(p: PendingWidget) {
        pending = null
        vm.addWidget(WidgetItem(UUID.randomUUID().toString(), WidgetKind.System, p.spanX, p.spanY, p.id))
    }

    private fun cancelPending() {
        pending?.let { widgetHost.delete(it.id) }
        pending = null
    }

    @Deprecated("Widget yapılandırma ekranı sonucu yalnızca bu yolla gelir (AppWidgetHost API'si).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE) {
            val p = pending ?: return
            if (resultCode == Activity.RESULT_OK) finishPending(p) else cancelPending()
        }
    }

    /**
     * Durum/gezinme çubuğu simgeleri duvar kâğıdına göre otomatik siyah veya beyaz olur.
     * Duvar kâğıdı koyu metni destekliyorsa (açık renkli) simgeler siyah, aksi halde beyaz.
     */
    /** Duvar kâğıdına göre son hesaplanan ton (koyu yazı mı). */
    private var wallpaperDarkText = false

    private fun applyBarTone(colors: WallpaperColors?) {
        dev.glass.launcher.ui.WallpaperAccent.update(colors)
        wallpaperDarkText = colors != null &&
            (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        applyCurrentBarTone()
    }

    /** Açık bir ekran (Ayarlar) tonu sabitlemişse ona, değilse duvar kâğıdına göre uygular. */
    private var hideNavHandle = true

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Alttan kaydırınca çubuk geçici görünebilir: launcher yeniden odaklanınca tekrar gizlenir.
        if (hasFocus) applyNavHandle()
    }

    /** Hareketle gezinti mi (üç tuş değil)? Sistem kaynağından okunur. */
    private fun gestureNav(): Boolean {
        // Gezinti modu sistem katmanıyla (overlay) belirlenir: uygulamanın kaynaklarına yansır, sistemin genel
        // kaynaklarına yansımaz. Okunamazsa sistemin gezinti ayarına bakılır (2 = hareketler).
        val fromRes = runCatching {
            val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            if (id != 0) resources.getInteger(id) else -1
        }.getOrDefault(-1)
        if (fromRes == 2) return true
        if (runCatching { android.provider.Settings.Secure.getInt(contentResolver, "navigation_mode") == 2 }.getOrDefault(false)) return true
        // Bazı sistemler (MagicOS) modu farklı tutar: alttaki gezinti alanında dokunulabilir tuş yoksa (yalnızca
        // ince hareket çubuğu) hareketle gezintidir. Üç tuşta tuşlar dokunulabilir alandır (≈ 48 dp).
        return runCatching {
            val ins = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView) ?: return@runCatching false
            val tap = ins.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.tappableElement()).bottom
            val nav = ins.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            val dp = resources.displayMetrics.density
            nav > 0 && tap < 40f * dp
        }.getOrDefault(false)
    }

    /**
     * Hareketle gezintide ana ekrandayken alttaki ince çubuk gizlenir (ayar açıksa). Hareketler çalışmaya devam eder;
     * uygulamalar kendi penceresinde çubuğu normal gösterir. Üç tuşlu gezintide tuşlar hiç gizlenmez.
     */
    private fun applyNavHandle() {
        if (isDestroyed) return
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                if (hideNavHandle && gestureNav()) {
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                } else {
                    show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                }
            }
        }
    }

    private fun applyCurrentBarTone() {
        if (isDestroyed) return
        val darkText = dev.glass.launcher.ui.BarTone.override ?: wallpaperDarkText
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkText
            isAppearanceLightNavigationBars = darkText
        }
    }

    private fun updateDefault() {
        val rm = getSystemService(RoleManager::class.java)
        isDefault = rm.isRoleHeld(RoleManager.ROLE_HOME)
    }

    fun requestHomeRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        }
    }

    companion object {
        /** Kendini ana ekran görevine taşıma bu süreçte yalnızca bir kez denenir (döngüye girmesin). */
        @Volatile private var healedOnce = false
        /** "Glass Ayarları" simgesinden gelindi: ayarlar açılsın (bkz. EntryActivity). */
        const val EXTRA_SETTINGS = "dev.glass.launcher.OPEN_SETTINGS"
        /** Ayarlardaki "Widget ekle": ana ekranda widget galerisi açılsın. */
        const val EXTRA_ADD_WIDGET = "dev.glass.launcher.ADD_WIDGET"
        private const val REQ_CONFIGURE = 0x5701
        private const val REQ_RECONFIGURE = 0x5702
        private const val MAX_WALLPAPER_BLUR = 90f
    }
}
