package dev.glass.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.glass.launcher.data.AppInfo
import kotlin.math.roundToInt

/** Uygulamadan ana ekrana dönüş efektinin bilgisi. [target]: simgenin yeri (kök koordinatı, px). */
class ReturnInfo(val app: AppInfo, val target: Rect, val color: Color, val onScreen: Boolean)

/**
 * Dönüş efekti (iOS'taki "uygulama simgesine küçülür" hissi): ekran boyutunda, uygulamanın renginde ve
 * simgesini taşıyan yuvarlak köşeli bir kart, simgenin yerine küçülerek oturur. [p] 0 → 1 ilerler.
 *
 * Not: Android, gerçek uygulama penceresinin simgeye küçülmesini yalnızca sistem launcher'ına izin verir;
 * bu efekt launcher'ın kendi katmanında çizilir.
 */
@Composable
fun AppReturnOverlay(info: ReturnInfo, screen: IntSize, p: Float, vm: LauncherViewModel) {
    val density = LocalDensity.current
    val st = LocalLauncherSettings.current
    val t = info.target
    val left = lerp(0f, t.left, p)
    val top = lerp(0f, t.top, p)
    val w = lerp(screen.width.toFloat(), t.width, p)
    val h = lerp(screen.height.toFloat(), t.height, p)
    val iconPx = t.width
    val iconDp = with(density) { iconPx.toDp() }
    // Köşe: ekranın yuvarlak köşesinden simge köşesine.
    val corner = with(density) { lerp(42.dp.toPx(), iconPx * st.iconCorner, p).toDp() }
    // Simge kartın ortasında, başta ekranın ~%28'i kadar, sonunda kartı tam doldurur.
    val startIcon = minOf(screen.width, screen.height) * 0.28f
    val iconNow = lerp(startIcon, iconPx, p)
    // Ekranda değilse (başka sayfa) kart ortada küçülüp kaybolur.
    val alpha = if (info.onScreen) (if (p > 0.92f) (1f - p) / 0.08f else 1f) else 1f - p
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
            .clip(appShape(corner))
            .background(info.color),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(iconDp).graphicsLayer { scaleX = iconNow / iconPx; scaleY = iconNow / iconPx }) {
            AppIconImage(info.app, vm, iconDp, st.iconGlass, st.glassStrength)
        }
    }
}
