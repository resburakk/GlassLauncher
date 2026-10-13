package dev.glass.launcher.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.glass.launcher.data.LauncherSettings

/**
 * iPhone (393pt genişlik) ölçülerinden türetilen oranlar (göz kararı, cihazda ince ayar gerekebilir):
 * simge 60pt (0.1527), yatay adım 90pt (0.229), satır adımı ~96pt (x1.07), sol kenar ~31pt (0.08),
 * dock yüksekliği ~96pt (0.244), dock yan boşluğu ~16pt (0.04).
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

    companion object {
        val IndicatorHeight = 40.dp

        fun compute(width: Dp, height: Dp, s: LauncherSettings): HomeMetrics {
            val cols = s.columns
            val f = 4f / cols
            val pitchX = width * 0.229f * f
            val icon = (width * 0.1527f * f * s.iconScale).coerceAtMost(pitchX * 0.92f)
            val marginX = (width - icon - pitchX * (cols - 1)) / 2f
            val pitchY = pitchX * 1.07f
            val topPad = width * 0.04f

            // Dock her zaman 4 simgelik, ızgara sütun sayısından bağımsız.
            val dockH = width * 0.244f
            val dockMarginX = width * 0.04f
            val dockPitch = width * 0.229f
            val dockIcon = (width * 0.1527f * s.iconScale).coerceAtMost(minOf(dockPitch * 0.92f, dockH * 0.78f))
            val dockInnerStart = ((width - dockMarginX * 2f) - dockIcon - dockPitch * 3f) / 2f
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
