package dev.glass.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glass.launcher.ui.LauncherViewModel
import dev.glass.launcher.ui.SettingsScreen

/**
 * Ayarlar ayrı bir uygulama ekranı olarak açılır: sistem onu sıradan bir uygulama gibi görür, böylece alttan
 * kaydırma onu kapatıp ana ekrana döner, geri hareketi çalışır (MagicOS geriyi ana ekran uygulamasına vermiyor)
 * ve son uygulamalarda "Glass Ayarları" kartı olarak görünür. Ana ekranla aynı ViewModel'i kullanır.
 */
class SettingsActivity : ComponentActivity() {
    private val vm: LauncherViewModel get() = (application as LauncherApp).launcherViewModel
    private var isDefault by mutableStateOf(true)

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateDefault() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighRefresh()
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            dev.glass.launcher.ui.GlassLens.edgeFrac = s.glassEdgeWidth
            dev.glass.launcher.ui.GlassLens.lightAngle = s.glassLightAngle
            dev.glass.launcher.ui.GlassLens.lightBack = s.glassLightBack
            dev.glass.launcher.ui.GlassLens.contactShadow = s.glassContactShadow
            SettingsScreen(
                vm = vm, s = s, isDefaultHome = isDefault,
                onRequestDefault = ::requestHomeRole,
                onAddWidget = ::addWidgetOnHome,
                onClose = { finishAndRemoveTask() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateDefault()
    }

    private fun updateDefault() {
        isDefault = runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }.getOrDefault(true)
    }

    private fun requestHomeRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        }
    }

    /** "Widget ekle": ana ekrana dönülür ve widget galerisi orada açılır (widget'lar ana ekrana eklenir). */
    private fun addWidgetOnHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
                    .putExtra(MainActivity.EXTRA_ADD_WIDGET, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finishAndRemoveTask()
    }

    /** 120 Hz (ana ekrandaki gibi): ekranın en yüksek yenileme hızlı modu istenir. */
    private fun requestHighRefresh() {
        runCatching {
            val d = display ?: return
            val cur = d.mode
            val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
            if (android.os.Build.VERSION.SDK_INT >= 35) window.isFrameRatePowerSavingsBalanced = false
        }
    }
}
