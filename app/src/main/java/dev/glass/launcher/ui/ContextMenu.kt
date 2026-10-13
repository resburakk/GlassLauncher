package dev.glass.launcher.ui

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.glass.launcher.data.AppInfo
import dev.glass.launcher.data.FolderItem
import kotlin.math.roundToInt

/** Uzun basma menüsünün açıldığı hücre. */
data class MenuRequest(
    val itemId: String,
    val place: CellPlace,
    val iconRect: Rect,
    val cellRect: Rect,
    val iconDp: androidx.compose.ui.unit.Dp,
)

/** Menü satırı. */
private class MenuAction(val title: String, val glyph: Glyph?, val destructive: Boolean = false, val run: () -> Unit)

private val Red = Color(0xFFFF453A)

/**
 * iOS tarzı bağlam menüsü: simge yerinde hafif büyür, yanında cam bir menü açılır.
 * Uygulamalar için: uygulama kısayolları (yalnızca varsayılan ana ekransa), Ana Ekranı Düzenle,
 * Uygulama Bilgisi, Ana Ekrandan Kaldır / Ana Ekrana Ekle, Uygulamayı Sil.
 * Klasörler için: Yeniden Adlandır, Klasörü Dağıt, Ana Ekranı Düzenle.
 */
@Composable
fun ContextMenuOverlay(
    req: MenuRequest,
    vm: LauncherViewModel,
    app: AppInfo?,
    folder: FolderItem?,
    onHome: Boolean,
    strength: Float,
    iconGlass: Boolean,
    byKey: Map<String, AppInfo>,
    onEditHome: () -> Unit,
    onRemoveFromHome: () -> Unit,
    onAddToHome: () -> Unit,
    onOpenFolder: () -> Unit,
    onDissolveFolder: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current

    val shortcuts by produceState<List<Pair<ShortcutInfo, ImageBitmap?>>>(emptyList(), app?.key) {
        if (app == null) return@produceState
        val px = with(density) { 24.dp.roundToPx() }
        value = vm.shortcuts(app).map { s ->
            s to vm.shortcutIcon(s, ctx.resources.displayMetrics.densityDpi)?.toBitmap(px, px)?.asImageBitmap()
        }
    }

    val actions = buildList {
        if (app != null) {
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            add(MenuAction("Uygulama Bilgisi", Glyph.Info) { vm.openAppInfo(app) })
            if (req.place == CellPlace.Library) {
                if (!onHome) add(MenuAction("Ana Ekrana Ekle", Glyph.Plus) { onAddToHome() })
            } else {
                add(MenuAction("Ana Ekrandan Kaldır", Glyph.Minus) { onRemoveFromHome() })
            }
            if (!app.isSystem) add(MenuAction("Uygulamayı Sil", Glyph.Trash, destructive = true) { vm.uninstall(app) })
        } else if (folder != null) {
            add(MenuAction("Yeniden Adlandır", Glyph.Pencil) { onOpenFolder() })
            add(MenuAction("Ana Ekranı Düzenle", Glyph.Edit) { onEditHome() })
            add(MenuAction("Klasörü Dağıt", Glyph.Grid, destructive = true) { onDissolveFolder() })
        }
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }) {
        // Kaldırılmış simge
        Box(
            Modifier
                .offset { IntOffset(req.iconRect.left.roundToInt(), req.iconRect.top.roundToInt()) }
                .graphicsLayer { scaleX = 1.06f; scaleY = 1.06f }
        ) {
            when {
                app != null -> AppIconImage(app, vm, req.iconDp, iconGlass, strength)
                folder != null -> FolderIcon(folder, byKey, vm, req.iconDp, strength)
            }
        }

        val gapPx = with(density) { 10.dp.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Column(
                    Modifier
                        .width(250.dp)
                        .liquidGlass(RoundedCornerShape(22.dp), strength, 18.dp)
                        .background(Color(0x59000000))
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    shortcuts.forEachIndexed { i, (info, icon) ->
                        MenuRow(
                            title = info.shortLabel?.toString() ?: "",
                            leading = {
                                if (icon != null) Image(icon, null, Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)))
                            },
                            onClick = { vm.startShortcut(info); onClose() },
                        )
                        if (i < shortcuts.lastIndex) Hairline()
                    }
                    if (shortcuts.isNotEmpty()) Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0x33000000)))
                    actions.forEachIndexed { i, a ->
                        MenuRow(
                            title = a.title,
                            color = if (a.destructive) Red else Color.White,
                            trailing = { a.glyph?.let { GlyphIcon(it, if (a.destructive) Red else Color.White, 18.dp) } },
                            onClick = { a.run(); onClose() },
                        )
                        if (i < actions.lastIndex) Hairline()
                    }
                }
            },
        ) { measurables, constraints ->
            val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val maxX = (constraints.maxWidth - p.width - marginPx).coerceAtLeast(marginPx)
            val x = req.iconRect.left.coerceIn(marginPx, maxX)
            val below = req.cellRect.bottom + gapPx + p.height < constraints.maxHeight - marginPx
            val y = if (below) req.cellRect.bottom + gapPx else req.iconRect.top - gapPx - p.height
            layout(constraints.maxWidth, constraints.maxHeight) {
                p.place(x.roundToInt(), y.coerceAtLeast(marginPx).roundToInt())
            }
        }
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x40FFFFFF)))
}

@Composable
private fun MenuRow(
    title: String,
    color: Color = Color.White,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title, color = color, fontSize = 16.sp, fontWeight = FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}
