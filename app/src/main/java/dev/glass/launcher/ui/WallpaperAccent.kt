package dev.glass.launcher.ui

import android.app.WallpaperColors
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Duvar kâğıdından alınan vurgu rengi (Android Material You gibi). Renkli simgelerde "Otomatik" renk ve
 * isteğe bağlı olarak cam yüzeylerin hafif renk tonu bundan gelir. Duvar kâğıdı değişince güncellenir.
 */
object WallpaperAccent {
    private val _color = MutableStateFlow(0xFF64D2FF.toInt())
    val color: StateFlow<Int> = _color

    fun update(colors: WallpaperColors?) {
        val c = colors?.primaryColor?.toArgb() ?: return
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        // Koyu zeminli simgede ve camda okunur kalsın: yeterince doygun ve açık.
        hsv[1] = hsv[1].coerceIn(0.40f, 0.85f)
        hsv[2] = hsv[2].coerceAtLeast(0.88f)
        _color.value = android.graphics.Color.HSVToColor(hsv)
    }
}

/** Ana ekranda geçerli vurgu rengi (ARGB). */
val LocalAccent = staticCompositionLocalOf { 0xFF64D2FF.toInt() }
