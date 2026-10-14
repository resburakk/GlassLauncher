package dev.glass.launcher.data

/** Bir öğenin ekrandaki yeri: sayfa, satır, sütun ve kapladığı hücre sayısı. */
data class Placement(val item: HomeItem, val page: Int, val row: Int, val col: Int, val spanX: Int, val spanY: Int) {
    fun covers(r: Int, c: Int) = r in row until row + spanY && c in col until col + spanX
}

/**
 * Düz öğe listesini (uygulama 1×1, klasör 1×1, widget N×M) sayfalara yerleştirir.
 * Her öğe, listedeki sırasıyla, sayfadaki ilk boş ve sığan konuma (satır öncelikli) konur; sığmazsa
 * yeni sayfaya geçer. Böylece widget'ın yanında kalan boşlukları sonraki simgeler doldurur (iOS gibi).
 */
object GridPacker {

    fun spanOf(item: HomeItem, columns: Int, rows: Int): Pair<Int, Int> = when (item) {
        is WidgetItem -> item.spanX.coerceIn(1, columns) to item.spanY.coerceIn(1, rows)
        is PageBreak -> 0 to 0
        else -> 1 to 1
    }

    fun pack(items: List<HomeItem>, columns: Int, rows: Int): List<Placement> {
        val out = ArrayList<Placement>(items.size)
        var page = 0
        var occupied = Array(rows) { BooleanArray(columns) }
        for (item in items) {
            if (item is PageBreak) {
                // İlk boş hücreye görünmez olarak konur ve sayfanın kalanını doldurur; sonraki öğe yeni
                // sayfaya geçer. Sayfa boşsa ya da tamamen doluysa etkisizdir (boş sayfa oluşturmaz).
                val free = (0 until rows * columns).firstOrNull { !occupied[it / columns][it % columns] }
                val used = occupied.any { row -> row.any { it } }
                if (free != null && used) {
                    out += Placement(item, page, free / columns, free % columns, 1, 1)
                    for (row in occupied) row.fill(true)
                } else {
                    if (free == null) { page++; occupied = Array(rows) { BooleanArray(columns) } }
                    out += Placement(item, page, 0, 0, 0, 0)
                }
                continue
            }
            val (sx, sy) = spanOf(item, columns, rows)
            var placed = false
            while (!placed) {
                var pos = 0
                while (pos < rows * columns) {
                    val r = pos / columns
                    val c = pos % columns
                    if (fits(occupied, r, c, sx, sy, rows, columns)) {
                        for (rr in r until r + sy) for (cc in c until c + sx) occupied[rr][cc] = true
                        out += Placement(item, page, r, c, sx, sy)
                        placed = true
                        break
                    }
                    pos++
                }
                if (!placed) {
                    page++
                    occupied = Array(rows) { BooleanArray(columns) }
                }
            }
        }
        return out
    }

    private fun fits(o: Array<BooleanArray>, r: Int, c: Int, sx: Int, sy: Int, rows: Int, cols: Int): Boolean {
        if (r + sy > rows || c + sx > cols) return false
        for (rr in r until r + sy) for (cc in c until c + sx) if (o[rr][cc]) return false
        return true
    }

    fun pageCount(placements: List<Placement>): Int =
        (placements.filter { it.item !is PageBreak }.maxOfOrNull { it.page } ?: 0) + 1

    /** Bir sayfanın tamamen dolu olup olmadığı (sürüklerken yeni boş sayfa açmak için). */
    fun isPageFull(placements: List<Placement>, page: Int, columns: Int, rows: Int): Boolean =
        placements.any { it.page == page && it.item is PageBreak && it.spanX > 0 } ||
            placements.filter { it.page == page }.sumOf { it.spanX * it.spanY } >= columns * rows

    /**
     * Sürüklenen öğenin bırakıldığı hücreye göre listedeki yeni sırasını bulur.
     * [placements] sürüklenen öğe ÇIKARILMIŞ listenin yerleşimidir. O hücreyi kaplayan ya da
     * okuma sırasında o hücreden sonra gelen ilk öğenin önüne yerleştirilir; yoksa en sona.
     */
    fun insertionIndex(placements: List<Placement>, page: Int, row: Int, col: Int, columns: Int): Int {
        val target = page.toLong() * 10_000 + row * columns + col
        placements.forEachIndexed { i, p ->
            if (p.item is PageBreak && p.spanX == 0) return@forEachIndexed // etkisiz sayfa sonu
            if (p.page == page && p.covers(row, col)) return i
            val start = p.page.toLong() * 10_000 + p.row * columns + p.col
            // Sayfa sonundan sonraki boş hücreler: öğe sayfa sonunun önüne (aynı sayfaya) girer.
            if (p.item is PageBreak && p.page == page && start <= target) return i
            if (start >= target) return i
        }
        return placements.size
    }
}
