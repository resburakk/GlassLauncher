package dev.glass.launcher.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * "Boş alana çift dokununca ekranı kilitle", Kontrol Merkezi ve Dinamik Ada için Erişilebilirlik hizmeti.
 * Olay dinlemez. Yalnızca "Honor bildirim penceresini gizle" açıkken, ada bir bildirimi gösterdiği anda sistemin
 * (SystemUI) üstteki bildirim penceresini bulup yukarı kaydırmak için sistem pencerelerine bakar (bkz. [HeadsUpHider]);
 * başka uygulamaların ekranını okumaz, hiçbir şey kaydetmez.
 * Bu yöntemle kilitlenen telefon parmak izi/yüz tanıma ile açılabilir (Cihaz Yöneticisi yöntemi bunu bozar).
 * Kullanıcı Ayarlar > Erişilebilirlik'ten açıkça etkinleştirmelidir.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        // Dinamik Ada bu hizmetin katmanına taşınır: her uygulamanın ve durum çubuğunun üstünde.
        runCatching { IslandOverlay.sync(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg.contains("systemui", ignoreCase = true)) HeadsUpHider.onSystemUiEvent()
    }
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) { instance = null; runCatching { IslandOverlay.sync(applicationContext) } }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) { instance = null; runCatching { IslandOverlay.sync(applicationContext) } }
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: LockService? = null

        /** Hizmet çalışıyorsa onun bağlamı (erişilebilirlik katmanı penceresi için). */
        fun context(): Context? = instance

        /** Çalışan hizmet (sistem bildirim penceresini gizlemek için, bkz. [HeadsUpHider]). */
        fun service(): AccessibilityService? = instance

        /**
         * Sistem arayüzünün pencere/içerik olaylarını yalnızca bir bildirim penceresi beklenirken (≈2 sn) ve
         * gecikmesiz dinler; geri kalan zamanda olay alınmaz (pil, işlemci).
         */
        fun watchSystemUi(on: Boolean) {
            val s = instance ?: return
            runCatching {
                val info = s.serviceInfo ?: return
                info.eventTypes = if (on) AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                info.notificationTimeout = if (on) 0L else 1000L
                s.serviceInfo = info
            }
        }

        /** Kilitlediyse true; hizmet kapalıysa false. */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        /** Sistemin Kontrol Merkezi'ni (hızlı ayarlar) açar; hizmet kapalıysa false. */
        fun quickSettings(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) == true

        /** Sistemin kendi son kullanılanlar ekranını açar; hizmet kapalıysa false. */
        fun systemRecents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true

        /** Sistemin bildirim gölgesini açar; hizmet kapalıysa false. */
        fun notifications(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) == true

        /** Hizmet sistem ayarlarında etkin mi? */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, LockService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
