package dev.glass.launcher.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * iOS kilit açılışı: ekran kilidi açılınca ana ekran simgeleri biraz büyükten yerine oturarak belirir.
 * Ekran kapanınca simgeler gizlenir (kilit ekranının arkasında); kilit açılınca canlandırılır.
 */
object UnlockAnim {
    /** true: gizle (ekran kapandı); false: canlandırarak göster (kilit açıldı). */
    private val _events = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 4)
    val events: SharedFlow<Boolean> = _events
    @Volatile var hidden = false
        private set

    /** Açılış ilerlemesi (0 gizli … 1 yerinde). Hücreler çizimde okur: her simge kendi konumuna göre gelir. */
    val progress = androidx.compose.runtime.mutableFloatStateOf(1f)

    /**
     * Bir simgenin açılıştaki dönüşümü (iOS gibi): simgeler ekranın dışına doğru, kendi uzaklıkları oranında
     * büyük ve dağınık başlar, merkeze doğru toplanarak yerine oturur; dıştakiler biraz gecikmeli gelir.
     * Dock simgeleri alttan yükselir. Dönen: (dx, dy, ölçek, saydamlık).
     */
    fun cellTransform(cx: Float, cy: Float, w: Float, h: Float, dock: Boolean): FloatArray? {
        val u = progress.floatValue
        if (u >= 0.999f) return null
        if (w <= 0f || h <= 0f) return null
        if (dock) {
            val p = u.coerceIn(0f, 1f)
            val e = 1f - p
            return floatArrayOf(0f, h * 0.18f * e, 1f + 0.25f * e, (p * 1.6f).coerceIn(0f, 1f))
        }
        val vx = cx - w / 2f
        val vy = cy - h * 0.45f
        val d = (kotlin.math.hypot(vx, vy) / kotlin.math.hypot(w / 2f, h / 2f)).coerceIn(0f, 1f)
        val delay = 0.18f * d
        val p = ((u - delay) / (1f - delay)).coerceIn(0f, 1f)
        val e = 1f - p
        return floatArrayOf(vx * 0.9f * e, vy * 0.9f * e, 1f + 0.9f * e, (p * 1.5f).coerceIn(0f, 1f))
    }

    fun hide() { hidden = true; _events.tryEmit(true) }

    /** Gizliyse canlandırarak gösterir; zaten görünürse hiçbir şey yapmaz. */
    fun play() {
        if (!hidden) return
        hidden = false
        _events.tryEmit(false)
    }
}
