package dev.glass.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.AppItem
import dev.glass.launcher.data.FolderItem
import dev.glass.launcher.data.GapItem
import dev.glass.launcher.data.GridPacker
import dev.glass.launcher.data.HomeItem
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.PageBreak
import dev.glass.launcher.data.WidgetItem
import java.util.UUID
import kotlin.math.roundToInt

private class PageEntry(val key: String, val items: List<HomeItem>, hidden: Boolean) {
    var hidden by mutableStateOf(hidden)
}

/**
 * iOS "Sayfaları Düzenle": ana ekran sayfaları küçük önizlemeler olarak görünür. Onay işaretiyle sayfa
 * gizlenir/gösterilir, basılı tutup sürükleyerek sıralanır, gizli sayfa "−" ile silinir. "Bitti" ile uygulanır.
 */
@Composable
fun PagesEditor(
    vm: LauncherViewModel,
    layout: HomeLayout,
    byKey: Map<String, AppInfo>,
    columns: Int,
    rows: Int,
    strength: Float,
    onClose: () -> Unit,
) {
    val visible: (HomeItem) -> Boolean = { it !is AppItem || byKey.containsKey(it.key) }
    val entries = remember {
        val v = layout.visiblePages(columns, rows, visible).map { PageEntry(UUID.randomUUID().toString(), it, false) }.toMutableList()
        layout.hiddenPages.sortedBy { it.pos }.forEach { hp ->
            v.add(hp.pos.coerceIn(0, v.size), PageEntry(UUID.randomUUID().toString(), hp.items, true))
        }
        mutableStateListOf<PageEntry>().apply { addAll(v) }
    }
    val deleted = remember { mutableStateListOf<HomeItem>() }
    var confirmDelete by remember { mutableStateOf<PageEntry?>(null) }
    val bounds = remember { HashMap<String, Rect>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    fun apply() {
        vm.applyPages(entries.map { it.items to it.hidden }, deleted.toList())
        onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sayfaları Düzenle", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, modifier = Modifier.weight(1f))
                GlassButton("Bitti", strength, 34.dp) { apply() }
            }
            Text(
                "İşareti kaldırınca sayfa gizlenir. Sıralamak için basılı tutup sürükle. Gizli sayfa \"−\" ile silinebilir.",
                color = Color(0xCCFFFFFF), fontSize = 13.sp, fontFamily = AppFont,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        pair.forEach { e ->
                            val dragging = dragKey == e.key
                            val dim by animateFloatAsState(if (e.hidden) 0.45f else 1f, label = "pageDim")
                            Column(
                                Modifier
                                    .weight(1f)
                                    .zIndex(if (dragging) 1f else 0f)
                                    .offset { if (dragging) IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) else IntOffset.Zero }
                                    .graphicsLayer { if (dragging) { scaleX = 1.05f; scaleY = 1.05f } }
                                    .onGloballyPositioned { bounds[e.key] = it.boundsInRoot() }
                                    .pointerInput(e.key) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragKey = e.key; dragOffset = Offset.Zero },
                                            onDragEnd = {
                                                val from = entries.indexOfFirst { it.key == e.key }
                                                val center = bounds[e.key]?.center?.plus(dragOffset)
                                                val to = center?.let { c ->
                                                    entries.indexOfFirst { o -> o.key != e.key && bounds[o.key]?.contains(c) == true }
                                                } ?: -1
                                                if (from >= 0 && to >= 0) entries.add(to, entries.removeAt(from))
                                                dragKey = null; dragOffset = Offset.Zero
                                            },
                                            onDragCancel = { dragKey = null; dragOffset = Offset.Zero },
                                        ) { change, d -> change.consume(); dragOffset += d }
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box {
                                    PageThumb(e.items, vm, byKey, columns, rows, Modifier.graphicsLayer { alpha = dim })
                                    if (e.hidden) {
                                        Box(
                                            Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp).size(26.dp).clip(CircleShape)
                                                .background(Color(0xFF8E8E93)).clickable { confirmDelete = e },
                                            contentAlignment = Alignment.Center,
                                        ) { Box(Modifier.width(11.dp).height(2.5.dp).background(Color.Black)) }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                // Onay işareti: görünür / gizli
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape)
                                        .then(if (e.hidden) Modifier.border(2.dp, Color.White, CircleShape) else Modifier.background(Color(0xFF0A84FF)))
                                        .clickable {
                                            // En az bir sayfa görünür kalmalı.
                                            if (!e.hidden && entries.count { !it.hidden } <= 1) return@clickable
                                            e.hidden = !e.hidden
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { if (!e.hidden) GlyphIcon(Glyph.Check, Color.White, 16.dp) }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    confirmDelete?.let { e ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Sayfa silinsin mi?") },
            text = { Text("Sayfadaki uygulamalar ve klasörler silinmez, Uygulama Arşivi'nde kalır. Widget'lar kaldırılır.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleted += e.items; entries.remove(e); confirmDelete = null
                }) { Text("Sil", color = Color(0xFFFF453A)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("Vazgeç") } },
        )
    }
}

/** Bir sayfanın küçük önizlemesi: simgeler, klasörler ve widget'lar ızgaradaki yerlerinde. */
@Composable
private fun PageThumb(items: List<HomeItem>, vm: LauncherViewModel, byKey: Map<String, AppInfo>, columns: Int, rows: Int, modifier: Modifier) {
    val placements = remember(items, columns, rows) {
        GridPacker.pack(items, columns, rows) { it !is GapItem && (it !is AppItem || byKey.containsKey(it.key)) }
            .filter { it.page == 0 && it.item !is PageBreak && it.item !is GapItem }
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(0.5f).clip(RoundedCornerShape(18.dp))
            .background(Color(0x26FFFFFF)).border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(18.dp)),
    ) {
        val cw = maxWidth / columns
        val ch = (maxHeight * 0.82f) / rows
        val icon = minOf(cw, ch) * 0.62f
        placements.forEach { p ->
            val x = cw * p.col + (cw * p.spanX - (if (p.item is WidgetItem) cw * p.spanX * 0.88f else icon)) / 2f
            val y = maxHeight * 0.06f + ch * p.row + (ch * p.spanY - (if (p.item is WidgetItem) ch * p.spanY * 0.88f else icon)) / 2f
            Box(Modifier.offset(x, y)) {
                when (val it = p.item) {
                    is AppItem -> byKey[it.key]?.let { app -> AppIconImage(app, vm, icon, false, 0f, badge = false) }
                    is FolderItem -> Box(Modifier.size(icon).clip(RoundedCornerShape(icon * 0.25f)).background(Color(0x55FFFFFF)))
                    is WidgetItem -> Box(
                        Modifier.width(cw * p.spanX * 0.88f).height(ch * p.spanY * 0.88f)
                            .clip(RoundedCornerShape(10.dp)).background(Color(0x40FFFFFF)),
                    )
                    else -> Unit
                }
            }
        }
    }
}
