package dev.glass.launcher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Bulanık duvar kâğıdı kopyası ve orijinal görselin boyutu (ekrana yerleşim hesabı için). */
class Blurred(val image: ImageBitmap, val srcW: Int, val srcH: Int)

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

    /**
     * Cam yüzeyler için önceden bulanıklaştırılmış küçük kopya (bir kez üretilir, çizimde ölçeklenir).
     * Küçük boyuta indirip kutu bulanıklığı uygulamak, her karede bulanıklık hesaplamaktan çok daha ucuzdur.
     */
    suspend fun loadBlurred(): Blurred? = withContext(Dispatchers.Default) {
        val f = file
        if (!f.exists()) return@withContext null
        runCatching {
            var srcW = 0
            var srcH = 0
            val small = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                srcW = info.size.width
                srcH = info.size.height
                val k = BLUR_W / srcW.toFloat()
                decoder.setTargetSize(BLUR_W, (srcH * k).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.copy(Bitmap.Config.ARGB_8888, true)
            BlurUtil.boxBlur(small, radius = 4, passes = 3)
            BlurUtil.saturate(small, 1.25f)
            Blurred(small.asImageBitmap(), srcW, srcH)
        }.getOrNull()
    }

    /** Kopyalanan görseli sistem duvar kâğıdı (ana ekran) olarak da ayarlar. SET_WALLPAPER izni gerekir. */
    suspend fun applyToSystem(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val wm = context.getSystemService(WallpaperManager::class.java)
            file.inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) }
            true
        }.getOrDefault(false)
    }

    // --- Fotoğraf widget'ı görselleri ---

    private val photoDir: File get() = File(context.filesDir, "photos").apply { mkdirs() }

    suspend fun savePhoto(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = "photo_${System.currentTimeMillis()}.img"
            context.contentResolver.openInputStream(uri)!!.use { input ->
                File(photoDir, name).outputStream().use { input.copyTo(it) }
            }
            name
        }.getOrNull()
    }

    fun deletePhoto(name: String) {
        runCatching { File(photoDir, name).delete() }
    }

    suspend fun loadPhoto(name: String, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = File(photoDir, name)
        if (!f.exists()) return@withContext null
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height).toFloat()
                val s = minOf(1f, maxSide / longest)
                decoder.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1),
                )
            }.asImageBitmap()
        }.getOrNull()
    }

    private companion object {
        const val MAX_SIDE = 1600f
        const val BLUR_W = 120
    }
}
