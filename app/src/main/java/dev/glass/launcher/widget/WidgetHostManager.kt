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
    val host = AppWidgetHost(context, HOST_ID)
    private val views = HashMap<Int, AppWidgetHostView>()
    private val prefs = context.getSharedPreferences("widget_glass", Context.MODE_PRIVATE)

    /** Bu widget'ın kodu launcher'ın ana iş parçacığında hata verip çökertti mi? (bkz. LauncherApp) */
    private fun crashedMain(info: AppWidgetProviderInfo): Boolean {
        val pkg = info.provider.packageName
        return prefs.getStringSet("crashMain", emptySet())!!.any { it.startsWith("$pkg.") }
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
        // Ana ekranı çökertmiş widget yeniden çalıştırılmaz (çökme döngüsü olmasın); kullanıcı kaldırabilir.
        if (crashedMain(info)) return null
        val v = runCatching { host.createView(context, id, info) }.getOrNull() ?: return null
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
