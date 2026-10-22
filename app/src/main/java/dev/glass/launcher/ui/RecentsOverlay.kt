package dev.glass.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.RecentApps
import dev.glass.launcher.service.LockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Launcher'ın kendi son kullanılanlar ekranı (iOS 26 uygulama değiştirici gibi).
 *
 * Kartlar yatayda üst üste dizilir: en son kullanılan ortada, eskiler solda (sağa kaydırınca gelir); yeni kart
 * eskisinin üstünde durur. Karta dokununca uygulama kartın yerinden büyüyerek açılır; kart yukarı atılınca
 * kapanır; boşluğa dokununca ana ekrana dönülür. Önizlemeler erişilebilirlik hizmetinin aldığı ekran
 * görüntüleridir; yoksa kartta uygulamanın simgesi görünür.
 */
@Composable
fun RecentsOverlay(
    vm: LauncherViewModel,
    allApps: List<AppInfo>,
    fallback: List<AppInfo>,
    iconGlass: Boolean,
    strength: Float,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var hasUsage by remember { mutableStateOf(RecentApps.hasUsageAccess(ctx)) }
    val previews = LockService.running
    // Liste açılışta bir kez okunur (kapatılan kartlar anında listeden çıkar).
    val apps = remember { mutableStateListOf<AppInfo>() }
    LaunchedEffect(allApps, hasUsage) {
        val list = withContext(Dispatchers.Default) { RecentApps.load(ctx, allApps, fallback) }
        apps.clear(); apps.addAll(list)
    }
    val thumbVersion by RecentApps.thumbVersion.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = LocalPt.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val cardW = w * 0.70f
        val cardH = cardW * (h / w)
        // Kartlar arası yatay adım (iOS'taki gibi büyük ölçüde üst üste).
        val step = cardW * 0.62f
        val top = (h - cardH) / 2f + with(density) { 14.dp.toPx() }
        val cornerPx = cardW / w * with(density) { 52.dp.toPx() }
        // Kartın üstündeki simge + ad satırının yüksekliği.
        val labelPx = with(density) { (u * 30f + 8.dp).toPx() }
        val scroll = remember { Animatable(0f) }
        // Kartın yukarı atılma miktarı (px), anahtar = uygulama anahtarı.
        val lift = remember { mutableStateMapOf<String, Float>() }
        val maxIndex = (apps.size - 1).coerceAtLeast(0)

        fun cardLeft(i: Int, s: Float): Float = w / 2f - cardW / 2f - (i - s) * step
        fun hit(x: Float, y: Float): Int {
            // Üstte duran kart (en küçük sıra) önce.
            for (i in apps.indices) {
                val l = cardLeft(i, scroll.value)
                val t = top + (lift[apps[i].key] ?: 0f)
                if (x in l..(l + cardW) && y in (t - labelPx)..(t + cardH)) return i
            }
            return -1
        }

        fun dismiss(i: Int) {
            val app = apps.getOrNull(i) ?: return
            RecentApps.dismiss(ctx, app.packageName)
            apps.removeAt(i)
            lift.remove(app.key)
            scope.launch { scroll.snapTo(scroll.value.coerceIn(0f, (apps.size - 1).coerceAtLeast(0).toFloat())) }
            if (apps.isEmpty()) onClose()
        }

        fun open(i: Int) {
            val app = apps.getOrNull(i) ?: return
            val l = cardLeft(i, scroll.value)
            vm.launchFrom(app, view, Rect(l, top, l + cardW, top + cardH))
            onClose()
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(apps.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val idx = hit(down.position.x, down.position.y)
                        val slop = viewConfiguration.touchSlop
                        var axis = 0 // 0: henüz yok, 1: yatay (kaydırma), 2: dikey (kapatma)
                        var dx = 0f; var dy = 0f
                        val vt = VelocityTracker()
                        vt.addPosition(down.uptimeMillis, down.position)
                        scope.launch { scroll.stop() }
                        var startScroll = scroll.value
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            vt.addPosition(c.uptimeMillis, c.position)
                            if (!c.pressed) break
                            val d = c.positionChange()
                            dx += d.x; dy += d.y
                            if (axis == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                                axis = if (abs(dy) > abs(dx) && dy < 0 && idx >= 0) 2 else 1
                                startScroll = scroll.value
                            }
                            when (axis) {
                                1 -> {
                                    // Sağa sürüklemek eski kartları getirir; uçlarda direnç.
                                    var s = startScroll + dx / step
                                    if (s < 0f) s *= 0.3f
                                    if (s > maxIndex) s = maxIndex + (s - maxIndex) * 0.3f
                                    scope.launch { scroll.snapTo(s) }
                                    c.consume()
                                }
                                2 -> {
                                    val key = apps.getOrNull(idx)?.key
                                    if (key != null) lift[key] = minOf(0f, dy)
                                    c.consume()
                                }
                            }
                        }
                        val v = vt.calculateVelocity()
                        when (axis) {
                            0 -> if (idx >= 0) open(idx) else onClose()
                            1 -> {
                                val target = (scroll.value + (v.x / step) * 0.18f).roundToInt()
                                    .coerceIn(0, maxIndex).toFloat()
                                scope.launch {
                                    scroll.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 320f), v.x / step)
                                }
                            }
                            2 -> {
                                val app = apps.getOrNull(idx)
                                val l = app?.let { lift[it.key] } ?: 0f
                                if (app != null) {
                                    if (l < -cardH * 0.28f || v.y < -1800f) {
                                        scope.launch {
                                            val a = Animatable(l)
                                            a.animateTo(-h, tween(180)) { lift[app.key] = value }
                                            dismiss(apps.indexOf(app))
                                        }
                                    } else {
                                        scope.launch {
                                            val a = Animatable(l)
                                            a.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) { lift[app.key] = value }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // Eskiler önce (altta), yeniler sonra (üstte) çizilir.
            for (i in apps.indices.reversed()) {
                val app = apps[i]
                androidx.compose.runtime.key(app.key) {
                val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName, thumbVersion) {
                    value = withContext(Dispatchers.IO) { RecentApps.loadThumb(ctx, app.packageName)?.asImageBitmap() }
                }
                Box(
                    Modifier
                        .graphicsLayer {
                            val s = scroll.value
                            val l = cardLeft(i, s)
                            val ly = lift[app.key] ?: 0f
                            translationX = l
                            // Kartın üstündeki ad satırı kadar yukarıdan başlar (kartın kendisi [top]'ta).
                            translationY = top + ly - labelPx
                            // Ortadan uzaklaştıkça çok hafif küçülür; yukarı atılırken söner.
                            val dist = abs(i - s).coerceAtMost(2f)
                            val k = 1f - 0.035f * dist
                            scaleX = k; scaleY = k
                            alpha = (1f + ly / (h * 0.6f)).coerceIn(0f, 1f)
                            // Sağa kaydıkça (ekran dışına) kart görünmez.
                            if (l > w || l + cardW < 0f) alpha = 0f
                        }
                ) {
                    Column {
                        // Kartın üstünde simge + ad (iOS gibi).
                        Row(
                            Modifier.offset(y = 0.dp).padding(start = 4.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIconImage(app, vm, u * 30f, iconGlass, strength, badge = false)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                app.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = AppFont,
                            )
                        }
                        val shape = RoundedCornerShape(with(density) { cornerPx.toDp() })
                        Box(
                            Modifier
                                .size(with(density) { cardW.toDp() }, with(density) { cardH.toDp() })
                                .shadow(24.dp, shape, clip = false)
                                .clip(shape)
                                .background(Color(0xFF1C1C1E)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val t = thumb
                            if (t != null) {
                                Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                // Önizleme yok (ilk açılış, güvenli ekran): uygulamanın simgesi.
                                AppIconImage(app, vm, u * 84f, iconGlass, strength)
                            }
                        }
                    }
                }
                }
            }
            if (apps.isEmpty()) {
                Text(
                    "Son kullanılan uygulama yok", color = Color(0xCCFFFFFF), fontSize = 17.sp,
                    fontFamily = AppFont, modifier = Modifier.align(Alignment.Center),
                )
            }
            // Eksik izinler: kısa açıklama + ayarı açan düğme.
            if (!hasUsage || !previews) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 18.dp, start = 24.dp, end = 24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!hasUsage) PermissionChip(
                        "Tüm son uygulamaları görmek için Kullanım erişimi izni ver",
                    ) {
                        runCatching { ctx.startActivity(RecentApps.usageAccessIntent(ctx)) }
                            .onFailure {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                    }
                    if (!previews) PermissionChip(
                        "Kart önizlemeleri için Erişilebilirlik > Glass Launcher hizmetini aç",
                    ) {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }
        }
    }
    // Ayarlardan dönülünce izin durumu yenilenir.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.collect {
            if (it == androidx.lifecycle.Lifecycle.State.RESUMED) hasUsage = RecentApps.hasUsageAccess(ctx)
        }
    }
}

@Composable
private fun PermissionChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .liquidGlass(RoundedCornerShape(18.dp), 1f, 0.dp, overlay = true)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, fontFamily = AppFont)
    }
}
