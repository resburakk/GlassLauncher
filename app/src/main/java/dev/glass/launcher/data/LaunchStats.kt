package dev.glass.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Calendar

/**
 * Akıllı öneriler için kullanım geçmişi (iOS Siri önerileri / Pixel öneri satırı gibi). Yalnızca launcher'dan
 * yapılan açılışlar sayılır: her uygulama için günün 24 saatine ve hafta içi / hafta sonuna göre sayaç.
 * Ek izin gerekmez, veri telefonda kalır. Eski kullanım zamanla azalır (her gün %3), alışkanlık değişince
 * öneriler de değişir.
 */
object LaunchStats {
    private const val PREFS = "launch_stats"
    private const val KEY = "v1"
    /** [uygulama bileşeni] → 48 sayaç (0-23 hafta içi saatleri, 24-47 hafta sonu saatleri). */
    private val data = HashMap<String, FloatArray>()
    private var loaded = false
    private var lastDecayDay = 0L
    private val _version = MutableStateFlow(0)
    /** Her kayıtta artar: öneriler yeniden hesaplanır. */
    val version: StateFlow<Int> = _version

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastDecayDay = p.getLong("decayDay", 0L)
        runCatching {
            val o = JSONObject(p.getString(KEY, "{}") ?: "{}")
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                data[k] = FloatArray(48) { i -> if (i < a.length()) a.optDouble(i, 0.0).toFloat() else 0f }
            }
        }
    }

    @Synchronized
    private fun save(ctx: Context) {
        val o = JSONObject()
        data.forEach { (k, v) -> o.put(k, org.json.JSONArray(v.map { (it * 100).toInt() / 100.0 })) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, o.toString()).putLong("decayDay", lastDecayDay).apply()
    }

    private fun slot(c: Calendar = Calendar.getInstance()): Int {
        val wd = c.get(Calendar.DAY_OF_WEEK)
        val weekend = wd == Calendar.SATURDAY || wd == Calendar.SUNDAY
        return c.get(Calendar.HOUR_OF_DAY) + if (weekend) 24 else 0
    }

    /** Uygulama launcher'dan açıldı. */
    @Synchronized
    fun record(ctx: Context, component: String) {
        load(ctx)
        val today = System.currentTimeMillis() / 86_400_000L
        if (lastDecayDay != 0L && today > lastDecayDay) {
            val f = Math.pow(0.97, (today - lastDecayDay).toDouble()).toFloat()
            val dead = ArrayList<String>()
            data.forEach { (k, v) -> for (i in v.indices) v[i] *= f; if (v.sum() < 0.05f) dead += k }
            dead.forEach { data.remove(it) }
        }
        lastDecayDay = today
        val v = data.getOrPut(component) { FloatArray(48) }
        v[slot()] += 1f
        save(ctx)
        _version.value++
    }

    /**
     * Şu an için puanlar: bu saat tam ağırlık, ±1 saat yarım, ±2 saat çeyrek; aynı gün türü (hafta içi/sonu)
     * öncelikli, diğer gün türü %30 ağırlıkla. Ayrıca günün her saatinden küçük bir genel kullanım payı.
     */
    @Synchronized
    fun scores(ctx: Context): Map<String, Float> {
        load(ctx)
        val now = slot()
        val h = now % 24
        val base = now - h
        val other = if (base == 0) 24 else 0
        val out = HashMap<String, Float>()
        data.forEach { (k, v) ->
            var s = 0f
            for (d in -2..2) {
                val w = when (kotlin.math.abs(d)) { 0 -> 1f; 1 -> 0.5f; else -> 0.25f }
                val hh = ((h + d) % 24 + 24) % 24
                s += v[base + hh] * w + v[other + hh] * w * 0.3f
            }
            s += v.sum() * 0.02f
            if (s > 0f) out[k] = s
        }
        return out
    }
}
