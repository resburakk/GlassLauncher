package dev.glass.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * iOS lastik bandı (esnek çekme): içerik kenara gelince çekmeye devam edildikçe direnç artarak kayar,
 * bırakınca yaylı geri döner. Formül iOS'unki: b = (1 − 1 / (x·0,55 / d + 1)) · d.
 *
 * Önemli: kaydırmayı ALGILAYAN düğüm kaydırılmamalı (yoksa parmak altındaki hareket küçülür ve titrer);
 * kaydırma yalnızca içeriğe/öğelere [rubberY] ile uygulanır.
 */
class RubberBand internal constructor(private val scope: CoroutineScope) {
    private val anim = Animatable(0f)
    /** Ekrandaki esneme (px). Çizim sırasında okunmalı (graphicsLayer içinde). */
    val value: Float get() = anim.value
    private var raw = 0f
    /** Esnemenin ölçüldüğü boyut (genelde ekran yüksekliği), ilk kullanımda ayarlanır. */
    var extent = 2000f

    private fun band(r: Float): Float {
        val a = abs(r)
        return sign(r) * (1f - 1f / (a * 0.55f / extent + 1f)) * extent
    }

    private fun unband(v: Float): Float {
        val a = abs(v).coerceAtMost(extent * 0.98f)
        return sign(v) * (1f / (1f - a / extent) - 1f) * extent / 0.55f
    }

    /** Parmak hareketi: [dy] kadar daha çekildi. */
    fun drag(dy: Float) {
        if (anim.isRunning) { raw = unband(anim.value) }
        raw += dy
        val v = band(raw)
        scope.launch { anim.snapTo(v) }
    }

    /** Esneme varken ters yöne çekme önce esnemeyi geri alır; geri alınan miktarı döner. */
    fun undo(dy: Float): Float {
        if (raw == 0f || sign(dy) == sign(raw)) return 0f
        val next = raw + dy
        val used = if (sign(next) != sign(raw)) -raw else dy
        raw += used
        val v = band(raw)
        scope.launch { anim.snapTo(v) }
        return used
    }

    val stretched: Boolean get() = raw != 0f || anim.value != 0f

    /** Parmak kalktı: yaylı geri dönüş. */
    fun release() {
        raw = 0f
        scope.launch { anim.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 340f)) }
    }

    /** Kaydırılabilir bir alanın (liste, ızgara, kaydırmalı sütun) kenarında esnemek için. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || raw == 0f) return Offset.Zero
            return Offset(0f, undo(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
            drag(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!stretched) return Velocity.Zero
            release()
            return available
        }
    }
}

@Composable
fun rememberRubberBand(): RubberBand {
    val scope = rememberCoroutineScope()
    return remember { RubberBand(scope) }
}

/** İçeriği esnemeye göre dikey kaydırır (yalnızca çizimde okunur; yeniden yerleşim yok). */
fun Modifier.rubberY(rb: RubberBand): Modifier = graphicsLayer { translationY = rb.value }

/** Kaydırılamayan boş alanlarda çekmeyi esnemeye çevirir (yukarı/aşağı). */
fun Modifier.rubberDrag(rb: RubberBand, enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(rb) {
        rb.extent = size.height.toFloat().coerceAtLeast(1f)
        androidx.compose.foundation.gestures.detectVerticalDragGestures(
            onDragEnd = { rb.release() },
            onDragCancel = { rb.release() },
        ) { change, dy ->
            change.consume()
            rb.drag(dy)
        }
    }
