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

    // ------------------------------------------------------------ Shizuku ile izin

    /** 0 = Shizuku çalışmıyor / kurulu değil, 1 = Shizuku izni gerekiyor, 2 = hazır. */
    fun shizukuState(): Int = runCatching {
        if (!rikka.shizuku.Shizuku.pingBinder()) 0
        else if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 2 else 1
    }.getOrDefault(0)

    /**
     * Shizuku üzerinden "pm grant" çalıştırıp WRITE_SECURE_SETTINGS iznini verir. Gerekirse önce Shizuku'nun kendi
     * izin penceresini açar. Sonuç ana iş parçacığında [done] ile bildirilir (null = başarılı, metin = hata).
     */
    fun grantViaShizuku(ctx: Context, done: (String?) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun run() {
            Thread {
                val err = runCatching {
                    val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                        "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
                    ).apply { isAccessible = true }
                    val p = m.invoke(null, arrayOf("pm", "grant", ctx.packageName, android.Manifest.permission.WRITE_SECURE_SETTINGS), null, null) as Process
                    val out = p.errorStream.bufferedReader().readText() + p.inputStream.bufferedReader().readText()
                    p.waitFor()
                    if (granted(ctx)) null else "izin verilemedi: ${out.take(160)}"
                }.getOrElse { "hata: ${it.cause?.message ?: it.message}" }
                main.post { done(err) }
            }.start()
        }
        when (shizukuState()) {
            0 -> done("Shizuku çalışmıyor. Shizuku uygulamasını açıp \"Başlat\"a bas.")
            2 -> run()
            else -> {
                val code = 7301
                val l = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != code) return
                        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                        if (grantResult == PackageManager.PERMISSION_GRANTED) run() else main.post { done("Shizuku izni reddedildi") }
                    }
                }
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(l)
                runCatching { rikka.shizuku.Shizuku.requestPermission(code) }.onFailure { done("hata: ${it.message}") }
            }
        }
    }
}
