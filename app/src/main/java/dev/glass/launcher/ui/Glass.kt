package dev.glass.launcher.ui

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
): Modifier = composed {
    val look0 = LocalGlass.current
    // Düşük güçte her cam buzlu kabul edilir (arkası hazır bulanık kopya); şeffaf cam net kopya ister.
    val look = if ((frosted || look0.reduced) && look0.clear) look0.copy(clear = false) else look0
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
    val softPx = if (clear && bd != null && bd === look.sharp) with(LocalDensity.current) { 2.5.dp.toPx() } else 0f
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
    var origin by remember { mutableStateOf(Offset.Zero) }
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
            if (bd != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() }
            else Modifier
        )
        .smoothClip(shape)
        .then(
            if (bd != null) Modifier.drawBehind {
                drawBackdrop(bd, look.rootW, look.rootH, origin, lens, refr, softPx, clear)
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
        GlassLens.configure(lens, size.width, size.height, minOf(size.width, size.height) * 0.3f, refraction, origin.x, origin.y, soft)
        drawRect(androidx.compose.ui.graphics.ShaderBrush(lens))
        return
    }
    // Duvar kâğıdı kökte ContentScale.Crop ile çizilir: aynı eşlemeyi bulanık kopyaya uygula.
    val scale = maxOf(rootW / bd.srcW, rootH / bd.srcH)
    val offX = (rootW - bd.srcW * scale) / 2f
    val offY = (rootH - bd.srcH * scale) / 2f
    val k = img.width.toFloat() / bd.srcW // orijinal → küçük kopya
    val lens = 1.04f
    val cx = origin.x + size.width / 2f
    val cy = origin.y + size.height / 2f
    val halfW = size.width / 2f / lens
    val halfH = size.height / 2f / lens
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
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
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
                    ln.setPosition(l, t, l + rw, t + rh)
                    GlassLens.configure(sh, rw.toFloat(), rh.toFloat(), rr.topLeftCornerRadius.x, refr)
                    ln.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content"))
                    val lc = ln.beginRecording(rw, rh)
                    lc.translate(-l.toFloat(), -t.toFloat())
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
