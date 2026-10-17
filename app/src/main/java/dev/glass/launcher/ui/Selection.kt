package dev.glass.launcher.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ana ekranda çoklu seçim (düzenleme modunda "Seç"). Seçiliyken simgelere dokunmak seçer/bırakır;
 * seçili bir simge sürüklenince seçilenlerin hepsi birlikte taşınır.
 */
@Stable
class SelectionState {
    var active by mutableStateOf(false)
    val ids = mutableStateListOf<String>()

    fun toggle(id: String) { if (id in ids) ids.remove(id) else ids.add(id) }
    fun start() { active = true; ids.clear() }
    fun end() { active = false; ids.clear() }

    /** [id] sürüklenirken seçilenlerin hepsi birlikte mi taşınıyor? */
    fun isGroupDrag(dragKey: String?): Boolean = active && dragKey != null && dragKey in ids && ids.size > 1
}

/** Simgenin sağ altındaki seçim işareti: boş halka veya mavi daire içinde onay. */
@Composable
fun SelectBadge(selected: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .shadow(3.dp, CircleShape)
            .background(if (selected) Color(0xFF0A84FF) else Color.Black.copy(alpha = 0.25f), CircleShape)
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) GlyphIcon(Glyph.Check, Color.White, size * 0.6f)
    }
}

/**
 * Seçim modunda alttan açılan işlem çubuğu: seçim sayısı, Klasör Oluştur, Klasöre Taşı, Arşive Kaldır.
 * "Klasöre Taşı" mevcut klasörlerin listesini açar.
 */
@Composable
fun SelectionBar(
    visible: Boolean,
    count: Int,
    appCount: Int,
    folders: List<Pair<String, String>>,
    onCreateFolder: () -> Unit,
    onMoveInto: (String) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    val motion = LocalMotion.current
    var picking by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (!visible) picking = false
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(motion.bouncy()) { it } + fadeIn(motion.tween(160)),
            exit = slideOutVertically(motion.tween(200)) { it } + fadeOut(motion.tween(160)),
        ) {
            val shape = RoundedCornerShape(30.dp)
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(10.dp)
                    .fillMaxWidth()
                    .shadow(18.dp, shape)
                    .background(Color(0xFF1C1C1E), shape)
                    .clickable(interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (count) {
                            0 -> "Simgelere dokunarak seç"
                            else -> "$count öğe seçildi"
                        },
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    Text(
                        "Bitti", color = Color(0xFF0A84FF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDone).padding(8.dp),
                    )
                }
                if (count > 1) {
                    Text(
                        "Seçili bir simgeyi sürükleyince hepsi birlikte taşınır.",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (picking) {
                    Text(
                        "Hangi klasöre?", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 6.dp),
                    )
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        folders.forEach { (id, name) ->
                            BarButton(name, Modifier.fillMaxWidth()) { picking = false; onMoveInto(id) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    BarButton("Geri", Modifier.fillMaxWidth(), dim = true) { picking = false }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BarButton("Klasör Oluştur", Modifier.weight(1f), enabled = appCount >= 2, onClick = onCreateFolder)
                        BarButton("Klasöre Taşı", Modifier.weight(1f), enabled = count > 0 && folders.isNotEmpty()) { picking = true }
                    }
                    Spacer(Modifier.height(6.dp))
                    BarButton("Ana Ekrandan Kaldır", Modifier.fillMaxWidth(), enabled = count > 0, destructive = true, onClick = onRemove)
                }
            }
        }
    }
}

@Composable
private fun BarButton(
    text: String, modifier: Modifier, enabled: Boolean = true, destructive: Boolean = false, dim: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (dim) 0.06f else 0.12f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 15.sp, maxLines = 1,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                destructive -> Color(0xFFFF453A)
                else -> Color.White
            },
        )
    }
}
