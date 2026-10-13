package dev.glass.launcher.data

import android.content.pm.ApplicationInfo
import java.text.Normalizer
import java.util.Locale

/** Uygulama Arşivi kategorileri (iOS App Library benzeri). */
enum class AppCategory(val title: String) {
    Social("Sosyal"),
    Entertainment("Eğlence"),
    Games("Oyunlar"),
    Photo("Fotoğraf ve Video"),
    Shopping("Alışveriş"),
    Finance("Finans"),
    Travel("Seyahat ve Harita"),
    Productivity("Verimlilik"),
    News("Haber ve Okuma"),
    Utilities("Yardımcı Araçlar"),
    Other("Diğer"),
}

/**
 * Kategoriler önce uygulamanın kendi bildirdiği Android kategorisinden (ApplicationInfo.category)
 * alınır. Çoğu uygulama bunu bildirmediği için paket adı ve etiket üzerinde basit anahtar kelime
 * eşlemesi yapılır. Bu bir tahmindir; yanlış kategoriye düşen uygulamalar olabilir.
 */
object AppCategorizer {
    private val keywords: List<Pair<AppCategory, List<String>>> = listOf(
        AppCategory.Social to listOf(
            "whatsapp", "instagram", "facebook", "telegram", "twitter", "tiktok", "snapchat", "discord",
            "linkedin", "messenger", "signal", "threads", "reddit", "pinterest", "bip", "viber",
        ),
        AppCategory.Entertainment to listOf(
            "youtube", "netflix", "spotify", "disney", "primevideo", "exxen", "blutv", "tod.", "gain",
            "twitch", "music", "muzik", "podcast", "fizy", "deezer", "soundcloud", "tabii", "mubi",
        ),
        AppCategory.Shopping to listOf(
            "trendyol", "hepsiburada", "amazon", "a101", "bim", "migros", "getir", "carrefour", "sok",
            "n11", "ciceksepeti", "akakce", "aliexpress", "temu", "shein", "boyner", "lcwaikiki", "defacto",
            "yemeksepeti", "market", "shop", "store", "ikea", "teknosa", "mediamarkt", "vatan", "dolap",
            "letgo", "sahibinden", "arabam", "bonusflas",
        ),
        AppCategory.Finance to listOf(
            "bank", "banka", "akbank", "garanti", "ziraat", "isbank", "isbankasi", "yapikredi", "vakif",
            "halkbank", "qnb", "enpara", "denizbank", "papara", "pay", "wallet", "cuzdan",
            "finans", "kart", "albest", "midas", "binance", "btcturk", "paribu", "borsa", "e-devlet", "edevlet",
        ),
        AppCategory.Travel to listOf(
            "maps", "harita", "booking", "airbnb", "uber", "bitaksi", "thy", "turkishairlines", "pegasus",
            "obilet", "enuygun", "trivago", "tripadvisor", "yandex.navi", "navigation", "waze", "moovit",
            "flight", "ucak", "otel", "hotel", "travel", "martı", "marti",
        ),
        AppCategory.Productivity to listOf(
            "office", "docs", "sheets", "slides", "drive", "notion", "calendar", "takvim", "gmail", "mail",
            "outlook", "chatgpt", "openai", "claude", "anthropic", "copilot", "gemini", "authenticator",
            "keep", "notes", "notlar", "todo", "camscanner", "scanner", "pdf", "translate", "ceviri",
            "teams", "zoom", "meet", "slack", "dropbox", "onedrive", "evernote", "word", "excel",
        ),
        AppCategory.Photo to listOf(
            "camera", "kamera", "photo", "foto", "gallery", "galeri", "snapseed", "lightroom", "capcut",
            "inshot", "vsco", "picsart", "canva", "video",
        ),
        AppCategory.News to listOf(
            "news", "haber", "hurriyet", "sozcu", "milliyet", "ntv", "cnn", "bbc", "kindle", "book",
            "kitap", "storytel", "medium", "flipboard", "dergi", "magazine",
        ),
        AppCategory.Games to listOf("game", "oyun", "games", "supercell", "king.", "playrix", "rovio"),
        AppCategory.Utilities to listOf(
            "settings", "ayarlar", "clock", "saat", "calculator", "hesap", "contacts", "kisiler", "dialer",
            "phone", "telefon", "messages", "mesaj", "files", "dosya", "remote", "kumanda", "mirror", "ayna",
            "weather", "hava", "compass", "pusula", "recorder", "kaydedici", "flashlight", "fener", "vpn",
            "security", "guvenlik", "health", "saglik", "112", "acil", "arrows",
        ),
    )

    /** Türkçe karakterleri ve aksanları sadeleştirir: "Çeviri" → "ceviri", "ŞOK" → "sok". */
    fun normalize(s: String): String {
        val lower = s.lowercase(Locale("tr", "TR")).replace('ı', 'i')
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{Mn}+"), "")
    }

    fun categorize(packageName: String, label: String, androidCategory: Int, isSystem: Boolean): AppCategory {
        when (androidCategory) {
            ApplicationInfo.CATEGORY_GAME -> return AppCategory.Games
            ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.Social
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.Entertainment
            ApplicationInfo.CATEGORY_IMAGE -> return AppCategory.Photo
            ApplicationInfo.CATEGORY_NEWS -> return AppCategory.News
            ApplicationInfo.CATEGORY_MAPS -> return AppCategory.Travel
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppCategory.Productivity
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> return AppCategory.Utilities
        }
        val hay = normalize(packageName) + " " + normalize(label)
        for ((cat, words) in keywords) {
            if (words.any { hay.contains(normalize(it)) }) return cat
        }
        return if (isSystem) AppCategory.Utilities else AppCategory.Other
    }
}
