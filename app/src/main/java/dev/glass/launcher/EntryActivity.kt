package dev.glass.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle

/**
 * "Glass Ayarları" simgesi ve kurulumdaki "Aç" düğmesi buraya gelir; ekranda hiçbir şey göstermeden launcher'ı
 * ANA EKRAN isteğiyle açar ve kapanır.
 *
 * Neden: launcher sıradan bir uygulama isteğiyle (LAUNCHER) açılırsa sistem onu "ana ekran" görevi değil, sıradan
 * bir uygulama görevi olarak başlatır ve launcher o görevde kalır. O zaman sistem ana ekrandayken bile bir
 * uygulamanın içindeymişiz gibi davranır: son uygulamalar ancak ikinci kaydırmada açılır, son uygulamalarda boşluğa
 * dokununca ana ekrana dönülmez ve launcher son uygulamalarda kart olarak görünür.
 */
class EntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isHome = runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }
            .getOrDefault(false)
        // Kurulum/güncellemeden sonraki ilk açılış ("Aç" düğmesi): ana ekran. Diğer durumlarda (ve henüz varsayılan
        // ana ekran değilken: oradan varsayılan yapılabilir) ayarlar kendi ekranında açılır.
        val firstRun = runCatching {
            val stamp = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            val prefs = getSharedPreferences("launch", MODE_PRIVATE)
            val seen = prefs.getLong("seenUpdate", 0L)
            prefs.edit().putLong("seenUpdate", stamp).apply()
            seen != stamp
        }.getOrDefault(false)
        val target = when {
            firstRun && isHome ->
                // Bileşen belirtilmeden ana ekran isteği: sistem launcher'ı gerçek ana ekran görevinde açar.
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
            else -> Intent(this, SettingsActivity::class.java)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(target) }.onFailure {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETTINGS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
