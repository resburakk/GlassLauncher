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
data class IconLook(val style: IconStyle, val tint: Int, val customFile: String?, val fillRound: Boolean = true)

/**
 * Sınırlı boyutlu simge önbelleği. Bitmap'ler ekrandaki gerçek boyutta üretilir (büyük bitmap yok);
 * sayısı ve toplam bellek LRU ile sınırlıdır. Ağır iş ana thread dışında yapılır.
 */
class IconCache(private val context: Context) {
    // Toplam ~24 MB üst sınır (192 px simge ≈ 147 KB).
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private val iconDir: File get() = File(context.filesDir, "icons").apply { mkdirs() }

    suspend fun get(app: AppInfo, sizePx0: Int, look: IconLook): ImageBitmap {
        // Animasyonun ilk karesinde (ör. 0'dan büyüyen menü/hücre) boyut 0 gelebilir: en az 1 px.
        val sizePx = sizePx0.coerceAtLeast(1)
        val k = "${app.key}@$sizePx/${look.style}/${look.tint}/${look.customFile}/${look.fillRound}"
        cache.get(k)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            // Küçük simgeler (klasör önizlemesi vb.) de en az 192 px'te çizilip sonra küçültülür: yuvarlak simge
            // algılaması her boyutta aynı sonucu verir ve küçük simgeler daha net olur.
            val renderPx = maxOf(sizePx, MIN_RENDER_PX)
            val custom = look.customFile?.let { f ->
                runCatching { BitmapFactory.decodeFile(File(iconDir, f).path) }.getOrNull()
            }
            val big = if (custom != null) {
                IconRenderer.renderCustom(custom, renderPx).also { custom.recycle() }
            } else {
                // En yüksek çözünürlüklü simge kaynağı istenir; ekrandaki boyuta yüksek kaliteyle küçültülür (daha net).
                val d = runCatching { app.activityInfo.getIcon(android.util.DisplayMetrics.DENSITY_XXXHIGH) }.getOrNull()
                    ?: app.activityInfo.getIcon(0)
                IconRenderer.render(d, renderPx, look.style, look.tint, look.fillRound)
            }
            val out = if (renderPx == sizePx) big
            else android.graphics.Bitmap.createScaledBitmap(big, sizePx, sizePx, true).also { if (it !== big) big.recycle() }
            out.asImageBitmap()
        }
        cache.put(k, bmp)
        return bmp
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

    private companion object {
        const val MIN_RENDER_PX = 192
    }
}
