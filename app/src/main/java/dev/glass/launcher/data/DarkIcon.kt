package dev.glass.launcher.data

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * iOS 18/26 tarzı koyu mod simgesi (yapay zekâ yok, iOS'taki gibi hesapla):
 * 1. Zemin bulunur. Düz ya da tek renk tonunda geçişli zeminse koyu iOS zeminiyle değiştirilir.
 * 2. Renkli zemin üzerindeki beyaz/gri sembol zeminin rengini alır (iOS: WhatsApp yeşil sembol, siyah zemin).
 * 3. Açık zemin üzerindeki koyu, renksiz sembol açılır (siyah yazı -> beyaz); renkli kısımlar korunur.
 * 4. Fotoğraf gibi / çok renkli zeminli simgeler yalnızca karartılır; zaten koyu simgeler neredeyse değişmez.
 */
object DarkIcon {

    private class Bg(val uniform: Boolean, val colorful: Boolean, val hue: Float, val sat: Float, val lum: Float, val v: Float = 1f) {
        /**
         * Zemin zaten koyu mu (X, Uber, TikTok gibi siyah/koyu gri): yalnızca hafifçe karartılır. Parlaklığa (luma)
         * göre değil HSV değerine göre bakılır: koyu lacivert, kırmızı, mor gibi doygun renkli zeminler (Booking,
         * Pinterest, e-Devlet) luma'da düşük çıkıp yanlışlıkla "koyu" sayılıyor ve hiç dönüştürülmüyordu.
         */
        val dark: Boolean get() = if (colorful) v < 0.30f else lum < 0.30f
    }

    /** Uyarlanabilir simge: zemin ve ön plan katmanları ayrı. [orig] ikisinin normal birleşimi. */
    fun fromLayers(bgLayer: Bitmap, fgLayer: Bitmap, orig: Bitmap): Bitmap {
        val n = orig.width
        val bg = IntArray(n * n).also { bgLayer.getPixels(it, 0, n, 0, 0, n, n) }
        val samples = ArrayList<Int>()
        var i = 0
        while (i < bg.size) { if ((bg[i] ushr 24) > 200) samples += bg[i]; i += 3 }
        if (samples.size < 50) return fromFlat(orig)
        val info = analyze(samples.toIntArray())
        if (info.dark) return scaled(orig, 0.92f)
        val fg = IntArray(n * n).also { fgLayer.getPixels(it, 0, n, 0, 0, n, n) }
        val weight = FloatArray(n * n) { (fg[it] ushr 24) / 255f }
        // Ön plan katmanı simgenin çoğunu kaplıyorsa (Honor/MagicOS: zemin plakası ön planın içinde) katmanlar
        // ayrı değerlendirilemez: plaka sembol sanılır ve koyu zeminde açık bir kare (çerçeve) kalırdı. Birleşik
        // simgeden zemin kenardan bulunur.
        run {
            var cover0 = 0
            for (a in weight) if (a > 0.5f) cover0++
            if (cover0 > 0.5f * n * n) return fromFlat(orig)
        }
        if (!info.uniform) {
            // Geçişli/çok renkli zemin (Instagram gibi): ön plan katmanı ayrıysa zemin yine siyaha döner ve açık
            // renkli sembol zeminin AYNI noktadaki rengini alır (iOS: renk geçişli sembol, siyah zemin). Ön plan
            // bütün simgeyi kaplıyorsa (resim gibi simge) yalnızca karartılır.
            var cover = 0
            for (a in weight) if (a > 0.5f) cover++
            if (cover > 0.62f * n * n) return scaled(orig, 0.72f)
            return compose(fg, weight, info, orig, n, bgPx = bg, forceColorize = info.colorful)
        }
        return compose(fg, weight, info, orig, n, bgPx = if (info.colorful) bg else null)
    }

    /** Katmansız (eski tip) simge: zemin, kenara yakın piksellerden bulunur. */
    fun fromFlat(orig: Bitmap): Bitmap {
        val n = orig.width
        val px = IntArray(n * n).also { orig.getPixels(it, 0, n, 0, 0, n, n) }
        val e0 = (n * 0.04f).toInt(); val e1 = (n * 0.11f).toInt(); val corner = (n * 0.22f).toInt()
        val raw = ArrayList<Int>()
        for (y in 0 until n step 2) for (x in 0 until n step 2) {
            val e = min(min(x, y), min(n - 1 - x, n - 1 - y))
            if (e !in e0..e1) continue
            val nearX = x < corner || x > n - 1 - corner
            val nearY = y < corner || y > n - 1 - corner
            if (nearX && nearY) continue // köşeler ekranda yuvarlatılıyor; örnek alınmaz
            val c = px[y * n + x]
            if ((c ushr 24) > 200) raw += c
        }
        if (raw.size < 30) return scaled(orig, 0.72f)
        // Kenara değen büyük semboller (ahize, kalp) zemin sanılmasın: zeminden belirgin farklı örnekler atılır.
        val med = medianColor(raw)
        val dd = raw.map { dist(it, med) }
        val cut = max(percentile(dd, 0.60f) * 1.6f, 18f)
        val samples = raw.filterIndexed { k, _ -> dd[k] <= cut }.toIntArray()
        val info = analyze(samples)
        if (info.dark) return scaled(orig, 0.92f)
        if (!info.uniform) return floodFlat(px, n, med, orig) ?: whiteOnGradient(px, n, info, orig) ?: scaled(orig, 0.72f)
        val pal = IntArray(min(64, samples.size)) { samples[(it.toLong() * (samples.size - 1) / max(1, min(64, samples.size) - 1)).toInt()] }
        val weight = FloatArray(n * n) { p ->
            val c = px[p]
            if ((c ushr 24) < 40) 0f
            else {
                var best = Float.MAX_VALUE
                for (q in pal) { val d = dist(c, q); if (d < best) best = d }
                smooth(24f, 72f, best) * ((c ushr 24) / 255f)
            }
        }
        return compose(px, weight, info, orig, n)
    }

    /**
     * Büyük logolu tek parça simge (Chrome gibi): zemin, kenar şeridinden içeri doğru zemin rengine yakın pikseller
     * izlenerek bulunur (boya kovası). Zemin yeterince büyük ve düz renkse siyaha döner, logo olduğu gibi kalır.
     */
    private fun floodFlat(px: IntArray, n: Int, med: Int, orig: Bitmap): Bitmap? {
        val tol = 46f
        val mask = BooleanArray(n * n)
        val queue = IntArray(n * n)
        var head = 0; var tail = 0
        val e0 = (n * 0.03f).toInt(); val e1 = (n * 0.08f).toInt()
        fun seed(x: Int, y: Int) {
            val p = y * n + x
            if (mask[p]) return
            val c = px[p]
            if ((c ushr 24) > 200 && dist(c, med) < tol) { mask[p] = true; queue[tail++] = p }
        }
        for (k in 0 until n) for (e in e0..e1) {
            seed(k, e); seed(k, n - 1 - e); seed(e, k); seed(n - 1 - e, k)
        }
        while (head < tail) {
            val p = queue[head++]
            val x = p % n; val y = p / n
            if (x > 0) seed(x - 1, y)
            if (x < n - 1) seed(x + 1, y)
            if (y > 0) seed(x, y - 1)
            if (y < n - 1) seed(x, y + 1)
        }
        var count = 0
        for (m in mask) if (m) count++
        if (count < 0.12f * n * n) return null
        val samples = IntArray(minOf(count, 4000))
        var k = 0; var idx = 0
        val stepS = maxOf(1, count / samples.size)
        for (p in mask.indices) if (mask[p]) { if (idx % stepS == 0 && k < samples.size) samples[k++] = px[p]; idx++ }
        val info = analyze(samples.copyOf(k))
        if (info.dark) return null
        // Ön plan ağırlığı: zemin dışı; kenarlarda zemine olan uzaklıkla yumuşak geçiş (pürüzsüz logo kenarı).
        val weight = FloatArray(n * n) { p ->
            val c = px[p]
            when {
                (c ushr 24) < 40 -> 0f
                mask[p] -> 0f
                else -> smooth(tol * 0.6f, tol * 1.6f, dist(c, med)) * ((c ushr 24) / 255f)
            }
        }
        return compose(px, weight, info, orig, n)
    }

    /**
     * Renk geçişli (Instagram gibi) zemin üzerinde beyaz sembol: zemin siyaha döner, sembol zeminin AYNI noktadaki
     * rengini alır (iOS'taki gibi). Zemin rengi, beyaz olmayan piksellerin kaba ızgara ortalamasından kestirilir.
     */
    private fun whiteOnGradient(px: IntArray, n: Int, info: Bg, orig: Bitmap): Bitmap? {
        if (!info.colorful) return null
        val hsv = FloatArray(3)
        fun white(c: Int): Boolean { toHsv(c, hsv); return (c ushr 24) > 120 && hsv[1] < 0.22f && hsv[2] > 0.80f }
        var whites = 0
        for (c in px) if (white(c)) whites++
        // Beyaz sembol belirgin olmalı ama simgenin çoğu olmamalı.
        if (whites < 0.04f * n * n || whites > 0.55f * n * n) return null
        val g = 8
        val sr = FloatArray(g * g); val sg = FloatArray(g * g); val sb = FloatArray(g * g); val cnt = IntArray(g * g)
        for (y in 0 until n) for (x in 0 until n) {
            val c = px[y * n + x]
            if ((c ushr 24) < 200 || white(c)) continue
            val k = (y * g / n) * g + (x * g / n)
            sr[k] += ((c shr 16) and 0xff).toFloat(); sg[k] += ((c shr 8) and 0xff).toFloat(); sb[k] += (c and 0xff).toFloat(); cnt[k]++
        }
        // Boş hücreler komşulardan doldurulur.
        repeat(4) {
            for (k in 0 until g * g) if (cnt[k] == 0) {
                val x = k % g; val y = k / g
                var r = 0f; var gg = 0f; var b = 0f; var m = 0
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until g || yy !in 0 until g) continue
                    val q = yy * g + xx
                    if (cnt[q] > 0) { r += sr[q] / cnt[q]; gg += sg[q] / cnt[q]; b += sb[q] / cnt[q]; m++ }
                }
                if (m > 0) { sr[k] = r / m; sg[k] = gg / m; sb[k] = b / m; cnt[k] = 1 }
            }
        }
        val field = IntArray(n * n) { p ->
            val fx = (p % n).toFloat() * g / n - 0.5f; val fy = (p / n).toFloat() * g / n - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, g - 1); val y0 = floor(fy).toInt().coerceIn(0, g - 1)
            val x1 = (x0 + 1).coerceAtMost(g - 1); val y1 = (y0 + 1).coerceAtMost(g - 1)
            val tx = (fx - x0).coerceIn(0f, 1f); val ty = (fy - y0).coerceIn(0f, 1f)
            fun ch(a: FloatArray, k: Int) = if (cnt[k] > 0) a[k] / cnt[k] else 128f
            fun bil(a: FloatArray) = lerp(lerp(ch(a, y0 * g + x0), ch(a, y0 * g + x1), tx), lerp(ch(a, y1 * g + x0), ch(a, y1 * g + x1), tx), ty)
            (0xff shl 24) or (clamp(bil(sr)) shl 16) or (clamp(bil(sg)) shl 8) or clamp(bil(sb))
        }
        val weight = FloatArray(n * n) { p ->
            val c = px[p]
            if ((c ushr 24) < 40) 0f else { toHsv(c, hsv); smooth(0.62f, 0.86f, hsv[2]) * (1f - smooth(0.18f, 0.32f, hsv[1])) * ((c ushr 24) / 255f) }
        }
        return compose(px, weight, info, orig, n, bgPx = field, forceColorize = true)
    }

    /** Açık renkli ön plan, simgeyi bir kenardan öbür kenara kesen bir şerit mi (idefix'teki beyaz bant)? */
    private fun lightBand(fg: IntArray, w: FloatArray, n: Int): Boolean {
        val hsv = FloatArray(3)
        fun light(p: Int): Boolean {
            if (w[p] < 0.5f) return false
            val c = fg[p]; toHsv(c, hsv)
            return hsv[1] < 0.20f && lum(c) > 0.62f
        }
        val e = (n * 0.06f).toInt().coerceAtLeast(1)
        var rows = 0
        for (y in (n * 0.15f).toInt() until (n * 0.85f).toInt()) {
            if (light(y * n + e) && light(y * n + n - 1 - e)) rows++
        }
        var cols = 0
        for (x in (n * 0.15f).toInt() until (n * 0.85f).toInt()) {
            if (light(e * n + x) && light((n - 1 - e) * n + x)) cols++
        }
        return rows > n * 0.08f || cols > n * 0.08f
    }

    /** Koyu zemin + yeniden renklendirilmiş ön plan. [fg] ön plan renkleri, [w] ön plan ağırlığı (0..1). */
    private fun compose(
        fg: IntArray, w: FloatArray, bg: Bg, orig: Bitmap, n: Int,
        /** Zeminin piksel renkleri (uyarlanabilir simge): beyaz sembol bu renkleri piksel piksel alır. */
        bgPx: IntArray? = null,
        forceColorize: Boolean = false,
    ): Bitmap {
        val total = n * n
        var fgCount = 0; var darkCount = 0; var lightCount = 0
        val hsv = FloatArray(3)
        for (p in 0 until total) {
            if (w[p] < 0.5f) continue
            fgCount++
            val c = fg[p]; toHsv(c, hsv); val l = lum(c)
            if (hsv[1] < 0.30f && l < 0.40f) darkCount++
            if (hsv[1] < 0.20f && l > 0.62f) lightCount++
        }
        // Zemin çıkarılınca geriye neredeyse hiçbir şey kalmıyorsa (World Mobil gibi tamamı tek renk karakter)
        // simge yanlış ayrışmıştır: orijinali korunur, hafifçe koyulaştırılır.
        var wsum = 0f
        for (a in w) wsum += a
        if (fgCount < 30 || wsum < 0.05f * total) return scaled(orig, 0.86f)
        // Fotoğraf gibi büyük koyu alan (kamera lensi): yalnızca karart.
        if (darkCount.toFloat() / total > 0.30f) return scaled(orig, 0.72f)
        // Büyük beyaz şerit/plaka (idefix, Trendyol gibi): ince bir sembol değil tasarımın parçası. Zemin rengiyle
        // boyanırsa tasarım bozulur; orijinal görünüm korunur, hafifçe koyulaştırılır.
        val colorizeWanted = (forceColorize || (bg.colorful && bg.sat > 0.30f)) && lightCount > 0.4f * fgCount
        if (colorizeWanted && (lightCount > 0.30f * total || lightBand(fg, w, n))) return scaled(orig, 0.86f)
        val colorize = colorizeWanted
        val bh = FloatArray(3)
        // Koyu sembolleri açığa çevirmek yalnızca ince yazı/çizgilerde (Arrows'taki oklar). Büyük koyu dolgu
        // (Trendyol'un siyah şeridi) çevrilmez; beyaza dönüp tasarımı bozuyordu.
        val invertDark = darkCount > 0.02f * fgCount && darkCount < 0.10f * total
        val src = decontaminate(fg, w, n)
        val out = IntArray(total)
        for (y in 0 until n) {
            val t = y / (n - 1f)
            val br = lerp(44f, 10f, t); val bgG = lerp(44f, 10f, t); val bb = lerp(46f, 12f, t)
            for (x in 0 until n) {
                val p = y * n + x
                val a = w[p].coerceIn(0f, 1f)
                var r = br; var g = bgG; var b = bb
                if (a > 0.002f) {
                    val c = src[p]
                    var fr = ((c shr 16) and 0xff).toFloat()
                    var fgc = ((c shr 8) and 0xff).toFloat()
                    var fb = (c and 0xff).toFloat()
                    toHsv(c, hsv); val l = lum(c)
                    if (colorize && hsv[1] < 0.20f && l > 0.62f) {
                        val src = bgPx?.get(p)
                        val rgb = if (src != null && (src ushr 24) > 120) {
                            // Zeminin bu noktadaki rengi: canlı ve parlak tutulur (koyu zeminde okunaklı).
                            toHsv(src, bh)
                            fromHsv(bh[0], min(bh[1] * 1.08f, 0.92f), (max(bh[2], 0.78f) * (0.55f + 0.45f * l)).coerceIn(0f, 1f))
                        } else {
                            fromHsv(bg.hue, min(bg.sat * 1.05f, 0.85f), (l * 0.98f).coerceIn(0f, 1f))
                        }
                        fr = rgb[0]; fgc = rgb[1]; fb = rgb[2]
                    } else if (invertDark && hsv[1] < 0.30f && l < 0.40f) {
                        val rgb = fromHsv(hsv[0], hsv[1] * 0.6f, (1f - hsv[2] * 0.85f).coerceIn(0f, 1f))
                        fr = rgb[0]; fgc = rgb[1]; fb = rgb[2]
                    }
                    r = r * (1 - a) + fr * a; g = g * (1 - a) + fgc * a; b = b * (1 - a) + fb * a
                }
                out[p] = (0xff shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
        return createBitmap(n, n).also { it.setPixels(out, 0, n, 0, 0, n, n) }
    }

    /**
     * Kenar temizliği (TÜM simgeler için aynı): şeklin kenarındaki yarı saydam pikseller, orijinal simgede şeklin rengi
     * ile (çoğu zaman beyaz) zeminin karışımıdır. Zemin koyuya dönünce bu karışık renk şeklin etrafında açık bir halka
     * bırakıyordu. Kenar pikselinin rengi en yakın TAM şekil pikselinden alınır; saydamlığı (yumuşak kenar) korunur.
     */
    private fun decontaminate(fg: IntArray, w: FloatArray, n: Int): IntArray {
        val out = fg.copyOf()
        val solid = 0.92f
        val maxR = maxOf(2, n / 48)
        for (y in 0 until n) for (x in 0 until n) {
            val p = y * n + x
            val a = w[p]
            if (a <= 0.002f || a >= solid) continue
            var best = -1; var bestD = Int.MAX_VALUE
            for (r in 1..maxR) {
                val y0 = maxOf(0, y - r); val y1 = minOf(n - 1, y + r)
                val x0 = maxOf(0, x - r); val x1 = minOf(n - 1, x + r)
                for (yy in y0..y1) for (xx in x0..x1) {
                    if (maxOf(abs(xx - x), abs(yy - y)) != r) continue // yalnızca bu halka
                    val q = yy * n + xx
                    if (w[q] < solid) continue
                    val d = (xx - x) * (xx - x) + (yy - y) * (yy - y)
                    if (d < bestD) { bestD = d; best = q }
                }
                if (best >= 0) break
            }
            if (best >= 0) out[p] = (fg[p] and 0xff000000.toInt()) or (fg[best] and 0x00ffffff)
        }
        return out
    }

    private fun analyze(samples: IntArray): Bg {
        val hsv = FloatArray(3)
        var colorful = 0; var sx = 0.0; var sy = 0.0
        val sats = ArrayList<Float>(); val vals = FloatArray(samples.size); val lums = FloatArray(samples.size)
        for ((k, c) in samples.withIndex()) {
            toHsv(c, hsv); vals[k] = hsv[2]; lums[k] = lum(c)
            if (hsv[1] > 0.25f) {
                colorful++; sats += hsv[1]
                val ang = hsv[0] * 2 * Math.PI
                sx += cos(ang); sy += sin(ang)
            }
        }
        val frac = colorful.toFloat() / samples.size
        val lum = percentile(lums.toList(), 0.5f)
        if (frac > 0.6f) {
            val r = hypot(sx, sy) / colorful
            val hue = ((atan2(sy, sx) / (2 * Math.PI)).toFloat() + 1f) % 1f
            val satMean = sats.average().toFloat()
            val satStd = sqrt(sats.map { (it - satMean) * (it - satMean) }.average()).toFloat()
            return Bg(r > 0.97 && satStd < 0.18f, true, hue, percentile(sats, 0.5f), lum, percentile(vals.toList(), 0.5f))
        }
        val vMean = vals.average().toFloat()
        val vStd = sqrt(vals.map { (it - vMean) * (it - vMean) }.average()).toFloat()
        return Bg(frac < 0.15f && vStd < 0.10f, false, 0f, 0f, lum, vMean)
    }

    private fun scaled(src: Bitmap, k: Float): Bitmap {
        val n = src.width
        val px = IntArray(n * n).also { src.getPixels(it, 0, n, 0, 0, n, n) }
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and 0xff000000.toInt()) or
                (clamp(((c shr 16) and 0xff) * k) shl 16) or
                (clamp(((c shr 8) and 0xff) * k) shl 8) or
                clamp((c and 0xff) * k)
        }
        return createBitmap(n, src.height).also { it.setPixels(px, 0, n, 0, 0, n, src.height) }
    }

    // --- Yardımcılar ---
    private fun lum(c: Int) = (0.2126f * ((c shr 16) and 0xff) + 0.7152f * ((c shr 8) and 0xff) + 0.0722f * (c and 0xff)) / 255f
    private fun dist(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val db = (a and 0xff) - (b and 0xff)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }
    private fun medianColor(cs: List<Int>): Int {
        fun ch(s: Int) = cs.map { (it shr s) and 0xff }.sorted()[cs.size / 2]
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun percentile(v: List<Float>, q: Float): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted(); return s[((s.size - 1) * q).toInt()]
    }
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun clamp(v: Float) = v.toInt().coerceIn(0, 255)

    /** h, s, v: 0..1 */
    private fun toHsv(c: Int, out: FloatArray) {
        val r = ((c shr 16) and 0xff) / 255f; val g = ((c shr 8) and 0xff) / 255f; val b = (c and 0xff) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        var h = 0f
        if (d > 1e-6f) {
            h = when (mx) {
                r -> ((g - b) / d).let { if (it < 0) it + 6 else it }
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6f
        }
        out[0] = h; out[1] = if (mx > 0) d / mx else 0f; out[2] = mx
    }

    private fun fromHsv(h: Float, s: Float, v: Float): FloatArray {
        val hh = ((h % 1f) + 1f) % 1f * 6f
        val i = floor(hh).toInt() % 6; val f = hh - floor(hh)
        val p = v * (1 - s); val q = v * (1 - f * s); val t = v * (1 - (1 - f) * s)
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
            3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
        }
        return floatArrayOf(r * 255f, g * 255f, b * 255f)
    }

    @Suppress("unused") private fun absf(x: Float) = abs(x)
}
