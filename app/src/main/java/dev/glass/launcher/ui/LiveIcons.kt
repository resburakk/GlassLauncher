package dev.glass.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
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
enum class LiveIconKind { Clock, Calendar }

private val IosRed = Color(0xFFFF3B30)
private val IosOrange = Color(0xFFFF9500)
private val TR_DAYS = arrayOf("Paz", "Pzt", "Sal", "Çar", "Per", "Cum", "Cmt")

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

@Composable
fun LiveIcon(kind: LiveIconKind, size: Dp, seconds: Boolean) {
    when (kind) {
        LiveIconKind.Clock -> LiveClock(seconds && size >= 30.dp)
        LiveIconKind.Calendar -> LiveCalendar()
    }
}

/** iOS Takvim simgesi: beyaz zemin, üstte kırmızı kısa gün adı, altta büyük tarih. */
@Composable
private fun LiveCalendar() {
    val measurer = rememberTextMeasurer()
    val now = rememberNow(false)
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val s = size.minDimension
        onDrawBehind {
        drawRect(Color.White)
        val cal = Calendar.getInstance().apply { timeInMillis = now() }
        val day = TR_DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val date = cal.get(Calendar.DAY_OF_MONTH).toString()
        val dayStyle = TextStyle(
            color = IosRed, fontFamily = AppFont, fontWeight = FontWeight.Medium,
            fontSize = (s * 0.165f).toSp(), letterSpacing = 0.sp,
        )
        val dateStyle = TextStyle(
            color = Color.Black, fontFamily = AppFont, fontWeight = FontWeight.Light,
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

/** iOS Saat simgesi: siyah zemin, beyaz kadran, rakamlar, siyah ibreler, turuncu saniye ibresi. */
@Composable
private fun LiveClock(seconds: Boolean) {
    val measurer = rememberTextMeasurer()
    val now = rememberNow(seconds)
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val s = size.minDimension
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val faceR = s * 0.445f
        // Rakamlar boyut değişince bir kez ölçülür (her karede değil).
        val numStyle = TextStyle(
            color = Color.Black, fontFamily = AppFont, fontWeight = FontWeight.Medium,
            fontSize = (s * 0.108f).toSp(), letterSpacing = 0.sp,
        )
        val numR = faceR * 0.76f
        val nums = (1..12).map { h ->
            val a = Math.toRadians(h * 30.0 - 90.0)
            val m = measurer.measure(h.toString(), numStyle)
            m to Offset(
                c.x + numR * cos(a).toFloat() - m.size.width / 2f,
                c.y + numR * sin(a).toFloat() - m.size.height / 2f,
            )
        }
        onDrawBehind {
        drawRect(Color.Black)
        drawCircle(Color.White, faceR, c)
        drawTicks(c, faceR, s)
        nums.forEach { (m, o) -> drawText(m, topLeft = o) }
        val cal = Calendar.getInstance().apply { timeInMillis = now() }
        val ms = cal.get(Calendar.MILLISECOND)
        val sec = cal.get(Calendar.SECOND) + ms / 1000f
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) + min / 60f)
        hand(c, hr * 30f, faceR * 0.50f, s * 0.052f, Color.Black, tail = 0f)
        hand(c, min * 6f, faceR * 0.78f, s * 0.040f, Color.Black, tail = 0f)
        drawCircle(Color.Black, s * 0.042f, c)
        if (seconds) {
            hand(c, sec * 6f, faceR * 0.86f, s * 0.016f, IosOrange, tail = faceR * 0.20f)
            drawCircle(IosOrange, s * 0.028f, c)
            drawCircle(Color.White, s * 0.010f, c)
        }
        }
    })
}

private fun DrawScope.drawTicks(c: Offset, r: Float, s: Float) {
    for (i in 0 until 60) {
        if (i % 5 == 0) continue
        val a = Math.toRadians(i * 6.0)
        val o = Offset(c.x + r * 0.95f * sin(a).toFloat(), c.y - r * 0.95f * cos(a).toFloat())
        val n = Offset(c.x + r * 0.90f * sin(a).toFloat(), c.y - r * 0.90f * cos(a).toFloat())
        drawLine(Color(0xFFB0B0B5), n, o, strokeWidth = s * 0.008f)
    }
}

private fun DrawScope.hand(c: Offset, deg: Float, len: Float, width: Float, color: Color, tail: Float) {
    rotate(deg, pivot = c) {
        drawLine(color, Offset(c.x, c.y + tail), Offset(c.x, c.y - len), strokeWidth = width, cap = StrokeCap.Round)
    }
}
