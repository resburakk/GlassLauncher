package dev.glass.launcher.ui

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * Uygulama kilidi ve gizli uygulamalar için kimlik doğrulama: telefonun kendi parmak izi / yüz tanıma /
 * ekran kilidi penceresi (sistem BiometricPrompt). Launcher hiçbir biyometrik veriyi görmez veya saklamaz.
 *
 * Not: kilit launcher'dan açılışlarda çalışır. Uygulama bildirimden veya son kullanılanlardan açılırsa
 * sistem kilidi (MagicOS "Uygulama Kilidi") gibi engelleyemez; bu Android'in launcher'lara tanıdığı sınır.
 */
object AppLock {
    /** Pencereyi açacak etkinlik (ana ekran veya ayarlar; ön plana gelince kendini kaydeder). */
    @Volatile private var host: WeakReference<Activity>? = null
    private val unlockedAt = HashMap<String, Long>()

    fun attach(a: Activity) { host = WeakReference(a) }

    /** Telefonda ekran kilidi (PIN, desen, şifre) var mı? Yoksa kilit uygulanamaz. */
    fun deviceSecure(a: Activity? = host?.get()): Boolean =
        a?.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Aynı uygulama birkaç saniye içinde yeniden açılırsa tekrar sorulmaz (çift dokunma vb.). */
    fun recentlyUnlocked(key: String): Boolean =
        unlockedAt[key]?.let { SystemClock.elapsedRealtime() - it < 5_000L } == true

    fun markUnlocked(key: String) { unlockedAt[key] = SystemClock.elapsedRealtime() }

    /** Kimlik doğrulama ister; başarılı olursa [onOk] çalışır. Ekran kilidi yoksa doğrudan geçer. */
    fun authenticate(title: String, subtitle: String? = null, onOk: () -> Unit) {
        val a = host?.get() ?: return
        if (!deviceSecure(a) || Build.VERSION.SDK_INT < 30) { onOk(); return }
        runCatching {
            val prompt = BiometricPrompt.Builder(a)
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onOk()
            })
        }
    }
}
