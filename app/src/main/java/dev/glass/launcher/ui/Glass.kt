package dev.glass.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.glass.launcher.data.LauncherSettings

private fun al(x: Float) = x.coerceIn(0f, 1f)
private fun white(a: Float) = Color.White.copy(alpha = al(a))
private fun black(a: Float) = Color.Black.copy(alpha = al(a))

/** Cam yüzeylerin ortak görünüm bilgisi (tema, kalite). Kök bileşende sağlanır. */
@Immutable
data class GlassLook(
    val dark: Boolean = false,
    /** Düşük güç / otomatik kalite düşürme: gölge ve parlama katmanları azaltılır. */
    val reduced: Boolean = false,
)

val LocalGlass = staticCompositionLocalOf { GlassLook() }

/** Geçerli ayarlar; simge ve etiket çizen bileşenler buradan okur. */
val LocalLauncherSettings = staticCompositionLocalOf { LauncherSettings() }

/**
 * Liquid Glass benzeri yüzey: buzlu dolgu, üst-sol speküler parlama, alt iç gölge ve kırılmayı andıran
 * iki tonlu kenar ışığı. Koyu temada cam koyu tonlanır. Gerçek arka plan bulanıklığı, launcher'ın kendi
 * duvar kâğıdı seçiliyse [GlassBackdrop] ile ayrıca çizilir (Android, sistem duvar kâğıdının arkasını
 * bulanıklaştırmaya izin vermez).
 */
fun Modifier.liquidGlass(shape: Shape, strength: Float = 1f, elevation: Dp = 14.dp): Modifier = composed {
    val look = LocalGlass.current
    val s = strength
    val base = if (look.dark) {
        Brush.verticalGradient(listOf(black(0.30f * s + 0.08f), black(0.42f * s + 0.08f)))
    } else {
        Brush.verticalGradient(listOf(white(0.30f * s), white(0.12f * s)))
    }
    val rim = if (look.dark) {
        Brush.linearGradient(listOf(white(0.45f * s), white(0.05f * s), white(0.25f * s)))
    } else {
        Brush.linearGradient(listOf(white(0.80f * s), white(0.10f * s), white(0.50f * s)))
    }
    this
        .then(
            if (look.reduced || elevation == 0.dp) Modifier
            else Modifier.shadow(elevation, shape, clip = false, ambientColor = black(0.2f), spotColor = black(0.2f))
        )
        .clip(shape)
        .background(base)
        .then(
            if (look.reduced) Modifier
            else Modifier.drawWithContent {
                drawContent()
                drawRect(
                    Brush.radialGradient(
                        listOf(white((if (look.dark) 0.12f else 0.24f) * s), Color.Transparent),
                        center = Offset(size.width * 0.2f, 0f),
                        radius = size.width * 0.7f,
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, black(0.10f * s)),
                        startY = size.height * 0.55f,
                        endY = size.height,
                    )
                )
            }
        )
        .border(1.dp, rim, shape)
}

/** Uygulama simgeleri için ince cam kenarı + üst parlama. */
fun Modifier.glassIcon(shape: Shape, enabled: Boolean, strength: Float): Modifier =
    if (!enabled) this.clip(shape)
    else this.clip(shape)
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    listOf(white(0.22f * strength), Color.Transparent),
                    endY = size.height * 0.5f,
                )
            )
        }
        .border(
            1.dp,
            Brush.linearGradient(listOf(white(0.65f * strength), white(0.04f * strength), white(0.35f * strength))),
            shape,
        )

@Composable
fun SearchGlyph(color: Color, glyphSize: Dp) {
    Canvas(Modifier.size(glyphSize)) {
        val s = this.size.minDimension
        val stroke = s * 0.13f
        val r = s * 0.34f
        val c = Offset(s * 0.42f, s * 0.42f)
        drawCircle(color, radius = r, center = c, style = Stroke(stroke))
        drawLine(
            color,
            Offset(c.x + r * 0.72f, c.y + r * 0.72f),
            Offset(s * 0.92f, s * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
