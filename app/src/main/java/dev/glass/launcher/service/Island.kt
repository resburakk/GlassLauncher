package dev.glass.launcher.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Dinamik Ada ayarları (telefonda saklanır; ada servisi ve Ayarlar ekranı aynı değerleri okur). */
data class IslandSettings(
    val enabled: Boolean = false,
    /** Kamera deliğine göre dikey ve yatay kaydırma (dp). */
    val offsetY: Float = 8f,
    val offsetX: Float = 0f,
    /** Ölçüler (dp). 0 = otomatik (kamera deliğinin boyutuna göre). */
    val idleW: Float = 99f,
    val compactW: Float = 127f,
    val islandH: Float = 33f,
    val expandedW: Float = 0f,
    /** Köşe yuvarlaklığı: 1 = tam yuvarlak (hap), 0 = köşeli. */
    val corner: Float = 1f,
    /** Etkinlik yokken kamera deliğinin çevresinde küçük siyah hap. */
    val idleVisible: Boolean = true,
    /** 0 siyah (iOS), 1 koyu cam. */
    val style: Int = 0,
    val animSpeed: Float = 1f,
    val haptics: Boolean = true,
    /** Dokununca genişler (basılı tutunca uygulama açılır); kapalıysa tersi. */
    val tapExpands: Boolean = true,
    /** Mesaj, şarj, sessiz mod gibi kısa bildirimlerin süresi (sn). */
    val transientSeconds: Float = 4f,
    val hideLandscape: Boolean = true,
    val music: Boolean = true,
    val calls: Boolean = true,
    val timers: Boolean = true,
    val charging: Boolean = true,
    val ringer: Boolean = true,
    val notifications: Boolean = true,
    /** Mesaj önizlemesinde metin gösterilsin (kapalıysa yalnızca gönderen). */
    val notifText: Boolean = true,
    val navigation: Boolean = true,
    val progress: Boolean = true,
    /** Ada bir bildirimi gösterirken sistemin (Honor) üstten açılan penceresi gizlensin (erişilebilirlik gerekir). */
    val hideSystemPopup: Boolean = false,
    /** Bu uygulamaların sistem penceresi gizlenmez (paket adları). */
    val popupKeep: Set<String> = emptySet(),
    /** Ada açıkken sistemin üstten açılan pencerelerini sistem ayarıyla tamamen kapat (WRITE_SECURE_SETTINGS gerekir). */
    val systemOff: Boolean = false,
) {
    companion object {
        private const val P = "island"
        fun load(ctx: Context): IslandSettings {
            val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            val d = IslandSettings()
            return IslandSettings(
                enabled = p.getBoolean("enabled", d.enabled),
                offsetY = p.getFloat("offsetY", d.offsetY),
                offsetX = p.getFloat("offsetX", d.offsetX),
                idleW = p.getFloat("idleW", d.idleW),
                compactW = p.getFloat("compactW", d.compactW),
                islandH = p.getFloat("islandH", d.islandH),
                expandedW = p.getFloat("expandedW", d.expandedW),
                corner = p.getFloat("corner", d.corner),
                idleVisible = p.getBoolean("idleVisible", d.idleVisible),
                style = p.getInt("style", d.style),
                animSpeed = p.getFloat("animSpeed", d.animSpeed),
                haptics = p.getBoolean("haptics", d.haptics),
                tapExpands = p.getBoolean("tapExpands", d.tapExpands),
                transientSeconds = p.getFloat("transientSeconds", d.transientSeconds),
                hideLandscape = p.getBoolean("hideLandscape", d.hideLandscape),
                music = p.getBoolean("music", d.music),
                calls = p.getBoolean("calls", d.calls),
                timers = p.getBoolean("timers", d.timers),
                charging = p.getBoolean("charging", d.charging),
                ringer = p.getBoolean("ringer", d.ringer),
                notifications = p.getBoolean("notifications", d.notifications),
                notifText = p.getBoolean("notifText", d.notifText),
                navigation = p.getBoolean("navigation", d.navigation),
                progress = p.getBoolean("progress", d.progress),
                hideSystemPopup = p.getBoolean("hideSystemPopup", d.hideSystemPopup),
                popupKeep = p.getStringSet("popupKeep", d.popupKeep)?.toSet() ?: d.popupKeep,
                systemOff = p.getBoolean("systemOff", d.systemOff),
            )
        }

        fun save(ctx: Context, s: IslandSettings) {
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", s.enabled).putFloat("offsetY", s.offsetY).putFloat("offsetX", s.offsetX)
                .putFloat("idleW", s.idleW).putFloat("compactW", s.compactW).putFloat("islandH", s.islandH)
                .putFloat("expandedW", s.expandedW).putFloat("corner", s.corner)
                .putBoolean("idleVisible", s.idleVisible).putInt("style", s.style)
                .putFloat("animSpeed", s.animSpeed).putBoolean("haptics", s.haptics).putBoolean("tapExpands", s.tapExpands)
                .putFloat("transientSeconds", s.transientSeconds).putBoolean("hideLandscape", s.hideLandscape)
                .putBoolean("music", s.music).putBoolean("calls", s.calls).putBoolean("timers", s.timers)
                .putBoolean("charging", s.charging).putBoolean("ringer", s.ringer).putBoolean("notifications", s.notifications)
                .putBoolean("notifText", s.notifText).putBoolean("navigation", s.navigation).putBoolean("progress", s.progress)
                .putBoolean("hideSystemPopup", s.hideSystemPopup).putStringSet("popupKeep", s.popupKeep)
                .putBoolean("systemOff", s.systemOff)
                .apply()
        }
    }
}

/** Adada gösterilen etkinlikler. [key] aynı etkinliğin güncellemelerini tanır (animasyon yeniden başlamasın). */
sealed class IslandAct(val key: String, val pkg: String, val open: PendingIntent?) {
    /** Sürekli etkinlikler (arama, zamanlayıcı, navigasyon, müzik, ilerleme) önceliğe göre sıralanır. */
    abstract val priority: Int

    class Call(
        key: String, pkg: String, open: PendingIntent?,
        val name: String, val since: Long, val incoming: Boolean, val avatar: Bitmap?,
        val answer: PendingIntent?, val hangup: PendingIntent?,
    ) : IslandAct(key, pkg, open) { override val priority = 100 }

    /** [base] kronometre tabanı (geri sayımda bitiş anı); yoksa [text] (bildirimdeki süre yazısı). */
    class Timer(
        key: String, pkg: String, open: PendingIntent?,
        val title: String, val base: Long, val countDown: Boolean, val text: String,
    ) : IslandAct(key, pkg, open) { override val priority = 80 }

    class Nav(
        key: String, pkg: String, open: PendingIntent?,
        val title: String, val text: String, val arrow: Bitmap?,
    ) : IslandAct(key, pkg, open) { override val priority = 70 }

    class Music(val np: NowPlayingState) : IslandAct("music", np.pkg, null) { override val priority = 60 }

    class Progress(
        key: String, pkg: String, open: PendingIntent?,
        val title: String, val value: Float, val indeterminate: Boolean,
    ) : IslandAct(key, pkg, open) { override val priority = 40 }

    // Kısa süreli olanlar: sürekli etkinliğin önüne birkaç saniye çıkar.
    class Charging(val level: Int, val plugged: Boolean) : IslandAct("charge", "", null) { override val priority = 0 }
    class Ringer(val mode: Int) : IslandAct("ringer", "", null) { override val priority = 0 }
    class Message(
        key: String, pkg: String, open: PendingIntent?,
        val title: String, val text: String, val avatar: Bitmap?,
    ) : IslandAct(key, pkg, open) { override val priority = 0 }
}

/**
 * Dinamik Ada'nın beyni: bildirimlerden (arama, zamanlayıcı, navigasyon, indirme, mesaj), çalan medyadan ve sistem
 * yayınlarından (şarj, sessiz mod) etkinlikleri toplar. Bildirim içerikleri yalnızca bellekte tutulur, hiçbir yere
 * gönderilmez.
 */
object Island {
    private val _settings = MutableStateFlow(IslandSettings())
    val settings: StateFlow<IslandSettings> = _settings

    private val _ongoing = MutableStateFlow<List<IslandAct>>(emptyList())
    /** Sürekli etkinlikler (bildirim kaynaklı), önceliğe göre. Müzik ayrıca [NowPlaying]'den eklenir. */
    val ongoing: StateFlow<List<IslandAct>> = _ongoing

    /** Ayarlar > Dinamik Ada açıkken önizleme: 0 yok, 1 boşta, 2 kompakt, 3 genişlemiş. */
    val preview = MutableStateFlow(0)

    private val _transient = MutableStateFlow<IslandAct?>(null)
    val transient: StateFlow<IslandAct?> = _transient

    private val main = Handler(Looper.getMainLooper())
    private val clearTransient = Runnable { _transient.value = null }
    private var app: Context? = null
    private val seen = HashMap<String, Int>()
    /** Şu an çalan aramalar (yeni gelen aramayı bir kez tanımak için). */
    private val ringing = HashSet<String>()
    private var receiversOn = false

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        _settings.value = IslandSettings.load(ctx)
        SystemHeadsUp.sync(app!!, _settings.value)
    }

    fun update(ctx: Context, s: IslandSettings) {
        IslandSettings.save(ctx, s)
        _settings.value = s
        SystemHeadsUp.sync(ctx.applicationContext, s)
        IslandOverlay.sync(ctx.applicationContext)
    }

    fun showTransient(a: IslandAct) {
        main.removeCallbacks(clearTransient)
        _transient.value = a
        main.postDelayed(clearTransient, (_settings.value.transientSeconds * 1000).toLong().coerceAtLeast(1500L))
    }

    fun dismissTransient() { main.removeCallbacks(clearTransient); _transient.value = null }

    // ------------------------------------------------------------ Bildirimler

    private fun cs(n: Notification, k: String) = n.extras?.getCharSequence(k)?.toString().orEmpty()

    private fun largeIcon(ctx: Context, n: Notification): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(ctx)?.let { d ->
            val w = d.intrinsicWidth.coerceIn(1, 192); val h = d.intrinsicHeight.coerceIn(1, 192)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b); d.setBounds(0, 0, w, h); d.draw(c); b
        }
    }.getOrNull()

    private fun action(n: Notification, vararg words: String): PendingIntent? = n.actions?.firstOrNull { a ->
        val t = a.title?.toString()?.lowercase(java.util.Locale("tr", "TR")).orEmpty()
        words.any { t.contains(it) }
    }?.actionIntent

    private val timePattern = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")

    /** Etkin bildirimlerin tamamından sürekli etkinlikleri yeniden kurar. */
    fun rebuild(ctx: Context, active: Array<StatusBarNotification>) {
        val out = ArrayList<IslandAct>()
        val me = ctx.packageName
        for (sbn in active) {
            if (sbn.packageName == me) continue
            val n = sbn.notification ?: continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val ex = n.extras ?: continue
            val title = cs(n, Notification.EXTRA_TITLE)
            val text = cs(n, Notification.EXTRA_TEXT)
            val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || ex.getBoolean("android.showChronometer")
            val isCall = n.category == Notification.CATEGORY_CALL || ex.containsKey("android.callType")
            when {
                isCall -> {
                    val type = ex.getInt("android.callType", 0) // 1 gelen, 2 süren, 3 taranan
                    val answer = action(n, "cevap", "yanıt", "answer", "kabul", "aç")
                    val hang = action(n, "reddet", "decline", "kapat", "bitir", "sonlandır", "hang", "end", "reject")
                    val incoming = type == 1 || (type == 0 && answer != null && !chrono)
                    val name = ex.getParcelable<android.app.Person>("android.callPerson")?.name?.toString()?.takeIf { it.isNotBlank() } ?: title
                    out += IslandAct.Call(sbn.key, sbn.packageName, n.contentIntent, name.ifBlank { "Arama" }, if (chrono) n.`when` else 0L, incoming, largeIcon(ctx, n), answer, hang)
                }
                !sbn.isOngoing -> Unit
                n.category == Notification.CATEGORY_NAVIGATION || sbn.packageName == "com.google.android.apps.maps" ->
                    out += IslandAct.Nav(sbn.key, sbn.packageName, n.contentIntent, title, text, largeIcon(ctx, n))
                chrono || (sbn.packageName.contains("clock", true) && (timePattern.containsMatchIn(title) || timePattern.containsMatchIn(text))) -> {
                    val down = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
                    val t = timePattern.find(title)?.value ?: timePattern.find(text)?.value.orEmpty()
                    out += IslandAct.Timer(sbn.key, sbn.packageName, n.contentIntent, if (t == title) text else title, if (chrono) n.`when` else 0L, down, t)
                }
                ex.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 || ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) -> {
                    if (n.category == Notification.CATEGORY_TRANSPORT || ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue
                    val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX)
                    val v = if (max > 0) ex.getInt(Notification.EXTRA_PROGRESS) / max.toFloat() else 0f
                    out += IslandAct.Progress(sbn.key, sbn.packageName, n.contentIntent, title.ifBlank { text }, v.coerceIn(0f, 1f), max <= 0)
                }
            }
        }
        out.sortByDescending { it.priority }
        // Yeni gelen arama: ada kendiliğinden büyür, sistemin arama penceresi açılırsa gizlenir.
        val s = _settings.value
        out.filterIsInstance<IslandAct.Call>().filter { it.incoming && it.key !in ringing }.forEach {
            if (s.enabled && s.calls) HeadsUpHider.request(it.pkg)
        }
        ringing.clear(); out.filterIsInstance<IslandAct.Call>().filter { it.incoming }.forEach { ringing += it.key }
        _ongoing.value = out
    }

    /** Yeni bir bildirim geldi: mesaj ise kısa süre adada önizlenir. */
    fun posted(ctx: Context, sbn: StatusBarNotification, importance: Int) {
        val s = _settings.value
        if (!s.enabled || !s.notifications) return
        if (sbn.packageName == ctx.packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return
        if (importance in 0 until android.app.NotificationManager.IMPORTANCE_DEFAULT) return // sessiz bildirimler
        val title = cs(n, Notification.EXTRA_TITLE)
        val text = (n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        // Aynı bildirimin güncellenmesi (aynı içerik) yeniden gösterilmez.
        val h = (title + "\u0000" + text).hashCode()
        if (seen[sbn.key] == h) return
        seen[sbn.key] = h
        if (seen.size > 200) seen.clear()
        if (System.currentTimeMillis() - sbn.postTime > 15_000) return
        // Arama sürerken mesaj adayı kaplamaz.
        if (_ongoing.value.any { it is IslandAct.Call }) return
        showTransient(IslandAct.Message(sbn.key, sbn.packageName, n.contentIntent, title.ifBlank { appLabel(ctx, sbn.packageName) }, text, largeIcon(ctx, n)))
        // Aynı bildirimin sistem penceresi açılırsa gizlenir: yalnızca ada genişler.
        HeadsUpHider.request(sbn.packageName)
    }

    fun removed(key: String) {
        seen.remove(key)
        val t = _transient.value
        if (t is IslandAct.Message && t.key == key) dismissTransient()
    }

    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    // ------------------------------------------------------------ Şarj ve sessiz mod

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = _settings.value
            if (!s.enabled) return
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> if (s.charging) {
                    val bm = c.getSystemService(BatteryManager::class.java)
                    val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
                    showTransient(IslandAct.Charging(lvl, i.action == Intent.ACTION_POWER_CONNECTED))
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> if (s.ringer && !isInitialStickyBroadcast) {
                    showTransient(IslandAct.Ringer(i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)))
                }
            }
        }
    }

    fun registerReceivers(ctx: Context) {
        if (receiversOn) return
        receiversOn = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        runCatching { ctx.applicationContext.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) }
            .onFailure { runCatching { ctx.applicationContext.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) } }
    }

    fun unregisterReceivers(ctx: Context) {
        if (!receiversOn) return
        receiversOn = false
        runCatching { ctx.applicationContext.unregisterReceiver(receiver) }
    }

    /** Bildirimin/etkinliğin uygulamasını açar. */
    fun open(ctx: Context, a: IslandAct): Boolean {
        val pi = a.open
        if (pi != null) {
            val ok = runCatching {
                val opts = android.app.ActivityOptions.makeBasic().apply {
                    if (android.os.Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                pi.send(null, 0, null, null, null, null, opts.toBundle()); true
            }.getOrDefault(false)
            if (ok) return true
        }
        if (a is IslandAct.Music) return NowPlaying.open(ctx)
        val i = a.pkg.takeIf { it.isNotEmpty() }?.let { ctx.packageManager.getLaunchIntentForPackage(it) } ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    fun fire(pi: PendingIntent?) {
        if (pi == null) return
        runCatching {
            val opts = android.app.ActivityOptions.makeBasic().apply {
                if (android.os.Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            pi.send(null, 0, null, null, null, null, opts.toBundle())
        }
    }
}
