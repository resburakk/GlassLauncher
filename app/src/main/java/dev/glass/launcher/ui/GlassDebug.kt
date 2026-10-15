package dev.glass.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** GEÇİCİ tanı bayrağı (yalnızca test turunda adb ile ayarlanır): cam katmanlarını tek tek kapatır. */
object GlassDebug {
    var mode by mutableIntStateOf(0)
}
