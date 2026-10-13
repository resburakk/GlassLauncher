package dev.glass.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.LauncherSettings
import dev.glass.launcher.data.SearchRanker
import java.util.Locale

/**
 * iOS App Library benzeri Uygulama Arşivi sayfası (ana ekranın son sayfasından sonra).
 * Üstte arama kutusu; altında 2 sütunlu kategori kutuları. Her kutuda ilk 3 uygulama büyük simge,
 * dördüncü köşede kalan uygulamaların küçük önizlemesi (dokununca kategori açılır).
 * Arama kutusuna dokununca klavye açılır ve alfabetik tam liste gösterilir.
 */
@Composable
fun LibraryPage(
    vm: LauncherViewModel,
    groups: List<LibraryGroup>,
    allApps: List<AppInfo>,
    settings: LauncherSettings,
    drag: DragState,
    edit: Boolean,
    jiggle: State<Float>,
    onDropFromLibrary: (String, androidx.compose.ui.geometry.Offset, String?) -> Unit,
    onOpenGroup: (LibraryGroup) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val strength = settings.glassStrength

    LaunchedEffect(searching) {
        if (searching) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    fun endSearch() {
        keyboard?.hide()
        query = ""
        searching = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .liquidGlass(CircleShape, strength, 4.dp)
                    .clickable(remember { MutableInteractionSource() }, null) { searching = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph(Color(0xCCFFFFFF), 16.dp)
                Spacer(Modifier.width(8.dp))
                if (searching) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            SearchRanker.rank(query, allApps) { it.label }.firstOrNull()?.let {
                                endSearch(); vm.launch(it)
                            }
                        }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("Uygulama Arşivi", color = Color(0x99FFFFFF), fontSize = 16.sp)
                                inner()
                            }
                        },
                    )
                } else {
                    Text("Uygulama Arşivi", color = Color(0xB3FFFFFF), fontSize = 16.sp)
                }
            }
            if (searching) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "İptal", color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.clickable { endSearch() }.padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (searching) {
            LibrarySearchList(vm, allApps, query, settings) { app -> endSearch(); vm.launch(app) }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val gap = 18.dp
                val tile = (maxWidth - gap) / 2f
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(groups, key = { it.title }) { g ->
                        CategoryTile(vm, g, tile, settings, drag, edit, jiggle, onDropFromLibrary) { onOpenGroup(g) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    vm: LauncherViewModel,
    g: LibraryGroup,
    tile: Dp,
    settings: LauncherSettings,
    drag: DragState,
    edit: Boolean,
    jiggle: State<Float>,
    onDrop: (String, androidx.compose.ui.geometry.Offset, String?) -> Unit,
    onOpen: () -> Unit,
) {
    val pad = tile * 0.09f
    val gap = tile * 0.08f
    val big = (tile - pad * 2f - gap) / 2f
    val bigApps = if (g.apps.size <= 4) g.apps.take(4) else g.apps.take(3)
    val rest = if (g.apps.size <= 4) emptyList() else g.apps.drop(3).take(4)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(tile)
                .liquidGlass(RoundedCornerShape(tile * 0.2f), settings.glassStrength * 0.8f, 4.dp)
                .padding(pad)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                (0 until 2).forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        (0 until 2).forEach { c ->
                            val i = r * 2 + c
                            val app = bigApps.getOrNull(i)
                            when {
                                app != null -> DraggableCell(
                                    id = "lib:${g.title}:${app.key}", itemId = app.key, place = CellPlace.Library,
                                    label = app.label, showLabel = false, icon = big, pitchX = big, cellHeight = big,
                                    drag = drag, layer = DragLayer.Home, isApp = true, acceptsMerge = false,
                                    edit = false, jiggle = jiggle,
                                    onClick = { vm.launch(app) },
                                    onDrop = { p, h -> onDrop(app.key, p, h) },
                                ) { AppIconImage(app, vm, big, settings.iconGlass, settings.glassStrength) }
                                i == 3 && rest.isNotEmpty() -> MiniCluster(vm, rest, big, onOpen)
                                else -> Spacer(Modifier.size(big))
                            }
                        }
                    }
                }
            }
        }
        Text(
            g.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).width(tile),
        )
    }
}

/** Kutunun dördüncü köşesi: 2×2 küçük simge, dokununca kategorinin tamamı açılır. */
@Composable
private fun MiniCluster(vm: LauncherViewModel, apps: List<AppInfo>, size: Dp, onOpen: () -> Unit) {
    val gap = size * 0.08f
    val mini = (size - gap) / 2f
    Column(
        Modifier.size(size).clickable(remember { MutableInteractionSource() }, null, onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        apps.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { AppIconImage(it, vm, mini, false, 0f) }
            }
        }
    }
}

/** Alfabetik liste (harf başlıklı) veya arama sonuçları. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibrarySearchList(
    vm: LauncherViewModel,
    allApps: List<AppInfo>,
    query: String,
    settings: LauncherSettings,
    onOpen: (AppInfo) -> Unit,
) {
    val tr = remember { Locale("tr", "TR") }
    val results = remember(query, allApps) {
        if (query.isBlank()) allApps else SearchRanker.rank(query, allApps) { it.label }
    }
    val sections = remember(results, query) {
        if (query.isNotBlank()) listOf("" to results)
        else results.groupBy { a ->
            val ch = a.label.trim().firstOrNull()?.uppercase(tr) ?: "#"
            if (ch.first().isLetter()) ch else "#"
        }.toList()
    }
    LazyColumn(
        Modifier.fillMaxWidth().fillMaxHeight().liquidGlass(RoundedCornerShape(24.dp), settings.glassStrength * 0.8f, 4.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        sections.forEach { (letter, apps) ->
            if (letter.isNotEmpty()) {
                stickyHeader(key = "h:$letter") {
                    Text(
                        letter, color = Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().background(Color(0x26000000))
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(apps, key = { "a:${it.key}" }) { app ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(app) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconImage(app, vm, 38.dp, settings.iconGlass, settings.glassStrength)
                    Spacer(Modifier.width(12.dp))
                    Text(app.label, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (results.isEmpty()) {
            item { Text("Sonuç bulunamadı", color = Color(0xCCFFFFFF), modifier = Modifier.padding(16.dp)) }
        }
    }
}

/** Bir kategorinin tamamı: ortada cam panel, 4 sütunlu ızgara (iOS'ta kategori kutusu açılınca olduğu gibi). */
@Composable
fun LibraryGroupOverlay(
    group: LibraryGroup,
    vm: LauncherViewModel,
    settings: LauncherSettings,
    drag: DragState,
    jiggle: State<Float>,
    onDrop: (String, androidx.compose.ui.geometry.Offset, String?) -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        val panelW = maxWidth * 0.9f
        val inner = panelW - 32.dp
        val pitch = inner / 4f
        val icon = pitch * 0.72f
        val rowH = icon + 26.dp
        val maxGridH = maxHeight * 0.6f
        Column(
            Modifier
                .width(panelW)
                .liquidGlass(RoundedCornerShape(36.dp), settings.glassStrength, 18.dp)
                .background(Color(0x40000000))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                group.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(minOf(rowH * ((group.apps.size + 3) / 4), maxGridH)),
            ) {
                items(group.apps, key = { it.key }) { app ->
                    Box(Modifier.fillMaxWidth().height(rowH), contentAlignment = Alignment.TopCenter) {
                        DraggableCell(
                            id = "grp:${app.key}", itemId = app.key, place = CellPlace.Library,
                            label = app.label, showLabel = true, icon = icon, pitchX = pitch, cellHeight = rowH,
                            drag = drag, layer = DragLayer.Folder, isApp = true, acceptsMerge = false,
                            edit = false, jiggle = jiggle,
                            onClick = { vm.launch(app); onClose() },
                            onDrop = { p, h -> onDrop(app.key, p, h) },
                        ) { AppIconImage(app, vm, icon, settings.iconGlass, settings.glassStrength) }
                    }
                }
            }
        }
    }
}
