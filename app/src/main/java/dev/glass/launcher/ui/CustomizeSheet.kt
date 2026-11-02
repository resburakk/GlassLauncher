package dev.glass.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.IconStyle
import dev.glass.launcher.data.LauncherSettings

private val Palette = listOf(0xFF64D2FF, 0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158).map { it.toInt() }

/**
 * iOS 18/26 "Özelleştir" paneli: düzenleme modundaki "+" menüsünden alttan açılır. Simge görünümü
 * (Varsayılan, Koyu, Açık, Renkli, Şeffaf), şeffaf camın tonu, renkli simgenin rengi ve simge boyutu
 * anında değişir; ana ekran arkada canlı görünür.
 */
@Composable
fun CustomizeSheet(
    visible: Boolean,
    settings: LauncherSettings,
    strength: Float,
    onChange: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onClose: () -> Unit,
) {
    val motion = LocalMotion.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(motion.tween(180)), exit = fadeOut(motion.tween(160))) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose,
                )
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(motion.bouncy()) { it } + fadeIn(motion.tween(160)),
            exit = slideOutVertically(motion.tween(200)) { it } + fadeOut(motion.tween(160)),
        ) {
            // Açılır menüyle aynı cam ve yazı rengi; köşeler dock ile aynı yarıçapta (bütünlük).
            val st = LocalLauncherSettings.current
            val dark = menuDarkNow()
            val fg = menuInk(dark)
            val corner = LocalHomeMetrics.current?.dockCorner ?: 34.dp
            val shape = appShape(corner)
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                BlurBackdrop(corner, Modifier.matchParentSize(), menuBlur = true)
                androidx.compose.runtime.CompositionLocalProvider(LocalSheetInk provides fg) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .menuGlass(shape, (st.menuOpacity + 0.15f).coerceAtMost(0.95f), dark)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(fg.copy(alpha = 0.3f)))
                    Spacer(Modifier.height(12.dp))
                    Text("Özelleştir", color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
                    Spacer(Modifier.height(16.dp))

                    // Görünüm: açık ve koyu mod için birlikte (Varsayılan: açıkta orijinal, koyuda koyu — iOS gibi).
                    val cur = when {
                        settings.iconStyle == IconStyle.Original && settings.iconStyleDark == IconStyle.Dark -> "auto"
                        settings.iconStyle == settings.iconStyleDark -> settings.iconStyle.name
                        else -> ""
                    }
                    Label("Görünüm")
                    Segmented(
                        listOf("auto" to "Varsayılan", IconStyle.Dark.name to "Koyu", IconStyle.Light.name to "Açık", IconStyle.Tinted.name to "Renkli", IconStyle.Clear.name to "Şeffaf"),
                        cur,
                    ) { k ->
                        onChange {
                            when (k) {
                                "auto" -> it.copy(iconStyle = IconStyle.Original, iconStyleDark = IconStyle.Dark)
                                else -> IconStyle.valueOf(k).let { st -> it.copy(iconStyle = st, iconStyleDark = st) }
                            }
                        }
                    }

                    if (cur == IconStyle.Clear.name) {
                        Spacer(Modifier.height(12.dp))
                        Label("Şeffaf cam")
                        Segmented(listOf("light" to "Açık", "dark" to "Koyu", "auto" to "Otomatik"), settings.clearTone) { k ->
                            onChange { it.copy(clearTone = k) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Label("Yüzey")
                        // Buzlu: arkası bulanık. Cam: iOS 26 Clear gibi arkası net, duvar kâğıdı simgenin içinden görünür.
                        Segmented(listOf("frost" to "Buzlu", "glass" to "Cam"), settings.clearSurface) { k ->
                            onChange { it.copy(clearSurface = k) }
                        }
                    }
                    if (cur == IconStyle.Tinted.name) {
                        Spacer(Modifier.height(12.dp))
                        Label("Renk")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Swatch(
                                Brush.sweepGradient(listOf(Color(0xFFFF375F), Color(0xFFFFD60A), Color(0xFF30D158), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF375F))),
                                settings.tintColor == 0, "A",
                            ) { onChange { it.copy(tintColor = 0) } }
                            Palette.forEach { c ->
                                Swatch(Brush.linearGradient(listOf(Color(c), Color(c))), settings.tintColor == c, null) { onChange { it.copy(tintColor = c) } }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    // Boyut: Küçük (etiketli) / Büyük (iOS gibi etiketsiz, büyük simge).
                    val large = !settings.showLabels && settings.iconScale >= 1.2f
                    Label("Boyut")
                    Segmented(listOf("small" to "Küçük", "large" to "Büyük"), if (large) "large" else "small") { k ->
                        onChange {
                            if (k == "large") it.copy(showLabels = false, iconScale = 1.25f)
                            else it.copy(showLabels = true, iconScale = if (it.iconScale >= 1.2f) 1.1f else it.iconScale)
                        }
                    }
                }
                }
            }
        }
    }
}

/** Panel yazı rengi (açılır menüyle aynı: koyu menüde beyaz, açıkta koyu gri). */
private val LocalSheetInk = androidx.compose.runtime.staticCompositionLocalOf { Color.White }

@Composable
private fun Label(t: String) {
    Text(
        t, color = LocalSheetInk.current.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 7.dp),
    )
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val fg = LocalSheetInk.current
    val dark = fg.red > 0.5f
    Row(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(fg.copy(alpha = 0.10f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (k, t) ->
            val sel = k == selected
            Box(
                // Uzun adlar ("Varsayılan") daha geniş yer alır: yazı sıkışmaz, kesilmez.
                Modifier.weight(0.9f + t.length * 0.09f).fillMaxSize().clip(RoundedCornerShape(18.dp))
                    .background(if (sel) (if (dark) Color.White.copy(alpha = 0.92f) else Color.White) else Color.Transparent)
                    .clickable { onSelect(k) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t, color = if (sel) Color(0xFF1C1C1E) else fg, fontSize = if (options.size >= 5) 13.sp else 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, fontFamily = AppFont, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun Swatch(brush: Brush, selected: Boolean, label: String?, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(brush)
            .then(if (selected) Modifier.border(2.dp, LocalSheetInk.current, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (label != null) Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}
