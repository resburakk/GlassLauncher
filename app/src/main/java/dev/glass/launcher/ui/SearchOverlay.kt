package dev.glass.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.SearchRanker

/** Spotlight benzeri arama: cam arama kutusu, 4 simgelik öneri satırı, anlık sonuçlar, otomatik klavye. */
@Composable
fun SearchOverlay(
    vm: LauncherViewModel,
    allApps: List<AppInfo>,
    suggestions: List<AppInfo>,
    strength: Float,
    iconGlass: Boolean,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    // Türkçe karakter duyarsız, sıralı çevrimdışı arama ("cevi" → "Çeviri", "gm" → "Google Maps").
    val results = remember(query, allApps) { SearchRanker.rank(query, allApps) { it.label } }

    fun close() { keyboard?.hide(); onClose() }
    fun open(app: AppInfo) { keyboard?.hide(); vm.launch(app); onClose() }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { close() }) }) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .liquidGlass(CircleShape, strength, 4.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchGlyph(Color(0xCCFFFFFF), 18.dp)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontFamily = AppFont),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { open(it) } }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("Ara", color = Color(0x99FFFFFF), fontSize = 17.sp)
                                inner()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "İptal", color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.clickable { close() }.padding(vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            if (query.isBlank()) {
                Text(
                    "Öneriler", color = Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                )
                // Öneri kutusu ana ekrandaki dock ile birebir aynı ölçüde (genişlik, yükseklik, köşe,
                // simge boyutu ve aralığı); simgeler ana ekranla aynı boyutta.
                val hm = LocalHomeMetrics.current
                if (hm != null) {
                    val st = LocalLauncherSettings.current
                    val screenW = LocalConfiguration.current.screenWidthDp.dp
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .requiredWidth(screenW - hm.dockMarginX * 2f)
                            .height(hm.dockH)
                            .liquidGlass(RoundedCornerShape(hm.dockCorner), strength * st.dockOpacity, 6.dp)
                    ) {
                        suggestions.take(4).forEachIndexed { i, app ->
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = hm.dockInnerStart + hm.dockPitch * i.toFloat())
                            ) {
                                AppCell(app, vm, hm.dockIcon, hm.dockPitch, hm.dockIcon, false, iconGlass, strength) { open(app) }
                            }
                        }
                    }
                }
            } else if (results.isEmpty()) {
                Text("Sonuç bulunamadı", color = Color(0xCCFFFFFF), fontSize = 15.sp, modifier = Modifier.padding(6.dp))
            } else {
                LazyColumn(
                    Modifier
                        .weight(1f, fill = false)
                        .liquidGlass(RoundedCornerShape(24.dp), strength * 0.8f, 6.dp)
                ) {
                    items(results, key = { it.key }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { open(app) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIconImage(app, vm, 40.dp, iconGlass, strength)
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, color = Color.White, fontSize = 16.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
