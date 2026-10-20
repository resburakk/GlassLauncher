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
    /** Launcher'ın sistemin ana ekran görevinde çalışıp çalışmadığı (MainActivity.checkHomeTask). */
    var homeTask by mutableStateOf("Ana ekran görevi: henüz kontrol edilmedi.")
    // Telefonda geri/ana ekran olaylarının launcher'a ulaşıp ulaşmadığını görmek için sayaçlar.
    var backCallbacks by mutableStateOf(0)
    var backKeys by mutableStateOf(0)
    var homeIntents by mutableStateOf(0)
    var focusLost by mutableStateOf(0)
    var hasFocus by mutableStateOf(true)
    val eventsText: String
        get() = "Olaylar: geri işlendi $backCallbacks, geri tuşu $backKeys, ana ekran $homeIntents, " +
            "odak kaybı $focusLost (şu an ${if (hasFocus) "odakta" else "odakta DEĞİL"})."

    // --- Kare başına süre ayrımı (Android FrameMetrics): arayüz iş parçacığı, çizim (RenderThread), ekran kartı ---
    private val lock = Any()
    @Volatile private var recording = false
    private val samples = ArrayList<FloatArray>()

    /** Pencereye takılır (MainActivity); yalnızca kaydırma ölçülürken örnek toplar. */
    val listener = android.view.Window.OnFrameMetricsAvailableListener { _, fm, _ ->
        if (!recording) return@OnFrameMetricsAvailableListener
        fun d(id: Int) = fm.getMetric(id).coerceAtLeast(0L) / 1_000_000f
        val ui = d(android.view.FrameMetrics.INPUT_HANDLING_DURATION) + d(android.view.FrameMetrics.ANIMATION_DURATION) +
            d(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION) + d(android.view.FrameMetrics.DRAW_DURATION)
        val rt = d(android.view.FrameMetrics.SYNC_DURATION) + d(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) +
            d(android.view.FrameMetrics.SWAP_BUFFERS_DURATION)
        val gpu = if (android.os.Build.VERSION.SDK_INT >= 31) d(android.view.FrameMetrics.GPU_DURATION) else 0f
        synchronized(lock) { if (samples.size < 800) samples += floatArrayOf(ui, rt, gpu) }
    }

    /** Kaydırma başladı: ayrıntılı örnek toplamaya başla. */
    fun begin() {
        synchronized(lock) { samples.clear() }
        recording = true
    }

    private fun breakdown(budget: Float): String {
        recording = false
        val list = synchronized(lock) { ArrayList(samples) }
        if (list.size < 5) return ""
        fun part(i: Int, name: String): String {
            val v = list.map { it[i] }.sorted()
            val avg = v.average().toFloat()
            val over = v.count { it > budget }
            return "$name ort ${"%.1f".format(avg)} / en uzun ${"%.1f".format(v.last())} ms ($over kare bütçeyi aştı)"
        }
        return "\n\nKare süreleri (bütçe ${"%.1f".format(budget)} ms):\n• " + part(0, "Arayüz:") + "\n• " + part(1, "Çizim:") +
            "\n• " + part(2, "Ekran kartı:")
    }

    /** [intervalsNs]: art arda karelerin arası (ns), [refresh]: ekranın bildirdiği yenileme hızı (Hz). */
    fun report(intervalsNs: LongArray, refresh: Float, label: String = "Son kaydırma (ana ekran)") {
        if (intervalsNs.size < 8) { recording = false; return }
        val ms = intervalsNs.map { it / 1_000_000f }.sorted()
        val median = ms[ms.size / 2]
        val expected = 1000f / refresh.coerceAtLeast(1f)
        val late = ms.count { it > expected * 1.5f }
        val worst = ms.last()
        val hz = 1000f / median.coerceAtLeast(0.1f)
        text = "$label: ${ms.size} kare, ortalama ≈ ${hz.toInt()} Hz " +
            "(kare arası ${"%.1f".format(median)} ms). Takılan kare: $late (%${(late * 100 / ms.size)}), " +
            "en uzun ${"%.0f".format(worst)} ms. Ekranın bildirdiği hız: ${refresh.toInt()} Hz." + breakdown(expected)
    }
}
