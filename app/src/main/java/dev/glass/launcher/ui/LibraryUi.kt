package dev.glass.launcher.ui

import androidx.compose.foundation.layout.imePadding

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.draw.drawWithContent

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
    /** Arşiv sayfası şu an ekranda mı (başka sayfaya geçilince arşiv ilk haline döner). */
    active: Boolean = true,
) {
    var searching by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    // Ekran en üstteyken aşağı çekilirse arama moduna geçer (arama çubuğuna dokunulmuş gibi).
    val pullDown = remember(gridState) {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f) acc = 0f
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                if (source == NestedScrollSource.UserInput && available.y > 0f && atTop) {
                    acc += available.y
                    if (acc > 120f) { searching = true; acc = 0f }
                }
                return Offset.Zero
            }
        }
    }
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

    // Liste açıkken telefonun geri hareketi listeyi kapatır.
    androidx.activity.compose.BackHandler(enabled = searching) { endSearch() }
    // Başka sayfaya geçilince (ana ekran vb.) arşiv ilk haline döner: liste kapanır, kategoriler en üstten.
    LaunchedEffect(active) {
        if (!active) {
            if (searching) endSearch()
            runCatching { gridState.scrollToItem(0) }
        }
    }

    val u = LocalPt.current
    Column(Modifier.fillMaxSize().padding(horizontal = u * 29f).nestedScroll(pullDown)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(LocalPt.current * 43f)
                    // Buzlu (bulanık) cam, gölgesiz: şeffaf cam stilinde arkada net/pikselli duvar kâğıdı görünüyordu.
                    .liquidGlass(CircleShape, strength, 0.dp, frosted = true)
                    .clickable(remember { MutableInteractionSource() }, null) { searching = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                // Aramada iOS gibi sola hizalı (yazı tek satır kalır); boştayken ortada.
                horizontalArrangement = if (searching) Arrangement.Start else Arrangement.Center,
            ) {
                SearchGlyph(Color(0xCCFFFFFF), LocalPt.current * 17f)
                Spacer(Modifier.width(8.dp))
                if (searching) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontFamily = AppFont, shadow = IosTextShadow),
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
                                if (query.isEmpty()) Text(
                                    "Uygulama Arşivi", color = Color(0x99FFFFFF), fontSize = 17.sp, fontFamily = AppFont,
                                    style = TextStyle(shadow = IosTextShadow), maxLines = 1, softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                inner()
                            }
                        },
                    )
                } else {
                    Text("Uygulama Arşivi", color = Color(0xB3FFFFFF), fontSize = 17.sp, fontFamily = AppFont, style = TextStyle(shadow = IosTextShadow), maxLines = 1, softWrap = false)
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
        Spacer(Modifier.height(u * 10f))

        if (searching) {
            LibrarySearchList(vm, allApps, query, settings) { app -> endSearch(); vm.launch(app) }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val gap = u * 24f
                val tile = (maxWidth - gap) / 2f
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(u * 12f),
                    // Son satır gezinme çubuğunun üstüne kadar kaydırılabilsin; üstte solma payı.
                    contentPadding = PaddingValues(top = u * 10f, bottom = 72.dp),
                    modifier = Modifier.fillMaxSize().edgeFade(top = u * 14f, bottom = 46.dp),
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
    val view = androidx.compose.ui.platform.LocalView.current
    val u = LocalPt.current
    val pad = u * 12.6f
    val gap = u * 12.8f
    val big = (tile - pad * 2f - gap) / 2f
    val bigApps = if (g.apps.size <= 4) g.apps.take(4) else g.apps.take(3)
    val rest = if (g.apps.size <= 4) emptyList() else g.apps.drop(3).take(4)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(tile)
                .lightGlass(appShape(u * settings.libraryTileCorner), 0.6f, 0.25f)
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
                                    onClick = { r -> vm.launchFrom(app, view, r) },
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
            g.title, color = Color.White, fontSize = (u.value * 13f).sp, fontWeight = FontWeight.Medium,
            fontFamily = AppFont, style = TextStyle(shadow = IosTextShadow),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = u * 4f).width(tile),
        )
    }
}

/** Kutunun dördüncü köşesi: 2×2 küçük simge, dokununca kategorinin tamamı açılır. */
@Composable
private fun MiniCluster(vm: LauncherViewModel, apps: List<AppInfo>, size: Dp, onOpen: () -> Unit) {
    val gap = size * 0.1f
    // Küçük simgeler, dördüncü hücrenin ortasında biraz daha küçük durur.
    val mini = ((size - gap) / 2f) * 0.8f
    Box(
        Modifier.size(size).clickable(remember { MutableInteractionSource() }, null, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        // Her zaman 2×2 sabit yuvalar: ikonlar sol üstten sırayla dizilir, eksikler boş kalır
        // (2-3 ikon olunca ortaya/aşağı kaymasın).
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            (0 until 2).forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    (0 until 2).forEach { c ->
                        val app = apps.getOrNull(r * 2 + c)
                        if (app != null) AppIconImage(app, vm, mini, false, 0f) else Spacer(Modifier.size(mini))
                    }
                }
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
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            // Klavyenin ÜSTÜNDE biter (arkasına uzanıp kenarı klavyenin üstünde kesik çizgi gibi görünmesin).
            .imePadding()
            .padding(bottom = 10.dp)
            .liquidGlass(RoundedCornerShape(24.dp), settings.glassStrength * 0.8f, 0.dp, frosted = true)
            .edgeFade(top = 10.dp, bottom = 22.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        sections.forEach { (letter, apps) ->
            if (letter.isNotEmpty()) {
                // Sade harf başlığı (iOS gibi): ayrı bir bant/çubuk yok.
                item(key = "h:$letter") {
                    Text(
                        letter, color = Color(0x99FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
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
    val view = androidx.compose.ui.platform.LocalView.current
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        val u = LocalPt.current
        val panelW = maxWidth * 0.9f
        val inner = panelW - u * 54f
        val pitch = inner / 4f
        // Simgeler ana ekranla aynı boyutta (sığmazsa küçülür).
        val icon = minOf(LocalHomeMetrics.current?.icon ?: (pitch * 0.72f), pitch * 0.92f)
        val rowH = icon + 26.dp
        val maxGridH = maxHeight * 0.6f
        val badgeRoom = minOf(12.dp, u * 29f)
        // Ad, ana ekrandaki klasörle aynı biçimde panelin ÜSTÜNDE; etiket stili dışarıdaki etiketle aynı.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            group.title,
            style = CellLabelStyle.copy(fontSize = (settings.openFolderTitleSize * u.value).sp),
            modifier = Modifier.width(panelW).padding(bottom = u * 14f),
        )
        Column(
            Modifier
                .width(panelW)
                .lightGlass(appShape(u * settings.libraryGroupCorner), 0.75f, 0.35f)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = u * 29f - badgeRoom, bottom = u * 15f, start = u * 27.5f, end = u * 27.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                // Izgara kendi sınırında kırpar: üst sıradaki bildirim rozeti taşabilsin diye üstte boşluk.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = badgeRoom),
                modifier = Modifier.fillMaxWidth().height(minOf(rowH * ((group.apps.size + 3) / 4) + badgeRoom, maxGridH)),
            ) {
                items(group.apps, key = { it.key }) { app ->
                    Box(Modifier.fillMaxWidth().height(rowH), contentAlignment = Alignment.TopCenter) {
                        DraggableCell(
                            id = "grp:${app.key}", itemId = app.key, place = CellPlace.Library,
                            label = app.label, showLabel = true, icon = icon, pitchX = pitch, cellHeight = rowH,
                            drag = drag, layer = DragLayer.Folder, isApp = true, acceptsMerge = false,
                            edit = false, jiggle = jiggle,
                            onClick = { r -> vm.launchFrom(app, view, r); onClose() },
                            onDrop = { p, h -> onDrop(app.key, p, h) },
                        ) { AppIconImage(app, vm, icon, settings.iconGlass, settings.glassStrength) }
                    }
                }
            }
        }
        }
    }
}

/** Kaydırılan içerik üst ve alt kenarda yumuşakça solar (iOS gibi; keskin kesik yerine). */
private fun Modifier.edgeFade(top: Dp, bottom: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val t = (top.toPx() / h).coerceIn(0f, 0.5f)
        val b = (bottom.toPx() / h).coerceIn(0f, 0.5f)
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Transparent, t to Color.Black, (1f - b) to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
