package dev.glass.launcher.ui

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Üstten açılan cam "Kontrol Paneli". Yalnızca izin gerektirmeyen, Android'in izin verdiği araçlar:
 * fener, medya sesi, pil, internet/Bluetooth/uçak modu sistem ekranlarına kısayol.
 * Sistem bildirim gölgesinin yerini almaz.
 */
@Composable
fun ControlPanel(strength: Float, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var torch by remember { mutableStateOf(false) }
    val battery = remember {
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    val now = remember { LocalDateTime.now() }
    val tr = remember { Locale("tr", "TR") }

    fun openSettings(action: String) {
        runCatching {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onClose()
        }
    }

    fun toggleTorch() {
        runCatching {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, !torch)
            torch = !torch
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -80.dp.toPx()) onClose() },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
    ) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(start = 6.dp, bottom = 4.dp)) {
                Text(
                    now.format(DateTimeFormatter.ofPattern("HH:mm")),
                    color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    now.format(DateTimeFormatter.ofPattern("d MMMM EEEE", tr)),
                    color = Color(0xCCFFFFFF), fontSize = 15.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTile("📶", "İnternet", false, strength) { openSettings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) }
                GlassTile("🔵", "Bluetooth", false, strength) { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
                GlassTile("✈️", "Uçak modu", false, strength) { openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
                GlassTile("🔦", "Fener", torch, strength) { toggleTorch() }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(RoundedCornerShape(26.dp), strength, 6.dp)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔊", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Slider(
                    value = vol,
                    onValueChange = {
                        vol = it
                        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) }
                    },
                    valueRange = 0f..maxVol.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color(0x55FFFFFF),
                    ),
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(RoundedCornerShape(26.dp), strength, 6.dp)
                    .padding(18.dp)
            ) {
                Text("Pil  %$battery", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color(0x44FFFFFF))) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(battery.coerceIn(0, 100) / 100f)
                            .background(Color(0xFF34C759))
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.GlassTile(glyph: String, label: String, active: Boolean, strength: Float, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .aspectRatio(1f)
            .liquidGlass(RoundedCornerShape(24.dp), strength, 6.dp)
            .background(if (active) Color(0x66FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(glyph, fontSize = 24.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}
