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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.floor

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
    onStartEdit: () -> Unit,
    onRename: (String) -> Unit,
    onReorder: (String, Int) -> Unit,
    onMoveOut: (String) -> Unit,
    onClose: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

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
        val panelW = maxWidth * 0.88f
        val inner = panelW - 40.dp
        val pitch = inner / 3f
        val icon = pitch * 0.62f
        val rowH = icon + 28.dp
        val pitchPx = with(density) { pitch.toPx() }
        val rowHPx = with(density) { rowH.toPx() }

        val pages = folder.keys.chunked(9)
        val pagerState = rememberPagerState { maxOf(pages.size, 1) }
        var gridOrigin by remember { mutableStateOf(Offset.Zero) }

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
                    .height(rowH * 3)
                    .onGloballyPositioned { gridOrigin = it.positionInRoot() },
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
                                        drag = drag, acceptsMerge = false, edit = edit, jiggle = jiggle,
                                        onClick = { if (!edit) { vm.launch(app); onClose() } },
                                        onDragStart = onStartEdit,
                                        onDrop = { pos ->
                                            if (panelRect.contains(pos)) {
                                                val col = floor((pos.x - gridOrigin.x) / pitchPx).toInt().coerceIn(0, 2)
                                                val r = floor((pos.y - gridOrigin.y) / rowHPx).toInt().coerceIn(0, 2)
                                                val idx = (pagerState.currentPage * 9 + r * 3 + col)
                                                    .coerceAtMost(folder.keys.size - 1)
                                                onReorder(k, idx)
                                            } else {
                                                onMoveOut(k)
                                            }
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
