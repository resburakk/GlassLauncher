package dev.glass.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/** Canlı çizilen sistem simgeleri (iOS'taki gibi). */
enum class LiveIconKind { Clock, Calendar, Weather }

private val TR_DAYS = arrayOf("Paz", "Pzt", "Sal", "Çar", "Per", "Cum", "Cmt")

/**
 * Sayfa kayarken ve arşiv/widget sayfası geçişlerinde canlı simgelerin (saniye ibresi) her kare güncellenmesi
 * duraklatılır: saniye ibresi bulunduğu sayfanın tamamını her karede yeniden çizdiriyor, kaymayı pürüzlendiriyordu.
 */
object LiveMotion {
    var scrolling by androidx.compose.runtime.mutableStateOf(false)
    var transition by androidx.compose.runtime.mutableStateOf(false)
    val paused: Boolean get() = scrolling || transition
}

/**
 * Şu anki zaman (ms). Yalnızca launcher öndeyken güncellenir; arka planda pil harcamaz.
 * [smooth] true: her karede (akıcı saniye ibresi); false: dakika başlarında.
 */
@Composable
private fun rememberNow(smooth: Boolean): () -> Long {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(smooth, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (smooth) {
                    if (LiveMotion.paused) {
                        androidx.compose.runtime.snapshotFlow { LiveMotion.paused }.first { !it }
                    }
                    withFrameMillis { now.longValue = System.currentTimeMillis() }
                } else {
                    val t = System.currentTimeMillis()
                    now.longValue = t
                    delay(60_000L - t % 60_000L + 5L)
                }
            }
        }
    }
    // Okuma çizim aşamasında yapılır: yalnızca simge yeniden çizilir, yeniden kompozisyon olmaz.
    return { now.longValue }
}

/** Canlı simgelerin renkleri (açık / koyu mod). */
private class LivePalette(
    val calBg: Color, val calRed: Color, val calInk: Color,
    val clockBg: Color, val clockInk: Color, val clockRed: Color,
)

private val LightPalette = LivePalette(
    calBg = Color.White, calRed = Color(0xFFFF3B30), calInk = Color.Black,
    clockBg = Color.White, clockInk = Color(0xFF1E1E1E), clockRed = Color(0xFFE63946),
)
private val DarkPalette = LivePalette(
    calBg = Color(0xFF1C1C1E), calRed = Color(0xFFFF453A), calInk = Color.White,
    clockBg = Color(0xFF1C1C1E), clockInk = Color.White, clockRed = Color(0xFFFF5C5C),
)

/** Canlı simgenin görünümü: seçili simge görünümüyle aynı (açık, koyu, renkli, şeffaf). */
enum class LiveLook { Light, Dark, Tinted, Clear }

/**
 * [look]: simgelerin seçili görünümüne uyar. Renkli: koyu zeminde tek renk ([tint]); Şeffaf: zemin yok (arkası
 * cam), beyazımsı çizgiler.
 */
@Composable
fun LiveIcon(kind: LiveIconKind, size: Dp, seconds: Boolean, look: LiveLook = LiveLook.Light, tint: Color = Color(0xFF64D2FF)) {
    val p = when (look) {
        LiveLook.Light -> LightPalette
        LiveLook.Dark -> DarkPalette
        LiveLook.Tinted -> LivePalette(
            calBg = Color(0xFF141416), calRed = tint, calInk = tint,
            clockBg = Color(0xFF141416), clockInk = tint, clockRed = tint,
        )
        LiveLook.Clear -> LivePalette(
            calBg = Color.Transparent, calRed = Color(0xF2FFFFFF), calInk = Color(0xF2FFFFFF),
            clockBg = Color.Transparent, clockInk = Color(0xF2FFFFFF), clockRed = Color(0xB3FFFFFF),
        )
    }
    when (kind) {
        LiveIconKind.Clock -> LiveClock(seconds && size >= 30.dp, p)
        LiveIconKind.Calendar -> LiveCalendar(p)
        LiveIconKind.Weather -> WeatherLiveIcon(look, tint)
    }
}

/** Takvim simgesi: üstte kırmızı kısa gün adı, altta büyük tarih; açık modda beyaz, koyuda koyu gri zemin. */
@Composable
private fun LiveCalendar(p: LivePalette) {
    val measurer = rememberTextMeasurer()
    val now = rememberNow(false)
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val s = size.minDimension
        onDrawBehind {
        drawRect(p.calBg)
        val cal = Calendar.getInstance().apply { timeInMillis = now() }
        val day = TR_DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val date = cal.get(Calendar.DAY_OF_MONTH).toString()
        val dayStyle = TextStyle(
            color = p.calRed, fontFamily = AppFont, fontWeight = FontWeight.SemiBold,
            fontSize = (s * 0.165f).toSp(), letterSpacing = 0.sp,
        )
        val dateStyle = TextStyle(
            color = p.calInk, fontFamily = AppFont, fontWeight = FontWeight.Medium,
            fontSize = (s * 0.50f).toSp(), letterSpacing = (-0.02f * s).toSp(),
        )
        val d = measurer.measure(day, dayStyle)
        val n = measurer.measure(date, dateStyle)
        drawText(d, topLeft = Offset((size.width - d.size.width) / 2f, s * 0.205f - d.size.height / 2f))
        // Rakamın görsel ortası (yazı kutusu üst/alt boşluk içerir) ~%60 yükseklikte durur.
        drawText(n, topLeft = Offset((size.width - n.size.width) / 2f, s * 0.60f - n.size.height / 2f))
        }
    })
}

/**
 * Bauhaus saat simgesi: krem (koyu modda koyu kahve) zemin, iri 12-3-6-9 rakamları, diğer saatlerde
 * noktalar, kalın yuvarlak uçlu ibreler, kırmızı saniye ibresi.
 */
@Composable
private fun LiveClock(seconds: Boolean, p: LivePalette) {
    val measurer = rememberTextMeasurer()
    val now = rememberNow(seconds)
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = s * 0.42f
        // Rakamlar boyut/renk değişince bir kez ölçülür (her karede değil).
        val numStyle = TextStyle(
            color = p.clockInk, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = (s * 0.17f).toSp(), letterSpacing = 0.sp,
        )
        val nums = listOf(12, 3, 6, 9).map { h ->
            val a = Math.toRadians(h * 30.0 - 90.0)
            val m = measurer.measure(h.toString(), numStyle)
            m to Offset(
                c.x + r * 0.80f * cos(a).toFloat() - m.size.width / 2f,
                c.y + r * 0.80f * sin(a).toFloat() - m.size.height / 2f,
            )
        }
        val dots = listOf(1, 2, 4, 5, 7, 8, 10, 11).map { h ->
            val a = Math.toRadians(h * 30.0 - 90.0)
            Offset(c.x + r * 0.82f * cos(a).toFloat(), c.y + r * 0.82f * sin(a).toFloat())
        }
        onDrawBehind {
        drawRect(p.clockBg)
        nums.forEach { (m, o) -> drawText(m, topLeft = o) }
        dots.forEach { drawCircle(p.clockInk, s * 0.016f, it) }
        val cal = Calendar.getInstance().apply { timeInMillis = now() }
        val ms = cal.get(Calendar.MILLISECOND)
        val sec = cal.get(Calendar.SECOND) + ms / 1000f
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) + min / 60f)
        hand(c, hr * 30f, r * 0.45f, s * 0.07f, p.clockInk, tail = 0f)
        hand(c, min * 6f, r * 0.68f, s * 0.05f, p.clockInk, tail = 0f)
        if (seconds) {
            hand(c, sec * 6f, r * 0.70f, s * 0.018f, p.clockRed, tail = r * 0.18f)
            drawCircle(p.clockRed, s * 0.04f, c)
        } else {
            drawCircle(p.clockInk, s * 0.045f, c)
        }
        }
    })
}

private fun DrawScope.hand(c: Offset, deg: Float, len: Float, width: Float, color: Color, tail: Float) {
    rotate(deg, pivot = c) {
        drawLine(color, Offset(c.x, c.y + tail), Offset(c.x, c.y - len), strokeWidth = width, cap = StrokeCap.Round)
    }
}
