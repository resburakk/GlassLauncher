package dev.glass.launcher.ui

import androidx.compose.ui.layout.layout

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

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
    /** Kategori açılır; ikinci değer kartın ekrandaki merkezi (açılış oradan büyür). */
    onOpenGroup: (LibraryGroup, Offset) -> Unit,
    /** Arşiv sayfası şu an ekranda mı (başka sayfaya geçilince arşiv ilk haline döner). */
    active: Boolean = true,
) {
    var searching by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    // Arşiv listesi kayarken de kart merceği kapalı (bkz. GlassMotion).
    LaunchedEffect(gridState.isScrollInProgress) { GlassMotion.scrolling = gridState.isScrollInProgress }
    MeasureFrames(gridState.isScrollInProgress, "Son kaydırma (Uygulama Arşivi)")
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
    val strength = settings.panelGlassStrength

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
    val side = u * 29f
    // Kenar boşluğu arama çubuğu ve kartlara ayrı verilir: arama listesi (iOS gibi) ekran kenarına kadar uzanır,
    // sağdaki harf dizini kenara yakın durur.
    Column(Modifier.fillMaxSize().nestedScroll(pullDown)) {
        Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(LocalPt.current * 43f)
                    // Buzlu (bulanık) cam, gölgesiz: şeffaf cam stilinde arkada net/pikselli duvar kâğıdı görünüyordu.
                    .liquidGlass(CircleShape, strength, 0.dp, overlay = true)
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
            LibrarySearchList(vm, allApps, query, settings, side) { app -> endSearch(); vm.launch(app) }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = side)) {
                val gap = u * 24f
                val tile = (maxWidth - gap) / 2f
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    // iOS 26: kart satırları arası ≈ 199 pt (kart 160 + etiket + boşluk).
                    verticalArrangement = Arrangement.spacedBy(u * 20f),
                    // Son satır gezinme çubuğunun üstüne kadar kaydırılabilsin; üstte solma payı.
                    contentPadding = PaddingValues(top = u * 10f, bottom = 72.dp),
                    modifier = Modifier.fillMaxSize().edgeFade(top = u * 14f, bottom = 46.dp),
                ) {
                    items(groups, key = { it.title }) { g ->
                        CategoryTile(vm, g, tile, settings, drag, edit, jiggle, onDropFromLibrary) { c -> onOpenGroup(g, c) }
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
    onOpen: (Offset) -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val u = LocalPt.current
    // iOS 26 ölçüleri (kart genişliğine oranla): iç boşluk %8,5, büyük simgeler arası %7,3.
    val pad = tile * 0.085f
    val gap = tile * 0.073f
    var center by remember { mutableStateOf(Offset.Zero) }
    // Köşe: kendi ayarı ya da (seçildiyse) dock'un köşesiyle aynı yarıçap; kartın yarısını geçmez.
    val dockR = LocalHomeMetrics.current?.dockCorner
    val tileCorner = (if (settings.libraryTileCornerDock && dockR != null) dockR else u * settings.libraryTileCorner).coerceAtMost(tile / 2f)
    val big = (tile - pad * 2f - gap) / 2f
    val bigApps = if (g.apps.size <= 4) g.apps.take(4) else g.apps.take(3)
    val rest = if (g.apps.size <= 4) emptyList() else g.apps.drop(3).take(4)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(tile)
                .onGloballyPositioned { center = it.boundsInRoot().center }
                // Dock ile aynı Liquid Glass (kırılma, parlama, kenar ışığı; tüm cam ayarları uygulanır).
                // Arkası bulanık arşiv zemini: şeffaf stilde de bulanık kopya kullanılır. Gölgesiz: yarı saydam
                // camın altından gölge koyu bir kare gibi görünüyordu.
                .liquidGlass(appShape(tileCorner), settings.panelGlassStrength, 0.dp, overlay = true)
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
                                i == 3 && rest.isNotEmpty() -> MiniCluster(vm, rest, big, "${g.title} kategorisini aç") { onOpen(center) }
                                else -> Spacer(Modifier.size(big))
                            }
                        }
                    }
                }
            }
        }
        Text(
            // iOS 26: kategori adı küçük (≈12 pt) ve orta kalınlıkta.
            g.title, color = Color.White, fontSize = (u.value * 12f).sp, fontWeight = FontWeight.Medium,
            fontFamily = AppFont, style = TextStyle(shadow = IosTextShadow),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = u * 5f).width(tile),
        )
    }
}

/** Kutunun dördüncü köşesi: 2×2 küçük simge, dokununca kategorinin tamamı açılır. */
@Composable
private fun MiniCluster(vm: LauncherViewModel, apps: List<AppInfo>, size: Dp, description: String, onOpen: () -> Unit) {
    val gap = size * 0.1f
    // Küçük simgeler, dördüncü hücrenin ortasında biraz daha küçük durur.
    val mini = ((size - gap) / 2f) * 0.8f
    Box(
        Modifier.size(size)
            .semantics { contentDescription = description }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onOpen),
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

/** Sağdaki harf dizini: iOS'taki gibi A–Z ve #. */
private val IndexLetters: List<String> = ('A'..'Z').map { it.toString() } + "#"

/** Türkçe harfler dizinde temel harfle eşleşir (Ç → C, Ş → S ...). Harf olmayanlar "#". */
private fun baseLetter(key: String): Char = when (val c = key.firstOrNull()) {
    null -> '#'
    'Ç' -> 'C'; 'Ğ' -> 'G'; 'İ', 'I' -> 'I'; 'Ö' -> 'O'; 'Ş' -> 'S'; 'Ü' -> 'U'
    else -> if (c in 'A'..'Z') c else '#'
}

/**
 * Alfabetik liste (harf başlıklı) veya arama sonuçları — iOS 26 gibi panelsiz, doğrudan bulanık arka planın
 * üstünde. Satırlar arasında yazı hizasından başlayan ince çizgi; sağda harf dizini (yalnızca tam listede).
 * Ölçüler (pt, iOS'tan): simge 43, satır 66, sol boşluk 27, simge–yazı arası 15,6, yazı 17.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibrarySearchList(
    vm: LauncherViewModel,
    allApps: List<AppInfo>,
    query: String,
    settings: LauncherSettings,
    side: Dp,
    onOpen: (AppInfo) -> Unit,
) {
    val u = LocalPt.current
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
    // Her harf başlığının listedeki sırası (dizinden atlamak için).
    val starts = remember(sections) {
        val m = LinkedHashMap<String, Int>()
        var i = 0
        sections.forEach { (letter, apps) ->
            if (letter.isNotEmpty()) { m[letter] = i; i++ }
            i += apps.size
        }
        m
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Arama metni değişince sonuçlar en üstten gösterilir.
    LaunchedEffect(query) { runCatching { listState.scrollToItem(0) } }

    val icon = u * 43f
    val rowH = u * 66f
    val textGap = u * 15.6f
    val showIndex = query.isBlank() && starts.size > 1
    val sepColor = Color(0x2EFFFFFF)

    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            // Klavyenin ÜSTÜNDE biter (arkasına uzanıp kenarı klavyenin üstünde kesik çizgi gibi görünmesin).
            .imePadding()
            .padding(bottom = 10.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().edgeFade(top = 10.dp, bottom = 22.dp),
            contentPadding = PaddingValues(start = u * 27f, end = if (showIndex) u * 28f else side, top = 4.dp, bottom = 8.dp),
        ) {
            sections.forEach { (letter, apps) ->
                if (letter.isNotEmpty()) {
                    // Sade harf başlığı (iOS gibi): ayrı bir bant/çubuk yok.
                    item(key = "h:$letter") {
                        Text(
                            letter, color = Color(0x99FFFFFF), fontSize = (u.value * 13f).sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = AppFont,
                            modifier = Modifier.fillMaxWidth().padding(start = u * 4f, top = u * 12f, bottom = u * 2f),
                        )
                    }
                }
                itemsIndexed(apps, key = { _, a -> "a:${a.key}" }) { i, app ->
                    val last = i == apps.size - 1
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(rowH)
                            .clickable { onOpen(app) }
                            .drawBehind {
                                // Ayırıcı: yazının hizasından satırın sonuna kadar ince çizgi (bölümün son satırında yok).
                                if (!last) {
                                    val x0 = (icon + textGap).toPx()
                                    val w = 0.5.dp.toPx().coerceAtLeast(1f)
                                    drawLine(sepColor, Offset(x0, size.height - w / 2f), Offset(size.width, size.height - w / 2f), w)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconImage(app, vm, icon, settings.iconGlass, settings.glassStrength)
                        Spacer(Modifier.width(textGap))
                        Text(
                            app.label, color = Color.White, fontSize = (u.value * 17f).sp, fontFamily = AppFont,
                            style = TextStyle(shadow = IosTextShadow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (results.isEmpty()) {
                item {
                    Text(
                        "Sonuç bulunamadı", color = Color(0xCCFFFFFF), fontSize = (u.value * 17f).sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
        if (showIndex) {
            AlphabetIndex(Modifier.align(Alignment.CenterEnd).padding(end = u * 3f)) { picked ->
                // Seçilen harf ya da (o harfle başlayan uygulama yoksa) ondan sonraki ilk harf.
                val keys = starts.keys.toList()
                val target = if (picked == '#') keys.firstOrNull { baseLetter(it) == '#' } ?: keys.last()
                else keys.firstOrNull { val b = baseLetter(it); b != '#' && b >= picked }
                    ?: keys.lastOrNull { baseLetter(it) != '#' } ?: keys.last()
                val idx = starts[target] ?: return@AlphabetIndex
                scope.launch { runCatching { listState.scrollToItem(idx) } }
            }
        }
    }
}

/** Dikey A–Z dizini: dokunulan ya da parmakla üzerinden geçilen harfe atlar (her harf değişiminde hafif titreşim).
 *  Parmak dizindeyken solunda, o anki harfi gösteren küçük bir cam baloncuk belirir ve harfle birlikte kayar. */
@Composable
private fun AlphabetIndex(modifier: Modifier, onPick: (Char) -> Unit) {
    val u = LocalPt.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val pick by rememberUpdatedState(onPick)
    val n = IndexLetters.size
    // Parmağın üstündeki harf (-1: dokunulmuyor) ve baloncuğun görünürlüğü.
    var active by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var shown by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        // iOS: harf başına ≈ 12,4 pt; yer yetmezse (klavye açıkken) sıkışır.
        val cell = minOf(u * 12.4f, maxHeight * 0.96f / n)
        val font = with(density) { minOf(u * 11f, cell * 0.86f).toSp() }
        val colTop = (maxHeight - cell * n.toFloat()) / 2f
        if (active >= 0 && shown != active) shown = active
        val vis by androidx.compose.animation.core.animateFloatAsState(
            if (active >= 0) 1f else 0f,
            if (active >= 0) androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 700f)
            else androidx.compose.animation.core.tween(160),
            label = "idxBubble",
        )
        val bubbleY by androidx.compose.animation.core.animateFloatAsState(
            with(density) { (colTop + cell * (shown + 0.5f)).toPx() },
            androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 900f),
            label = "idxBubbleY",
        )
        if (vis > 0.01f) {
            val d = u * 52f
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    // Boyutsuz yerleşim: baloncuk dizinin genişliğini değiştirmez, solunda durur.
                    .layout { m, _ ->
                        val p = m.measure(androidx.compose.ui.unit.Constraints())
                        layout(0, 0) {
                            p.place(-(p.width + (u * 14f).roundToPx()), (bubbleY - p.height / 2f).toInt())
                        }
                    }
                    .graphicsLayer {
                        alpha = vis
                        val sc = 0.6f + 0.4f * vis
                        scaleX = sc; scaleY = sc
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    }
                    .size(d)
                    .liquidGlass(CircleShape, LocalLauncherSettings.current.panelGlassStrength, 0.dp, overlay = true),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    IndexLetters[shown.coerceIn(0, n - 1)], color = Color.White, fontSize = (u.value * 26f).sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1,
                )
            }
        }
        Column(
            Modifier
                .width(u * 18f)
                .height(cell * n.toFloat())
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var last = -1
                        fun at(y: Float) {
                            val i = (y / size.height * n).toInt().coerceIn(0, n - 1)
                            if (i != last) {
                                last = i
                                active = i
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                pick(IndexLetters[i].first())
                            }
                        }
                        at(down.position.y)
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                at(c.position.y)
                                c.consume()
                            }
                        } finally {
                            active = -1
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IndexLetters.forEachIndexed { li, l ->
                Box(Modifier.height(cell), contentAlignment = Alignment.Center) {
                    Text(l, color = if (li == active) Color.White else Color(0xE6FFFFFF), fontSize = font, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Bir kategorinin tamamı (iOS 26): panelsiz, tüm ekran. Bulanık arka planın üstünde sola yaslı büyük kalın
 * başlık, altında ana ekran boyutunda simgelerle 4 sütunlu kayan ızgara. Boş yere dokununca kapanır.
 * Ölçüler (pt, iOS'tan): kenar 29, satır 100,6, başlık 34 kalın (alt çizgisi üstten ≈ 217), başlık–ilk satır 44.
 */
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
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }) {
        val u = LocalPt.current
        val side = u * 29f
        // Simgeler ana ekranla aynı boyutta (4 sütuna sığmazsa küçülür).
        val icon = minOf(LocalHomeMetrics.current?.icon ?: (u * 64f), (maxWidth - side * 2f) / 4.6f)
        // iOS'ta ilk ve son sütundaki simgelerin dış kenarı kenar boşluğuna hizalı: sütunlar buna göre yayılır.
        val pitch = (maxWidth - side * 2f - icon) / 3f
        val gridSide = side + icon / 2f - pitch / 2f
        val rowH = maxOf(u * 100.6f, icon + u * 34f)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // iOS gibi esnek: başta/sonda çekmeye devam edince öğeler direnç artarak kayar (ızgaranın kendisi değil).
        val rb = rememberRubberBand()
        rb.extent = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = gridSide, end = gridSide, bottom = navBottom + u * 24f),
            modifier = Modifier.fillMaxSize().nestedScroll(rb.connection),
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    group.title, color = Color.White, fontSize = (u.value * 34f).sp, fontWeight = FontWeight.Bold,
                    fontFamily = AppFont, style = TextStyle(shadow = IosTextShadow),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .rubberY(rb)
                        .padding(start = side - gridSide, end = side - gridSide, top = u * 176f, bottom = u * 38f),
                )
            }
            items(group.apps, key = { it.key }) { app ->
                Box(Modifier.fillMaxWidth().height(rowH).rubberY(rb), contentAlignment = Alignment.TopCenter) {
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
