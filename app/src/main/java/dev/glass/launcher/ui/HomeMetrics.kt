package dev.glass.launcher.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import dev.glass.launcher.data.LauncherSettings

/**
 * Ekran ölçüleri. iPhone (393pt genişlik) oranlarından türetilir (göz kararı; cihazda ince ayar gerekebilir):
 * simge 60pt (0.1527), yatay adım 90pt (0.229), satır adımı ~96pt (×1.07), dock yüksekliği ~96pt (0.244),
 * dock yan boşluğu ~16pt (0.04). Simge boyutu, yatay/dikey aralık, etiket ve dock ayarları birbirinden
 * bağımsız uygulanır.
 */
data class HomeMetrics(
    val columns: Int,
    val rows: Int,
    val icon: Dp,
    val pitchX: Dp,
    val pitchY: Dp,
    val marginX: Dp,
    val topPad: Dp,
    val dockH: Dp,
    val dockMarginX: Dp,
    val dockIcon: Dp,
    val dockPitch: Dp,
    val dockInnerStart: Dp,
    val dockReserve: Dp,
    val pillW: Dp,
    val pillH: Dp,
) {
    val perPage: Int get() = columns * rows

    /** [spanX]×[spanY] hücrelik widget'ın boyutu (iOS'taki gibi simge kenarlarına hizalı). */
    fun widgetWidth(spanX: Int): Dp = pitchX * (spanX - 1) + icon
    fun widgetHeight(spanY: Int): Dp = pitchY * (spanY - 1) + icon

    companion object {
        val IndicatorHeight = 44.dp

        fun compute(width: Dp, height: Dp, s: LauncherSettings): HomeMetrics {
            val cols = s.columns
            val f = 4f / cols
            val basePitch = width * 0.229f * f
            val icon = (width * 0.1527f * f * s.iconScale).coerceAtMost(basePitch * 0.95f)
            val maxPitch = (width - icon - 16.dp) / (cols - 1).coerceAtLeast(1).toFloat()
            val pitchX = max(min(basePitch * s.hSpacing, maxPitch), icon + 2.dp)
            val marginX = (width - icon - pitchX * (cols - 1).toFloat()) / 2f
            val labelH = (s.labelSize * 1.45f).dp
            val minPitchY = icon + s.labelGap.dp + (if (s.showLabels) labelH else 0.dp) + 6.dp
            val pitchY = max(basePitch * 1.07f * s.vSpacing, minPitchY)
            val topPad = width * 0.04f

            // Dock her zaman 4 simgelik, ızgara sütun sayısından bağımsız.
            val dockH = width * 0.244f * s.dockHeight
            val dockMarginX = (width * 0.04f + width * (1f - s.dockWidth) * 0.5f).coerceAtLeast(4.dp)
            val innerW = width - dockMarginX * 2f
            val dockIcon = (width * 0.1527f * s.iconScale).coerceAtMost(min(width * 0.229f * 0.92f, dockH * 0.78f))
            val dockPitch = min(width * 0.229f, (innerW * 0.94f - dockIcon) / 3f)
            val dockInnerStart = (innerW - dockIcon - dockPitch * 3f) / 2f
            val dockReserve = dockH + width * 0.03f

            val free = height - topPad - dockReserve - IndicatorHeight
            val rows = (free / pitchY).toInt().coerceIn(1, s.rows)
            return HomeMetrics(
                cols, rows, icon, pitchX, pitchY, marginX, topPad,
                dockH, dockMarginX, dockIcon, dockPitch, dockInnerStart, dockReserve,
                width * 0.27f, width * 0.085f,
            )
        }
    }
}
