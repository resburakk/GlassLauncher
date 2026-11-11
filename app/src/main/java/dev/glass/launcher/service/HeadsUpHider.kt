package dev.glass.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Honor'un (sistemin) üstten açılan bildirim penceresini gizler; aynı bildirim yalnızca Dinamik Ada'da görünür.
 * dynamicSpot gibi erişilebilirlik hizmetiyle çalışır:
 *  1. Ada bir mesajı / gelen aramayı gösterdiğinde çağrılır ([request]).
 *  2. Kısa bir süre boyunca ekrandaki sistem (SystemUI) pencereleri taranır; ekranın üst kısmında, ekran genişliğinde
 *     bir bildirim satırı bulunursa (bildirim paneli açık DEĞİLKEN) üzerinde parmakla yapılır gibi yukarı kaydırılır.
 *     Honor'da yukarı kaydırmak bildirimi silmez, yalnızca üstteki pencereyi kapatır (bildirim panelinde kalır).
 *  3. Panel açıksa ya da pencere bulunamazsa hiçbir şey yapılmaz (kullanıcının ekranına rastgele dokunulmaz).
 * Son denemenin sonucu ayarlarda görünür ([last]); telefonda nasıl çalıştığını görmek için.
 */
object HeadsUpHider {
    private const val TAG = "GlassDiag"
    private val main = Handler(Looper.getMainLooper())
    private val _last = MutableStateFlow("")
    val last: StateFlow<String> = _last

    private var token = 0
    private var lastSwipe = 0L

    /** [pkg] uygulamasının bildirimi adada gösterildi: sistemin penceresi açılırsa gizle. */
    fun request(pkg: String) {
        val s = Island.settings.value
        if (!s.enabled || !s.hideSystemPopup || pkg in s.popupKeep) return
        val svc = LockService.service() ?: run { note("Erişilebilirlik hizmeti kapalı"); return }
        val my = ++token
        val start = System.currentTimeMillis()
        // Pencere açılış animasyonu ~150–300 ms; 1,6 sn boyunca 90 ms arayla bakılır.
        fun attempt(n: Int) {
            if (my != token) return
            val r = runCatching { tryHide(svc, pkg) }.getOrElse { "hata: ${it.javaClass.simpleName}" }
            when {
                r == null && System.currentTimeMillis() - start < 1600 -> main.postDelayed({ attempt(n + 1) }, 90)
                r == null -> note("${Island.appLabel(svc, pkg)}: sistem penceresi görülmedi")
                else -> note("${Island.appLabel(svc, pkg)}: $r")
            }
        }
        main.postDelayed({ attempt(0) }, 120)
    }

    private fun note(t: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _last.value = "$time · $t"
        Log.i(TAG, "hun: $t")
    }

    /** null = henüz bir şey yok (tekrar denenir); metin = sonuç (biter). */
    private fun tryHide(svc: AccessibilityService, pkg: String): String? {
        // Kilit ekranında yukarı kaydırmak kilidi açmaya çalışır: orada hiç dokunulmaz.
        val km = svc.getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return "kilit ekranı açık, dokunulmadı"
        val dm = svc.resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels; val dp = dm.density
        val windows = svc.windows ?: return null
        var best: Rect? = null
        var bestArea = 0
        var shadeOpen = false
        val r = Rect()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val root = w.root ?: continue
            val wp = root.packageName?.toString().orEmpty()
            if (!wp.contains("systemui", ignoreCase = true)) continue
            // Pencerenin görünen içeriği: geniş satırların en alt noktası panelin açık olup olmadığını söyler.
            var lowest = 0
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
            var seen = 0
            while (stack.isNotEmpty() && seen < 500) {
                val n = stack.removeLast(); seen++
                if (!n.isVisibleToUser) continue
                n.getBoundsInScreen(r)
                val wide = r.width() > sw * 0.6f
                if (wide && r.height() < sh * 0.9f) lowest = maxOf(lowest, r.bottom)
                val id = n.viewIdResourceName.orEmpty()
                val rowLike = id.contains("expandableNotificationRow", true) || id.contains("notification_row", true) ||
                    id.contains("heads_up", true) || id.contains("headsup", true)
                val shapeOk = wide && r.top < sh * 0.32f && r.height() in (44 * dp).toInt()..(sh * 0.42f).toInt()
                if (shapeOk && (rowLike || n.packageName?.toString()?.contains("systemui", true) == true)) {
                    val area = r.width() * r.height()
                    // Satır kimliği olan düğüm tercih edilir; yoksa en büyük uygun alan.
                    val score = if (rowLike) area * 4 else area
                    if (score > bestArea) { bestArea = score; best = Rect(r) }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
            }
            if (lowest > sh * 0.55f) shadeOpen = true
        }
        if (shadeOpen) return "bildirim paneli açık, dokunulmadı"
        val b = best ?: return null
        if (System.currentTimeMillis() - lastSwipe < 500) return "zaten kaydırıldı"
        lastSwipe = System.currentTimeMillis()
        // Satırın ortasından ekranın üstüne hızlı kaydırma (parmakla yukarı atmak gibi).
        val x = b.exactCenterX().coerceIn(sw * 0.2f, sw * 0.8f)
        val y0 = b.exactCenterY()
        val path = Path().apply { moveTo(x, y0); lineTo(x, (b.top - 60 * dp).coerceAtLeast(1f)) }
        val ok = svc.dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 140)).build(), null, null,
        )
        return if (ok) "sistem penceresi yukarı kaydırıldı (${b.top}–${b.bottom} px)" else "kaydırma yapılamadı"
    }
}
