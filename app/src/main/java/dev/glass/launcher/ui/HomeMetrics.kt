package dev.glass.launcher.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
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
    /** Yatay (iPhone Duo gibi): dock sağ kenarda dikey; simgeler yukarıdan aşağı dizilir. */
    val dockVertical: Boolean = false,
    /** Dikey dock'un sol kenarı ve boyu (dockH = kalınlığı). */
    val dockLeft: Dp = 0.dp,
    val dockLen: Dp = 0.dp,
    /** Simge dizisinin ve arama hapının yerleştiği alanın genişliği (yatayda dock'un solu). */
    val contentW: Dp = 0.dp,
    /** Bu alanın sol kenarı (yatayda kamera deliği soldaysa onun sağı). */
    val contentX: Dp = 0.dp,
) {
    val perPage: Int get() = columns * rows

    /**
     * iOS 26'da widget simge sütunlarının dış kenarından her yanda simgenin ~%5,5'i kadar taşar (iki widget
     * arasındaki boşluk simgeler arasındakinden dardır). Widget bu kadar sola/yukarı kaydırılarak çizilir.
     */
    val widgetBleed: Dp get() = icon * 0.055f

    /** [spanX]×[spanY] hücrelik widget'ın boyutu (simge kenarları + iOS taşma payı). */
    /** Widget adımı: yatayda sütunlar seyrek, satırlar sık; widget satıra sığsın ve oranı bozulmasın diye küçüğü alınır. */
    val widgetStep: Dp get() = if (dockVertical) minOf(pitchX, pitchY) else pitchX
    fun widgetWidth(spanX: Int): Dp = widgetStep * (spanX - 1) + icon + widgetBleed * 2f
    /** Yatayda widget kendi sütun alanından dar kalır; alanın ortasına kaydırma payı. */
    fun widgetShiftX(spanX: Int): Dp = (pitchX - widgetStep) * (spanX - 1) / 2f
    /**
     * Yükseklik yatay adımla hesaplanır: iOS'ta küçük widget tam karedir, büyük widget iki küçük + bir boşluk
     * kadardır. Satırlar daha seyrek olduğundan kalan yer widget'ın altındaki ada kalır (iOS gibi).
     */
    fun widgetHeight(spanY: Int): Dp = widgetStep * (spanY - 1) + icon + widgetBleed * 2f

    companion object {
        /** Referans ekran genişliği (pt). */
        const val REF_WIDTH_PT = 402f

        fun pt(screenWidth: Dp): Dp = screenWidth / REF_WIDTH_PT

        /**
         * @param width/height tam ekran boyutu (dp)
         * @param statusTop durum çubuğu yüksekliği; ilk satır en az bunun biraz altında başlar
         * @param navButtons üç tuşlu gezinti çubuğunun yüksekliği (hareketle gezintide 0): dock onun üstünde durur
         */
        fun compute(
            width: Dp, height: Dp, statusTop: Dp, s: LauncherSettings, navButtons: Dp = 0.dp,
            cutLeft: Dp = 0.dp, cutRight: Dp = 0.dp,
        ): HomeMetrics {
            // Yatayda ölçüler ekranın kısa kenarına göre (dikeydeki genişlik): yoksa simgeler ve dock iki kat büyüyüp
            // ekranı kaplıyordu.
            val ref = min(width, height)
            val u = pt(ref)
            if (width > height * 1.1f) return landscape(width, height, statusTop, s, navButtons, u, cutLeft, cutRight)
            val cols = s.columns
            val f = 4f / cols

            // Izgara
            val basePitchX = u * 92f * f
            val icon = (u * 65f * f * s.iconScale).coerceAtMost(basePitchX * 0.96f)
            val maxPitch = (width - icon - 16.dp) / (cols - 1).coerceAtLeast(1).toFloat()
            val pitchX = max(min(basePitchX * s.hSpacing, maxPitch), icon + 2.dp)
            val marginX = (width - icon - pitchX * (cols - 1).toFloat()) / 2f
            val labelBlock = if (s.showLabels) u * (s.labelGap + s.labelSize * 1.25f) else 0.dp
            // iOS 26 ekran görüntüsünden ölçüldü: satır aralığı 100,5 pt (önce 98; satırlar aşağı indikçe kayıyordu).
            val pitchY = max(u * 100.5f * s.vSpacing, icon + labelBlock + u * 4f)
            val firstTop = max(u * 90f, statusTop + u * 12f)

            // Dock (her zaman 4 simge; ızgara sütunlarından bağımsız)
            // iOS 26 dock: simge dock kenarına her yandan eşit uzaklıkta (simgenin ~%31'i); yükseklik buna göre.
            val dockIcon = (u * 65f * s.iconScale).coerceAtMost(u * 90f * 0.96f)
            val dockH = dockIcon * 1.62f * s.dockHeight
            val dockInset = (dockH - dockIcon) / 2f
            val dockMarginX = (u * 16.5f + ref * (1f - s.dockWidth) * 0.5f + (width - ref) / 2f).coerceAtLeast(4.dp)
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
                pillW, pillH, pillCenterY, contentW = width,
            )
        }

        /**
         * Yatay ana ekran (iPhone Duo gibi): dock sağ kenarda dikey (4 simge yukarıdan aşağı), simgeler dock'un solundaki
         * alanda; sütun sayısı alana göre artar (sığdığı kadar, en çok 8), satırlar yüksekliğe sığdığı kadar. Arama hapı
         * simge alanının altında ortada. Ölçüler dikeydeki genişliğe (kısa kenar) göre.
         */
        private fun landscape(
            width: Dp, height: Dp, statusTop: Dp, s: LauncherSettings, navButtons: Dp, u: Dp,
            cutLeft: Dp, cutRight: Dp,
        ): HomeMetrics {
            val pc = s.columns
            val f = 4f / pc
            val basePitchX = u * 92f * f
            val icon = (u * 65f * f * s.iconScale).coerceAtMost(basePitchX * 0.96f)
            val labelBlock = if (s.showLabels) u * (s.labelGap + s.labelSize * 1.25f) else 0.dp

            val dockIcon = (u * 65f * s.iconScale).coerceAtMost(u * 90f * 0.96f)
            val dockT = dockIcon * 1.62f * s.dockHeight
            val dockInset = (dockT - dockIcon) / 2f
            val sideGap = u * (s.dockBottom * 0.8f).coerceAtLeast(8f)
            // Kamera deliği yatayda yan kenara düşer: o taraftaki öğeler deliğin içine girmesin.
            val dockLeft = width - max(sideGap, cutRight + u * 4f) - dockT
            val gridX = if (cutLeft > 0.dp) cutLeft + u * 2f else 0.dp
            val top = statusTop + u * 8f
            val bottomGap = max(u * 10f, navButtons + u * 6f)
            val dockLen = height - top - bottomGap
            val dockPitch = max((dockLen - dockInset * 2f - dockIcon) / 3f, dockIcon + 2.dp)

            val gridW = dockLeft - u * 6f - gridX
            val wantPitch = basePitchX * s.hSpacing
            val cols = (((gridW - icon - u * 24f) / wantPitch).toInt() + 1).coerceIn(pc, 8)
            val pitchX = max(min((gridW - icon - u * 24f) / (cols - 1).coerceAtLeast(1).toFloat(), wantPitch * 1.5f), icon + 2.dp)
            val marginX = gridX + (gridW - icon - pitchX * (cols - 1).toFloat()) / 2f

            val pillW = u * 77f * s.pillScale
            val pillH = u * 29f * s.pillScale
            val pillCenterY = height - bottomGap - pillH / 2f
            val firstTop = top + u * 4f
            val pitchY = max(u * 92f * s.vSpacing, icon + labelBlock + u * 4f)
            val avail = pillCenterY - pillH / 2f - u * 8f - firstTop - icon - labelBlock
            val rows = ((avail / pitchY).toInt() + 1).coerceIn(1, s.rows)
            val gridBottom = firstTop + pitchY * (rows - 1).toFloat() + icon + labelBlock
            val dockCornerR = dockT * (if (s.dockCorner < 0f) 0.378f else s.dockCorner)
            // dockMarginX: arama önerileri paneli gibi dock genişliğini kullanan yerler dikeydeki genişlikte kalsın.
            val ref = min(width, height)
            val dockMarginX = u * 16.5f + (width - ref) / 2f
            return HomeMetrics(
                u, cols, rows, icon, pitchX, pitchY, marginX, firstTop, gridBottom,
                dockT, dockMarginX, top, dockIcon, dockPitch, dockInset, dockCornerR,
                pillW, pillH, pillCenterY,
                dockVertical = true, dockLeft = dockLeft, dockLen = dockLen, contentW = gridW, contentX = gridX,
            )
        }
    }
}

/** Ekran kesiği (kamera deliği) sol kenardaysa genişliği; yatay düzende simgeler ondan uzak durur. */
@androidx.compose.runtime.Composable
fun cutoutLeftDp(): Dp {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    return with(d) { WindowInsets.displayCutout.getLeft(this, dir).toDp() }
}

/** Ekran kesiği (kamera deliği) sağ kenardaysa genişliği; yatay düzende dock ondan uzak durur. */
@androidx.compose.runtime.Composable
fun cutoutRightDp(): Dp {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    return with(d) { WindowInsets.displayCutout.getRight(this, dir).toDp() }
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
