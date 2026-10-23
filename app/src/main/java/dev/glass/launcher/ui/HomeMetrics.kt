package dev.glass.launcher.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import dev.glass.launcher.data.LauncherSettings

/**
 * iOS "nokta" (pt) birimi: ekran genişliği / 402 (iPhone 16 Pro). Tüm ölçüler bu birimle ölçeklenir;
 * böylece Honor 600'de de iPhone'daki görsel oranlar korunur.
 */
val LocalPt = staticCompositionLocalOf { 1.dp }

/** Ana ekran ölçüleri; klasör, arama ve arşiv katmanları simgeleri ana ekranla aynı boyutta çizmek için okur. */
val LocalHomeMetrics = staticCompositionLocalOf<HomeMetrics?> { null }

/**
 * Ana ekran ölçüleri — kullanıcının paylaştığı iOS 26 ekran görüntüsünden (iPhone 16 Pro, 402×874 pt)
 * piksel ölçümüyle alınmıştır:
 *  - simge 65 pt, sütun adımı 92 pt (kenar boşluğu ≈ 30 pt), satır adımı 98 pt
 *  - ilk simge satırının üstü ekranın tepesinden 90 pt aşağıda
 *  - etiket 12 pt, simgenin 5 pt altında, hafif gölgeli
 *  - dock 98 pt yüksek, yanlardan 16.5 pt, ekranın altından 15 pt yukarıda; dock simgeleri 65 pt, adım 90 pt
 *  - arama hapı 77×29 pt; simge dizisinin altı ile dock'un üstü arasında TAM ORTADA (simetrik)
 * Tüm konumlar ekranın sol-üst köşesine göredir (durum çubuğu dahil), iOS'taki gibi.
 */
data class HomeMetrics(
    val pt: Dp,
    val columns: Int,
    val rows: Int,
    val icon: Dp,
    val pitchX: Dp,
    val pitchY: Dp,
    val marginX: Dp,
    /** İlk simge satırının üst kenarı (ekran tepesinden). */
    val firstTop: Dp,
    /** Simge dizisinin (son satırın etiketiyle birlikte) alt kenarı. */
    val gridBottom: Dp,
    val dockH: Dp,
    val dockMarginX: Dp,
    val dockTop: Dp,
    val dockIcon: Dp,
    val dockPitch: Dp,
    val dockInnerStart: Dp,
    val dockCorner: Dp,
    val pillW: Dp,
    val pillH: Dp,
    /** Arama hapının merkezinin ekran tepesine uzaklığı. */
    val pillCenterY: Dp,
) {
    val perPage: Int get() = columns * rows

    /**
     * iOS 26'da widget simge sütunlarının dış kenarından her yanda simgenin ~%5,5'i kadar taşar (iki widget
     * arasındaki boşluk simgeler arasındakinden dardır). Widget bu kadar sola/yukarı kaydırılarak çizilir.
     */
    val widgetBleed: Dp get() = icon * 0.055f

    /** [spanX]×[spanY] hücrelik widget'ın boyutu (simge kenarları + iOS taşma payı). */
    fun widgetWidth(spanX: Int): Dp = pitchX * (spanX - 1) + icon + widgetBleed * 2f
    /**
     * Yükseklik yatay adımla hesaplanır: iOS'ta küçük widget tam karedir, büyük widget iki küçük + bir boşluk
     * kadardır. Satırlar daha seyrek olduğundan kalan yer widget'ın altındaki ada kalır (iOS gibi).
     */
    fun widgetHeight(spanY: Int): Dp = pitchX * (spanY - 1) + icon + widgetBleed * 2f

    companion object {
        /** Referans ekran genişliği (pt). */
        const val REF_WIDTH_PT = 402f

        fun pt(screenWidth: Dp): Dp = screenWidth / REF_WIDTH_PT

        /**
         * @param width/height tam ekran boyutu (dp)
         * @param statusTop durum çubuğu yüksekliği; ilk satır en az bunun biraz altında başlar
         * @param navButtons üç tuşlu gezinti çubuğunun yüksekliği (hareketle gezintide 0): dock onun üstünde durur
         */
        fun compute(width: Dp, height: Dp, statusTop: Dp, s: LauncherSettings, navButtons: Dp = 0.dp): HomeMetrics {
            val u = pt(width)
            val cols = s.columns
            val f = 4f / cols

            // Izgara
            val basePitchX = u * 92f * f
            val icon = (u * 65f * f * s.iconScale).coerceAtMost(basePitchX * 0.96f)
            val maxPitch = (width - icon - 16.dp) / (cols - 1).coerceAtLeast(1).toFloat()
            val pitchX = max(min(basePitchX * s.hSpacing, maxPitch), icon + 2.dp)
            val marginX = (width - icon - pitchX * (cols - 1).toFloat()) / 2f
            val labelBlock = if (s.showLabels) u * (s.labelGap + s.labelSize * 1.25f) else 0.dp
            val pitchY = max(u * 98f * s.vSpacing, icon + labelBlock + u * 4f)
            val firstTop = max(u * 90f, statusTop + u * 12f)

            // Dock (her zaman 4 simge; ızgara sütunlarından bağımsız)
            // iOS 26 dock: simge dock kenarına her yandan eşit uzaklıkta (simgenin ~%31'i); yükseklik buna göre.
            val dockIcon = (u * 65f * s.iconScale).coerceAtMost(u * 90f * 0.96f)
            val dockH = dockIcon * 1.62f * s.dockHeight
            val dockInset = (dockH - dockIcon) / 2f
            val dockMarginX = (u * 16.5f + width * (1f - s.dockWidth) * 0.5f).coerceAtLeast(4.dp)
            // Üç tuşlu gezintide dock tuş çubuğunun üstüne binmez: çubuğun üstünde küçük bir boşlukla durur.
            val dockBottom = if (navButtons > 0.dp) max(u * s.dockBottom, navButtons + u * 8f) else u * s.dockBottom
            val dockTop = height - dockBottom - dockH
            val innerW = width - dockMarginX * 2f
            // Simgeler yanlardan da üst/alt kadar içeride, aralarındaki boşluk eşit (iOS gibi).
            val dockPitch = max((innerW - dockInset * 2f - dockIcon) / 3f, dockIcon + 2.dp)
            // Simge hücrenin sol kenarında durur: ilk simgenin sol kenarı = dockInset (son simgenin sağı da öyle).
            val dockInnerStart = dockInset
            // iOS 26 ekran görüntüsünden ölçüldü: dock köşesi yüksekliğin %37,8'i (ayardan değiştirilebilir).
            val dockCornerR = dockH * (if (s.dockCorner < 0f) 0.378f else s.dockCorner)

            // Arama hapı ve satır sayısı: hap, simge dizisinin altı ile dock arasında simetrik durur.
            val pillW = u * 77f * s.pillScale
            val pillH = u * 29f * s.pillScale
            val minGap = u * 14f
            val avail = dockTop - minGap * 2f - pillH - firstTop - icon - labelBlock
            val fit = (avail / pitchY).toInt() + 1
            val rows = fit.coerceIn(1, s.rows)
            val gridBottom = firstTop + pitchY * (rows - 1).toFloat() + icon + labelBlock
            val pillCenterY = (gridBottom + dockTop) / 2f - u * s.pillOffset

            return HomeMetrics(
                u, cols, rows, icon, pitchX, pitchY, marginX, firstTop, gridBottom,
                dockH, dockMarginX, dockTop, dockIcon, dockPitch, dockInnerStart, dockCornerR,
                pillW, pillH, pillCenterY,
            )
        }
    }
}

/**
 * Üç tuşlu gezinti çubuğunun yüksekliği; hareketle gezintide 0. (Hareket çubuğu dokunulabilir öğe değildir,
 * tuşlar ise dokunulabilirdir: "tappableElement" alt kenarı yalnızca üç tuşta dolu gelir.)
 */
@androidx.compose.runtime.Composable
fun navButtonsDp(): Dp {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val h = with(d) { WindowInsets.tappableElement.getBottom(this).toDp() }
    // MagicOS hareket çubuğunu da dokunulabilir bildiriyor (≈ 20–30 dp); üç tuşlu çubuk ≈ 48 dp.
    return if (h >= 40.dp) h else 0.dp
}
