package dev.glass.launcher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.UserManager
import android.util.SizeF
import android.view.ViewGroup

/**
 * Gerçek Android widget'larını barındırır (AppWidgetHost). Uygulama ömrü boyunca tek örnek.
 * Widget görünümleri kimliğe göre önbelleğe alınır; yeniden oluşturma maliyeti ve titreme önlenir.
 */
class WidgetHostManager(private val context: Context) {
    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    val host: AppWidgetHost = GlassAppWidgetHost(context, HOST_ID)
    private val views = HashMap<Int, AppWidgetHostView>()
    private val prefs = context.getSharedPreferences("widget_glass", Context.MODE_PRIVATE)

    /** Cam görünüm tercihleri değişince artar (Compose yeniden çizer). */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    /** Kullanıcının "Orijinal Görünüm" seçtiği widget'lar. */
    fun isOriginal(id: Int): Boolean = prefs.getStringSet("orig", emptySet())!!.contains(id.toString())

    fun setOriginal(id: Int, original: Boolean) {
        val s = prefs.getStringSet("orig", emptySet())!!.toMutableSet()
        if (original) s.add(id.toString()) else s.remove(id.toString())
        prefs.edit().putStringSet("orig", s).apply()
        version.intValue++
    }

    private fun providerKey(info: AppWidgetProviderInfo): String {
        val pkg = info.provider.packageName
        val ver = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).longVersionCode
        }.getOrDefault(0L)
        return info.provider.flattenToString() + "@" + ver
    }

    /** Sağlayıcı ekranda çalışırken cama uymadığı görüldü (hazır resim çiziyor). */
    fun markIncompatible(info: AppWidgetProviderInfo) {
        prefs.edit().putBoolean("c:" + providerKey(info), false).apply()
        android.util.Log.i("GlassDiag", "widget uyumsuz (çalışırken): ${info.provider.flattenToShortString()}")
    }

    /**
     * Widget cam görünüme uygun mu? Sonuç uygulama sürümüyle birlikte saklanır; uygulama güncellenince
     * yeniden incelenir. Ana iş parçacığında çağrılmamalı.
     */
    /** Bu widget'ın kodu launcher içinde hata verdi mi? [mainOnly]: ana ekranı çökertenler. */
    fun crashed(info: AppWidgetProviderInfo, mainOnly: Boolean = false): Boolean {
        val pkg = info.provider.packageName
        val keys = if (mainOnly) listOf("crashMain") else listOf("crash", "crashMain")
        val classes = keys.flatMap { prefs.getStringSet(it, emptySet())!! }
        // Sınıf adı genelde paketle başlar (com.hihonor.calendar.widget… ↔ com.hihonor.calendar); ilk iki parça da eşlenir.
        val root = pkg.split('.').take(2).joinToString(".") + "."
        return classes.any { it.startsWith("$pkg.") || (it.startsWith(root) && pkg.substringAfterLast('.').let { last -> it.contains(".$last.") }) }
    }

    fun isCompatible(info: AppWidgetProviderInfo): Boolean {
        if (crashed(info)) return false
        val k = "c:" + providerKey(info)
        if (prefs.contains(k)) return prefs.getBoolean(k, true)
        val ok = runCatching {
            GlassWidgetStyle.inflateExecutor.submit<Boolean> { GlassWidgetStyle.probe(context, info) }.get(4, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false)
        prefs.edit().putBoolean(k, ok).apply()
        android.util.Log.i("GlassDiag", "widget ${if (ok) "uygun" else "uyumsuz"}: ${info.provider.flattenToShortString()}")
        return ok
    }

    fun startListening() {
        runCatching { host.startListening() }
    }

    fun stopListening() {
        runCatching { host.stopListening() }
    }

    /** Tüm profillerdeki (iş profili dahil) widget sağlayıcıları. */
    fun providers(): List<AppWidgetProviderInfo> {
        val um = context.getSystemService(UserManager::class.java)
        return um.userProfiles.flatMap { p ->
            runCatching { manager.getInstalledProvidersForProfile(p) }.getOrDefault(emptyList())
        }
    }

    fun allocate(): Int = host.allocateAppWidgetId()

    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean =
        runCatching { manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }.getOrDefault(false)

    fun info(id: Int): AppWidgetProviderInfo? = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()

    fun delete(id: Int) {
        views.remove(id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        runCatching { host.deleteAppWidgetId(id) }
    }

    /** Bu launcher'a bağlı, artık ekranda olmayan widget kimliklerini temizler. */
    fun deleteOrphans(keep: Set<Int>) {
        runCatching { host.appWidgetIds }.getOrNull()?.forEach { id -> if (id !in keep) delete(id) }
    }

    fun view(id: Int): AppWidgetHostView? {
        views[id]?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            return v
        }
        val info = info(id) ?: return null
        // Ana iş parçacığında launcher'ı çökertmiş widget yeniden çalıştırılmaz (çökme döngüsü olmasın).
        if (crashed(info, mainOnly = true)) return null
        val v = runCatching { host.createView(context, id, info) }.getOrNull() ?: return null
        (v as? GlassWidgetHostView)?.onIncompatible = { markIncompatible(it) }
        views[id] = v
        return v
    }

    /** Sağlayıcıya widget'ın ekrandaki boyutunu bildirir (dp). */
    fun updateSize(id: Int, widthDp: Float, heightDp: Float) {
        views[id]?.let { v ->
            runCatching { v.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp))) }
        }
    }

    fun isConfigurable(id: Int): Boolean = info(id)?.configure != null

    companion object {
        const val HOST_ID = 0x61A5
    }
}
