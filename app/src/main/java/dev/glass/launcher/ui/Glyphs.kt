package dev.glass.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Özgün, basit çizgi simgeler (Apple simgeleri kullanılmaz). */
enum class Glyph { Edit, Info, Minus, Trash, Plus, Folder, Pencil, Grid, Search, Close, Check, Widget, Wallpaper }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) { drawGlyph(glyph, color) }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val s = size.minDimension
    val w = s * 0.09f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val c = Offset(s / 2f, s / 2f)
    when (glyph) {
        Glyph.Edit, Glyph.Grid -> {
            val cell = s * 0.32f
            val gap = s * 0.12f
            val start = (s - cell * 2 - gap) / 2f
            for (r in 0..1) for (col in 0..1) {
                drawRoundRect(
                    color, Offset(start + col * (cell + gap), start + r * (cell + gap)), Size(cell, cell),
                    CornerRadius(cell * 0.3f), style = stroke,
                )
            }
        }
        Glyph.Info -> {
            drawCircle(color, s * 0.42f, c, style = stroke)
            drawLine(color, Offset(c.x, s * 0.45f), Offset(c.x, s * 0.72f), w, StrokeCap.Round)
            drawCircle(color, w * 0.8f, Offset(c.x, s * 0.3f))
        }
        Glyph.Minus -> {
            drawCircle(color, s * 0.42f, c, style = stroke)
            drawLine(color, Offset(s * 0.3f, c.y), Offset(s * 0.7f, c.y), w, StrokeCap.Round)
        }
        Glyph.Plus -> {
            drawCircle(color, s * 0.42f, c, style = stroke)
            drawLine(color, Offset(s * 0.3f, c.y), Offset(s * 0.7f, c.y), w, StrokeCap.Round)
            drawLine(color, Offset(c.x, s * 0.3f), Offset(c.x, s * 0.7f), w, StrokeCap.Round)
        }
        Glyph.Trash -> {
            drawLine(color, Offset(s * 0.18f, s * 0.26f), Offset(s * 0.82f, s * 0.26f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.4f, s * 0.14f), Offset(s * 0.6f, s * 0.14f), w, StrokeCap.Round)
            val p = Path().apply {
                moveTo(s * 0.26f, s * 0.3f); lineTo(s * 0.32f, s * 0.86f)
                lineTo(s * 0.68f, s * 0.86f); lineTo(s * 0.74f, s * 0.3f)
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.Folder -> {
            val p = Path().apply {
                moveTo(s * 0.12f, s * 0.26f); lineTo(s * 0.4f, s * 0.26f); lineTo(s * 0.48f, s * 0.34f)
                lineTo(s * 0.88f, s * 0.34f); lineTo(s * 0.88f, s * 0.8f); lineTo(s * 0.12f, s * 0.8f); close()
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.Pencil -> {
            drawLine(color, Offset(s * 0.22f, s * 0.78f), Offset(s * 0.74f, s * 0.26f), w * 1.6f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.15f, s * 0.85f), Offset(s * 0.2f, s * 0.8f), w, StrokeCap.Round)
        }
        Glyph.Search -> {
            drawCircle(color, s * 0.3f, Offset(s * 0.43f, s * 0.43f), style = stroke)
            drawLine(color, Offset(s * 0.65f, s * 0.65f), Offset(s * 0.86f, s * 0.86f), w, StrokeCap.Round)
        }
        Glyph.Close -> {
            drawLine(color, Offset(s * 0.28f, s * 0.28f), Offset(s * 0.72f, s * 0.72f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.72f, s * 0.28f), Offset(s * 0.28f, s * 0.72f), w, StrokeCap.Round)
        }
        Glyph.Check -> {
            val p = Path().apply {
                moveTo(s * 0.22f, s * 0.52f); lineTo(s * 0.42f, s * 0.72f); lineTo(s * 0.8f, s * 0.3f)
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.Widget -> {
            drawRoundRect(color, Offset(s * 0.12f, s * 0.12f), Size(s * 0.76f, s * 0.34f), CornerRadius(s * 0.1f), style = stroke)
            drawRoundRect(color, Offset(s * 0.12f, s * 0.56f), Size(s * 0.32f, s * 0.32f), CornerRadius(s * 0.1f), style = stroke)
            drawRoundRect(color, Offset(s * 0.56f, s * 0.56f), Size(s * 0.32f, s * 0.32f), CornerRadius(s * 0.1f), style = stroke)
        }
        Glyph.Wallpaper -> {
            drawRoundRect(color, Offset(s * 0.12f, s * 0.16f), Size(s * 0.76f, s * 0.68f), CornerRadius(s * 0.12f), style = stroke)
            val p = Path().apply {
                moveTo(s * 0.18f, s * 0.74f); lineTo(s * 0.4f, s * 0.48f); lineTo(s * 0.56f, s * 0.64f)
                lineTo(s * 0.66f, s * 0.54f); lineTo(s * 0.84f, s * 0.74f)
            }
            drawPath(p, color, style = stroke)
            drawCircle(color, s * 0.06f, Offset(s * 0.66f, s * 0.34f))
        }
    }
}
