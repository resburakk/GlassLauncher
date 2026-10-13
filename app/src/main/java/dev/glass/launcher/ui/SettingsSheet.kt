package dev.glass.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.LauncherSettings
import java.util.Locale

private val Blue = Color(0xFF0A84FF)
private val RowBg = Color(0xFF2C2C2E)
private val Divider = Color(0xFF3A3A3C)
private val Muted = Color(0xFF8E8E93)

/** iOS ayarlar görünümlü (gruplanmış liste) ana ekran ayarları sayfası. Değişiklikler anında uygulanır. */
@Composable
fun SettingsSheet(
    s: LauncherSettings,
    onChange: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(Color(0xF21C1C1E))
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ana Ekran", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "Bitti", color = Blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onClose() }.padding(8.dp)
                )
            }

            Group("Simgeler") {
                SliderRow("Simge boyutu", "%${(s.iconScale * 100).toInt()}", s.iconScale, 0.7f..1.4f) { v ->
                    onChange { it.copy(iconScale = v) }
                }
                RowDivider()
                ToggleRow("Uygulama adları", s.showLabels) { v -> onChange { it.copy(showLabels = v) } }
                RowDivider()
                ChoiceRow("Sütun sayısı", listOf(3, 4, 5), s.columns) { v -> onChange { it.copy(columns = v) } }
                RowDivider()
                ChoiceRow("En çok satır", listOf(5, 6, 7), s.rows) { v -> onChange { it.copy(rows = v) } }
            }

            Group("Liquid Glass") {
                ToggleRow("Simge kenar ışığı", s.iconGlass) { v -> onChange { it.copy(iconGlass = v) } }
                RowDivider()
                SliderRow("Cam yoğunluğu", "%${(s.glassStrength * 100).toInt()}", s.glassStrength, 0.5f..1.5f) { v ->
                    onChange { it.copy(glassStrength = v) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(Locale("tr", "TR")), color = Muted, fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RowBg), content = content)
}

@Composable
private fun RowDivider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Divider))
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF34C759),
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Color(0xFF39393D),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Muted, fontSize = 16.sp)
        }
        Slider(
            value = value, onValueChange = onValue, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF48484A),
            ),
        )
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                Box(
                    Modifier
                        .width(40.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (o == selected) Blue else Color(0xFF3A3A3C))
                        .clickable { onSelect(o) },
                    contentAlignment = Alignment.Center,
                ) { Text("$o", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
