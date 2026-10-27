package dev.glass.launcher.ui

import androidx.compose.runtime.DisposableEffect

import androidx.compose.ui.draw.drawBehind

import androidx.compose.ui.input.nestedscroll.nestedScroll

import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.runtime.collectAsState

import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.layout.BoxScope

import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import android.app.ActivityOptions
import dev.glass.launcher.data.HomeItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.glass.launcher.R
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.AppItem
import dev.glass.launcher.data.FolderItem
import dev.glass.launcher.data.GridPacker
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import dev.glass.launcher.data.PageBreak
import dev.glass.launcher.data.HomeLayout
import dev.glass.launcher.data.LauncherSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Izgara hücresi; page = -1 dock'u, col o durumda dock yuvasını gösterir. */
private data class Cell(val page: Int, val row: Int, val col: Int)

/**
 * Ana ekran içeriği: sayfalı ızgara + en sonda Uygulama Arşivi sayfası, sayfa noktaları/arama hapı ve dock.
 * Arşive geçerken dock ve arama hapı aşağı kayarak kaybolur (iOS gibi).
 */
@Composable
internal fun HomeContent(
    vm: LauncherViewModel,
    layout: HomeLayout,
    byKey: Map<String, AppInfo>,
    allApps: List<AppInfo>,
    groups: List<LibraryGroup>,
    settings: LauncherSettings,
    drag: DragState,
    bridge: DropBridge,
    folderPages: Map<String, Int>,
    edit: Boolean,
    jiggle: State<Float>,
    isDefaultHome: Boolean,
    onRequestDefault: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFolder: (FolderItem, Offset) -> Unit,
    onOpenGroup: (LibraryGroup, Offset) -> Unit,
    /** Çoklu seçim durumu (düzenleme modunda "Seç"). */
    selection: SelectionState,
    /** Arşive geçiş oranı (0 = ana ekran, 1 = arşiv); arka plan bulanıklığı için üst bileşen okur. */
    libProgress: MutableFloatState,
    /** Widget sayfası (ilk sayfanın solunda); null ise kapalı. */
    today: TodayState? = null,
    /** Widget sayfasına şu an kaydırılabilir mi (düzenleme/sürükleme yokken). */
    todayAllowed: () -> Boolean = { true },
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Tam ekran: konumlar iOS'taki gibi ekranın sol-üst köşesinden ölçülür (durum çubuğu dahil).
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val m = HomeMetrics.compute(maxWidth, maxHeight, statusTop, settings, navButtonsDp())
        val firstTopPx = with(density) { m.firstTop.toPx() }
        val motion = LocalMotion.current
        val view = LocalView.current
        val items = layout.items
        // Uygulamalar 1×1, widget'lar N×M hücre: sayfalara yerleştir.
        // Görünmeyen öğeler (kaldırılmış/erişilemeyen uygulama) sayfa tutmaz: boş sayfalar otomatik silinir.
        // Görünmez boşluklar (GapItem) sayfayı "dolu" saymaz: yalnızca boşluk kalan sayfa silinir.
        val visible: (HomeItem) -> Boolean = { it !is dev.glass.launcher.data.GapItem && (it !is AppItem || byKey.containsKey(it.key)) }
        val placements = remember(items, m.columns, m.rows, byKey) { GridPacker.pack(items, m.columns, m.rows, visible) }
        val pageTotal = if (items.isEmpty()) 1 else GridPacker.pageCount(placements)
        // Yalnızca parmak gerçekten kaydırılınca sürükleme sayılır: uzun basıp menü açmak (ör. arşivde) sona boş
        // sayfa ekleyip arşivi bir sayfa kaydırmasın.
        val dragging = drag.key != null && drag.moved
        // Sürüklerken sonda her zaman yeni boş bir sayfa görünür (iOS gibi); oraya bırakılan öğe yeni sayfaya geçer.
        val homeCount = pageTotal + if (dragging) 1 else 0
        val libIndex = homeCount
        val pagerState = rememberPagerState { homeCount + if (settings.showLibrary) 1 else 0 }

        var gridOrigin by remember { mutableStateOf(Offset.Zero) }
        var dockRect by remember { mutableStateOf(Rect.Zero) }

        // --- Canlı yer değiştirme (iOS gibi): sürüklerken diğer simgeler kayarak yer açar ---
        var preview by remember { mutableStateOf<List<HomeItem>?>(null) }
        var dockPreview by remember { mutableStateOf<List<String>?>(null) }
        val targetCell by remember(m, libIndex) {
            derivedStateOf {
                val k = drag.key
                when {
                    k == null || drag.hoverId != null -> null
                    pagerState.currentPage >= libIndex -> null
                    dockRect.height > 0f && drag.pos.y >= dockRect.top - with(density) { 12.dp.toPx() } -> {
                        val dockPitchPx = with(density) { m.dockPitch.toPx() }
                        val dockIconPx = with(density) { m.dockIcon.toPx() }
                        val first = dockRect.left + with(density) { m.dockInnerStart.toPx() }
                        val slot = floor((drag.pos.x - first + (dockPitchPx - dockIconPx) / 2f) / dockPitchPx).toInt().coerceIn(0, 3)
                        Cell(-1, 0, slot)
                    }
                    else -> {
                        val iconPx = with(density) { m.icon.toPx() }
                        val pitchXPx = with(density) { m.pitchX.toPx() }
                        val pitchYPx = with(density) { m.pitchY.toPx() }
                        val marginPx = with(density) { m.marginX.toPx() }
                        val col = floor((drag.pos.x - gridOrigin.x - marginPx + (pitchXPx - iconPx) / 2f) / pitchXPx)
                            .toInt().coerceIn(0, m.columns - 1)
                        val row = floor((drag.pos.y - gridOrigin.y - firstTopPx) / pitchYPx).toInt().coerceIn(0, m.rows - 1)
                        Cell(pagerState.currentPage, row, col)
                    }
                }
            }
        }
        val dragKey = drag.key
        LaunchedEffect(targetCell, dragKey) {
            if (dragKey == null) {
                preview = null
                dockPreview = null
                return@LaunchedEffect
            }
            val cell = targetCell ?: return@LaunchedEffect // klasör hedefi üzerinde: düzen sabit kalır
            // Kısa bekleme: simge merkezine (klasör hedefine) yönelen parmak düzeni hemen bozmasın.
            delay((260 / motion.speed).toLong())
            val cur = vm.layout.value
            if (selection.isGroupDrag(dragKey)) {
                // Toplu sürükleme: seçilenler birlikte yer açar (dock'a konmaz).
                val ids = selection.ids.toSet()
                val rest = cur.items.filter { it.id !in ids }
                if (cell.page < 0) { preview = rest; dockPreview = null; return@LaunchedEffect }
                dockPreview = null
                if (cell.page >= GridPacker.pageCount(GridPacker.pack(rest, m.columns, m.rows, visible))) {
                    preview = rest + PageBreak("onizleme-yeni-sayfa") + cur.items.filter { it.id in ids }
                    return@LaunchedEffect
                }
                val gi = GridPacker.insertionIndex(GridPacker.pack(rest, m.columns, m.rows, visible), cell.page, cell.row, cell.col, m.columns)
                preview = rest.toMutableList().apply { addAll(gi.coerceIn(0, size), cur.items.filter { it.id in ids }) }
                return@LaunchedEffect
            }
            val without = cur.items.filter { it.id != dragKey }
            if (cell.page < 0) {
                // Dock üzerinde: ızgaradaki boşluk kapanır; dock'ta yer varsa simgeler kayar.
                preview = without
                val isApp = byKey.containsKey(dragKey)
                dockPreview = if (isApp && (dragKey in cur.dock || cur.dock.size < 4)) {
                    (cur.dock - dragKey).toMutableList().apply { add(cell.col.coerceIn(0, size), dragKey) }
                } else null
                return@LaunchedEffect
            }
            dockPreview = cur.dock - dragKey
            val idx = GridPacker.insertionIndex(GridPacker.pack(without, m.columns, m.rows, visible), cell.page, cell.row, cell.col, m.columns)
            val dragged = cur.items.firstOrNull { it.id == dragKey }
                ?: if (byKey.containsKey(dragKey)) AppItem(dragKey) else return@LaunchedEffect
            // Sondaki boş sayfanın üzerinde: öğe yeni sayfada gösterilir.
            if (cell.page >= GridPacker.pageCount(GridPacker.pack(without, m.columns, m.rows, visible))) {
                preview = without + PageBreak("onizleme-yeni-sayfa") + dragged
                return@LaunchedEffect
            }
            var gapN = 0
            preview = GridPacker.withGaps(
                without.toMutableList().apply { add(idx.coerceIn(0, size), dragged) },
                dragged.id, cell.page, cell.row, cell.col, m.columns, m.rows, visible,
            ) { dev.glass.launcher.data.GapItem("onizleme-bosluk-${gapN++}") }
        }
        val shownItems = preview ?: items
        val shownPlacements = remember(shownItems, m.columns, m.rows, byKey) { GridPacker.pack(shownItems, m.columns, m.rows, visible) }
        val byPage = remember(shownPlacements) { shownPlacements.groupBy { it.page } }
        val shownDock = dockPreview ?: layout.dock

        // Kaydırma bitince camlar arkalarını yeniden kendileri çizer (kayarken bunu sayfanın altındaki katman yapar).
        // Bu devir teslim sayfayı baştan çizdirir; kaydırmanın son karesine denk gelince sayfa son pikselleri atlayıp
        // "zınk" diye oturuyordu. Kayma modu, sayfa tamamen durduktan kısa süre sonra (görünmeden) bırakılır.
        var scrollHold by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.isScrollInProgress) {
            if (pagerState.isScrollInProgress) scrollHold = true else { delay(320); scrollHold = false }
        }
        // Kayarken katman camlarının merceği kapalı (bkz. GlassMotion).
        LaunchedEffect(scrollHold) { GlassMotion.scrolling = scrollHold }
        // Kaydırma sürerken (parmak kalktıktan sonraki yerine oturma dahil) yüksek kare hızı istenir (Android 15+).
        LaunchedEffect(pagerState.isScrollInProgress) {
            if (android.os.Build.VERSION.SDK_INT >= 35) runCatching {
                view.requestedFrameRate = if (pagerState.isScrollInProgress) android.view.View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                else android.view.View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
        // Performans ölçümü: her sayfa kaydırmasında gerçek kare aralıkları (Ayarlar > Performans).
        LaunchedEffect(pagerState.isScrollInProgress) {
            if (!pagerState.isScrollInProgress) return@LaunchedEffect
            val refresh = view.display?.refreshRate?.takeIf { it > 1f } ?: 60f
            FrameStats.begin()
            val list = ArrayList<Long>()
            var last = 0L
            try {
                while (list.size < 600) {
                    withFrameNanos { t ->
                        if (last != 0L) list += t - last
                        last = t
                    }
                }
            } finally {
                // Kaydırma bitince (etki iptal edilir) ölçüm raporlanır.
                FrameStats.report(list.toLongArray(), refresh)
            }
        }

        // Otomatik kalite: kaydırma sırasında kare sürelerini örnekle (ek kare istemez; zaten çizilen kareler).
        LaunchedEffect(pagerState.isScrollInProgress, settings.autoQuality) {
            if (!settings.autoQuality || !pagerState.isScrollInProgress) return@LaunchedEffect
            val refresh = view.display?.refreshRate?.takeIf { it > 1f } ?: 60f
            // Ölçüt 60 Hz'e göre: 120 Hz ekranda 8 ms'lik bütçe çok sıkı; küçük takılmalar camı sadeleştirmesin.
            val budget = (1_000_000_000L / minOf(refresh, 60f)).toLong()
            var last = 0L
            var n = 0
            var slow = 0
            while (n < 40) {
                withFrameNanos { t ->
                    if (last != 0L) {
                        n++
                        if (t - last > budget * 18 / 10) slow++
                    }
                    last = t
                }
            }
            if (slow > n / 3) vm.reportSlowFrames()
        }

        /** Uygulamayı, simgesinden büyüyen sistem animasyonuyla açar. */
        fun launchFrom(app: AppInfo, r: Rect) {
            vm.launchFrom(app, view, r)
        }

        // 0 = ana ekran, 1 = tamamen Uygulama Arşivi
        val libFraction by remember(libIndex) {
            derivedStateOf {
                (pagerState.currentPage + pagerState.currentPageOffsetFraction - (libIndex - 1)).coerceIn(0f, 1f)
            }
        }

        LaunchedEffect(libIndex) {
            snapshotFlow { libFraction }.collect { libProgress.floatValue = it }
        }

        // Sayfa kaydırılırken noktalar, durunca arama hapı görünür (iOS 16+ davranışı).
        var showDots by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.isScrollInProgress) {
            if (pagerState.isScrollInProgress) showDots = true else { delay(900); showDots = false }
        }
        val dotsAlpha by animateFloatAsState(if (showDots) 1f else 0f, motion.tween(220), label = "dots")
        // Düzenleme modunda "Ara" düğmesi kaybolur; yerine "Seç" / "Bu Sayfayı Sil" gelir (HomeScreen).
        val pillShown by animateFloatAsState(if (edit) 0f else 1f, motion.tween(160), label = "pillEdit")

        // Sürüklerken ekran kenarında bekleyince sayfa değişir (arşive geçmez).
        val widthPx = with(density) { maxWidth.toPx() }
        val edgePx = with(density) { 36.dp.toPx() }
        val edge by remember(widthPx, edgePx) {
            derivedStateOf {
                when {
                    drag.key == null -> 0
                    drag.pos.x < edgePx -> -1
                    drag.pos.x > widthPx - edgePx -> 1
                    else -> 0
                }
            }
        }
        LaunchedEffect(edge, libIndex) {
            if (edge != 0) {
                while (true) {
                    delay(650)
                    val target = pagerState.currentPage + edge
                    if (target in 0 until libIndex) pagerState.animateScrollToPage(target)
                }
            }
        }

        val libIndexNow by rememberUpdatedState(libIndex)
        fun handleDrop(id: String, pos: Offset, hover: String?) {
            // Güncel değer okunur: hücrenin elindeki bırakma işlevi sürükleme başlamadan önceki halinden kalmış
            // olabilir (o anda sondaki yeni sayfa yoktu ve yeni sayfaya bırakma "arşive bırakma" sanılıyordu).
            if (pagerState.currentPage >= libIndexNow) return // arşiv sayfasına bırakma: değişiklik yok
            val cur = vm.layout.value
            if (selection.isGroupDrag(id)) {
                val ids = selection.ids.toList()
                // Dock'a toplu bırakma yok.
                if (dockRect.height > 0f && pos.y >= dockRect.top - with(density) { 12.dp.toPx() }) return
                // Seçili olmayan bir simge/klasörün üzerine: hepsi o klasöre ya da yeni klasöre.
                if (hover != null && hover !in ids) {
                    val target = cur.items.firstOrNull { it.id == hover }
                    when (target) {
                        is FolderItem -> vm.editLayout { it.moveIntoFolder(hover, ids) }
                        is AppItem -> vm.editLayout { l -> l.createFolderFrom(ids + hover, vm.folderNameFor(l.appKeysOf(ids + hover)), hover) }
                        else -> return
                    }
                    selection.ids.clear()
                    return
                }
                val iconPxG = with(density) { m.icon.toPx() }
                val pxG = with(density) { m.pitchX.toPx() }
                val pyG = with(density) { m.pitchY.toPx() }
                val mxG = with(density) { m.marginX.toPx() }
                val colG = floor((pos.x - gridOrigin.x - mxG + (pxG - iconPxG) / 2f) / pxG).toInt().coerceIn(0, m.columns - 1)
                val rowG = floor((pos.y - gridOrigin.y - firstTopPx) / pyG).toInt().coerceIn(0, m.rows - 1)
                val rest = cur.items.filter { it.id !in ids.toSet() }
                if (pagerState.currentPage >= GridPacker.pageCount(GridPacker.pack(rest, m.columns, m.rows, visible))) {
                    vm.editLayout { it.moveGroupToNewPage(ids) }
                    return
                }
                val gi = GridPacker.insertionIndex(GridPacker.pack(rest, m.columns, m.rows, visible), pagerState.currentPage, rowG, colG, m.columns)
                vm.editLayout { it.moveGroup(ids, gi) }
                return
            }
            val isApp = byKey.containsKey(id)
            val topLevel = cur.items.any { it.id == id }

            // 1) Dock bölgesi
            if (dockRect.height > 0f && pos.y >= dockRect.top - with(density) { 12.dp.toPx() }) {
                if (!isApp) return // klasörler dock'a konmaz
                val dockPitchPx = with(density) { m.dockPitch.toPx() }
                val dockIconPx = with(density) { m.dockIcon.toPx() }
                val first = dockRect.left + with(density) { m.dockInnerStart.toPx() }
                val slot = floor((pos.x - first + (dockPitchPx - dockIconPx) / 2f) / dockPitchPx)
                    .toInt().coerceIn(0, 3)
                vm.editLayout { it.moveToDock(id, slot) }
                return
            }

            // 2) Bir simgenin üzerine bırakıldı: klasör oluştur / klasöre ekle
            if (hover != null && isApp) {
                vm.editLayout { it.mergeInto(hover, id) }
                return
            }

            // 3) Ana ekran ızgarasında bir hücreye (widget'lar hesaba katılarak)
            val iconPx = with(density) { m.icon.toPx() }
            val pitchXPx = with(density) { m.pitchX.toPx() }
            val pitchYPx = with(density) { m.pitchY.toPx() }
            val marginPx = with(density) { m.marginX.toPx() }
            val x = pos.x - gridOrigin.x
            val y = pos.y - gridOrigin.y - firstTopPx
            val col = floor((x - marginPx + (pitchXPx - iconPx) / 2f) / pitchXPx).toInt().coerceIn(0, m.columns - 1)
            val row = floor(y / pitchYPx).toInt().coerceIn(0, m.rows - 1)
            val without = cur.items.filter { it.id != id }
            // Sondaki yeni (boş) sayfaya bırakıldı: öğe yeni sayfaya geçer.
            if (pagerState.currentPage >= GridPacker.pageCount(GridPacker.pack(without, m.columns, m.rows, visible))) {
                vm.editLayout { it.moveToNewPage(id) }
                return
            }
            val idx = GridPacker.insertionIndex(
                GridPacker.pack(without, m.columns, m.rows, visible), pagerState.currentPage, row, col, m.columns,
            )
            val page = pagerState.currentPage
            // Boş bir hücreye bırakıldıysa öğe tam oraya yerleşir (önüne görünmez boşluklar eklenir, iOS 18+ gibi).
            if (topLevel) vm.editLayout { it.moveItem(id, idx).placeAt(id, page, row, col, m.columns, m.rows, visible) }
            else vm.editLayout { it.moveAppToGrid(id, idx).placeAt(id, page, row, col, m.columns, m.rows, visible) } // dock, klasör veya arşivden
        }

        SideEffect {
            bridge.home = ::handleDrop
            bridge.goToPage = { p -> scope.launch { pagerState.animateScrollToPage(p.coerceIn(0, pagerState.pageCount - 1)) } }
            bridge.goToLastHomePage = { scope.launch { pagerState.animateScrollToPage(maxOf(pageTotal - 1, 0)) } }
            bridge.onLibrary = pagerState.currentPage == libIndex
            bridge.homePageCount = pageTotal
            bridge.currentPageIds = placements.filter { it.page == pagerState.currentPage }.map { it.item.id }
            vm.currentPageStart = placements.indexOfFirst { it.page == pagerState.currentPage }
                .let { if (it < 0) items.size else it }
        }

        // İlk sayfada sağa kaydırma: pager'ın kullanamadığı kaydırma widget sayfasını açar (iOS Bugün görünümü);
        // açıkken ters yöne kaydırma önce onu kapatır. Bırakınca hıza göre açılır/kapanır.
        val todayAllowedNow by rememberUpdatedState(todayAllowed)
        val todayConn = remember(today, pagerState) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                    val t = today ?: return Offset.Zero
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return Offset.Zero
                    if (t.progress > 0f && available.x < 0f) {
                        val before = t.progress
                        t.dragBy(available.x)
                        return Offset((t.progress - before) * t.widthPx, 0f)
                    }
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                    val t = today ?: return Offset.Zero
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return Offset.Zero
                    if (available.x > 0f && pagerState.currentPage == 0 && todayAllowedNow()) {
                        val before = t.progress
                        t.dragBy(available.x)
                        return Offset((t.progress - before) * t.widthPx, 0f)
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    val t = today ?: return androidx.compose.ui.unit.Velocity.Zero
                    if (t.progress > 0f) {
                        t.settle(scope, available.x)
                        return available
                    }
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }
        // Kaydırma sürerken her karede tüm ekran "değişti" sayılır: sayfalar önbellek katmanından taşınırken telefonun
        // kısmi yeniden çizimi ekranın bir kısmını eski karede bırakıyordu (ortadaki sütunlar donuyordu). Bu kutu
        // görünmezdir; yalnızca her karede tüm ekranı yeniletir (sayfaların kendisi yeniden çizilmez).
        var fullDamageTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        LaunchedEffect(scrollHold) {
            LiveMotion.scrolling = scrollHold
            if (scrollHold) while (true) withFrameNanos { fullDamageTick++ }
        }
        Box(Modifier.fillMaxSize().drawBehind { if (fullDamageTick >= 0) drawRect(Color.Transparent) })
        // Sayfa içi camların arkası (klasör simgeleri, widget'lar): yalnızca sayfalar kayarken, sayfaların ALTINDA
        // her karede o anki kaymaya göre çizilir. Sayfalar hazır görüntü olarak taşınırken camların içi donuk kalmaz.
        val pageAnchors = remember { androidx.compose.runtime.mutableStateMapOf<Int, PageAnchor>() }
        // Widget sayfası geçişi: ana ekran tek parça kayarken sayfalardaki camların arkası, sayfaların DIŞINDAKİ alt
        // katmanda (HomeScreen) gerçek ekran konumuna göre çizilir; yoksa camın içi geçiş öncesi yerde kalıyordu.
        val outerAnchor = LocalPageAnchor.current
        val todayMoving = { today?.moving == true }
        androidx.compose.runtime.SideEffect {
            outerAnchor?.drawExtra = { sx ->
                if (!edit && !dragging) {
                    val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    pageAnchors.forEach { (pg, a) -> drawPageGlassHoles(a, (pg - pos) * size.width + sx, gridOrigin) }
                }
            }
        }
        val stillJiggle = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize().drawBehind {
            // Arşiv yukarı/aşağı kayarken de kart camlarının arkası burada çizilir (kartlar kendi arkalarını çizmez).
            if (!(scrollHold || GlassMotion.listHoles) || edit || dragging) return@drawBehind
            // Widget sayfası geçişi de sayfa kaydırıcısından başladığı için "kayıyor" sayılıyordu: bu katman ana ekranla
            // birlikte kayıp camları geçiş öncesi yerden çiziyor, doğru yerden çizilen dış katmanın üstünü örtüyordu
            // (widget'ların içi donuk görünüyordu). Geçişte camları yalnızca dış katman (HomeScreen) çizer.
            if (todayMoving()) return@drawBehind
            val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
            pageAnchors.forEach { (pg, a) -> drawPageGlassHoles(a, (pg - pos) * size.width, gridOrigin) }
        })
        // Kenarda yana çekince Android'in esneme (gerilme) efekti kapalı: sayfa esnerken kartların camı (altındaki ayrı
        // katmanda çizilen bulanık iç) esnemeye katılmıyor, kenarlarda kaymış ikinci bir hat oluşuyordu.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !dragging,
            // Honor MagicOS 10 "Varsayılan" sayfa geçişi (ekran kaydından ölçüldü): sekmeden, kritik sönümlü yay; duvar
            // kâğıdı sabit, simgeler sayfayla tek parça; bir seferde en çok bir sayfa. Honor'un ölçülen sertliği (ω≈25/sn)
            // sonda "çakılma" hissi veriyordu: biraz daha yumuşak (ω≈20/sn), sona doğru süzülerek oturur (~0,3 sn).
            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = androidx.compose.foundation.pager.PagerSnapDistance.atMost(1),
                snapAnimationSpec = motion.spring(damping = 1f, stiffness = 420f),
                snapPositionalThreshold = 0.5f,
            ),
            // Sürüklerken TÜM sayfalar bellekte kalır: sürüklemenin başladığı sayfa (ve onu izleyen dokunma
            // kodu) uzak bir sayfaya geçilince atılmasın, yoksa bırakma kayboluyordu.
            // Bütün ana ekran sayfaları hep hazır tutulur: sayfa değişince bir sonrakinin simgeleri baştan hazırlanıyor,
            // o karede 30 ms'lik tek bir takılma oluyordu. (Arşiv yalnızca son sayfadayken hazırlanır.)
            beyondViewportPageCount = if (dragging) homeCount else maxOf(1, pageTotal - 1),
            modifier = Modifier.fillMaxSize().nestedScroll(todayConn).onGloballyPositioned {
                // Widget sayfası geçişinde ana ekran tek parça kayar: konum o sırada güncellenmez. Güncellenince her karede
                // bütün sayfaların (görünmeyen arşiv dahil) camları baştan çiziliyor, geçiş 120 Hz'in altına düşüyordu.
                if ((today == null || today.progress == 0f) && !GlassMotion.homeShift) gridOrigin = it.positionInRoot()
            },
        ) { page ->
            if (page == libIndex) {
                // Arşiv de ana ekran sayfaları gibi: kayarken kartların bulanık içi sayfanın altındaki ayrı katmanda
                // (her karede ucuz), kartların kendisi hazır görüntü olarak taşınır.
                val libAnchor = remember { PageAnchor() }
                libAnchor.holesActive = { (scrollHold || GlassMotion.listHoles || todayMoving()) && !edit && !dragging }
                DisposableEffect(page, libAnchor) {
                    pageAnchors[page] = libAnchor
                    onDispose { if (pageAnchors[page] === libAnchor) pageAnchors.remove(page) }
                }
                PageBox(libAnchor, gridOrigin, cache = !edit) {
                // Arama çubuğu, ana ekrandaki ilk simge satırıyla AYNI üst çizgiden başlar.
                Box(Modifier.fillMaxSize().padding(top = m.firstTop)) {
                    LibraryPage(
                        vm = vm, groups = groups, allApps = allApps, settings = settings,
                        drag = drag, edit = edit, jiggle = jiggle,
                        onDropFromLibrary = { k, p, h -> handleDrop(k, p, h) },
                        onOpenGroup = onOpenGroup,
                        active = pagerState.settledPage == libIndex,
                    )
                }
                }
                return@HorizontalPager
            }
            val pagePl = byPage[page].orEmpty()
            // Üstten hizalı sabit ızgara: boş hücreler boş kalır, simgeler yayılmaz.
            // Sayfa bir kez hazır görüntü olarak çizilir; kayarken yalnızca bu görüntü taşınır (bkz. PageBox).
            val anchor = remember { PageAnchor() }
            // Düzenleme modunda yalnızca görünen sayfa ve iki komşusu sallanır: bellekte tutulan uzak sayfaların
            // simgeleri de her karede sallanınca çizim komutları bütçeyi aşıyordu (uzak sayfalar zaten görünmüyor).
            val nearCurrent by remember(page) { derivedStateOf { kotlin.math.abs(page - pagerState.currentPage) <= 1 } }
            val pageJiggle = if (nearCurrent) jiggle else stillJiggle
            // Kayarken (düzenleme/sürükleme dışında) cam arkaları sayfanın altındaki katmanda çizilir.
            anchor.holesActive = { (scrollHold || todayMoving()) && !edit && !dragging }
            // Güvence: geçiş sırasında cam kendi arkasını çizerse de o anki kaymaya göre doğru yerden çizsin (donuk kalmasın).
            anchor.shift = { today?.takeIf { it.moving }?.let { Offset(it.progress * it.widthPx, 0f) } ?: Offset.Zero }
            DisposableEffect(page, anchor) {
                pageAnchors[page] = anchor
                onDispose { if (pageAnchors[page] === anchor) pageAnchors.remove(page) }
            }
            PageBox(anchor, gridOrigin, cache = !edit) {
                pagePl.forEach { pl ->
                  key(pl.item.id) {
                    val x = m.marginX + m.pitchX * pl.col.toFloat()
                    val y = m.firstTop + m.pitchY * pl.row.toFloat()
                    // Yer değişince simge yeni konumuna yayla kayar.
                    val ax by animateDpAsState(x, motion.reflow(), label = "x")
                    val ay by animateDpAsState(y, motion.reflow(), label = "y")
                    Box(Modifier.offset { IntOffset(ax.roundToPx(), ay.roundToPx()) }) {
                        when (val item = pl.item) {
                            is AppItem -> {
                                val app = byKey[item.key]
                                if (app != null) {
                                    DraggableCell(
                                        id = item.id, place = CellPlace.Grid,
                                        label = app.label, showLabel = settings.showLabels,
                                        icon = m.icon, pitchX = m.pitchX, cellHeight = m.pitchY,
                                        drag = drag, layer = DragLayer.Home, isApp = true,
                                        acceptsMerge = true, edit = edit, jiggle = pageJiggle,
                                        onClick = { r ->
                                            if (selection.active) selection.toggle(item.id) else if (!edit) launchFrom(app, r)
                                        },
                                        onDrop = { p, h -> handleDrop(item.id, p, h) },
                                        selected = if (selection.active) item.id in selection.ids else null,
                                        hidden = selection.isGroupDrag(drag.key) && item.id in selection.ids,
                                    ) { AppIconImage(app, vm, m.icon, settings.iconGlass, settings.glassStrength) }
                                }
                            }
                            is FolderItem -> DraggableCell(
                                id = item.id, place = CellPlace.Grid,
                                label = item.name, showLabel = settings.showLabels,
                                icon = m.icon, pitchX = m.pitchX, cellHeight = m.pitchY,
                                drag = drag, layer = DragLayer.Home, isApp = false,
                                acceptsMerge = true, edit = edit, jiggle = pageJiggle,
                                // Seçim modunda da klasör açılır; seçim içindeki uygulamalarda yapılır.
                                onClick = { r -> onOpenFolder(item, r.center) },
                                onDrop = { p, h -> handleDrop(item.id, p, h) },
                            ) {
                                FolderIcon(
                                    item, byKey, vm, m.icon, settings.glassStrength * settings.folderOpacity,
                                    folderPages[item.id] ?: 0,
                                )
                            }
                            is WidgetItem -> {
                                val w = m.widgetWidth(pl.spanX)
                                val h = m.widgetHeight(pl.spanY)
                                // iOS: widget simge kenarlarından biraz taşar (bkz. HomeMetrics.widgetBleed); altında uygulama adı.
                                // iOS: widget yalnızca yanlara taşar; üstten satır hizasında başlar (ölçüldü).
                                Column(Modifier.offset(-m.widgetBleed, 0.dp)) {
                                WidgetCell(
                                    id = item.id, width = w, height = h, drag = drag, edit = edit, jiggle = pageJiggle,
                                    onDrop = { p, hv -> handleDrop(item.id, p, hv) },
                                    widgetLabel = if (item.stack.isNotEmpty()) "Widget yığını, ${item.stack.size + 1} widget" else if (item.kind == WidgetKind.System) "Widget" else "${item.kind.title} widget'ı",
                                    // Sistem widget'ları ve yığınlar dokunuşu kendileri yönetir.
                                    onClick = if (item.kind != WidgetKind.System && item.stack.isEmpty()) { r -> vm.openWidget(item, view, r) } else null,
                                ) { WidgetBody(item, vm, w, h, settings.glassStrength) }
                                if (settings.showLabels) {
                                    val caption = rememberWidgetCaption(item, vm)
                                    Text(
                                        caption,
                                        style = CellLabelStyle.copy(fontSize = (settings.labelSize * m.pt.value).sp, fontWeight = fontWeightOf(settings.labelWeight)),
                                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(w).padding(top = m.pt * settings.labelGap, start = 2.dp, end = 2.dp)
                                            .graphicsLayer { alpha = if (drag.key == item.id) 0f else 1f }
                                            .then(Modifier.clearAndSetSemantics { }),
                                    )
                                }
                                }
                            }
                            is PageBreak -> Unit // görünmez sayfa sonu
                            is dev.glass.launcher.data.GapItem -> Unit // görünmez boş hücre
                        }
                    }
                  }
                }
            }
        }
        }

        if (!isDefaultHome) {
            Column(
                Modifier
                    .padding(top = statusTop + 4.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x99000000))
                    .padding(14.dp)
            ) {
                Text(stringResource(R.string.make_default_hint), color = Color.White, fontSize = 13.sp)
                Button(onClick = onRequestDefault) { Text(stringResource(R.string.make_default)) }
            }
        }

        // Alt bölüm: arama hapı / sayfa noktaları ve dock. Arşive geçerken aşağı kayar.
        val hideShiftPx = with(density) { (maxHeight - m.pillCenterY + m.pillH).toPx() }
        val dockW = maxWidth - m.dockMarginX * 2f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = libFraction * hideShiftPx
                    alpha = 1f - libFraction
                }
        ) {
            // Hap: simge dizisinin altı ile dock'un üstü arasında tam ortada.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(m.pillH)
                    .offset(y = m.pillCenterY - m.pillH / 2f),
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.graphicsLayer { alpha = dotsAlpha }) {
                    repeat(homeCount) { i ->
                        Box(
                            Modifier.padding(3.dp).size(7.dp).clip(CircleShape)
                                .background(if (i == pagerState.currentPage) Color.White else Color(0x66FFFFFF))
                        )
                    }
                    // Arşiv sayfası için küçük, soluk bir gösterge
                    Box(
                        Modifier.padding(3.dp).size(7.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (pagerState.currentPage == libIndex) Color.White else Color(0x40FFFFFF))
                    )
                }
                // Görünürlük çizimde okunur: sayfa kaydırılırken noktalar/hap geçişi ana ekranı yeniden oluşturmasın.
                SearchPill(m, settings.glassStrength, { (1f - dotsAlpha) * (1f - libFraction) * pillShown }, onOpenSearch)
            }

            if (libFraction < 0.98f) {
                Box(
                    Modifier
                        .offset(x = m.dockMarginX, y = m.dockTop)
                        .width(dockW)
                        .height(m.dockH)
                        .onGloballyPositioned { dockRect = it.boundsInRoot() }
                        .liquidGlass(appShape(m.dockCorner), settings.glassStrength * settings.dockOpacity, 14.dp, ios = true, brightRim = true, iconRim = true)
                        // iOS 26 dock'u arkasına göre hafif soğuk/mavimsi (ölçüldü: mavi−kırmızı farkı ~7 birim fazla).
                        .background(Color(0xFF8FB4FF).copy(alpha = 0.035f), appShape(m.dockCorner))
                ) {
                    shownDock.forEachIndexed { i, dk ->
                      key(dk) {
                        val app = byKey[dk]
                        if (app != null) {
                            val dx by animateDpAsState(m.dockInnerStart + m.dockPitch * i.toFloat(), motion.reflow(), label = "dx")
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .offset { IntOffset(dx.roundToPx(), 0) }
                            ) {
                                DraggableCell(
                                    id = dk, place = CellPlace.Dock,
                                    label = app.label, showLabel = false,
                                    icon = m.dockIcon, pitchX = m.dockPitch, cellHeight = m.dockIcon,
                                    drag = drag, layer = DragLayer.Home, isApp = true,
                                    acceptsMerge = false, edit = edit, jiggle = jiggle,
                                    onClick = { r -> if (!edit) launchFrom(app, r) },
                                    onDrop = { p, h -> handleDrop(dk, p, h) },
                                ) { AppIconImage(app, vm, m.dockIcon, settings.iconGlass, settings.glassStrength) }
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
private fun SearchPill(m: HomeMetrics, strength: Float, alpha: () -> Float, onClick: () -> Unit) {
    Row(
        Modifier
            .graphicsLayer { this.alpha = alpha() }
            .width(m.pillW)
            .height(m.pillH)
            .liquidGlass(CircleShape, strength * 0.8f, 6.dp)
            .clickable { if (alpha() > 0.5f) onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pt = m.pt.value
        val k = m.pillH / (m.pt * 29f) // arama düğmesi boyut çarpanı
        // iOS 26 ölçümü: büyüteç ve yazı ince, beyazın ~%80'i; büyüteç yazının yanında küçük.
        SearchGlyph(Color.White.copy(alpha = 0.82f), m.pt * 9.5f * k)
        Spacer(Modifier.width(m.pt * 5f * k))
        Text(
            "Ara", color = Color.White.copy(alpha = 0.82f),
            fontSize = (LocalLauncherSettings.current.pillTextSize * pt * k).sp,
            fontWeight = fontWeightOf(LocalLauncherSettings.current.pillTextWeight),
            style = LocalTextStyle.current.copy(shadow = IosTextShadow),
        )
    }
}

private val LabelStyle: TextStyle get() = TextStyle(
    color = Color.White,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    textAlign = TextAlign.Center,
    fontFamily = AppFont,
    shadow = IosTextShadow,
)

/**
 * Uygulama simgesi: seçili görünüm (orijinal/açık/koyu/renkli veya özel görsel) ve ayarlanabilir köşe
 * yuvarlaklığıyla. Bitmap ekrandaki boyutta, arka planda üretilir ve önbelleğe alınır.
 */
@Composable
internal fun AppIconImage(app: AppInfo, vm: LauncherViewModel, icon: Dp, glass: Boolean, strength: Float, badge: Boolean = true) {
    val st = LocalLauncherSettings.current
    val px = with(LocalDensity.current) { icon.roundToPx() }
    val look = vm.iconLook(app, st, androidx.compose.foundation.isSystemInDarkTheme())
    val live = vm.liveKind(app, st)
    // Kenar çizgisi kalınlığı: ana ekran boyutunda ayardaki değer, küçük simgelerde orantılı incelir.
    val rimW = st.iconRimWidth.dp * (icon / 60.dp).coerceAtMost(1f)
    if (live != null) {
        Box(Modifier.size(icon)) {
        // Canlı simge seçili simge görünümüne uyar (Otomatik); ayardan sabit açık/koyu da seçilebilir.
        val liveLook = when (st.liveIconTheme) {
            "light" -> LiveLook.Light
            "dark" -> LiveLook.Dark
            else -> when (look.style) {
                dev.glass.launcher.data.IconStyle.Dark -> LiveLook.Dark
                dev.glass.launcher.data.IconStyle.Tinted -> LiveLook.Tinted
                dev.glass.launcher.data.IconStyle.Clear -> LiveLook.Clear
                else -> LiveLook.Light
            }
        }
        val liveShape = appShape(icon * st.iconCorner)
        Box(
            Modifier.fillMaxSize().then(
                if (liveLook == LiveLook.Clear) Modifier.liquidGlass(liveShape, strength * 0.8f, 0.dp, frosted = true, ios = true)
                    .background(
                        if (when (st.clearTone) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }) Color(0x61000000)
                        else Color(0x38FFFFFF)
                    )
                else Modifier.glassIcon(liveShape, glass, strength, st.iconRimGlow, rimW)
            )
        ) {
            LiveIcon(live, icon, st.liveClockSeconds, liveLook, Color(look.tint))
        }
        if (badge && !app.isShortcut) NotificationBadge(badgeCount(listOf(app.packageName)), icon)
        }
        return
    }
    val bmp by produceState<ImageBitmap?>(null, app.key, app.iconStamp, px, look) { value = vm.iconCache.get(app, px, look) }
    // iOS 26 "Şeffaf" simge: zemin gerçek Liquid Glass (klasör simgesiyle aynı cam), üstünde gri-beyaz sembol.
    val clear = look.style == dev.glass.launcher.data.IconStyle.Clear && look.customFile == null
    Box(Modifier.size(icon)) {
        Box(
            Modifier.fillMaxSize().then(
                if (clear) {
                    // Şeffaf cam tonu (iOS 26): açık cam hafif beyaz, koyu cam hafif siyah tülle.
                    val darkGlass = when (st.clearTone) {
                        "dark" -> true
                        "light" -> false
                        else -> androidx.compose.foundation.isSystemInDarkTheme()
                    }
                    Modifier.liquidGlass(appShape(icon * st.iconCorner), strength * 0.8f, 0.dp, frosted = true, ios = true)
                        .background(if (darkGlass) Color(0x61000000) else Color(0x38FFFFFF))
                }
                else Modifier.glassIcon(appShape(icon * st.iconCorner), glass, strength, st.iconRimGlow, rimW)
            )
        ) {
            // Ekran okuyucu adı hücreden okur (etiket ya da hücrenin açıklaması); simge ayrıca okunmaz.
            // Renk canlılığı: çizimde doygunluk filtresi (simge görüntüsü ve çözünürlüğü değişmez). Koyu/Renkli/Şeffaf
            // stiller kendi renk düzenleriyle çalışır; onlara uygulanmaz.
            val vivid = remember(st.iconSaturation, look.style) {
                val sat = st.iconSaturation
                if (kotlin.math.abs(sat - 1f) < 0.01f || look.customFile != null ||
                    (look.style != dev.glass.launcher.data.IconStyle.Original && look.style != dev.glass.launcher.data.IconStyle.Light)
                ) null
                else androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(sat) })
            }
            bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.High, colorFilter = vivid) }
        }
        if (badge && !app.isShortcut) NotificationBadge(badgeCount(listOf(app.packageName)), icon)
    }
}

/** Verilen paketlerin toplam bildirim sayısı ("Bildirim sayıları" ayarı kapalıysa 0). */
@Composable
internal fun badgeCount(packages: List<String>): Int {
    if (!LocalLauncherSettings.current.showBadges) return 0
    val counts by dev.glass.launcher.service.Badges.counts.collectAsState()
    return packages.sumOf { counts[it] ?: 0 }
}

/**
 * iOS rozeti: simgenin sağ üst köşesinde, kenardan hafifçe taşan kırmızı daire ve beyaz sayı.
 * Çok basamaklı sayılarda hap şeklinde uzar.
 */
@Composable
internal fun BoxScope.NotificationBadge(count: Int, icon: Dp) {
    if (count <= 0) return
    // Küçük simgelerde (arşiv/klasör önizlemesi) rozet de orantılı küçülür; çok küçükse yalnızca nokta.
    val small = icon < 30.dp
    val h = if (small) (icon * 0.42f).coerceIn(6.dp, 12.dp) else (icon * 0.36f).coerceIn(14.dp, 26.dp)
    val dot = h < 11.dp
    val text = if (count > 999) "999+" else count.toString()
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .offset(x = h * 0.32f, y = -h * 0.32f)
            .height(h)
            .widthIn(min = h)
            .clip(CircleShape)
            .background(Color(0xFFFF3B30))
            .padding(horizontal = if (dot) 0.dp else h * 0.26f),
        contentAlignment = Alignment.Center,
    ) {
        if (!dot) Text(
            text, color = Color.White, fontFamily = AppFont, fontWeight = FontWeight.SemiBold,
            fontSize = (h.value * 0.62f).sp, lineHeight = (h.value * 0.62f).sp, maxLines = 1,
        )
    }
}

/** Sürüklenmeyen basit hücre (arama sonuçları vb.). */
@Composable
internal fun AppCell(
    app: AppInfo, vm: LauncherViewModel,
    icon: Dp, pitchX: Dp, cellHeight: Dp, showLabel: Boolean,
    glass: Boolean, strength: Float,
    /** Etiket yazı boyutu (pt) ve kalınlığı; null: varsayılan (ana ekran etiket ayarları). */
    labelSize: Float? = null, labelWeight: Float? = null,
    onClick: () -> Unit,
) {
    // Android'in gri kare "basıldı" efekti yerine iOS gibi: simge hafifçe küçülüp kararır.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotion.current
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, motion.spring(damping = 0.6f, stiffness = 600f), label = "cellPress")
    Box(Modifier.width(icon).height(cellHeight), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .requiredWidth(pitchX)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppIconImage(app, vm, icon, glass, strength)
            if (showLabel) {
                Text(
                    app.label,
                    style = LabelStyle.copy(
                        fontWeight = fontWeightOf(labelWeight ?: LocalLauncherSettings.current.labelWeight),
                        fontSize = labelSize?.sp ?: LabelStyle.fontSize,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp).fillMaxWidth()
                )
            }
        }
    }
}
