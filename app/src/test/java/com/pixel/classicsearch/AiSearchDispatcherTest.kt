package com.pixel.classicsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSearchDispatcherTest {

    @Test
    fun `buildSearchUrl generates correct Perplexity search query url`() {
        val url = AiSearchDispatcher.buildSearchUrl(WidgetPrefs.SEARCH_ENGINE_PERPLEXITY, "quantum computing")
        assertEquals("https://www.perplexity.ai/search?q=quantum+computing", url)
    }

    @Test
    fun `buildSearchUrl generates correct ChatGPT search query url`() {
        val url = AiSearchDispatcher.buildSearchUrl(WidgetPrefs.SEARCH_ENGINE_CHATGPT, "write a python script")
        assertEquals("https://chatgpt.com/?q=write+a+python+script", url)
    }

    @Test
    fun `buildSearchUrl properly encodes Cyrillic queries and special symbols`() {
        val query = "как приготовить борщ?"
        val url = AiSearchDispatcher.buildSearchUrl(WidgetPrefs.SEARCH_ENGINE_PERPLEXITY, query)
        assertTrue(url.startsWith("https://www.perplexity.ai/search?q="))
        // Check that Cyrillic was percent-encoded and question mark encoded
        assertTrue(url.contains("%D0%BA%D0%B0%D0%BA"))
        assertTrue(url.contains("%3F"))
    }

    @Test
    fun `buildSearchUrl generates correct Google AI Mode url with udm=50`() {
        val url = AiSearchDispatcher.buildSearchUrl(WidgetPrefs.SEARCH_ENGINE_AI_MODE, "space exploration")
        assertEquals("https://www.google.com/search?udm=50&q=space+exploration", url)
    }

    @Test
    fun `buildSearchUrl generates correct Copilot search query url`() {
        val url = AiSearchDispatcher.buildSearchUrl(WidgetPrefs.SEARCH_ENGINE_COPILOT, "best android phone 2026")
        assertEquals("https://copilot.microsoft.com/?q=best+android+phone+2026", url)
    }

    @Test
    fun `buildSearchUrl generates correct Claude new conversation url`() {
        val url = AiSearchDispatcher.buildSearchUrl(WidgetPrefs.SEARCH_ENGINE_CLAUDE, "summarize this")
        assertEquals("https://claude.ai/new?q=summarize+this", url)
    }

    @Test
    fun `buildSearchUrl defaults to Google Search for google engine or unknown`() {
        val googleUrl = AiSearchDispatcher.buildSearchUrl(WidgetPrefs.SEARCH_ENGINE_GOOGLE, "hello world")
        assertEquals("https://www.google.com/search?q=hello+world", googleUrl)

        val unknownUrl = AiSearchDispatcher.buildSearchUrl("custom_unknown", "hello world")
        assertEquals("https://www.google.com/search?q=hello+world", unknownUrl)
    }

    @Test
    fun `getTargetPackage resolves correct Android package for each AI provider`() {
        assertEquals("com.google.android.googlequicksearchbox", AiSearchDispatcher.getTargetPackage(WidgetPrefs.SEARCH_ENGINE_GOOGLE))
        assertEquals("com.openai.chatgpt", AiSearchDispatcher.getTargetPackage(WidgetPrefs.SEARCH_ENGINE_CHATGPT))
        assertEquals("ai.perplexity.app.android", AiSearchDispatcher.getTargetPackage(WidgetPrefs.SEARCH_ENGINE_PERPLEXITY))
        assertEquals("com.google.android.apps.bard", AiSearchDispatcher.getTargetPackage(WidgetPrefs.SEARCH_ENGINE_GEMINI))
        assertEquals("com.microsoft.copilot", AiSearchDispatcher.getTargetPackage(WidgetPrefs.SEARCH_ENGINE_COPILOT))
        assertEquals("com.anthropic.claude", AiSearchDispatcher.getTargetPackage(WidgetPrefs.SEARCH_ENGINE_CLAUDE))
    }

    @Test
    fun `getTargetPackage resolves custom app package prefix`() {
        val target = AiSearchDispatcher.getTargetPackage("${WidgetPrefs.PREFIX_APP}org.example.aiassistant")
        assertEquals("org.example.aiassistant", target)
    }

    @Test
    fun `getTargetPackage returns null for unrecognized non-app engine`() {
        assertNull(AiSearchDispatcher.getTargetPackage("unknown_engine"))
    }
}
