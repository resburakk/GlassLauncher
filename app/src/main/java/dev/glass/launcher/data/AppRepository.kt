package dev.glass.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * LauncherApps üzerinden yüklü uygulamaları listeler.
 * Sürekli tarama yok: yalnızca sistem geri çağrıları (kurma/kaldırma/güncelleme) listeyi yeniler.
 */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    fun start() {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        scope.launch {
            val collator = Collator.getInstance(Locale("tr", "TR"))
            val list = withContext(Dispatchers.Default) {
                launcherApps.profiles.flatMap { profile ->
                    launcherApps.getActivityList(null, profile).map { info ->
                        val ai = info.applicationInfo
                        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                        val label = info.label.toString()
                        AppInfo(
                            componentName = info.componentName,
                            user = profile,
                            label = label,
                            activityInfo = info,
                            category = AppCategorizer.categorize(info.componentName.packageName, label, ai.category, system),
                            installTime = info.firstInstallTime,
                            isSystem = system,
                        )
                    }
                }.sortedWith { a, b -> collator.compare(a.label, b.label) }
            }
            _apps.value = list
        }
    }

    /** Varsayılan dock: Telefon, Mesajlar, Tarayıcı, Kamera (cihazın varsayılan çözümleyicilerinden). */
    val defaultDockPackages: List<String> by lazy {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        intents.mapNotNull { i ->
            context.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.filter { it != "android" }.distinct()
    }

    private fun resolvePkg(i: Intent): String? = runCatching {
        context.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it != "android" }

    /** Canlı saat simgesi çizilecek saat uygulamaları (Honor, Huawei, Google, AOSP + varsayılan). */
    val clockPackages: Set<String> by lazy {
        setOf("com.hihonor.deskclock", "com.android.deskclock", "com.google.android.deskclock", "com.huawei.deskclock") +
            listOfNotNull(resolvePkg(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)))
    }

    /** Canlı takvim simgesi çizilecek takvim uygulamaları. */
    val calendarPackages: Set<String> by lazy {
        setOf("com.hihonor.calendar", "com.android.calendar", "com.google.android.calendar", "com.huawei.calendar") +
            listOfNotNull(resolvePkg(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)))
    }

    /** [options]: simgeden büyüyen açılış animasyonu (ActivityOptions). Animasyonu sistem çizer. */
    fun launch(app: AppInfo, sourceBounds: Rect? = null, options: Bundle? = null) {
        dev.glass.launcher.LaunchTracker.mark()
        runCatching {
            launcherApps.startMainActivity(app.componentName, app.user, sourceBounds, options)
        }
    }

    /** Sistemin "Uygulama bilgisi" ekranı. */
    fun openAppInfo(app: AppInfo) {
        dev.glass.launcher.LaunchTracker.mark()
        runCatching { launcherApps.startAppDetailsActivity(app.componentName, app.user, null, null) }
    }

    /** Sistemin kaldırma onay penceresini açar (kaldırma kararını kullanıcı verir). */
    fun uninstall(app: AppInfo) {
        runCatching {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                .putExtra(Intent.EXTRA_USER, app.user)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    /**
     * Uygulama kısayolları (uzun basma menüsü). Android bunları yalnızca VARSAYILAN ana ekran
     * uygulamasına verir; değilse boş liste döner.
     */
    suspend fun shortcuts(app: AppInfo): List<ShortcutInfo> = withContext(Dispatchers.Default) {
        runCatching {
            if (!launcherApps.hasShortcutHostPermission()) return@runCatching emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(app.componentName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            launcherApps.getShortcuts(q, app.user).orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .take(4)
        }.getOrDefault(emptyList())
    }

    suspend fun shortcutIcon(info: ShortcutInfo, density: Int): Drawable? = withContext(Dispatchers.Default) {
        runCatching { launcherApps.getShortcutIconDrawable(info, density) }.getOrNull()
    }

    fun startShortcut(info: ShortcutInfo) {
        dev.glass.launcher.LaunchTracker.mark()
        runCatching { launcherApps.startShortcut(info, null, null) }
    }
}
