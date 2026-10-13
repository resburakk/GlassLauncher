package dev.glass.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import kotlin.math.max

/**
 * Uygulama simgesini iOS gibi "tam dolu" kare olarak çizer ve seçilen görünümü uygular.
 *
 * - Uyarlanabilir (adaptive) simgeler: arka plan ve ön plan katmanları ayrı ayrı, kenara kadar çizilir
 *   (sistemin şekil maskesi ve kenar boşluğu kullanılmaz; köşe yuvarlaklığını launcher verir).
 * - Eski tip simgeler: saydam kenarlar kırpılır; simge kareyi dolduruyorsa tam boy, dolduramıyorsa
 *   (yuvarlak/şekilli logolar) açık/koyu bir plaka üzerine ortalanır.
 * - Açık / Koyu: arka plan katmanı açık veya koyu degrade ile değiştirilir.
 * - Renkli (tinted): koyu zemin üzerinde tek renk simge. Monokrom katman varsa o, yoksa ön planın
 *   parlaklığından üretilen maske kullanılır (makul bir yedek; her uygulamada kusursuz olmayabilir).
 *
 * Çıktı köşeleri yuvarlatılmamış kare bitmap'tir; yuvarlatma ekranda (ayarlanabilir) yapılır.
 */
object IconRenderer {

    fun render(d: Drawable, size: Int, style: IconStyle, tint: Int): Bitmap {
        val out = createBitmap(size, size)
        val c = Canvas(out)
        if (d is AdaptiveIconDrawable) {
            // Katmanlara renk filtresi uygulanacağı için paylaşılan sistem önbelleği etkilenmesin.
            renderAdaptive(c, d.mutate() as AdaptiveIconDrawable, size, style, tint)
        } else {
            renderLegacy(c, d, size, style, tint)
        }
        return out
    }

    /** Kullanıcının seçtiği PNG/WebP görselini kareye ortalayarak kırpar. */
    fun renderCustom(src: Bitmap, size: Int): Bitmap {
        val out = createBitmap(size, size)
        val c = Canvas(out)
        val side = minOf(src.width, src.height)
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2
        c.drawBitmap(src, Rect(sx, sy, sx + side, sy + side), Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun drawBackdrop(c: Canvas, size: Int, style: IconStyle) {
        val (top, bottom) = when (style) {
            IconStyle.Light -> Color.rgb(255, 255, 255) to Color.rgb(232, 232, 237)
            IconStyle.Dark, IconStyle.Tinted -> Color.rgb(44, 44, 46) to Color.rgb(10, 10, 12)
            IconStyle.Original -> Color.WHITE to Color.WHITE
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
    }

    private fun renderAdaptive(c: Canvas, d: AdaptiveIconDrawable, size: Int, style: IconStyle, tint: Int) {
        // 108 birimlik katman tuvalinin ortadaki 72 birimi ekranda görünen alandır.
        val inset = (size * 0.25f).toInt()
        val bounds = Rect(-inset, -inset, size + inset, size + inset)
        val fg = d.foreground
        val bg = d.background
        when (style) {
            IconStyle.Original -> {
                bg?.let { it.bounds = bounds; it.draw(c) }
                fg?.let { it.bounds = bounds; it.draw(c) }
            }
            IconStyle.Light, IconStyle.Dark -> {
                drawBackdrop(c, size, style)
                fg?.let { layer ->
                    layer.bounds = bounds
                    if (style == IconStyle.Dark) {
                        // Koyu zeminde ön planı hafif aydınlat (iOS koyu simgeler gibi canlı kalsın).
                        layer.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1.08f, 1.08f, 1.08f, 1f) })
                    }
                    layer.draw(c)
                    layer.colorFilter = null
                }
            }
            IconStyle.Tinted -> {
                drawBackdrop(c, size, style)
                val mono = d.monochrome
                if (mono != null) {
                    mono.bounds = bounds
                    mono.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    mono.draw(c)
                    mono.colorFilter = null
                } else if (fg != null) {
                    drawTintedFromLuminance(c, fg, bounds, size, tint)
                }
            }
        }
    }

    private fun renderLegacy(c: Canvas, d: Drawable, size: Int, style: IconStyle, tint: Int) {
        // Önce geçici olarak çiz, saydam kenarları bul.
        val tmp = createBitmap(size, size)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(tmp))
        val box = opaqueBounds(tmp) ?: run {
            drawBackdrop(c, size, if (style == IconStyle.Original) IconStyle.Light else style)
            return
        }
        val cropped = Bitmap.createBitmap(tmp, box.left, box.top, box.width(), box.height())
        val fills = fillsSquare(cropped)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (style == IconStyle.Tinted) paint.colorFilter = tintFilter(tint)
        if (fills && style == IconStyle.Original) {
            // Kare simge: kenara kadar (kenar yumuşamasını gizlemek için çok az büyütülür).
            val over = size * 0.02f
            c.drawBitmap(cropped, null, RectF(-over, -over, size + over, size + over), paint)
        } else {
            drawBackdrop(c, size, if (style == IconStyle.Original) IconStyle.Light else style)
            val scale = if (fills) 0.74f else 0.78f
            val w = cropped.width.toFloat()
            val h = cropped.height.toFloat()
            val k = size * scale / max(w, h)
            val dw = w * k
            val dh = h * k
            val l = (size - dw) / 2f
            val t = (size - dh) / 2f
            c.drawBitmap(cropped, null, RectF(l, t, l + dw, t + dh), paint)
        }
        if (cropped != tmp) cropped.recycle()
        tmp.recycle()
    }

    private fun drawTintedFromLuminance(c: Canvas, layer: Drawable, bounds: Rect, size: Int, tint: Int) {
        val tmp = createBitmap(size, size)
        layer.bounds = bounds
        layer.draw(Canvas(tmp))
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = tintFilter(tint)
        c.drawBitmap(tmp, 0f, 0f, p)
        tmp.recycle()
    }

    /** Parlaklığı saydamlığa çevirip tek renge boyar: açık pikseller renkli, koyu pikseller sönük. */
    private fun tintFilter(tint: Int): ColorMatrixColorFilter {
        val r = Color.red(tint) / 255f
        val g = Color.green(tint) / 255f
        val b = Color.blue(tint) / 255f
        return ColorMatrixColorFilter(
            floatArrayOf(
                0f, 0f, 0f, 0f, r * 255f,
                0f, 0f, 0f, 0f, g * 255f,
                0f, 0f, 0f, 0f, b * 255f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
            )
        )
    }

    private fun opaqueBounds(b: Bitmap): Rect? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bo = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 40) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > bo) bo = y
            }
        }
        return if (r < l || bo < t) null else Rect(l, t, r + 1, bo + 1)
    }

    /** Kırpılmış simge kareyi dolduruyor mu? (köşelere yakın noktalar opak mı) */
    private fun fillsSquare(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        if (w < 4 || h < 4) return false
        val ratio = w.toFloat() / h
        if (ratio < 0.9f || ratio > 1.1f) return false
        val i = 0.16f
        val pts = listOf(i to i, 1 - i to i, i to 1 - i, 1 - i to 1 - i)
        return pts.all { (fx, fy) ->
            val x = (fx * (w - 1)).toInt()
            val y = (fy * (h - 1)).toInt()
            (b.getPixel(x, y) ushr 24) > 200
        }
    }
}
