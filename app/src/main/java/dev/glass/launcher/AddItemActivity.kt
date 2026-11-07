package dev.glass.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import dev.glass.launcher.ui.AppFont
import java.util.UUID

/**
 * Bir uygulama ana ekrana kısayol ya da widget eklemek istediğinde açılır (Chrome "Ana ekrana ekle",
 * WhatsApp sohbet kısayolu, Haritalar yol tarifi, uygulamaların "Widget ekle" düğmeleri).
 * iOS tarzı alttan bir cam kart gösterir; kullanıcı onaylarsa öğe ana ekrana eklenir.
 */
class AddItemActivity : ComponentActivity() {
    private var request: LauncherApps.PinItemRequest? = null
    private var pendingWidgetId = -1

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) acceptWidget(bound = true) else cancelWidget()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val req = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        if (req == null || !req.isValid) { finish(); return }
        request = req
        // Arkadaki ekran bulanıklaşır (destekleyen cihazlarda pencere bulanıklığı).
        if (Build.VERSION.SDK_INT >= 31) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 80 }
        }

        val isShortcut = req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        val sc = req.shortcutInfo
        val winfo: AppWidgetProviderInfo? = if (!isShortcut) runCatching { req.getAppWidgetProviderInfo(this) }.getOrNull() else null
        val pkg = sc?.`package` ?: winfo?.provider?.packageName ?: ""
        val appLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val title = when {
            sc != null -> (sc.shortLabel ?: sc.longLabel ?: appLabel).toString()
            winfo != null -> winfo.loadLabel(packageManager) ?: appLabel
            else -> appLabel
        }
        val dpi = resources.displayMetrics.densityDpi
        val px = (72 * resources.displayMetrics.density).toInt()
        val icon: ImageBitmap? = runCatching {
            val d = when {
                sc != null -> la.getShortcutIconDrawable(sc, dpi) ?: packageManager.getApplicationIcon(pkg)
                winfo != null -> winfo.loadPreviewImage(this, dpi) ?: winfo.loadIcon(this, dpi)
                else -> packageManager.getApplicationIcon(pkg)
            }
            d.toBitmap(if (sc != null) px else px * 2, if (sc != null) px else (px * 2 * d.intrinsicHeight.coerceAtLeast(1) / d.intrinsicWidth.coerceAtLeast(1)).coerceIn(px / 2, px * 3)).asImageBitmap()
        }.getOrNull()

        setContent {
            val noRipple = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(noRipple, null) { finish() },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color(0xF2F7F7FA))
                        .clickable(noRipple, null) { }
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (isShortcut) "Ana Ekrana Ekle" else "Widget Ekle",
                        fontFamily = AppFont, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E),
                    )
                    Spacer(Modifier.height(16.dp))
                    if (icon != null) {
                        Image(
                            icon, null,
                            Modifier
                                .then(if (isShortcut) Modifier.size(72.dp) else Modifier.widthIn(max = 260.dp))
                                .clip(RoundedCornerShape(if (isShortcut) 17.dp else 20.dp)),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        title, fontFamily = AppFont, fontSize = 16.sp, color = Color(0xFF1C1C1E),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    if (appLabel.isNotEmpty() && appLabel != title) {
                        Text(appLabel, fontFamily = AppFont, fontSize = 13.sp, color = Color(0xFF8E8E93))
                    }
                    Spacer(Modifier.height(20.dp))
                    Button("Ana Ekrana Ekle", Color(0xFF0A84FF), Color.White) {
                        if (isShortcut) acceptShortcut() else startWidget(winfo)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button("Vazgeç", Color(0x14000000), Color(0xFF0A84FF)) { finish() }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Button(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontFamily = AppFont, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }

    private fun acceptShortcut() {
        val req = request ?: return finish()
        // Kabul edilince kısayol sabitlenir; launcher kısayol listesini tazeleyip ana ekranın sonuna ekler.
        runCatching { if (req.isValid) req.accept() }
        (application as LauncherApp).appRepository.refreshPinned()
        finish()
    }

    private fun startWidget(info: AppWidgetProviderInfo?) {
        val la = application as LauncherApp
        if (info == null) return finish()
        val id = runCatching { la.widgetHost.allocate() }.getOrNull() ?: return finish()
        pendingWidgetId = id
        if (la.widgetHost.bindIfAllowed(id, info)) {
            acceptWidget(bound = true)
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            runCatching { bindLauncher.launch(i) }.onFailure { cancelWidget() }
        }
    }

    private fun acceptWidget(bound: Boolean) {
        val la = application as LauncherApp
        val req = request
        val id = pendingWidgetId
        if (!bound || req == null || id == -1) return cancelWidget()
        val ok = runCatching { req.isValid && req.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }) }.getOrDefault(false)
        if (!ok) return cancelWidget()
        val info = la.widgetHost.info(id)
        // iOS boyutları: küçük 2×2, orta 4×2, büyük 4×4 (widget'ın önerdiği hücre sayısına göre).
        val cw = if (Build.VERSION.SDK_INT >= 31) info?.targetCellWidth ?: 0 else 0
        val ch = if (Build.VERSION.SDK_INT >= 31) info?.targetCellHeight ?: 0 else 0
        val sx = if (cw in 1..2) 2 else 4
        val sy = if (ch >= 3) 4 else 2
        la.launcherViewModel.addWidget(WidgetItem(UUID.randomUUID().toString(), WidgetKind.System, sx, sy, id))
        pendingWidgetId = -1
        finish()
    }

    private fun cancelWidget() {
        if (pendingWidgetId != -1) runCatching { (application as LauncherApp).widgetHost.delete(pendingWidgetId) }
        pendingWidgetId = -1
        finish()
    }
}
