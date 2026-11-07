package dev.glass.launcher.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
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
class SquircleShape(val radius: Dp, private val smoothing: Float) : Shape {

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

/** Kenar yumuşatmalı silme fırçası (dışarıda kalan pikselleri kısmi örtüyle siler; basamak oluşmaz). */
internal val AaClearPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
}

/**
 * [clip] ile aynı, ancak iOS sürekli köşesinde (squircle) kenar yumuşatmalı. Android, özel yol ile kırpmada
 * kenar yumuşatma yapmaz (köşeler basamak basamak görünür); burada içerik ayrı bir katmanda çizilip şeklin
 * dışında kalan kısım yumuşak kenarlı olarak silinir. Düz yuvarlak köşe ve daire zaten yumuşak kırpılır.
 */
fun androidx.compose.ui.Modifier.smoothClip(shape: Shape): androidx.compose.ui.Modifier =
    if (shape !is SquircleShape) this.then(androidx.compose.ui.Modifier.clip(shape))
    else this
        .graphicsLayer {
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            clip = true
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val inv = android.graphics.Path().apply {
                when (outline) {
                    is Outline.Generic -> set(outline.path.asAndroidPath())
                    is Outline.Rounded -> addRoundRect(
                        android.graphics.RectF(0f, 0f, size.width, size.height),
                        outline.roundRect.topLeftCornerRadius.x, outline.roundRect.topLeftCornerRadius.y,
                        android.graphics.Path.Direction.CW,
                    )
                    is Outline.Rectangle -> addRect(0f, 0f, size.width, size.height, android.graphics.Path.Direction.CW)
                }
                fillType = android.graphics.Path.FillType.INVERSE_WINDING
            }
            onDrawWithContent {
                drawContent()
                drawIntoCanvas { it.nativeCanvas.drawPath(inv, AaClearPaint) }
            }
        }

/** Şeklin [size] boyutundaki yolu (kök koordinatına [left],[top] kadar kaydırılmış), platform yolu olarak. */
internal fun Shape.androidPath(
    left: Float, top: Float, width: Float, height: Float, dir: LayoutDirection, density: Density,
): android.graphics.Path {
    val p = android.graphics.Path()
    when (val o = createOutline(Size(width, height), dir, density)) {
        is Outline.Generic -> p.set(o.path.asAndroidPath())
        is Outline.Rounded -> p.addRoundRect(
            android.graphics.RectF(0f, 0f, width, height),
            o.roundRect.topLeftCornerRadius.x, o.roundRect.topLeftCornerRadius.y, android.graphics.Path.Direction.CW,
        )
        is Outline.Rectangle -> p.addRect(0f, 0f, width, height, android.graphics.Path.Direction.CW)
    }
    p.offset(left, top)
    return p
}
