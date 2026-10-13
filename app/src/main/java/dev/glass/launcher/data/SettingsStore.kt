package dev.glass.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "launcher_settings")

data class LauncherSettings(
    val columns: Int = 4,
    val rows: Int = 6,
    val showLabels: Boolean = true,
    val iconScale: Float = 1.1f,
    val iconGlass: Boolean = true,
    val glassStrength: Float = 1f,
    /** Son başlatılan uygulamalar (ComponentName.flattenToString), en yenisi başta. */
    val recents: List<String> = emptyList(),
    /** Yeni kurulan uygulamalar ana ekrana mı eklensin (kapalıysa yalnızca Uygulama Arşivi'nde). */
    val addNewToHome: Boolean = true,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val columns = intPreferencesKey("columns")
        val rows = intPreferencesKey("rows")
        val showLabels = booleanPreferencesKey("show_labels")
        val iconScale = floatPreferencesKey("icon_scale")
        val iconGlass = booleanPreferencesKey("icon_glass")
        val glassStrength = floatPreferencesKey("glass_strength")
        val recents = stringPreferencesKey("recents")
        val layout = stringPreferencesKey("home_layout")
        val addNewToHome = booleanPreferencesKey("add_new_to_home")
    }

    private fun read(p: Preferences): LauncherSettings {
        val d = LauncherSettings()
        return LauncherSettings(
            columns = (p[Keys.columns] ?: d.columns).coerceIn(3, 6),
            rows = (p[Keys.rows] ?: d.rows).coerceIn(4, 8),
            showLabels = p[Keys.showLabels] ?: d.showLabels,
            iconScale = (p[Keys.iconScale] ?: d.iconScale).coerceIn(0.7f, 1.4f),
            iconGlass = p[Keys.iconGlass] ?: d.iconGlass,
            glassStrength = (p[Keys.glassStrength] ?: d.glassStrength).coerceIn(0.5f, 1.5f),
            recents = (p[Keys.recents] ?: "").split("|").filter { it.isNotEmpty() },
            addNewToHome = p[Keys.addNewToHome] ?: d.addNewToHome,
        )
    }

    private fun write(p: MutablePreferences, s: LauncherSettings) {
        p[Keys.columns] = s.columns
        p[Keys.rows] = s.rows
        p[Keys.showLabels] = s.showLabels
        p[Keys.iconScale] = s.iconScale
        p[Keys.iconGlass] = s.iconGlass
        p[Keys.glassStrength] = s.glassStrength
        p[Keys.recents] = s.recents.joinToString("|")
        p[Keys.addNewToHome] = s.addNewToHome
    }

    val settings: Flow<LauncherSettings> = context.dataStore.data.map { read(it) }

    /** Ana ekran yerleşimi (JSON). Henüz kaydedilmediyse null. */
    val layoutJson: Flow<String?> = context.dataStore.data.map { it[Keys.layout] }

    suspend fun saveLayout(json: String) {
        context.dataStore.edit { it[Keys.layout] = json }
    }

    suspend fun update(block: (LauncherSettings) -> LauncherSettings) {
        context.dataStore.edit { p -> write(p, block(read(p))) }
    }

    suspend fun addRecent(component: String) = update {
        it.copy(recents = (listOf(component) + it.recents.filter { r -> r != component }).take(8))
    }
}
