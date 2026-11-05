package dev.glass.launcher.ui

import androidx.compose.ui.graphics.drawscope.translate

import androidx.compose.ui.input.nestedscroll.nestedScroll

import androidx.compose.ui.platform.LocalDensity


import androidx.compose.foundation.gestures.detectVerticalDragGestures

import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.AppCategorizer
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.DeviceHit
import dev.glass.launcher.data.DeviceHitKind
import dev.glass.launcher.data.DeviceSearch
import dev.glass.launcher.data.SearchRanker
import kotlinx.coroutines.delay

/** Arama çubuğundaki yazının hafif gölgesi (okunurluk için, abartısız). */
private val SearchTextShadow: androidx.compose.ui.graphics.Shadow
    get() = if (HomeInk.light) androidx.compose.ui.graphics.Shadow.None else androidx.compose.ui.graphics.Shadow(Color(0x55000000), androidx.compose.ui.geometry.Offset(0f, 1f), 3f)

/**
 * iOS tarzı arama: cam arama kutusu (temizle / mikrofon), adlı 4'lü öneri paneli (dock ölçüsünde, dört
 * kenarı eşit boşluklu), "En İyi Sonuç" + "Uygulamalar", izin verildiyse kişiler / mesajlar / dosyalar,
 * en altta internette ve Play Store'da arama. Eşleşen harfler kalın gösterilir.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchOverlay(
    vm: LauncherViewModel,
    allApps: List<AppInfo>,
    suggestions: List<AppInfo>,
    strength: Float,
    iconGlass: Boolean,
    onClose: () -> Unit,
    /** Açılış/kapanış geçişinin görünürlüğü (0..1); öneri panelinin arka plan bulanıklığı buna uyar. */
    visibility: () -> Float = { 1f },
) {
    val ctx = LocalContext.current
    val st = LocalLauncherSettings.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val view = LocalView.current
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    LaunchedEffect(Unit) {
        // Kutu ekranın altında; yerleşim tamamlanınca odaklanıp klavyeyi aç. Klavye gelmezse birkaç kez
        // yeniden istenir (bazı cihaz/klavyelerde ilk istek yok sayılabiliyor).
        delay(80)
        focus.requestFocus()
        repeat(5) {
            delay(120)
            if (imeVisible) return@LaunchedEffect
            keyboard?.show()
            runCatching {
                val imm = view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // Türkçe karakter duyarsız, sıralı çevrimdışı arama ("cevi" → "Çeviri", "gm" → "Google Maps").
    val results = remember(query, allApps) { SearchRanker.rank(query, allApps) { it.label } }

    // Cihaz içi arama izinleri
    var permTick by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    val missing = remember(permTick, st.searchContacts, st.searchMessages, st.searchFiles) {
        DeviceSearch.missingPermissions(ctx, st.searchContacts, st.searchMessages, st.searchFiles)
    }
    val device by produceState(emptyList<DeviceHit>(), query, permTick, st.searchContacts, st.searchMessages, st.searchFiles) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        delay(150) // yazarken her harfte sorgu yapma
        value = DeviceSearch.search(ctx, query.trim(), st.searchContacts, st.searchMessages, st.searchFiles)
    }

    // Sesle arama: telefonun kendi konuşma tanıma ekranı açılır (launcher ek izin istemez).
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }

    // Son aramalar (iOS "Recent Searches"): bir sonuçla sonuçlanan aramalar saklanır, en yeni üstte, en çok 5 tane.
    val recentPrefs = remember { ctx.getSharedPreferences("search_recent", android.content.Context.MODE_PRIVATE) }
    var recent by remember { mutableStateOf(recentPrefs.getString("list", "").orEmpty().split('\n').filter { it.isNotBlank() }) }
    fun saveRecent() {
        val q = query.trim()
        if (q.isEmpty()) return
        recent = (listOf(q) + recent.filter { !it.equals(q, ignoreCase = true) }).take(5)
        recentPrefs.edit().putString("list", recent.joinToString("\n")).apply()
    }
    fun clearRecent() { recent = emptyList(); recentPrefs.edit().remove("list").apply() }

    fun close() { keyboard?.hide(); onClose() }
    fun open(app: AppInfo) { saveRecent(); keyboard?.hide(); vm.launch(app); onClose() }
    fun launch(i: Intent) {
        saveRecent()
        keyboard?.hide()
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        onClose()
    }
    fun webSearch(q: String) {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        if (i.resolveActivity(ctx.packageManager) != null) launch(i)
        else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
    }
    fun storeSearch(q: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q) + "&c=apps"))
        if (i.resolveActivity(ctx.packageManager) != null) launch(i)
        else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(q) + "&c=apps")))
    }

    // Nötr cam: duvar kâğıdının rengini yumuşatan hafif gri-beyaz katman (iOS'taki gibi daha nötr).
    val neutral = if (st.searchNeutral) Modifier.background(Color(0x38E5E5EA)) else Modifier

    val fieldSize = st.searchFieldTextSize.sp

    // ---------------------------------------------------------------- iOS 26 düzeni (Ayarlar > Arama)
    if (st.searchIos) {
        // iOS 26 Spotlight: durum çubuğu gizlenir, koyu arama çubuğu en üstte (Dinamik Ada hizasında).
        val activity = ctx as? android.app.Activity
        androidx.compose.runtime.DisposableEffect(activity) {
            val w = activity?.window
            val c = w?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
            c?.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            onDispose { c?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) }
        }
        val top = results.firstOrNull()
        // Otomatik tamamlama: en iyi uygulama yazılanla başlıyorsa çubukta "Sağlık — Aç" (kalan harfler gri).
        val qn = AppCategorizer.normalize(query.trim())
        val completion = if (top != null && qn.isNotEmpty() && query.isNotBlank()) {
            val ln = AppCategorizer.normalize(top.label)
            if (ln.startsWith(qn) && ln.length == top.label.length) top.label.substring(query.trimEnd().length.coerceAtMost(top.label.length)) + " — Aç"
            else " — " + top.label
        } else ""
        val rb = rememberRubberBand()
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { close() }) }
                .rubberDrag(rb)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                // Arama çubuğu: neredeyse siyah hap, ince ışıklı kenar; açılışta yukarıdan iner.
                val dropPx = with(LocalDensity.current) { 40.dp.toPx() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .graphicsLayer {
                            val v = visibility().coerceIn(0f, 1f)
                            translationY = -(1f - (1f - (1f - v) * (1f - v))) * dropPx
                        }
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0xF2141416), Color(0xF7000000))))
                        .border(0.8.dp, Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF), Color(0x4DFFFFFF))), CircleShape)
                        .pointerInput(Unit) { detectTapGestures { focus.requestFocus(); keyboard?.show() } }
                        .padding(start = 26.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 21.sp, fontFamily = AppFont),
                        cursorBrush = SolidColor(Color.White),
                        // Tamamlama yazının hemen arkasında gri görünür (imleç yazının sonunda kalır).
                        visualTransformation = if (completion.isEmpty()) androidx.compose.ui.text.input.VisualTransformation.None
                        else androidx.compose.ui.text.input.VisualTransformation { t ->
                            val shown = buildAnnotatedString {
                                append(t.text)
                                // iOS: tamamlanan kısım seçili gibi açık bir zeminde.
                                withStyle(SpanStyle(color = Color(0xE6FFFFFF), background = Color(0x40FFFFFF))) { append(completion) }
                            }
                            androidx.compose.ui.text.input.TransformedText(shown, object : androidx.compose.ui.text.input.OffsetMapping {
                                override fun originalToTransformed(offset: Int) = offset
                                override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(t.text.length)
                            })
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            top?.let { open(it) } ?: if (query.isNotBlank()) webSearch(query.trim()) else Unit
                        }, onSearch = {
                            top?.let { open(it) } ?: if (query.isNotBlank()) webSearch(query.trim()) else Unit
                        }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("Ara", color = Color(0x99FFFFFF), fontSize = 21.sp, fontFamily = AppFont, maxLines = 1)
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (top != null && query.isNotBlank()) {
                        // Yazarken sağda en iyi sonucun simgesi (dokununca açılır).
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable { open(top) }, contentAlignment = Alignment.Center) {
                            AppIconImage(top, vm, 34.dp, iconGlass, strength, badge = false)
                        }
                    } else {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable {
                                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ara")
                                runCatching { mic.launch(i) }
                            },
                            contentAlignment = Alignment.Center,
                        ) { GlyphIcon(Glyph.Mic, Color(0xCCFFFFFF), 22.dp) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().weight(1f, fill = false).graphicsLayer {
                        val v = visibility().coerceIn(0f, 1f)
                        alpha = v
                        translationY = rb.value - (1f - v) * dropPx * 0.6f
                    },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (query.isBlank()) {
                        // Boşken: çubuğun hemen altında 4 öneri (cam kart).
                        SuggestionsPanel(vm, suggestions.take(4), strength, iconGlass, true, neutral, visibility, ::open)
                    } else {
                        IosResults(
                            rb = rb, vm = vm, query = query.trim(), apps = results, device = device,
                            iconGlass = iconGlass, strength = strength, visibility = visibility,
                            showPermissionCard = missing.isNotEmpty() && st.searchDeviceCard,
                            onAskPermission = { permLauncher.launch(missing.toTypedArray()) },
                            onDismissCard = { vm.updateSettings { it.copy(searchDeviceCard = false) } },
                            onOpenApp = ::open,
                            onOpenHit = { launch(it.intent) },
                            onWeb = { webSearch(query.trim()) },
                            onStore = { storeSearch(query.trim()) },
                            onLaunch = { launch(it) },
                        )
                    }
                }
            }
        }
        return
    }
    // iOS 26 düzeni: arka plan hafif kararır; öneriler hep üstte, sonuçlar ortada, arama kutusu altta
    // klavyenin hemen üstünde (safeDrawing klavye yüksekliğini de kapsar).
    // iOS gibi esnek: boş alanda ya da sonuç listesinin başında/sonunda çekince öneriler ve sonuçlar direnç artarak
    // kayar, bırakınca yaylı geri döner (arama kutusu klavyenin üstünde sabit kalır).
    val rb = rememberRubberBand()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { close() }) }
            .rubberDrag(rb)
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            // iOS 26: öneriler yukarıdan aşağı yumuşakça iner (açılışta), kapanırken yukarı çekilir.
            val dropPx = with(LocalDensity.current) { 56.dp.toPx() }
            Column(Modifier.graphicsLayer {
                val v = visibility().coerceIn(0f, 1f)
                val e = 1f - (1f - v) * (1f - v) // yavaşlayarak yerine oturur
                translationY = -(1f - e) * dropPx + rb.value
            }) {
            // Başlık + sağda iOS'taki "Show More" gibi 4 ↔ 8 öneri geçişi (aşağı/yukarı çekmeyle aynı ayar).
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Öneriler", color = ink(Color(0xCCFFFFFF)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = AppFont, modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                if (suggestions.size > 4) {
                    val more = st.searchSuggestExpanded
                    Text(
                        if (more) "Daha Az" else "Daha Fazla",
                        color = ink(Color(0xE6FFFFFF)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.updateSettings { it.copy(searchSuggestExpanded = !more) } }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            SuggestionsPanel(vm, suggestions, strength, iconGlass, st.searchShowLabels, neutral, visibility, ::open)
            }
            Spacer(Modifier.height(10.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (query.isBlank() && recent.isNotEmpty()) {
                    // iOS gibi: başlık + "Temizle", altında ince çizgi ve büyüteçli son aramalar (dokununca yeniden aranır).
                    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = visibility().coerceIn(0f, 1f) }) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Son Aramalar", color = ink(Color(0xCCFFFFFF)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                fontFamily = AppFont, modifier = Modifier.padding(start = 6.dp).weight(1f),
                            )
                            Text(
                                "Temizle", color = ink(Color(0xE6FFFFFF)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { clearRecent() }.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(0.5.dp).background(ink(Color(0x40FFFFFF))))
                        recent.forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { query = r }
                                    .padding(horizontal = 6.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SearchGlyph(ink(Color(0xE6FFFFFF)), 18.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(r, color = ink(Color.White), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = SearchTextShadow))
                            }
                        }
                    }
                }
                if (query.isNotBlank()) {
                    Box(
                        if (st.searchResultsPanel) {
                            Modifier.liquidGlass(RoundedCornerShape(24.dp), strength * 0.8f, 0.dp, panel = true, overlay = true)
                                .glassBrightness(st.searchGlassBrightness).then(neutral)
                        } else Modifier
                    ) {
                        if (st.searchResultsPanel) {
                            BlurBackdrop(24.dp, Modifier.matchParentSize(), alpha = visibility)
                        }
                        ResultsList(
                            rb = rb,
                            vm = vm, query = query.trim(), apps = results, device = device,
                            iconGlass = iconGlass, strength = strength,
                            showPermissionCard = missing.isNotEmpty() && st.searchDeviceCard,
                            onAskPermission = { permLauncher.launch(missing.toTypedArray()) },
                            onDismissCard = { vm.updateSettings { it.copy(searchDeviceCard = false) } },
                            onOpenApp = ::open,
                            onOpenHit = { launch(it.intent) },
                            onWeb = { webSearch(query.trim()) },
                            onStore = { storeSearch(query.trim()) },
                            onLaunch = { launch(it) },
                        )
                    }
                }
            }

            // Arama kutusu: klavyenin üstünde, arada küçük bir boşlukla. "İptal" yok (iOS 26); × temizler,
            // boşluğa dokunmak veya geri hareketi kapatır.
            // Çubuk da arkasındaki gerçek bulanık sahneyi (duvar kâğıdı + simgeler) gösterir.
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).height(48.dp)) {
            BlurBackdrop(24.dp, Modifier.matchParentSize(), shape = CircleShape, alpha = visibility)
            Row(
                Modifier
                    .fillMaxSize()
                    // Arama camı ayarı (Genel / Dock ile eşit) [strength] içinde zaten uygulanmış.
                    .liquidGlass(CircleShape, strength, 0.dp, panel = true, overlay = true)
                    // Açık arka planda yazı okunsun diye çubuğun içi çok hafif koyulaşır.
                    .background(Color.Black.copy(alpha = 0.10f), CircleShape)
                    .pointerInput(Unit) { detectTapGestures { focus.requestFocus(); keyboard?.show() } }
                    .padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph(ink(Color(0xE6FFFFFF)), 18.dp)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    // Yazı biraz daha belirgin: hafif gölge (açık arka planda kaybolmasın).
                    textStyle = TextStyle(color = ink(Color.White), fontSize = fieldSize, fontFamily = AppFont, shadow = SearchTextShadow),
                    cursorBrush = SolidColor(ink(Color.White)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        results.firstOrNull()?.let { open(it) } ?: if (query.isNotBlank()) webSearch(query.trim()) else Unit
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Ara", color = ink(Color(0xD9FFFFFF)), fontSize = fieldSize, fontFamily = AppFont,
                                style = TextStyle(shadow = SearchTextShadow))
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    // Temizle (×)
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).clickable { query = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(ink(Color(0xCCFFFFFF))), contentAlignment = Alignment.Center) {
                            GlyphIcon(Glyph.Close, if (HomeInk.light) Color.White else Color(0xFF3A3A3C), 14.dp)
                        }
                    }
                } else {
                    // Mikrofon: sesle yazma
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).clickable {
                            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ara")
                            runCatching { mic.launch(i) }
                        },
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Mic, ink(Color(0xCCFFFFFF)), 20.dp) }
                }
            }
            }
        }
    }
}

/** Öneri paneli: ana ekrandaki dock ile aynı genişlik/köşe; simgelerin dört yanındaki boşluk eşit. */
@Composable
private fun SuggestionsPanel(
    vm: LauncherViewModel,
    suggestions: List<AppInfo>,
    strength: Float,
    iconGlass: Boolean,
    showLabels: Boolean,
    neutral: Modifier,
    visibility: () -> Float,
    onOpen: (AppInfo) -> Unit,
) {
    val hm = LocalHomeMetrics.current ?: return
    val st = LocalLauncherSettings.current
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val panelW = screenW - hm.dockMarginX * 2f
    // Yanlardaki boşluk = simgelerin dış kenarından panel kenarına; aynı boşluk üstte ve altta da kullanılır.
    val pad = hm.dockInnerStart
    val labelBlock: Dp = if (showLabels) 22.dp else 0.dp
    val cellH = hm.dockIcon + labelBlock
    // Aşağı çekince 8 öneri (iki sıra), yukarı çekince 4; son durum ayarlarda saklanır.
    val expanded = st.searchSuggestExpanded && suggestions.size > 4
    val rowGap = pad * 0.6f
    val motion = LocalMotion.current
    val panelH by androidx.compose.animation.core.animateDpAsState(
        pad * 2f + cellH + if (expanded) cellH + rowGap else 0.dp, motion.spring(damping = 0.8f, stiffness = 500f), label = "suggestH",
    )
    val secondRow by androidx.compose.animation.core.animateFloatAsState(if (expanded) 1f else 0f, motion.tween(200), label = "suggestRow2")
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
    val expandedNow by rememberUpdatedState(st.searchSuggestExpanded)
    val panelCorner = hm.dockH * st.searchSuggestCorner
    val panelShape = appShape(panelCorner)
    // Panel, arkasındaki GERÇEK görüntünün (bulanık duvar kâğıdı + bulanık simgeler) bir derece daha bulanık
    // kopyasını gösterir; böylece çevresindeki bulanık alanla aynı sahnenin devamı gibi durur.
    Box(Modifier.requiredWidth(panelW).height(panelH)) {
    BlurBackdrop(panelCorner, Modifier.matchParentSize(), shape = panelShape, alpha = visibility)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(threshold) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total > threshold && !expandedNow) vm.updateSettings { it.copy(searchSuggestExpanded = true) }
                        else if (total < -threshold && expandedNow) vm.updateSettings { it.copy(searchSuggestExpanded = false) }
                    },
                ) { change, dy -> change.consume(); total += dy }
            }
            // Gölgesiz: yarı saydam camın içinden gölge koyu bir dikdörtgen gibi görünüyordu.
            .liquidGlass(panelShape, strength * st.dockOpacity, 0.dp, panel = true, overlay = true)
            // Öneri paneli göze batacak kadar parlaktı: hafif koyu tonla biraz kısılır.
            .background(Color.Black.copy(alpha = 0.12f), panelShape)
    ) {
        suggestions.take(if (expanded || secondRow > 0f) 8 else 4).forEachIndexed { i, app ->
            val row = i / 4
            val col = i % 4
            Box(
                Modifier
                    .offset(x = pad + hm.dockPitch * col.toFloat(), y = pad + (cellH + rowGap) * row.toFloat())
                    .graphicsLayer { if (row == 1) { alpha = secondRow; translationY = (1f - secondRow) * -24f } }
            ) {
                AppCell(
                    app, vm, hm.dockIcon, hm.dockPitch, cellH, showLabels, iconGlass, strength,
                    labelSize = st.searchSuggestLabelSize, labelWeight = st.searchSuggestLabelWeight,
                ) { onOpen(app) }
            }
        }
    }
    }
}

@Composable
private fun ResultsList(
    rb: RubberBand,
    vm: LauncherViewModel,
    query: String,
    apps: List<AppInfo>,
    device: List<DeviceHit>,
    iconGlass: Boolean,
    strength: Float,
    showPermissionCard: Boolean,
    onAskPermission: () -> Unit,
    onDismissCard: () -> Unit,
    onOpenApp: (AppInfo) -> Unit,
    onOpenHit: (DeviceHit) -> Unit,
    onWeb: () -> Unit,
    onStore: () -> Unit,
    onLaunch: (Intent) -> Unit,
) {
    val st = LocalLauncherSettings.current
    // Hızlı cevaplar (hesap, birim çevirme, ayarlar): çevrimdışı, yazarken anında.
    val quick = remember(query) { dev.glass.launcher.data.QuickAnswers.compute(query) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(query) { mutableStateOf(false) }
    val rowSize = st.searchResultTextSize.sp
    val topSize = (st.searchResultTextSize + 1f).sp
    val rowWeight = fontWeightOf(st.searchResultWeight)
    val contacts = device.filter { it.kind == DeviceHitKind.Contact }
    val messages = device.filter { it.kind == DeviceHitKind.Message }
    val files = device.filter { it.kind == DeviceHitKind.File }
    LazyColumn(
        // Alt kenarda yumuşak solma: liste klavyenin üstünde kesik görünmez.
        Modifier
            .nestedScroll(rb.connection)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                // Esneme yalnızca çizimde uygulanır (listenin dokunma alanı kaymaz, titreme olmaz).
                translate(0f, rb.value) { this@drawWithContent.drawContent() }
                val h = 28.dp.toPx()
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - h, endY = size.height),
                    topLeft = Offset(0f, size.height - h), size = Size(size.width, h), blendMode = BlendMode.DstIn,
                )
            },
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
    ) {
        quick.filterIsInstance<dev.glass.launcher.data.QuickAnswer.Value>().firstOrNull()?.let { v ->
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { clip.setText(AnnotatedString(v.result.substringBefore(' ').ifEmpty { v.result })); copied = true }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(v.expression + " =", color = ink(Color(0xB3FFFFFF)), fontSize = rowSize, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v.result, color = ink(Color.White), fontSize = 34.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (copied) "Kopyalandı" else "Kopyalamak için dokunun", color = ink(Color(0x99FFFFFF)), fontSize = 12.sp, fontFamily = AppFont)
                }
            }
        }
        val links = quick.filterIsInstance<dev.glass.launcher.data.QuickAnswer.SettingLink>()
        if (links.isNotEmpty()) {
            item { SectionHeader("Ayarlar") }
            links.forEachIndexed { i, l ->
                if (i > 0) item { Hairline() }
                item { ActionRow(Glyph.Gear, l.title) { onLaunch(l.intent) } }
            }
        }
        apps.firstOrNull()?.let { top ->
            item { SectionHeader("En İyi Sonuç") }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenApp(top) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconImage(top, vm, 48.dp, iconGlass, strength)
                    Spacer(Modifier.width(14.dp))
                    Text(highlight(top.label, query), color = ink(Color.White), fontSize = topSize, fontWeight = rowWeight, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (apps.size > 1) {
            item { SectionHeader("Uygulamalar") }
            itemsIndexed(apps.drop(1).take(40), key = { _, a -> a.key }) { i, app ->
                Column {
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenApp(app) }.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconImage(app, vm, 34.dp, iconGlass, strength)
                        Spacer(Modifier.width(12.dp))
                        Text(highlight(app.label, query), color = ink(Color.White), fontSize = rowSize, fontWeight = rowWeight, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        deviceSection("Kişiler", Glyph.Person, contacts, query, onOpenHit)
        deviceSection("Mesajlar", Glyph.Message, messages, query, onOpenHit)
        deviceSection("Dosyalar", Glyph.Doc, files, query, onOpenHit)
        if (showPermissionCard) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ink(Color(0x1FFFFFFF)))
                        .padding(14.dp)
                ) {
                    Text("Kişiler, mesajlar ve dosyalar da aransın mı?", color = ink(Color.White), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "İzin verirsen bunlar yalnızca telefonunda, çevrimdışı aranır; hiçbir şey dışarı gönderilmez.",
                        color = ink(Color(0xCCFFFFFF)), fontSize = 13.sp, fontFamily = AppFont,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text(
                            "İzin ver", color = Color(0xFF64B5FF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
                            modifier = Modifier.clickable(onClick = onAskPermission).padding(vertical = 4.dp, horizontal = 2.dp),
                        )
                        Spacer(Modifier.width(22.dp))
                        Text(
                            "Şimdi değil", color = ink(Color(0xCCFFFFFF)), fontSize = 15.sp, fontFamily = AppFont,
                            modifier = Modifier.clickable(onClick = onDismissCard).padding(vertical = 4.dp, horizontal = 2.dp),
                        )
                    }
                }
            }
        }
        item { SectionHeader("Daha Fazla") }
        item { ActionRow(Glyph.Globe, "“$query” için internette ara", onWeb) }
        item { Hairline() }
        item { ActionRow(Glyph.Bag, "“$query” için Play Store'da ara", onStore) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.deviceSection(
    title: String, glyph: Glyph, hits: List<DeviceHit>, query: String, onOpen: (DeviceHit) -> Unit,
) {
    if (hits.isEmpty()) return
    item { SectionHeader(title) }
    itemsIndexed(hits) { i, hit ->
        Column {
        if (i > 0) Hairline()
        Row(
            Modifier.fillMaxWidth().clickable { onOpen(hit) }.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(ink(Color(0x33FFFFFF))), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, ink(Color.White), 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(highlight(hit.title, query), color = ink(Color.White), fontSize = LocalLauncherSettings.current.searchResultTextSize.sp, fontWeight = fontWeightOf(LocalLauncherSettings.current.searchResultWeight), fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(highlight(hit.subtitle, query), color = ink(Color(0xB3FFFFFF)), fontSize = 13.sp, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title, color = ink(Color(0xB3FFFFFF)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(0.5.dp).background(ink(Color(0x2EFFFFFF))))
}

@Composable
private fun ActionRow(glyph: Glyph, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { GlyphIcon(glyph, ink(Color.White), 22.dp) }
        Spacer(Modifier.width(12.dp))
        Text(title, color = ink(Color.White), fontSize = LocalLauncherSettings.current.searchResultTextSize.sp, fontWeight = fontWeightOf(LocalLauncherSettings.current.searchResultWeight), fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Aranan metne uyan kısmı kalın gösterir (Türkçe karakter duyarsız). */
private fun highlight(text: String, query: String): AnnotatedString {
    val n = AppCategorizer.normalize(text)
    val q = AppCategorizer.normalize(query)
    if (q.isEmpty() || n.length != text.length) return AnnotatedString(text)
    val i = n.indexOf(q)
    if (i < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, i))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink(Color.White))) { append(text.substring(i, i + q.length)) }
        append(text.substring(i + q.length))
    }
}

/**
 * Arama açıkken arka plana uygulanan toplam karartma: ana ekran perdesi (%28) + arama katmanı (%18) ≈ %41.
 * Öneri paneli ve arama çubuğu, camlarında duvar kâğıdını karartılmadan gösterdiği için aynı oranda
 * koyulaştırılır; böylece çevreleriyle uyumlu, dock ile aynı ayarda görünürler.
 */


/**
 * iOS 26 Spotlight sonuçları: tek büyük cam kart. Üstte uygulamalar büyük simgeleriyle (altında "Uygulamalar"),
 * sonra satırlar (simge, kalın başlık, gri alt başlık) ve her grubun ALTINDA gri başlığı (iOS'taki gibi), sağda
 * isteğe bağlı bağlantı ("Uygulamada Ara").
 */
@Composable
private fun IosResults(
    rb: RubberBand,
    vm: LauncherViewModel,
    query: String,
    apps: List<AppInfo>,
    device: List<DeviceHit>,
    iconGlass: Boolean,
    strength: Float,
    visibility: () -> Float,
    showPermissionCard: Boolean,
    onAskPermission: () -> Unit,
    onDismissCard: () -> Unit,
    onOpenApp: (AppInfo) -> Unit,
    onOpenHit: (DeviceHit) -> Unit,
    onWeb: () -> Unit,
    onStore: () -> Unit,
    onLaunch: (Intent) -> Unit,
) {
    val hm = LocalHomeMetrics.current
    val ctx = LocalContext.current
    val quick = remember(query) { dev.glass.launcher.data.QuickAnswers.compute(query) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(query) { mutableStateOf(false) }
    val contacts = device.filter { it.kind == DeviceHitKind.Contact }
    val messages = device.filter { it.kind == DeviceHitKind.Message }
    val files = device.filter { it.kind == DeviceHitKind.File }
    val links = quick.filterIsInstance<dev.glass.launcher.data.QuickAnswer.SettingLink>()
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth()) {
        BlurBackdrop(30.dp, Modifier.matchParentSize(), shape = shape, alpha = visibility)
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .liquidGlass(shape, strength, 0.dp, panel = true, overlay = true)
                .background(Color.White.copy(alpha = 0.06f), shape)
                .nestedScroll(rb.connection)
                .pointerInput(Unit) { detectTapGestures { } },
            contentPadding = PaddingValues(top = 14.dp, bottom = 10.dp),
        ) {
            quick.filterIsInstance<dev.glass.launcher.data.QuickAnswer.Value>().firstOrNull()?.let { v ->
                item {
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { clip.setText(AnnotatedString(v.result.substringBefore(' ').ifEmpty { v.result })); copied = true }
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    ) {
                        Text(v.expression + " =", color = Color(0xB3FFFFFF), fontSize = 16.sp, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(v.result, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item { IosFooter(if (copied) "Kopyalandı" else "Hesap Makinesi") }
                item { IosDivider() }
            }
            if (apps.isNotEmpty()) {
                // Üstte en çok 4 uygulama, ana ekran simgesi boyutunda ve adlarıyla.
                item {
                    val icon = hm?.dockIcon ?: 60.dp
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.Start) {
                        apps.take(4).forEach { a ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onOpenApp(a) }.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AppIconImage(a, vm, icon, iconGlass, strength, badge = false)
                                Spacer(Modifier.height(5.dp))
                                Text(a.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = AppFont,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        repeat((4 - apps.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item { IosFooter("Uygulamalar") }
            }
            // Ayarlar: telefon ayar sayfaları + bulunan uygulamaların ayarları (iOS'taki "Sağlık — Uygulamalar").
            val appSettings = apps.take(2)
            if (links.isNotEmpty() || appSettings.isNotEmpty()) {
                item { IosDivider() }
                links.forEach { l ->
                    item { IosRow(title = l.title, subtitle = "Ayarlar", leading = { IosGlyphTile(Glyph.Gear, Color(0xFF8E8E93)) }) { onLaunch(l.intent) } }
                }
                appSettings.forEach { a ->
                    item {
                        IosRow(title = a.label, subtitle = "Uygulama ayarları", leading = { AppIconImage(a, vm, 44.dp, iconGlass, strength, badge = false) }) {
                            onLaunch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
                        }
                    }
                }
                item {
                    IosFooter("Ayarlar", action = "Uygulamada Ara") {
                        onLaunch(Intent(android.provider.Settings.ACTION_SETTINGS))
                    }
                }
            }
            listOf(Triple("Kişiler", Glyph.Person, contacts), Triple("Mesajlar", Glyph.Message, messages), Triple("Dosyalar", Glyph.Doc, files))
                .filter { it.third.isNotEmpty() }
                .forEach { (title, glyph, hits) ->
                    item { IosDivider() }
                    hits.forEach { h ->
                        item { IosRow(title = h.title, subtitle = h.subtitle, leading = { IosGlyphTile(glyph, Color(0xFF636366)) }) { onOpenHit(h) } }
                    }
                    item { IosFooter(title) }
                }
            item { IosDivider() }
            item { IosRow(title = "“$query”", subtitle = "İnternette ara", leading = { IosGlyphTile(Glyph.Globe, Color(0xFF0A84FF)) }, onClick = onWeb) }
            item { IosRow(title = "“$query”", subtitle = "Play Store'da ara", leading = { IosGlyphTile(Glyph.Bag, Color(0xFF34C759)) }, onClick = onStore) }
            item { IosFooter("Daha Fazla") }
            if (showPermissionCard) {
                item { IosDivider() }
                item {
                    IosRow(title = "Kişiler, mesajlar ve dosyalar da aransın mı?", subtitle = "Dokun: izin ver · yalnızca telefonda aranır",
                        leading = { IosGlyphTile(Glyph.Lock, Color(0xFF0A84FF)) }, onClick = onAskPermission)
                }
                item { IosFooter("Telefonda Ara", action = "Şimdi değil", onAction = onDismissCard) }
            }
        }
    }
}

@Composable
private fun IosRow(title: String, subtitle: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Color(0xB3FFFFFF), fontSize = 14.sp, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun IosGlyphTile(glyph: Glyph, color: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, Color.White, 22.dp)
    }
}

/** iOS 26: grup başlığı grubun ALTINDA, gri; sağda isteğe bağlı bağlantı. */
@Composable
private fun IosFooter(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color(0xD9FFFFFF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont, modifier = Modifier.weight(1f))
        if (action != null) Text(
            "$action ↗", color = Color(0xD9FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction?.invoke() }.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun IosDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(0.5.dp).background(Color(0x40FFFFFF)))
}
