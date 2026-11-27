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
        }.getOrDefault(false).also {
            if (it) {
                _version.value++
                prefs.edit().putBoolean("fromSync", false).apply() // elle seçildi
            }
        }
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

    /** Launcher kopyası sistem duvar kâğıdından mı alındı (ikisi aynı görüntü mü)? */
    fun isFromSync(): Boolean = prefs.getBoolean("fromSync", false)

    /** Son kopyalama sorunu: 0 yok, 1 izin yok, 2 hareketli duvar kâğıdı, 3 başka hata (ana ekrandaki uyarı kartı için). */
    val syncProblem: Int get() = prefs.getInt("problem", 0)
    private fun problem(c: Int) { prefs.edit().putInt("problem", c).apply() }

    /** Son kopyalama denemesinin sonucu (Ayarlar'da gösterilir). */
    val syncStatus: String get() = prefs.getString("status", null) ?: "Henüz denenmedi."

    private fun status(s: String) {
        prefs.edit().putString("status", s).apply()
        android.util.Log.i("GlassDiag", "wallpaperSync $s")
    }

    /**
     * Sistem duvar kâğıdını launcher kopyası olarak alır (cam bulanıklığı ve kırılma için). Yalnızca
     * duvar kâğıdı değiştiyse kopyalar ([force] hariç). Canlı duvar kâğıdında veya izin yoksa false döner.
     * Görüntü cihazda kalır; hiçbir yere gönderilmez.
     */
    private fun sameBytes(a: File, b: File): Boolean = runCatching {
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                val ba = ByteArray(64 * 1024); val bb = ByteArray(64 * 1024)
                while (true) {
                    val na = x.readNBytes(ba, 0, ba.size); val nb = y.readNBytes(bb, 0, bb.size)
                    if (na != nb) return@runCatching false
                    if (na == 0) return@runCatching true
                    if (!ba.copyOfRange(0, na).contentEquals(bb.copyOfRange(0, nb))) return@runCatching false
                }
                @Suppress("UNREACHABLE_CODE") true
            }
        }
    }.getOrDefault(false)

    /** Ekrandan alınması gereken canlı duvar kâğıdı (parmak izi); null = gerek yok. MainActivity izler. */
    val liveCaptureNeeded = MutableStateFlow<String?>(null)

    /**
     * Launcher içeriği gizliyken alınan ekran görüntüsünü (yalnızca canlı duvar kâğıdı görünür) cam için kaydeder.
     * Durum çubuğu ve gezinme çubuğu bölgeleri (saat, simgeler, ada) hemen altlarındaki/üstlerindeki satır uzatılarak
     * örtülür.
     */
    suspend fun saveLiveCapture(shot: Bitmap, fp: String, topPx: Int, bottomPx: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val b = shot.copy(Bitmap.Config.ARGB_8888, true)
            val c = android.graphics.Canvas(b)
            val w = b.width; val h = b.height
            val t = topPx.coerceIn(0, h / 4); val bt = bottomPx.coerceIn(0, h / 4)
            if (t > 0) c.drawBitmap(b, android.graphics.Rect(0, t + 2, w, t + 3), android.graphics.Rect(0, 0, w, t + 2), null)
            if (bt > 0) c.drawBitmap(b, android.graphics.Rect(0, h - bt - 3, w, h - bt - 2), android.graphics.Rect(0, h - bt - 2, w, h), null)
            val tmp = File(context.filesDir, "wallpaper.tmp")
            tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) return@runCatching false
            prefs.edit().putInt("lastId", -1).putString("lastFp", fp).putBoolean("fromSync", true).apply()
            liveCaptureNeeded.value = null
            _version.value++
            val ts = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("tr")).format(java.util.Date())
            status("Canlı duvar kâğıdı ana ekrandan alındı ($ts, ${w}x$h).")
            problem(0)
            true
        }.getOrElse { status("Canlı duvar kâğıdı alınamadı: ${it.message}"); false }
    }

    /**
     * Canlı duvar kâğıdının önizleme resmini cam için kopyalar. Yalnızca resim ekranla yaklaşık aynı orandaysa (±%15;
     * yoksa camdaki görüntü kayık dururdu) ve renkleri sistemin bildirdiği renklerle tutuyorsa kullanılır.
     */
    private fun fromLiveThumbnail(
        wm: WallpaperManager, live: android.app.WallpaperInfo, name: String, fp: String, sysColors: android.app.WallpaperColors?,
    ): Boolean = runCatching {
        val d = live.loadThumbnail(context.packageManager) ?: run { android.util.Log.i("GlassDiag", "canlı önizleme yok"); return@runCatching false }
        val w = d.intrinsicWidth; val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return@runCatching false
        val dm = context.resources.displayMetrics
        val screen = minOf(dm.widthPixels, dm.heightPixels).toFloat() / maxOf(dm.widthPixels, dm.heightPixels)
        val ratio = minOf(w, h).toFloat() / maxOf(w, h)
        if (h < w || kotlin.math.abs(ratio - screen) > screen * 0.15f) {
            android.util.Log.i("GlassDiag", "canlı önizleme oranı uymuyor ${w}x$h")
            return@runCatching false
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h); d.draw(android.graphics.Canvas(bmp))
        val tmp = File(context.filesDir, "wallpaper.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (sysColors != null && !colorsMatch(tmp, sysColors)) { android.util.Log.i("GlassDiag", "canlı önizleme renkleri uymuyor"); tmp.delete(); return@runCatching false }
        if (!tmp.renameTo(file)) return@runCatching false
        prefs.edit().putInt("lastId", -1).putString("lastFp", fp).putBoolean("fromSync", true).apply()
        _version.value++
        val t = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("tr")).format(java.util.Date())
        status("Canlı duvar kâğıdı \"$name\": önizleme resmi kullanılıyor ($t, ${w}x$h).")
        problem(0)
        true
    }.getOrElse { android.util.Log.w("GlassDiag", "canlı önizleme: ${it.message}"); false }

    /**
     * Kopyalanan görüntü, sistemin ekrandaki duvar kâğıdı için bildirdiği renklerle uyuşuyor mu. Sistemin baskın rengi,
     * görüntünün kendi baskın renklerinden birine yakın olmalı (gevşek eşik: yalnızca tamamen başka bir görüntüyü yakalar).
     */
    private fun colorsMatch(f: File, sys: android.app.WallpaperColors): Boolean = runCatching {
        val small = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
            val k = 96f / maxOf(info.size.width, info.size.height)
            d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val mine = android.app.WallpaperColors.fromBitmap(small)
        val a = listOfNotNull(sys.primaryColor, sys.secondaryColor, sys.tertiaryColor)
        val b = listOfNotNull(mine.primaryColor, mine.secondaryColor, mine.tertiaryColor)
        fun dist(x: android.graphics.Color, y: android.graphics.Color): Float {
            val dr = (x.red() - y.red()) * 255; val dg = (x.green() - y.green()) * 255; val db = (x.blue() - y.blue()) * 255
            return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
        }
        // Sistemin baskın rengi görüntünün renklerinden birine VE görüntünün baskın rengi sistemin renklerinden birine yakın.
        val p = a.first(); val q = b.first()
        b.minOf { dist(p, it) } < 90f || a.minOf { dist(q, it) } < 90f
    }.getOrDefault(true)

    suspend fun syncFromSystem(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // Android sürümüne göre biri ya da ikisi gerekir; hiçbiri yoksa denemeye gerek yok.
        // Android 14+ (Samsung, Honor 400 ve diğerleri): sistem duvar kâğıdını okumak "Tüm dosyalara erişim" ister;
        // Fotoğraflar izni tek başına yetmez (bazı üreticiler yine de verir, Honor 600 gibi). İzin yoksa denenir,
        // güvenlik hatası gelirse kullanıcıya açıkça hangi iznin gerektiği söylenir.
        if (!hasPhotosPermission() && !hasAllFilesAccess()) {
            status("İzin yok: Tüm dosyalara erişim gerekli.")
            problem(1)
            return@withContext false
        }
        runCatching {
            val wm = context.getSystemService(WallpaperManager::class.java)
            // Canlı (hareketli) duvar kâğıdının görüntüsünü Android başka uygulamalara vermez.
            val live = wm.wallpaperInfo
            if (live != null) {
                val name = runCatching { live.loadLabel(context.packageManager).toString() }.getOrDefault(live.packageName)
                val lc = runCatching { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
                val liveFp = "live:" + live.component.flattenToShortString() + ":" +
                    (lc?.let { c -> listOfNotNull(c.primaryColor, c.secondaryColor, c.tertiaryColor).joinToString("/") { it.toArgb().toString() } } ?: "")
                // Bu canlı duvar kâğıdı zaten alındıysa (önizlemeden ya da ekrandan) yeniden alınmaz.
                if (!force && liveFp == prefs.getString("lastFp", "") && file.exists()) { liveCaptureNeeded.value = null; problem(0); return@runCatching true }
                // Honor dinamik temaları canlı duvar kâğıdıdır: görüntüsü verilmez. 1) Önizleme resmi ekranla aynı
                // oranda ve renkleri tutuyorsa o kullanılır. 2) Tutmuyorsa launcher içeriği bir anlık gizlenip
                // erişilebilirlik hizmetiyle duvar kâğıdının kendisi ekrandan alınır (MainActivity).
                if (fromLiveThumbnail(wm, live, name, liveFp, lc)) { liveCaptureNeeded.value = null; return@runCatching true }
                // Eski kopya (sistemden alınmışsa) yanlış görüntü göstermesin; elle seçilen launcher duvar kâğıdına dokunma.
                if (prefs.getBoolean("fromSync", false) && file.exists()) { file.delete(); _version.value++ }
                prefs.edit().putInt("lastId", -1).putString("lastFp", "").apply()
                if (dev.glass.launcher.service.LockService.canScreenshot()) {
                    status("Canlı duvar kâğıdı: \"$name\". Ana ekrandan alınıyor (simgeler bir an gizlenir)…")
                    liveCaptureNeeded.value = liveFp
                } else {
                    status("Canlı duvar kâğıdı: \"$name\". Görüntüsü alınamıyor. Ayarlar > Erişilebilirlik'te Glass Launcher hizmetini açarsan ana ekrandan alınır.")
                    liveCaptureNeeded.value = null
                }
                problem(2)
                return@runCatching false
            }
            liveCaptureNeeded.value = null
            val id = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            // Kimlik tek başına yetmiyor: MagicOS teması / dinamik tema duvar kâğıdını değiştirince kimlik aynı kalabiliyor
            // (cam eski duvar kâğıdını gösteriyordu). Sistemin bildirdiği renkler de parmak izi olarak karşılaştırılır.
            val sysColors = runCatching { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
            val fp = sysColors?.let { c -> listOfNotNull(c.primaryColor, c.secondaryColor, c.tertiaryColor).joinToString("/") { it.toArgb().toString() } } ?: ""
            if (!force && id >= 0 && id == prefs.getInt("lastId", -1) && fp == prefs.getString("lastFp", "") && file.exists()) { problem(0); return@runCatching true }
            val tmp = File(context.filesDir, "wallpaper.tmp")
            fun fromDrawable(): Boolean {
                val d = runCatching { wm.drawable }.getOrNull() ?: return false
                val bmp = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    ?: android.graphics.Bitmap.createBitmap(
                        d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888,
                    ).also { b -> d.setBounds(0, 0, b.width, b.height); d.draw(android.graphics.Canvas(b)) }
                tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                return true
            }
            // Önce orijinal dosya (tam çözünürlük); yoksa çizilebilir nesneden üret.
            val pfd = runCatching { wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
            var source = "orijinal dosya"
            if (pfd != null) {
                pfd.use { p -> android.os.ParcelFileDescriptor.AutoCloseInputStream(p).use { input -> tmp.outputStream().use { input.copyTo(it) } } }
                // Bazı temalarda (MagicOS) dosya eski duvar kâğıdında kalıyor, ekranda görünen başka: renkler tutmuyorsa
                // ekranda çizilen görüntü (drawable) denenir.
                if (sysColors != null && !colorsMatch(tmp, sysColors) && fromDrawable()) source = "ekrandaki görüntü"
            } else {
                if (!fromDrawable()) {
                    status("Duvar kâğıdı görüntüsü alınamadı.")
                    problem(3)
                    return@runCatching false
                }
                source = "ekrandaki görüntü"
            }
            // Hâlâ ekrandaki duvar kâğıdıyla uyuşmuyorsa yanlış görüntü camda gösterilmez (eski kopya silinir).
            if (sysColors != null && !colorsMatch(tmp, sysColors)) {
                tmp.delete()
                if (prefs.getBoolean("fromSync", false) && file.exists()) { file.delete(); _version.value++ }
                prefs.edit().putInt("lastId", id).putString("lastFp", fp).apply()
                status("Sistemin verdiği görüntü ekrandaki duvar kâğıdıyla uyuşmuyor (tema). Cam yalnızca saydam çizilir.")
                problem(3)
                return@runCatching false
            }
            // Aynı resim yeniden geldiyse (MagicOS kilit açılışında duvar kâğıdı kimliğini yenileyebiliyor) ana ekran
            // duvar kâğıdı baştan yüklenmez: yükleme her seferinde kısa bir yanıp sönmeye yol açıyordu.
            if (file.exists() && file.length() == tmp.length() && sameBytes(file, tmp)) {
                tmp.delete()
                prefs.edit().putInt("lastId", id).putString("lastFp", fp).putBoolean("fromSync", true).apply()
                problem(0)
                return@runCatching true
            }
            if (!tmp.renameTo(file)) return@runCatching false
            prefs.edit().putInt("lastId", id).putString("lastFp", fp).putBoolean("fromSync", true).apply()
            _version.value++
            val t = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("tr")).format(java.util.Date())
            status("Kopyalandı ($t, ${file.length() / 1024} KB, $source).")
            problem(0)
            true
        }.onFailure {
            if (it is SecurityException) { status("İzin yok: Tüm dosyalara erişim gerekli."); problem(1) }
            else { status("Hata: ${it.javaClass.simpleName} ${it.message ?: ""}".trim()); problem(3) }
        }
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
