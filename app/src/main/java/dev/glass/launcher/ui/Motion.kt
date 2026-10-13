package dev.glass.launcher.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Animasyon zamanlaması. Ayarlardaki "Animasyon hızı" (0.5×–2×) tüm geçişlere buradan uygulanır:
 * süreler hıza bölünür, yaylar (spring) hızın karesiyle sertleşir. iOS'taki gibi çoğu geçiş yay tabanlıdır.
 */
@Immutable
data class Motion(val speed: Float = 1f) {
    fun <T> tween(ms: Int): FiniteAnimationSpec<T> = tween((ms / speed).toInt().coerceAtLeast(1))

    fun <T> spring(damping: Float = 0.82f, stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        spring(dampingRatio = damping, stiffness = stiffness * speed * speed)

    /** Klasör/menü açılışı gibi "esnek" geçişler. */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(damping = 0.74f, stiffness = 420f)

    /** Simgelerin yer değiştirmesi. */
    fun <T> reflow(): FiniteAnimationSpec<T> = spring(damping = 0.86f, stiffness = 520f)
}

val LocalMotion = staticCompositionLocalOf { Motion() }
