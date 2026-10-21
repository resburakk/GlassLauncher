package dev.glass.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "launcher_settings")

/** Simge görünümleri (bkz. [IconRenderer]). */
enum class IconStyle(val title: String) { Original("Orijinal"), Light("Açık"), Dark("Koyu"), Tinted("Renkli") }

/** Ana ekranda aşağı kaydırma hareketinin yapacağı iş. */
enum class SwipeAction(val title: String) {
    /** Telefonun kendi Kontrol Merkezi (hızlı ayarlar). */
    ControlCenter("Kontrol Merkezi"),
    /** Telefonun kendi bildirim gölgesi. */
    Notifications("Bildirimler"),
    Search("Arama"),
    /** Ekranın sol yarısından: bildirimler; sağ yarısından: Kontrol Merkezi. */
    Split("Sol: Bildirimler · Sağ: Kontrol Merkezi"),
    None("Hiçbiri"),
}

/** Cam yüzeylerin tonu. Otomatik: duvar kâğıdının parlaklığına göre. */
enum class ThemeMode(val title: String) { Auto("Otomatik"), Light("Açık"), Dark("Koyu") }

data class LauncherSettings(
    // Ana ekran
    val columns: Int = 4,
    val rows: Int = 6,
    val showLabels: Boolean = true,
    val hSpacing: Float = 1f,
    val vSpacing: Float = 1f,
    /** Etiketin simgeye uzaklığı (pt). */
    val labelGap: Float = 5f,
    /** Etiket yazı boyutu (pt; iOS'ta 12). */
    val labelSize: Float = 11.2f,
    /** Ana ekran simge adlarının kalınlığı (300–700). */
    val labelWeight: Float = 500f,
    // Simgeler
    val iconScale: Float = 1f,
    /** Açık modda simge görünümü. */
    val iconStyle: IconStyle = IconStyle.Original,
    /** Koyu modda simge görünümü (telefon koyu moda geçince kendiliğinden kullanılır). */
    val iconStyleDark: IconStyle = IconStyle.Dark,
    /** Koyu modda da orijinal kalacak uygulamalar (basılı tutma menüsünden). */
    val darkOriginalApps: Set<String> = emptySet(),
    val tintColor: Int = 0xFF64D2FF.toInt(),
    val iconCorner: Float = 0.28125f,
    /** iOS sürekli köşe (squircle) yumuşaklığı: 0 = düz yuvarlak köşe, 0.6 = iOS. */
    val cornerSmoothing: Float = 0.6f,
    /** Simgelerin üst/sol kenar ışığı (iOS 26 cam kabarıklığı): 0 = eski görünüm. */
    val iconRimGlow: Float = 1f,
    val iconGlass: Boolean = true,
    /** Uygulama anahtarı → özel simge dosya adı (filesDir/icons altında). */
    val customIcons: Map<String, String> = emptyMap(),
    // Dock
    val dockHeight: Float = 1f,
    val dockWidth: Float = 1f,
    val dockOpacity: Float = 1f,
    /** Dock'un ekranın altına uzaklığı (pt; iOS'ta 15). */
    val dockBottom: Float = 15f,
    /** Dock köşe yuvarlaklığı (yüksekliğe oran; 0.5 = hap şekli). */
    val dockCorner: Float = 0.435f,
    /** Arama düğmesi boyut çarpanı. */
    val pillScale: Float = 1f,
    /** Arama düğmesinin ortalanmış konumdan yukarı (+) / aşağı (−) kayması (pt). */
    val pillOffset: Float = -10f,
    // Klasörler
    val folderOpacity: Float = 1f,
    /** Açık klasör panelinin ekran genişliğine oranı. */
    val folderSize: Float = 0.74f,
    /** Açık klasör panelinin köşe yuvarlaklığı (pt; iOS 26 ≈ 34). */
    val folderCorner: Float = 55f,
    /** Uygulama arşivindeki kapalı klasör kutusunun köşe yuvarlaklığı (pt; iOS ≈ 20). */
    val libraryTileCorner: Float = 30f,
    /** true: arşivin büyük klasör kartlarının köşesi dock'un köşesiyle aynı (dock köşe ayarını izler). */
    val libraryTileCornerDock: Boolean = false,
    /** Uygulama arşivindeki açık klasörün köşe yuvarlaklığı (pt; iOS ≈ 34). */
    val libraryGroupCorner: Float = 34f,
    /** Tüm cam yüzeylerdeki beyaz kenar çizgisinin kalınlığı (pt; 0 = çizgi yok). */
    val glassRimWidth: Float = 0.5f,
    /** Açık klasörün (ana ekran ve arşiv) adının yazı boyutu (pt). */
    val openFolderTitleSize: Float = 25f,
    /** Cam bulanıklığının şiddeti (çarpan; 0 = bulanıklık yok, 1 = varsayılan, 2 = iki katı). */
    val glassBlur: Float = 1f,
    /** Cam parlaklığı (−1 = koyu, 0 = varsayılan, +1 = açık). */
    val glassBrightness: Float = 0f,
    /** Kenardaki ışık parlamasının gücü (çarpan; 0 = yok). */
    val glassRimGlow: Float = 1f,
    /** Cam dolgusunun yoğunluğu; düşük = daha saydam (çarpan). */
    val glassOpacity: Float = 1f,
    /** Dock üstündeki "Ara" düğmesinin yazı boyutu (pt) ve kalınlığı (300–700). */
    val pillTextSize: Float = 12f,
    val pillTextWeight: Float = 500f,
    /** Basılı tutma menüsü görünümü (iOS 26). */
    val menuCorner: Float = 30f,
    val menuWidth: Float = 260f,
    val menuTextSize: Float = 15f,
    val menuRowHeight: Float = 48f,
    /** Menü camının açıklığı (beyaz dolgu yoğunluğu). */
    val menuOpacity: Float = 0.62f,
    val menuDark: Boolean = false,
    // Hareketler
    /** Ekranın üst kenarından aşağı kaydırma. */
    val topSwipe: SwipeAction = SwipeAction.ControlCenter,
    /** Ekranın ortasından (boş alandan) aşağı kaydırma. */
    val midSwipe: SwipeAction = SwipeAction.Search,
    /** Boş alana çift dokununca ekranı kilitle (Erişilebilirlik hizmeti gerekir). */
    val doubleTapLock: Boolean = true,
    /** Uygulama arşivi sayfası (ana ekranın sağı). Varsayılan kapalı: sayfa hiç görünmez. */
    val showLibrary: Boolean = true,
    /** Widget sayfası (iOS Bugün görünümü, ilk sayfanın solunda). */
    val showToday: Boolean = true,
    /** Yuvarlak (köşeleri boş) simgeleri dolu kareye tamamla. */
    val iconFillRound: Boolean = true,
    /** Saat ve Takvim simgeleri canlı çizilir (iOS gibi: ibreler döner, tarih her gün değişir). */
    val liveIcons: Boolean = true,
    /** Simge kenarındaki ince beyaz çizginin (iOS kenar ışığı) kalınlığı, dp. */
    val iconRimWidth: Float = 1f,
    /** Duvar kâğıdı seçilince sistemde nereye uygulanır: "home", "lock", "both". */
    val wallpaperTarget: String = "both",
    /** Canlı saat simgesinde saniye ibresi. */
    val liveClockSeconds: Boolean = true,
    /** Canlı saat/takvim görünümü: "auto" (telefonun açık/koyu modu), "light", "dark". */
    val liveIconTheme: String = "auto",
    /** Uygulama simgeden büyüyerek açılır, dönüşte simgeye küçülerek oturur. */
    val appTransitions: Boolean = false,
    /** Arama öneri panelinin köşesi (dock yüksekliğine oranla; dock köşesinden bağımsız). */
    val searchSuggestCorner: Float = 0.34f,
    /** Liquid Glass kenar kırılması (mercek): 0 kapalı … 1 güçlü. */
    val glassRefraction: Float = 0.6f,
    /** iOS 26 kalın cam kenarı: kırılma bandının genişliği (kısa kenarın oranı). */
    val glassEdgeWidth: Float = 0.42f,
    /** iOS 27 tarzı koyu derinlik kenarı: 0 kapalı … 1 belirgin. */
    val glassEdgeDark: Float = 0f,
    /** Genel şeffaflık (iOS 27 / MagicOS 11 kaydırıcısı): 0 çok şeffaf … 0.5 dengeli … 1 renkli. */
    val glassTint: Float = 0.5f,
    /** Sistem duvar kâğıdı kendiliğinden launcher kopyası olarak alınır ("Tüm dosyalara erişim" izni gerekir). */
    val syncSystemWallpaper: Boolean = true,
    /** Cam stili: "clear" (iOS 26 Şeffaf: arkası net, kenarda mercek) veya "frosted" (buzlu, bulanık). */
    val glassStyle: String = "clear",
    /** Arama paneli/çubuğu ve Uygulama Arşivi camı: "general" = yalnızca genel cam ayarları, "dock" = dock ile aynı yoğunluk. */
    val panelGlassSync: String = "dock",
    /** Arama önerileri 8 (iki sıra) mı açık, 4 (tek sıra) mı? Son durum hatırlanır. */
    val searchSuggestExpanded: Boolean = false,
    /** Arama önerilerindeki uygulama adlarının yazı boyutu (pt) ve kalınlığı. */
    val searchSuggestLabelSize: Float = 11f,
    val searchSuggestLabelWeight: Float = 400f,
    /** Arama ekranı: öneri adları, nötr cam, cihaz içi arama türleri ve izin kartı. */
    val searchShowLabels: Boolean = true,
    val searchNeutral: Boolean = false,
    val searchContacts: Boolean = true,
    val searchMessages: Boolean = true,
    val searchFiles: Boolean = true,
    val searchDeviceCard: Boolean = true,
    /** Arama yazı ayarları (pt) ve sonuçların cam panel içinde gösterilmesi. */
    val searchFieldTextSize: Float = 14f,
    val searchResultTextSize: Float = 14f,
    val searchResultWeight: Float = 300f,
    val searchResultsPanel: Boolean = false,
    /** Arama paneli ve çubuğunun cam parlaklığı (karartılmış arka planla uyum için varsayılan biraz koyu). */
    val searchGlassBrightness: Float = 0f,
    /** Yazı gölgesi (iOS tarzı dağınık gölge): açık/kapalı, yoğunluk ve yayılma. */
    val textShadow: Boolean = true,
    val textShadowStrength: Float = 0.455f,
    val textShadowSpread: Float = 2.5f,
    /** Ayarlar ekranının teması: "auto" (telefona uyar), "light", "dark". */
    val settingsTheme: String = "auto",
    /** Düzenleme modundaki üst düğmelerin (+, Ayarlar, Bitti) yazı boyutu (pt) ve kalınlığı. */
    val editButtonTextSize: Float = 12f,
    val editButtonWeight: Float = 400f,
    // Liquid Glass ve görünüm
    val glassStrength: Float = 1f,
    val theme: ThemeMode = ThemeMode.Auto,
    val launcherWallpaper: Boolean = false,
    val wallpaperDim: Float = 0f,
    // Animasyonlar
    val animSpeed: Float = 1f,
    // Performans
    val realBlur: Boolean = true,
    val autoQuality: Boolean = true,
    val lowPower: Boolean = false,
    // Uygulama Arşivi
    /** Yeni kurulan uygulamalar ana ekrana mı eklensin (kapalıysa yalnızca Uygulama Arşivi'nde). */
    val addNewToHome: Boolean = true,
    /** Simgelerde bildirim sayısı (kırmızı rozet). Bildirim erişimi izni gerekir. */
    val showBadges: Boolean = true,
    /** Ana ekran duvar kâğıdının sürekli bulanıklığı (0 = net, 1 = en bulanık). */
    val homeWallpaperBlur: Float = 0f,
    /** Son başlatılan uygulamalar (ComponentName.flattenToString), en yenisi başta. */
    val recents: List<String> = emptyList(),
) {
    /** Arama ve arşiv camının yoğunluğu: genel cam yoğunluğu; "dock ile eşitle" seçiliyse dock'un yoğunluğu da uygulanır. */
    val panelGlassStrength: Float get() = glassStrength * (if (panelGlassSync == "dock") dockOpacity else 1f)

    fun sanitized(): LauncherSettings = copy(
        columns = columns.coerceIn(3, 6),
        rows = rows.coerceIn(4, 8),
        hSpacing = hSpacing.coerceIn(0.8f, 1.3f),
        vSpacing = vSpacing.coerceIn(0.8f, 1.4f),
        labelGap = labelGap.coerceIn(0f, 14f),
        labelSize = labelSize.coerceIn(9f, 16f),
        iconScale = iconScale.coerceIn(0.7f, 1.4f),
        iconCorner = iconCorner.coerceIn(0.05f, 0.5f),
        cornerSmoothing = cornerSmoothing.coerceIn(0f, 1f),
        iconRimGlow = iconRimGlow.coerceIn(0f, 2f),
        iconRimWidth = iconRimWidth.coerceIn(0f, 3f),
        searchSuggestCorner = searchSuggestCorner.coerceIn(0.05f, 0.5f),
        glassRefraction = glassRefraction.coerceIn(0f, 1f),
        glassEdgeWidth = glassEdgeWidth.coerceIn(0.15f, 0.6f),
        glassEdgeDark = glassEdgeDark.coerceIn(0f, 1f),
        glassTint = glassTint.coerceIn(0f, 1f),
        searchSuggestLabelSize = searchSuggestLabelSize.coerceIn(8f, 18f),
        searchSuggestLabelWeight = searchSuggestLabelWeight.coerceIn(300f, 700f),
        wallpaperTarget = wallpaperTarget.takeIf { it in setOf("home", "lock", "both") } ?: "both",
        dockHeight = dockHeight.coerceIn(0.8f, 1.3f),
        dockWidth = dockWidth.coerceIn(0.85f, 1.05f),
        dockOpacity = dockOpacity.coerceIn(0f, 2f),
        dockBottom = dockBottom.coerceIn(0f, 60f),
        dockCorner = dockCorner.coerceIn(0.08f, 0.5f),
        pillScale = pillScale.coerceIn(0.7f, 1.5f),
        pillOffset = pillOffset.coerceIn(-60f, 60f),
        folderOpacity = folderOpacity.coerceIn(0f, 2f),
        folderSize = folderSize.coerceIn(0.55f, 0.95f),
        folderCorner = folderCorner.coerceIn(8f, 80f),
        libraryTileCorner = libraryTileCorner.coerceIn(8f, 60f),
        libraryGroupCorner = libraryGroupCorner.coerceIn(8f, 80f),
        glassRimWidth = glassRimWidth.coerceIn(0f, 4f),
        openFolderTitleSize = openFolderTitleSize.coerceIn(10f, 40f),
        glassBlur = glassBlur.coerceIn(0f, 2f),
        glassBrightness = glassBrightness.coerceIn(-1f, 1f),
        glassRimGlow = glassRimGlow.coerceIn(0f, 2f),
        glassOpacity = glassOpacity.coerceIn(0f, 2f),
        pillTextSize = pillTextSize.coerceIn(9f, 24f),
        pillTextWeight = pillTextWeight.coerceIn(300f, 700f),
        menuCorner = menuCorner.coerceIn(10f, 44f),
        menuWidth = menuWidth.coerceIn(200f, 330f),
        menuTextSize = menuTextSize.coerceIn(13f, 22f),
        menuRowHeight = menuRowHeight.coerceIn(36f, 64f),
        menuOpacity = menuOpacity.coerceIn(0.1f, 0.95f),
        searchFieldTextSize = searchFieldTextSize.coerceIn(12f, 22f),
        searchResultTextSize = searchResultTextSize.coerceIn(12f, 22f),
        searchResultWeight = searchResultWeight.coerceIn(300f, 700f),
        searchGlassBrightness = searchGlassBrightness.coerceIn(-1f, 1f),
        textShadowStrength = textShadowStrength.coerceIn(0.05f, 0.9f),
        textShadowSpread = textShadowSpread.coerceIn(0.3f, 3f),
        editButtonTextSize = editButtonTextSize.coerceIn(10f, 20f),
        editButtonWeight = editButtonWeight.coerceIn(300f, 700f),
        labelWeight = labelWeight.coerceIn(300f, 700f),
        settingsTheme = settingsTheme.takeIf { it in setOf("auto", "light", "dark") } ?: "auto",
        glassStrength = glassStrength.coerceIn(0.5f, 1.5f),
        wallpaperDim = wallpaperDim.coerceIn(0f, 0.6f),
        animSpeed = animSpeed.coerceIn(0.5f, 2f),
        recents = recents.take(8),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("columns", columns); put("rows", rows); put("showLabels", showLabels)
        put("hSpacing", hSpacing.toDouble()); put("vSpacing", vSpacing.toDouble())
        put("labelGap", labelGap.toDouble()); put("labelSize", labelSize.toDouble())
        put("iconScale", iconScale.toDouble()); put("iconStyle", iconStyle.name); put("iconStyleDark", iconStyleDark.name); put("darkOriginalApps", JSONArray(darkOriginalApps.toList())); put("tintColor", tintColor)
        put("iconCorner", iconCorner.toDouble()); put("iconGlass", iconGlass)
        put("customIcons", JSONObject(customIcons as Map<*, *>))
        put("dockHeight", dockHeight.toDouble()); put("dockWidth", dockWidth.toDouble())
        put("dockOpacity", dockOpacity.toDouble()); put("folderOpacity", folderOpacity.toDouble())
        put("dockBottom", dockBottom.toDouble()); put("folderSize", folderSize.toDouble()); put("folderCorner", folderCorner.toDouble())
        put("libraryTileCorner2", libraryTileCorner.toDouble()); put("libraryTileCornerDock", libraryTileCornerDock); put("libraryGroupCorner", libraryGroupCorner.toDouble())
        put("glassRimWidth", glassRimWidth.toDouble()); put("openFolderTitleSize", openFolderTitleSize.toDouble())
        put("glassBlur", glassBlur.toDouble()); put("glassBrightness", glassBrightness.toDouble())
        put("glassRimGlow", glassRimGlow.toDouble()); put("glassOpacity", glassOpacity.toDouble())
        put("pillTextSize", pillTextSize.toDouble()); put("pillTextWeight", pillTextWeight.toDouble())
        put("menuCorner", menuCorner.toDouble()); put("menuWidth", menuWidth.toDouble())
        put("menuTextSize", menuTextSize.toDouble()); put("menuRowHeight", menuRowHeight.toDouble())
        put("menuOpacity", menuOpacity.toDouble()); put("menuDark", menuDark)
        put("dockCorner", dockCorner.toDouble()); put("pillScale", pillScale.toDouble()); put("pillOffset", pillOffset.toDouble())
        put("topSwipe", topSwipe.name); put("midSwipe2", midSwipe.name); put("doubleTapLock", doubleTapLock); put("showLibrary2", showLibrary); put("showToday", showToday); put("iconFillRound", iconFillRound)
        put("liveIcons", liveIcons); put("liveClockSeconds", liveClockSeconds)
        put("iconRimWidth", iconRimWidth.toDouble()); put("wallpaperTarget", wallpaperTarget)
        put("liveIconTheme", liveIconTheme); put("appTransitions2", appTransitions)
        put("searchSuggestCorner", searchSuggestCorner.toDouble()); put("searchSuggestLabelSize", searchSuggestLabelSize.toDouble())
        put("searchSuggestLabelWeight", searchSuggestLabelWeight.toDouble())
        put("glassRefraction", glassRefraction.toDouble()); put("glassEdgeWidth", glassEdgeWidth.toDouble()); put("glassEdgeDark", glassEdgeDark.toDouble()); put("glassTint", glassTint.toDouble())
        put("syncSystemWallpaper", syncSystemWallpaper); put("glassStyle", glassStyle); put("panelGlassSync", panelGlassSync)
        put("searchSuggestExpanded", searchSuggestExpanded)
        put("searchShowLabels", searchShowLabels); put("searchNeutral2", searchNeutral)
        put("searchContacts", searchContacts); put("searchMessages", searchMessages); put("searchFiles", searchFiles)
        put("searchDeviceCard", searchDeviceCard)
        put("searchFieldTextSize", searchFieldTextSize.toDouble()); put("searchResultTextSize", searchResultTextSize.toDouble())
        put("searchResultWeight", searchResultWeight.toDouble()); put("searchResultsPanel", searchResultsPanel)
        put("searchGlassBrightness2", searchGlassBrightness.toDouble())
        put("textShadow", textShadow); put("textShadowStrength", textShadowStrength.toDouble()); put("textShadowSpread", textShadowSpread.toDouble())
        put("settingsTheme", settingsTheme); put("labelWeight", labelWeight.toDouble())
        put("cornerSmoothing", cornerSmoothing.toDouble()); put("iconRimGlow", iconRimGlow.toDouble())
        put("editButtonTextSize", editButtonTextSize.toDouble()); put("editButtonWeight", editButtonWeight.toDouble())
        put("metricsVersion", METRICS_VERSION)
        put("glassStrength", glassStrength.toDouble()); put("theme", theme.name)
        put("launcherWallpaper", launcherWallpaper); put("wallpaperDim", wallpaperDim.toDouble())
        put("animSpeed", animSpeed.toDouble())
        put("realBlur", realBlur); put("autoQuality", autoQuality); put("lowPower", lowPower)
        put("addNewToHome", addNewToHome); put("showBadges", showBadges); put("homeWallpaperBlur", homeWallpaperBlur.toDouble()); put("recents", JSONArray(recents))
    }

    companion object {
        /**
         * Ölçü düzeni sürümü. 2: iOS ekran görüntüsünden ölçülen yeni varsayılanlar. Daha eski kayıtlarda
         * simge/aralık/etiket/dock ölçüleri yeni varsayılanlara çekilir (diğer ayarlar korunur).
         */
        const val METRICS_VERSION = 2

        /** Eksik veya hatalı alanlar varsayılan değerle doldurulur (eski yedeklerle uyumlu). */
        fun fromJson(o: JSONObject): LauncherSettings {
            val d = LauncherSettings()
            fun f(k: String, def: Float) = if (o.has(k)) o.optDouble(k, def.toDouble()).toFloat() else def
            val icons = o.optJSONObject("customIcons")
            val iconMap = buildMap {
                icons?.keys()?.forEach { k -> put(k, icons.optString(k)) }
            }
            val rec = o.optJSONArray("recents")
            return LauncherSettings(
                columns = o.optInt("columns", d.columns),
                rows = o.optInt("rows", d.rows),
                showLabels = o.optBoolean("showLabels", d.showLabels),
                hSpacing = f("hSpacing", d.hSpacing),
                vSpacing = f("vSpacing", d.vSpacing),
                labelGap = f("labelGap", d.labelGap),
                labelSize = f("labelSize", d.labelSize),
                iconScale = f("iconScale", d.iconScale),
                iconStyle = runCatching { IconStyle.valueOf(o.optString("iconStyle")) }.getOrDefault(d.iconStyle),
                iconStyleDark = runCatching { IconStyle.valueOf(o.optString("iconStyleDark")) }.getOrDefault(d.iconStyleDark),
                darkOriginalApps = o.optJSONArray("darkOriginalApps")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() } ?: d.darkOriginalApps,
                tintColor = o.optInt("tintColor", d.tintColor),
                iconCorner = f("iconCorner", d.iconCorner),
                iconGlass = o.optBoolean("iconGlass", d.iconGlass),
                customIcons = iconMap,
                dockHeight = f("dockHeight", d.dockHeight),
                dockWidth = f("dockWidth", d.dockWidth),
                dockOpacity = f("dockOpacity", d.dockOpacity),
                folderOpacity = f("folderOpacity", d.folderOpacity),
                glassStrength = f("glassStrength", d.glassStrength),
                theme = runCatching { ThemeMode.valueOf(o.optString("theme")) }.getOrDefault(d.theme),
                launcherWallpaper = o.optBoolean("launcherWallpaper", d.launcherWallpaper),
                wallpaperDim = f("wallpaperDim", d.wallpaperDim),
                animSpeed = f("animSpeed", d.animSpeed),
                realBlur = o.optBoolean("realBlur", d.realBlur),
                autoQuality = o.optBoolean("autoQuality", d.autoQuality),
                lowPower = o.optBoolean("lowPower", d.lowPower),
                addNewToHome = o.optBoolean("addNewToHome", d.addNewToHome),
                showBadges = o.optBoolean("showBadges", d.showBadges),
                homeWallpaperBlur = o.optDouble("homeWallpaperBlur", d.homeWallpaperBlur.toDouble()).toFloat(),
                recents = if (rec == null) emptyList() else List(rec.length()) { rec.optString(it) }.filter { it.isNotEmpty() },
                dockBottom = f("dockBottom", d.dockBottom),
                folderSize = f("folderSize", d.folderSize),
                folderCorner = f("folderCorner", d.folderCorner),
                // "libraryTileCorner2": iOS 26 ölçüsüne (23 pt) geçiş; eski kayıttaki değer bir kez yok sayılır.
                libraryTileCorner = f("libraryTileCorner2", d.libraryTileCorner),
                libraryTileCornerDock = o.optBoolean("libraryTileCornerDock", d.libraryTileCornerDock),
                libraryGroupCorner = f("libraryGroupCorner", d.libraryGroupCorner),
                glassRimWidth = f("glassRimWidth", d.glassRimWidth),
                openFolderTitleSize = f("openFolderTitleSize", d.openFolderTitleSize),
                glassBlur = f("glassBlur", d.glassBlur),
                glassBrightness = f("glassBrightness", d.glassBrightness),
                glassRimGlow = f("glassRimGlow", d.glassRimGlow),
                glassOpacity = f("glassOpacity", d.glassOpacity),
                pillTextSize = f("pillTextSize", d.pillTextSize),
                pillTextWeight = f("pillTextWeight", d.pillTextWeight),
                menuCorner = f("menuCorner", d.menuCorner),
                menuWidth = f("menuWidth", d.menuWidth),
                menuTextSize = f("menuTextSize", d.menuTextSize),
                menuRowHeight = f("menuRowHeight", d.menuRowHeight),
                menuOpacity = f("menuOpacity", d.menuOpacity),
                menuDark = o.optBoolean("menuDark", d.menuDark),
                dockCorner = f("dockCorner", d.dockCorner),
                pillScale = f("pillScale", d.pillScale),
                pillOffset = f("pillOffset", d.pillOffset),
                topSwipe = runCatching { SwipeAction.valueOf(o.optString("topSwipe")) }.getOrDefault(d.topSwipe),
                midSwipe = runCatching { SwipeAction.valueOf(o.optString("midSwipe2")) }.getOrDefault(d.midSwipe),
                doubleTapLock = o.optBoolean("doubleTapLock", d.doubleTapLock),
                // "showLibrary2": arşiv yeniden varsayılan olarak açık (eski kayıttaki kapalı değer bir kez yok sayılır).
                showLibrary = o.optBoolean("showLibrary2", d.showLibrary),
                showToday = o.optBoolean("showToday", d.showToday),
                iconFillRound = o.optBoolean("iconFillRound", d.iconFillRound),
                liveIcons = o.optBoolean("liveIcons", d.liveIcons),
                iconRimWidth = o.optDouble("iconRimWidth", d.iconRimWidth.toDouble()).toFloat(),
                liveIconTheme = o.optString("liveIconTheme", d.liveIconTheme),
                appTransitions = o.optBoolean("appTransitions2", d.appTransitions),
                searchSuggestCorner = o.optDouble("searchSuggestCorner", d.searchSuggestCorner.toDouble()).toFloat(),
                glassRefraction = o.optDouble("glassRefraction", d.glassRefraction.toDouble()).toFloat(),
                glassEdgeWidth = o.optDouble("glassEdgeWidth", d.glassEdgeWidth.toDouble()).toFloat(),
                glassEdgeDark = o.optDouble("glassEdgeDark", d.glassEdgeDark.toDouble()).toFloat(),
                glassTint = o.optDouble("glassTint", d.glassTint.toDouble()).toFloat(),
                syncSystemWallpaper = o.optBoolean("syncSystemWallpaper", d.syncSystemWallpaper),
                searchSuggestExpanded = o.optBoolean("searchSuggestExpanded", d.searchSuggestExpanded),
                glassStyle = o.optString("glassStyle", d.glassStyle).takeIf { it == "clear" || it == "frosted" } ?: d.glassStyle,
                panelGlassSync = o.optString("panelGlassSync", d.panelGlassSync).takeIf { it == "general" || it == "dock" } ?: d.panelGlassSync,
                searchSuggestLabelSize = o.optDouble("searchSuggestLabelSize", d.searchSuggestLabelSize.toDouble()).toFloat(),
                searchSuggestLabelWeight = o.optDouble("searchSuggestLabelWeight", d.searchSuggestLabelWeight.toDouble()).toFloat(),
                wallpaperTarget = o.optString("wallpaperTarget", d.wallpaperTarget),
                liveClockSeconds = o.optBoolean("liveClockSeconds", d.liveClockSeconds),
                searchShowLabels = o.optBoolean("searchShowLabels", d.searchShowLabels),
                searchNeutral = o.optBoolean("searchNeutral2", d.searchNeutral),
                searchContacts = o.optBoolean("searchContacts", d.searchContacts),
                searchMessages = o.optBoolean("searchMessages", d.searchMessages),
                searchFiles = o.optBoolean("searchFiles", d.searchFiles),
                searchDeviceCard = o.optBoolean("searchDeviceCard", d.searchDeviceCard),
                searchFieldTextSize = f("searchFieldTextSize", d.searchFieldTextSize),
                searchResultTextSize = f("searchResultTextSize", d.searchResultTextSize),
                searchResultWeight = f("searchResultWeight", d.searchResultWeight),
                searchResultsPanel = o.optBoolean("searchResultsPanel", d.searchResultsPanel),
                searchGlassBrightness = f("searchGlassBrightness2", d.searchGlassBrightness),
                textShadow = o.optBoolean("textShadow", d.textShadow),
                textShadowStrength = f("textShadowStrength", d.textShadowStrength),
                textShadowSpread = f("textShadowSpread", d.textShadowSpread),
                settingsTheme = o.optString("settingsTheme", d.settingsTheme),
                labelWeight = f("labelWeight", d.labelWeight),
                cornerSmoothing = f("cornerSmoothing", d.cornerSmoothing),
                iconRimGlow = f("iconRimGlow", d.iconRimGlow),
                editButtonTextSize = f("editButtonTextSize", d.editButtonTextSize),
                editButtonWeight = f("editButtonWeight", d.editButtonWeight),
            ).let { loaded ->
                if (o.optInt("metricsVersion", 1) >= METRICS_VERSION) loaded
                else loaded.copy(
                    iconScale = d.iconScale, hSpacing = d.hSpacing, vSpacing = d.vSpacing,
                    labelGap = d.labelGap, labelSize = d.labelSize, rows = d.rows,
                    dockHeight = d.dockHeight, dockWidth = d.dockWidth, dockBottom = d.dockBottom,
                )
            }.sanitized()
        }
    }
}

/**
 * Ayarlar ve ana ekran yerleşimi DataStore'da saklanır (uygulama kapanınca/çökünce kaybolmaz).
 * Ayarlar tek bir JSON değeri olarak tutulur; böylece yedekleme/geri yükleme aynı biçimi kullanır.
 */
class SettingsStore(private val context: Context) {
    private object Keys {
        val settings = stringPreferencesKey("settings_v2")
        val layout = stringPreferencesKey("home_layout")
        val layoutBackup = stringPreferencesKey("home_layout_backup")
        // Eski sürüm (0.5) anahtarları — yalnızca ilk geçişte okunur.
        val columns = intPreferencesKey("columns")
        val rows = intPreferencesKey("rows")
        val showLabels = booleanPreferencesKey("show_labels")
        val iconScale = floatPreferencesKey("icon_scale")
        val iconGlass = booleanPreferencesKey("icon_glass")
        val glassStrength = floatPreferencesKey("glass_strength")
        val recents = stringPreferencesKey("recents")
        val addNewToHome = booleanPreferencesKey("add_new_to_home")
    }

    private fun read(p: Preferences): LauncherSettings {
        p[Keys.settings]?.let { json ->
            return runCatching { LauncherSettings.fromJson(JSONObject(json)) }.getOrDefault(LauncherSettings())
        }
        val d = LauncherSettings()
        return LauncherSettings(
            columns = p[Keys.columns] ?: d.columns,
            rows = p[Keys.rows] ?: d.rows,
            showLabels = p[Keys.showLabels] ?: d.showLabels,
            // Eski (0.5) kayıtlarda simge ölçüsü yeni varsayılana çekilir (ölçü düzeni 2).
            iconGlass = p[Keys.iconGlass] ?: d.iconGlass,
            glassStrength = p[Keys.glassStrength] ?: d.glassStrength,
            recents = (p[Keys.recents] ?: "").split("|").filter { it.isNotEmpty() },
            addNewToHome = p[Keys.addNewToHome] ?: d.addNewToHome,
        ).sanitized()
    }

    val settings: Flow<LauncherSettings> = context.dataStore.data.map { read(it) }

    /** Ana ekran yerleşimi (JSON). Henüz kaydedilmediyse null. */
    val layoutJson: Flow<String?> = context.dataStore.data.map { it[Keys.layout] }

    suspend fun saveLayout(json: String) {
        context.dataStore.edit { it[Keys.layout] = json }
    }

    /** "iOS 26 dizilimini uygula" öncesindeki düzen (geri almak için). Yoksa null. */
    val layoutBackupJson: Flow<String?> = context.dataStore.data.map { it[Keys.layoutBackup] }

    suspend fun saveLayoutBackup(json: String?) {
        context.dataStore.edit { if (json == null) it.remove(Keys.layoutBackup) else it[Keys.layoutBackup] = json }
    }

    suspend fun update(block: (LauncherSettings) -> LauncherSettings) {
        context.dataStore.edit { p -> p[Keys.settings] = block(read(p)).sanitized().toJson().toString() }
    }

    suspend fun addRecent(component: String) = update {
        it.copy(recents = (listOf(component) + it.recents.filter { r -> r != component }).take(8))
    }

    /** Yedekleme: ayarlar + yerleşim tek JSON belgesinde. */
    suspend fun exportJson(layout: HomeLayout, current: LauncherSettings): String = JSONObject()
        .put("app", "GlassLauncher")
        .put("format", 1)
        .put("settings", current.toJson())
        .put("layout", layout.toJsonObject())
        .toString(2)

    /** Geri yükleme: geçersiz belge için null döner; geçerliyse ayarları yazar ve yerleşimi döndürür. */
    suspend fun importJson(json: String): HomeLayout? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optString("app") != "GlassLauncher") return null
        val layout = o.optJSONObject("layout")?.let { HomeLayout.fromJsonObject(it) } ?: return null
        val s = o.optJSONObject("settings")?.let { LauncherSettings.fromJson(it) } ?: LauncherSettings()
        context.dataStore.edit {
            it[Keys.settings] = s.toJson().toString()
            it[Keys.layout] = layout.toJson()
        }
        return layout
    }
}
