package dev.glass.launcher.service

import android.annotation.SuppressLint
import android.content.Context

/**
 * Telefonun KENDİ Kontrol Merkezi'ni / bildirim gölgesini açar.
 * 1) Erişilebilirlik hizmeti açıksa resmi yol: performGlobalAction (Android'in desteklediği yöntem).
 * 2) Değilse StatusBarManager'ın genişletme yöntemi denenir (EXPAND_STATUS_BAR izni). Bu yöntem
 *    Android'in genel API'sinde değildir; birçok launcher kullanır ama bazı sürümlerde/MagicOS'ta
 *    engellenebilir — o durumda false döner ve kullanıcıya Erişilebilirlik izni önerilir.
 */
object SystemPanels {
    fun openControlCenter(ctx: Context): Boolean =
        LockService.quickSettings() || expand(ctx, "expandSettingsPanel")

    fun openNotifications(ctx: Context): Boolean =
        LockService.notifications() || expand(ctx, "expandNotificationsPanel")

    @SuppressLint("WrongConstant")
    private fun expand(ctx: Context, method: String): Boolean = runCatching {
        val sbm = ctx.getSystemService("statusbar") ?: return false
        sbm.javaClass.getMethod(method).invoke(sbm)
        true
    }.getOrDefault(false)
}
