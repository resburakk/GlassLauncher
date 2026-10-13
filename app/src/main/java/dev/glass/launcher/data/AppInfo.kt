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
) {
    val key: String = "${componentName.flattenToString()}#${user.hashCode()}"
    override fun equals(other: Any?) = other is AppInfo && other.key == key && other.label == label
    override fun hashCode() = key.hashCode()
}
