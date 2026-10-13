package dev.glass.launcher

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.glass.launcher.ui.CrashScreen
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
    private val vm: LauncherViewModel by viewModels()
    private var isDefault by mutableStateOf(false)
    /** "Glass Ayarları" kısayoluyla açıldıysa ayarlar doğrudan gösterilir. */
    private var openSettingsRequest by mutableStateOf(0)

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateDefault() }

    private val wallpaperManager by lazy { getSystemService(WallpaperManager::class.java) }
    private val colorsListener = WallpaperManager.OnColorsChangedListener { colors, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) applyBarTone(colors)
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
        enableEdgeToEdge()
        updateDefault()
        applyBarTone(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
        if (isSettingsEntry(intent)) openSettingsRequest++
        val lastCrash = (application as LauncherApp).takeLastCrash()
        setContent {
            var crash by remember { mutableStateOf(lastCrash) }
            crash?.let { text ->
                CrashScreen(text, onContinue = { crash = null })
                return@setContent
            }
            CompositionLocalProvider(LocalWidgetRequester provides this) {
                HomeScreen(
                    vm, isDefault,
                    onRequestDefault = ::requestHomeRole,
                    openSettingsRequest = openSettingsRequest,
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
    }

    override fun onResume() {
        super.onResume()
        updateDefault()
        applyBarTone(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isSettingsEntry(intent)) {
            openSettingsRequest++
            return
        }
        // Launcher zaten öndeyken Home tuşu: iOS gibi ilk sayfaya dön, açık katmanları kapat.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) vm.onHomePressed()
    }

    private fun isSettingsEntry(i: Intent?): Boolean =
        i?.component?.className?.endsWith(".SettingsEntry") == true

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
    private fun applyBarTone(colors: WallpaperColors?) {
        val darkText = colors != null &&
            (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
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

    private companion object {
        const val REQ_CONFIGURE = 0x5701
        const val REQ_RECONFIGURE = 0x5702
    }
}
