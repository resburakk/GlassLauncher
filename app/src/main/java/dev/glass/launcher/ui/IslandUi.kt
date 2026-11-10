package dev.glass.launcher.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glass.launcher.service.Island
import dev.glass.launcher.service.IslandAct
import dev.glass.launcher.service.IslandOverlay
import dev.glass.launcher.service.NowPlaying
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val IGreen = Color(0xFF30D158)
private val IOrange = Color(0xFFFF9F0A)
private val IRed = Color(0xFFFF453A)
private val IBlue = Color(0xFF0A84FF)
private val IDim = Color(0x99FFFFFF)

/** Adanın o anki hâli. */
private sealed class IslandMode(val id: String) {
    object Hidden : IslandMode("hidden")
    object Idle : IslandMode("idle")
    class Compact(val a: IslandAct) : IslandMode("c:" + a.key)
    class Expanded(val a: IslandAct) : IslandMode("e:" + a.key)
}

@Composable
private fun rememberSecondTick(active: Boolean): Long {
    val t by produceState(System.currentTimeMillis(), active) {
        while (active) {
            value = System.currentTimeMillis()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    return t
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun appIcon(pkg: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(pkg) {
        if (pkg.isEmpty()) null else runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
}

/** Ada ölçüleri (dp): ayarda 0 olanlar kamera deliğine göre otomatik. */
internal data class IslandDims(val idleW: Float, val compactW: Float, val h: Float, val expandedW: Float)

internal fun islandDims(geo: dev.glass.launcher.service.IslandGeo, density: Float, s: dev.glass.launcher.service.IslandSettings): IslandDims {
    val screen = geo.screenW / density
    val autoH = max(34f, geo.cutH / density + 12f)
    val h = if (s.islandH > 0f) s.islandH else autoH
    val autoIdle = max(h * 3.2f, geo.cutW / density + 70f)
    val idle = if (s.idleW > 0f) s.idleW else autoIdle
    val compact = if (s.compactW > 0f) s.compactW else (idle + 104f).coerceAtMost(screen - 100f)
    val big = if (s.expandedW > 0f) s.expandedW else (screen - 22f).coerceAtMost(420f)
    return IslandDims(idle.coerceAtMost(screen), compact.coerceAtMost(screen), h, big.coerceAtMost(screen))
}

/**
 * Dinamik Ada (iOS 26 gibi): kamera deliğinin çevresinde siyah ada. Etkinliğe göre genişler (müzik, arama,
 * zamanlayıcı, navigasyon, indirme), dokununca büyür, basılı tutunca uygulamayı açar (ayarlanabilir), iki etkinlik
 * varsa ikincisi yanında küçük daire olur. Mesaj, şarj ve sessiz mod birkaç saniye görünür.
 */
@Composable
fun IslandRoot() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    val s by Island.settings.collectAsStateWithLifecycle()
    val ongoing by Island.ongoing.collectAsStateWithLifecycle()
    val transientRaw by Island.transient.collectAsStateWithLifecycle()
    val npReal by NowPlaying.state.collectAsStateWithLifecycle()
    val preview by Island.preview.collectAsStateWithLifecycle()
    val aboveBars by IslandOverlay.aboveBars
    // Önizlemede (Ayarlar > Dinamik Ada) örnek bir müzik etkinliği gösterilir.
    val np = if (preview >= 2) dev.glass.launcher.service.NowPlayingState(ctx.packageName, "Önizleme", "Dinamik Ada", null, true) else npReal
    var expanded by IslandOverlay.expanded
    var swapped by remember { mutableStateOf(false) }
    val geo = remember(conf.orientation, conf.screenWidthDp) { IslandOverlay.geometry(ctx) }

    // Müzik duraklatılınca birkaç saniye daha görünür (iOS gibi), sonra ada küçülür.
    var lastPlaying by remember { mutableLongStateOf(0L) }
    val playing = np?.playing == true && np?.saved == false
    LaunchedEffect(playing) { if (playing) lastPlaying = System.currentTimeMillis() }
    var lingerTick by remember { mutableStateOf(0) }
    LaunchedEffect(playing, lastPlaying) { if (!playing && lastPlaying > 0) { delay(6000); lingerTick++ } }
    val musicVisible = s.music && np != null && np?.saved == false &&
        (playing || System.currentTimeMillis() - lastPlaying < 6000).also { lingerTick.hashCode() }

    val acts = buildList {
        ongoing.forEach { a ->
            val ok = when (a) {
                is IslandAct.Call -> s.calls
                is IslandAct.Timer -> s.timers
                is IslandAct.Nav -> s.navigation
                is IslandAct.Progress -> s.progress
                else -> true
            }
            if (ok) add(a)
        }
        if (musicVisible) np?.let { add(IslandAct.Music(it)) }
    }.sortedByDescending { it.priority }
    val first = if (swapped && acts.size > 1) acts[1] else acts.getOrNull(0)
    val second = if (swapped && acts.size > 1) acts[0] else acts.getOrNull(1)
    val transient = transientRaw
    val landscapeHidden = s.hideLandscape && conf.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Gelen arama iOS'taki gibi kendiliğinden büyük gösterilir.
    val incoming = first is IslandAct.Call && first.incoming
    val mode: IslandMode = when {
        preview == 1 -> IslandMode.Idle
        preview == 2 && first != null -> IslandMode.Compact(first)
        preview == 3 && first != null -> IslandMode.Expanded(first)
        landscapeHidden -> IslandMode.Hidden
        transient is IslandAct.Message -> IslandMode.Expanded(transient)
        transient != null -> IslandMode.Compact(transient)
        first != null && (expanded || incoming) -> IslandMode.Expanded(first)
        first != null -> IslandMode.Compact(first)
        s.idleVisible -> IslandMode.Idle
        else -> IslandMode.Hidden
    }
    LaunchedEffect(first?.key) { if (first == null) expanded = false }
    // Genişlemiş ada bir süre dokunulmazsa kendiliğinden küçülür.
    LaunchedEffect(expanded, mode.id) { if (expanded) { delay(8000); expanded = false } }
    LaunchedEffect(transient?.key, transient) {
        if (transient != null && s.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    // ---- Boyutlar (dp)
    val dims = islandDims(geo, density.density, s)
    val baseH = dims.h
    val idleW = dims.idleW
    val compactW = dims.compactW
    val bigW = dims.expandedW
    // Uygulama katmanında durum çubuğu (saat, pil) adanın üstünde çizilir: genişlemiş adanın içeriği durum çubuğunun
    // altından başlar. Erişilebilirlik katmanında ada durum çubuğunun da üstündedir: gerek yok.
    val islandTopPx = (geo.cy - (baseH * density.density) / 2f + s.offsetY * density.density).coerceAtLeast(2f * density.density)
    val topInset = if (aboveBars) 0f else ((geo.statusH - islandTopPx) / density.density).coerceIn(0f, 40f)
    val (tw, th) = when (mode) {
        IslandMode.Hidden -> 0f to 0f
        IslandMode.Idle -> idleW to baseH
        is IslandMode.Compact -> compactW to baseH
        is IslandMode.Expanded -> bigW to topInset + when (mode.a) {
            is IslandAct.Music -> 168f
            is IslandAct.Call -> if (mode.a.incoming || mode.a.hangup != null) 128f else 88f
            is IslandAct.Message -> 92f
            is IslandAct.Timer -> 96f
            else -> 96f
        }
    }
    val bubble = second != null && mode is IslandMode.Compact && transient == null
    val bubbleD = baseH
    val gap = 7f
    val extra = if (bubble) gap + bubbleD else 0f

    val spec = spring<Float>(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow * s.animSpeed * s.animSpeed)
    val aw = remember { Animatable(0f) }
    val ah = remember { Animatable(0f) }
    var winW by remember { mutableStateOf(0f) }
    var winH by remember { mutableStateOf(0f) }
    val px = { dp: Float -> (dp * density.density).roundToInt() }
    fun place(wDp: Float, hDp: Float) {
        winW = wDp; winH = hDp
        if (wDp <= 0f || hDp <= 0f) { IslandOverlay.place(0, 0, 0, 0); return }
        // Ekran ortasına göre sabit kaydırma (kamera deliği ortada değilse onun konumu + kullanıcı kaydırması).
        val x = geo.cx - geo.screenW / 2 + px(s.offsetX)
        val y = (geo.cy - px(baseH) / 2 + px(s.offsetY)).coerceAtLeast(px(2f))
        IslandOverlay.place(x, y, px(wDp), px(hDp))
    }
    LaunchedEffect(tw, th, extra, geo, s.offsetY, s.offsetX, baseH) {
        val targetWinW = if (tw <= 0f) 0f else tw + extra * 2
        // Büyürken pencere önce hedef boyuta açılır; küçülürken animasyon bitince daralır (tek seferlik boyut değişimi).
        if (targetWinW > 0f) {
            val grow = targetWinW > winW + 0.5f || th > winH + 0.5f
            place(max(targetWinW, winW), max(th, winH))
            // Pencere büyürken Android yeni boyutu bir iki kare sonra uygular: animasyon hemen başlarsa ada henüz
            // dar olan pencereye sığmayıp kenarı düz kesik görünüyordu. Pencere büyüyene kadar beklenir.
            if (grow) { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { } }
        }
        val j1 = launch { aw.animateTo(tw, spec) }
        val j2 = launch { ah.animateTo(th, spec) }
        j1.join(); j2.join()
        place(targetWinW, th)
    }
    LaunchedEffect(mode is IslandMode.Expanded) {
        if (mode is IslandMode.Expanded && s.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    if (winW <= 0f || aw.value <= 1f) return
    val shapeR = (ah.value / 2f).coerceAtMost(if (mode is IslandMode.Expanded) 42f else 999f) * s.corner.coerceIn(0f, 1f)
    val glass = s.style == 1
    // iOS 27 görünümü: ada açıldıkça zemini yukarıdan aşağı siyahtan saydama geçer. Üst kısım kamera deliğiyle
    // birleşen tam siyah kalır; alt kısımda arkadaki ekran koyulaşmış olarak seçilir. Kapalı (küçük) ada tam siyah.
    // Geçiş adanın yüksekliğine bağlı: açılırken ve kapanırken kesintisiz.
    val openK = ((ah.value - baseH) / 60f).coerceIn(0f, 1f)
    val solid = if (glass) Color(0xFF1C1C1E) else Color.Black
    val topA = if (glass) 0.85f else 1f
    fun lerp(a: Float, b: Float) = a + (b - a) * openK
    val islandBg = Brush.verticalGradient(
        0f to solid.copy(alpha = topA),
        0.28f to solid.copy(alpha = lerp(topA, topA * 0.97f)),
        0.62f to solid.copy(alpha = lerp(topA, if (glass) 0.66f else 0.80f)),
        1f to solid.copy(alpha = lerp(topA, if (glass) 0.48f else 0.60f)),
    )
    // İnce cam kenarı: açık adada alt kenara doğru belirginleşen ışık çizgisi (üstte siyahla birleşir, görünmez).
    val rim = if (glass) Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x14FFFFFF)))
        else Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x0FFFFFFF), 1f to Color.White.copy(alpha = 0.30f * openK))
    val islandMod = Modifier
        .requiredSize(aw.value.dp, ah.value.dp)
        .clip(RoundedCornerShape(shapeR.dp))
        .background(islandBg)
        .then(if (glass || openK > 0.01f) Modifier.border(0.8.dp, rim, RoundedCornerShape(shapeR.dp)) else Modifier)

    Box(Modifier.fillMaxSize()) {
        Box(
            islandMod.align(Alignment.TopCenter)
                .pointerInput(mode.id, s.tapExpands) {
                    detectTapGestures(
                        onTap = {
                            when (mode) {
                                is IslandMode.Expanded -> { if (transient != null) { Island.open(ctx, mode.a); Island.dismissTransient() } else expanded = false }
                                is IslandMode.Compact -> if (transient != null) Island.dismissTransient() else if (s.tapExpands) expanded = true else Island.open(ctx, mode.a)
                                else -> Unit
                            }
                        },
                        onLongPress = {
                            val a = (mode as? IslandMode.Compact)?.a ?: (mode as? IslandMode.Expanded)?.a
                            if (a != null && transient == null) {
                                if (s.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                if (s.tapExpands) { expanded = false; Island.open(ctx, a) } else expanded = true
                            }
                        },
                    )
                }
                .pointerInput(mode.id) {
                    var dx = 0f; var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            when {
                                dy < -24f -> { if (transient != null) Island.dismissTransient() else expanded = false }
                                dy > 30f && mode is IslandMode.Compact && transient == null -> expanded = true
                                abs(dx) > 40f && acts.size > 1 -> swapped = !swapped
                            }
                        },
                    ) { ch, d -> ch.consume(); dx += d.x; dy += d.y }
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = mode,
                contentKey = { it.id },
                transitionSpec = { (fadeIn(tween(180, 60)) + scaleIn(tween(220), 0.92f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(120), 0.96f)) },
                label = "island",
            ) { m ->
                Box(Modifier.requiredSize(tw.dp, th.dp), contentAlignment = Alignment.Center) {
                    when (m) {
                        is IslandMode.Compact -> CompactContent(m.a, baseH.dp)
                        is IslandMode.Expanded -> Box(Modifier.fillMaxSize().padding(top = topInset.dp)) { ExpandedContent(m.a, s.notifText) }
                        else -> Unit
                    }
                }
            }
        }
        // İkinci etkinlik: adanın sağında küçük daire.
        if (bubble && second != null) {
            Box(
                Modifier.align(Alignment.TopCenter)
                    .offset { IntOffset(px(aw.value / 2f + gap + bubbleD / 2f), 0) }
                    .size(bubbleD.dp).clip(CircleShape).background(if (glass) Color(0xD91C1C1E) else Color.Black)
                    .clickable { swapped = !swapped },
                contentAlignment = Alignment.Center,
            ) { Leading(second, (bubbleD * 0.62f).dp) }
        }
    }
}

// ---------------------------------------------------------------- Kompakt

@Composable
private fun CompactContent(a: IslandAct, h: Dp) {
    val pad = h * 0.2f
    val icon = h * 0.62f
    Row(Modifier.fillMaxSize().padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {
        when (a) {
            is IslandAct.Charging -> {
                Text(if (a.plugged) "Şarj oluyor" else "Şarj kesildi", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text("%${a.level}", color = if (a.plugged) IGreen else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
                Spacer(Modifier.width(5.dp))
                Canvas(Modifier.size(width = icon * 1.15f, height = icon * 0.6f)) { battery(if (a.plugged) IGreen else Color.White, a.level / 100f, a.plugged) }
            }
            is IslandAct.Ringer -> {
                val silent = a.mode != android.media.AudioManager.RINGER_MODE_NORMAL
                Canvas(Modifier.size(icon)) { bell(if (silent) IRed else Color.White, silent) }
                Spacer(Modifier.weight(1f))
                Text(
                    when (a.mode) { android.media.AudioManager.RINGER_MODE_SILENT -> "Sessiz"; android.media.AudioManager.RINGER_MODE_VIBRATE -> "Titreşim"; else -> "Zil açık" },
                    color = if (silent) IRed else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                )
            }
            else -> {
                Leading(a, icon)
                Spacer(Modifier.weight(1f))
                Trailing(a, icon)
            }
        }
    }
}

/** Sol taraf: etkinliğin simgesi (kapak, telefon, saat, ok, uygulama simgesi). */
@Composable
private fun Leading(a: IslandAct, size: Dp) {
    when (a) {
        is IslandAct.Music -> {
            val img = remember(a.np.art) { a.np.art?.asImageBitmap() }
            if (img != null) Image(img, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)), contentScale = ContentScale.Crop)
            else AppIconBox(a.pkg, size)
        }
        is IslandAct.Call -> Box(Modifier.size(size).clip(CircleShape).background(IGreen), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size * 0.6f)) { phone(Color.White) }
        }
        is IslandAct.Timer -> Canvas(Modifier.size(size)) { timer(IOrange) }
        is IslandAct.Nav -> {
            val img = remember(a.arrow) { a.arrow?.asImageBitmap() }
            if (img != null) Image(img, null, Modifier.size(size), contentScale = ContentScale.Fit) else AppIconBox(a.pkg, size)
        }
        else -> AppIconBox(a.pkg, size)
    }
}

/** Sağ taraf: ses dalgası, süre, ilerleme halkası. */
@Composable
private fun Trailing(a: IslandAct, size: Dp) {
    val now = rememberSecondTick(a is IslandAct.Call || a is IslandAct.Timer)
    when (a) {
        is IslandAct.Music -> Waveform(artColor(a.np.art), a.np.playing, size)
        is IslandAct.Call -> Text(
            if (a.incoming) "Gelen" else if (a.since > 0) clock(now - a.since) else "Arama",
            color = IGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
        )
        is IslandAct.Timer -> Text(timerText(a, now), color = IOrange, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1)
        is IslandAct.Nav -> Text(a.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(size * 3.4f))
        is IslandAct.Progress -> Canvas(Modifier.size(size * 0.86f)) { ring(IBlue, a.value, a.indeterminate) }
        else -> Unit
    }
}

private fun timerText(a: IslandAct.Timer, now: Long): String = when {
    a.base > 0 && a.countDown -> clock(a.base - now + 999)
    a.base > 0 -> clock(now - a.base)
    else -> a.text
}

@Composable
private fun AppIconBox(pkg: String, size: Dp) {
    val img = appIcon(pkg)
    if (img != null) Image(img, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.26f)))
    else Box(Modifier.size(size).clip(CircleShape).background(Color(0x33FFFFFF)))
}

@Composable
private fun artColor(art: android.graphics.Bitmap?): Color = remember(art) {
    if (art == null) IGreen else runCatching {
        val c = android.graphics.Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.3f).coerceIn(0.35f, 1f); hsv[2] = hsv[2].coerceAtLeast(0.85f)
        Color(android.graphics.Color.HSVToColor(hsv))
    }.getOrDefault(IGreen)
}

@Composable
private fun Waveform(color: Color, playing: Boolean, size: Dp) {
    val bars = 5
    if (!playing) {
        Canvas(Modifier.size(width = size * 1.1f, height = size)) {
            val bw = this.size.width / (bars * 2f - 1f)
            for (i in 0 until bars) drawRoundRect(color.copy(alpha = 0.7f), Offset(i * bw * 2f, this.size.height * 0.42f), Size(bw, this.size.height * 0.16f), CornerRadius(bw / 2f))
        }
        return
    }
    val tr = rememberInfiniteTransition(label = "wave")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(Modifier.size(width = size * 1.1f, height = size)) {
        val bw = this.size.width / (bars * 2f - 1f)
        for (i in 0 until bars) {
            val ph = (p + i * 0.23f) * 2f * Math.PI.toFloat()
            val k = 0.25f + 0.75f * (0.5f + 0.5f * kotlin.math.sin(ph) * kotlin.math.cos(ph * 0.5f + i)).coerceIn(0f, 1f)
            val bh = this.size.height * k
            drawRoundRect(color, Offset(i * bw * 2f, (this.size.height - bh) / 2f), Size(bw, bh), CornerRadius(bw / 2f))
        }
    }
}

// ---------------------------------------------------------------- Genişlemiş

@Composable
private fun ExpandedContent(a: IslandAct, showText: Boolean) {
    val ctx = LocalContext.current
    val now = rememberSecondTick(a is IslandAct.Call || a is IslandAct.Timer)
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.Center) {
        when (a) {
            is IslandAct.Music -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Leading(a, 60.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.np.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (a.np.artist.isNotBlank()) Text(a.np.artist, color = IDim, fontSize = 14.sp, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Waveform(artColor(a.np.art), a.np.playing, 22.dp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IslandButton(30.dp, onClick = { NowPlaying.previous() }) { skip(Color.White, false) }
                    IslandButton(40.dp, onClick = { NowPlaying.toggle() }) { play(Color.White, a.np.playing) }
                    IslandButton(30.dp, onClick = { NowPlaying.next() }) { skip(Color.White, true) }
                }
            }
            is IslandAct.Call -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val av = remember(a.avatar) { a.avatar?.asImageBitmap() }
                    if (av != null) Image(av, null, Modifier.size(46.dp).clip(CircleShape), contentScale = ContentScale.Crop) else Leading(a, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (a.incoming) "Gelen arama" else "Arama", color = IDim, fontSize = 13.sp, fontFamily = AppFont)
                        Text(a.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!a.incoming && a.since > 0) Text(clock(now - a.since), color = IGreen, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
                }
                if (a.incoming || a.hangup != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        if (a.hangup != null) RoundAction(IRed, { Island.fire(a.hangup) }) { phone(Color.White, down = true) }
                        if (a.incoming && a.answer != null) RoundAction(IGreen, { Island.fire(a.answer) }) { phone(Color.White) }
                        if (a.hangup == null && a.answer == null) RoundAction(Color(0x33FFFFFF), { Island.open(ctx, a) }) { phone(Color.White) }
                    }
                }
            }
            is IslandAct.Timer -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(34.dp)) { timer(IOrange) }
                    Spacer(Modifier.width(12.dp))
                    Text(a.title.ifBlank { "Zamanlayıcı" }, color = IDim, fontSize = 14.sp, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(timerText(a, now), color = IOrange, fontSize = 36.sp, fontWeight = FontWeight.Light, fontFamily = AppFont, maxLines = 1)
                }
            }
            is IslandAct.Nav -> Row(verticalAlignment = Alignment.CenterVertically) {
                Leading(a, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.text.isNotBlank()) Text(a.text, color = IDim, fontSize = 14.sp, fontFamily = AppFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            is IslandAct.Progress -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconBox(a.pkg, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(a.title.ifBlank { Island.appLabel(ctx, a.pkg) }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!a.indeterminate) Text("%${(a.value * 100).roundToInt()}", color = IDim, fontSize = 14.sp, fontFamily = AppFont)
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(if (a.indeterminate) 0.3f else a.value).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(IBlue))
                }
            }
            is IslandAct.Message -> Row(verticalAlignment = Alignment.CenterVertically) {
                val av = remember(a.avatar) { a.avatar?.asImageBitmap() }
                Box {
                    if (av != null) Image(av, null, Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop) else AppIconBox(a.pkg, 44.dp)
                    if (av != null) Box(Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp)) { AppIconBox(a.pkg, 18.dp) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (showText && a.text.isNotBlank()) Text(a.text, color = IDim, fontSize = 14.sp, fontFamily = AppFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else Text(Island.appLabel(ctx, a.pkg), color = IDim, fontSize = 13.sp, fontFamily = AppFont, maxLines = 1)
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun IslandButton(size: Dp, onClick: () -> Unit, draw: DrawScope.() -> Unit) {
    Box(Modifier.size(size * 1.6f).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { draw() }
    }
}

@Composable
private fun RoundAction(bg: Color, onClick: () -> Unit, draw: DrawScope.() -> Unit) {
    Box(Modifier.size(50.dp).clip(CircleShape).background(bg).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) { draw() }
    }
}

// ---------------------------------------------------------------- Simgeler (vektörel)

private fun DrawScope.phone(c: Color, down: Boolean = false) {
    val s = size.minDimension
    val p = Path().apply {
        moveTo(s * 0.22f, s * 0.08f); cubicTo(s * 0.08f, s * 0.14f, s * 0.04f, s * 0.32f, s * 0.14f, s * 0.5f)
        cubicTo(s * 0.3f, s * 0.78f, s * 0.52f, s * 0.92f, s * 0.72f, s * 0.95f)
        cubicTo(s * 0.86f, s * 0.97f, s * 0.95f, s * 0.86f, s * 0.95f, s * 0.78f)
        lineTo(s * 0.74f, s * 0.62f); lineTo(s * 0.6f, s * 0.72f)
        cubicTo(s * 0.46f, s * 0.64f, s * 0.36f, s * 0.54f, s * 0.28f, s * 0.4f)
        lineTo(s * 0.38f, s * 0.26f); close()
    }
    if (down) {
        rotate(135f, Offset(s / 2f, s / 2f)) { drawPath(p, c) }
    } else drawPath(p, c)
}

private fun DrawScope.timer(c: Color) {
    val s = size.minDimension; val w = s * 0.1f
    val ctr = Offset(s / 2f, s * 0.56f); val r = s * 0.38f
    drawCircle(c, r, ctr, style = Stroke(w))
    drawLine(c, Offset(s * 0.4f, s * 0.08f), Offset(s * 0.6f, s * 0.08f), w, StrokeCap.Round)
    drawLine(c, ctr, Offset(ctr.x, ctr.y - r * 0.6f), w, StrokeCap.Round)
    drawLine(c, ctr, Offset(ctr.x + r * 0.42f, ctr.y + r * 0.2f), w, StrokeCap.Round)
}

private fun DrawScope.bell(c: Color, slash: Boolean) {
    val s = size.minDimension; val w = s * 0.09f
    val p = Path().apply {
        moveTo(s * 0.18f, s * 0.72f); lineTo(s * 0.82f, s * 0.72f)
        cubicTo(s * 0.72f, s * 0.62f, s * 0.74f, s * 0.5f, s * 0.74f, s * 0.42f)
        cubicTo(s * 0.74f, s * 0.24f, s * 0.62f, s * 0.14f, s * 0.5f, s * 0.14f)
        cubicTo(s * 0.38f, s * 0.14f, s * 0.26f, s * 0.24f, s * 0.26f, s * 0.42f)
        cubicTo(s * 0.26f, s * 0.5f, s * 0.28f, s * 0.62f, s * 0.18f, s * 0.72f); close()
    }
    drawPath(p, c)
    drawCircle(c, s * 0.08f, Offset(s * 0.5f, s * 0.82f))
    if (slash) drawLine(c, Offset(s * 0.12f, s * 0.1f), Offset(s * 0.88f, s * 0.9f), w * 1.2f, StrokeCap.Round)
}

private fun DrawScope.battery(c: Color, level: Float, bolt: Boolean) {
    val w = size.width; val h = size.height; val sw = h * 0.1f
    drawRoundRect(c.copy(alpha = 0.5f), Offset(0f, 0f), Size(w * 0.9f, h), CornerRadius(h * 0.28f), style = Stroke(sw))
    drawRoundRect(c.copy(alpha = 0.5f), Offset(w * 0.92f, h * 0.3f), Size(w * 0.08f, h * 0.4f), CornerRadius(h * 0.1f))
    drawRoundRect(c, Offset(sw * 1.6f, sw * 1.6f), Size((w * 0.9f - sw * 3.2f) * level.coerceIn(0.05f, 1f), h - sw * 3.2f), CornerRadius(h * 0.16f))
    if (bolt) {
        val p = Path().apply {
            moveTo(w * 0.5f, h * 0.05f); lineTo(w * 0.32f, h * 0.56f); lineTo(w * 0.46f, h * 0.56f)
            lineTo(w * 0.4f, h * 0.95f); lineTo(w * 0.6f, h * 0.42f); lineTo(w * 0.46f, h * 0.42f); close()
        }
        drawPath(p, Color.Black)
    }
}

private fun DrawScope.ring(c: Color, v: Float, indeterminate: Boolean) {
    val s = size.minDimension; val w = s * 0.16f
    drawCircle(c.copy(alpha = 0.25f), s / 2f - w / 2f, style = Stroke(w))
    drawArc(c, -90f, if (indeterminate) 90f else 360f * v, false, Offset(w / 2f, w / 2f), Size(s - w, s - w), style = Stroke(w, cap = StrokeCap.Round))
}

private fun DrawScope.play(c: Color, playing: Boolean) {
    val s = size.minDimension
    if (playing) {
        val bw = s * 0.24f
        drawRoundRect(c, Offset(s * 0.2f, s * 0.12f), Size(bw, s * 0.76f), CornerRadius(bw * 0.3f))
        drawRoundRect(c, Offset(s * 0.56f, s * 0.12f), Size(bw, s * 0.76f), CornerRadius(bw * 0.3f))
    } else drawPath(Path().apply { moveTo(s * 0.24f, s * 0.1f); lineTo(s * 0.9f, s * 0.5f); lineTo(s * 0.24f, s * 0.9f); close() }, c)
}

private fun DrawScope.skip(c: Color, forward: Boolean) {
    val s = size.minDimension
    fun tri(x0: Float) = Path().apply {
        if (forward) { moveTo(x0, s * 0.22f); lineTo(x0 + s * 0.42f, s * 0.5f); lineTo(x0, s * 0.78f) }
        else { moveTo(x0 + s * 0.42f, s * 0.22f); lineTo(x0, s * 0.5f); lineTo(x0 + s * 0.42f, s * 0.78f) }
        close()
    }
    drawPath(tri(s * 0.08f), c); drawPath(tri(s * 0.5f), c)
}
