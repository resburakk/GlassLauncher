package dev.glass.launcher.ime

import android.content.Context
import org.json.JSONObject

/**
 * Glass Klavye ayarları (iOS: Ayarlar > Genel > Klavye + ek tema/hassasiyet ayarları). SharedPreferences'ta durur;
 * klavye her açılışta güncel değerleri okur.
 */
data class KbConfig(
    val theme: Int = 0,            // 0 otomatik, 1 açık, 2 koyu
    val glass: Boolean = true,     // arkası bulanık cam zemin
    val glassTint: Int = 50,       // cam yoğunluğu 0 şeffaf … 100 renkli (iOS 27 Liquid Glass kaydırıcısı)
    val autoCaps: Boolean = true,
    val autocorrect: Boolean = true,
    val predictive: Boolean = true,
    val swipe: Boolean = true,
    val swipeDeleteWord: Boolean = true, // kaydırarak yazılan kelime tek silmede silinir
    val capsLock: Boolean = true,
    val preview: Boolean = true,   // karakter önizleme (balon)
    val periodShortcut: Boolean = true, // iki boşluk = ". "
    val smartPunct: Boolean = true,     // " ' → “ ” ’
    val sound: Boolean = false,
    val haptics: Boolean = true,
    val hapticStrength: Int = 1,   // 0 hafif, 1 orta, 2 güçlü
    val dictation: Boolean = true, // mikrofon tuşu
    val oneHanded: Int = 0,        // 0 kapalı, 1 sol, 2 sağ
    val height: Int = 1,           // 0 kısa, 1 normal, 2 uzun
    val longPressMs: Int = 420,    // basılı tutma süresi (sil tekrarı başlangıcı)
    val repeatMs: Int = 60,        // sil tekrar aralığı
    val touchSlopDp: Int = 14,     // tuşlar arasına dokununca yakalama mesafesi
    val replacements: Map<String, String> = DEFAULT_REPLACEMENTS,
) {
    companion object {
        const val PREFS = "glass_keyboard"
        val DEFAULT_REPLACEMENTS = linkedMapOf("slm" to "Selam", "nbr" to "Naber?", "tmm" to "Tamam")

        fun load(ctx: Context): KbConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = KbConfig()
            val repl = p.getString("replacements", null)?.let { s ->
                runCatching { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrNull()
            } ?: d.replacements
            return KbConfig(
                theme = p.getInt("theme", d.theme),
                glass = p.getBoolean("glass", d.glass),
                glassTint = p.getInt("glassTint", d.glassTint),
                autoCaps = p.getBoolean("autocaps", d.autoCaps),
                autocorrect = p.getBoolean("autocorrect", d.autocorrect),
                predictive = p.getBoolean("predictive", d.predictive),
                swipe = p.getBoolean("swipe", d.swipe),
                swipeDeleteWord = p.getBoolean("swipeDeleteWord", d.swipeDeleteWord),
                capsLock = p.getBoolean("capsLock", d.capsLock),
                preview = p.getBoolean("preview", d.preview),
                periodShortcut = p.getBoolean("periodShortcut", d.periodShortcut),
                smartPunct = p.getBoolean("smartPunct", d.smartPunct),
                sound = p.getBoolean("sound", d.sound),
                haptics = p.getBoolean("haptics", d.haptics),
                hapticStrength = p.getInt("hapticStrength", d.hapticStrength),
                dictation = p.getBoolean("dictation", d.dictation),
                oneHanded = p.getInt("oneHanded", d.oneHanded),
                height = p.getInt("height", d.height),
                longPressMs = p.getInt("longPressMs", d.longPressMs),
                repeatMs = p.getInt("repeatMs", d.repeatMs),
                touchSlopDp = p.getInt("touchSlopDp", d.touchSlopDp),
                replacements = repl,
            )
        }

        fun save(ctx: Context, c: KbConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("theme", c.theme).putBoolean("glass", c.glass).putInt("glassTint", c.glassTint).putBoolean("autocaps", c.autoCaps)
                .putBoolean("autocorrect", c.autocorrect).putBoolean("predictive", c.predictive)
                .putBoolean("swipe", c.swipe).putBoolean("swipeDeleteWord", c.swipeDeleteWord).putBoolean("capsLock", c.capsLock)
                .putBoolean("preview", c.preview).putBoolean("periodShortcut", c.periodShortcut).putBoolean("smartPunct", c.smartPunct)
                .putBoolean("sound", c.sound).putBoolean("haptics", c.haptics).putInt("hapticStrength", c.hapticStrength)
                .putBoolean("dictation", c.dictation).putInt("oneHanded", c.oneHanded).putInt("height", c.height)
                .putInt("longPressMs", c.longPressMs).putInt("repeatMs", c.repeatMs).putInt("touchSlopDp", c.touchSlopDp)
                .putString("replacements", JSONObject(c.replacements as Map<*, *>).toString())
                .apply()
        }
    }
}
