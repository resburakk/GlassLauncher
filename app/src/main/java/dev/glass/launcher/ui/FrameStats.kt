package dev.glass.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sayfa kaydırmalarındaki gerçek kare ölçümü (telefonda akıcılığı sayıyla görmek için).
 * Ana ekranda/arşive her kaydırmada kare aralıkları ölçülür; Ayarlar > Cam > Performans'ta gösterilir.
 */
object FrameStats {
    var text by mutableStateOf("Henüz ölçüm yok: ana ekranda birkaç kez sayfa kaydırın.")
        private set

    /** [intervalsNs]: art arda karelerin arası (ns), [refresh]: ekranın bildirdiği yenileme hızı (Hz). */
    fun report(intervalsNs: LongArray, refresh: Float) {
        if (intervalsNs.size < 8) return
        val ms = intervalsNs.map { it / 1_000_000f }.sorted()
        val median = ms[ms.size / 2]
        val expected = 1000f / refresh.coerceAtLeast(1f)
        val late = ms.count { it > expected * 1.5f }
        val worst = ms.last()
        val hz = 1000f / median.coerceAtLeast(0.1f)
        text = "Son kaydırma: ${ms.size} kare, ortalama ≈ ${hz.toInt()} Hz " +
            "(kare arası ${"%.1f".format(median)} ms). Takılan kare: $late (%${(late * 100 / ms.size)}), " +
            "en uzun ${"%.0f".format(worst)} ms. Ekranın bildirdiği hız: ${refresh.toInt()} Hz."
    }
}
