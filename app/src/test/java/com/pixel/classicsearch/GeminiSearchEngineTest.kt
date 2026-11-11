package com.pixel.classicsearch

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSearchEngineTest {

    private val sampleCandidates = listOf(
        AppSearchCandidate("com.spotify.music", "Spotify"),
        AppSearchCandidate("com.google.android.apps.maps", "Google Maps"),
        AppSearchCandidate("ru.yandex.taxi", "Yandex Go"),
        AppSearchCandidate("com.android.chrome", "Chrome"),
        AppSearchCandidate("org.telegram.messenger", "Telegram")
    )

    @Test
    fun `resolves music natural language queries to Spotify`() {
        val action = GeminiSearchEngine.findSemanticAction("хочу послушать музыку", sampleCandidates)
        assertNotNull(action)
        assertEquals("com.spotify.music", action?.targetPackage)
        assertEquals("Spotify", action?.label)
        assertEquals("Музыка", action?.reason)

        val enAction = GeminiSearchEngine.findSemanticAction("play relaxing song", sampleCandidates)
        assertNotNull(enAction)
        assertEquals("com.spotify.music", enAction?.targetPackage)
    }

    @Test
    fun `resolves navigation natural language queries to Maps`() {
        val action = GeminiSearchEngine.findSemanticAction("построй маршрут домой", sampleCandidates)
        assertNotNull(action)
        assertEquals("com.google.android.apps.maps", action?.targetPackage)
        assertEquals("Навигация", action?.reason)
    }

    @Test
    fun `resolves taxi natural language queries to Taxi app`() {
        val action = GeminiSearchEngine.findSemanticAction("заказать такси в аэропорт", sampleCandidates)
        assertNotNull(action)
        assertEquals("ru.yandex.taxi", action?.targetPackage)
        assertEquals("Такси", action?.reason)
    }

    @Test
    fun `returns null for queries that do not match semantic categories`() {
        val action = GeminiSearchEngine.findSemanticAction("в каком году открыли Америку", sampleCandidates)
        assertNull(action)

        val emptyAction = GeminiSearchEngine.findSemanticAction("hi", sampleCandidates)
        assertNull(emptyAction)
    }

    @Test
    fun `PixelNanoManager streamAnswer yields factual answer chunks`() = runBlocking {
        val chunks = PixelNanoManager.streamAnswer("столица австралии").toList()
        val combined = chunks.joinToString("")
        assertTrue(combined.contains("Канберра"))

        val speedChunks = PixelNanoManager.streamAnswer("скорость света").toList()
        val speedText = speedChunks.joinToString("")
        assertTrue(speedText.contains("299 792 458"))
    }

    @Test
    fun `PixelNanoManager returns valid Tensor generation description`() {
        val genName = PixelNanoManager.getTensorGenerationName()
        assertTrue(genName.isNotEmpty())
    }
}
