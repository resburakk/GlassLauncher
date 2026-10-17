package dev.glass.launcher.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Yalnızca "boş alana çift dokununca ekranı kilitle" için kullanılan Erişilebilirlik hizmeti.
 * Ekran içeriğini OKUMAZ, olay dinlemez; tek yaptığı sistemin kilitleme eylemini çağırmaktır.
 * Bu yöntemle kilitlenen telefon parmak izi/yüz tanıma ile açılabilir (Cihaz Yöneticisi yöntemi bunu bozar).
 * Kullanıcı Ayarlar > Erişilebilirlik'ten açıkça etkinleştirmelidir.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: LockService? = null

        /** Kilitlediyse true; hizmet kapalıysa false. */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        /** Sistemin Kontrol Merkezi'ni (hızlı ayarlar) açar; hizmet kapalıysa false. */
        fun quickSettings(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) == true

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
