package dev.glass.launcher.data

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Launcher'ın kendi duvar kâğıdı (Photo Picker ile seçilir, izin gerekmez).
 * Gerçek arka plan bulanıklığı (Liquid Glass) yalnızca bu görsel üzerinden çizilebilir.
 */
class WallpaperRepository(private val context: Context) {
    private val file get() = File(context.filesDir, "wallpaper.img")

    suspend fun load(): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext null
        runCatching {
            val src = ImageDecoder.createSource(f)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height).toFloat()
                val s = minOf(1f, MAX_SIDE / longest)
                decoder.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1),
                )
            }.asImageBitmap()
        }.getOrNull()
    }

    suspend fun save(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.filesDir, "wallpaper.tmp")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.renameTo(file)
        }.getOrDefault(false)
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

    private companion object { const val MAX_SIDE = 1600f }
}
