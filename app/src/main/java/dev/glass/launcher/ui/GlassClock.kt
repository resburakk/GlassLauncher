package dev.glass.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.glass.launcher.data.WidgetItem
import kotlinx.coroutines.delay
import java.util.Calendar

private val TR_DAYS_LONG = arrayOf("Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi")
private val TR_MONTHS = arrayOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")

/**
 * iOS 26 kilit ekranındaki Liquid Glass saatin ana ekran widget'ı: rakamlar camdan; içlerinde arkadaki duvar
 * kâğıdı büyüteç gibi büyütülmüş ve kırılmış görünür, üstte cam parlaması ve ince ışıklı kenar. Zemin yok.
 */
@Composable
fun GlassClockWidget(w: WidgetItem, width: Dp, height: Dp) {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val t = System.currentTimeMillis()
                now.longValue = t
                delay(60_000L - t % 60_000L + 5L)
            }
        }
    }
    val cal = Calendar.getInstance().apply { timeInMillis = now.longValue }
    val time = "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    val date = "${TR_DAYS_LONG[cal.get(Calendar.DAY_OF_WEEK) - 1]} ${cal.get(Calendar.DAY_OF_MONTH)} ${TR_MONTHS[cal.get(Calendar.MONTH)]}"
    val look = LocalGlass.current
    val src = look.sharp ?: look.backdrop
    var origin by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val big = w.spanY >= 4
    val dateSize = if (big) 20.sp else 16.sp
    Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date, color = Color.White, fontSize = dateSize, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                style = TextStyle(shadow = Shadow(Color(0x55000000), Offset(0f, 2f), 8f)),
            )
            val timeH = height * (if (big) 0.62f else 0.66f)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(timeH)
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                // Rakamlar alanı dolduracak kadar büyük (yükseklik ve genişliğe göre).
                var fs = size.height * 0.92f
                var style = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = fs.toSp(), letterSpacing = (-0.02f * fs).toSp())
                var layout = measurer.measure(time, style)
                if (layout.size.width > size.width * 0.96f) {
                    fs *= size.width * 0.96f / layout.size.width
                    style = style.copy(fontSize = fs.toSp(), letterSpacing = (-0.02f * fs).toSp())
                    layout = measurer.measure(time, style)
                }
                val tl = Offset((size.width - layout.size.width) / 2f, (size.height - layout.size.height) / 2f)
                // 1) Rakamların biçimi (maske)
                drawText(layout, color = Color.White, topLeft = tl)
                // 2) İçine arkadaki duvar kâğıdı, %14 büyütülmüş (cam büyüteç / kırılma)
                if (src != null && look.rootW > 0f && look.rootH > 0f && src.srcW > 0 && src.srcH > 0) {
                    val img = src.image
                    val scale = maxOf(look.rootW / src.srcW, look.rootH / src.srcH)
                    val offX = (look.rootW - src.srcW * scale) / 2f
                    val offY = (look.rootH - src.srcH * scale) / 2f
                    val k = img.width.toFloat() / src.srcW
                    val lens = 1.14f
                    val cx = origin.x + size.width / 2f
                    val cy = origin.y + size.height / 2f
                    val hw = size.width / 2f / lens
                    val hh = size.height / 2f / lens
                    val l = ((cx - hw - offX) / scale * k).toInt().coerceIn(0, img.width - 1)
                    val t = ((cy - hh - offY) / scale * k).toInt().coerceIn(0, img.height - 1)
                    val sw = (hw * 2f / scale * k).toInt().coerceIn(1, img.width - l)
                    val sh = (hh * 2f / scale * k).toInt().coerceIn(1, img.height - t)
                    drawImage(
                        img, srcOffset = IntOffset(l, t), srcSize = IntSize(sw, sh),
                        dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        blendMode = BlendMode.SrcIn, filterQuality = FilterQuality.Medium,
                    )
                }
                // 3) Cam parlaması: üstten aşağı sönen beyaz (yalnızca rakamların içinde)
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.22f))),
                    blendMode = BlendMode.SrcAtop,
                )
                // 4) İnce ışıklı kenar
                drawText(
                    layout, topLeft = tl,
                    brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.5f))),
                    drawStyle = Stroke(width = (fs * 0.012f).coerceAtLeast(1.5f)),
                )
            }
        }
    }
}
