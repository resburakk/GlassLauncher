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
    private val userManager = context.getSystemService(android.os.UserManager::class.java)
    private val _profiles = MutableStateFlow<List<ProfileState>>(emptyList())
    /** İş profili ve Özel Alan durumları (ana profil hariç). */
    val profiles: StateFlow<List<ProfileState>> = _profiles

    private fun kindOf(user: UserHandle): ProfileKind {
        if (user == android.os.Process.myUserHandle()) return ProfileKind.Main
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            val t = runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull()
            if (t == android.os.UserManager.USER_TYPE_PROFILE_PRIVATE) return ProfileKind.Private
        }
        return ProfileKind.Work
    }

    /** İş profilini duraklatır / açar ya da Özel Alan'ı kilitler / açar (açarken sistem kimlik sorar). */
    fun setQuiet(user: UserHandle, quiet: Boolean) {
        runCatching { userManager.requestQuietModeEnabled(quiet, user) }
        refresh()
    }

    private val _pinned = MutableStateFlow<List<AppInfo>>(emptyList())
    /** Ana ekrana sabitlenmiş kısayollar (yalnızca ana ekranda görünür; arşivde ve aramada yok). */
    val pinned: StateFlow<List<AppInfo>> = _pinned
    /**
     * Kısayol listesi güvenilir mi: en az bir kez okundu ve launcher varsayılan ana ekran (Android kısayolları
     * yalnızca ona verir). Değilse kayıtlı düzendeki kısayollar silinmez, korunur.
     */
    @Volatile var pinnedReliable = false
        private set

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) = refreshPinned()
    }

    fun start() {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        // Profil açıldı / duraklatıldı / kilitlendi: liste ve durumlar yenilenir.
        val f = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
            }
        }
        runCatching {
            context.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = refresh()
            }, f, Context.RECEIVER_NOT_EXPORTED)
        }
        refresh()
    }

    fun refresh() {
        scope.launch {
            val collator = Collator.getInstance(Locale("tr", "TR"))
            val list = withContext(Dispatchers.Default) {
                val states = launcherApps.profiles.map { u ->
                    ProfileState(u, kindOf(u), runCatching { userManager.isQuietModeEnabled(u) }.getOrDefault(false))
                }
                _profiles.value = states.filter { it.kind != ProfileKind.Main }
                states.flatMap { st ->
                    val profile = st.user
                    val kind = st.kind
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
                            profile = kind,
                        )
                    }
                }.sortedWith { a, b -> collator.compare(a.label, b.label) }
            }
            _apps.value = list
            refreshPinned()
        }
    }

    /** Sabitlenmiş kısayolları yeniden okur. */
    fun refreshPinned() {
        scope.launch {
            val host = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)
            if (!host) { pinnedReliable = false; return@launch }
            val list = runCatching {
                launcherApps.profiles.flatMap { user ->
                    val q = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                    val acts = HashMap<String, List<android.content.pm.LauncherActivityInfo>>()
                    launcherApps.getShortcuts(q, user).orEmpty().mapNotNull { sc ->
                        val list = acts.getOrPut(sc.`package`) { launcherApps.getActivityList(sc.`package`, user) }
                        val act = list.firstOrNull { it.componentName == sc.activity } ?: list.firstOrNull()
                            ?: return@mapNotNull null
                        AppInfo(
                            componentName = act.componentName,
                            user = user,
                            label = (sc.shortLabel ?: sc.longLabel ?: act.label).toString(),
                            activityInfo = act,
                            isSystem = true,
                            shortcut = sc,
                        )
                    }
                }
            }.getOrNull() ?: return@launch
            _pinned.value = list
            pinnedReliable = true
        }
    }

    /** Kısayolun sabitlemesini kaldırır (ana ekrandan silindi). Listeden hemen düşer. */
    fun unpin(app: AppInfo) {
        val sc = app.shortcut ?: return
        val rest = _pinned.value.filter { it.key != app.key }
        _pinned.value = rest
        val keep = rest.mapNotNull { it.shortcut }.filter { it.`package` == sc.`package` && it.userHandle == sc.userHandle }.map { it.id }
        runCatching { launcherApps.pinShortcuts(sc.`package`, keep, sc.userHandle) }
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
        val sc = app.shortcut
        if (sc != null) {
            runCatching { launcherApps.startShortcut(sc, sourceBounds, options) }
            return
        }
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
