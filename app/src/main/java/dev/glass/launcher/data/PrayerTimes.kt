package dev.glass.launcher.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/** Bir günün namaz vakitleri (İmsak, Güneş, Öğle, İkindi, Akşam, Yatsı) ve hicri tarihi. */
data class PrayerDay(
    val date: LocalDate,
    val times: List<Pair<String, LocalTime>>,
    val hijri: String,
)

data class PrayerState(val city: String, val today: PrayerDay, val tomorrow: PrayerDay?)

/**
 * Namaz vakitleri: AlAdhan, Diyanet İşleri hesap yöntemi (method=13), telefonun yaklaşık konumu. Bir ayın vakitleri
 * tek istekle alınır ve telefonda saklanır: ay boyunca internet gerekmez. Konum ~2 km'den fazla değişirse yenilenir.
 */
object PrayerRepo {
    private val _state = MutableStateFlow<PrayerState?>(null)
    val state: StateFlow<PrayerState?> = _state
    private val lock = kotlinx.coroutines.sync.Mutex()

    private val names = listOf("Fajr" to "İmsak", "Sunrise" to "Güneş", "Dhuhr" to "Öğle", "Asr" to "İkindi", "Maghrib" to "Akşam", "Isha" to "Yatsı")
    private val hijriMonths = listOf(
        "Muharrem", "Safer", "Rebiülevvel", "Rebiülahir", "Cemaziyelevvel", "Cemaziyelahir",
        "Recep", "Şaban", "Ramazan", "Şevval", "Zilkade", "Zilhicce",
    )

    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        lock.lock()
        try { refreshInner(ctx.applicationContext) } finally { lock.unlock() }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prayer", Context.MODE_PRIVATE)

    private suspend fun refreshInner(ctx: Context) {
        val p = prefs(ctx)
        val today = LocalDate.now()
        // Önce kayıtlı veri (hemen gösterilir).
        var lat = p.getFloat("lat", Float.NaN).toDouble()
        var lon = p.getFloat("lon", Float.NaN).toDouble()
        var city = p.getString("city", "").orEmpty()
        publish(ctx, today, city)
        val loc = WeatherRepo.locate(ctx)
        if (loc != null) {
            val moved = lat.isNaN() || distanceKm(lat, lon, loc.latitude, loc.longitude) > 2.0
            if (moved) {
                lat = loc.latitude; lon = loc.longitude
                city = runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(ctx, Locale("tr", "TR")).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { it.subAdminArea ?: it.locality ?: it.adminArea }
                }.getOrNull().orEmpty()
                p.edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).putString("city", city)
                    .remove(key(YearMonth.from(today))).remove(key(YearMonth.from(today.plusDays(1)))).apply()
            }
        }
        if (lat.isNaN()) return
        // Bu ayın ve (ay sonundaysa) gelecek ayın vakitleri.
        for (ym in listOf(YearMonth.from(today), YearMonth.from(today.plusDays(1))).distinct()) {
            if (p.getString(key(ym), null) != null) continue
            val body = fetch(ym, lat, lon) ?: continue
            p.edit().putString(key(ym), body).apply()
        }
        publish(ctx, today, city)
    }

    private fun key(ym: YearMonth) = "m_" + ym.toString()

    private fun fetch(ym: YearMonth, lat: Double, lon: Double): String? = runCatching {
        val url = "https://api.aladhan.com/v1/calendar/${ym.year}/${ym.monthValue}?latitude=" +
            "%.4f".format(Locale.US, lat) + "&longitude=" + "%.4f".format(Locale.US, lon) + "&method=13"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        val text = c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
        val arr = JSONObject(text).getJSONArray("data")
        if (arr.length() == 0) null else arr.toString()
    }.getOrNull()

    private fun publish(ctx: Context, today: LocalDate, city: String) {
        val p = prefs(ctx)
        val t = day(p.getString(key(YearMonth.from(today)), null), today) ?: return
        val tm = today.plusDays(1)
        val n = day(p.getString(key(YearMonth.from(tm)), null), tm)
        _state.value = PrayerState(city, t, n)
    }

    private fun day(json: String?, date: LocalDate): PrayerDay? = runCatching {
        val arr = JSONArray(json ?: return null)
        val want = "%02d-%02d-%04d".format(date.dayOfMonth, date.monthValue, date.year)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = o.getJSONObject("date")
            if (d.getJSONObject("gregorian").getString("date") != want) continue
            val tim = o.getJSONObject("timings")
            val times = names.map { (k, tr) ->
                val s = tim.getString(k).take(5)
                tr to LocalTime.of(s.substring(0, 2).toInt(), s.substring(3, 5).toInt())
            }
            val h = d.getJSONObject("hijri")
            val hm = h.getJSONObject("month").getInt("number")
            val hijri = "${h.getString("day").toInt()} ${hijriMonths.getOrElse(hm - 1) { "" }} ${h.getString("year")}"
            return PrayerDay(date, times, hijri)
        }
        null
    }.getOrNull()

    private fun distanceKm(a: Double, b: Double, c: Double, d: Double): Double {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(a, b, c, d, r)
        return r[0] / 1000.0
    }
}
