package com.pixel.classicsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPrefsConstantsTest {

    @Test
    fun `preset action constants match expected identifier keys`() {
        assertEquals("default", WidgetPrefs.ACTION_DEFAULT)
        assertEquals("voice_search", WidgetPrefs.ACTION_VOICE_SEARCH)
        assertEquals("gemini", WidgetPrefs.ACTION_GEMINI)
        assertEquals("google_lens", WidgetPrefs.ACTION_GOOGLE_LENS)
        assertEquals("ai_mode", WidgetPrefs.ACTION_AI_MODE)
        assertEquals("chatgpt", WidgetPrefs.ACTION_CHATGPT)
        assertEquals("perplexity", WidgetPrefs.ACTION_PERPLEXITY)
        assertEquals("qr_scanner", WidgetPrefs.ACTION_QR_SCANNER)
        assertEquals("browser_brave", WidgetPrefs.ACTION_BROWSER_BRAVE)
        assertEquals("browser_firefox", WidgetPrefs.ACTION_BROWSER_FIREFOX)
        assertEquals("browser_cromite", WidgetPrefs.ACTION_BROWSER_CROMITE)
        assertEquals("app:", WidgetPrefs.PREFIX_APP)
    }

    @Test
    fun `custom app action prefix detection works correctly`() {
        val customAppAction = "${WidgetPrefs.PREFIX_APP}com.example.myapp"
        assertTrue(customAppAction.startsWith(WidgetPrefs.PREFIX_APP))
        assertEquals("com.example.myapp", customAppAction.removePrefix(WidgetPrefs.PREFIX_APP))
    }

    @Test
    fun `surface styles and icon styles index definitions are sound`() {
        assertEquals(0, WidgetPrefs.SURFACE_DEEP_RING)
        assertEquals(1, WidgetPrefs.SURFACE_PASTEL)
        assertEquals(2, WidgetPrefs.SURFACE_RICH_ACCENT)
        assertEquals(3, WidgetPrefs.SURFACE_AMOLED)

        assertEquals(0, WidgetPrefs.ICON_ADAPTIVE_EMERALD)
        assertEquals(1, WidgetPrefs.ICON_DYNAMIC_MONET)
        assertEquals(2, WidgetPrefs.ICON_CLASSIC_COLOR)
    }
}
