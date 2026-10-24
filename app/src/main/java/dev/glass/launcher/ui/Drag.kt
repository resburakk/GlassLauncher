package dev.glass.launcher.ui

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.offset

import androidx.compose.ui.layout.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.FolderItem

/** Sürüklenebilir öğenin bulunduğu katman. Klasör açıkken yalnızca klasör katmanı dokunuşa açıktır. */
enum class DragLayer { Home, Folder }

/** Hücrenin nerede durduğu (uzun basma menüsündeki seçenekleri belirler). */
enum class CellPlace { Grid, Dock, Folder, Library }

/**
 * Ekranda görünen, sürüklenebilir bir hücrenin kaydı. Konumlar kök (pencere) koordinatındadır.
 * [id] kayıt kimliğidir (aynı uygulama hem ana ekranda hem arşivde görünebilir); [itemId] sürüklenen öğedir.
 */
class DragTarget(val id: String) {
    var itemId: String = id
    var place: CellPlace = CellPlace.Grid
    var layer: DragLayer = DragLayer.Home
    var isApp: Boolean = true
    var acceptsMerge: Boolean = false
    var iconDp: Dp = 60.dp
    /** Widget'lar için hayalet boyutu (uygulamalarda null: simge boyutu kullanılır). */
    var ghostSize: DpSize? = null
    var cellRect: Rect = Rect.Zero
    var iconRect: Rect = Rect.Zero
    var onDrop: (Offset, String?) -> Unit = { _, _ -> }
    /**
     * Konumu şu anki gerçek ekran yerinden yeniden hesaplar. Konum yalnızca yerleşim değişince kaydediliyor;
     * klasör büyüyerek açılırken (animasyon yerleşimi değiştirmez) simgelerin konumu klasör henüz küçükken kalıyor,
     * klasör içinde uzun basma simgeyi bulamıyordu. Uzun basma başlarken çağrılır.
     */
    var refresh: (() -> Unit)? = null
}

/**
 * Sürükle-bırak denetleyicisi.
 *
 * Tasarım: hücreler kendileri hareket algılamaz; yalnızca konumlarını buraya kaydeder. Uzun basma ve
 * sürükleme kök katmanda, olayların ilk geçişinde (Initial) tek bir yerde algılanır. Sürükleme başladığı
 * anda tüm olaylar tüketilir; böylece sayfa kaydırıcı (pager) parmağı çalamaz ve sayfa kaymaz.
 */
@Stable
class DragState {
    private val targets = HashMap<String, DragTarget>()

    var key by mutableStateOf<String?>(null)
        private set
    var pos by mutableStateOf(Offset.Zero)
        private set
    var iconDp by mutableStateOf(60.dp)
        private set
    var ghostSize by mutableStateOf<DpSize?>(null)
        private set
    var hoverId by mutableStateOf<String?>(null)
        private set
    /** Parmak gerçekten kaydırıldı mı? (Uzun basmada menü açıkken hayalet simge gösterilmez.) */
    var moved by mutableStateOf(false)
        private set
    /** Dokunuşa açık katman (klasör açıkken Folder). */
    var activeLayer by mutableStateOf(DragLayer.Home)

    private var dropHandler: ((Offset, String?) -> Unit)? = null
    private var dragIsApp = true

    val isDragging: Boolean get() = key != null

    fun register(t: DragTarget) {
        targets[t.id] = t
    }

    fun unregister(t: DragTarget) {
        if (targets[t.id] === t) targets.remove(t.id)
    }

    fun target(id: String): DragTarget? = targets[id]

    /** Ana ekranda veya dock'ta görünen bir öğenin simge alanı (kök koordinatı); yoksa null. */
    fun homeIconRect(itemId: String): Rect? = targets.values
        .firstOrNull { it.itemId == itemId && (it.place == CellPlace.Grid || it.place == CellPlace.Dock) && !it.iconRect.isEmpty }
        ?.iconRect

    /** Etkin katmandaki hedeflerin konumunu o anki ekran yerinden yeniler (bkz. [DragTarget.refresh]). */
    fun refreshActive() {
        targets.values.forEach { if (it.layer == activeLayer) runCatching { it.refresh?.invoke() } }
    }

    fun hitTest(p: Offset): DragTarget? =
        targets.values.firstOrNull { it.layer == activeLayer && it.cellRect.contains(p) }

    fun targetsIn(layer: DragLayer): Collection<DragTarget> = targets.values.filter { it.layer == layer }

    fun start(t: DragTarget, at: Offset) {
        key = t.itemId
        iconDp = t.iconDp
        ghostSize = t.ghostSize
        pos = at
        dropHandler = t.onDrop
        dragIsApp = t.isApp
        hoverId = null
        moved = false
    }

    fun markMoved() {
        moved = true
    }

    fun move(p: Offset) {
        pos = p
        val k = key ?: return
        // Klasör oluşturma/ekleme hedefi: yalnızca uygulama sürüklenirken, simgenin merkezine yakınsa.
        hoverId = if (!dragIsApp) null else targets.values.firstOrNull {
            it.itemId != k && it.acceptsMerge && it.layer == activeLayer &&
                (p - it.iconRect.center).getDistance() < it.iconRect.width * 0.42f
        }?.itemId
    }

    /** Parmak kalkınca: önce hayalet kaldırılır, sonra bırakma işlemi uygulanır. */
    fun finish() {
        val p = pos
        val hover = hoverId
        val h = dropHandler
        clear()
        h?.invoke(p, hover)
    }

    /** Bırakma işlemini değiştirir (ör. klasörden ana ekrana geçişte). */
    fun redirectDrop(handler: (Offset, String?) -> Unit) {
        if (key != null) dropHandler = handler
    }

    fun clear() {
        key = null
        hoverId = null
        dropHandler = null
        moved = false
    }
}

/** Uzun basma süresi: sistem varsayılanından (≈500 ms) kısa; menü daha çabuk ve kolay açılır. */
const val LONG_PRESS_MS = 300L

/**
 * Kök katmana takılan tek hareket algılayıcı.
 * - Kısa dokunuş: hiçbir şey tüketilmez, hücrenin tıklaması çalışır.
 * - Kaydırma (uzun basmadan önce): hiçbir şey tüketilmez, sayfa kaydırıcı çalışır.
 * - Uzun basma bir hücre üzerinde: iOS gibi menü HEMEN (parmak ekrandayken) açılır. Parmak kaydırılırsa
 *   menü kapanır, düzenleme modu + sürükle-bırak başlar; kaydırmadan bırakılırsa menü açık kalır.
 * - Uzun basma boş alanda: düzenleme modu.
 * - Düzenleme modunda bir hücreyi doğrudan sürüklemek de sürüklemeyi başlatır (iOS gibi).
 */
suspend fun PointerInputScope.detectHomeDrag(
    drag: DragState,
    enabled: () -> Boolean,
    isEditing: () -> Boolean,
    onLongPressEmpty: () -> Unit,
    onMenu: (DragTarget) -> Unit,
    onDragStarted: () -> Unit,
    /** Düzenleme modunda bu öğe parmağı kaydırınca doğrudan sürüklenebilir mi (seçimde: yalnızca seçililer). */
    canDirectDrag: (DragTarget) -> Boolean = { true },
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val editing = isEditing()
        drag.refreshActive()
        val hit = drag.hitTest(down.position)

        // 0 = vazgeç (dokunma veya kaydırma), 1 = düzenleme modunda doğrudan sürükleme, null = uzun basma
        val outcome: Int? = withTimeoutOrNull(LONG_PRESS_MS) {
            var r = -1
            while (r < 0) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id }
                // Yalnızca gerçekten basılı İKİNCİ bir parmak iptal eder; parmağı içermeyen ara olaylar (ör. düzen
                // değişince gönderilen yapay olaylar) yok sayılır. Önceden bunlar uzun basmayı iptal edip dokunuşu
                // tıklamaya çeviriyordu (klasörde basılı tutunca menü yerine uygulama açılıyordu).
                val otherDown = ev.changes.any { it.id != down.id && it.pressed }
                r = when {
                    c == null -> -1
                    !c.pressed || otherDown -> 0
                    (c.position - down.position).getDistance() > slop -> {
                        val d = c.position - down.position
                        // Hızlı yatay hareket her zaman sayfa çevirir (iOS gibi); yavaş/kontrollü hareket sürükler.
                        val flick = kotlin.math.abs(d.x) > kotlin.math.abs(d.y) * 1.2f &&
                            c.uptimeMillis - down.uptimeMillis < 140
                        if (editing && hit != null && !flick && canDirectDrag(hit)) 1 else 0
                    }
                    else -> -1
                }
            }
            r
        }
        if (outcome == 0) return@awaitEachGesture

        var moved = outcome == 1
        if (hit == null) {
            onLongPressEmpty()
        } else {
            drag.start(hit, down.position)
            if (moved) {
                drag.markMoved()
                onDragStarted()
            } else {
                onMenu(hit)
            }
        }

        // Parmak kalkana kadar tüm olayları tüket: alttaki katmanlar (pager, tıklama) tepki vermez.
        var last = down.position
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val c = ev.changes.firstOrNull { it.id == down.id }
            if (c != null) last = c.position
            if (drag.isDragging && c != null) {
                // Menü açıkken biraz daha geniş tolerans: titreyen parmak menüyü kapatmasın.
                if (!moved && (c.position - down.position).getDistance() > slop * 2f) {
                    moved = true
                    drag.markMoved()
                    onDragStarted()
                }
                drag.move(c.position)
            }
            ev.changes.forEach { it.consume() }
            if (c == null || !c.pressed) break
        }
        if (drag.isDragging) {
            if (moved) {
                drag.move(last)
                drag.finish()
            } else {
                drag.clear() // menü açık kalır
            }
        }
    }
}

/** iOS ana ekran etiketi: beyaz, normal kalınlık, yumuşak ve hafif koyu gölge. */
internal val CellLabelStyle: TextStyle get() = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.1.sp,
    textAlign = TextAlign.Center,
    fontFamily = AppFont,
    shadow = IosTextShadow,
)

/**
 * Sürüklenebilir hücre (uygulama veya klasör). Hareket algılamaz; konumunu [DragState]'e kaydeder.
 * Düzenleme modunda hafifçe sallanır; üzerine bir uygulama getirilince büyür (klasör ipucu).
 */
@Composable
internal fun DraggableCell(
    id: String,
    itemId: String = id,
    place: CellPlace = CellPlace.Grid,
    label: String,
    showLabel: Boolean,
    icon: Dp,
    pitchX: Dp,
    cellHeight: Dp,
    drag: DragState,
    layer: DragLayer,
    isApp: Boolean,
    acceptsMerge: Boolean,
    edit: Boolean,
    jiggle: State<Float>,
    onClick: (Rect) -> Unit,
    onDrop: (Offset, String?) -> Unit,
    /** Çoklu seçim: null = seçim modu kapalı; true/false = işaret dolu/boş. */
    selected: Boolean? = null,
    /** Toplu sürüklemede seçili diğer simgeler yerinde gizlenir (hayalette birlikte taşınır). */
    hidden: Boolean = false,
    iconContent: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val iconPx = with(density) { icon.toPx() }
    val pitchPx = with(density) { pitchX.toPx() }
    val cellHPx = with(density) { cellHeight.toPx() }

    val target = remember(id) { DragTarget(id) }
    target.itemId = itemId
    target.place = place
    target.layer = layer
    target.isApp = isApp
    target.acceptsMerge = acceptsMerge
    target.iconDp = icon
    target.onDrop = onDrop
    DisposableEffect(target, drag) {
        drag.register(target)
        onDispose { drag.unregister(target) }
    }

    // Yalnızca bu hücrenin "üzerinde mi" sonucu değişince yeniden çizilir (tüm ızgara değil).
    val hovered by remember(itemId) { derivedStateOf { drag.hoverId == itemId } }
    val interaction = remember { MutableInteractionSource() }
    val pressed0 by interaction.collectIsPressedAsState()
    // Basılı tutup menü açılınca parmağın kalkışı hücreye ulaşmıyor, "basılı" görünüm (küçük ve soluk) takılı
    // kalıyordu. Uzun basışta (menü açılırken) basılı görünüm bırakılır; iOS'ta da simge kalkar, küçülmez.
    var longHeld by remember { mutableStateOf(false) }
    LaunchedEffect(pressed0) {
        if (pressed0) { kotlinx.coroutines.delay(400); longHeld = true } else longHeld = false
    }
    val pressed = pressed0 && !longHeld
    val motion = LocalMotion.current
    // iOS gibi: üzerine simge gelince büyür, basılınca hafifçe küçülüp kararır.
    val scale by animateFloatAsState(
        when {
            hovered -> 1.16f
            pressed -> 0.92f
            else -> 1f
        },
        motion.spring(damping = 0.6f, stiffness = 600f),
        label = "cellScale",
    )
    val sign = remember(itemId) { if ((itemId.hashCode() and 1) == 0) 1f else -1f }

    Box(Modifier.width(icon).height(cellHeight), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .requiredWidth(pitchX)
                .onGloballyPositioned { c ->
                    fun update(c: androidx.compose.ui.layout.LayoutCoordinates) {
                        val o = c.positionInRoot()
                        target.cellRect = Rect(o.x, o.y, o.x + pitchPx, o.y + cellHPx)
                        val left = o.x + (pitchPx - iconPx) / 2f
                        target.iconRect = Rect(left, o.y, left + iconPx, o.y + iconPx)
                    }
                    update(c)
                    target.refresh = { if (c.isAttached) update(c) }
                }
                .graphicsLayer {
                    // Basınca yalnızca küçülür, soluklaşmaz: yarı saydamlık hücreyi ayrı katmana alıp sınırında kesiyordu
                    // (simgeden taşan bildirim rozeti yarım görünüyordu). Katmansız saydamlık (ModulateAlpha) ise cam
                    // efektleriyle birlikte basılı tutma anında grafik sürücüsünü kilitledi (emülatör ekranı dondu).
                    alpha = if (drag.key == itemId || hidden) 0f else 1f
                    scaleX = scale
                    scaleY = scale
                    rotationZ = if (edit) jiggle.value * sign else 0f
                }
                // Düzenleme modu: simge + etiket + yazı gölgesi bir kez kendi katmanına çizilir, sallanma yalnızca bu hazır
                // görüntüyü döndürür (açı her karede değişince gölgeler her karede yeniden hesaplanıyor, 60 Hz'e düşüyordu).
                .cachedLayer(edit)
                // Ekran okuyucu (TalkBack): ad her zaman okunur (etiket gizliyse de), klasör olduğu söylenir.
                .semantics(mergeDescendants = true) {
                    if (!showLabel) contentDescription = label
                    if (!isApp) stateDescription = "Klasör"
                    if (selected != null) stateDescription = if (selected) "Seçili" else "Seçili değil"
                }
                .clickable(interactionSource = interaction, indication = null, onClickLabel = if (isApp) "Aç" else "Klasörü aç") {
                    onClick(target.iconRect)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                iconContent()
                if (selected != null) {
                    SelectBadge(
                        selected, icon * 0.36f,
                        Modifier.align(Alignment.BottomEnd).offset(x = icon * 0.08f, y = icon * 0.08f),
                    )
                }
            }
            if (showLabel) {
                val st = LocalLauncherSettings.current
                val pt = LocalPt.current
                Text(
                    label,
                    style = CellLabelStyle.copy(fontSize = (st.labelSize * pt.value).sp, fontWeight = fontWeightOf(st.labelWeight)),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = pt * st.labelGap, start = 2.dp, end = 2.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** Kapalı klasör: 3×3 ızgarada, klasörde en son görüntülenen sayfanın en çok 9 küçük simgesi. */
@Composable
internal fun FolderIcon(
    folder: FolderItem,
    byKey: Map<String, AppInfo>,
    vm: LauncherViewModel,
    icon: Dp,
    strength: Float,
    page: Int = 0,
) {
    // Mini simgeler kutunun %22'si, aralarında %3 boşluk; 3×3 blok kutunun ortasında toplu durur.
    val mini = icon * 0.22f
    val gap = icon * 0.03f
    val block = mini * 3f + gap * 2f
    val pageCount = (folder.keys.size + 8) / 9
    val shown = folder.keys.drop(page.coerceIn(0, maxOf(pageCount - 1, 0)) * 9).take(9)
    // Klasör rozeti: içindeki uygulamaların toplam bildirim sayısı (iOS gibi).
    val folderBadge = badgeCount(folder.keys.mapNotNull { byKey[it]?.takeIf { a -> !a.isShortcut }?.packageName })
    Box(Modifier.size(icon)) {
    Box(
        // Gölgesiz: yarı saydam camın altındaki gölge içeriden kare gibi görünüyordu.
        // Köşe, uygulama simgeleriyle aynı "Köşe yuvarlaklığı" ayarını izler.
        Modifier.size(icon).liquidGlass(appShape(icon * LocalLauncherSettings.current.iconCorner), strength * 0.8f, 0.dp, ios = true),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.size(block), verticalArrangement = Arrangement.spacedBy(gap)) {
            shown.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { k ->
                        val app = byKey[k]
                        if (app != null) AppIconImage(app, vm, mini, false, 0f, badge = false)
                        else Spacer(Modifier.size(mini))
                    }
                }
            }
        }
    }
    NotificationBadge(folderBadge, icon)
    }
}

/**
 * Ana ekrandaki widget hücresi: konumunu sürükle-bırak sistemine kaydeder; uzun basınca menü,
 * sürükleyince taşıma. Düzenleme modunda sallanır. İçerik dokunuşları (sistem widget'ları) olduğu gibi çalışır.
 */
@Composable
internal fun WidgetCell(
    id: String,
    width: Dp,
    height: Dp,
    drag: DragState,
    edit: Boolean,
    jiggle: State<Float>,
    onDrop: (Offset, String?) -> Unit,
    /** Ekran okuyucunun okuyacağı ad (ör. "Hava Durumu widget'ı"). */
    widgetLabel: String = "Widget",
    /** Dokununca (düzenleme modu dışında): launcher widget'larında ilgili uygulamayı açar. */
    onClick: ((Rect) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // iOS: widget'a basılınca hafifçe içeri çöker.
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && onClick != null && !edit) 0.96f else 1f, LocalMotion.current.tween(if (pressed) 90 else 160), label = "widgetPress",
    )
    val density = LocalDensity.current
    val wPx = with(density) { width.toPx() }
    val hPx = with(density) { height.toPx() }
    val target = remember(id) { DragTarget(id) }
    target.itemId = id
    target.place = CellPlace.Grid
    target.layer = DragLayer.Home
    target.isApp = false
    target.acceptsMerge = false
    target.iconDp = width
    target.ghostSize = DpSize(width, height)
    target.onDrop = onDrop
    DisposableEffect(target, drag) {
        drag.register(target)
        onDispose { drag.unregister(target) }
    }
    Box(
        Modifier
            .size(width, height)
            .onGloballyPositioned { c ->
                fun update(c: androidx.compose.ui.layout.LayoutCoordinates) {
                    val o = c.positionInRoot()
                    target.cellRect = Rect(o.x, o.y, o.x + wPx, o.y + hPx)
                    target.iconRect = target.cellRect
                }
                update(c)
                target.refresh = { if (c.isAttached) update(c) }
            }
            .graphicsLayer {
                alpha = if (drag.key == id) 0f else 1f
                rotationZ = if (edit) jiggle.value * 0.5f else 0f
                scaleX = pressScale; scaleY = pressScale
            }
            .cachedLayer(edit)
            .semantics { contentDescription = widgetLabel }
            .then(
                if (onClick != null && !edit) Modifier.clickable(interactionSource = interaction, indication = null, onClickLabel = "Aç") { onClick(target.cellRect) }
                else Modifier
            )
    ) { content() }
}


/**
 * [on] iken içerik kendi katmanına bir kez çizilir (döndürme/ölçek bu hazır görüntüye uygulanır). Katman, içeriğin
 * çevresinde [margin] kadar taşma payıyla açılır: yazı gölgeleri ve simgeden taşan rozetler kesilmez. Yerleşim
 * değişmez (dışarıya özgün boyut bildirilir).
 */
internal fun Modifier.cachedLayer(on: Boolean, margin: androidx.compose.ui.unit.Dp = 18.dp): Modifier =
    if (!on) this else this
        .layout { m, c ->
            // Dışarıya özgün boyut; içteki geniş katman taşma payı kadar sola/yukarı kaydırılır.
            val mp = margin.roundToPx()
            val p = m.measure(c.offset(mp * 2, mp * 2))
            val w = (p.width - mp * 2).coerceAtLeast(0); val h = (p.height - mp * 2).coerceAtLeast(0)
            layout(w, h) { p.place(-mp, -mp) }
        }
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .layout { m, c ->
            // Katman içeriğin her yanında taşma payı kadar geniş.
            val mp = margin.roundToPx()
            val p = m.measure(c.offset(-mp * 2, -mp * 2))
            layout(p.width + mp * 2, p.height + mp * 2) { p.place(mp, mp) }
        }
