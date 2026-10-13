package dev.glass.launcher.data

/**
 * Çevrimdışı uygulama araması. Türkçe karakter duyarsızdır ("cevi" → "Çeviri").
 * Sıralama: ad başı eşleşme > kelime başı eşleşme > içinde geçme > baş harfler ("gm" → "Google Maps").
 */
object SearchRanker {
    fun <T> rank(query: String, items: List<T>, label: (T) -> String): List<T> {
        val q = AppCategorizer.normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        return items.mapNotNull { item ->
            val l = AppCategorizer.normalize(label(item))
            val words = l.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
            val initials = words.joinToString("") { it.take(1) }
            val score = when {
                l.startsWith(q) -> 0
                words.any { it.startsWith(q) } -> 1
                l.contains(q) -> 2
                q.length >= 2 && initials.startsWith(q) -> 3
                else -> -1
            }
            if (score < 0) null else score to item
        }.sortedWith(compareBy({ it.first }, { label(it.second).length })).map { it.second }
    }
}
