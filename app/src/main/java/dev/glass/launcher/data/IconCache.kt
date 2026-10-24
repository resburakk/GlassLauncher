package dev.glass.launcher.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Simge görünümünü belirleyen ayarlar (önbellek anahtarının parçası). */
data class IconLook(val style: IconStyle, val tint: Int, val customFile: String?, val fillRound: Boolean = true, val iconPack: String = "")

/**
 * Sınırlı boyutlu simge önbelleği. Bitmap'ler ekrandaki gerçek boyutta üretilir (büyük bitmap yok);
 * sayısı ve toplam bellek LRU ile sınırlıdır. Ağır iş ana thread dışında yapılır.
 */
class IconCache(private val context: Context) {
    // Toplam ~128 MB üst sınır: kalite öncelikli, bütün simgeler bellekte kalsın (yeniden üretim yok).
    private val cache = object : LruCache<String, ImageBitmap>(128 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private val iconDir: File get() = File(context.filesDir, "icons").apply { mkdirs() }

    suspend fun get(app: AppInfo, sizePx0: Int, look: IconLook): ImageBitmap {
        // Animasyonun ilk karesinde (ör. 0'dan büyüyen menü/hücre) boyut 0 gelebilir: en az 1 px.
        val sizePx = sizePx0.coerceAtLeast(1)
        val k = "${app.key}~${app.iconStamp}@$sizePx/${look.style}/${look.tint}/${look.customFile}/${look.fillRound}/${look.iconPack}"
        cache.get(k)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            // Küçük simgeler (klasör önizlemesi vb.) de en az 192 px'te çizilip sonra küçültülür: yuvarlak simge
            // algılaması her boyutta aynı sonucu verir ve küçük simgeler daha net olur.
            // İki kat çözünürlükte üretilip yüksek kaliteyle küçültülür: koyu/renkli simgelerde sembol kenarları
            // pürüzsüz, ince çizgiler net.
            val renderPx = maxOf(sizePx * 2, MIN_RENDER_PX)
            val custom = look.customFile?.let { f ->
                runCatching { BitmapFactory.decodeFile(File(iconDir, f).path) }.getOrNull()
            }
            val big0 = if (custom != null) {
                IconRenderer.renderCustom(custom, renderPx).also { custom.recycle() }
            } else {
                // En yüksek çözünürlüklü simge kaynağı istenir; ekrandaki boyuta yüksek kaliteyle küçültülür (daha net).
                val appIcon = { runCatching { app.activityInfo.getIcon(android.util.DisplayMetrics.DENSITY_XXXHIGH) }.getOrNull()
                    ?: app.activityInfo.getIcon(0) }
                // Simge paketi seçiliyse paketteki çizim (paket içermiyorsa uygulamanın kendi simgesi).
                val packIcon = if (look.iconPack.isNotEmpty() && app.shortcut == null) {
                    IconPacks.icon(context, look.iconPack, app.componentName, android.util.DisplayMetrics.DENSITY_XXXHIGH)
                } else null
                val sc = app.shortcut
                val scIcon = sc?.let { info ->
                    runCatching {
                        context.getSystemService(android.content.pm.LauncherApps::class.java)
                            .getShortcutIconDrawable(info, android.util.DisplayMetrics.DENSITY_XXXHIGH)
                    }.getOrNull()
                }
                val main = if (packIcon != null) {
                    // Paket tasarımı korunur: şekli (yuvarlak vb.) kareye tamamlanmaz.
                    IconRenderer.render(packIcon, renderPx, look.style, look.tint, false)
                } else IconRenderer.render(scIcon ?: appIcon(), renderPx, look.style, look.tint, look.fillRound)
                if (sc != null && scIcon != null) withAppBadge(main, appIcon(), renderPx, look) else main
            }
            val big = if (app.profile == ProfileKind.Work) withWorkBadge(big0, renderPx) else big0
            val out = if (renderPx == sizePx) big else downscale(big, sizePx).also { if (it !== big) big.recycle() }
            out.asImageBitmap()
        }
        cache.put(k, bmp)
        return bmp
    }

    /**
     * Kısayol simgesinin sağ alt köşesine sahibi olan uygulamanın küçük simgesi (Android'deki gibi):
     * hangi uygulamanın kısayolu olduğu anlaşılır. Rozet ince bir halkayla simgeden ayrılır.
     */
    private fun withAppBadge(main: android.graphics.Bitmap, appIcon: android.graphics.drawable.Drawable, size: Int, look: IconLook): android.graphics.Bitmap {
        val out = if (main.isMutable) main else main.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { main.recycle() }
        val b = (size * 0.40f).toInt().coerceAtLeast(8)
        val badge = IconRenderer.render(appIcon, b, look.style, look.tint, look.fillRound)
        val c = android.graphics.Canvas(out)
        val l = (size - b).toFloat(); val t = (size - b).toFloat()
        val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(90, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(b * 0.06f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(l, t + b * 0.03f, l + b, t + b * 1.03f, b * 0.25f, b * 0.25f, ring)
        c.drawBitmap(badge, l, t, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        badge.recycle()
        return out
    }

    /** İş profili uygulaması: sağ altta mavi daire içinde beyaz çanta (Android'deki iş rozeti gibi). */
    private fun withWorkBadge(main: android.graphics.Bitmap, size: Int): android.graphics.Bitmap {
        val out = if (main.isMutable) main else main.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { main.recycle() }
        val c = android.graphics.Canvas(out)
        val r = size * 0.17f
        val cx = size - r * 1.05f; val cy = size - r * 1.05f
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(cx, cy, r, p)
        p.color = 0xFF1A73E8.toInt()
        c.drawCircle(cx, cy, r * 0.86f, p)
        // Çanta: gövde ve sap.
        p.color = android.graphics.Color.WHITE
        val bw = r * 0.95f; val bh = r * 0.66f
        c.drawRoundRect(cx - bw / 2, cy - bh / 2 + r * 0.08f, cx + bw / 2, cy + bh / 2 + r * 0.08f, r * 0.12f, r * 0.12f, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = r * 0.12f
        c.drawRoundRect(cx - bw * 0.22f, cy - bh / 2 - r * 0.16f, cx + bw * 0.22f, cy - bh / 2 + r * 0.1f, r * 0.08f, r * 0.08f, p)
        return out
    }

    /** Seçilen görseli uygulamaya özel simge olarak kopyalar; dosya adını döndürür. */
    suspend fun importCustomIcon(appKey: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = "icon_${appKey.hashCode().toUInt()}_${System.currentTimeMillis()}.img"
            val dst = File(iconDir, name)
            context.contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
            // Geçerli bir görsel mi? (yalnızca boyut okunur)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dst.path, opts)
            if (opts.outWidth <= 0) { dst.delete(); null } else name
        }.getOrNull()
    }

    fun deleteCustomIcon(name: String) {
        runCatching { File(iconDir, name).delete() }
    }

    fun clear() = cache.evictAll()

    /** Yüksek kaliteli küçültme: büyük oranlarda yarıya yarıya inilir (tek adımda küçültmede ayrıntı kaybolur). */
    private fun downscale(src: android.graphics.Bitmap, size: Int): android.graphics.Bitmap {
        var cur = src
        while (cur.width / 2 >= size) {
            val next = android.graphics.Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (cur.width == size) return cur
        val out = android.graphics.Bitmap.createScaledBitmap(cur, size, size, true)
        if (cur !== src && out !== cur) cur.recycle()
        return out
    }

    private companion object {
        const val MIN_RENDER_PX = 384
    }
}
