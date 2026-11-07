package dev.glass.launcher.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Kamera deliğinin konumu (piksel). Delik yoksa ekranın üst ortası. */
data class IslandGeo(val cx: Int, val cy: Int, val cutW: Int, val cutH: Int, val screenW: Int, val statusH: Int)

/**
 * Dinamik Ada penceresi: "diğer uygulamaların üzerinde göster" izniyle bütün uygulamaların üstünde, kamera deliğinin
 * çevresinde durur. Pencere yalnızca adanın kapladığı kadardır (geri kalan ekrana dokunuş engellenmez); boyut yalnızca
 * geçişin başında ve sonunda değişir, animasyonun kendisi pencerenin içinde çizilir (120 Hz, takılma yok).
 * Bildirim dinleyicisi (rozetler için zaten açık) çalıştığı sürece yaşar; ayrı bir arka plan servisi gerekmez.
 */
object IslandOverlay {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var lp: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null

    /** Ada genişlemiş mi (dışarı dokununca kapanır). */
    val expanded = mutableStateOf(false)

    fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** 0 yok, 1 uygulama katmanı (izinli), 2 erişilebilirlik katmanı (her yerde, durum çubuğunun da üstünde). */
    private var host = 0

    /** Ada durum çubuğunun üstünde mi (erişilebilirlik katmanı): saat ve simgeler adanın altında kalır. */
    val aboveBars = mutableStateOf(false)

    /**
     * Ayarlara ve izne göre adayı açar/kapatır. Erişilebilirlik hizmeti açıksa ada onun katmanında durur: telefonun
     * Ayarlar'ı dahil HER uygulamanın ve durum çubuğunun üstünde görünür (uygulama katmanını Ayarlar gizler).
     */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        Island.init(app)
        val a11y = LockService.context()
        val wantHost = when {
            !Island.settings.value.enabled -> 0
            a11y != null -> 2
            canDraw(app) -> 1
            else -> 0
        }
        if (wantHost == host && (host == 0) == (view == null)) return
        if (view != null) detach(app)
        host = 0
        if (wantHost == 2 && a11y != null) attach(a11y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 2)
        else if (wantHost == 1) attach(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 1)
    }

    fun geometry(ctx: Context): IslandGeo {
        val w = ctx.getSystemService(WindowManager::class.java)
        val m = w.currentWindowMetrics
        val bounds = m.bounds
        val dm = ctx.resources.displayMetrics
        val sb = m.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top.takeIf { it > 0 } ?: (24 * dm.density).toInt()
        val cut = m.windowInsets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty && it.top < bounds.height() / 4 }
        if (cut != null) return IslandGeo(cut.centerX(), cut.centerY(), cut.width(), cut.height(), bounds.width(), sb)
        // Delik yok: durum çubuğunun ortası.
        return IslandGeo(bounds.width() / 2, sb / 2, 0, (sb * 0.6f).toInt(), bounds.width(), sb)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(ctx: Context, type: Int, kind: Int) {
        val w = ctx.getSystemService(WindowManager::class.java) ?: return
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { dev.glass.launcher.ui.IslandRoot() }
            // Adanın dışına dokununca genişlemiş ada kapanır.
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) expanded.value = false; false }
        }
        val p = WindowManager.LayoutParams(
            1, 1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Ortadan hizalı: genişlik değişince pencere iki yana eşit büyür, konumu (x) hiç değişmez. Soldan
            // hizalıyken x genişliğe göre yeniden hesaplanıyordu; Android konumu ve boyutu bazen bir kare arayla
            // uyguladığı için ada büyüyüp küçülürken bir an sağa sola kayıyordu.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "GlassIsland"
            windowAnimations = 0
        }
        val ok = runCatching { w.addView(v, p); true }.getOrDefault(false)
        if (!ok) { o.stop(); return }
        wm = w; view = v; lp = p; owner = o; host = kind
        aboveBars.value = kind == 2
        NowPlaying.start(ctx.applicationContext)
        Island.registerReceivers(ctx.applicationContext)
    }

    private fun detach(ctx: Context) {
        runCatching { wm?.removeViewImmediate(view) }
        owner?.stop()
        view = null; lp = null; owner = null; wm = null; host = 0
        expanded.value = false
        Island.unregisterReceivers(ctx)
    }

    /** Pencereyi adanın kapladığı alana getirir. Genişlik/yükseklik 0 ise ada gizli (dokunuşlar geçer). */
    fun place(x: Int, y: Int, width: Int, height: Int) {
        val p = lp ?: return; val v = view ?: return
        val hidden = width <= 0 || height <= 0
        val nw = if (hidden) 1 else width; val nh = if (hidden) 1 else height
        val flags = if (hidden) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (p.x == x && p.y == y && p.width == nw && p.height == nh && p.flags == flags) return
        p.x = x; p.y = y; p.width = nw; p.height = nh; p.flags = flags
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val reg = LifecycleRegistry(this)
        private val ssc = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        fun start() { ssc.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
        fun stop() { reg.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
    }
}
