package dev.glass.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Sistem widget'ı ekleme akışı (bağlama izni + yapılandırma ekranı) Activity'de yürür. */
interface WidgetRequester {
    fun addSystemWidget(info: AppWidgetProviderInfo, spanX: Int, spanY: Int)
    fun reconfigure(appWidgetId: Int)
}

val LocalWidgetRequester = staticCompositionLocalOf<WidgetRequester?> { null }

/** iOS boyutları: küçük 2×2, orta 4×2, büyük 4×4. */
enum class WidgetSize(val title: String, val spanX: Int, val spanY: Int) {
    Small("Küçük", 2, 2), Medium("Orta", 4, 2), Large("Büyük", 4, 4)
}

private class PickerEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val builtin: WidgetKind?,
    val info: AppWidgetProviderInfo?,
    val defaultSize: WidgetSize,
)

/**
 * Widget galerisi: önce launcher'ın kendi widget'ları (saat, analog saat, takvim, pil, fotoğraf),
 * ardından telefondaki uygulamaların gerçek Android widget'ları. Seçince boyut sorulur ve eklenir.
 */
@Composable
fun WidgetPicker(vm: LauncherViewModel, strength: Float, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val requester = LocalWidgetRequester.current
    var selected by remember { mutableStateOf<String?>(null) }

    val entries by produceState(emptyList<PickerEntry>()) {
        val builtins = listOf(
            PickerEntry("b:clock", "Saat", "Dijital saat ve tarih", WidgetKind.DigitalClock, null, WidgetSize.Small),
            PickerEntry("b:analog", "Analog Saat", "Saniye ibreli kadran", WidgetKind.AnalogClock, null, WidgetSize.Small),
            PickerEntry("b:cal", "Takvim", "Bugün ve ay görünümü", WidgetKind.Calendar, null, WidgetSize.Small),
            PickerEntry("b:bat", "Pil", "Telefonun pil durumu", WidgetKind.Battery, null, WidgetSize.Small),
            PickerEntry("b:photo", "Fotoğraf", "Seçtiğiniz bir fotoğraf", WidgetKind.Photo, null, WidgetSize.Medium),
        )
        val pm = ctx.packageManager
        val system = withContext(Dispatchers.Default) {
            vm.widgetHost.providers().map { info ->
                val label = runCatching { info.loadLabel(pm) }.getOrNull() ?: info.provider.shortClassName
                val appLabel = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                }.getOrDefault(info.provider.packageName)
                val wide = info.targetCellWidth >= 3 || info.minWidth > ctx.resources.displayMetrics.density * 200
                val tall = info.targetCellHeight >= 3 || info.minHeight > ctx.resources.displayMetrics.density * 200
                PickerEntry(
                    "s:${info.provider.flattenToString()}#${info.profile.hashCode()}", label, appLabel, null, info,
                    when { wide && tall -> WidgetSize.Large; wide -> WidgetSize.Medium; else -> WidgetSize.Small },
                )
            }.sortedWith(compareBy({ it.subtitle.lowercase() }, { it.title.lowercase() }))
        }
        value = builtins + system
    }

    fun add(e: PickerEntry, size: WidgetSize) {
        if (e.builtin != null) {
            vm.addWidget(WidgetItem(UUID.randomUUID().toString(), e.builtin, size.spanX, size.spanY))
            onClose()
        } else if (e.info != null && requester != null) {
            requester.addSystemWidget(e.info, size.spanX, size.spanY)
            onClose()
        }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(Color(0xF21C1C1E))
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Widget Ekle", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "Kapat", color = Color(0xFF0A84FF), fontSize = 17.sp,
                    modifier = Modifier.clickable { onClose() }.padding(8.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(entries, key = { it.key }) { e ->
                    val open = selected == e.key
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF2C2C2E))
                            .clickable { selected = if (open) null else e.key }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EntryPreview(e)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(e.subtitle, color = Color(0xFF8E8E93), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (open) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WidgetSize.entries.forEach { sz ->
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (sz == e.defaultSize) Color(0xFF0A84FF) else Color(0xFF3A3A3C))
                                            .clickable { add(e, sz) },
                                        contentAlignment = Alignment.Center,
                                    ) { Text(sz.title, color = Color.White, fontSize = 15.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryPreview(e: PickerEntry) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, e.key) {
        val info = e.info ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val d = runCatching { info.loadPreviewImage(ctx, ctx.resources.displayMetrics.densityDpi) }.getOrNull()
                ?: runCatching { info.loadIcon(ctx, ctx.resources.displayMetrics.densityDpi) }.getOrNull()
            d?.let { dr ->
                val w = dr.intrinsicWidth.coerceIn(1, 360)
                val h = dr.intrinsicHeight.coerceIn(1, 360)
                dr.toBitmap(w, h).asImageBitmap()
            }
        }
    }
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3A3C)).heightIn(max = 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = img
        when {
            bmp != null -> Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            e.builtin == WidgetKind.DigitalClock || e.builtin == WidgetKind.AnalogClock ->
                Text("12:30", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            e.builtin == WidgetKind.Calendar -> Text("13", color = Color(0xFFFF453A), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            e.builtin == WidgetKind.Battery -> Text("%", color = Color(0xFF30D158), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            e.builtin == WidgetKind.Photo -> GlyphIcon(Glyph.Wallpaper, Color.White, 26.dp)
            else -> GlyphIcon(Glyph.Widget, Color.White, 26.dp)
        }
    }
}
