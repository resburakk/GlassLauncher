package dev.glass.launcher.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherSettingsTest {
    @Test fun json_roundTrip() {
        val s = LauncherSettings(
            columns = 5, iconStyle = IconStyle.Tinted, tintColor = 0xFF30D158.toInt(), theme = ThemeMode.Dark,
            customIcons = mapOf("a#1" to "icon_1.img"), recents = listOf("x/y"), dockOpacity = 0.4f,
            addNewToHome = false, lowPower = true,
        )
        assertEquals(s, LauncherSettings.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test fun fromJson_missingFields_useDefaults_andClamp() {
        val s = LauncherSettings.fromJson(JSONObject("""{"columns": 12, "iconScale": 9}"""))
        assertEquals(6, s.columns)
        assertEquals(1.4f, s.iconScale)
        assertEquals(LauncherSettings().iconStyle, s.iconStyle)
    }

    @Test fun fromJson_unknownEnum_fallsBack() {
        val s = LauncherSettings.fromJson(JSONObject("""{"iconStyle": "Mor", "theme": "?"}"""))
        assertEquals(IconStyle.Original, s.iconStyle)
        assertEquals(ThemeMode.Auto, s.theme)
    }
}
