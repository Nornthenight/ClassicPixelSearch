package com.pixel.classicsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppSearchMatcherTest {

    private val sampleCandidates = listOf(
        AppSearchCandidate("com.google.android.apps.photos", "Google Photos"),
        AppSearchCandidate("com.android.vending", "Google Play Store"),
        AppSearchCandidate("org.telegram.messenger", "Telegram"),
        AppSearchCandidate("com.spotify.music", "Spotify"),
        AppSearchCandidate("com.android.chrome", "Chrome"),
        AppSearchCandidate("com.calculator", "Calculator"),
        AppSearchCandidate("com.calendar", "Calendar"),
        AppSearchCandidate("com.avito.android", "Авито"),
        AppSearchCandidate("ru.profi.client", "PROFI.RU")
    )

    @Test
    fun `exact label match returns correct candidate case-insensitive`() {
        val result = AppSearchMatcher.findBestMatch(sampleCandidates, "telegram")
        assertNotNull(result)
        assertEquals("org.telegram.messenger", result?.packageName)
        assertEquals("Telegram", result?.label)
    }

    @Test
    fun `exact package name match resolves correctly`() {
        val result = AppSearchMatcher.findBestMatch(sampleCandidates, "com.spotify.music")
        assertNotNull(result)
        assertEquals("Spotify", result?.label)
    }

    @Test
    fun `prefix match selects candidate and resolves shortest label on tie`() {
        val candidates = listOf(
            AppSearchCandidate("com.test.app2", "Telephony Services"),
            AppSearchCandidate("org.telegram.messenger", "Telegram")
        )
        val result = AppSearchMatcher.findBestMatch(candidates, "Tele")
        assertNotNull(result)
        assertEquals("org.telegram.messenger", result?.packageName)
    }

    @Test
    fun `word boundary prefix matches inside multi-word titles`() {
        // Query "Play" should match "Google Play Store"
        val result = AppSearchMatcher.findBestMatch(sampleCandidates, "Play")
        assertNotNull(result)
        assertEquals("com.android.vending", result?.packageName)
        assertEquals("Google Play Store", result?.label)
    }

    @Test
    fun `query AV matches Avito via phonetic translit or package segment`() {
        val result = AppSearchMatcher.findBestMatch(sampleCandidates, "AV")
        assertNotNull(result)
        assertEquals("com.avito.android", result?.packageName)
        assertEquals("Авито", result?.label)
    }

    @Test
    fun `query R matches PROFI_RU via word-boundary or package segment`() {
        val result = AppSearchMatcher.findBestMatch(sampleCandidates, "R")
        assertNotNull(result)
        assertEquals("ru.profi.client", result?.packageName)
        assertEquals("PROFI.RU", result?.label)
    }

    @Test
    fun `word boundary prefix matches word separated by hyphen or dot`() {
        val customCandidates = listOf(
            AppSearchCandidate("com.foo.bar", "My-Special-Tool"),
            AppSearchCandidate("com.baz.qux", "Other App")
        )
        val result = AppSearchMatcher.findBestMatch(customCandidates, "Special")
        assertNotNull(result)
        assertEquals("com.foo.bar", result?.packageName)
    }

    @Test
    fun `blank or empty query returns null`() {
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, ""))
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, "   "))
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, null))
    }

    @Test
    fun `empty candidates list returns null`() {
        assertNull(AppSearchMatcher.findBestMatch(emptyList(), "Telegram"))
    }

    @Test
    fun `non matching query returns null`() {
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, "ZzzNonExistent"))
    }

    @Test
    fun `multi-word search queries and questions do not falsely match apps`() {
        // Multi-word queries with spaces must return null so they route to AI Search
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, "погода в москве"))
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, "how to bake cookies"))
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, "tell me a joke"))
    }

    @Test
    fun `queries with question mark return null for AI routing`() {
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, "what is this?"))
        assertNull(AppSearchMatcher.findBestMatch(sampleCandidates, "chrome?"))
    }
}
