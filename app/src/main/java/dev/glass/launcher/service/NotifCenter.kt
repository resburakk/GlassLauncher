package dev.glass.launcher.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bildirimdeki bir eylem düğmesi; [remoteInput] varsa hızlı yanıt düğmesidir. */
class NotifAction(val title: String, val intent: PendingIntent, val remoteInput: RemoteInput?)

/**
 * Bildirim panelinde gösterilen tek bildirim. Yalnızca bellekte tutulur: diske yazılmaz, hiçbir yere gönderilmez.
 */
class PanelNotif(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    /** Uygulamanın kendi alt başlığı (ör. e-posta hesabı, sohbet adı). */
    val subText: String,
    val time: Long,
    val intent: PendingIntent?,
    /** Kullanıcı silebilir mi (süren işlemler, ör. müzik çalar, silinemez). */
    val clearable: Boolean,
    val ongoing: Boolean,
    val autoCancel: Boolean,
    val color: Int,
    val smallIcon: Icon?,
    val largeIcon: Icon?,
    val actions: List<NotifAction>,
) {
    /** Hızlı yanıt düğmesi (WhatsApp, Telegram, Mesajlar gibi uygulamalar sunar). */
    val reply: NotifAction? get() = actions.firstOrNull { it.remoteInput?.allowFreeFormInput == true }
}

/** Bir uygulamanın bildirimleri (iOS gibi uygulamaya göre gruplanmış; en yeni en üstte). */
class NotifGroup(val pkg: String, val items: List<PanelNotif>) {
    val latest: Long get() = items.firstOrNull()?.time ?: 0L
}

/**
 * Launcher'ın bildirim merkezi: bildirim dinleyicisinden ([BadgeListener]) gelen bildirimler ve onlar üzerindeki
 * işlemler (açma, silme, tümünü silme, hızlı yanıt).
 */
object NotifCenter {
    private val _items = MutableStateFlow<List<PanelNotif>>(emptyList())
    /** Tüm bildirimler, en yeni en üstte. */
    val items: StateFlow<List<PanelNotif>> = _items

    private val _connected = MutableStateFlow(false)
    /** Dinleyici şu an sisteme bağlı mı (MagicOS arka planda kapatabilir). */
    val connected: StateFlow<Boolean> = _connected

    @Volatile internal var service: BadgeListener? = null

    internal fun onConnected(s: BadgeListener) { service = s; _connected.value = true }
    internal fun onDisconnected() { service = null; _connected.value = false; _items.value = emptyList() }
    internal fun set(list: List<PanelNotif>) { _items.value = list }

    /** Uygulamaya göre gruplar; en son bildirim alan uygulama en üstte. */
    fun groups(list: List<PanelNotif> = _items.value): List<NotifGroup> =
        list.groupBy { it.pkg }.map { (p, l) -> NotifGroup(p, l.sortedByDescending { it.time }) }.sortedByDescending { it.latest }

    /**
     * Dinleyici bağlı değilse sistemden yeniden bağlanmasını ister (izin verilmiş olmalı). MagicOS dinleyiciyi
     * arka planda kapattığında launcher öne gelince ve panel açılınca çağrılır.
     */
    fun ensureBound(ctx: Context) {
        if (_connected.value || !BadgeListener.isGranted(ctx)) return
        runCatching { NotificationListenerService.requestRebind(BadgeListener.component(ctx)) }
    }

    /** Bildirime dokunuldu: uygulamanın kendi hedefini açar; uygulama öyle istediyse bildirim de kalkar. */
    fun open(n: PanelNotif): Boolean {
        val pi = n.intent ?: return false
        val ok = send(pi, null, null)
        if (ok && n.autoCancel) dismiss(n)
        return ok
    }

    /** Bildirimi siler (silinemez bildirimlerde bir şey yapmaz). */
    fun dismiss(n: PanelNotif) {
        if (!n.clearable) return
        runCatching { service?.cancelNotification(n.key) }
    }

    /** Uygulamanın tüm silinebilir bildirimlerini siler. */
    fun dismissGroup(g: NotifGroup) = g.items.forEach { dismiss(it) }

    /** Tüm silinebilir bildirimleri siler (sistemdeki "Tümünü temizle" ile aynı). */
    fun dismissAll() { runCatching { service?.cancelAllNotifications() } }

    /** Bildirimdeki bir eylem düğmesine basar (ör. "Okundu olarak işaretle"). */
    fun action(ctx: Context, a: NotifAction): Boolean = send(a.intent, ctx, null)

    /** Hızlı yanıt gönderir. Uygulama yanıt düğmesi sunmuyorsa false döner. */
    fun reply(ctx: Context, n: PanelNotif, text: String): Boolean {
        val a = n.reply ?: return false
        val ri = a.remoteInput ?: return false
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(ri), fill, Bundle().apply { putCharSequence(ri.resultKey, text) })
        return send(a.intent, ctx, fill)
    }

    private fun send(pi: PendingIntent, ctx: Context?, fill: Intent?): Boolean = runCatching {
        val opts = android.app.ActivityOptions.makeBasic().apply {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
        }
        pi.send(ctx, 0, fill, null, null, null, opts.toBundle())
        true
    }.getOrDefault(false)

    /** Sistem bildirimlerinden panel listesini kurar (BadgeListener çağırır). */
    internal fun build(active: Array<StatusBarNotification>): List<PanelNotif> {
        // Grup özeti: aynı gruptan alt bildirim varsa gösterilmez (içerik alt bildirimlerdedir); tek başınaysa gösterilir.
        val childGroups = active.filter { (it.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY == 0 }.map { it.groupKey }.toHashSet()
        val out = ArrayList<PanelNotif>(active.size)
        for (sbn in active) {
            val n = sbn.notification ?: continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 && sbn.groupKey in childGroups) continue
            val ex = n.extras
            val title = (ex?.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ex?.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
            val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            // İçeriksiz bildirimler (yalnızca hizmet simgesi) gösterilmez.
            if (title.isBlank() && text.isBlank()) continue
            val acts = n.actions.orEmpty().mapNotNull { a ->
                val pi = a.actionIntent ?: return@mapNotNull null
                NotifAction(a.title?.toString().orEmpty(), pi, a.remoteInputs?.firstOrNull { it.allowFreeFormInput })
            }
            out += PanelNotif(
                key = sbn.key,
                pkg = sbn.packageName,
                title = title,
                text = text,
                subText = ex?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
                time = if (n.`when` > 0L) n.`when` else sbn.postTime,
                intent = n.contentIntent,
                clearable = sbn.isClearable,
                ongoing = sbn.isOngoing,
                autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
                color = n.color,
                smallIcon = n.smallIcon,
                largeIcon = n.getLargeIcon(),
                actions = acts,
            )
        }
        out.sortByDescending { it.time }
        return out
    }
}
