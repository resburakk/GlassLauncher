package dev.glass.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Bir widget'ın ana ekranda kapladığı hücre sayısı. */
data class Span(val x: Int, val y: Int) {
    /** iOS adlarıyla boyut: Küçük 2×2, Orta 4×2, Büyük 4×4; diğerleri "3×2" gibi. */
    val title: String
        get() = when (x to y) {
            2 to 2 -> "Küçük"
            4 to 2 -> "Orta"
            4 to 4 -> "Büyük"
            else -> "$x×$y"
        }
}

/**
 * Sistem (Honor vb.) widget'larının boyut hesabı. Widget'ın kendi bildirdiği önerilen hücre sayısı ve
 * kabul ettiği en küçük boyut esas alınır; widget asla en küçük boyutunun altına sıkıştırılmaz
 * (alan yetmezse en küçük boyutta çizilip orantılı küçültülür, bkz. [layoutSize]).
 */
object WidgetSizing {

    /** Widget'ın kabul ettiği en küçük boyut (dp). [AppWidgetProviderInfo.minWidth] piksel cinsindendir. */
    fun minSizeDp(info: AppWidgetProviderInfo, density: Float): Pair<Float, Float> {
        val resH = (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
        val resV = (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
        val w = if (resH && info.minResizeWidth > 0) minOf(info.minResizeWidth, info.minWidth) else info.minWidth
        val h = if (resV && info.minResizeHeight > 0) minOf(info.minResizeHeight, info.minHeight) else info.minHeight
        return (w / density) to (h / density)
    }

    /** Widget'ın izin verdiği en büyük boyut (dp); sınır yoksa null. */
    private fun maxSizeDp(info: AppWidgetProviderInfo, density: Float): Pair<Float?, Float?> =
        info.maxResizeWidth.takeIf { it > 0 }?.let { it / density } to info.maxResizeHeight.takeIf { it > 0 }?.let { it / density }

    /** [wDp]×[hDp] boyutunu karşılayan en küçük hücre sayısı (sayfa sınırına kırpılır). */
    fun spanForDp(wDp: Float, hDp: Float, m: HomeMetrics): Span {
        var x = 1
        while (x < m.columns && m.widgetWidth(x).value + 0.5f < wDp) x++
        var y = 1
        while (y < m.rows && m.widgetHeight(y).value + 0.5f < hDp) y++
        return Span(x, y)
    }

    /** Widget eklenirken önerilen boyut: widget'ın bildirdiği hedef hücre sayısı, yoksa en küçük boyutu. */
    fun defaultSpan(info: AppWidgetProviderInfo, m: HomeMetrics, density: Float): Span {
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            // Android hücreleri iOS'takinden küçük olabilir; hedef yine de en küçük boyutu karşılamalı.
            val t = Span(info.targetCellWidth.coerceIn(1, m.columns), info.targetCellHeight.coerceIn(1, m.rows))
            val (mw, mh) = minSizeDp(info, density)
            val need = spanForDp(mw, mh, m)
            return Span(maxOf(t.x, need.x), maxOf(t.y, need.y))
        }
        return spanForDp(info.minWidth / density, info.minHeight / density, m)
    }

    /**
     * Seçilebilecek boyutlar: önerilen boyut + widget'ın yeniden boyutlandırma izni ve sınırları içinde
     * kalan iOS boyutları (2×2, 4×2, 4×4) ve birkaç ara boyut. Küçükten büyüğe sıralı.
     */
    fun sizeOptions(info: AppWidgetProviderInfo, m: HomeMetrics, density: Float): List<Span> {
        val def = defaultSpan(info, m, density)
        val resH = (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
        val resV = (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
        val (minW, minH) = minSizeDp(info, density)
        val (maxW, maxH) = maxSizeDp(info, density)
        val candidates = listOf(Span(2, 2), Span(4, 2), Span(4, 4), Span(4, 1), Span(2, 1), Span(4, 3), Span(1, 1))
        val ok = candidates.filter { s ->
            s.x <= m.columns && s.y <= m.rows &&
                (s.x == def.x || resH) && (s.y == def.y || resV) &&
                m.widgetWidth(s.x).value + 0.5f >= minW && m.widgetHeight(s.y).value + 0.5f >= minH &&
                (maxW == null || m.widgetWidth(s.x).value <= maxW + 0.5f) &&
                (maxH == null || m.widgetHeight(s.y).value <= maxH + 0.5f)
        }
        return (listOf(def) + ok).distinct().sortedBy { it.x * it.y }.take(5)
    }

    /**
     * Widget'ın çizileceği gerçek boyut: kapladığı alan, widget'ın en küçük boyutundan küçükse en küçük
     * boyut kullanılır. Dönen ölçek (≤ 1) ile orantılı küçültülür; en-boy oranı korunur, germe yoktur.
     */
    fun layoutSize(info: AppWidgetProviderInfo?, width: Dp, height: Dp, density: Float): Triple<Dp, Dp, Float> {
        if (info == null) return Triple(width, height, 1f)
        val (mw, mh) = minSizeDp(info, density)
        // Tek bir ölçek: iki yönde de en küçük boyut karşılanır. Çizim alanı aynı en-boy oranında büyütülür,
        // böylece küçültülünce alanı tam doldurur (kenarda boşluk, germe yok).
        val scale = minOf(if (mw > 0f) width.value / mw else 1f, if (mh > 0f) height.value / mh else 1f, 1f)
        return Triple((width.value / scale).dp, (height.value / scale).dp, scale)
    }
}
