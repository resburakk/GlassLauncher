package dev.glass.launcher.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * iOS'un "sürekli köşe" (squircle) şekli. Köşe kavisi düz kenara ani değil, yavaşça karışır
 * (eğrilik süreklidir). [smoothing] 0 = düz çeyrek daire (RoundedCornerShape ile aynı),
 * ~0.6 = iOS simgelerine en yakın. Figma'nın "corner smoothing" formülüyle hesaplanır.
 */
class SquircleShape(private val radius: Dp, private val smoothing: Float) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val maxP = min(w, h) / 2f
        var r = with(density) { radius.toPx() }.coerceIn(0f, maxP)
        var s = smoothing.coerceIn(0f, 1f)
        if (r <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        var p = (1f + s) * r
        if (p > maxP) { s = (maxP / r - 1f).coerceAtLeast(0f); p = (1f + s) * r }
        r = min(r, maxP)

        // Sağ üst köşe için yerel noktalar (Figma corner smoothing).
        val arcMeasure = 90f * (1f - s)
        val arcSection = sin(rad(arcMeasure / 2f)) * r * sqrt(2f)
        val angleAlpha = (90f - arcMeasure) / 2f
        val p3ToP4 = r * tan(rad(angleAlpha / 2f))
        val angleBeta = 45f * s
        val c = p3ToP4 * cos(rad(angleBeta))
        val d = c * tan(rad(angleBeta))
        val b = (p - arcSection - c - d) / 3f
        val a = 2f * b

        // Köşe, her biri 4 noktalı kübik eğrilerden oluşur: giriş eğrisi, yay, çıkış eğrisi.
        val segs = mutableListOf<List<Offset>>()
        val e1 = Offset(w - p + a + b + c, d)
        segs += listOf(Offset(w - p, 0f), Offset(w - p + a, 0f), Offset(w - p + a + b, 0f), e1)
        if (arcMeasure > 0.01f) {
            val center = Offset(w - r, r)
            val a0 = rad(-45f - arcMeasure / 2f)
            val a1 = rad(-45f + arcMeasure / 2f)
            val k = 4f / 3f * tan((a1 - a0) / 4f)
            val p0 = Offset(center.x + r * cos(a0), center.y + r * sin(a0))
            val p3 = Offset(center.x + r * cos(a1), center.y + r * sin(a1))
            val p1 = Offset(p0.x - k * r * sin(a0), p0.y + k * r * cos(a0))
            val p2 = Offset(p3.x + k * r * sin(a1), p3.y - k * r * cos(a1))
            segs += listOf(p0, p1, p2, p3)
        }
        val e2 = Offset(w - d, p - a - b - c)
        segs += listOf(e2, Offset(w, p - a - b), Offset(w, p - a), Offset(w, p))

        fun corner(map: (Offset) -> Offset, reverse: Boolean): List<List<Offset>> {
            val mapped = segs.map { seg -> seg.map(map) }
            return if (reverse) mapped.reversed().map { it.reversed() } else mapped
        }
        val tr = corner({ it }, false)
        val br = corner({ Offset(it.x, h - it.y) }, true)
        val bl = corner({ Offset(w - it.x, h - it.y) }, false)
        val tl = corner({ Offset(w - it.x, it.y) }, true)

        val path = Path()
        path.moveTo(tl.last().last().x, tl.last().last().y)
        for (cornerSegs in listOf(tr, br, bl, tl)) {
            val first = cornerSegs.first().first()
            path.lineTo(first.x, first.y)
            for (seg in cornerSegs) path.cubicTo(seg[1].x, seg[1].y, seg[2].x, seg[2].y, seg[3].x, seg[3].y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private fun rad(deg: Float) = (deg * Math.PI / 180.0).toFloat()

    override fun equals(other: Any?) = other is SquircleShape && other.radius == radius && other.smoothing == smoothing
    override fun hashCode() = radius.hashCode() * 31 + smoothing.hashCode()
}

/** Launcher genelinde köşe şekli: "Köşe yumuşaklığı" ayarına göre iOS sürekli köşe ya da düz yuvarlak köşe. */
@Composable
fun appShape(radius: Dp): Shape {
    val s = LocalLauncherSettings.current.cornerSmoothing
    return if (s < 0.01f) RoundedCornerShape(radius) else SquircleShape(radius, s)
}
