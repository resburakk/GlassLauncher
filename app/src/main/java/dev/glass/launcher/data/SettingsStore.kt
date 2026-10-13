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
    val labelSize: Float = 12f,
    // Simgeler
    val iconScale: Float = 1f,
    val iconStyle: IconStyle = IconStyle.Original,
    val tintColor: Int = 0xFF64D2FF.toInt(),
    val iconCorner: Float = 0.225f,
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
    val dockCorner: Float = 0.34f,
    /** Arama düğmesi boyut çarpanı. */
    val pillScale: Float = 1f,
    /** Arama düğmesinin ortalanmış konumdan yukarı (+) / aşağı (−) kayması (pt). */
    val pillOffset: Float = 0f,
    // Klasörler
    val folderOpacity: Float = 1f,
    /** Açık klasör panelinin ekran genişliğine oranı. */
    val folderSize: Float = 0.74f,
    /** Açık klasör panelinin köşe yuvarlaklığı (pt; iOS 26 ≈ 34). */
    val folderCorner: Float = 34f,
    /** Uygulama arşivindeki kapalı klasör kutusunun köşe yuvarlaklığı (pt; iOS ≈ 20). */
    val libraryTileCorner: Float = 20f,
    /** Uygulama arşivindeki açık klasörün köşe yuvarlaklığı (pt; iOS ≈ 34). */
    val libraryGroupCorner: Float = 34f,
    /** Tüm cam yüzeylerdeki beyaz kenar çizgisinin kalınlığı (pt; 0 = çizgi yok). */
    val glassRimWidth: Float = 1f,
    /** Açık klasörün (ana ekran ve arşiv) adının yazı boyutu (pt). */
    val openFolderTitleSize: Float = 20f,
    // Hareketler
    /** Ekranın üst kenarından aşağı kaydırma. */
    val topSwipe: SwipeAction = SwipeAction.ControlCenter,
    /** Ekranın ortasından (boş alandan) aşağı kaydırma. */
    val midSwipe: SwipeAction = SwipeAction.Search,
    /** Boş alana çift dokununca ekranı kilitle (Erişilebilirlik hizmeti gerekir). */
    val doubleTapLock: Boolean = true,
    /** Uygulama arşivi sayfası (ana ekranın sağı). Varsayılan kapalı: sayfa hiç görünmez. */
    val showLibrary: Boolean = false,
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
    /** Son başlatılan uygulamalar (ComponentName.flattenToString), en yenisi başta. */
    val recents: List<String> = emptyList(),
) {
    fun sanitized(): LauncherSettings = copy(
        columns = columns.coerceIn(3, 6),
        rows = rows.coerceIn(4, 8),
        hSpacing = hSpacing.coerceIn(0.8f, 1.3f),
        vSpacing = vSpacing.coerceIn(0.8f, 1.4f),
        labelGap = labelGap.coerceIn(0f, 14f),
        labelSize = labelSize.coerceIn(9f, 16f),
        iconScale = iconScale.coerceIn(0.7f, 1.4f),
        iconCorner = iconCorner.coerceIn(0.05f, 0.5f),
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
        libraryTileCorner = libraryTileCorner.coerceIn(8f, 40f),
        libraryGroupCorner = libraryGroupCorner.coerceIn(8f, 80f),
        glassRimWidth = glassRimWidth.coerceIn(0f, 4f),
        openFolderTitleSize = openFolderTitleSize.coerceIn(10f, 40f),
        glassStrength = glassStrength.coerceIn(0.5f, 1.5f),
        wallpaperDim = wallpaperDim.coerceIn(0f, 0.6f),
        animSpeed = animSpeed.coerceIn(0.5f, 2f),
        recents = recents.take(8),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("columns", columns); put("rows", rows); put("showLabels", showLabels)
        put("hSpacing", hSpacing.toDouble()); put("vSpacing", vSpacing.toDouble())
        put("labelGap", labelGap.toDouble()); put("labelSize", labelSize.toDouble())
        put("iconScale", iconScale.toDouble()); put("iconStyle", iconStyle.name); put("tintColor", tintColor)
        put("iconCorner", iconCorner.toDouble()); put("iconGlass", iconGlass)
        put("customIcons", JSONObject(customIcons as Map<*, *>))
        put("dockHeight", dockHeight.toDouble()); put("dockWidth", dockWidth.toDouble())
        put("dockOpacity", dockOpacity.toDouble()); put("folderOpacity", folderOpacity.toDouble())
        put("dockBottom", dockBottom.toDouble()); put("folderSize", folderSize.toDouble()); put("folderCorner", folderCorner.toDouble())
        put("libraryTileCorner", libraryTileCorner.toDouble()); put("libraryGroupCorner", libraryGroupCorner.toDouble())
        put("glassRimWidth", glassRimWidth.toDouble()); put("openFolderTitleSize", openFolderTitleSize.toDouble())
        put("dockCorner", dockCorner.toDouble()); put("pillScale", pillScale.toDouble()); put("pillOffset", pillOffset.toDouble())
        put("topSwipe", topSwipe.name); put("midSwipe", midSwipe.name); put("doubleTapLock", doubleTapLock); put("showLibrary", showLibrary)
        put("metricsVersion", METRICS_VERSION)
        put("glassStrength", glassStrength.toDouble()); put("theme", theme.name)
        put("launcherWallpaper", launcherWallpaper); put("wallpaperDim", wallpaperDim.toDouble())
        put("animSpeed", animSpeed.toDouble())
        put("realBlur", realBlur); put("autoQuality", autoQuality); put("lowPower", lowPower)
        put("addNewToHome", addNewToHome); put("recents", JSONArray(recents))
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
                recents = if (rec == null) emptyList() else List(rec.length()) { rec.optString(it) }.filter { it.isNotEmpty() },
                dockBottom = f("dockBottom", d.dockBottom),
                folderSize = f("folderSize", d.folderSize),
                folderCorner = f("folderCorner", d.folderCorner),
                libraryTileCorner = f("libraryTileCorner", d.libraryTileCorner),
                libraryGroupCorner = f("libraryGroupCorner", d.libraryGroupCorner),
                glassRimWidth = f("glassRimWidth", d.glassRimWidth),
                openFolderTitleSize = f("openFolderTitleSize", d.openFolderTitleSize),
                dockCorner = f("dockCorner", d.dockCorner),
                pillScale = f("pillScale", d.pillScale),
                pillOffset = f("pillOffset", d.pillOffset),
                topSwipe = runCatching { SwipeAction.valueOf(o.optString("topSwipe")) }.getOrDefault(d.topSwipe),
                midSwipe = runCatching { SwipeAction.valueOf(o.optString("midSwipe")) }.getOrDefault(d.midSwipe),
                doubleTapLock = o.optBoolean("doubleTapLock", d.doubleTapLock),
                showLibrary = o.optBoolean("showLibrary", d.showLibrary),
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
