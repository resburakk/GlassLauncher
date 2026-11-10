package dev.glass.launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup

/**
 * 120 Hz: bir pencerenin ekranın en yüksek yenileme hızında çizilmesini ister (tüm ekranlar aynı yolu kullanır).
 *  - Ekranın mevcut çözünürlükteki en hızlı modu (ör. 120 Hz) tercih edilir.
 *  - Android 15+/16 "uyarlanabilir yenileme hızı" pencereyi pil için ≈60 Hz'e indirmesin diye kapatılır.
 *  - Pencerenin kök görünümleri "yüksek hız" ister: yalnızca bir şey çizilirken (animasyon, kaydırma) etkilidir,
 *    boştayken ekran yine düşük hıza iner, pil harcamaz. Önceden yalnızca ana ekran sayfa kaydırması yüksek hız
 *    istiyordu; klasör, arşiv, menü, uygulama açılış efekti gibi animasyonlarda karar sisteme kalıyordu.
 * Çözünürlük değişebileceği için (MagicOS akıllı çözünürlük) pencere her öne geldiğinde yeniden çağrılmalıdır.
 */
object HighRefresh {
    fun apply(a: Activity) {
        val w = a.window ?: return
        runCatching {
            val d = a.display ?: return@runCatching
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return@runCatching
            if (w.attributes.preferredDisplayModeId != best.modeId || w.attributes.preferredRefreshRate != best.refreshRate) {
                w.attributes = w.attributes.apply {
                    preferredDisplayModeId = best.modeId
                    preferredRefreshRate = best.refreshRate
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 35) runCatching {
            w.isFrameRatePowerSavingsBalanced = false
            val decor = w.decorView
            vote(decor)
            // İçerik henüz eklenmemiş olabilir: çizimden hemen önce kök içerik görünümüne de uygulanır.
            decor.post { roots(decor).forEach(::vote) }
        }
    }

    /** Bir görünümün yüksek hız istemesi (Android 15+). */
    fun vote(v: View) {
        if (Build.VERSION.SDK_INT >= 35) runCatching { v.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH }
    }

    /** Pencere kökü → android.R.id.content → onun çocukları (Compose kök görünümü dahil). */
    private fun roots(decor: View): List<View> {
        val out = ArrayList<View>()
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return out
        out += content
        for (i in 0 until content.childCount) {
            val c = content.getChildAt(i); out += c
            if (c is ViewGroup) for (j in 0 until c.childCount) out += c.getChildAt(j)
        }
        return out
    }
}
