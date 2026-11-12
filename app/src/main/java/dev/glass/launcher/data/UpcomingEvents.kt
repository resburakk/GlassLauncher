package dev.glass.launcher.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Takvimdeki bir etkinlik (tekrarlayanlar her tekrarı için ayrı). Tüm gün etkinliklerinde [day] yerel gündür. */
data class CalEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String,
) {
    /** Etkinliğin başladığı yerel gün. */
    val day: LocalDate
        get() = if (allDay) Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).toLocalDate()
}

/** Yaklaşan etkinlikler (önümüzdeki 14 gün). Yalnızca okunur; hiçbir yere gönderilmez. */
object UpcomingEvents {
    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun load(ctx: Context, max: Int = 12): List<CalEvent>? = withContext(Dispatchers.IO) {
        if (!granted(ctx)) return@withContext null
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - 24L * 3600_000L)
        ContentUris.appendId(b, now + 14L * 24 * 3600_000L)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = ArrayList<CalEvent>()
        runCatching {
            ctx.contentResolver.query(b.build(), proj, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                val today = LocalDate.now()
                while (c.moveToNext()) {
                    val e = CalEvent(
                        id = c.getLong(0), title = c.getString(1).orEmpty().ifBlank { "(Başlıksız)" },
                        begin = c.getLong(2), end = c.getLong(3), allDay = c.getInt(4) == 1,
                        color = c.getInt(5), location = c.getString(6).orEmpty(),
                    )
                    // Bitmiş etkinlikler gösterilmez; tüm gün etkinliği bugünse kalır.
                    if (e.allDay) { if (e.day.isBefore(today)) continue } else if (e.end <= now) continue
                    out += e
                    if (out.size >= max) break
                }
            }
        }
        out
    }
}
