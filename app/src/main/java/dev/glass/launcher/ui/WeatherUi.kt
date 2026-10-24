package dev.glass.launcher.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.glass.launcher.data.Sky
import dev.glass.launcher.data.WeatherNow
import dev.glass.launcher.data.WeatherRepo
import dev.glass.launcher.data.WidgetItem
import kotlinx.coroutines.launch

private val TR_SHORT_DAYS = arrayOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")

/** Hava durumu verisi: ekrandayken (launcher öndeyken) yenilenir; yenileme kendi içinde 30 dk ile sınırlı. */
@Composable
private fun rememberWeather(): WeatherNow? {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { WeatherRepo.refresh(ctx.applicationContext) }
    }
    val w by WeatherRepo.now.collectAsStateWithLifecycle()
    return w
}

/** Gökyüzü simgesi (güneş, ay, bulut, yağmur, kar, şimşek, sis): vektörel, her boyutta net. */
@Composable
fun SkyGlyph(sky: Sky, day: Boolean, size: Dp, mono: Color? = null) {
    Canvas(Modifier.size(size)) { drawSky(sky, day, mono) }
}

private val SunBase = Color(0xFFFFD23F)
private val CloudBase = Color(0xFFF2F4F8)
private val ShadeBase = Color(0xFFC9D1DC)
private val RainBase = Color(0xFF5AC8FA)

fun DrawScope.drawSky(sky: Sky, day: Boolean, mono: Color? = null) {
    val s = size.minDimension
    // Tek renk (renkli / şeffaf simge): bütün parçalar aynı renk, katman farkı saydamlıkla.
    val Sun = mono ?: SunBase
    val CloudC = mono ?: CloudBase
    val CloudShade = mono?.copy(alpha = mono.alpha * 0.6f) ?: ShadeBase
    val RainC = mono ?: RainBase
    val White = mono ?: Color.White
    val Moon = mono ?: Color(0xFFF5F3CE)
    fun sun(cx: Float, cy: Float, r: Float) {
        if (day) {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                drawLine(Sun, Offset(cx + c * r * 1.35f, cy + sn * r * 1.35f), Offset(cx + c * r * 1.75f, cy + sn * r * 1.75f), r * 0.22f, StrokeCap.Round)
            }
            drawCircle(Sun, r, Offset(cx, cy))
        } else {
            // Hilal: açık daireden kaydırılmış koyu daire "kesilir" (saydam zemin üstünde ikinci dairenin rengiyle).
            val p = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
            }
            val cut = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(cx - r * 0.35f, cy - r * 1.2f, cx + r * 1.65f, cy + r * 0.8f))
            }
            val moon = Path().apply { op(p, cut, androidx.compose.ui.graphics.PathOperation.Difference) }
            drawPath(moon, Moon)
        }
    }
    fun cloud(cx: Float, cy: Float, w: Float, color: Color) {
        val h = w * 0.42f
        drawRoundRect(color, Offset(cx - w / 2, cy - h * 0.1f), Size(w, h * 0.62f), CornerRadius(h * 0.31f))
        drawCircle(color, w * 0.22f, Offset(cx - w * 0.17f, cy))
        drawCircle(color, w * 0.28f, Offset(cx + w * 0.1f, cy - h * 0.18f))
    }
    when (sky) {
        Sky.Clear -> sun(s * 0.5f, s * 0.5f, s * 0.24f)
        Sky.PartlyCloudy -> { sun(s * 0.62f, s * 0.36f, s * 0.18f); cloud(s * 0.44f, s * 0.6f, s * 0.66f, CloudC) }
        Sky.Cloudy -> { cloud(s * 0.6f, s * 0.42f, s * 0.5f, CloudShade); cloud(s * 0.44f, s * 0.6f, s * 0.7f, CloudC) }
        Sky.Fog -> {
            cloud(s * 0.5f, s * 0.4f, s * 0.66f, CloudC)
            for (i in 0..2) drawLine(CloudShade, Offset(s * 0.18f, s * (0.66f + i * 0.1f)), Offset(s * 0.82f, s * (0.66f + i * 0.1f)), s * 0.05f, StrokeCap.Round)
        }
        Sky.Drizzle, Sky.Rain -> {
            cloud(s * 0.5f, s * 0.4f, s * 0.7f, CloudC)
            val n = if (sky == Sky.Rain) 3 else 2
            for (i in 0 until n) {
                val x = s * (0.34f + i * 0.16f)
                drawLine(RainC, Offset(x, s * 0.66f), Offset(x - s * 0.05f, s * 0.84f), s * 0.055f, StrokeCap.Round)
            }
        }
        Sky.Snow -> {
            cloud(s * 0.5f, s * 0.4f, s * 0.7f, CloudC)
            for (i in 0 until 3) drawCircle(White, s * 0.04f, Offset(s * (0.34f + i * 0.16f), s * (0.72f + (i % 2) * 0.08f)))
        }
        Sky.Storm -> {
            cloud(s * 0.5f, s * 0.38f, s * 0.7f, CloudShade)
            val bolt = Path().apply {
                moveTo(s * 0.52f, s * 0.56f); lineTo(s * 0.40f, s * 0.76f); lineTo(s * 0.50f, s * 0.76f)
                lineTo(s * 0.44f, s * 0.94f); lineTo(s * 0.62f, s * 0.70f); lineTo(s * 0.52f, s * 0.70f); close()
            }
            drawPath(bolt, Sun)
        }
    }
}

/** Hava durumu widget'ı (iOS gibi): küçük, orta (saatlik) ve büyük (saatlik + 5 gün) boyutlar. */
@Composable
fun WeatherWidget(w: WidgetItem) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = rememberWeather()
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { WeatherRepo.refresh(ctx.applicationContext, force = true) }
    }
    if (data == null) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SkyGlyph(Sky.PartlyCloudy, true, 44.dp)
            Spacer(Modifier.height(6.dp))
            if (!WeatherRepo.hasPermission(ctx)) {
                Text(
                    "Konum izni ver", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))
                        .clickable { perm.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                Text("Hava durumu alınıyor…", color = Color(0xCCFFFFFF), fontSize = 13.sp, fontFamily = AppFont)
            }
        }
        return
    }
    val big = w.spanY >= 4
    val wide = w.spanX >= 4
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    data.city.ifEmpty { "Konumum" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("${data.temp}°", color = Color.White, fontSize = if (wide) 44.sp else 40.sp, fontWeight = FontWeight.Light, fontFamily = AppFont)
            }
            if (wide) {
                Column(horizontalAlignment = Alignment.End) {
                    SkyGlyph(data.sky, data.day, 26.dp)
                    Text(data.sky.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont)
                    Text("Y:${data.max}° D:${data.min}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont)
                }
            }
        }
        if (!wide) {
            Spacer(Modifier.weight(1f))
            SkyGlyph(data.sky, data.day, 22.dp)
            Text(data.sky.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont, maxLines = 1)
            Text("Y:${data.max}° D:${data.min}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont)
            return@Column
        }
        Spacer(Modifier.weight(1f))
        // Saatlik: 6 saat.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            data.hours.forEachIndexed { i, h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (i == 0) "Şimdi" else "%02d".format(h.hour), color = Color(0xDDFFFFFF), fontSize = 12.sp, fontFamily = AppFont)
                    Spacer(Modifier.height(4.dp))
                    SkyGlyph(h.sky, h.day, 22.dp)
                    Spacer(Modifier.height(4.dp))
                    Text("${h.temp}°", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
                }
            }
        }
        if (big && data.days.size > 1) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x40FFFFFF)))
            Spacer(Modifier.height(6.dp))
            val lo = data.days.minOf { it.min }
            val hi = data.days.maxOf { it.max }.coerceAtLeast(lo + 1)
            data.days.drop(1).take(5).forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(TR_SHORT_DAYS[(d.dayOfWeek - 1).coerceIn(0, 6)], color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont, modifier = Modifier.width(40.dp))
                    SkyGlyph(d.sky, true, 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("${d.min}°", color = Color(0xB3FFFFFF), fontSize = 14.sp, fontFamily = AppFont, modifier = Modifier.width(34.dp))
                    // Sıcaklık aralığı çubuğu (iOS gibi).
                    Canvas(Modifier.weight(1f).height(5.dp)) {
                        drawRoundRect(Color(0x33FFFFFF), cornerRadius = CornerRadius(size.height / 2))
                        val a = (d.min - lo).toFloat() / (hi - lo); val b = (d.max - lo).toFloat() / (hi - lo)
                        drawRoundRect(
                            Brush.horizontalGradient(listOf(Color(0xFF5AC8FA), Color(0xFFFFD23F), Color(0xFFFF9F0A))),
                            topLeft = Offset(size.width * a, 0f), size = Size(size.width * (b - a).coerceAtLeast(0.06f), size.height),
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${d.max}°", color = Color.White, fontSize = 14.sp, fontFamily = AppFont)
                }
            }
        }
    }
}

/**
 * Hava durumu uygulamasının canlı simgesi: gökyüzü simgesi ve o anki sıcaklık. Zemin seçili simge görünümüne
 * uyar (açık: gündüz/gece degradesi, koyu: koyu degrade, renkli: koyu zemin + tek renk, şeffaf: zeminsiz).
 */
@Composable
fun WeatherLiveIcon(look: LiveLook = LiveLook.Light, tint: Color = Color(0xFF64D2FF)) {
    val data = rememberWeather()
    val day = data?.day ?: true
    val bg: Brush? = when (look) {
        LiveLook.Light -> Brush.verticalGradient(if (day) listOf(Color(0xFF2E7BD6), Color(0xFF6CB4F2)) else listOf(Color(0xFF0B1636), Color(0xFF2A3F78)))
        LiveLook.Dark -> Brush.verticalGradient(listOf(Color(0xFF1E2A44), Color(0xFF0C1120)))
        LiveLook.Tinted -> Brush.verticalGradient(listOf(Color(0xFF2C2C2E), Color(0xFF0A0A0C)))
        LiveLook.Clear -> null
    }
    val mono: Color? = when (look) {
        LiveLook.Tinted -> tint
        LiveLook.Clear -> Color(0xF2FFFFFF)
        else -> null
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().then(if (bg != null) Modifier.background(bg) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val s = maxWidth
        if (data == null) {
            // Veri yokken (konum izni / ilk yükleme) simge kutuyu doldurur.
            SkyGlyph(Sky.PartlyCloudy, day, s * 0.78f, mono)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SkyGlyph(data.sky, day, s * 0.56f, mono)
                Text(
                    "${data.temp}°", color = mono ?: Color.White, fontFamily = AppFont, fontWeight = FontWeight.SemiBold,
                    fontSize = (s.value * 0.25f).sp, lineHeight = (s.value * 0.26f).sp,
                )
            }
        }
    }
}
