package dev.glass.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Otomatik test için ayar değiştirme (yalnızca bilgisayardan adb ile; DUMP izni gerektiği için uygulamalar
 * gönderemez): am broadcast -a dev.glass.launcher.SET -n dev.glass.launcher/.DebugSettingsReceiver --es key K --es value V
 */
class DebugSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val raw = intent.getStringExtra("value") ?: return
        val app = context.applicationContext as? LauncherApp ?: return
        val pr = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.settingsStore.update { s ->
                    val o = s.toJson()
                    when {
                        raw == "true" || raw == "false" -> o.put(key, raw == "true")
                        raw.toDoubleOrNull() != null -> o.put(key, raw.toDouble())
                        else -> o.put(key, raw)
                    }
                    LauncherSettings.fromJson(o)
                }
            } finally { pr.finish() }
        }
    }
}
