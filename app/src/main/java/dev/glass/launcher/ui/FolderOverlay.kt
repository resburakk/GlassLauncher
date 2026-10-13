package dev.glass.launcher.ui

import androidx.compose.foundation.background
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
) {
    val keyboard = LocalSoftwareKeyboardController.current

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
        val panelW = maxWidth * settings.folderSize
        val inner = panelW - 40.dp
        val pitch = inner / 3f
        val icon = pitch * 0.66f
        val rowH = icon + 26.dp
        val pages = folder.keys.chunked(9)
        val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, maxOf(pages.size - 1, 0))) {
            maxOf(pages.size, 1)
        }
        val pageChanged by rememberUpdatedState(onPageChanged)
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { pageChanged(it) }
        }

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

        Column(
            Modifier
                .width(panelW)
                .onGloballyPositioned { panelRect = it.boundsInRoot() }
                .liquidGlass(RoundedCornerShape(38.dp), settings.glassStrength, 18.dp)
                .background(Color(0x40000000))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = drag.key == null,
                modifier = Modifier
                    .fillMaxWidth()
                        .height(rowH * 3),
            ) { page ->
                val keys = pages.getOrNull(page).orEmpty()
                Column(Modifier.fillMaxSize()) {
                    keys.chunked(3).forEach { row ->
                        Row(
                            Modifier.height(rowH).padding(start = (pitch - icon) / 2f),
                            horizontalArrangement = Arrangement.spacedBy(pitch - icon),
                        ) {
                            row.forEach { k ->
                                val app = byKey[k]
                                if (app != null) {
                                    DraggableCell(
                                        id = k, label = app.label, showLabel = settings.showLabels,
                                        icon = icon, pitchX = pitch, cellHeight = rowH,
                                        drag = drag, layer = DragLayer.Folder, isApp = true,
                                        acceptsMerge = false, edit = edit, jiggle = jiggle,
                                        onClick = { if (!edit) { vm.launch(app); onClose() } },
                                        onDrop = { pos, hover ->
                                            if (panelRect.contains(pos)) reorderAt(k, pos)
                                            else onDropOutside(k, pos, hover)
                                        },
                                    ) { AppIconImage(app, vm, icon, settings.iconGlass, settings.glassStrength) }
                                }
                            }
                        }
                    }
                }
            }

            if (pages.size > 1) {
                Row(Modifier.padding(top = 10.dp)) {
                    repeat(pages.size) { i ->
                        Box(
                            Modifier.padding(3.dp).size(7.dp).clip(CircleShape)
                                .background(if (i == pagerState.currentPage) Color.White else Color(0x66FFFFFF))
                        )
                    }
                }
            }
        }
    }
}
