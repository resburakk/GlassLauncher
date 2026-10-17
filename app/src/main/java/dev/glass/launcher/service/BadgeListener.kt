package dev.glass.launcher.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Uygulama simgelerindeki bildirim sayıları (iOS rozetleri). Bildirimlerin İÇERİĞİ okunmaz/saklanmaz;
 * yalnızca hangi uygulamanın kaç bildirimi olduğu sayılır ve hiçbir şey dışarı gönderilmez.
 * Kullanıcı "Bildirim erişimi" izni verdiğinde çalışır.
 */
class BadgeListener : NotificationListenerService() {

    override fun onListenerConnected() = recount()
    override fun onListenerDisconnected() { Badges.set(emptyMap()) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = recount()

    private fun recount() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val r = Ranking()
        val out = HashMap<String, Int>()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            // Süren işlemler (müzik, indirme) ve grup özetleri sayılmaz (iOS'taki gibi yalnızca okunmamışlar).
            if (sbn.isOngoing) continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            // Uygulama/kanal rozeti kapatmışsa sayılmaz.
            if (ranking != null && ranking.getRanking(sbn.key, r) && !r.canShowBadge()) continue
            out[sbn.packageName] = (out[sbn.packageName] ?: 0) + maxOf(n.number, 1)
        }
        Badges.set(out)
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, BadgeListener::class.java)

        /** Kullanıcı bildirim erişimi verdi mi? */
        fun isGranted(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = component(ctx)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Paket adı → bildirim sayısı. */
object Badges {
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts
    fun set(m: Map<String, Int>) { if (_counts.value != m) _counts.value = m }
}
