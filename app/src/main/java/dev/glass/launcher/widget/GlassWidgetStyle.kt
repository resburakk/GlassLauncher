package dev.glass.launcher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Telefonun kendi widget'larını (Honor vb.) Liquid Glass görünümüne uyarlar.
 *
 * Widget'ın içeriği uygulamadan gelir ama görünümleri bu süreçte oluşturulur; böylece:
 *  - widget'ın kendi kartı (kök arka plan, `@android:id/background`, alanın çoğunu kaplayan zemin) kaldırılır,
 *    yerine launcher'ın cam zemini gelir;
 *  - koyu gri/siyah yazılar ve tek renkli koyu simgeler camın üstünde okunacak şekilde beyaza çevrilir
 *    (renkli yazılar, ör. kırmızı Pazar, olduğu gibi kalır);
 *  - açık renkli küçük zeminlerin (düğme, bugün işareti) üstündeki yazılara dokunulmaz.
 *
 * Yazısı olmayan, içeriğini hazır resim olarak çizen widget'lar cama uymaz: bunlar ayırt edilir ve
 * widget galerisinde gösterilmez.
 */
object GlassWidgetStyle {

    private class Original(val bg: Drawable?, val text: ColorStateList?, val filter: android.graphics.ColorFilter?, val shadow: Boolean)

    /** Bir görünüm ağacının cama uygunluğu. */
    class Scan {
        var texts = 0
        var lists = 0
        var images = 0
        /** Alanın büyük kısmını kaplayan hazır resim (fotoğraf/çizilmiş kart). */
        var bigBitmap = false
    }

    fun scan(root: View, static: Boolean): Scan {
        val s = Scan()
        val area = (root.width.toLong() * root.height).coerceAtLeast(1)
        fun walk(v: View) {
            if (v.visibility == View.GONE && !static) return
            when (v) {
                is TextView -> s.texts++
                is AdapterView<*> -> s.lists++
                is ImageView -> {
                    s.images++
                    val d = v.drawable
                    val a = v.width.toLong() * v.height
                    if (!static && d is BitmapDrawable && a >= area * 0.6) s.bigBitmap = true
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return s
    }

    fun compatible(s: Scan): Boolean = (s.texts > 0 || s.lists > 0) && !s.bigBitmap

    // ---- Renk yardımcıları ----

    private fun lum(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    private fun sat(c: Int): Float {
        val mx = maxOf(Color.red(c), Color.green(c), Color.blue(c))
        val mn = minOf(Color.red(c), Color.green(c), Color.blue(c))
        return if (mx == 0) 0f else (mx - mn) / mx.toFloat()
    }

    private val drawableTone = WeakHashMap<Drawable, Int>()

    /** Çizimin ortalama rengi (alfa dahil); 8×8 örnekle, çizim başına bir kez. */
    private fun tone(d: Drawable): Int = drawableTone.getOrPut(d) {
        runCatching {
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val old = d.copyBounds()
            d.setBounds(0, 0, 8, 8); d.draw(c); d.bounds = old
            var a = 0L; var r = 0L; var g = 0L; var b = 0L
            for (y in 0 until 8) for (x in 0 until 8) {
                val p = bmp.getPixel(x, y); val pa = Color.alpha(p)
                a += pa; r += Color.red(p) * pa; g += Color.green(p) * pa; b += Color.blue(p) * pa
            }
            bmp.recycle()
            if (a == 0L) Color.TRANSPARENT else Color.argb((a / 64).toInt(), (r / a).toInt(), (g / a).toInt(), (b / a).toInt())
        }.getOrDefault(Color.TRANSPARENT)
    }

    private val monoDark = WeakHashMap<Drawable, Boolean>()

    /** Tek renkli koyu simge mi (glif)? Fotoğraf/renkli simgeler hariç. */
    private fun isMonoDark(d: Drawable): Boolean = monoDark.getOrPut(d) {
        runCatching {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val old = d.copyBounds()
            d.setBounds(0, 0, 16, 16); d.draw(c); d.bounds = old
            var n = 0; var bad = 0
            for (y in 0 until 16) for (x in 0 until 16) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 60) continue
                n++
                if (sat(p) > 0.25f || lum(p) > 0.5f) bad++
            }
            bmp.recycle()
            n > 4 && bad <= n / 20
        }.getOrDefault(false)
    }

    private fun isLight(c: Int) = Color.alpha(c) > 120 && lum(c) > 0.6f

    // ---- Uygulama / geri alma ----

    private val originals = WeakHashMap<View, Original>()

    /** Cam görünümü uygular. Tekrar çağrılabilir (widget güncellenince yeniden uygulanır). */
    fun apply(host: ViewGroup) {
        val content = host.getChildAt(0) ?: return
        val area = (content.width.toLong() * content.height).coerceAtLeast(1)
        if (content.width == 0) return
        fun remember(v: View) {
            if (!originals.containsKey(v)) originals[v] = Original(
                v.background, (v as? TextView)?.textColors, (v as? ImageView)?.colorFilter, (v as? TextView)?.shadowRadius?.let { it > 0f } ?: false,
            )
        }
        fun walk(v: View, onLight: Boolean) {
            var light = onLight
            val bg = v.background
            if (bg != null) {
                val big = v === content || v.id == android.R.id.background || v.width.toLong() * v.height >= area * 0.8
                if (big) { remember(v); v.background = null }
                else if (isLight(tone(bg))) light = true
            }
            if (v is TextView && !light) {
                val c = v.currentTextColor
                if (sat(c) < 0.3f && lum(c) < 0.55f) {
                    remember(v)
                    val a = Color.alpha(c) / 255f * (if (lum(c) < 0.2f) 1f else 0.72f)
                    v.setTextColor(Color.argb((a * 255).toInt(), 255, 255, 255))
                }
                if (v.shadowRadius == 0f && sat(v.currentTextColor) < 0.3f && lum(v.currentTextColor) > 0.6f) {
                    remember(v)
                    v.setShadowLayer(6f, 0f, 1.5f, 0x38000000)
                }
            }
            if (v is ImageView && !light) {
                val d = v.drawable
                if (d != null && d !is BitmapDrawable && v.colorFilter == null && isMonoDark(d)) {
                    remember(v)
                    v.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                } else if (d is BitmapDrawable && v.colorFilter == null && v.width.toLong() * v.height < area * 0.15 && isMonoDark(d)) {
                    remember(v)
                    v.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), light)
        }
        walk(content, false)
    }

    /** Orijinal görünüme döndürür (kaldırılan zeminler ve renkler geri gelir). */
    fun restore(host: ViewGroup) {
        fun walk(v: View) {
            originals.remove(v)?.let { o ->
                if (v.background == null && o.bg != null) v.background = o.bg
                if (v is TextView) {
                    o.text?.let { v.setTextColor(it) }
                    if (!o.shadow) v.setShadowLayer(0f, 0f, 0f, 0)
                }
                if (v is ImageView) v.colorFilter = o.filter
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(host)
    }

    // ---- Galeri için önceden inceleme ----

    /** Görünüm oluşturma Looper isteyebilir (TextClock vb.): ayrı, Looper'lı tek iş parçacığı. */
    val inflateExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({ Looper.prepare(); r.run() }, "widget-scan")
    }

    /**
     * Widget'ın yerleşimini (ön izleme ya da ilk yerleşim) gerçek boyutunda oluşturup inceler.
     * Oluşturulamayan ya da yazısız/resimden ibaret widget'lar uyumsuz sayılır.
     */
    fun probe(ctx: Context, info: AppWidgetProviderInfo): Boolean {
        val layout = info.previewLayout.takeIf { it != 0 } ?: info.initialLayout
        if (layout == 0) return false
        return runCatching {
            val rv = RemoteViews(info.provider.packageName, layout)
            val parent = FrameLayout(ctx)
            val v = rv.apply(ctx, parent)
            parent.addView(v)
            val dm = ctx.resources.displayMetrics
            val w = (info.minWidth.takeIf { it > 0 } ?: (dm.density * 160).toInt()).coerceAtLeast((dm.density * 80).toInt())
            val h = (info.minHeight.takeIf { it > 0 } ?: (dm.density * 160).toInt()).coerceAtLeast((dm.density * 80).toInt())
            parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, w, h)
            compatible(scan(v, static = true))
        }.getOrDefault(false)
    }
}

/** Telefon widget'larını [GlassWidgetHostView] ile barındıran AppWidgetHost. */
class GlassAppWidgetHost(context: Context, id: Int) : AppWidgetHost(context, id) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        GlassWidgetHostView(context)
}

/**
 * Cam görünümü kendi kendine uygulayan widget görünümü. Widget her güncellendiğinde ya da içerik yeniden
 * yerleştiğinde, bir sonraki çizimden hemen önce görünüm yeniden uyarlanır (kare başına ek maliyet yok).
 * [compatible]: içerik cama uygun mu (Compose cam zemini buna göre çizer).
 */
class GlassWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var glass: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) GlassWidgetStyle.restore(this)
            dirty = true; invalidate()
        }
    val compatible = mutableStateOf(true)
    /** İçerik sonradan uyumsuz çıkarsa (hazır resim) bildirilir; galeri bir dahaki sefere bu widget'ı gizler. */
    var onIncompatible: ((AppWidgetProviderInfo) -> Unit)? = null
    private var dirty = true

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { dirty = true }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (dirty) { dirty = false; restyle() }
        true
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        dirty = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnPreDrawListener(preDraw)
        dirty = true
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    private fun restyle() {
        if (!glass) return
        val content = getChildAt(0) ?: return
        if (content.width == 0) return
        val s = GlassWidgetStyle.scan(content, static = false)
        val ok = GlassWidgetStyle.compatible(s)
        if (!ok) {
            if (compatible.value) {
                GlassWidgetStyle.restore(this)
                compatible.value = false
                appWidgetInfo?.let { onIncompatible?.invoke(it) }
            }
            return
        }
        compatible.value = true
        GlassWidgetStyle.apply(this)
    }
}
