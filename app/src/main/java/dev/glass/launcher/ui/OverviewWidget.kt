package dev.glass.launcher.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.CalEvent
import dev.glass.launcher.data.Sky
import dev.glass.launcher.data.UpcomingEvents
import dev.glass.launcher.data.WidgetItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.ChronoField
import java.util.Locale

private val OTR = Locale("tr", "TR")
private val ORed = Color(0xFFFF453A)
private val OOrange = Color(0xFFFF9F0A)
private val OShortDays = arrayOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
private val OHijriMonths = listOf(
    "Muharrem", "Safer", "Rebiülevvel", "Rebiülahir", "Cemaziyelevvel", "Cemaziyelahir",
    "Recep", "Şaban", "Ramazan", "Şevval", "Zilkade", "Zilhicce",
)

private fun greeting(h: Int) = when (h) {
    in 5..11 -> "Günaydın"
    in 12..17 -> "İyi günler"
    in 18..21 -> "İyi akşamlar"
    else -> "İyi geceler"
}

private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

/** Hicri tarih (cihazda hesaplanır, internet gerekmez). */
private fun hijri(d: LocalDate): String = runCatching {
    val h = HijrahDate.from(d)
    "${h.get(ChronoField.DAY_OF_MONTH)} ${OHijriMonths[h.get(ChronoField.MONTH_OF_YEAR) - 1]} ${h.get(ChronoField.YEAR)}"
}.getOrDefault("")

private fun eventTime(e: CalEvent, today: LocalDate): String {
    val day = when (e.day) {
        today -> ""
        today.plusDays(1) -> "Yarın "
        else -> e.day.format(DateTimeFormatter.ofPattern("d MMM ", OTR))
    }
    if (e.allDay) return day + "Tüm gün"
    val z = ZoneId.systemDefault()
    val f = DateTimeFormatter.ofPattern("HH:mm")
    return day + Instant.ofEpochMilli(e.begin).atZone(z).format(f) + "–" + Instant.ofEpochMilli(e.end).atZone(z).format(f)
}

/**
 * "Gün Özeti" (4×3 / 4×4): selamlama ve hava durumu, büyük tarih (hicri tarih ile), yaklaşan etkinlikler ve ay takvimi;
 * 4×4'te altta 6 günlük tahmin. Hava verisi Hava Durumu widget'ıyla aynı kaynaktan, etkinlikler takvimden gelir.
 */
@Composable
fun OverviewWidget(w: WidgetItem) {
    val ctx = LocalContext.current
    val ink = LocalWidgetInk.current
    val tick = rememberMinuteTick()
    val weather = rememberWeather()
    val today = remember(tick) { LocalDate.now() }
    val hour = remember(tick) { LocalTime.now().hour }
    val big = w.spanY >= 4
    val huge = w.spanY >= 5

    var granted by remember { mutableStateOf(UpcomingEvents.granted(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val events by produceState<List<CalEvent>>(emptyList(), tick, granted) {
        value = if (granted) UpcomingEvents.load(ctx, if (huge) 5 else 3).orEmpty() else emptyList()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        // ---- Üst: selamlama + hava (sol), büyük tarih (sağ)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(greeting(hour), color = ink.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont, maxLines = 1)
                if (weather != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${weather.temp}°", color = ink.primary, fontSize = 38.sp, fontWeight = FontWeight.Light, fontFamily = AppFont, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        SkyGlyph(weather.sky, weather.day, 28.dp)
                    }
                    Text(
                        "${weather.sky.title} · ${weather.city}", color = ink.secondary, fontSize = 12.sp, fontFamily = AppFont,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (weather.sunrise >= 0 && weather.sunset >= 0) {
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SunPill(Sky.Clear, true, hm(weather.sunrise))
                            SunPill(Sky.Clear, false, hm(weather.sunset))
                        }
                    }
                } else {
                    Text("Hava durumu yükleniyor…", color = ink.dim, fontSize = 12.sp, fontFamily = AppFont, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    today.month.getDisplayName(JTextStyle.FULL, OTR).uppercase(OTR), color = ORed, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = AppFont, maxLines = 1,
                )
                Text(
                    "${today.dayOfMonth}", fontSize = 50.sp, fontWeight = FontWeight.Bold, fontFamily = AppFont, maxLines = 1,
                    style = TextStyle(brush = Brush.verticalGradient(listOf(ink.primary, ink.primary.copy(alpha = 0.35f))), lineHeight = 52.sp),
                )
                Text(
                    today.dayOfWeek.getDisplayName(JTextStyle.FULL, OTR), color = ink.secondary, fontSize = 12.sp,
                    fontFamily = AppFont, maxLines = 1,
                )
                val hj = remember(today) { hijri(today) }
                if (hj.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hj, color = ink.secondary, fontSize = 10.sp, fontFamily = AppFont, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(ink.track).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // ---- 4×5: önümüzdeki saatler
        if (huge && weather != null && weather.hours.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ink.track).padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                weather.hours.take(6).forEachIndexed { i, h ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (i == 0) "Şimdi" else "%02d".format(h.hour), color = ink.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        SkyGlyph(h.sky, h.day, 20.dp)
                        Spacer(Modifier.height(3.dp))
                        Text("${h.temp}°", color = ink.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Orta: yaklaşan etkinlikler (sol) + ay takvimi (sağ)
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("YAKLAŞAN", color = ink.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AppFont)
                when {
                    !granted -> Text(
                        "Takvim izni ver", color = ink.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(ink.track)
                            .clickable { perm.launch(Manifest.permission.READ_CALENDAR) }.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    events.isEmpty() -> Text("Yaklaşan etkinlik yok", color = ink.secondary, fontSize = 12.sp, fontFamily = AppFont)
                    else -> events.forEach { e ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val uri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.id)
                                runCatching {
                                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                        ) {
                            Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(Color(e.color or 0xFF000000.toInt())))
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text(e.title.ifBlank { "Etkinlik" }, color = ink.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(eventTime(e, today), color = ink.secondary, fontSize = 11.sp, fontFamily = AppFont, maxLines = 1)
                            }
                        }
                    }
                }
            }
            MonthGrid(today, Modifier.weight(1f).fillMaxHeight(), header = false)
        }

        // ---- Alt (4×4): şu anki hava + 6 günlük tahmin
        if (big && weather != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.track))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 10.dp)) {
                    SkyGlyph(weather.sky, weather.day, 34.dp)
                    Text("${weather.temp}°", color = ink.primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
                    Text("${weather.min}° • ${weather.max}°", color = ink.secondary, fontSize = 10.sp, fontFamily = AppFont, maxLines = 1)
                }
                val days = (if (weather.days.size > 6) weather.days.drop(1) else weather.days).take(6)
                days.forEach { d ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(OShortDays[(d.dayOfWeek - 1).coerceIn(0, 6)], color = OOrange, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
                        Spacer(Modifier.height(3.dp))
                        SkyGlyph(d.sky, true, 22.dp)
                        Spacer(Modifier.height(3.dp))
                        Text("${d.max}°", color = ink.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont, maxLines = 1)
                        Text("${d.min}°", color = ink.dim, fontSize = 10.sp, fontFamily = AppFont, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SunPill(sky: Sky, day: Boolean, text: String) {
    val ink = LocalWidgetInk.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(ink.track).padding(start = 5.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        SkyGlyph(sky, day, 13.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, color = ink.secondary, fontSize = 10.sp, fontFamily = AppFont, maxLines = 1)
    }
}
