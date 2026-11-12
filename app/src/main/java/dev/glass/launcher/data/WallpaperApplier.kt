package dev.glass.launcher.data

import android.app.WallpaperManager
import android.net.Uri
import dev.glass.launcher.LauncherApp

/** Duvar kâğıdının uygulanacağı yer (Honor'un kendi seçeneklerindeki gibi). */
enum class WallpaperTarget(val code: String, val title: String) {
    Home("home", "Ana ekran"),
    Lock("lock", "Kilit ekranı"),
    Both("both", "İkisi de");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: Both
    }
}

/**
 * Seçilen görseli uygular: ana ekran seçildiyse launcher'ın kendi kopyası (gerçek cam bulanıklığı
 * için) güncellenir ve aynı görsel sistem duvar kâğıdı yapılır. Ağ kullanılmaz; her şey cihazda.
 * Dönen metin kullanıcıya gösterilir.
 */
object WallpaperApplier {
    suspend fun apply(app: LauncherApp, uri: Uri, target: WallpaperTarget): String {
        val repo = app.wallpaperRepository
        val home = target != WallpaperTarget.Lock
        if (home) {
            if (!repo.save(uri)) return "Görsel açılamadı."
            app.settingsStore.update { it.copy(launcherWallpaper = true, wallpaperTarget = target.code) }
        } else {
            app.settingsStore.update { it.copy(wallpaperTarget = target.code) }
        }
        val flags = when (target) {
            WallpaperTarget.Home -> WallpaperManager.FLAG_SYSTEM
            WallpaperTarget.Lock -> WallpaperManager.FLAG_LOCK
            WallpaperTarget.Both -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        // Ana ekran için launcher kopyasından (hazır dosya), yalnız kilit ekranı için doğrudan kaynaktan.
        val ok = repo.applyToSystem(flags, if (home) null else uri)
        return when {
            ok -> "Duvar kâğıdı ayarlandı (${target.title.lowercase()})."
            home -> "Launcher duvar kâğıdı ayarlandı; sistem duvar kâğıdı değiştirilemedi."
            else -> "Kilit ekranı duvar kâğıdı değiştirilemedi."
        }
    }
}
