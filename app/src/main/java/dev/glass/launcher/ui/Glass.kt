package dev.glass.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

private fun al(x: Float) = x.coerceIn(0f, 1f)
private fun white(a: Float) = Color.White.copy(alpha = al(a))

/**
 * Liquid Glass benzeri yüzey: buzlu dolgu, üst-sol speküler parlama, alt iç gölge ve
 * kırılmayı andıran iki tonlu kenar ışığı. Gerçek arka plan bulanıklığı değildir
 * (Android, duvar kâğıdının arkasını bulanıklaştırmaya izin vermez).
 */
fun Modifier.liquidGlass(shape: Shape, strength: Float = 1f, elevation: Dp = 14.dp): Modifier =
    this.shadow(elevation, shape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(white(0.30f * strength), white(0.12f * strength))))
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.radialGradient(
                    listOf(white(0.24f * strength), Color.Transparent),
                    center = Offset(size.width * 0.2f, 0f),
                    radius = size.width * 0.7f,
                )
            )
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = al(0.10f * strength))),
                    startY = size.height * 0.55f,
                    endY = size.height,
                )
            )
        }
        .border(
            1.dp,
            Brush.linearGradient(listOf(white(0.80f * strength), white(0.10f * strength), white(0.50f * strength))),
            shape,
        )

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
