package dev.glass.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridPackerTest {
    private fun a(k: String) = AppItem(k)

    @Test fun appsFillRowMajor_andOverflowToNextPage() {
        val items = (1..10).map { a("$it") }
        val p = GridPacker.pack(items, columns = 4, rows = 2)
        assertEquals(0, p[0].page); assertEquals(0, p[0].row); assertEquals(0, p[0].col)
        assertEquals(1, p[5].row); assertEquals(1, p[5].col)
        assertEquals(1, p[8].page); assertEquals(0, p[8].col)
        assertEquals(2, GridPacker.pageCount(p))
    }

    @Test fun widgetTakesSpan_andAppsFillGapsBeside() {
        val items = listOf(WidgetItem("w", WidgetKind.Calendar, 2, 2), a("1"), a("2"), a("3"), a("4"), a("5"))
        val p = GridPacker.pack(items, 4, 6)
        assertEquals(0, p[0].row); assertEquals(0, p[0].col); assertEquals(2, p[0].spanX)
        // Widget'ın sağındaki 2×2 boşluk dolar
        assertEquals(0 to 2, p[1].row to p[1].col)
        assertEquals(0 to 3, p[2].row to p[2].col)
        assertEquals(1 to 2, p[3].row to p[3].col)
        assertEquals(1 to 3, p[4].row to p[4].col)
        assertEquals(2 to 0, p[5].row to p[5].col)
    }

    @Test fun widgetWiderThanGrid_isClamped() {
        val p = GridPacker.pack(listOf(WidgetItem("w", WidgetKind.Battery, 4, 2)), 3, 6)
        assertEquals(3, p[0].spanX)
    }

    @Test fun widgetThatDoesNotFit_goesToNextPage() {
        val items = (1..7).map { a("$it") } + WidgetItem("w", WidgetKind.Photo, 4, 4)
        val p = GridPacker.pack(items, 4, 4)
        val w = p.last()
        assertEquals(1, w.page)
        assertEquals(0, w.row)
    }

    @Test fun insertionIndex_beforeOccupant() {
        val items = (0 until 6).map { a("$it") }
        val p = GridPacker.pack(items, 4, 4)
        assertEquals(2, GridPacker.insertionIndex(p, 0, 0, 2, 4))
        assertEquals(5, GridPacker.insertionIndex(p, 0, 1, 1, 4))
    }

    @Test fun insertionIndex_emptyCell_appends() {
        val p = GridPacker.pack((0 until 3).map { a("$it") }, 4, 4)
        assertEquals(3, GridPacker.insertionIndex(p, 0, 3, 3, 4))
    }

    @Test fun insertionIndex_insideWidget_returnsWidget() {
        val items = listOf(a("x"), WidgetItem("w", WidgetKind.Calendar, 2, 2))
        val p = GridPacker.pack(items, 4, 4)
        // widget (0,1)-(1,2) kaplar; (1,2) hücresi widget'ın içinde
        assertEquals(1, GridPacker.insertionIndex(p, 0, 1, 2, 4))
    }

    @Test fun isPageFull_countsSpans() {
        val items = listOf(WidgetItem("w", WidgetKind.Photo, 4, 2)) + (1..8).map { a("$it") }
        val p = GridPacker.pack(items, 4, 4)
        assertTrue(GridPacker.isPageFull(p, 0, 4, 4))
    }

    @Test fun pageBreak_startsNextPage_andDropAfterLastAppStaysOnPage() {
        val items = listOf(a("1"), a("2"), a("3"), PageBreak("b"), a("4"), a("5"))
        val p = GridPacker.pack(items, 4, 6)
        assertEquals(0 to (0 to 3), p[3].page to (p[3].row to p[3].col))
        assertEquals(1, p[4].page); assertEquals(0 to 0, p[4].row to p[4].col)
        assertEquals(2, GridPacker.pageCount(p))
        assertTrue(GridPacker.isPageFull(p, 0, 4, 6))
        // 1. sayfanın boş bölgesine bırakılan öğe sayfa sonunun ÖNÜNE girer (1. sayfada kalır)
        assertEquals(3, GridPacker.insertionIndex(p, 0, 3, 2, 4))
    }

    @Test fun pageBreak_onEmptyOrFullPage_isNoOp() {
        val lead = GridPacker.pack(listOf(PageBreak("b"), a("1")), 4, 6)
        assertEquals(0, lead[1].page); assertEquals(1, GridPacker.pageCount(lead))
        val full = GridPacker.pack((1..4).map { a("$it") } + PageBreak("b") + a("5"), 2, 2)
        assertEquals(1, full[5].page); assertEquals(2, GridPacker.pageCount(full))
    }
}
