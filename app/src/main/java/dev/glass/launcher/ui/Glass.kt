package dev.glass.launcher.ui

import androidx.compose.ui.graphics.drawscope.clipRect

import androidx.compose.ui.geometry.Size

import androidx.compose.ui.graphics.addOutline

import androidx.compose.ui.layout.onPlaced

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.runtime.snapshotFlow

import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import dev.glass.launcher.data.Blurred
import dev.glass.launcher.data.LauncherSettings

private fun al(x: Float) = x.coerceIn(0f, 1f)

/**
 * Genel şeffaflık kaydırıcısının cam dolgusuna çarpanı: 0 → 0.35 (çok şeffaf), 0.5 → 1 (bugünkü görünüm),
 * 1 → 1.8 (renkli/opak).
 */
fun glassTintFactor(t: Float): Float =
    if (t < 0.5f) 0.35f + (t / 0.5f) * 0.65f else 1f + ((t - 0.5f) / 0.5f) * 0.8f

/** iOS 27 tarzı koyu derinlik kenarı: parlak kenar çizgisinin hemen içinde ince, yarı saydam koyu bant. */
private fun Modifier.darkEdge(shape: Shape, amount: Float, width: Dp): Modifier =
    if (amount <= 0.01f) this else this.border(width, black(0.22f * amount), shape)
private fun white(a: Float) = Color.White.copy(alpha = al(a))
private fun black(a: Float) = Color.Black.copy(alpha = al(a))

/** Cam yüzeylerin ortak görünüm bilgisi (tema, kalite). Kök bileşende sağlanır. */
@Immutable
data class GlassLook(
    val dark: Boolean = false,
    /** Düşük güç / otomatik kalite düşürme: gölge, parlama ve bulanıklık katmanları kapatılır. */
    val reduced: Boolean = false,
    /** Kaydırmada yavaşlık algılandı: kenar kırılması kapalı (bulanıklık açık kalır). */
    val noLens: Boolean = false,
    /** iOS 26 "Şeffaf" cam: arkası net (hafif yumuşak), dolgu çok ince, kenarda belirgin mercek. */
    val clear: Boolean = false,
    /** Launcher duvar kâğıdının NET kopyası (şeffaf cam için); yoksa null. */
    val sharp: Blurred? = null,
    /** Launcher duvar kâğıdının bulanık kopyası (gerçek arka plan bulanıklığı için); yoksa null. */
    val backdrop: Blurred? = null,
    /** Duvar kâğıdının çizildiği kök alanın boyutu (px). */
    val rootW: Float = 0f,
    val rootH: Float = 0f,
)

val LocalGlass = staticCompositionLocalOf { GlassLook() }

/**
 * Ana ekran sayfası: içindeki camlar (klasör simgeleri, widget'lar) arkalarındaki duvar kâğıdını sayfanın DURDUĞU
 * andaki konumuna göre örnekler. Böylece sayfa kayarken camlar yeniden çizilmez ve sayfanın hazır çizilmiş hali
 * (bkz. [PageBox]) her karede yeniden kullanılabilir. Kayma sırasındaki fark bulanık arka planda fark edilmez.
 */
class PageAnchor {
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    /** Sayfa dururken sol üst köşesinin kökteki konumu. */
    var rest: Offset = Offset.Zero
    /** true iken (sayfalar kayarken) sayfadaki camların arkası sayfanın İÇİNDE değil, altındaki ayrı ince katmanda
     *  her karede gerçek konumuna göre çizilir ([drawPageGlassHoles]); sayfanın hazır görüntüsü hiç bozulmaz. */
    var holesActive: () -> Boolean = { false }
    /** Sayfanın kendisi (katman dönüşümüyle) kayıyorsa o anki kayması; çizimde okunur (widget sayfası). */
    var shift: (() -> Offset)? = null
    val holes = androidx.compose.runtime.mutableStateMapOf<Any, GlassHole>()
}

/** Sayfa içindeki bir camın arkası: sayfaya göre alanı, şekli ve çizim ayarları. */
class GlassHole(
    val rect: androidx.compose.ui.geometry.Rect,
    val path: androidx.compose.ui.graphics.Path,
    val bd: Blurred,
    val lens: android.graphics.RuntimeShader,
    val refr: Float,
    val soft: Float,
    val clear: Boolean,
    val rootW: Float,
    val rootH: Float,
    /** Görünür kısım (liste kenarında kırpılan kart taşmasın). */
    val clip: androidx.compose.ui.geometry.Rect = rect,
    /** true: kayarken mercek çizilmez (çok sayıda büyük kart: arşiv). */
    val plain: Boolean = false,
    val ios: Boolean = false,
    val lensRadius: Float = -1f,
)

/** Bir sayfanın cam arkalarını sayfa [dx] kadar kaymışken çizer. [rootOffset]: çizim alanının kökteki sol üst köşesi. */
fun DrawScope.drawPageGlassHoles(anchor: PageAnchor, dx: Float, rootOffset: Offset) {
    for (h in anchor.holes.values) {
        val left = h.rect.left + dx
        val top = h.rect.top
        if (left > size.width || left + h.rect.width < 0f) continue
        if (h.clip.width <= 0f || h.clip.height <= 0f) continue
        clipRect(h.clip.left + dx, h.clip.top, h.clip.right + dx, h.clip.bottom) {
            translate(left, top) {
                clipPath(h.path) {
                    if (h.plain) drawBackdrop(h.bd, h.rootW, h.rootH, rootOffset + Offset(left, top), soft = h.soft,
                        area = Size(h.rect.width, h.rect.height))
                    else drawBackdrop(
                        h.bd, h.rootW, h.rootH, rootOffset + Offset(left, top), h.lens, h.refr, h.soft, h.clear || h.ios,
                        area = Size(h.rect.width, h.rect.height), ios = h.ios, lensRadius = h.lensRadius,
                    )
                }
            }
        }
    }
}

val LocalPageAnchor = staticCompositionLocalOf<PageAnchor?> { null }

/**
 * Bir ana ekran sayfasının kabı: içerik ayrı bir katmanda bir kez çizilir; sayfa kayarken yalnızca bu hazır
 * görüntü taşınır (simgeler, yazı gölgeleri ve camlar her karede yeniden çizilmez → 120 Hz'de akıcı kaydırma).
 */
@Composable
fun PageBox(anchor: PageAnchor, rest: Offset, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    anchor.rest = rest
    androidx.compose.runtime.CompositionLocalProvider(LocalPageAnchor provides anchor) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .onPlaced { anchor.coords = it }
                // Sayfa ayrı katmanda bir kez çizilir; kayarken yalnızca bu hazır görüntü taşınır. (Telefonun kısmi
                // yeniden çiziminde sayfanın bir kısmı donmasın diye kaydırma sırasında tüm ekran yenilenir: HomeContent.)
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
            content = content,
        )
    }
}

/** Cam efektinin o anki durumu (Ayarlar > Liquid Glass'ta gösterilir). */
object GlassStatus {
    @Volatile var text: String = "Bilinmiyor (ana ekran henüz açılmadı)."
}

/**
 * Launcher içeriğinin (duvar kâğıdı kopyası + simgeler) kaydedilmiş çizim katmanı. Üst katmandaki menü ve
 * düğmeler bunu bulanıklaştırarak yalnızca KENDİ arkalarını gerçekten bulanık gösterir (tüm ekran değil).
 */
val LocalContentBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

/**
 * Sistem duvar kâğıdını bulanıklaştırma (0 = net, 1 = tam bulanık). Activity, Android 12+ pencere arkası
 * bulanıklığıyla uygular; cihaz desteklemiyorsa etkisizdir.
 */
val LocalWindowBlur = staticCompositionLocalOf<((Float) -> Unit)?> { null }

/** Geçerli ayarlar; simge ve etiket çizen bileşenler buradan okur. */
val LocalLauncherSettings = staticCompositionLocalOf { LauncherSettings() }

/**
 * Sayfalar ya da arşiv listesi kayarken true. Katman camları (arşiv kartları vb.) o sırada kenar merceğini çizmez,
 * yalnızca bulanık arka planı gösterir: onlarca büyük kartta mercek her karede ekran kartını bütçenin üstüne
 * çıkarıyordu (arşive kaydırırken %15 takılma). Kaydırma bitince mercek geri gelir. Çizimde okunur.
 */
object GlassMotion {
    var scrolling by androidx.compose.runtime.mutableStateOf(false)
    /** Katman camları şu an hareket halinde mi (kopya çizilmez, konum izlenmez). */
    val moving: Boolean get() = scrolling
}

/** Arşiv/arama/widget sayfası açıkken arkadaki bulanık duvar kâğıdı (bu katmanlardaki camlar onu büker). */
val LocalOverlayBackdrop = androidx.compose.runtime.compositionLocalOf<Blurred?> { null }

/**
 * Liquid Glass benzeri yüzey: buzlu dolgu, üst-sol speküler parlama, alt iç gölge ve kırılmayı andıran
 * iki tonlu kenar ışığı. Koyu temada cam koyu tonlanır. Gerçek arka plan bulanıklığı, launcher'ın kendi
 * duvar kâğıdı seçiliyse [GlassBackdrop] ile ayrıca çizilir (Android, sistem duvar kâğıdının arkasını
 * bulanıklaştırmaya izin vermez).
 */
fun Modifier.liquidGlass(
    shape: Shape,
    strength: Float = 1f,
    elevation: Dp = 14.dp,
    /** true: üst katman paneli — arkasındaki launcher içeriğini gerçek zamanlı bulanıklaştırır. */
    panel: Boolean = false,
    /** true: "Şeffaf" cam stilinde bile arkada duvar kâğıdının BULANIK kopyası (okunabilirlik gereken yüzeyler). */
    frosted: Boolean = false,
    /**
     * true: bulanık arka planlı katmanlardaki (arşiv, arama, widget sayfası) cam ana ekrandaki klasör simgesiyle
     * AYNI görünür: ince dolgu, kenarda belirgin mercek. Arkasında net duvar kâğıdı yerine o katmanın bulanık arka
     * planı bükülür (cam, çevresindeki görüntünün devamı gibi durur).
     */
    overlay: Boolean = false,
    /** iOS 26 kalın kenar merceği (dışarıdan bükme, kavisli eğim, yönlü parlama). Bütün camlarda açık. */
    ios: Boolean = true,
): Modifier = composed {
    val look1 = LocalGlass.current
    val ov = LocalOverlayBackdrop.current
    val look0 = if (overlay && !look1.reduced) {
        val b = ov ?: look1.backdrop
        look1.copy(clear = true, sharp = b, backdrop = b)
    } else look1
    // Düşük güçte her cam buzlu kabul edilir (arkası hazır bulanık kopya); şeffaf cam net kopya ister.
    val look = if ((frosted && !overlay || look0.reduced) && look0.clear) look0.copy(clear = false) else look0
    val st = LocalLauncherSettings.current
    val rimW = st.glassRimWidth
    val op = st.glassOpacity * glassTintFactor(st.glassTint)
    val glow = st.glassRimGlow
    val s = strength
    val lens = remember { GlassLens.newShader() }
    // Panel: arkasındaki bulanıklığı ayrı bir [BlurBackdrop] kutusu çizer; burada yalnızca dolgu incelir.
    val content = if (panel && !look.reduced) LocalGlassRegions.current else null
    val clear = look.clear
    val bd = when {
        content != null -> null
        // Düşük güç: gerçek zamanlı bölge bulanıklığı yerine hazır bulanık duvar kâğıdı (ucuz, her yüzeyde aynı).
        look.reduced -> look.backdrop
        clear -> look.sharp ?: look.backdrop
        else -> look.backdrop
    }
    // Gerçek arka plan varken dolgu daha ince; şeffaf camda neredeyse yok (arkası görünsün).
    val fill = when {
        bd != null || content != null -> if (clear) 0.2f else 0.55f
        else -> if (clear) 0.6f else 1f
    }
    // Hafif yumuşatma yalnızca net kopyada; katman camlarının kopyası zaten bulanık (9 örneklik yumuşatma boşuna
    // ekran kartı yüküydü).
    val softPx = if (!overlay && clear && bd != null && bd === look.sharp) with(LocalDensity.current) { 2.5.dp.toPx() } else 0f
    val refr = when {
        look.noLens || look.reduced -> 0f
        clear -> (st.glassRefraction * 1.3f).coerceAtMost(1.2f)
        else -> st.glassRefraction
    }
    val base = if (look.dark) {
        Brush.verticalGradient(listOf(black((0.30f * s + 0.08f) * fill * op), black((0.42f * s + 0.08f) * fill * op)))
    } else {
        Brush.verticalGradient(listOf(white(0.30f * s * fill * op), white(0.12f * s * fill * op)))
    }
    // iOS 26 kalın kenar merceği yalnızca kırılma açıkken (düşük güç / kırılma 0 ise kapalı).
    val iosLens = ios && refr > 0.01f
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Katman camı: hareket sırasında konum her karede izlenmez (her kartın her karede yeniden çizilmesine yol
    // açıyordu); hareket bitince son konum bir kez alınır.
    val lastCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    if (overlay) {
        LaunchedEffect(Unit) {
            snapshotFlow { GlassMotion.moving }.collect { m ->
                val c = lastCoords[0]
                if (!m && c != null && c.isAttached) origin = c.positionInRoot()
            }
        }
    }
    val pageAnchor = LocalPageAnchor.current
    var inPage by remember { mutableStateOf(false) }
    val holeKey = remember { Any() }
    val holeDensity = LocalDensity.current
    val holeDir = androidx.compose.ui.platform.LocalLayoutDirection.current
    if (pageAnchor != null) {
        DisposableEffect(pageAnchor, holeKey) { onDispose { pageAnchor.holes.remove(holeKey) } }
    }
    val rim = if (look.dark) {
        Brush.linearGradient(listOf(white(0.45f * s * glow), white(0.05f * s * glow), white(0.25f * s * glow)))
    } else {
        Brush.linearGradient(listOf(white(0.80f * s * glow), white(0.10f * s * glow), white(0.50f * s * glow)))
    }
    this
        .then(
            if (look.reduced || elevation == 0.dp) Modifier
            else Modifier.shadow(elevation, shape, clip = false, ambientColor = black(0.2f), spotColor = black(0.2f))
        )
        .then(
            if (bd != null) Modifier.onGloballyPositioned { c ->
                // Sayfa içindeyse konum sayfaya göre (kayarken değişmez → yeniden çizim yok); değilse köke göre.
                val pc = pageAnchor?.coords
                lastCoords[0] = c
                val inPageNow = pc != null && pc.isAttached
                // Sayfa içindeki cam (arşiv kartları) konumunu hep izler: kayarken onu alt katman çizer, devir teslim
                // aynı karede olur. Yalnızca sayfa dışındaki katman camları hareket sırasında konum izlemez.
                if (inPageNow || !(overlay && GlassMotion.moving)) {
                    origin = if (inPageNow) pageAnchor!!.rest + pc!!.localPositionOf(c, Offset.Zero) else c.positionInRoot()
                }
                inPage = pc != null && pc.isAttached
                if (inPage && pageAnchor != null && pc != null) {
                    // Sayfaya göre alan: kayarken değişmez, yalnızca yerleşim değişince güncellenir.
                    val r = androidx.compose.ui.geometry.Rect(pc.localPositionOf(c, Offset.Zero),
                        Size(c.size.width.toFloat(), c.size.height.toFloat()))
                    val clipR = runCatching { pc.localBoundingBoxOf(c, clipBounds = true) }.getOrDefault(r)
                    val old = pageAnchor.holes[holeKey]
                    if (old == null || old.rect != r || old.clip != clipR || old.bd !== bd || old.refr != refr || old.clear != clear ||
                        old.soft != softPx || old.rootW != look.rootW || old.rootH != look.rootH
                    ) {
                        val outline = shape.createOutline(r.size, holeDir, holeDensity)
                        val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
                        pageAnchor.holes[holeKey] = GlassHole(
                            // Kayarken de tam cam (mercek + parlama): önceden sade çiziliyor, sayfa yerine oturunca
                            // mercek birden gelip kartlar aydınlanıyordu.
                            r, path, bd, lens, refr, softPx, clear, look.rootW, look.rootH, clipR, plain = false,
                            ios = ios && refr > 0.01f, lensRadius = (shape as? SquircleShape)?.let { with(holeDensity) { it.radius.toPx() } } ?: outlineRadius(outline),
                        )
                    }
                }
            }
            else Modifier
        )
        .smoothClip(shape)
        .then(
            if (bd != null) Modifier.drawBehind {
                // Sayfalar kayarken bu camın arkasını sayfanın altındaki ayrı katman çizer (bkz. drawPageGlassHoles).
                if (!(inPage && pageAnchor != null && pageAnchor.holesActive())) {
                    // Katman camı hareket halindeyken kopya çizilmez: cam şeffaf kalır, arkadaki gerçek arka plan
                    // görünür (konumu her karede değişen kopya hem pahalıydı hem de yanlış yerden görünüyordu).
                    val sh = if (inPage) pageAnchor?.shift?.invoke() else null
                    if (sh != null) {
                        // Kendisi kayan sayfa (widget sayfası): kopya her karede doğru yerden (az sayıda cam, ucuz).
                        drawBackdrop(bd, look.rootW, look.rootH, origin + sh, lens, refr, softPx, clear || iosLens,
                            ios = iosLens, lensRadius = if (iosLens) shapeRadiusPx(shape, size, layoutDirection, this) else -1f)
                    } else if (inPage || !(overlay && GlassMotion.moving)) {
                        // Sayfa içinde yalnızca alt katmanın da baktığı kaydırma bilgisi geçerli: bir kare geç güncellenen
                        // hareket işaretine bakınca kayma bitiminde kart bir kare boş kalıp göz kırpıyordu.
                        drawBackdrop(bd, look.rootW, look.rootH, origin, lens, refr, softPx, clear || iosLens,
                            ios = iosLens, lensRadius = if (iosLens) shapeRadiusPx(shape, size, layoutDirection, this) else -1f)
                    }
                }
            }
            else Modifier
        )

        .background(base)
        .glassBrightness(st.glassBrightness)
        .then(
            if (look.reduced) Modifier
            // Parlama camın kendisine aittir: içeriğin (simgeler, yazılar) ALTINDA çizilir, yoksa simgeler soluk görünür.
            else Modifier.drawBehind {
                // Speküler parlama (iOS 27: daha parlak).
                drawRect(
                    Brush.radialGradient(
                        listOf(white((if (look.dark) 0.18f else 0.32f) * s * glow), Color.Transparent),
                        center = Offset(size.width * 0.2f, 0f),
                        radius = size.width * 0.7f,
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, black(0.10f * s)),
                        startY = size.height * 0.55f,
                        endY = size.height,
                    )
                )
            }
        )
        .then(if (look.reduced) Modifier else Modifier.darkEdge(shape, st.glassEdgeDark, (rimW + 1.6f).dp))
        .then(if (rimW > 0f) Modifier.border(rimW.dp, rim, shape) else Modifier)
}

/**
 * Bulanık duvar kâğıdının bu yüzeyin arkasına düşen bölümünü çizer. Kaynak bölge %4 küçültülür: içerik
 * hafifçe büyümüş görünür ve kenarlarda kayar — camdaki ışık kırılmasına benzer bir "mercek" hissi verir.
 */
/**
 * Düşük güç modunda kendi gerçek zamanlı bulanıklığı olmayan yüzeylerin (açılır menüler, "+" menüsü) arkasına
 * hazır bulanık duvar kâğıdı kopyası çizer: arkadaki simgeler yazıların arasından görünmez, okunur kalır.
 * Tam kalitede hiçbir şey yapmaz (orada gerçek bulanıklık var). Şekle kırpılmış bir zincirde kullanılmalı.
 */
fun Modifier.reducedBackdrop(): Modifier = composed {
    val look = LocalGlass.current
    val bd = look.backdrop
    if (!look.reduced || bd == null) return@composed this
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind { drawBackdrop(bd, look.rootW, look.rootH, origin) }
}

private fun DrawScope.drawBackdrop(
    bd: Blurred, rootW: Float, rootH: Float, origin: Offset,
    lens: android.graphics.RuntimeShader? = null, refraction: Float = 0f, soft: Float = 0f, forceLens: Boolean = false,
    /** Çizilecek alanın boyutu (varsayılan: çizim alanının tamamı). */
    area: Size = size,
    /** iOS 26 kalın kenar merceği. */
    ios: Boolean = false,
    /** Camın gerçek köşe yarıçapı (px); < 0: kısa kenarın %30'u. */
    lensRadius: Float = -1f,
) {
    if (rootW <= 0f || rootH <= 0f || bd.srcW <= 0 || bd.srcH <= 0) return
    val img = bd.image
    if (lens != null && (refraction > 0.01f || forceLens)) {
        // Kırılmalı yol: bulanık kopya, kök koordinatlarına eşlenmiş bir doku olarak merceğe verilir.
        val scaleR = maxOf(rootW / bd.srcW, rootH / bd.srcH)
        val kR = img.width.toFloat() / bd.srcW
        val bs = bd.shader
        val m = android.graphics.Matrix()
        m.setScale(scaleR / kR, scaleR / kR)
        m.postTranslate((rootW - bd.srcW * scaleR) / 2f, (rootH - bd.srcH * scaleR) / 2f)
        bs.setLocalMatrix(m)
        lens.setInputShader("content", bs)
        val rad = if (lensRadius >= 0f) lensRadius else minOf(area.width, area.height) * 0.3f
        GlassLens.configure(lens, area.width, area.height, rad, refraction, origin.x, origin.y, soft, ios)
        drawRect(androidx.compose.ui.graphics.ShaderBrush(lens), size = area)
        return
    }
    // Duvar kâğıdı kökte ContentScale.Crop ile çizilir: aynı eşlemeyi bulanık kopyaya uygula.
    val scale = maxOf(rootW / bd.srcW, rootH / bd.srcH)
    val offX = (rootW - bd.srcW * scale) / 2f
    val offY = (rootH - bd.srcH * scale) / 2f
    val k = img.width.toFloat() / bd.srcW // orijinal → küçük kopya
    val lens = 1.04f
    val cx = origin.x + area.width / 2f
    val cy = origin.y + area.height / 2f
    val halfW = area.width / 2f / lens
    val halfH = area.height / 2f / lens
    val sl = ((cx - halfW - offX) / scale * k)
    val st = ((cy - halfH - offY) / scale * k)
    val sw = (halfW * 2f / scale * k)
    val sh = (halfH * 2f / scale * k)
    val il = sl.toInt().coerceIn(0, img.width - 1)
    val itop = st.toInt().coerceIn(0, img.height - 1)
    val iw = sw.toInt().coerceIn(1, img.width - il)
    val ih = sh.toInt().coerceIn(1, img.height - itop)
    drawImage(
        img,
        srcOffset = IntOffset(il, itop),
        srcSize = IntSize(iw, ih),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(area.width.toInt(), area.height.toInt()),
        filterQuality = FilterQuality.Low,
    )
}

/** Uygulama simgeleri için ince cam kenarı + üst parlama. */
fun Modifier.glassIcon(shape: Shape, enabled: Boolean, strength: Float, rimGlow: Float = 0f, rimWidth: Dp = 1.2.dp): Modifier =
    if (!enabled) this.clip(shape).iosRim(shape, rimGlow, rimWidth)
    else this.clip(shape)
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    listOf(white(0.22f * strength), Color.Transparent),
                    endY = size.height * 0.5f,
                )
            )
        }
        .iosRim(shape, rimGlow, rimWidth)
        .border(
            rimWidth / 1.2f,
            Brush.linearGradient(listOf(white(0.65f * strength), white(0.04f * strength), white(0.35f * strength))),
            shape,
        )

@Composable
fun SearchGlyph(color: Color, glyphSize: Dp) {
    Canvas(Modifier.size(glyphSize)) {
        val s = this.size.minDimension
        val stroke = s * 0.13f
        val r = s * 0.34f
        val c = Offset(s * 0.42f, s * 0.42f)
        drawCircle(color, radius = r, center = c, style = Stroke(stroke))
        drawLine(
            color,
            Offset(c.x + r * 0.72f, c.y + r * 0.72f),
            Offset(s * 0.92f, s * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Yalnızca kendi arkası bulanık olması gereken küçük cam yüzeylerin (menü, düğme) ekrandaki yuvarlak
 * köşeli alanları. Launcher içeriği, kendi çiziminde bulanık bir kopyasını yalnızca bu alanlara çizer.
 */
class GlassRegions {
    val regions = mutableStateMapOf<Any, RoundRect>()
    /** Yalnızca en alttaki ana ekran içeriğine uygulanan alanlar (ör. açık klasör paneli: kendi katmanı bulanmasın). */
    val rootRegions = mutableStateMapOf<Any, RoundRect>()
    /** Alanın gerçek şekli (ör. iOS sürekli köşe); yoksa düz yuvarlak köşe kullanılır. */
    val shapes = HashMap<Any, Shape>()
    /** Daha yoğun bulanık alanlar (arama paneli/çubuğu): arkadaki simge lekeleri kenarsız bir ışığa dönüşür. */
    val strong = HashSet<Any>()
    /** Alanın görünürlüğü (0..1): menü açılırken/kapanırken bulanıklık da menüyle birlikte belirir ve söner. */
    val alphas = mutableStateMapOf<Any, Float>()
}

val LocalGlassRegions = staticCompositionLocalOf<GlassRegions?> { null }

/**
 * Bir menünün/düğmenin YALNIZCA kendi arkasını gerçekten bulanıklaştırır (ekranın geri kalanı net kalır).
 * Panelin arkasına aynı boyutta yerleştirilir; kendi alanını [GlassRegions]'a kaydeder.
 */
@Composable
fun BlurBackdrop(
    corner: Dp, modifier: Modifier = Modifier, rootOnly: Boolean = false, shape: Shape? = null,
    /** Panelin o anki görünürlüğü (açılış/kapanış animasyonu); bulanıklık buna göre belirir/söner. */
    alpha: (() -> Float)? = null,
    /** true: bu alanın arkası yaklaşık iki kat daha bulanık. */
    strongBlur: Boolean = false,
) {
    val reg = LocalGlassRegions.current ?: return
    val map = if (rootOnly) reg.rootRegions else reg.regions
    if (LocalGlass.current.reduced) return
    val key = remember { Any() }
    val cr = with(LocalDensity.current) { corner.toPx() }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun update() {
        val c = coords[0] ?: return
        if (!c.isAttached) return
        val b = c.boundsInRoot()
        // Açılış animasyonundaki ölçek de hesaba katılır; köşe yarıçapı ölçekle küçülür.
        val k = if (c.size.width > 0) b.width / c.size.width else 1f
        val rr = RoundRect(b, CornerRadius((cr * k).coerceAtMost(minOf(b.width, b.height) / 2f)))
        if (map[key] != rr) map[key] = rr
    }
    if (shape != null) reg.shapes[key] = shape else reg.shapes.remove(key)
    if (strongBlur) reg.strong += key else reg.strong -= key
    DisposableEffect(reg, key) { onDispose { map.remove(key); reg.shapes.remove(key); reg.alphas.remove(key); reg.strong.remove(key) } }
    if (alpha != null) {
        val a by rememberUpdatedState(alpha)
        // Her karede: görünürlük değiştikçe (kapanış dahil) bulanıklık ve alanı birlikte güncellenir.
        LaunchedEffect(key) {
            snapshotFlow { a().coerceIn(0f, 1f) }.collect { v ->
                reg.alphas[key] = v
                update()
            }
        }
    }
    // Yaylı açılış animasyonu (ölçek katmanda olduğu için yerleşim bildirimi gelmez) boyunca alan
    // HER KAREDE yeniden ölçülür; bulanıklık menüyle birlikte büyür. Sonra seyrek kontrol yeterli.
    LaunchedEffect(key) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            update()
            if (now - start > 1_400_000_000L) break
        }
        while (true) {
            delay(300)
            update()
        }
    }
    Box(
        modifier.onGloballyPositioned { c ->
            coords[0] = c
            update()
        }
    )
}

/**
 * İçerik katmanına uygulanır: içeriği net çizer, ardından [GlassRegions]'daki alanlara içeriğin bulanık
 * kopyasını kırparak çizer. (İçerik, kendi çiziminde iki kez çizilir; bu Android'in desteklediği yoldur.)
 */
fun Modifier.glassRegionBlur(
    reg: GlassRegions,
    radius: Dp = 30.dp,
    includeRoot: Boolean = false,
    /** Yoğun bulanık alanların (arama paneli/çubuğu) yarıçapı: cam stilinden bağımsız, arkadaki simge
     *  sıraları kenarı belli olmayan bir ışığa dönüşsün diye büyük. */
    strongRadius: Dp = 56.dp,
): Modifier = composed {
    // Doğrudan platform RenderNode'u + RenderEffect bulanıklığı (Modifier.blur ile aynı yerel yol).
    val node = remember { android.graphics.RenderNode("glassRegionContent") }
    val blurNode = remember { android.graphics.RenderNode("glassRegionBlur") }
    val r = with(LocalDensity.current) { radius.toPx() }.coerceAtLeast(0.5f)
    val reduced = LocalGlass.current.reduced
    val refr = if (LocalGlass.current.noLens) 0f else LocalLauncherSettings.current.glassRefraction
    // Her cam alanı için ayrı mercek düğümü ve gölgelendirici (uniform'lar alana özeldir).
    val lensNodes = remember { ArrayList<android.graphics.RenderNode>() }
    val lensShaders = remember { ArrayList<android.graphics.RuntimeShader>() }
    // Yarı görünür alanda net içerik kısmen silinir (bulanık kopyayla karışır).
    val fadePaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        }
    }
    val effect = remember(r) {
        android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)
    }
    val strongNode = remember { android.graphics.RenderNode("glassRegionBlurStrong") }
    val sr = with(LocalDensity.current) { strongRadius.toPx() }.coerceAtLeast(r * 2.2f)
    val strongEffect = remember(sr) {
        android.graphics.RenderEffect.createBlurEffect(sr, sr, android.graphics.Shader.TileMode.CLAMP)
    }
    this.drawWithContent {
        drawContent()
        val list = if (includeRoot) reg.regions.entries.toList() + reg.rootRegions.entries else reg.regions.entries.toList()
        if (reduced || list.isEmpty()) return@drawWithContent
        val w = size.width.toInt(); val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@drawWithContent
        node.setPosition(0, 0, w, h)
        val rc = node.beginRecording(w, h)
        val prev = drawContext.canvas
        try {
            drawContext.canvas = androidx.compose.ui.graphics.Canvas(rc)
            drawContent()
        } finally {
            drawContext.canvas = prev
            node.endRecording()
        }
        // İki katman: bulanıklık, içerik düğümünü saran ayrı düğümde (Haze kütüphanesindeki gibi).
        blurNode.setPosition(0, 0, w, h)
        blurNode.setRenderEffect(effect)
        blurNode.beginRecording(w, h).also { it.drawRenderNode(node) }
        blurNode.endRecording()
        val anyStrong = list.any { it.key in reg.strong }
        if (anyStrong) {
            strongNode.setPosition(0, 0, w, h)
            strongNode.setRenderEffect(strongEffect)
            strongNode.beginRecording(w, h).also { it.drawRenderNode(node) }
            strongNode.endRecording()
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            list.forEachIndexed { i, entry ->
                val rr = entry.value
                // Alanın gerçek şekli (menü/klasör: iOS sürekli köşe). Kırpma yerine kenar yumuşatmalı silme
                // kullanılır: Android'de yol ile kırpma köşeleri basamak basamak yapar.
                val shp = reg.shapes[entry.key] ?: RoundedCornerShape(rr.topLeftCornerRadius.x)
                val path = shp.androidPath(rr.left, rr.top, rr.width, rr.height, layoutDirection, this)
                // Alanın net içeriği silinir (sistem duvar kâğıdı görünür), yerine bulanık kopyası çizilir.
                // İçerik saydam olduğu için silmeden çizilirse net simgeler bulanık kopyanın altından görünür.
                val vis = reg.alphas[entry.key] ?: 1f
                if (vis <= 0.01f) return@forEachIndexed
                if (vis >= 0.99f) nc.drawPath(path, AaClearPaint)
                else nc.drawPath(path, fadePaint.apply { alpha = (vis * 255).toInt() })
                nc.saveLayerAlpha(rr.left, rr.top, rr.right, rr.bottom, (vis * 255).toInt())
                val rw = rr.width.toInt(); val rh = rr.height.toInt()
                if (refr > 0.01f && rw > 4 && rh > 4) {
                    // Kırılma: alanın bulanık kopyası kendi düğümüne alınır, kenarında mercek gibi bükülür.
                    while (lensNodes.size <= i) {
                        lensNodes += android.graphics.RenderNode("glassLens")
                        lensShaders += GlassLens.newShader()
                    }
                    val ln = lensNodes[i]; val sh = lensShaders[i]
                    val l = rr.left.toInt(); val t = rr.top.toInt()
                    // Kayıt kenar bandı kadar geniş: iOS kalın kenarı görüntüyü kenarda dışarıdan bükerek alır.
                    val pad = GlassLens.padPx()
                    ln.setPosition(l - pad, t - pad, l + rw + pad, t + rh + pad)
                    GlassLens.configure(
                        sh, rw.toFloat(), rh.toFloat(), rr.topLeftCornerRadius.x, refr,
                        ios = true, px = pad.toFloat(), py = pad.toFloat(),
                    )
                    ln.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content"))
                    val lc = ln.beginRecording(rw + pad * 2, rh + pad * 2)
                    lc.translate(-(l - pad).toFloat(), -(t - pad).toFloat())
                    lc.drawRenderNode(if (entry.key in reg.strong) strongNode else blurNode)
                    ln.endRecording()
                    nc.drawRenderNode(ln)
                } else {
                    nc.drawRenderNode(if (entry.key in reg.strong) strongNode else blurNode)
                }
                // Şeklin dışında kalan bulanık kopya yumuşak kenarla silinir.
                path.fillType = android.graphics.Path.FillType.INVERSE_WINDING
                nc.drawPath(path, AaClearPaint)
                nc.restore()
            }
        }
    }
}

/** Parlaklık ayarı: pozitifse beyaz, negatifse siyah ince bir katman ekler. */
fun Modifier.glassBrightness(b: Float): Modifier = when {
    b > 0.01f -> this.background(white(0.35f * b))
    b < -0.01f -> this.background(black(0.35f * -b))
    else -> this
}

/**
 * Açık tonlu buzlu cam panel (klasörler ve arşiv kutuları). Saydamlık, parlaklık, kenar ışığı ve
 * çizgi kalınlığı ayarlarını uygular.
 */
fun Modifier.lightGlass(shape: Shape, rimTop: Float, rimBottom: Float, fill: Float = 1f): Modifier = composed {
    val st = LocalLauncherSettings.current
    val op = st.glassOpacity * glassTintFactor(st.glassTint) * (if (LocalGlass.current.clear) 0.5f else 1f) * fill
    val glow = st.glassRimGlow
    this
        .smoothClip(shape)
        .reducedBackdrop()
        .background(Brush.verticalGradient(listOf(white(0.24f * op), white(0.15f * op))))
        .glassBrightness(st.glassBrightness)
        .darkEdge(shape, st.glassEdgeDark, (st.glassRimWidth + 1.6f).dp)
        .then(
            if (st.glassRimWidth > 0f && glow > 0f) Modifier.border(
                st.glassRimWidth.dp,
                Brush.verticalGradient(listOf(white(rimTop * glow), white(rimBottom * glow))),
                shape,
            ) else Modifier
        )
}

/**
 * iOS 26 simge kenar ışığı: üst ve sol kenarda ince, parlak bir şerit; alt-sağ kenarda çok hafif.
 * Simgeye cam gibi hafif kabarıklık verir. [glow] 0 ise hiçbir şey çizilmez (eski görünüm).
 */
private fun Modifier.iosRim(shape: Shape, glow: Float, width: Dp): Modifier =
    if (glow <= 0.01f || width < 0.05.dp) this
    else this.border(
        width,
        Brush.linearGradient(
            0f to white(0.55f * glow),
            0.35f to white(0.12f * glow),
            0.7f to Color.Transparent,
            1f to white(0.10f * glow),
        ),
        shape,
    )


/** Şeklin köşe yarıçapı (px): yuvarlak dikdörtgen ise sol üst köşe, değilse kısa kenarın %30'u. */
internal fun outlineRadius(o: androidx.compose.ui.graphics.Outline): Float = when (o) {
    is androidx.compose.ui.graphics.Outline.Rounded -> o.roundRect.topLeftCornerRadius.x
    is androidx.compose.ui.graphics.Outline.Rectangle -> 0f
    is androidx.compose.ui.graphics.Outline.Generic -> {
        val b = o.path.getBounds(); minOf(b.width, b.height) * 0.3f
    }
}

/** Köşe yarıçapı (px): yumuşak köşeli şekilde kendi yarıçapı, değilse çizgi şeklinden. */
internal fun shapeRadiusPx(
    shape: Shape, size: Size, dir: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density,
): Float = (shape as? SquircleShape)?.let { with(density) { it.radius.toPx() } }
    ?.coerceAtMost(minOf(size.width, size.height) / 2f)
    ?: outlineRadius(shape.createOutline(size, dir, density))
