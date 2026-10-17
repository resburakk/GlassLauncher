package dev.glass.launcher.data

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.os.UserHandle

/** Başlatılabilir bir uygulama girişi. Kimlik: bileşen + kullanıcı profili. */
class AppInfo(
    val componentName: ComponentName,
    val user: UserHandle,
    val label: String,
    val activityInfo: LauncherActivityInfo,
    /** Uygulama Arşivi kategorisi. */
    val category: AppCategory = AppCategory.Other,
    /** İlk kurulum zamanı (ms). */
    val installTime: Long = 0L,
    /** Kaldırılamayan sistem uygulaması mı? */
    val isSystem: Boolean = false,
) {
    val key: String = "${componentName.flattenToString()}#${user.hashCode()}"
    val packageName: String get() = componentName.packageName
    /**
     * Uygulamanın yüklü sürümünü belirten damga: her güncellemede APK yolu (ve simge kaynağı) değişir.
     * Simge önbelleği bununla anahtarlanır; güncellenen uygulamanın yeni simgesi hemen görünür.
     */
    val iconStamp: String = runCatching {
        val ai = activityInfo.applicationInfo
        "${ai.sourceDir}:${ai.icon}:${activityInfo.activityInfo.icon}"
    }.getOrDefault("")
    override fun equals(other: Any?) = other is AppInfo && other.key == key && other.label == label && other.iconStamp == iconStamp
    override fun hashCode() = key.hashCode()
}
