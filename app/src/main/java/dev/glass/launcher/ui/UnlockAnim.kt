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

    fun hide() { hidden = true; _events.tryEmit(true) }

    /** Gizliyse canlandırarak gösterir; zaten görünürse hiçbir şey yapmaz. */
    fun play() {
        if (!hidden) return
        hidden = false
        _events.tryEmit(false)
    }
}
