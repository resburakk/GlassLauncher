package dev.glass.launcher.data

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sınırlı boyutlu simge önbelleği (en fazla 160 bitmap). Ağır iş ana thread dışında. */
class IconCache {
    private val cache = LruCache<String, ImageBitmap>(160)

    suspend fun get(app: AppInfo, sizePx: Int): ImageBitmap {
        val k = "${app.key}@$sizePx"
        cache.get(k)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            app.activityInfo.getIcon(0).toBitmap(sizePx, sizePx).asImageBitmap()
        }
        cache.put(k, bmp)
        return bmp
    }

    fun clear() = cache.evictAll()
}
