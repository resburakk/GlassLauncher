package dev.glass.launcher.ui

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Uygulama kilidi ve gizli uygulamalar için kimlik doğrulama: telefonun kendi parmak izi / yüz tanıma /
 * ekran kilidi penceresi (sistem BiometricPrompt). Launcher hiçbir biyometrik veriyi görmez veya saklamaz.
 *
 * Doğrulama bitince yapılacak iş (uygulamayı açmak) launcher YENİDEN ÖNE GELDİKTEN sonra çalıştırılır:
 * PIN/desen ekranı launcher'ı arka plana iter; o anda uygulama açmaya çalışmak Android'in arka plandan
 * başlatma kısıtına takılır ve sessizce hiçbir şey olmaz.
 *
 * Not: kilit launcher'dan açılışlarda çalışır. Uygulama bildirimden veya son kullanılanlardan açılırsa
 * sistem kilidi (MagicOS "Uygulama Kilidi") gibi engelleyemez; bu Android'in launcher'lara tanıdığı sınır.
 */
object AppLock {
    @Volatile private var host: WeakReference<Activity>? = null
    @Volatile private var resumed = false
    private var pending: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val unlockedAt = HashMap<String, Long>()

    /** Etkinlik öne geldi (onResume): bekleyen iş varsa şimdi çalışır. */
    fun attach(a: Activity) {
        host = WeakReference(a)
        resumed = true
        pending?.let { job ->
            pending = null
            // Pencere odağı tam gelsin diye bir kare sonra.
            main.postDelayed({ job() }, 60)
        }
    }

    /** Etkinlik arka plana geçti (onPause). */
    fun paused(a: Activity) {
        if (host?.get() === a) resumed = false
    }

    /** Telefonda ekran kilidi (PIN, desen, şifre) var mı? Yoksa kilit uygulanamaz. */
    fun deviceSecure(a: Activity? = host?.get()): Boolean =
        a?.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Aynı uygulama birkaç saniye içinde yeniden açılırsa tekrar sorulmaz (çift dokunma vb.). */
    fun recentlyUnlocked(key: String): Boolean =
        unlockedAt[key]?.let { SystemClock.elapsedRealtime() - it < 5_000L } == true

    fun markUnlocked(key: String) { unlockedAt[key] = SystemClock.elapsedRealtime() }

    /** İşi launcher öndeyse hemen, değilse öne geldiği an çalıştırır. */
    private fun runWhenResumed(job: () -> Unit) {
        main.post {
            val a = host?.get()
            if (resumed && a != null && !a.isFinishing) main.postDelayed({ job() }, 60) else pending = job
        }
    }

    /** Kimlik doğrulama ister; başarılı olursa [onOk] çalışır. Ekran kilidi yoksa doğrudan geçer. */
    fun authenticate(title: String, subtitle: String? = null, onOk: () -> Unit) {
        val a = host?.get() ?: return
        if (!deviceSecure(a) || Build.VERSION.SDK_INT < 30) { onOk(); return }
        val result = runCatching {
            val prompt = BiometricPrompt.Builder(a)
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = runWhenResumed(onOk)
                override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                    // Kullanıcı vazgeçtiyse sessiz; başka bir hata (donanım, kayıt yok vb.) ise nedeni gösterilir.
                    if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                        code != BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON
                    ) {
                        host?.get()?.let { Toast.makeText(it, "Kimlik doğrulanamadı: ${msg ?: code}", Toast.LENGTH_SHORT).show() }
                    }
                }
            })
        }
        if (result.isFailure) {
            Toast.makeText(a, "Kimlik doğrulama açılamadı: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }
}
