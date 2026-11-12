package dev.glass.launcher.service

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Sistemin üstten açılan bildirim pencerelerini (heads-up) Android'in kendi ayarıyla tamamen kapatır:
 * Settings.Global "heads_up_notifications_enabled" = 0. Pencere hiç oluşmaz (gizleme süresi sıfır).
 *
 * Bu ayarı değiştirmek WRITE_SECURE_SETTINGS izni ister; kullanıcı bir kez ADB ile verir:
 *   adb shell pm grant dev.glass.launcher android.permission.WRITE_SECURE_SETTINGS
 * Ada ya da seçenek kapatılınca eski değer geri yüklenir.
 */
object SystemHeadsUp {
    private const val KEY = "heads_up_notifications_enabled"
    private const val P = "system_headsup"
    const val GRANT_CMD = "adb shell pm grant dev.glass.launcher android.permission.WRITE_SECURE_SETTINGS"

    fun granted(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Sistem ayarının şu anki değeri: true = pencereler açık, false = kapalı, null = okunamadı. */
    fun systemEnabled(ctx: Context): Boolean? =
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY, 1) != 0 }.getOrNull()

    /** Launcher şu an sistem pencerelerini kapatmış durumda mı. */
    fun active(ctx: Context): Boolean = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("applied", false)

    fun sync(ctx: Context, s: IslandSettings) {
        val want = s.enabled && s.systemOff && granted(ctx)
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val applied = p.getBoolean("applied", false)
        runCatching {
            val cr = ctx.contentResolver
            if (want) {
                if (!applied) p.edit().putInt("orig", Settings.Global.getInt(cr, KEY, 1)).putBoolean("applied", true).apply()
                if (Settings.Global.getInt(cr, KEY, 1) != 0) Settings.Global.putInt(cr, KEY, 0)
            } else if (applied) {
                val orig = p.getInt("orig", 1)
                if (granted(ctx)) Settings.Global.putInt(cr, KEY, if (orig == 0) 0 else 1)
                p.edit().putBoolean("applied", false).apply()
            }
        }.onFailure { Log.w("GlassDiag", "headsup ayarı: ${it.message}") }
    }
}
