package dev.glass.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndCategoryTest {
    private val names = listOf("Çeviri", "ChatGPT", "Google Maps", "Şok Market", "Ayarlar", "Haritalar", "Instagram")

    @Test fun turkishInsensitive() {
        assertEquals(listOf("Çeviri"), SearchRanker.rank("cevi", names) { it })
        assertEquals("Şok Market", SearchRanker.rank("sok", names) { it }.first())
        assertEquals(listOf("Instagram"), SearchRanker.rank("İNSTA", names) { it })
    }

    @Test fun prefixBeatsContains() {
        val r = SearchRanker.rank("har", names) { it }
        assertEquals("Haritalar", r.first())
    }

    @Test fun initials() {
        assertTrue("Google Maps" in SearchRanker.rank("gm", names) { it })
    }

    @Test fun emptyQuery_returnsEmpty() {
        assertTrue(SearchRanker.rank("  ", names) { it }.isEmpty())
    }

    @Test fun normalize() {
        assertEquals("cevirici sok igdir", AppCategorizer.normalize("Çevirici ŞOK Iğdır"))
    }

    @Test fun categorize_byKeywords() {
        assertEquals(AppCategory.Shopping, AppCategorizer.categorize("com.trendyol.android", "Trendyol", -1, false))
        assertEquals(AppCategory.Finance, AppCategorizer.categorize("com.akbank.android.apps.akbank_direkt", "Akbank", -1, false))
        assertEquals(AppCategory.Travel, AppCategorizer.categorize("com.booking", "Booking.com", -1, false))
        assertEquals(AppCategory.Social, AppCategorizer.categorize("com.whatsapp", "WhatsApp", -1, false))
        assertEquals(AppCategory.Productivity, AppCategorizer.categorize("com.anthropic.claude", "Claude", -1, false))
        assertEquals(AppCategory.Other, AppCategorizer.categorize("com.example.xyz", "Xyz", -1, false))
        assertEquals(AppCategory.Utilities, AppCategorizer.categorize("com.example.xyz", "Xyz", -1, true))
    }
}
