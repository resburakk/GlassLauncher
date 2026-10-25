package dev.glass.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sayfa kaydırmalarındaki gerçek kare ölçümü (telefonda akıcılığı sayıyla görmek için).
 * Ana ekranda/arşive her kaydırmada kare aralıkları ölçülür; Ayarlar > Cam > Performans'ta gösterilir.
 */
/**
 * Yalnızca test için (emülatör ölçümü): geçişteki parçaları tek tek kapatıp hangisinin yük getirdiğini bulmak.
 * Normal kullanımda 0; yalnızca adb ile "glassDebug" ekiyle açılınca değişir.
 */
object GlassDebug {
    var mask by androidx.compose.runtime.mutableIntStateOf(0)
    /** Test ölçümü sürüyor (maske 0 iken de az kareli geçişler ölçülsün). */
    @Volatile var test = false
    fun off(bit: Int): Boolean = mask and bit != 0
}

object FrameStats {
    var text by mutableStateOf("Henüz ölçüm yok: ana ekranda birkaç kez sayfa kaydırın.")
        private set
    /** Launcher'ın sistemin ana ekran görevinde çalışıp çalışmadığı (MainActivity.checkHomeTask). */
    var homeTask by mutableStateOf("Ana ekran görevi: henüz kontrol edilmedi.")

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
        val sync = d(android.view.FrameMetrics.SYNC_DURATION)
        val cmd = d(android.view.FrameMetrics.COMMAND_ISSUE_DURATION)
        val swap = d(android.view.FrameMetrics.SWAP_BUFFERS_DURATION)
        val rt = sync + cmd + swap
        val gpu = if (android.os.Build.VERSION.SDK_INT >= 31) d(android.view.FrameMetrics.GPU_DURATION) else 0f
        synchronized(lock) { if (samples.size < 800) samples += floatArrayOf(ui, rt, gpu, sync, cmd, swap) }
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
        fun avg(i: Int) = "%.1f".format(list.map { it[i] }.average())
        return "\n\nKare süreleri (bütçe ${"%.1f".format(budget)} ms):\n• " + part(0, "Arayüz:") + "\n• " + part(1, "Çizim:") +
            "\n   (hazırlık ${avg(3)} / komut ${avg(4)} / gönderim ${avg(5)} ms)" +
            "\n• " + part(2, "Ekran kartı:")
    }

    /** [intervalsNs]: art arda karelerin arası (ns), [refresh]: ekranın bildirdiği yenileme hızı (Hz). */
    fun report(intervalsNs: LongArray, refresh: Float, label: String = "Son kaydırma (ana ekran)") {
        // Testte (emülatör yazılımla çizer, çok yavaş) az kareli geçişler de ölçülür.
        if (intervalsNs.size < (if (GlassDebug.mask != 0 || GlassDebug.test) 3 else 8)) { recording = false; return }
        val ms = intervalsNs.map { it / 1_000_000f }.sorted()
        val median = ms[ms.size / 2]
        val expected = 1000f / refresh.coerceAtLeast(1f)
        val late = ms.count { it > expected * 1.5f }
        val worst = ms.last()
        val hz = 1000f / median.coerceAtLeast(0.1f)
        val one = "$label: ${ms.size} kare, ortalama ≈ ${hz.toInt()} Hz " +
            "(kare arası ${"%.1f".format(median)} ms). Takılan kare: $late (%${(late * 100 / ms.size)}), " +
            "en uzun ${"%.0f".format(worst)} ms. Ekranın bildirdiği hız: ${refresh.toInt()} Hz." + breakdown(expected)
        // Her türün (ana ekran, arşiv, widget sayfası) son ölçümü ayrı tutulur; en yenisi en üstte.
        android.util.Log.d("GlassPerf", "mask=${GlassDebug.mask} " + one.replace('\n', ' '))
        synchronized(results) { results.remove(label); results[label] = one; text = results.values.reversed().joinToString("\n\n") }
    }
    private val results = LinkedHashMap<String, String>()
}


/**
 * [active] sürerken gerçek kare aralıklarını toplar; bitince "Ayarlar > Performans"ta [label] başlığıyla gösterir
 * (ana ekran sayfa kaydırmasıyla aynı ölçüm; arşiv kaydırması, widget sayfası geçişi için).
 */
@androidx.compose.runtime.Composable
fun MeasureFrames(active: Boolean, label: String) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val refresh = view.display?.refreshRate?.takeIf { it > 1f } ?: 60f
        FrameStats.begin()
        val list = ArrayList<Long>()
        var last = 0L
        try {
            while (list.size < 600) {
                androidx.compose.runtime.withFrameNanos { t ->
                    if (last != 0L) list += t - last
                    last = t
                }
            }
        } finally {
            FrameStats.report(list.toLongArray(), refresh, label)
        }
    }
}
