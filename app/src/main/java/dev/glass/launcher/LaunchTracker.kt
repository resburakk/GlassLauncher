package dev.glass.launcher

import android.os.SystemClock

/**
 * Launcher'ın kendisinin bir uygulama/pencere açtığı son an. Launcher bu açılış olmadan arka plana
 * geçiyorsa (ör. sistemin "Son uygulamalar" ekranı açıldı) ana ekran içeriği soldurulur.
 */
object LaunchTracker {
    @Volatile private var last = 0L

    fun mark() { last = SystemClock.uptimeMillis() }

    /** Son [windowMs] içinde launcher bir şey açtı mı? */
    fun recent(windowMs: Long = 1500L): Boolean = SystemClock.uptimeMillis() - last < windowMs
}
