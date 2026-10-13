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
    override fun equals(other: Any?) = other is AppInfo && other.key == key && other.label == label
    override fun hashCode() = key.hashCode()
}
