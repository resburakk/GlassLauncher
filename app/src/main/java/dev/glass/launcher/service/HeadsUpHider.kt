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

    private var lastSwipe = 0L

    // Bekleyen istek: sistem penceresi bu süre içinde açılırsa gizlenir.
    private var pendingPkg: String? = null
    private var pendingSvc: AccessibilityService? = null
    private var deadline = 0L
    private var lastTry = 0L
    private var started = 0L

    /**
     * [pkg] uygulamasının bildirimi adada gösterildi: sistemin penceresi açılırsa gizle.
     * Hız: sistem penceresi değiştiği AN haber verir (erişilebilirlik olayı, bekleme yok); ek olarak 30 ms'de bir
     * bakılır. Önceden ilk bakış 120 ms sonra, sonrakiler 90 ms arayla yapılıyordu.
     */
    fun request(pkg: String) {
        val s = Island.settings.value
        if (!s.enabled || !s.hideSystemPopup || pkg in s.popupKeep) return
        val svc = LockService.service() ?: run { note("Erişilebilirlik hizmeti kapalı"); return }
        pendingPkg = pkg; pendingSvc = svc
        started = System.currentTimeMillis(); deadline = started + 2600
        LockService.watchSystemUi(true)
        main.removeCallbacks(poll)
        attempt()
    }

    private val poll = Runnable { attempt() }

    /** Sistem arayüzünden bir pencere / içerik olayı geldi (yalnızca bekleyen istek varken dinlenir). */
    fun onSystemUiEvent() {
        if (pendingPkg == null) return
        if (System.currentTimeMillis() - lastTry < 12) return // aynı karede tekrar tarama yapılmaz
        attempt()
    }

    private fun attempt() {
        val pkg = pendingPkg ?: return; val svc = pendingSvc ?: return
        lastTry = System.currentTimeMillis()
        val r = runCatching { tryHide(svc, pkg) }.getOrElse { "hata: ${it.javaClass.simpleName}" }
        when {
            r == null && System.currentTimeMillis() < deadline -> { main.removeCallbacks(poll); main.postDelayed(poll, 30) }
            r == null -> finish("${Island.appLabel(svc, pkg)}: sistem penceresi görülmedi · ${describe(svc)}")
            else -> finish("${Island.appLabel(svc, pkg)}: $r · ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun finish(t: String) {
        pendingPkg = null; pendingSvc = null
        main.removeCallbacks(poll)
        LockService.watchSystemUi(false)
        note(t)
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
        // Satırın ortasından ekranın üstüne hızlı kaydırma (parmakla yukarı atmak gibi). Ada sistem penceresinin
        // üstünde durur: kaydırma sırasında ada dokunuşları alttaki pencereye geçirir; mümkünse adanın altında kalan
        // kısımdan başlanır.
        val isl = IslandOverlay.islandRect()
        val x = b.exactCenterX().coerceIn(sw * 0.2f, sw * 0.8f)
        val below = isl?.let { maxOf(b.top, it.bottom) + 10 * dp }
        val y0 = if (below != null && below < b.bottom - 8 * dp) (below + b.bottom) / 2f else b.exactCenterY()
        IslandOverlay.passThrough(300)
        // Kısa ve hızlı fırlatma (70 ms): sistem bunu "yukarı atma" sayar ve pencereyi hemen kapatır.
        val path = Path().apply { moveTo(x, y0); lineTo(x, (b.top - 60 * dp).coerceAtLeast(1f)) }
        val ok = svc.dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build(), null, null,
        )
        return if (ok) "sistem penceresi yukarı kaydırıldı (${b.top}–${b.bottom} px, ${y0.toInt()} px'ten)" else "kaydırma yapılamadı"
    }

    /** Bulunamadığında: ekrandaki pencerelerin kısa özeti (Ayarlar'da "Son deneme"de görünür). */
    private fun describe(svc: AccessibilityService): String = runCatching {
        val r = Rect()
        svc.windows.orEmpty().take(8).joinToString(", ") { w ->
            w.getBoundsInScreen(r)
            val pk = w.root?.packageName?.toString()?.substringAfterLast('.') ?: "?"
            "t${w.type}:$pk ${r.top}-${r.bottom}"
        }
    }.getOrDefault("")
}
