package dev.glass.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.content.pm.LauncherApps
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
                        AppInfo(info.componentName, profile, info.label.toString(), info)
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

    fun launch(app: AppInfo) {
        runCatching {
            launcherApps.startMainActivity(app.componentName, app.user, null, null)
        }
    }
}
