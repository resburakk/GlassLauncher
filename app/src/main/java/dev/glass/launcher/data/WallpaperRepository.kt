package dev.glass.launcher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Bulanık duvar kâğıdı kopyası ve orijinal görselin boyutu (ekrana yerleşim hesabı için). */
class Blurred(val image: ImageBitmap, val srcW: Int, val srcH: Int) {
    /** Kırılma (mercek) gölgelendiricisine girdi olarak verilen doku; kenarlarda kenar pikseli uzatılır. */
    val shader: android.graphics.BitmapShader by lazy {
        android.graphics.BitmapShader(
            image.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
        ).apply {
            // Küçük bulanık kopya büyütülürken yumuşak (doğrusal) örneklenir; aksi halde kare kare pikseller görünür.
            filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR
        }
    }
}

/**
 * Launcher'ın kendi duvar kâğıdı (Photo Picker ile seçilir, izin gerekmez).
 * Gerçek arka plan bulanıklığı (Liquid Glass) yalnızca bu görsel üzerinden çizilebilir.
 */
class WallpaperRepository(private val context: Context) {
    private val file get() = File(context.filesDir, "wallpaper.img")

    private val _version = MutableStateFlow(0)
    /** Launcher duvar kâğıdı her değiştiğinde artar (ör. galeriden "Farklı ayarla" ile); ana ekran yeniden yükler. */
    val version: StateFlow<Int> = _version

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
        }.getOrDefault(false).also { if (it) _version.value++ }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete().also { _version.value++ } }

    /**
     * Sistem duvar kâğıdını okuma izni var mı? Android bunun için "Fotoğraflar ve videolar" (görseller) iznini
     * ister; bazı sürümlerde "Tüm dosyalara erişim" de yeterlidir.
     */
    fun canReadSystemWallpaper(): Boolean = hasPhotosPermission() && hasAllFilesAccess()

    /** "Fotoğraflar" (görseller) izni. */
    fun hasPhotosPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** "Tüm dosyalara erişim" izni. */
    fun hasAllFilesAccess(): Boolean = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

    private val prefs get() = context.getSharedPreferences("wallpaper_sync", Context.MODE_PRIVATE)

    /**
     * Sistem duvar kâğıdını launcher kopyası olarak alır (cam bulanıklığı ve kırılma için). Yalnızca
     * duvar kâğıdı değiştiyse kopyalar ([force] hariç). Canlı duvar kâğıdında veya izin yoksa false döner.
     * Görüntü cihazda kalır; hiçbir yere gönderilmez.
     */
    suspend fun syncFromSystem(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // Android sürümüne göre biri ya da ikisi gerekir; hiçbiri yoksa denemeye gerek yok.
        if (!hasPhotosPermission() && !hasAllFilesAccess()) {
            android.util.Log.i("GlassDiag", "wallpaperSync izin yok")
            return@withContext false
        }
        runCatching {
            val wm = context.getSystemService(WallpaperManager::class.java)
            val id = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            if (!force && id >= 0 && id == prefs.getInt("lastId", -1) && file.exists()) return@runCatching true
            val tmp = File(context.filesDir, "wallpaper.tmp")
            // Önce orijinal dosya (tam çözünürlük); yoksa çizilebilir nesneden üret.
            val pfd = runCatching { wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
            if (pfd != null) {
                pfd.use { p -> android.os.ParcelFileDescriptor.AutoCloseInputStream(p).use { input -> tmp.outputStream().use { input.copyTo(it) } } }
            } else {
                val d = wm.drawable ?: run {
                    android.util.Log.i("GlassDiag", "wallpaperSync cizilebilir yok (canli duvar kagidi?)")
                    return@runCatching false
                }
                val bmp = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    ?: android.graphics.Bitmap.createBitmap(
                        d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888,
                    ).also { b -> d.setBounds(0, 0, b.width, b.height); d.draw(android.graphics.Canvas(b)) }
                tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (!tmp.renameTo(file)) return@runCatching false
            prefs.edit().putInt("lastId", id).apply()
            _version.value++
            android.util.Log.i("GlassDiag", "wallpaperSync kopyalandi id=$id dosya=${pfd != null} boyut=${file.length()}")
            true
        }.onFailure { android.util.Log.w("GlassDiag", "wallpaperSync hata: ${it.javaClass.simpleName} ${it.message}") }
            .getOrDefault(false)
    }

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
    suspend fun applyToSystem(
        flags: Int = WallpaperManager.FLAG_SYSTEM,
        source: Uri? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val wm = context.getSystemService(WallpaperManager::class.java)
            val input = if (source != null) context.contentResolver.openInputStream(source)!! else file.inputStream()
            input.use { wm.setStream(it, null, true, flags) }
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
