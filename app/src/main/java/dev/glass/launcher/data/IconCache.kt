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
data class IconLook(val style: IconStyle, val tint: Int, val customFile: String?)

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

    suspend fun get(app: AppInfo, sizePx: Int, look: IconLook): ImageBitmap {
        val k = "${app.key}@$sizePx/${look.style}/${look.tint}/${look.customFile}"
        cache.get(k)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            val custom = look.customFile?.let { f ->
                runCatching { BitmapFactory.decodeFile(File(iconDir, f).path) }.getOrNull()
            }
            if (custom != null) {
                IconRenderer.renderCustom(custom, sizePx).also { custom.recycle() }
            } else {
                IconRenderer.render(app.activityInfo.getIcon(0), sizePx, look.style, look.tint)
            }.asImageBitmap()
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
}
