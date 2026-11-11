package com.pixel.classicsearch

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Unified search dispatcher for Gemini (On-Device Nano & Cloud Flash).
 * Integrates directly with QuickSearchActivity to stream smart answers into
 * the frosted glass overlay card when no local apps match.
 */
object GeminiSearchEngine {

    enum class EngineSource {
        NANO_ON_DEVICE,  // Hardware Tensor G3/G4/G5/G6 NPU via Android AICore
        CLOUD_GEMINI,    // Google Generative AI API (Gemini 1.5 Flash via user API key)
        NONE
    }

    data class SemanticAction(
        val targetPackage: String,
        val label: String,
        val reason: String
    )

    fun getActiveEngineSource(context: Context): EngineSource {
        if (!WidgetPrefs.isGeminiEnabled(context)) {
            return EngineSource.NONE
        }

        val nanoStatus = PixelNanoManager.checkStatus(context)
        if (nanoStatus == PixelNanoManager.NanoStatus.AVAILABLE) {
            return EngineSource.NANO_ON_DEVICE
        }

        val apiKey = WidgetPrefs.getGeminiApiKey(context)
        if (apiKey.isNotBlank()) {
            return EngineSource.CLOUD_GEMINI
        }

        return EngineSource.NONE
    }

    /**
     * Streams answers from the active Gemini engine.
     * Emits pair of (EngineSource, textChunk).
     */
    fun streamAnswer(context: Context, query: String): Flow<Pair<EngineSource, String>> = flow {
        val source = getActiveEngineSource(context)
        when (source) {
            EngineSource.NANO_ON_DEVICE -> {
                PixelNanoManager.streamAnswer(query).collect { chunk ->
                    emit(Pair(EngineSource.NANO_ON_DEVICE, chunk))
                }
            }
            EngineSource.CLOUD_GEMINI -> {
                val key = WidgetPrefs.getGeminiApiKey(context)
                GeminiCloudClient.streamAnswer(key, query).collect { chunk ->
                    emit(Pair(EngineSource.CLOUD_GEMINI, chunk))
                }
            }
            EngineSource.NONE -> {
                // No active Gemini engine configured
            }
        }
    }

    /**
     * Semantic intent resolution: matches natural language phrases to installed applications
     * (e.g. "хочу музыку" -> Spotify/YouTube Music, "заказать еду" -> Delivery apps, "такси" -> Taxi apps).
     */
    fun findSemanticAction(query: String, installedPackages: List<AppSearchCandidate>): SemanticAction? {
        val q = query.lowercase().trim()
        if (q.length < 3) return null

        val musicKeywords = listOf("музык", "трек", "песн", "плейлист", "music", "song")
        if (musicKeywords.any { q.contains(it) }) {
            val musicApp = installedPackages.firstOrNull {
                it.packageName.contains("spotify") ||
                        it.packageName.contains("music") ||
                        it.packageName.contains("yandex.music") ||
                        it.packageName.contains("sound")
            }
            if (musicApp != null) {
                return SemanticAction(
                    targetPackage = musicApp.packageName,
                    label = musicApp.label,
                    reason = "Музыка"
                )
            }
        }

        val navKeywords = listOf("навигатор", "маршрут", "пробк", "карты", "maps", "navigate")
        if (navKeywords.any { q.contains(it) }) {
            val mapsApp = installedPackages.firstOrNull {
                it.packageName.contains("maps") ||
                        it.packageName.contains("yandex.yandexnavi") ||
                        it.packageName.contains("2gis")
            }
            if (mapsApp != null) {
                return SemanticAction(
                    targetPackage = mapsApp.packageName,
                    label = mapsApp.label,
                    reason = "Навигация"
                )
            }
        }

        val taxiKeywords = listOf("такси", "доехать", "taxi", "uber")
        if (taxiKeywords.any { q.contains(it) }) {
            val taxiApp = installedPackages.firstOrNull {
                it.packageName.contains("taxi") ||
                        it.packageName.contains("uber") ||
                        it.packageName.contains("yandex.taximeter")
            }
            if (taxiApp != null) {
                return SemanticAction(
                    targetPackage = taxiApp.packageName,
                    label = taxiApp.label,
                    reason = "Такси"
                )
            }
        }

        return null
    }
}
