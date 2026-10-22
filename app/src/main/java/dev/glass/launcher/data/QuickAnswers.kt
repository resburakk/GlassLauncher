package dev.glass.launcher.data

import android.content.Intent
import android.provider.Settings
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Arama ekranında yazarken beliren hızlı cevap: hesap sonucu, birim çevirme ya da bir telefon ayarı. */
sealed class QuickAnswer {
    /** Hesap / çevirme sonucu: dokununca panoya kopyalanır. */
    data class Value(val expression: String, val result: String) : QuickAnswer()
    /** Telefonun bir ayar ekranı. */
    data class SettingLink(val title: String, val intent: Intent) : QuickAnswer()
}

/**
 * Hızlı cevaplar (iOS Spotlight / Pixel arama gibi), tamamen çevrimdışı:
 * - Hesap makinesi: + − × ÷ ^ % ve parantez ("12*(3+4)", "2^10", "%18 of 250" yerine "250*18%").
 * - Birim çevirme: uzunluk, ağırlık, sıcaklık, hacim, hız ("5 km mil", "70 kg lb", "30 c f").
 * - Ayar arama: Wi-Fi, Bluetooth, ekran, ses, pil, depolama, konum, bildirimler, uygulamalar…
 */
object QuickAnswers {
    private val fmt = DecimalFormat("#,##0.########", DecimalFormatSymbols(Locale("tr", "TR")))

    fun compute(q0: String): List<QuickAnswer> {
        val q = q0.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<QuickAnswer>()
        calc(q)?.let { out += it }
        convert(q)?.let { out += it }
        out += settings(q)
        return out
    }

    // ---------- Hesap makinesi ----------

    private fun calc(q: String): QuickAnswer? {
        val s = q.replace('×', '*').replace('÷', '/').replace('x', '*').replace('X', '*').replace(" ", "").replace(',', '.')
        // En az bir işlem ve yalnızca sayı/işlem karakterleri (tek sayı "5" hesap sayılmaz).
        if (!s.matches(Regex("[0-9.+\\-*/^%()]+"))) return null
        if (!s.any { it in "+-*/^%" } || !s.any { it.isDigit() }) return null
        val v = runCatching { Parser(s).parse() }.getOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return QuickAnswer.Value(q, fmt.format(v))
    }

    /** Basit özyinelemeli ayrıştırıcı: + - * / ^ % (yüzde: x% = x/100) ve parantez. */
    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); require(i == s.length); return v }
        private fun peek() = if (i < s.length) s[i] else '\u0000'
        private fun expr(): Double {
            var v = term()
            while (true) when (peek()) {
                '+' -> { i++; v += term() }
                '-' -> { i++; v -= term() }
                else -> return v
            }
        }
        private fun term(): Double {
            var v = power()
            while (true) when (peek()) {
                '*' -> { i++; v *= power() }
                '/' -> { i++; v /= power() }
                else -> return v
            }
        }
        private fun power(): Double {
            val b = unary()
            return if (peek() == '^') { i++; Math.pow(b, power()) } else b
        }
        private fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> postfix()
        }
        private fun postfix(): Double {
            var v = atom()
            while (peek() == '%') { i++; v /= 100.0 }
            return v
        }
        private fun atom(): Double {
            if (peek() == '(') { i++; val v = expr(); require(peek() == ')'); i++; return v }
            val st = i
            while (peek().isDigit() || peek() == '.') i++
            require(i > st)
            return s.substring(st, i).toDouble()
        }
    }

    // ---------- Birim çevirme ----------

    private class UnitDef(val names: List<String>, val label: String, val group: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double)

    private fun lin(names: List<String>, label: String, group: String, k: Double) = UnitDef(names, label, group, { it * k }, { it / k })

    private val units = listOf(
        lin(listOf("km", "kilometre"), "km", "len", 1000.0),
        lin(listOf("m", "metre"), "m", "len", 1.0),
        lin(listOf("cm", "santim", "santimetre"), "cm", "len", 0.01),
        lin(listOf("mm", "milimetre"), "mm", "len", 0.001),
        lin(listOf("mil", "mi", "mile"), "mil", "len", 1609.344),
        lin(listOf("ft", "feet", "fit", "ayak"), "ft", "len", 0.3048),
        lin(listOf("inç", "inch", "in", "inc"), "inç", "len", 0.0254),
        lin(listOf("yd", "yarda"), "yd", "len", 0.9144),
        lin(listOf("kg", "kilo", "kilogram"), "kg", "mass", 1.0),
        lin(listOf("g", "gr", "gram"), "g", "mass", 0.001),
        lin(listOf("lb", "lbs", "pound", "libre"), "lb", "mass", 0.45359237),
        lin(listOf("oz", "ons"), "oz", "mass", 0.028349523125),
        lin(listOf("ton"), "ton", "mass", 1000.0),
        lin(listOf("l", "lt", "litre"), "L", "vol", 1.0),
        lin(listOf("ml", "mililitre"), "ml", "vol", 0.001),
        lin(listOf("gal", "galon", "gallon"), "gal", "vol", 3.785411784),
        lin(listOf("kmh", "km/h", "km/sa"), "km/sa", "speed", 1.0),
        lin(listOf("mph"), "mph", "speed", 1.609344),
        lin(listOf("knot", "knot", "kt", "deniz mili/sa"), "knot", "speed", 1.852),
        UnitDef(listOf("c", "°c", "celsius", "santigrat"), "°C", "temp", { it }, { it }),
        UnitDef(listOf("f", "°f", "fahrenheit"), "°F", "temp", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 }),
        UnitDef(listOf("k", "kelvin"), "K", "temp", { it - 273.15 }, { it + 273.15 }),
    )

    private fun unitOf(t: String) = units.firstOrNull { u -> u.names.any { it.equals(t, ignoreCase = true) } }

    private fun convert(q: String): QuickAnswer? {
        // "5 km mil", "5km to mi", "70 kg kaç lb", "30 c f"
        val m = Regex("^(-?[0-9]+(?:[.,][0-9]+)?)\\s*([^\\s0-9]+)\\s+(?:to\\s+|in\\s+|kaç\\s+|=\\s*)?([^\\s0-9]+)\\s*\\??$", RegexOption.IGNORE_CASE)
            .find(q.trim()) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val a = unitOf(m.groupValues[2]) ?: return null
        val b = unitOf(m.groupValues[3]) ?: return null
        if (a.group != b.group || a === b) return null
        val r = b.fromBase(a.toBase(v))
        return QuickAnswer.Value("${fmt.format(v)} ${a.label}", "${fmt.format(Math.round(r * 10000) / 10000.0)} ${b.label}")
    }

    // ---------- Ayar arama ----------

    private class SettingEntry(val title: String, val keys: List<String>, val action: String)

    private val settingsList = listOf(
        SettingEntry("Wi-Fi", listOf("wifi", "wi-fi", "kablosuz", "internet", "ağ"), Settings.ACTION_WIFI_SETTINGS),
        SettingEntry("Bluetooth", listOf("bluetooth", "bt", "kulaklık"), Settings.ACTION_BLUETOOTH_SETTINGS),
        SettingEntry("Mobil veri", listOf("mobil veri", "hücresel", "sim", "veri"), Settings.ACTION_DATA_ROAMING_SETTINGS),
        SettingEntry("Ekran ve parlaklık", listOf("ekran", "parlaklık", "karanlık mod", "koyu mod"), Settings.ACTION_DISPLAY_SETTINGS),
        SettingEntry("Ses ve titreşim", listOf("ses", "titreşim", "zil", "melodi"), Settings.ACTION_SOUND_SETTINGS),
        SettingEntry("Pil", listOf("pil", "batarya", "şarj", "güç tasarrufu"), Intent.ACTION_POWER_USAGE_SUMMARY),
        SettingEntry("Depolama", listOf("depolama", "hafıza", "alan"), Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        SettingEntry("Konum", listOf("konum", "gps"), Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        SettingEntry("Bildirimler", listOf("bildirim"), Settings.ACTION_NOTIFICATION_SETTINGS),
        SettingEntry("Uygulamalar", listOf("uygulamalar", "uygulama yönet"), Settings.ACTION_APPLICATION_SETTINGS),
        SettingEntry("Varsayılan uygulamalar", listOf("varsayılan", "ana ekran uygulaması", "launcher"), Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        SettingEntry("Güvenlik ve parmak izi", listOf("güvenlik", "parmak izi", "şifre", "kilit ekranı", "yüz"), Settings.ACTION_SECURITY_SETTINGS),
        SettingEntry("Gizlilik", listOf("gizlilik", "izin"), Settings.ACTION_PRIVACY_SETTINGS),
        SettingEntry("Uçak modu", listOf("uçak"), Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        SettingEntry("Rahatsız Etmeyin", listOf("rahatsız", "sessiz", "odak"), Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
        SettingEntry("Hotspot ve paylaşım", listOf("hotspot", "erişim noktası", "paylaşım"), "android.settings.TETHER_SETTINGS"),
        SettingEntry("NFC", listOf("nfc", "temassız"), Settings.ACTION_NFC_SETTINGS),
        SettingEntry("Dil ve klavye", listOf("dil", "klavye"), Settings.ACTION_LOCALE_SETTINGS),
        SettingEntry("Tarih ve saat", listOf("tarih", "saat ayar"), Settings.ACTION_DATE_SETTINGS),
        SettingEntry("Erişilebilirlik", listOf("erişilebilirlik"), Settings.ACTION_ACCESSIBILITY_SETTINGS),
        SettingEntry("Hesaplar", listOf("hesap", "senkron"), Settings.ACTION_SYNC_SETTINGS),
        SettingEntry("Telefon hakkında", listOf("hakkında", "sürüm", "model"), Settings.ACTION_DEVICE_INFO_SETTINGS),
        SettingEntry("Geliştirici seçenekleri", listOf("geliştirici"), Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    )

    private fun norm(s: String) = AppCategorizer.normalize(s)

    private fun settings(q: String): List<QuickAnswer> {
        val n = norm(q)
        if (n.length < 2) return emptyList()
        return settingsList.filter { e -> norm(e.title).startsWith(n) || e.keys.any { k -> norm(k).startsWith(n) || (n.length >= 4 && norm(k).contains(n)) } }
            .take(3)
            .map { QuickAnswer.SettingLink(it.title, Intent(it.action)) }
    }
}
