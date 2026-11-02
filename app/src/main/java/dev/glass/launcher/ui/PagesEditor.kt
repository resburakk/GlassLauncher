package dev.glass.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * gizlenir/gösterilir, basılı tutup sürükleyerek canlı sıralanır, ev simgesiyle ana sayfa seçilir, gizli sayfa "−" ile
 * silinir. "Bitti" ile uygulanır.
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
    // Ana sayfa: kayıtlı sıradaki görünür sayfa (sayfa sürüklense de seçim sayfayla birlikte gider).
    var homeKey by remember {
        mutableStateOf(entries.filter { !it.hidden }.let { v -> v.getOrNull(layout.homePage) ?: v.firstOrNull() }?.key)
    }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }   // sürüklenen sayfanın başladığı yuvanın merkezi
    var dragTotal by remember { mutableStateOf(Offset.Zero) }   // parmağın toplam hareketi

    fun apply() {
        val visibleKeys = entries.filter { !it.hidden }.map { it.key }
        val home = visibleKeys.indexOf(homeKey).coerceAtLeast(0)
        vm.applyPages(entries.map { it.items to it.hidden }, deleted.toList(), home)
        onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sayfaları Düzenle", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, modifier = Modifier.weight(1f))
                GlassButton("Bitti", strength, 34.dp) { apply() }
            }
            Text(
                "Basılı tutup sürükleyerek sayfaların yerini değiştir. Ev simgesiyle ana sayfayı seç. İşareti kaldırınca sayfa gizlenir, gizli sayfa \"−\" ile silinir.",
                color = Color(0xCCFFFFFF), fontSize = 13.sp, fontFamily = AppFont,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            val scroll = rememberScrollState()
            BoxWithConstraints(
                Modifier.fillMaxWidth().verticalScroll(scroll, enabled = dragKey == null).padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                val density = LocalDensity.current
                val gapX = 20.dp
                val cellW = (maxWidth - gapX) / 2
                val thumbH = cellW * 2f
                val rowH = thumbH + 10.dp + 28.dp + 22.dp
                val cellWpx = with(density) { cellW.toPx() }
                val gapXpx = with(density) { gapX.toPx() }
                val rowHpx = with(density) { rowH.toPx() }
                val thumbHpx = with(density) { thumbH.toPx() }
                fun slotOrigin(i: Int) = Offset((i % 2) * (cellWpx + gapXpx), (i / 2) * rowHpx)
                fun slotAt(p: Offset): Int {
                    val col = if (p.x < cellWpx + gapXpx / 2f) 0 else 1
                    val row = (p.y / rowHpx).toInt().coerceAtLeast(0)
                    return (row * 2 + col).coerceIn(0, entries.size - 1)
                }
                val rowsCount = (entries.size + 1) / 2
                Box(Modifier.fillMaxWidth().height(rowH * rowsCount + 40.dp)) {
                    entries.forEachIndexed { index, e ->
                        key(e.key) {
                            val dragging = dragKey == e.key
                            val dim by animateFloatAsState(if (e.hidden) 0.45f else 1f, label = "pageDim")
                            // Diğer sayfalar yeni yuvalarına kayarak geçer; sürüklenen sayfa parmağı izler.
                            val target = slotOrigin(index)
                            val animated by animateIntOffsetAsState(
                                IntOffset(target.x.roundToInt(), target.y.roundToInt()), label = "pageSlot",
                            )
                            val isHome = homeKey == e.key && !e.hidden
                            Column(
                                Modifier
                                    .width(cellW)
                                    .zIndex(if (dragging) 1f else 0f)
                                    .offset {
                                        if (dragging) {
                                            val c = dragStart + dragTotal
                                            IntOffset((c.x - cellWpx / 2f).roundToInt(), (c.y - thumbHpx / 2f).roundToInt())
                                        } else animated
                                    }
                                    .graphicsLayer { if (dragging) { scaleX = 1.06f; scaleY = 1.06f; shadowElevation = 24f } }
                                    .pointerInput(e.key) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                val i = entries.indexOfFirst { it.key == e.key }
                                                dragKey = e.key; dragIndex = i; dragTotal = Offset.Zero
                                                dragStart = slotOrigin(i) + Offset(cellWpx / 2f, thumbHpx / 2f)
                                            },
                                            onDragEnd = { dragKey = null; dragIndex = -1; dragTotal = Offset.Zero },
                                            onDragCancel = { dragKey = null; dragIndex = -1; dragTotal = Offset.Zero },
                                        ) { change, d ->
                                            change.consume()
                                            dragTotal += d
                                            // Parmak başka bir yuvaya gelince sayfalar canlı yer değiştirir (iOS gibi).
                                            val to = slotAt(dragStart + dragTotal)
                                            val from = entries.indexOfFirst { it.key == e.key }
                                            if (from >= 0 && to != from) { entries.add(to, entries.removeAt(from)); dragIndex = to }
                                        }
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
                                    if (isHome) {
                                        Text(
                                            "Ana Sayfa", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(50))
                                                .background(Color(0xFF0A84FF)).padding(horizontal = 9.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    // Onay işareti: görünür / gizli
                                    Box(
                                        Modifier.size(28.dp).clip(CircleShape)
                                            .then(if (e.hidden) Modifier.border(2.dp, Color.White, CircleShape) else Modifier.background(Color(0xFF0A84FF)))
                                            .clickable {
                                                // En az bir sayfa görünür kalmalı.
                                                if (!e.hidden && entries.count { !it.hidden } <= 1) return@clickable
                                                e.hidden = !e.hidden
                                                // Ana sayfa gizlenirse ilk görünür sayfa ana sayfa olur.
                                                if (e.hidden && homeKey == e.key) homeKey = entries.firstOrNull { !it.hidden }?.key
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) { if (!e.hidden) GlyphIcon(Glyph.Check, Color.White, 16.dp) }
                                    // Ana sayfa yap (ev simgesi)
                                    if (!e.hidden) Box(
                                        Modifier.size(28.dp).clip(CircleShape)
                                            .then(if (isHome) Modifier.background(Color.White) else Modifier.border(2.dp, Color(0x99FFFFFF), CircleShape))
                                            .clickable { homeKey = e.key }
                                            .semantics { contentDescription = if (isHome) "Ana sayfa" else "Ana sayfa yap" },
                                        contentAlignment = Alignment.Center,
                                    ) { HouseGlyph(if (isHome) Color(0xFF0A84FF) else Color.White, 15.dp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { e ->
        GlassAlert(
            title = "Sayfa silinsin mi?",
            message = "Sayfadaki uygulamalar ve klasörler silinmez, Uygulama Arşivi'nde kalır. Widget'lar kaldırılır.",
            buttons = listOf(
                GlassAlertButton("Sayfayı Sil", destructive = true) { deleted += e.items; entries.remove(e); confirmDelete = null },
                GlassAlertButton("Vazgeç", bold = true) { confirmDelete = null },
            ),
            onDismiss = { confirmDelete = null },
        )
    }
}

/** Ev simgesi (ana sayfa). */
@Composable
private fun HouseGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val sw = w * 0.13f
        val roof = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.08f, h * 0.48f); lineTo(w * 0.5f, h * 0.1f); lineTo(w * 0.92f, h * 0.48f)
        }
        drawPath(roof, color, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.22f, h * 0.42f); lineTo(w * 0.22f, h * 0.9f); lineTo(w * 0.42f, h * 0.9f); lineTo(w * 0.42f, h * 0.64f)
            lineTo(w * 0.58f, h * 0.64f); lineTo(w * 0.58f, h * 0.9f); lineTo(w * 0.78f, h * 0.9f); lineTo(w * 0.78f, h * 0.42f)
        }
        drawPath(body, color, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
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
