package dev.glass.launcher.ui

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
private fun white(a: Float) = Color.White.copy(alpha = al(a))
private fun black(a: Float) = Color.Black.copy(alpha = al(a))

/** Cam yüzeylerin ortak görünüm bilgisi (tema, kalite). Kök bileşende sağlanır. */
@Immutable
data class GlassLook(
    val dark: Boolean = false,
    /** Düşük güç / otomatik kalite düşürme: gölge, parlama ve bulanıklık katmanları kapatılır. */
    val reduced: Boolean = false,
    /** Launcher duvar kâğıdının bulanık kopyası (gerçek arka plan bulanıklığı için); yoksa null. */
    val backdrop: Blurred? = null,
    /** Duvar kâğıdının çizildiği kök alanın boyutu (px). */
    val rootW: Float = 0f,
    val rootH: Float = 0f,
)

val LocalGlass = staticCompositionLocalOf { GlassLook() }

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
): Modifier = composed {
    val look = LocalGlass.current
    val s = strength
    // Panel: arkasındaki bulanıklığı ayrı bir [BlurBackdrop] kutusu çizer; burada yalnızca dolgu incelir.
    val content = if (panel && !look.reduced) LocalGlassRegions.current else null
    val bd = if (look.reduced || content != null) null else look.backdrop
    // Gerçek bulanıklık varken dolgu daha ince tutulur; cam arkasındaki renkleri göstersin.
    val fill = if (bd != null || content != null) 0.55f else 1f
    val base = if (look.dark) {
        Brush.verticalGradient(listOf(black((0.30f * s + 0.08f) * fill), black((0.42f * s + 0.08f) * fill)))
    } else {
        Brush.verticalGradient(listOf(white(0.30f * s * fill), white(0.12f * s * fill)))
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val rim = if (look.dark) {
        Brush.linearGradient(listOf(white(0.45f * s), white(0.05f * s), white(0.25f * s)))
    } else {
        Brush.linearGradient(listOf(white(0.80f * s), white(0.10f * s), white(0.50f * s)))
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
        .clip(shape)
        .then(
            if (bd != null) Modifier.drawBehind { drawBackdrop(bd, look.rootW, look.rootH, origin) }
            else Modifier
        )

        .background(base)
        .then(
            if (look.reduced) Modifier
            else Modifier.drawWithContent {
                drawContent()
                drawRect(
                    Brush.radialGradient(
                        listOf(white((if (look.dark) 0.12f else 0.24f) * s), Color.Transparent),
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
        .border(1.dp, rim, shape)
}

/**
 * Bulanık duvar kâğıdının bu yüzeyin arkasına düşen bölümünü çizer. Kaynak bölge %4 küçültülür: içerik
 * hafifçe büyümüş görünür ve kenarlarda kayar — camdaki ışık kırılmasına benzer bir "mercek" hissi verir.
 */
private fun DrawScope.drawBackdrop(bd: Blurred, rootW: Float, rootH: Float, origin: Offset) {
    if (rootW <= 0f || rootH <= 0f || bd.srcW <= 0 || bd.srcH <= 0) return
    val img = bd.image
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
fun Modifier.glassIcon(shape: Shape, enabled: Boolean, strength: Float): Modifier =
    if (!enabled) this.clip(shape)
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
        .border(
            1.dp,
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
}

val LocalGlassRegions = staticCompositionLocalOf<GlassRegions?> { null }

/**
 * Bir menünün/düğmenin YALNIZCA kendi arkasını gerçekten bulanıklaştırır (ekranın geri kalanı net kalır).
 * Panelin arkasına aynı boyutta yerleştirilir; kendi alanını [GlassRegions]'a kaydeder.
 */
@Composable
fun BlurBackdrop(corner: Dp, modifier: Modifier = Modifier) {
    val reg = LocalGlassRegions.current ?: return
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
        if (reg.regions[key] != rr) reg.regions[key] = rr
    }
    DisposableEffect(reg, key) { onDispose { reg.regions.remove(key) } }
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
fun Modifier.glassRegionBlur(reg: GlassRegions, radius: Dp = 30.dp): Modifier = composed {
    // Doğrudan platform RenderNode'u + RenderEffect bulanıklığı (Modifier.blur ile aynı yerel yol).
    val node = remember { android.graphics.RenderNode("glassRegionBlur") }
    val r = with(LocalDensity.current) { radius.toPx() }
    val reduced = LocalGlass.current.reduced
    val effect = remember(r) {
        android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)
    }
    val lastDiag = remember { longArrayOf(0L) }
    this.drawWithContent {
        drawContent()
        val list = reg.regions.values.toList()
        if (reduced || list.isEmpty()) return@drawWithContent
        val w = size.width.toInt(); val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@drawWithContent
        node.setPosition(0, 0, w, h)
        node.setRenderEffect(effect)
        val rc = node.beginRecording(w, h)
        val prev = drawContext.canvas
        try {
            drawContext.canvas = androidx.compose.ui.graphics.Canvas(rc)
            drawContent()
        } finally {
            drawContext.canvas = prev
            node.endRecording()
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            list.forEach { rr ->
                nc.save()
                nc.clipPath(Path().apply { addRoundRect(rr) }.asAndroidPath())
                nc.drawRenderNode(node)
                nc.drawColor(0x40FF0000)
                nc.restore()
            }
            val now = System.currentTimeMillis()
            if (now - lastDiag[0] > 400) {
                lastDiag[0] = now
                android.util.Log.i("GlassDiag", "regions n=${list.size} " + list.joinToString { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" } + " size=${w}x$h hw=${nc.isHardwareAccelerated}")
            }
        }
    }
}
