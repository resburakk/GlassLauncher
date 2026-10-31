package dev.glass.launcher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Hava durumu türü (Open-Meteo WMO kodlarından). */
enum class Sky(val title: String) {
    Clear("Açık"), PartlyCloudy("Parçalı Bulutlu"), Cloudy("Bulutlu"), Fog("Sisli"),
    Drizzle("Çisenti"), Rain("Yağmurlu"), Snow("Karlı"), Storm("Gök Gürültülü");

    companion object {
        fun of(code: Int): Sky = when (code) {
            0 -> Clear
            1, 2 -> PartlyCloudy
            3 -> Cloudy
            45, 48 -> Fog
            51, 53, 55, 56, 57 -> Drizzle
            61, 63, 65, 66, 67, 80, 81, 82 -> Rain
            71, 73, 75, 77, 85, 86 -> Snow
            95, 96, 99 -> Storm
            else -> Cloudy
        }
    }
}

data class HourWeather(val hour: Int, val temp: Int, val sky: Sky, val day: Boolean)
data class DayWeather(val dayOfWeek: Int, val min: Int, val max: Int, val sky: Sky)

data class WeatherNow(
    val city: String,
    val temp: Int,
    val sky: Sky,
    val day: Boolean,
    val min: Int,
    val max: Int,
    val hours: List<HourWeather>,
    val days: List<DayWeather>,
    val updatedAt: Long,
    /** Bugünün gün doğumu / batımı (günün dakikası); bilinmiyorsa -1. Widget'ın gün batımı ve gece görünümü için. */
    val sunrise: Int = -1,
    val sunset: Int = -1,
)

/**
 * Hava durumu (iOS Hava Durumu widget'ı ve canlı simge için). Veri Open-Meteo'dan gelir (anahtar gerektirmez,
 * kişisel veri göndermez; yalnızca yaklaşık konum koordinatı). Konum telefonun son bilinen yaklaşık konumudur.
 * Launcher öndeyken en çok 30 dakikada bir yenilenir; son veri kaydedilir, açılışta hemen gösterilir.
 */
object WeatherRepo {
    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now
    @Volatile private var loadedCache = false
    private val lock = kotlinx.coroutines.sync.Mutex()

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Kaydedilmiş son veriyi yükler ve gerekiyorsa yeniler. */
    suspend fun refresh(ctx: Context, force: Boolean = false) = lock.withLockSafe { refreshInner(ctx, force) }

    private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockSafe(block: suspend () -> T): T {
        lock(); try { return block() } finally { unlock() }
    }

    /** Son bilinen konum yoksa (uzun süre konum kullanılmadıysa) ağdan tek seferlik yaklaşık konum istenir. */
    @Suppress("MissingPermission")
    private suspend fun currentLocation(lm: LocationManager): android.location.Location? =
        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.FUSED_PROVIDER
                runCatching {
                    lm.getCurrentLocation(provider, null, java.util.concurrent.Executors.newSingleThreadExecutor()) { l ->
                        if (cont.isActive) cont.resumeWith(Result.success(l))
                    }
                }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
            }
        }

    /** Telefonun yaklaşık konumu (son bilinen; yoksa tek seferlik). Namaz vakitleri widget'ı da kullanır. */
    suspend fun locate(ctx: Context): android.location.Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            @Suppress("MissingPermission")
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull() ?: currentLocation(lm)
    }

    private suspend fun refreshInner(ctx: Context, force: Boolean) = withContext(Dispatchers.IO) {
        if (!loadedCache) {
            loadedCache = true
            runCatching { ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("last", null)?.let { parse(JSONObject(it)) } }
                .getOrNull()?.let { _now.value = it }
        }
        val cur = _now.value
        if (!force && cur != null && System.currentTimeMillis() - cur.updatedAt < 30 * 60_000L) return@withContext
        if (!hasPermission(ctx)) return@withContext
        val loc = locate(ctx) ?: return@withContext
        val lat = "%.3f".format(Locale.US, loc.latitude)
        val lon = "%.3f".format(Locale.US, loc.longitude)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
            "&timezone=auto&forecast_days=6"
        val body = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
        }.getOrNull() ?: return@withContext
        val city = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale("tr", "TR")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let {
                it.subAdminArea ?: it.locality ?: it.adminArea
            }
        }.getOrNull().orEmpty()
        val o = runCatching { JSONObject(body).put("_city", city).put("_at", System.currentTimeMillis()) }.getOrNull() ?: return@withContext
        val w = runCatching { parse(o) }.getOrNull() ?: return@withContext
        _now.value = w
        ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().putString("last", o.toString()).apply()
    }

    private fun parse(o: JSONObject): WeatherNow {
        val cur = o.getJSONObject("current")
        val curTime = cur.getString("time") // "2026-10-23T07:00"
        val hourly = o.getJSONObject("hourly")
        val ht = hourly.getJSONArray("time")
        val htemp = hourly.getJSONArray("temperature_2m")
        val hcode = hourly.getJSONArray("weather_code")
        val hday = hourly.optJSONArray("is_day")
        val prefix = curTime.take(13)
        var start = 0
        for (i in 0 until ht.length()) if (ht.getString(i).take(13) == prefix) { start = i; break }
        val hours = (start until minOf(start + 6, ht.length())).map { i ->
            HourWeather(ht.getString(i).substring(11, 13).toInt(), Math.round(htemp.getDouble(i)).toInt(), Sky.of(hcode.getInt(i)), (hday?.optInt(i, 1) ?: 1) == 1)
        }
        val daily = o.getJSONObject("daily")
        val dt = daily.getJSONArray("time")
        val dmax = daily.getJSONArray("temperature_2m_max")
        val dmin = daily.getJSONArray("temperature_2m_min")
        val dcode = daily.getJSONArray("weather_code")
        val days = (0 until dt.length()).map { i ->
            val d = java.time.LocalDate.parse(dt.getString(i))
            DayWeather(d.dayOfWeek.value, Math.round(dmin.getDouble(i)).toInt(), Math.round(dmax.getDouble(i)).toInt(), Sky.of(dcode.getInt(i)))
        }
        return WeatherNow(
            city = o.optString("_city"),
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            sky = Sky.of(cur.getInt("weather_code")),
            day = cur.optInt("is_day", 1) == 1,
            min = days.firstOrNull()?.min ?: 0,
            max = days.firstOrNull()?.max ?: 0,
            hours = hours,
            days = days,
            updatedAt = o.optLong("_at", System.currentTimeMillis()),
            sunrise = minuteOf(daily.optJSONArray("sunrise")?.optString(0)),
            sunset = minuteOf(daily.optJSONArray("sunset")?.optString(0)),
        )
    }

    /** "2026-10-28T18:12" → 1092 (günün dakikası); okunamazsa -1. */
    private fun minuteOf(t: String?): Int = runCatching { t!!.substring(11, 13).toInt() * 60 + t.substring(14, 16).toInt() }.getOrDefault(-1)
}
