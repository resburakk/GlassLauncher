package dev.glass.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.graphics.drawable.toBitmap
import dev.glass.launcher.data.AppCategorizer
import dev.glass.launcher.data.WidgetItem
import dev.glass.launcher.data.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Sistem widget'ı ekleme akışı (bağlama izni + yapılandırma ekranı) Activity'de yürür. */
interface WidgetRequester {
    fun addSystemWidget(info: AppWidgetProviderInfo, spanX: Int, spanY: Int)
    fun reconfigure(appWidgetId: Int)
}

val LocalWidgetRequester = staticCompositionLocalOf<WidgetRequester?> { null }

/** Launcher'ın kendi widget'ları için iOS boyutları: küçük 2×2, orta 4×2, büyük 4×4. */
enum class WidgetSize(val title: String, val spanX: Int, val spanY: Int) {
    Small("Küçük", 2, 2), Medium("Orta", 4, 2), Large("Büyük", 4, 4)
}

/** Galeride bir widget ve seçilebilecek boyutları. */
internal class GalleryWidget(
    val key: String,
    val title: String,
    val info: AppWidgetProviderInfo?,
    val builtin: WidgetKind?,
    val sizes: List<Span>,
)

/** Galeride bir uygulama (widget'larıyla). */
internal class GalleryApp(
    val key: String,
    val name: String,
    val icon: ImageBitmap?,
    val widgets: List<GalleryWidget>,
    val isGlass: Boolean = false,
)

private val GlassBuiltins = listOf(
    Triple("b:clock", "Saat", WidgetKind.DigitalClock),
    Triple("b:analog", "Analog Saat", WidgetKind.AnalogClock),
    Triple("b:cal", "Takvim", WidgetKind.Calendar),
    Triple("b:bat", "Pil", WidgetKind.Battery),
    Triple("b:photo", "Fotoğraf", WidgetKind.Photo),
)

/**
 * iOS 26 gibi Liquid Glass widget galerisi: alttan açılan cam panel, arama ve uygulamalara göre liste.
 * Bir uygulamaya dokununca widget'ları, gerçek ön izleme görselleriyle, boyut boyut kaydırılan kartlarda
 * gösterilir; "Widget Ekle" ile eklenir. Sistem (Honor vb.) widget'ları olduğu gibi kullanılır.
 * Launcher'ın kendi widget'ları en altta "Glass" bölümündedir.
 */
@Composable
fun WidgetPicker(vm: LauncherViewModel, strength: Float, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val requester = LocalWidgetRequester.current
    val hm = LocalHomeMetrics.current
    val density = LocalDensity.current.density
    var query by remember { mutableStateOf("") }
    var openApp by remember { mutableStateOf<String?>(null) }

    val cacheKey = hm?.let { "${it.columns}x${it.rows}" }
    val apps by produceState(vm.widgetGalleryCache?.takeIf { it.first == cacheKey }?.second, hm) {
        val m = hm ?: return@produceState
        if (value != null) return@produceState // önceden yüklendi: anında açılır
        val pm = ctx.packageManager
        val iconPx = (48 * density).toInt()
        value = withContext(Dispatchers.Default) {
            val system = vm.widgetHost.providers()
                .groupBy { it.provider.packageName to it.profile }
                .map { (k, infos) ->
                    val (pkg, profile) = k
                    val appName = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    val icon = runCatching { pm.getApplicationIcon(pkg).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull()
                    GalleryApp(
                        key = "$pkg#${profile.hashCode()}",
                        name = appName,
                        icon = icon,
                        widgets = infos.map { info ->
                            GalleryWidget(
                                key = info.provider.flattenToString() + "#" + info.profile.hashCode(),
                                title = runCatching { info.loadLabel(pm) }.getOrNull() ?: appName,
                                info = info, builtin = null,
                                sizes = WidgetSizing.sizeOptions(info, m, density),
                            )
                        },
                    )
                }
                .sortedBy { AppCategorizer.normalize(it.name) }
            val glass = GalleryApp(
                key = "glass", name = "Glass Launcher", icon = null, isGlass = true,
                widgets = GlassBuiltins.map { (k, t, kind) ->
                    GalleryWidget(
                        k, t, null, kind,
                        if (kind == WidgetKind.Photo) listOf(Span(2, 2), Span(4, 2), Span(4, 4))
                        else WidgetSize.entries.map { Span(it.spanX, it.spanY) }.filter { it.x <= m.columns && it.y <= m.rows },
                    )
                },
            )
            system + glass
        }.also { vm.widgetGalleryCache = cacheKey!! to it }
    }

    fun add(w: GalleryWidget, s: Span) {
        if (w.builtin != null) {
            vm.addWidget(WidgetItem(UUID.randomUUID().toString(), w.builtin, s.x, s.y))
            onClose()
        } else if (w.info != null && requester != null) {
            requester.addSystemWidget(w.info, s.x, s.y)
            onClose()
        }
    }

    BackHandler(openApp != null) { openApp = null }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp)
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            BlurBackdrop(38.dp, Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .liquidGlass(shape, strength, 20.dp, panel = true)
                    .background(Color(0x8C141416), shape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                // Tutamak
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                }
                val list = apps
                AnimatedContent(
                    targetState = openApp,
                    transitionSpec = {
                        if (targetState != null) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                        else (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                    },
                    label = "gallery",
                ) { appKey ->
                    val app = list?.firstOrNull { it.key == appKey }
                    if (app == null) {
                        GalleryList(list, query, { query = it }, onOpen = { openApp = it }, onClose = onClose)
                    } else {
                        GalleryDetail(app, hm, onBack = { openApp = null }, onAdd = ::add)
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryList(
    apps: List<GalleryApp>?,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Widget'lar", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            GlassCircle(onClose) { GlyphIcon(Glyph.Close, Color.White, 14.dp) }
        }
        // Arama alanı (cam hap)
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .border(0.8.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(21.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Search, Color.White.copy(alpha = 0.7f), 16.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Widget Ara", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = AppFont),
                    cursorBrush = SolidColor(Color(0xFF0A84FF)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (apps == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Widget'lar yükleniyor…", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }
            return
        }
        val q = AppCategorizer.normalize(query.trim())
        val shown = if (q.isEmpty()) apps else apps.filter { a ->
            AppCategorizer.normalize(a.name).contains(q) || a.widgets.any { AppCategorizer.normalize(it.title).contains(q) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            items(shown, key = { it.key }) { a ->
                if (a.isGlass) {
                    Text(
                        "GLASS", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 6.dp),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onOpen(a.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppBadge(a, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (a.widgets.size == 1) a.widgets[0].title else "${a.widgets.size} widget",
                            color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("›", color = Color.White.copy(alpha = 0.4f), fontSize = 22.sp)
                }
            }
        }
    }
}

/** Bir uygulamanın widget'ları: her widget × boyut bir kart; yatay kaydırılır, altta "Widget Ekle". */
@Composable
private fun GalleryDetail(app: GalleryApp, hm: HomeMetrics?, onBack: () -> Unit, onAdd: (GalleryWidget, Span) -> Unit) {
    val pages = remember(app.key) { app.widgets.flatMap { w -> w.sizes.map { w to it } } }
    val pager = rememberPagerState { pages.size }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassCircle(onBack) { Text("‹", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(10.dp))
            AppBadge(app, 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(app.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (pages.isEmpty()) return
        val cur = pages.getOrNull(pager.currentPage) ?: pages[0]
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cur.first.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${cur.second.title} · ${cur.second.x}×${cur.second.y} hücre",
                color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val maxW = maxWidth - 64.dp
            val maxH = maxHeight - 32.dp
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
                val (w, s) = pages[i]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WidgetPreviewCard(app, w, s, hm, maxW, maxH)
                }
            }
        }
        if (pages.size > 1) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { i ->
                    Box(
                        Modifier.padding(3.dp).size(7.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f))
                    )
                }
            }
        }
        Box(
            Modifier
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF0A84FF))
                .clickable { onAdd(cur.first, cur.second) },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Plus, Color.White, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text("Widget Ekle", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Ön izleme kartı: ana ekrandaki gerçek oranında (widgetWidth × widgetHeight) çizilir. Sistem widget'larında
 * uygulamanın verdiği ön izleme görseli, yoksa uygulama simgesi gösterilir.
 */
@Composable
private fun WidgetPreviewCard(app: GalleryApp, w: GalleryWidget, s: Span, hm: HomeMetrics?, maxW: Dp, maxH: Dp) {
    val ctx = LocalContext.current
    val realW = hm?.widgetWidth(s.x) ?: (80.dp * s.x)
    val realH = hm?.widgetHeight(s.y) ?: (80.dp * s.y)
    val k = minOf(maxW / realW, maxH / realH, 1.25f)
    val cw = realW * k
    val ch = realH * k
    val corner = widgetCorner(cw)
    val img by produceState<ImageBitmap?>(null, w.key) {
        val info = w.info ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { info.loadPreviewImage(ctx, ctx.resources.displayMetrics.densityDpi) }.getOrNull()?.let { d ->
                val iw = d.intrinsicWidth.takeIf { it > 0 } ?: 600
                val ih = d.intrinsicHeight.takeIf { it > 0 } ?: 600
                val sc = minOf(1f, 900f / maxOf(iw, ih))
                d.toBitmap((iw * sc).toInt().coerceAtLeast(1), (ih * sc).toInt().coerceAtLeast(1)).asImageBitmap()
            }
        }
    }
    val info = w.info
    val density = LocalDensity.current.density
    Box(
        Modifier.size(cw, ch).clip(RoundedCornerShape(corner)).background(Color.White.copy(alpha = 0.10f))
            .border(0.8.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        val b = img
        when {
            // Android 12+ widget'ları (Honor dahil) ön izlemeyi canlı yerleşim olarak verir: gerçek widget
            // görünümü ana ekrandaki boyutunda çizilip karta orantılı küçültülür.
            info != null && info.previewLayout != 0 -> {
                val (lw, lh, sc) = WidgetSizing.layoutSize(info, realW, realH, density)
                AndroidView(
                    factory = { c ->
                        // Ön izleme dokunulmaz: dokunuşlar kaydırmaya (sayfa değiştirmeye) gider.
                        object : android.widget.FrameLayout(c) {
                            override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = true
                            override fun onTouchEvent(event: android.view.MotionEvent?) = false
                        }.apply {
                            val hv = android.appwidget.AppWidgetHostView(c)
                            runCatching {
                                hv.setAppWidget(-1, info)
                                hv.updateAppWidget(android.widget.RemoteViews(info.provider.packageName, info.previewLayout))
                            }
                            addView(hv, android.widget.FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    modifier = Modifier.requiredSize(lw, lh).graphicsLayer { scaleX = sc * k; scaleY = sc * k },
                )
            }
            // Ön izleme görseli kendi oranında, kesilmeden sığdırılır.
            b != null -> Image(b, w.title, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
            w.builtin != null -> BuiltinPreview(w.builtin, cw)
            else -> AppBadge(app, (minOf(cw, ch) * 0.4f))
        }
    }
}

@Composable
private fun BuiltinPreview(kind: WidgetKind, w: Dp) {
    val big = (w.value * 0.16f).coerceIn(18f, 46f).sp
    when (kind) {
        WidgetKind.DigitalClock -> Text("12:30", color = Color.White, fontSize = big, fontWeight = FontWeight.SemiBold)
        WidgetKind.AnalogClock -> GlyphIcon(Glyph.Globe, Color.White, w * 0.35f)
        WidgetKind.Calendar -> Text("14", color = Color(0xFFFF453A), fontSize = big, fontWeight = FontWeight.Bold)
        WidgetKind.Battery -> Text("%85", color = Color(0xFF30D158), fontSize = big, fontWeight = FontWeight.Bold)
        WidgetKind.Photo -> GlyphIcon(Glyph.Wallpaper, Color.White, w * 0.3f)
        else -> GlyphIcon(Glyph.Widget, Color.White, w * 0.3f)
    }
}

@Composable
private fun AppBadge(a: GalleryApp, size: Dp) {
    val icon = a.icon
    if (icon != null) {
        Image(icon, a.name, Modifier.size(size).clip(RoundedCornerShape(size * 0.23f)))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.23f)).background(Color(0xFF0A84FF)),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Widget, Color.White, size * 0.55f) }
    }
}

@Composable
private fun GlassCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(0.8.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
