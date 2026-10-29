package dev.glass.launcher.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * MagicOS / EMUI arka planda uygulamaları (ve bildirim dinleyicisini) agresif biçimde kapatır. Dinleyicinin ayakta
 * kalması için kullanıcıyı ilgili sistem ekranlarına yönlendirir. Bu ekranları açmak dışında hiçbir ayarı değiştirmez.
 */
object BackgroundAccess {
    /** Pil optimizasyonundan muaf mı (sistem uygulamayı arka planda kısıtlamıyor). */
    fun batteryExempt(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)

    /** Pil optimizasyonundan muafiyet ister (sistemin kendi onay penceresi); olmazsa genel listeyi açar. */
    fun requestBatteryExempt(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
        if (!start(ctx, direct)) start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * Honor / Huawei "Uygulama başlatma" yönetimi: burada Glass Launcher için "Otomatik yönet" kapatılıp
     * "Otomatik başlat", "İkincil başlat" ve "Arka planda çalış" açılmalı. Ekran bulunamazsa uygulama bilgisi açılır.
     */
    fun openStartupManager(ctx: Context) {
        val candidates = listOf(
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        for (c in candidates) if (start(ctx, Intent().setComponent(c))) return
        openAppDetails(ctx)
    }

    fun openAppDetails(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
    }

    /** Bildirim erişimi: önce uygulamanın kendi ayrıntı ekranı, olmazsa liste. */
    fun openNotificationAccess(ctx: Context) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, BadgeListener.component(ctx).flattenToString())
        if (!start(ctx, detail)) start(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun start(ctx: Context, i: Intent): Boolean = runCatching {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
