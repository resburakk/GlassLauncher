package dev.glass.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.glass.launcher.data.WallpaperApplier
import dev.glass.launcher.data.WallpaperTarget
import dev.glass.launcher.ui.AppFont
import kotlinx.coroutines.launch

/**
 * Galeride bir fotoğraf açılıp ⋮ > "Farklı ayarla" > "Glass Launcher duvar kâğıdı" seçilince açılır.
 * Kullanıcı yeri seçer (ana ekran / kilit / ikisi); görsel launcher'a kopyalanır ve sistem duvar
 * kâğıdı olarak da ayarlanır. Ağ kullanılmaz, izin istenmez (görsel erişimi galerinin verdiği geçici izinle).
 */
class WallpaperImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = imageUri(intent)
        if (uri == null) {
            Toast.makeText(this, "Görsel bulunamadı.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val app = application as LauncherApp
        setContent {
            var busy by remember { mutableStateOf(false) }
            fun choose(t: WallpaperTarget) {
                if (busy) return
                busy = true
                lifecycleScope.launch {
                    val msg = WallpaperApplier.apply(app, uri, t)
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { if (!busy) finish() },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(28.dp)
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xF22C2C2E))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Duvar kâğıdı yap", color = Color.White, fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                    )
                    Text(
                        "Görsel Glass Launcher'da ve telefonun duvar kâğıdında uygulanır.",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center,
                        fontFamily = AppFont,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (busy) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(16.dp))
                    } else {
                        WallpaperTarget.entries.forEach { t ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (t == WallpaperTarget.Both) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.12f))
                                    .clickable { choose(t) }
                                    .padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(t.title, color = Color.White, fontSize = 16.sp, fontFamily = AppFont)
                            }
                        }
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { finish() }.padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Vazgeç", color = Color(0xFF0A84FF), fontSize = 16.sp, fontFamily = AppFont)
                        }
                    }
                }
            }
        }
    }

    private fun imageUri(i: Intent?): Uri? = i?.data
        ?: i?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) }
}
