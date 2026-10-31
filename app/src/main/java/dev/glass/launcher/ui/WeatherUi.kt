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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
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

/** Günün evresi: widget zemininin rengi ve gökyüzü öğeleri buna göre seçilir. */
enum class SkyPhase { Day, Dusk, Night }

/**
 * Şu anki evre. Gün doğumu/batımı biliniyorsa onlara göre (batıştan 40 dk önce – 35 dk sonra ve doğuşun ±30 dk'sı
 * alacakaranlık), bilinmiyorsa saate göre. Hava verisi 30 dakikada bir gelse de evre her dakika güncellenir.
 */
fun phaseOf(w: WeatherNow?, nowMin: Int): SkyPhase {
    val rise = w?.sunrise?.takeIf { it >= 0 } ?: 7 * 60
    val set = w?.sunset?.takeIf { it >= 0 } ?: 18 * 60
    return when {
        nowMin in (set - 40)..(set + 35) || nowMin in (rise - 30)..(rise + 30) -> SkyPhase.Dusk
        nowMin < rise - 30 || nowMin > set + 35 -> SkyPhase.Night
        else -> SkyPhase.Day
    }
}

@Composable
private fun rememberMinuteOfDay(): Int {
    val v = androidx.compose.runtime.produceState(nowMinute()) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = nowMinute()
        }
    }
    return v.value
}

private fun nowMinute(): Int = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }

private fun c(v: Long) = Color(v)

/** Gökyüzü degradesi (üst, alt): hava türü × gün evresi. */
private fun skyGradient(sky: Sky, phase: SkyPhase): Pair<Color, Color> = when (sky) {
    Sky.Clear -> when (phase) {
        SkyPhase.Day -> c(0xFF2A7BDE) to c(0xFF6CB6F5)
        SkyPhase.Dusk -> c(0xFF33427F) to c(0xFFE58A5F)
        SkyPhase.Night -> c(0xFF0A1433) to c(0xFF22386A)
    }
    Sky.PartlyCloudy -> when (phase) {
        SkyPhase.Day -> c(0xFF3A80D2) to c(0xFF84B3E2)
        SkyPhase.Dusk -> c(0xFF3F4677) to c(0xFFC98468)
        SkyPhase.Night -> c(0xFF101A35) to c(0xFF2B3B60)
    }
    Sky.Cloudy -> when (phase) {
        SkyPhase.Day -> c(0xFF627790) to c(0xFF97A7BA)
        SkyPhase.Dusk -> c(0xFF4D5270) to c(0xFF8E7676)
        SkyPhase.Night -> c(0xFF1B2231) to c(0xFF363F52)
    }
    Sky.Fog -> when (phase) {
        SkyPhase.Day -> c(0xFF77838F) to c(0xFFA6AFB8)
        SkyPhase.Dusk -> c(0xFF66626F) to c(0xFF9E8A84)
        SkyPhase.Night -> c(0xFF252A33) to c(0xFF444A54)
    }
    Sky.Drizzle, Sky.Rain -> when (phase) {
        SkyPhase.Day -> c(0xFF4A5E74) to c(0xFF7A8EA3)
        SkyPhase.Dusk -> c(0xFF3D4460) to c(0xFF736571)
        SkyPhase.Night -> c(0xFF121925) to c(0xFF2A3647)
    }
    Sky.Snow -> when (phase) {
        SkyPhase.Day -> c(0xFF6F8DAD) to c(0xFFA9BCD1)
        SkyPhase.Dusk -> c(0xFF5E6486) to c(0xFFA595A6)
        SkyPhase.Night -> c(0xFF1A2436) to c(0xFF3A4862)
    }
    Sky.Storm -> when (phase) {
        SkyPhase.Day -> c(0xFF374050) to c(0xFF5A6474)
        SkyPhase.Dusk -> c(0xFF2C2E42) to c(0xFF564A5A)
        SkyPhase.Night -> c(0xFF0D1018) to c(0xFF242A38)
    }
}

private fun Color.dim(k: Float) = Color(red * k, green * k, blue * k, alpha)

/**
 * Hava durumu widget'ının zemini (renkli görünümler): gökyüzü degradesi ve havaya göre çizilmiş sahne —
 * güneş parıltısı, gün batımı ışığı, ay ve yıldızlar, yumuşak bulutlar, yağmur çizgileri, kar taneleri,
 * sis bantları, fırtınada şimşek ışığı. Hareketsizdir (iOS widget'ları gibi) ve önbelleğe alınır: yalnızca
 * boyut, hava türü ya da gün evresi değişince yeniden çizilir; kaydırma ve geçişlerde maliyeti yoktur.
 */
@Composable
fun WeatherBackdrop(dark: Boolean, modifier: Modifier = Modifier) {
    val data = WeatherRepo.now.collectAsStateWithLifecycle().value
    val minute = rememberMinuteOfDay()
    val sky = data?.sky ?: Sky.PartlyCloudy
    val phase = phaseOf(data, minute)
    Box(modifier.fillMaxSize().drawWithCache {
        val (top, bottom) = skyGradient(sky, phase).let { (a, b) -> if (dark) a.dim(0.62f) to b.dim(0.62f) else a to b }
        val w = size.width; val h = size.height; val m = size.minDimension
        val bg = Brush.verticalGradient(listOf(top, bottom))
        val rnd = java.util.Random(sky.ordinal * 31L + phase.ordinal)
        val night = phase == SkyPhase.Night
        val rainy = sky == Sky.Rain || sky == Sky.Drizzle || sky == Sky.Storm
        val cloudy = sky != Sky.Clear && sky != Sky.Fog
        // Yıldızlar: gece, gökyüzü açık ya da parçalı bulutluyken.
        val stars = if (night && (sky == Sky.Clear || sky == Sky.PartlyCloudy)) List(22) {
            Triple(Offset(w * (0.35f + rnd.nextFloat() * 0.63f), h * rnd.nextFloat() * 0.62f), m * (0.004f + rnd.nextFloat() * 0.007f), 0.35f + rnd.nextFloat() * 0.55f)
        } else emptyList()
        // Yağmur çizgileri ve kar taneleri.
        val drops = if (rainy) List(if (sky == Sky.Drizzle) 26 else 46) {
            Offset(w * rnd.nextFloat(), h * (0.18f + rnd.nextFloat() * 0.85f)) to (0.18f + rnd.nextFloat() * 0.3f)
        } else emptyList()
        val flakes = if (sky == Sky.Snow) List(40) {
            Triple(Offset(w * rnd.nextFloat(), h * (0.12f + rnd.nextFloat() * 0.88f)), m * (0.008f + rnd.nextFloat() * 0.014f), 0.45f + rnd.nextFloat() * 0.5f)
        } else emptyList()
        // Bulut rengi: yağışlı / fırtınalı havada koyu, gece daha da koyu; açık havada beyazımsı.
        val cloudCol = when {
            sky == Sky.Storm -> if (night) c(0xFF2A3040) else c(0xFF4A5363)
            rainy || sky == Sky.Snow -> if (night) c(0xFF3A4558) else c(0xFFB9C3CF)
            sky == Sky.Cloudy -> if (night) c(0xFF4A5468) else c(0xFFE3E8EE)
            else -> if (night) c(0xFF5A6684) else Color.White
        }.let { if (dark) it.dim(0.8f) else it }
        fun puff(cx: Float, cy: Float, r: Float, a: Float) = Triple(Offset(cx, cy), r, a)
        val puffs = buildList {
            if (cloudy) {
                // Sağ üstte toplanan yumuşak bulut kümesi (yazılar solda kalır).
                add(puff(w * 0.78f, h * 0.10f, m * 0.42f, 0.55f))
                add(puff(w * 1.02f, h * 0.20f, m * 0.40f, 0.5f))
                add(puff(w * 0.55f, h * 0.02f, m * 0.36f, 0.45f))
                if (sky != Sky.PartlyCloudy) {
                    add(puff(w * 0.25f, h * 0.0f, m * 0.40f, 0.42f))
                    add(puff(w * 0.62f, h * 0.24f, m * 0.34f, 0.40f))
                }
            }
        }
        onDrawBehind {
            drawRect(bg)
            // Güneş parıltısı (gündüz) ve gün batımı ışığı (alacakaranlık).
            // Güneş belirgin bir daire değil, ışık olarak: bulutların ardından sızan sıcak parıltı (önce), bulutun ışığa
            // bakan kenarının parlaması ve hafif mercek parlaması (bulutlardan sonra, aşağıda).
            val sunny = phase == SkyPhase.Day && (sky == Sky.Clear || sky == Sky.PartlyCloudy)
            val sc = Offset(w * 0.80f, h * 0.10f)
            fun glow(o: Offset, s: Float, col: Color, a: Float) {
                // Gauss benzeri düşüş: kenarı olmayan yumuşak ışık.
                val r = s * 2f
                drawCircle(Brush.radialGradient(
                    0f to col.copy(alpha = a), 0.25f to col.copy(alpha = a * 0.78f), 0.5f to col.copy(alpha = a * 0.37f),
                    0.75f to col.copy(alpha = a * 0.10f), 1f to col.copy(alpha = 0f), center = o, radius = r,
                ), r, o)
            }
            if (sunny) {
                glow(sc, m * 0.5f, Color(0xFFFFE199), 0.5f)
                glow(sc, m * 0.2f, Color(0xFFFFF5D1), 0.55f)
            }
            if (phase == SkyPhase.Dusk) {
                val gc = Offset(w * 0.7f, h * 1.08f)
                val a = if (sky == Sky.Clear || sky == Sky.PartlyCloudy) 0.55f else 0.28f
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFA25E).copy(alpha = a), Color(0x00FFA25E)), gc, w * 1.0f), w * 1.0f, gc)
            }
            stars.forEach { (o, r, a) -> drawCircle(Color.White.copy(alpha = a), r, o) }
            if (night && (sky == Sky.Clear || sky == Sky.PartlyCloudy)) {
                val mc = Offset(w * 0.84f, h * 0.17f); val r = m * 0.085f
                drawCircle(Brush.radialGradient(listOf(Color(0x40DDE6FF), Color(0x00DDE6FF)), mc, m * 0.45f), m * 0.45f, mc)
                val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc.x - r, mc.y - r, mc.x + r, mc.y + r)) }
                val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc.x - r * 0.45f, mc.y - r * 1.15f, mc.x + r * 1.55f, mc.y + r * 0.85f)) }
                drawPath(Path().apply { op(disc, cut, androidx.compose.ui.graphics.PathOperation.Difference) }, Color(0xFFF4F1D2))
            }
            // Fırtına: bulutların ardında soğuk şimşek ışığı.
            if (sky == Sky.Storm) {
                val lc = Offset(w * 0.7f, h * 0.15f)
                drawCircle(Brush.radialGradient(listOf(Color(0x55C9C2FF), Color(0x00C9C2FF)), lc, m * 0.6f), m * 0.6f, lc)
            }
            val stormBolt = sky == Sky.Storm
            puffs.forEach { (o, r, a) ->
                drawCircle(Brush.radialGradient(listOf(cloudCol.copy(alpha = a), cloudCol.copy(alpha = a * 0.55f), cloudCol.copy(alpha = 0f)), o, r), r, o)
            }
            if (sunny) {
                glow(sc, m * 0.13f, Color(0xFFFFF7DB), 0.45f)
                // Hafif mercek parlaması: ışıktan sol alta doğru birkaç soluk halka.
                listOf(Triple(0.35f, 0.035f, 0.18f), Triple(0.55f, 0.02f, 0.14f), Triple(0.75f, 0.05f, 0.10f)).forEach { (k, rr, a) ->
                    val o = Offset(sc.x + (w * 0.2f - sc.x) * k, sc.y + (h * 0.9f - sc.y) * k)
                    glow(o, m * rr, Color(0xFFFFF2D9), a)
                }
            }
            if (sky == Sky.Fog) {
                // Sis: geniş, yumuşak ve yatay uzamış pus kütleleri (çizgi gibi görünmez).
                listOf(Triple(0.75f, 0.30f, 0.20f), Triple(0.25f, 0.62f, 0.16f), Triple(0.8f, 0.85f, 0.18f)).forEach { (fx, fy, a) ->
                    val cx = w * fx; val cy = h * fy
                    scale(2.2f, 0.55f, Offset(cx, cy)) {
                        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color.White.copy(alpha = 0f)), Offset(cx, cy), m * 0.45f), m * 0.45f, Offset(cx, cy))
                    }
                }
            }
            if (stormBolt) {
                // Bulutların altından inen ince şimşek.
                val bx = w * 0.80f; val by = h * 0.26f; val k = m * 0.22f
                val bolt = Path().apply {
                    moveTo(bx + k * 0.10f, by); lineTo(bx - k * 0.22f, by + k * 0.55f); lineTo(bx + k * 0.02f, by + k * 0.55f)
                    lineTo(bx - k * 0.16f, by + k * 1.05f); lineTo(bx + k * 0.30f, by + k * 0.42f); lineTo(bx + k * 0.06f, by + k * 0.42f); close()
                }
                drawCircle(Brush.radialGradient(listOf(Color(0x40FFF3B0), Color(0x00FFF3B0)), Offset(bx, by + k * 0.5f), k * 1.4f), k * 1.4f, Offset(bx, by + k * 0.5f))
                drawPath(bolt, Color(0xE6FFF1A8))
            }
            val len = m * (if (sky == Sky.Drizzle) 0.05f else 0.085f)
            val sw = m * (if (sky == Sky.Drizzle) 0.008f else 0.010f)
            // Yağış sol taraftaki yazıların üstünde soluklaşır (okunaklılık); sağda tam görünür.
            fun side(x: Float) = (0.25f + 0.75f * ((x / w - 0.3f) / 0.35f).coerceIn(0f, 1f))
            drops.forEach { (o, a) ->
                drawLine(Color(0xFFDCEBFF).copy(alpha = a * side(o.x)), o, Offset(o.x - len * 0.28f, o.y + len), sw, StrokeCap.Round)
            }
            flakes.forEach { (o, r, a) -> drawCircle(Color.White.copy(alpha = a * side(o.x)), r, o) }
            // Yazıların okunaklılığı için alt kısımda hafif gölge.
            drawRect(Brush.verticalGradient(listOf(Color(0x00000000), Color(0x1F000000)), h * 0.45f, h))
        }
    })
}
