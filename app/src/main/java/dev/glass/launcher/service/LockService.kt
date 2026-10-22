package dev.glass.launcher.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Çift dokunarak kilitleme, sistem panelleri ve launcher'ın kendi son kullanılanlar ekranı için Erişilebilirlik
 * hizmeti. Ekrandaki yazıları OKUMAZ; yalnızca hangi uygulamanın önde olduğunu (pencere değişimi) izler ve o
 * uygulama açıkken birkaç saniyede bir ekran görüntüsü alıp kart önizlemesi olarak telefonda saklar.
 * Bu yöntemle kilitlenen telefon parmak izi/yüz tanıma ile açılabilir (Cihaz Yöneticisi yöntemi bunu bozar).
 * Kullanıcı Ayarlar > Erişilebilirlik'ten açıkça etkinleştirmelidir.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        // Erişilebilirlik düğmesi/kısayolu: launcher'ın son kullanılanlar ekranını açar (uygulama içindeyken de).
        runCatching {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                        openLauncherRecents()
                    }
                },
            )
        }
    }

    private fun openLauncherRecents() {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .setPackage(packageName)
                    .putExtra(dev.glass.launcher.MainActivity.EXTRA_RECENTS, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // --- Son kullanılanlar önizlemesi ---
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Şu an önde olan (önizlemesi alınan) uygulama; launcher/sistem ekranında null. */
    private var current: String? = null
    private val launchable = HashMap<String, Boolean>()

    private fun isApp(pkg: String): Boolean = launchable.getOrPut(pkg) {
        pkg != packageName && pkg != "com.android.systemui" &&
            runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false) &&
            !isHome(pkg)
    }

    private fun isHome(pkg: String): Boolean = runCatching {
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    }.getOrDefault(false)

    private val tick = object : Runnable {
        override fun run() {
            val pkg = current ?: return
            capture(pkg)
            main.postDelayed(this, 2500)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg == packageName || isHome(pkg) -> stop()
            isApp(pkg) -> if (pkg != current) {
                current = pkg
                main.removeCallbacks(tick)
                // Uygulama açılış animasyonu bitsin; sonra birkaç saniyede bir güncellenir.
                main.postDelayed(tick, 900)
            }
            // Klavye, bildirim paneli, sistem pencereleri: önizleme alınmaz ama önde olan uygulama değişmez.
            else -> Unit
        }
    }

    private fun stop() {
        current = null
        main.removeCallbacks(tick)
    }

    private fun capture(pkg: String) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isInteractive == false) return
        runCatching {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, io, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    // Görüntü alınırken uygulama değiştiyse (ör. launcher'a dönüldü) kaydetme.
                    if (current != pkg) { result.hardwareBuffer.close(); return }
                    runCatching {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        if (hw != null) {
                            val soft = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            // Kartlar ekranın yaklaşık %70'i: yarım çözünürlük kartta tam net görünür.
                            val out = android.graphics.Bitmap.createScaledBitmap(soft, soft.width / 2, soft.height / 2, true)
                            val f = dev.glass.launcher.data.RecentApps.thumbFile(this@LockService, pkg)
                            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
                            tmp.outputStream().use { o ->
                                @Suppress("DEPRECATION")
                                val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                                else android.graphics.Bitmap.CompressFormat.WEBP
                                out.compress(fmt, 92, o)
                            }
                            tmp.renameTo(f)
                            if (out !== soft) out.recycle()
                            soft.recycle()
                            hw.recycle()
                            dev.glass.launcher.data.RecentApps.bumpThumbs()
                        }
                    }
                    result.hardwareBuffer.close()
                }

                // Güvenli ekranlar (bankacılık vb.) görüntü vermez: kartta simge gösterilir.
                override fun onFailure(errorCode: Int) = Unit
            })
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: LockService? = null

        /** Kilitlediyse true; hizmet kapalıysa false. */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        /** Sistemin Kontrol Merkezi'ni (hızlı ayarlar) açar; hizmet kapalıysa false. */
        fun quickSettings(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) == true

        /** Sistemin kendi son kullanılanlar ekranını açar; hizmet kapalıysa false. */
        fun systemRecents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true

        /** Hizmet şu an bağlı mı (önizlemeler alınabiliyor mu)? */
        val running: Boolean get() = instance != null

        /** Sistemin bildirim gölgesini açar; hizmet kapalıysa false. */
        fun notifications(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) == true

        /** Hizmet sistem ayarlarında etkin mi? */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, LockService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
