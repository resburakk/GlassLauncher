package dev.glass.launcher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import dev.glass.launcher.ui.HomeScreen
import dev.glass.launcher.ui.LauncherViewModel

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private var isDefault by mutableStateOf(false)

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateDefault() }

    private val wallpaperManager by lazy { getSystemService(WallpaperManager::class.java) }
    private val colorsListener = WallpaperManager.OnColorsChangedListener { colors, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) applyBarTone(colors)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateDefault()
        applyBarTone(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
        setContent {
            HomeScreen(vm, isDefault, onRequestDefault = ::requestHomeRole)
        }
    }

    override fun onStart() {
        super.onStart()
        wallpaperManager.addOnColorsChangedListener(colorsListener, Handler(Looper.getMainLooper()))
    }

    override fun onStop() {
        wallpaperManager.removeOnColorsChangedListener(colorsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateDefault()
        applyBarTone(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
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

    private fun requestHomeRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        }
    }
}
