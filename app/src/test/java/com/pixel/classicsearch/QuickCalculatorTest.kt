package com.pixel.classicsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCalculatorTest {

    @Test
    fun `evaluates basic arithmetic and parentheses accurately`() {
        assertEquals("4410", QuickCalculator.evaluate("245 * 18"))
        assertEquals("55", QuickCalculator.evaluate("(120 + 45) / 3"))
        assertEquals("2.5", QuickCalculator.evaluate("10 / 4"))
        assertEquals("1024", QuickCalculator.evaluate("2^10"))
    }

    @Test
    fun `evaluates percentage expressions in English and Russian`() {
        assertEquals("360", QuickCalculator.evaluate("15% of 2400"))
        assertEquals("100", QuickCalculator.evaluate("20% от 500"))
    }

    @Test
    fun `returns null for plain numbers or text queries`() {
        assertNull(QuickCalculator.evaluate("2026"))
        assertNull(QuickCalculator.evaluate("-42"))
        assertNull(QuickCalculator.evaluate("погода в москве"))
        assertNull(QuickCalculator.evaluate("10 / 0"))
    }

    @Test
    fun `parses bang commands accurately`() {
        val perplexity = QuickCalculator.parseBangCommand("!p квантовая физика")
        assertNotNull(perplexity)
        assertEquals(WidgetPrefs.SEARCH_ENGINE_PERPLEXITY, perplexity?.engineOrTarget)
        assertEquals("квантовая физика", perplexity?.cleanQuery)

        val chatgpt = QuickCalculator.parseBangCommand("!c write a poem")
        assertNotNull(chatgpt)
        assertEquals(WidgetPrefs.SEARCH_ENGINE_CHATGPT, chatgpt?.engineOrTarget)
        assertEquals("write a poem", chatgpt?.cleanQuery)

        val youtube = QuickCalculator.parseBangCommand("!y lo-fi hip hop")
        assertNotNull(youtube)
        assertTrue(youtube?.isYouTube == true)
        assertEquals("lo-fi hip hop", youtube?.cleanQuery)

        assertNull(QuickCalculator.parseBangCommand("ordinary query"))
    }
}
