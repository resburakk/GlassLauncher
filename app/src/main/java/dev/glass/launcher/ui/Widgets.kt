package dev.glass.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val TR = Locale("tr", "TR")
private val Red = Color(0xFFFF453A)

/**
 * iOS 26 widget köşe yarıçapı: küçük (2×2) widget genişliğinin %17'si (ekran görüntüsünden ölçüldü; %60
 * yumuşaklıklı sürekli köşeyle). iOS'ta olduğu gibi küçük, orta ve büyük widget'ta aynıdır.
 */
@Composable
fun widgetCorner(): Dp = LocalHomeMetrics.current?.let { it.widgetWidth(2) * 0.17f } ?: 27.dp

/** iOS widget şekli: sürekli köşe (squircle, %60 yumuşaklık), simgelerle aynı köşe eğrisi. */
fun widgetShape(radius: Dp): androidx.compose.ui.graphics.Shape = SquircleShape(radius, 0.6f)

/** Saniye veya dakika başında yenilenen saat (yalnızca ekrandayken çalışır; arka planda pil harcamaz). */
@Composable
private fun rememberNow(everySecond: Boolean): LocalDateTime {
    val now by produceState(LocalDateTime.now(), everySecond) {
        while (true) {
            val n = LocalDateTime.now()
            value = n
            val waitMs = if (everySecond) 1000L - n.nano / 1_000_000 else (60 - n.second) * 1000L - n.nano / 1_000_000
            delay(waitMs.coerceAtLeast(50L))
        }
    }
    return now
}

/** Widget içindeki yazı ve çizgi renkleri (şeffaf/koyu zeminde beyaz, renkli açık zeminde koyu). */
internal class WidgetInk(
    val primary: Color, val secondary: Color, val dim: Color, val track: Color,
    /** Analog saat kadranı ve akrep/yelkovan. */
    val face: Color, val hand: Color,
)
private val GlassInk = WidgetInk(Color.White, Color(0xCCFFFFFF), Color(0x99FFFFFF), Color(0x33FFFFFF), Color(0xF2FFFFFF), Color(0xFF1C1C1E))
private val LightInk = WidgetInk(Color(0xFF1C1C1E), Color(0x991C1C1E), Color(0x801C1C1E), Color(0x1F1C1C1E), Color(0xFFF2F2F7), Color(0xFF1C1C1E))
private val DarkInk = WidgetInk(Color.White, Color(0xB3FFFFFF), Color(0x80FFFFFF), Color(0x33FFFFFF), Color(0xFF2C2C2E), Color.White)
internal val LocalWidgetInk = androidx.compose.runtime.staticCompositionLocalOf { GlassInk }

/** Widget görünümü: 0 şeffaf cam, 1 renkli (açık), 2 renkli koyu. */
@Composable
internal fun widgetFill(): Int = when (LocalLauncherSettings.current.widgetStyle) {
    "light" -> 1
    "dark" -> 2
    "auto" -> if (androidx.compose.foundation.isSystemInDarkTheme()) 2 else 1
    else -> 0
}

/** Bir widget'ın içeriği. Dış çerçeve (cam, köşe) burada çizilir. */
@Composable
fun WidgetBody(w: WidgetItem, vm: LauncherViewModel, width: Dp, height: Dp, strength: Float) {
    if (w.stack.isNotEmpty()) { WidgetStack(w, vm, width, height, strength); return }
    val shape = widgetShape(widgetCorner())
    when (w.kind) {
        WidgetKind.System -> SystemWidget(w, vm, width, height)
        // Cam saat zeminsizdir (iOS kilit ekranı saati gibi): rakamların kendisi cam.
        WidgetKind.GlassClock -> GlassClockWidget(w, width, height)
        WidgetKind.Photo -> Box(Modifier.size(width, height).clip(shape).background(Color(0x33000000))) {
            PhotoWidget(w, vm, width, height)
        }
        else -> {
            val fill = widgetFill()
            val weather = w.kind == WidgetKind.Weather
            val frame = if (fill == 0) {
                Modifier.size(width, height).liquidGlass(shape, strength, 0.dp, iconRim = true).background(Color(0x26000000))
            } else {
                // iOS 26 dolu widget: hava durumu gökyüzü renginde, diğerleri açık (beyaz) ya da koyu zemin.
                val bg = when {
                    // Hava durumu: zemini WeatherBackdrop çizer (hava türü ve gün evresine göre sahne).
                    weather -> SolidColor(Color.Transparent)
                    fill == 1 -> SolidColor(Color.White)
                    else -> SolidColor(Color(0xFF1C1C1E))
                }
                Modifier.size(width, height).contactShadow(shape, LocalLauncherSettings.current.glassContactShadow).clip(shape).background(bg)
            }
            val ink = when { fill == 0 || weather -> GlassInk; fill == 1 -> LightInk; else -> DarkInk }
            androidx.compose.runtime.CompositionLocalProvider(LocalWidgetInk provides ink) {
                Box(frame) {
                    if (weather && fill != 0) WeatherBackdrop(dark = fill == 2)
                    when (w.kind) {
                        WidgetKind.DigitalClock -> DigitalClockWidget(w)
                        WidgetKind.AnalogClock -> AnalogClockWidget(w)
                        WidgetKind.Calendar -> CalendarWidget(w)
                        WidgetKind.Battery -> BatteryWidget(w)
                        WidgetKind.Weather -> WeatherWidget(w)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Sistem widget'ı (Honor vb.) olduğu gibi gösterilir: kırpma, cam veya arka plan eklenmez. Widget'a
 * kapladığı alanın gerçek boyutu bildirilir; alan widget'ın en küçük boyutundan darsa widget o boyutta
 * çizilip orantılı küçültülür (en-boy oranı korunur, sıkışma ve kesilme olmaz).
 */
@Composable
private fun SystemWidget(w: WidgetItem, vm: LauncherViewModel, width: Dp, height: Dp) {
    val host = vm.widgetHost
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val info = remember(w.appWidgetId) { host.info(w.appWidgetId) }
    val (lw, lh, scale) = WidgetSizing.layoutSize(info, width, height, density)
    LaunchedEffect(w.appWidgetId, lw, lh) {
        host.updateSize(w.appWidgetId, lw.value, lh.value)
    }
    Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                host.view(w.appWidgetId) ?: TextView(ctx).apply {
                    text = "Widget yüklenemedi"
                    setTextColor(android.graphics.Color.WHITE)
                    textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                }
            },
            modifier = Modifier
                .requiredSize(lw, lh)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

@Composable
private fun DigitalClockWidget(w: WidgetItem) {
    val now = rememberNow(everySecond = false)
    val big = w.spanX >= 4
    val ink = LocalWidgetInk.current
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = if (big) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Text(
            now.format(DateTimeFormatter.ofPattern("HH:mm")), color = ink.primary,
            fontSize = if (big) 56.sp else 40.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            now.format(DateTimeFormatter.ofPattern("d MMMM EEEE", TR)), color = ink.secondary,
            fontSize = if (big) 17.sp else 13.sp, maxLines = 1,
        )
    }
}

@Composable
private fun AnalogClockWidget(w: WidgetItem) {
    val now = rememberNow(everySecond = true)
    val ink = LocalWidgetInk.current
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(ink.face, r, c)
            for (i in 0 until 60) {
                val a = Math.toRadians(i * 6.0)
                val major = i % 5 == 0
                val outer = r * 0.94f
                val inner = if (major) r * 0.80f else r * 0.88f
                drawLine(
                    if (major) ink.hand else ink.hand.copy(alpha = 0.4f),
                    Offset(c.x + (sin(a) * inner).toFloat(), c.y - (cos(a) * inner).toFloat()),
                    Offset(c.x + (sin(a) * outer).toFloat(), c.y - (cos(a) * outer).toFloat()),
                    strokeWidth = if (major) r * 0.035f else r * 0.015f,
                    cap = StrokeCap.Round,
                )
            }
            val h = now.hour % 12 + now.minute / 60f
            val m = now.minute + now.second / 60f
            rotate(h * 30f, c) {
                drawLine(ink.hand, c, Offset(c.x, c.y - r * 0.5f), r * 0.07f, StrokeCap.Round)
            }
            rotate(m * 6f, c) {
                drawLine(ink.hand, c, Offset(c.x, c.y - r * 0.75f), r * 0.05f, StrokeCap.Round)
            }
            rotate(now.second * 6f, c) {
                drawLine(Red, Offset(c.x, c.y + r * 0.15f), Offset(c.x, c.y - r * 0.82f), r * 0.02f, StrokeCap.Round)
            }
            drawCircle(Red, r * 0.05f, c)
        }
    }
}

@Composable
private fun CalendarWidget(w: WidgetItem) {
    val now = rememberNow(everySecond = false)
    val today = now.toLocalDate()
    val ink = LocalWidgetInk.current
    if (w.spanX < 4 && w.spanY < 4) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                today.dayOfWeek.getDisplayName(TextStyle.FULL, TR).uppercase(TR),
                color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            // Gün rakamı biraz küçük (önceden 52).
            Text("${today.dayOfMonth}", color = ink.primary, fontSize = 44.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.weight(1f))
            Text(
                today.month.getDisplayName(TextStyle.FULL, TR) + " " + today.year,
                color = ink.secondary, fontSize = 13.sp,
            )
        }
    } else {
        MonthGrid(today, Modifier.fillMaxSize().padding(14.dp))
    }
}

@Composable
private fun MonthGrid(today: LocalDate, modifier: Modifier) {
    val ym = YearMonth.from(today)
    val first = ym.atDay(1)
    val offset = (first.dayOfWeek.value - 1) // Pazartesi başlangıçlı
    val days = ym.lengthOfMonth()
    val ink = LocalWidgetInk.current
    Column(modifier) {
        Text(
            ym.month.getDisplayName(TextStyle.FULL, TR).uppercase(TR),
            color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        val names = listOf("Pt", "Sa", "Ça", "Pe", "Cu", "Ct", "Pz")
        Row(Modifier.fillMaxWidth()) {
            names.forEach {
                Text(it, color = ink.dim, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val cells = offset + days
        val weeks = (cells + 6) / 7
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            for (wk in 0 until weeks) {
                Row(Modifier.fillMaxWidth()) {
                    for (d in 0 until 7) {
                        val day = wk * 7 + d - offset + 1
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (day in 1..days) {
                                val isToday = day == today.dayOfMonth
                                Box(
                                    Modifier.size(24.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (isToday) Red else Color.Transparent),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$day", color = if (isToday) Color.White else ink.primary, fontSize = 12.sp,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryWidget(w: WidgetItem) {
    val ctx = LocalContext.current
    var level by remember { mutableStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    // Pil durumu yalnızca widget ekrandayken dinlenir (yapışkan yayın; sürekli servis yok).
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i ?: return
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                level = if (l < 0) -1 else l * 100 / s
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val sticky = ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        sticky?.let { receiver.onReceive(ctx, it) }
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    val pct = level.coerceIn(0, 100)
    val ink = LocalWidgetInk.current
    val color = when {
        charging -> Color(0xFF30D158)
        pct <= 20 -> Red
        else -> Color(0xFF30D158)
    }
    Row(
        Modifier.fillMaxSize().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (w.spanX >= 4) Arrangement.Start else Arrangement.Center,
    ) {
        Box(Modifier.size(if (w.spanX >= 4) 96.dp else 110.dp), contentAlignment = Alignment.Center) {
            val stroke = with(LocalDensity.current) { 9.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(ink.track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(color, -90f, 360f * pct / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(if (level < 0) "–" else "%$pct", color = ink.primary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (w.spanX >= 4) {
            Spacer(Modifier.width(18.dp))
            Column {
                Text("Telefon", color = ink.primary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (charging) "Şarj oluyor" else "Pil",
                    color = ink.secondary, fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun PhotoWidget(w: WidgetItem, vm: LauncherViewModel, width: Dp, height: Dp) {
    val maxSide = with(LocalDensity.current) { maxOf(width, height).roundToPx() }
    val img by produceState<ImageBitmap?>(null, w.data, maxSide) {
        value = if (w.data.isEmpty()) null else vm.loadPhoto(w.data, maxSide)
    }
    val bmp = img
    if (bmp != null) {
        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlyphIcon(Glyph.Wallpaper, Color.White, 34.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Fotoğraf seçmek için basılı tutun", color = Color(0xCCFFFFFF), fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}


/**
 * Widget yığını (iOS Akıllı Yığın): widget'lar aynı yerde üst üste; dikey kaydırarak geçilir, sağ kenarda
 * hangi widget'ta olunduğunu gösteren noktalar kısa süre görünür.
 */
@Composable
private fun WidgetStack(w: WidgetItem, vm: LauncherViewModel, width: Dp, height: Dp, strength: Float) {
    val pages = remember(w) { listOf(w.copy(stack = emptyList())) + w.stack }
    val state = androidx.compose.foundation.pager.rememberPagerState { pages.size }
    var showDots by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(state.currentPage, state.isScrollInProgress) {
        showDots = true
        if (!state.isScrollInProgress) { delay(1800); showDots = false }
    }
    val dotsAlpha by androidx.compose.animation.core.animateFloatAsState(if (showDots) 1f else 0f, label = "stackDots")
    Box(Modifier.size(width, height)) {
        androidx.compose.foundation.pager.VerticalPager(
            state = state,
            modifier = Modifier.size(width, height),
            beyondViewportPageCount = 1,
            key = { pages[it].id },
        ) { i -> WidgetBody(pages[i], vm, width, height, strength) }
        androidx.compose.foundation.layout.Column(
            Modifier.align(Alignment.CenterEnd).offset(x = 10.dp).graphicsLayer { alpha = dotsAlpha },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
            pages.indices.forEach { i ->
                Box(
                    Modifier.size(5.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (i == state.currentPage) Color.White else Color(0x66FFFFFF))
                )
            }
        }
    }
}


/**
 * Widget'ın altındaki ad (iOS gibi uygulamanın adı): launcher widget'larında ilgili uygulama, telefon
 * widget'larında widget'ı sağlayan uygulamanın adı.
 */
@Composable
fun rememberWidgetCaption(w: WidgetItem, vm: LauncherViewModel): String {
    val ctx = LocalContext.current
    return remember(w.kind, w.appWidgetId) {
        when (w.kind) {
            WidgetKind.DigitalClock, WidgetKind.AnalogClock, WidgetKind.GlassClock -> "Saat"
            WidgetKind.Calendar -> "Takvim"
            WidgetKind.Battery -> "Pil"
            WidgetKind.Photo -> "Fotoğraf"
            WidgetKind.Weather -> "Hava Durumu"
            WidgetKind.System -> runCatching {
                val info = vm.widgetHost.info(w.appWidgetId)
                val pm = ctx.packageManager
                info?.provider?.packageName?.let { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }
            }.getOrNull() ?: "Widget"
        }
    }
}
