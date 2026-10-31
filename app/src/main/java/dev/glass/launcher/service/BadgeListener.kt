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
 * Uygulama simgelerindeki bildirim sayıları (iOS rozetleri) ve basılı tutma menüsündeki son bildirim
 * önizlemesi. Önizleme (başlık + kısa metin) yalnızca bellekte tutulur, diske yazılmaz ve hiçbir yere
 * gönderilmez. Kullanıcı "Bildirim erişimi" izni verdiğinde çalışır.
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
        val prev = HashMap<String, MutableList<NotifPreview>>()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            // Süren işlemler (müzik, indirme) ve grup özetleri sayılmaz (iOS'taki gibi yalnızca okunmamışlar).
            if (sbn.isOngoing) continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            // Uygulama/kanal rozeti kapatmışsa sayılmaz.
            if (ranking != null && ranking.getRanking(sbn.key, r) && !r.canShowBadge()) continue
            out[sbn.packageName] = (out[sbn.packageName] ?: 0) + maxOf(n.number, 1)
            val ex = n.extras
            val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isNotBlank() || text.isNotBlank()) {
                prev.getOrPut(sbn.packageName) { ArrayList() } += NotifPreview(sbn.key, title, text, sbn.postTime, n.contentIntent)
            }
        }
        Badges.set(out)
        Badges.setPreviews(prev.mapValues { (_, l) -> l.sortedByDescending { it.time }.take(3) })
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

/** Basılı tutma menüsünde gösterilen bildirim önizlemesi (yalnızca bellekte). */
class NotifPreview(val key: String, val title: String, val text: String, val time: Long, val intent: android.app.PendingIntent?)

/** Paket adı → bildirim sayısı. */
object Badges {
    private val _previews = MutableStateFlow<Map<String, List<NotifPreview>>>(emptyMap())
    /** Paket adı → en yeni bildirimler (en çok 3). */
    val previews: StateFlow<Map<String, List<NotifPreview>>> = _previews
    fun setPreviews(m: Map<String, List<NotifPreview>>) { _previews.value = m }

    /** Bildirime dokunuldu: uygulamanın kendi hedefini açar (arka plandan başlatmaya izin vererek). */
    fun open(p: NotifPreview): Boolean {
        val pi = p.intent ?: return false
        return runCatching {
            val opts = android.app.ActivityOptions.makeBasic().apply {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
            }
            pi.send(null, 0, null, null, null, null, opts.toBundle())
            true
        }.getOrDefault(false)
    }

    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts
    fun set(m: Map<String, Int>) { if (_counts.value != m) _counts.value = m }
}
