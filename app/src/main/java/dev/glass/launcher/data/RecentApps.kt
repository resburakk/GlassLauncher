package dev.glass.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Launcher'ın kendi "son kullanılanlar" ekranı için veri: hangi uygulamaların hangi sırayla kullanıldığı
 * (Android "Kullanım erişimi" izniyle) ve kart önizlemeleri (erişilebilirlik hizmetinin aldığı ekran
 * görüntüleri; yalnızca telefonda, uygulamanın kendi klasöründe saklanır, hiçbir yere gönderilmez).
 */
object RecentApps {
    // UsageEvents.Event sabitleri (API 29+; adlarıyla eski SDK'larda da derlensin diye sayı olarak).
    private const val EVENT_ACTIVITY_DESTROYED = 24
    private const val EVENT_DEVICE_SHUTDOWN = 26
    private const val EVENT_DEVICE_STARTUP = 27

    /** Önizlemeler değişince artar (ekran yeni görüntüleri yükler). */
    private val _thumbVersion = MutableStateFlow(0)
    val thumbVersion: StateFlow<Int> = _thumbVersion

    fun bumpThumbs() { _thumbVersion.value++ }

    fun thumbDir(ctx: Context): File = File(ctx.filesDir, "recents").apply { mkdirs() }
    fun thumbFile(ctx: Context, pkg: String): File = File(thumbDir(ctx), "$pkg.webp")

    /** Kayıtlı önizleme (yoksa null). */
    fun loadThumb(ctx: Context, pkg: String): Bitmap? = runCatching {
        val f = thumbFile(ctx, pkg)
        if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    /** "Kullanım erişimi" izni verilmiş mi? */
    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** İzin ekranı (Ayarlar > Kullanım erişimi), mümkünse doğrudan bu uygulamanın sayfası. */
    fun usageAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Kartı kapatılan uygulamalar: kapatıldığı an (ms). Uygulama yeniden kullanılınca tekrar görünür. */
    private fun dismissedPrefs(ctx: Context) = ctx.getSharedPreferences("recents_dismissed", Context.MODE_PRIVATE)

    fun dismiss(ctx: Context, pkg: String) {
        dismissedPrefs(ctx).edit().putLong(pkg, System.currentTimeMillis()).apply()
        runCatching {
            // Arka plandaki işlemleri kapat (ön plandaki/sistem uygulamaları etkilenmez).
            ctx.getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(pkg)
        }
    }

    /**
     * Son kullanılan uygulamalar, en yeni önce. [apps]: launcher'daki uygulamalar (yalnızca simgesi olanlar).
     * İzin yoksa [fallback] (launcher'dan açılanlar) döner.
     */
    fun load(ctx: Context, apps: List<AppInfo>, fallback: List<AppInfo>, limit: Int = 24): List<AppInfo> {
        if (!hasUsageAccess(ctx)) return fallback.take(limit)
        val byPkg = apps.groupBy { it.packageName }
        val now = System.currentTimeMillis()
        val last = HashMap<String, Long>()
        // Yalnızca hâlâ AÇIK olan uygulamalar: her uygulamanın açık ekranları (etkinlikleri) izlenir. Geri ile çıkılan
        // ya da sistemin son kullanılanlarından atılan uygulamanın ekranları kapanır ("yok edildi" olayı) ve listeden
        // çıkar. Telefon yeniden başlayınca hepsi sıfırlanır.
        val live = HashMap<String, HashSet<String>>()
        runCatching {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val ev = usm.queryEvents(now - 3L * 24 * 3600_000L, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        live.getOrPut(e.packageName) { HashSet() } += (e.className ?: "")
                        if (e.eventType != UsageEvents.Event.ACTIVITY_STOPPED) {
                            val prev = last[e.packageName] ?: 0L
                            if (e.timeStamp > prev) last[e.packageName] = e.timeStamp
                        }
                    }
                    EVENT_ACTIVITY_DESTROYED -> live[e.packageName]?.remove(e.className ?: "")
                    EVENT_DEVICE_SHUTDOWN, EVENT_DEVICE_STARTUP -> { live.clear(); last.clear() }
                }
            }
        }
        last.keys.retainAll { live[it]?.isNotEmpty() == true }
        val dismissed = dismissedPrefs(ctx)
        return last.entries
            .asSequence()
            .filter { it.key != ctx.packageName && byPkg.containsKey(it.key) }
            .filter { it.value > dismissed.getLong(it.key, 0L) }
            .sortedByDescending { it.value }
            .mapNotNull { byPkg[it.key]?.firstOrNull() }
            .take(limit)
            .toList()
    }
}
