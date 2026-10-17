package dev.glass.launcher.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures

import kotlinx.coroutines.launch

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.FolderItem
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.delay

/**
 * Açık klasör: ekranın ortasında cam panel, üstte düzenlenebilir ad, 3×3 sayfalar (yatay kaydırma).
 * Panel içinde sürükleyince yeniden sıralanır; panel dışına bırakılan uygulama ana ekrana taşınır.
 */
@Composable
fun FolderOverlay(
    folder: FolderItem,
    byKey: Map<String, AppInfo>,
    vm: LauncherViewModel,
    settings: LauncherSettings,
    drag: DragState,
    jiggle: State<Float>,
    edit: Boolean,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    onRename: (String) -> Unit,
    onReorder: (String, Int) -> Unit,
    onDropOutside: (String, Offset, String?) -> Unit,
    onDragLeftPanel: () -> Unit,
    onClose: () -> Unit,
    /** Klasör kapanıyor: iOS 26 gibi küçülürken ilk sayfaya kayar. */
    closing: Boolean = false,
    /** Ana ekrandaki çoklu seçim: açıkken uygulamalara dokunmak seçer. */
    selection: SelectionState? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current

    var name by remember(folder.id) { mutableStateOf(folder.name) }
    val nameNow by rememberUpdatedState(name)
    val renameNow by rememberUpdatedState(onRename)
    val originalName = remember(folder.id) { folder.name }
    // Panel kapanırken (Geri tuşu dahil) değişen ad kaydedilir.
    DisposableEffect(folder.id) {
        onDispose {
            val n = nameNow.trim()
            if (n.isNotEmpty() && n != originalName) renameNow(n)
        }
    }

    var panelRect by remember { mutableStateOf(Rect.Zero) }

    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { keyboard?.hide(); onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        // iOS 26 ölçüleri (402 pt genişlikte): panel ≈304×344 pt, köşe 34 pt, iç kenar 27.5 pt yan / 29 pt üst;
        // simgeler, sütun ve satır aralığı ANA EKRANLA AYNI. "Açık klasör boyutu" ayarı aralığı ölçekler.
        val hm = LocalHomeMetrics.current
        val u = hm?.pt ?: (maxWidth / 402f)
        val icon = hm?.icon ?: (u * 65f)
        val k = (settings.folderSize / 0.756f).coerceIn(0.75f, 1.3f)
        val pitch = ((hm?.pitchX ?: (u * 92f)) * k).coerceAtLeast(icon + 4.dp)
        val rowH = ((hm?.pitchY ?: (u * 98f)) * k).coerceAtLeast(icon + 24.dp)
        val sidePad = u * 27.5f
        val topPad = u * 29f
        val bottomPad = u * 15f
        val panelW = (pitch * 2f + icon + sidePad * 2f).coerceAtMost(maxWidth - 16.dp)
        val panelShape = appShape((u * settings.folderCorner).coerceAtMost(panelW / 2f))
        val pages = folder.keys.chunked(9)
        val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, maxOf(pages.size - 1, 0))) {
            maxOf(pages.size, 1)
        }
        val pageChanged by rememberUpdatedState(onPageChanged)
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { pageChanged(it) }
        }
        LaunchedEffect(closing) {
            if (closing && pagerState.currentPage != 0) pagerState.animateScrollToPage(0)
        }
        // Tek sayfalı klasör: yana kaydırınca iOS gibi esnekçe kayar, bırakınca yaylı geri döner (başka sayfa yok).
        val rubber = remember { androidx.compose.animation.core.Animatable(0f) }
        val rubberScope = androidx.compose.runtime.rememberCoroutineScope()
        val singlePage = pages.size <= 1

        // Sürüklenen uygulama panelden çıkıp kısa süre dışarıda kalırsa klasör kapanır (iOS davranışı).
        val outside by remember {
            derivedStateOf { drag.isDragging && panelRect != Rect.Zero && !panelRect.contains(drag.pos) }
        }
        val leftPanel by rememberUpdatedState(onDragLeftPanel)
        LaunchedEffect(outside) {
            if (outside) {
                delay(450)
                leftPanel()
            }
        }

        fun reorderAt(key: String, pos: Offset) {
            // Bırakılan noktadaki hücrenin yerine geçer; boş alana bırakılırsa sona gider.
            val hit = drag.targetsIn(DragLayer.Folder).firstOrNull { it.cellRect.contains(pos) }?.id
            val idx = hit?.let { folder.keys.indexOf(it) }?.takeIf { it >= 0 } ?: (folder.keys.size - 1)
            onReorder(key, idx)
        }

        // Panel ekranın TAM ortasında (üstten/alttan eşit); ad, panelin hemen üstüne yerleşir.
        Layout(content = {
        // Klasör adı panelin ÜSTÜNDE, iOS'taki gibi büyük ve kalın; dokununca düzenlenir.
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            singleLine = true,
            // Açık klasörün adı, klasörün dışındaki etiketle aynı stilde.
            textStyle = CellLabelStyle.copy(fontSize = (settings.openFolderTitleSize * u.value).sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            modifier = Modifier.width(panelW),
        )
        val clearGlass = LocalGlass.current.clear
        val panelCorner = (u * settings.folderCorner).coerceAtMost(panelW / 2f)
        Box {
        // Şeffaf cam: panelin arkasındaki ana ekran görüntüsü kenarda mercek gibi bükülür (yalnızca alt katmanda).
        if (clearGlass) BlurBackdrop(panelCorner, Modifier.matchParentSize(), rootOnly = true, shape = panelShape)
        Column(
            Modifier
                .width(panelW)
                .onGloballyPositioned { panelRect = it.boundsInRoot() }
                // Açık tonlu buzlu cam: ince beyaz dolgu + parlak ince kenar; gölge YOK (yarı saydam panelde
                // gölge içeriden koyu bir kare gibi görünüyordu).
                .lightGlass(panelShape, 0.75f, 0.35f)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = topPad, bottom = bottomPad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = drag.key == null,
                modifier = Modifier
                    .then(
                        if (!singlePage) Modifier
                        else Modifier.pointerInput(Unit) {
                            var raw = 0f
                            val limit = size.width.toFloat().coerceAtLeast(1f)
                            fun settle() = rubberScope.launch {
                                raw = 0f
                                rubber.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 360f))
                            }
                            detectHorizontalDragGestures(
                                onDragStart = { raw = rubber.value; rubberScope.launch { rubber.stop() } },
                                onDragEnd = { settle() },
                                onDragCancel = { settle() },
                            ) { change, dx ->
                                change.consume()
                                raw += dx
                                // iOS lastik formülü: çekildikçe direnç artar, en fazla panel genişliğinin ~yarısı.
                                val a = kotlin.math.abs(raw)
                                val band = (1f - 1f / (a * 0.55f / limit + 1f)) * limit
                                rubberScope.launch { rubber.snapTo(if (raw < 0f) -band else band) }
                            }
                        }
                    )
                    .graphicsLayer { translationX = rubber.value }
                    .fillMaxWidth()
                        .height(rowH * 3),
            ) { page ->
                val keys = pages.getOrNull(page).orEmpty()
                Column(Modifier.fillMaxSize()) {
                    keys.chunked(3).forEach { row ->
                        Row(
                            Modifier.height(rowH).padding(start = sidePad),
                            horizontalArrangement = Arrangement.spacedBy(pitch - icon),
                        ) {
                            row.forEach { k ->
                                val app = byKey[k]
                                if (app != null) {
                                    DraggableCell(
                                        id = k, place = CellPlace.Folder, label = app.label, showLabel = settings.showLabels,
                                        icon = icon, pitchX = pitch, cellHeight = rowH,
                                        drag = drag, layer = DragLayer.Folder, isApp = true,
                                        acceptsMerge = false, edit = edit, jiggle = jiggle,
                                        onClick = { r ->
                                            if (selection?.active == true) selection.toggle(k)
                                            else if (!edit) { vm.launchFrom(app, view, r); onClose() }
                                        },
                                        onDrop = { pos, hover ->
                                            val group = selection?.isGroupDrag(k) == true
                                            if (panelRect.contains(pos) && !group) reorderAt(k, pos)
                                            else if (!panelRect.contains(pos)) onDropOutside(k, pos, hover)
                                        },
                                        selected = if (selection?.active == true) k in selection.ids else null,
                                        hidden = selection?.isGroupDrag(drag.key) == true && k in selection.ids,
                                    ) { AppIconImage(app, vm, icon, settings.iconGlass, settings.glassStrength) }
                                }
                            }
                        }
                    }
                }
            }

            if (pages.size > 1) {
                Row(Modifier.padding(top = 2.dp)) {
                    repeat(pages.size) { i ->
                        Box(
                            Modifier.padding(3.dp).size(7.dp).clip(CircleShape)
                                .background(if (i == pagerState.currentPage) Color.White else Color(0x66FFFFFF))
                        )
                    }
                }
            }
        }
        } // Box (panel + arka mercek alanı)
        }, modifier = Modifier.fillMaxSize()) { ms, c ->
            val loose = c.copy(minWidth = 0, minHeight = 0)
            val title = ms[0].measure(loose)
            val panel = ms[1].measure(loose)
            val gap = (u * 22f).roundToPx()
            layout(c.maxWidth, c.maxHeight) {
                val px = (c.maxWidth - panel.width) / 2
                val py = (c.maxHeight - panel.height) / 2
                panel.place(px, py)
                title.place((c.maxWidth - title.width) / 2, (py - gap - title.height).coerceAtLeast(0))
            }
        }
    }
}
