package dev.glass.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.R

/**
 * Launcher arayüz simgeleri: Framework7 Icons (MIT lisansı, FONT_LICENSE_Framework7Icons_MIT.txt).
 * iOS tarzında çizilmiş açık lisanslı settir; Apple'ın SF Symbols'u Apple dışı platformlarda
 * kullanılamadığı için tercih edildi. Her simge fonttaki adıyla (ligature) çağrılır.
 */
enum class Glyph(val f7: String) {
    Edit("square_grid_2x2"),
    Info("info_circle"),
    Minus("minus_circle"),
    Trash("trash"),
    Plus("plus_circle"),
    Folder("folder"),
    Pencil("pencil"),
    Grid("square_grid_2x2"),
    Search("search"),
    Close("xmark"),
    Check("checkmark_alt"),
    Widget("rectangle_3_offgrid"),
    Wallpaper("photo"),
    Mic("mic"),
    Person("person"),
    Message("chat_bubble"),
    Doc("doc_text"),
    Globe("globe"),
    Bag("bag"),
    Gear("gear_alt"),
    Lock("lock"),
    Hide("eye_slash"),
}

private val F7Icons = FontFamily(Font(R.font.framework7_icons))

/**
 * Framework7 simgelerinin çizim karesi 512 birimdir; mürekkep, taban çizgisinin 192 birim
 * (karenin %37,5'i) üzerinde ortalanır. Simgeyi kutunun tam ortasına oturtmak için kullanılır.
 */
private const val F7_INK_CENTER = 192f / 512f

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 18.dp) {
    val measurer = rememberTextMeasurer(cacheSize = 8)
    Spacer(
        Modifier
            .size(size)
            .clearAndSetSemantics { }
            .drawWithCache {
                val s = this.size.minDimension
                val layout = measurer.measure(
                    text = glyph.f7,
                    style = TextStyle(
                        fontFamily = F7Icons,
                        fontSize = s.toSp(),
                        letterSpacing = 0.sp,
                        fontFeatureSettings = "liga",
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    softWrap = false,
                    maxLines = 1,
                )
                val topLeft = Offset(
                    (this.size.width - layout.size.width) / 2f,
                    this.size.height / 2f + s * F7_INK_CENTER - layout.firstBaseline,
                )
                onDrawBehind { drawText(layout, color = color, topLeft = topLeft) }
            }
    )
}
