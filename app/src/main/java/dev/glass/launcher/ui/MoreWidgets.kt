package dev.glass.launcher.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import dev.glass.launcher.data.CalEvent
import dev.glass.launcher.data.PrayerRepo
import dev.glass.launcher.data.UpcomingEvents
import dev.glass.launcher.data.WeatherRepo
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.service.NowPlaying
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TRL = Locale("tr", "TR")
private val WRed = Color(0xFFFF453A)

/** Launcher öndeyken her dakika başında artan sayaç (widget'lar zamana göre yenilensin; saniyelik çizim yok). */
@Composable
private fun rememberMinuteTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tick++
                val n = LocalDateTime.now()
                delay(((60 - n.second) * 1000L - n.nano / 1_000_000).coerceAtLeast(200L))
            }
        }
    }
    return tick
}

@Composable
private fun PermissionPrompt(glyph: Glyph, text: String, button: String, onClick: () -> Unit) {
    val ink = LocalWidgetInk.current
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(glyph, ink.primary, 30.dp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = ink.secondary, fontSize = 12.sp, fontFamily = AppFont, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            button, color = ink.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(ink.track).clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// ---------------------------------------------------------------- Uygulama Önerileri

/**
 * iOS Siri Önerileri gibi: o saatte en çok açtığın uygulamalar (launcher'ın kendi kullanım istatistiği; dışarı gitmez).
 * Küçük 2×2 simge, orta 4×2, büyük 4×4 (adlarıyla).
 */
@Composable
fun SuggestionsWidget(w: WidgetItem, vm: LauncherViewModel, strength: Float) {
    val apps by vm.suggestedApps.collectAsStateWithLifecycle()
    val st = LocalLauncherSettings.current
    val ink = LocalWidgetInk.current
    val cols = if (w.spanX >= 4) 4 else 2
    val rows = if (w.spanY >= 4) 4 else 2
    val labels = w.spanY >= 4
    val list = apps.take(cols * rows)
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
            Text("Uygulama kullandıkça öneriler burada görünür", color = ink.secondary, fontSize = 13.sp, fontFamily = AppFont,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp)) {
        val cellW = maxWidth / cols
        val cellH = maxHeight / rows
        val labelH = if (labels) 16.dp else 0.dp
        val icon = minOf(cellW * 0.80f, (cellH - labelH) * 0.86f)
        Column(Modifier.fillMaxSize()) {
            repeat(rows) { r ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    repeat(cols) { c ->
                        val a = list.getOrNull(r * cols + c)
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (a != null) Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { vm.launch(a) },
                            ) {
                                AppIconImage(a, vm, icon, st.iconGlass, strength, badge = false)
                                if (labels) Text(
                                    a.label, color = ink.primary, fontSize = 11.sp, fontFamily = AppFont, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(cellW).padding(top = 3.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Yaklaşan Etkinlikler

private fun timeText(e: CalEvent): String {
    if (e.allDay) return "Tüm gün"
    val z = ZoneId.systemDefault()
    val f = DateTimeFormatter.ofPattern("HH:mm")
    val a = Instant.ofEpochMilli(e.begin).atZone(z); val b = Instant.ofEpochMilli(e.end).atZone(z)
    return "${a.format(f)}–${b.format(f)}"
}

private fun dayTitle(d: LocalDate): String {
    val t = LocalDate.now()
    return when (d) {
        t -> "Bugün"
        t.plusDays(1) -> "Yarın"
        else -> d.format(DateTimeFormatter.ofPattern("d MMMM EEEE", TRL))
    }
}

/**
 * iOS Takvim "Sıradaki" widget'ı: üstte bugünün günü, altında yaklaşan etkinlikler (renk çubuğu, başlık, saat).
 * Etkinliğe dokununca takvimde açılır.
 */
@Composable
fun EventsWidget(w: WidgetItem) {
    val ctx = LocalContext.current
    val ink = LocalWidgetInk.current
    val tick = rememberMinuteTick()
    var granted by remember { mutableStateOf(UpcomingEvents.granted(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        PermissionPrompt(Glyph.Doc, "Yaklaşan etkinlikler için takvim izni gerekir", "Takvim izni ver") { perm.launch(Manifest.permission.READ_CALENDAR) }
        return
    }
    val events by androidx.compose.runtime.produceState<List<CalEvent>>(emptyList(), tick) {
        value = UpcomingEvents.load(ctx).orEmpty()
    }
    val today = LocalDate.now()
    val header: @Composable (Boolean) -> Unit = { big ->
        Column {
            Text(today.format(DateTimeFormatter.ofPattern("EEEE", TRL)).uppercase(TRL), color = WRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = AppFont)
            Text("${today.dayOfMonth}", color = ink.primary, fontSize = if (big) 40.sp else 34.sp, fontWeight = FontWeight.Light, fontFamily = AppFont)
        }
    }
    val open: (CalEvent) -> Unit = { e ->
        runCatching {
            val uri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.id)
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, e.end)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    @Composable
    fun EventRow(e: CalEvent, compact: Boolean) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open(e) }.padding(vertical = if (compact) 2.dp else 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(if (compact) 28.dp else 32.dp).clip(RoundedCornerShape(2.dp)).background(Color(e.color or 0xFF000000.toInt())))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, color = ink.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timeText(e), color = ink.secondary, fontSize = 12.sp, fontFamily = AppFont, maxLines = 1)
            }
        }
    }
    val empty: @Composable () -> Unit = {
        Text("Yaklaşan etkinlik yok", color = ink.secondary, fontSize = 13.sp, fontFamily = AppFont)
    }
    when {
        w.spanX < 4 -> Column(Modifier.fillMaxSize().padding(14.dp)) {
            header(false)
            Spacer(Modifier.weight(1f))
            val e = events.firstOrNull()
            if (e == null) empty() else {
                if (e.day != today) Text(dayTitle(e.day), color = ink.dim, fontSize = 11.sp, fontFamily = AppFont)
                EventRow(e, compact = true)
            }
        }
        w.spanY < 4 -> Row(Modifier.fillMaxSize().padding(14.dp)) {
            Column(Modifier.width(92.dp)) {
                header(true)
                Spacer(Modifier.weight(1f))
                Text(today.format(DateTimeFormatter.ofPattern("MMMM", TRL)), color = ink.dim, fontSize = 12.sp, fontFamily = AppFont)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                if (events.isEmpty()) empty()
                events.take(3).forEach { e -> EventRow(e, compact = true) }
            }
        }
        else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                header(true)
                Spacer(Modifier.width(10.dp))
                Text(today.format(DateTimeFormatter.ofPattern("MMMM yyyy", TRL)), color = ink.dim, fontSize = 13.sp, fontFamily = AppFont, modifier = Modifier.padding(bottom = 9.dp))
            }
            Spacer(Modifier.height(6.dp))
            if (events.isEmpty()) empty()
            var last: LocalDate? = null
            events.take(6).forEach { e ->
                if (e.day != last) {
                    last = e.day
                    Text(dayTitle(e.day), color = ink.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                }
                EventRow(e, compact = false)
            }
        }
    }
}

// ---------------------------------------------------------------- Şimdi Çalıyor

private fun androidx.compose.ui.graphics.drawscope.DrawScope.playGlyph(c: Color, playing: Boolean) {
    val s = size.minDimension
    if (playing) {
        val bw = s * 0.22f
        drawRoundRect(c, Offset(s * 0.22f, s * 0.15f), androidx.compose.ui.geometry.Size(bw, s * 0.7f), androidx.compose.ui.geometry.CornerRadius(bw * 0.3f))
        drawRoundRect(c, Offset(s * 0.56f, s * 0.15f), androidx.compose.ui.geometry.Size(bw, s * 0.7f), androidx.compose.ui.geometry.CornerRadius(bw * 0.3f))
    } else {
        drawPath(Path().apply { moveTo(s * 0.25f, s * 0.12f); lineTo(s * 0.88f, s * 0.5f); lineTo(s * 0.25f, s * 0.88f); close() }, c)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.skipGlyph(c: Color, forward: Boolean) {
    val s = size.minDimension
    fun tri(x0: Float) = Path().apply {
        if (forward) { moveTo(x0, s * 0.2f); lineTo(x0 + s * 0.42f, s * 0.5f); lineTo(x0, s * 0.8f) }
        else { moveTo(x0 + s * 0.42f, s * 0.2f); lineTo(x0, s * 0.5f); lineTo(x0 + s * 0.42f, s * 0.8f) }
        close()
    }
    drawPath(tri(s * 0.08f), c); drawPath(tri(s * 0.5f), c)
}

@Composable
private fun MediaButton(size: Dp, onClick: () -> Unit, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Box(
        Modifier.size(size * 1.5f).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size)) { draw() } }
}

/**
 * iOS Müzik widget'ı gibi: çalan şarkının kapağı, adı, sanatçısı ve oynat/duraklat/önceki/sonraki. Spotify, YouTube
 * Music gibi her medya uygulamasıyla çalışır. Kapağa dokununca çalan uygulama açılır.
 */
@Composable
fun MusicWidget(w: WidgetItem) {
    val ctx = LocalContext.current
    val ink = LocalWidgetInk.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(dev.glass.launcher.service.BadgeListener.isGranted(ctx)) }
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            granted = dev.glass.launcher.service.BadgeListener.isGranted(ctx)
            NowPlaying.start(ctx.applicationContext)
        }
    }
    if (!granted) {
        PermissionPrompt(Glyph.Mic, "Çalan şarkıyı göstermek için bildirim erişimi gerekir", "Bildirim erişimini aç") {
            runCatching {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return
    }
    val s by NowPlaying.state.collectAsStateWithLifecycle()
    val np = s
    if (np == null) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(30.dp)) { playGlyph(ink.dim, false) }
            Spacer(Modifier.height(8.dp))
            Text("Şu an çalan bir şey yok", color = ink.secondary, fontSize = 13.sp, fontFamily = AppFont)
        }
        return
    }
    val artImg = remember(np.art) { np.art?.asImageBitmap() }
    val art: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(10.dp)).background(ink.track).clickable { NowPlaying.open(ctx) }, contentAlignment = Alignment.Center) {
            if (artImg != null) Image(artImg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Canvas(Modifier.size(26.dp)) { playGlyph(ink.dim, false) }
        }
    }
    val texts: @Composable (Boolean) -> Unit = { big ->
        Text(np.title, color = ink.primary, fontSize = if (big) 16.sp else 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (np.artist.isNotBlank()) Text(np.artist, color = ink.secondary, fontSize = if (big) 14.sp else 12.sp, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val controls: @Composable (Dp) -> Unit = { sz ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaButton(sz * 0.8f, { NowPlaying.previous() }) { skipGlyph(ink.primary, false) }
            Spacer(Modifier.width(4.dp))
            MediaButton(sz, { NowPlaying.toggle() }) { playGlyph(ink.primary, np.playing) }
            Spacer(Modifier.width(4.dp))
            MediaButton(sz * 0.8f, { NowPlaying.next() }) { skipGlyph(ink.primary, true) }
        }
    }
    when {
        w.spanX < 4 -> Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                art(Modifier.size(62.dp))
                Spacer(Modifier.weight(1f))
                MediaButton(22.dp, { NowPlaying.toggle() }) { playGlyph(ink.primary, np.playing) }
            }
            Spacer(Modifier.weight(1f))
            texts(false)
        }
        w.spanY < 4 -> Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            art(Modifier.fillMaxHeight().aspectRatio(1f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                texts(true)
                Spacer(Modifier.height(8.dp))
                controls(24.dp)
            }
        }
        else -> Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            art(Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth()) { texts(true) }
            Spacer(Modifier.height(6.dp))
            controls(28.dp)
        }
    }
}

// ---------------------------------------------------------------- Namaz Vakitleri

private fun remainText(mins: Long): String {
    val h = mins / 60; val m = mins % 60
    return when { h > 0 && m > 0 -> "$h sa $m dk"; h > 0 -> "$h sa"; else -> "$m dk" }
}

/**
 * Namaz vakitleri (Diyanet hesabı): sıradaki vakit ve kalan süre, günün vakitleri; şu anki vakit vurgulanır.
 * Kalan süre dakikada bir güncellenir.
 */
@Composable
fun PrayerWidget(w: WidgetItem) {
    val ctx = LocalContext.current
    val ink = LocalWidgetInk.current
    val scope = rememberCoroutineScope()
    val tick = rememberMinuteTick()
    var granted by remember { mutableStateOf(WeatherRepo.hasPermission(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) scope.launch { PrayerRepo.refresh(ctx) }
    }
    LaunchedEffect(tick / 30, granted) { if (granted) PrayerRepo.refresh(ctx) } // en çok yarım saatte bir (ay önbelleği var)
    if (!granted) {
        PermissionPrompt(Glyph.Globe, "Bulunduğun yerin vakitleri için konum izni gerekir", "Konum izni ver") { perm.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
        return
    }
    val st by PrayerRepo.state.collectAsStateWithLifecycle()
    val s = st
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Vakitler alınıyor…", color = ink.secondary, fontSize = 13.sp, fontFamily = AppFont)
        }
        return
    }
    @Suppress("UNUSED_VARIABLE") val t0 = tick // dakikada bir yeniden hesapla
    val now = LocalTime.now()
    val times = s.today.times
    val nextIdx = times.indexOfFirst { it.second.isAfter(now) }
    val (nextName, nextTime) = if (nextIdx >= 0) times[nextIdx] else (s.tomorrow?.times?.firstOrNull() ?: times.first())
    val curIdx = if (nextIdx < 0) times.lastIndex else nextIdx - 1 // -1: gece yarısından İmsak'a kadar (Yatsı sürer)
    val mins = run {
        val n = LocalDateTime.now()
        val target = if (nextIdx >= 0) n.toLocalDate().atTime(nextTime) else n.toLocalDate().plusDays(1).atTime(nextTime)
        java.time.Duration.between(n, target).toMinutes().coerceAtLeast(0) + 1
    }
    val f = DateTimeFormatter.ofPattern("HH:mm")
    val accent = Color(0xFF30D158)
    val headline: @Composable (Boolean) -> Unit = { big ->
        Text(s.city.ifEmpty { "Konumum" }, color = ink.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(nextName, color = ink.primary, fontSize = if (big) 30.sp else 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
        Text("${nextTime.format(f)} · ${remainText(mins)} kaldı", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
    }
    @Composable
    fun TimeCell(i: Int, modifier: Modifier) {
        val (n, t) = times[i]
        val cur = i == curIdx || (curIdx < 0 && i == times.lastIndex)
        Column(
            modifier.clip(RoundedCornerShape(8.dp)).background(if (cur) ink.track else Color.Transparent).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(n, color = if (cur) ink.primary else ink.secondary, fontSize = 11.sp, fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal, fontFamily = AppFont, maxLines = 1)
            Text(t.format(f), color = ink.primary, fontSize = 13.sp, fontWeight = if (cur) FontWeight.Bold else FontWeight.Medium, fontFamily = AppFont)
        }
    }
    when {
        w.spanX < 4 -> Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text(s.city.ifEmpty { "Konumum" }, color = ink.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text("Sıradaki", color = ink.dim, fontSize = 11.sp, fontFamily = AppFont)
            Text(nextName, color = ink.primary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
            Text(nextTime.format(f), color = ink.primary, fontSize = 18.sp, fontFamily = AppFont)
            Text("${remainText(mins)} kaldı", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
        }
        w.spanY < 4 -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { headline(false) }
                Text(s.today.hijri, color = ink.dim, fontSize = 11.sp, fontFamily = AppFont)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth()) { times.indices.forEach { i -> TimeCell(i, Modifier.weight(1f)) } }
        }
        else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            headline(true)
            Text("${s.today.date.format(DateTimeFormatter.ofPattern("d MMMM EEEE", TRL))} · ${s.today.hijri}", color = ink.dim, fontSize = 12.sp, fontFamily = AppFont, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                times.forEachIndexed { i, (n, t) ->
                    val cur = i == curIdx || (curIdx < 0 && i == times.lastIndex)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (cur) ink.track else Color.Transparent).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(n, color = ink.primary, fontSize = 15.sp, fontWeight = if (cur) FontWeight.Bold else FontWeight.Medium, fontFamily = AppFont, modifier = Modifier.weight(1f))
                        Text(t.format(f), color = ink.primary, fontSize = 15.sp, fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal, fontFamily = AppFont)
                    }
                }
            }
        }
    }
}
