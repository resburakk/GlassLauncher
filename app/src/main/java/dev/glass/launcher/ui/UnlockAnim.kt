package dev.glass.launcher.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * iOS 26 kilit açılışı: ana ekran (simgeler, dock) tek parça hafif büyükten yerine oturur, duvar kâğıdı
 * aynı anda hafifçe uzaklaşır; simgeler sana doğru geliyormuş gibi görünür. Ekran kapanınca içerik gizlenir
 * (kilit ekranının arkasında); kilit açılınca canlandırılır. Hareket hazır çizilmiş katmanlarda yapılır,
 * sayfalar her karede yeniden çizilmez (120 Hz).
 */
object UnlockAnim {
    /** true: gizle (ekran kapandı); false: canlandırarak göster (kilit açıldı). */
    private val _events = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 4)
    val events: SharedFlow<Boolean> = _events
    @Volatile var hidden = false
        private set

    fun hide() { hidden = true; _events.tryEmit(true) }

    /** Gizliyse canlandırarak gösterir; zaten görünürse hiçbir şey yapmaz. */
    fun play() {
        if (!hidden) return
        hidden = false
        _events.tryEmit(false)
    }
}
