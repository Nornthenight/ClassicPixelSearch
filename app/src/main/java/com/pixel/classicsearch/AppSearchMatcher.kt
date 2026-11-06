package com.pixel.classicsearch

data class AppSearchCandidate(
    val packageName: String,
    val label: String
)

/**
 * Pure, deterministic app search matching engine for Pixel OneSearch fallback.
 * Resolves keyboard Enter and tap queries to the most relevant application launch target.
 */
object AppSearchMatcher {

    fun findBestMatch(candidates: List<AppSearchCandidate>, rawQuery: String?): AppSearchCandidate? {
        val query = rawQuery?.trim() ?: return null
        if (query.isEmpty() || candidates.isEmpty()) return null

        // 1. Exact match on app label (case-insensitive)
        candidates.firstOrNull { it.label.equals(query, ignoreCase = true) }?.let {
            return it
        }

        // 2. Exact match on package name (case-insensitive)
        candidates.firstOrNull { it.packageName.equals(query, ignoreCase = true) }?.let {
            return it
        }

        // 3. Prefix match on app label (e.g. "Tele" -> "Telegram")
        val prefixMatches = candidates.filter { it.label.startsWith(query, ignoreCase = true) }
        if (prefixMatches.isNotEmpty()) {
            return prefixMatches.minByOrNull { it.label.length }
        }

        // 4. Word-boundary prefix match (e.g. "Play" -> "Google Play Store")
        val wordMatches = candidates.filter { candidate ->
            candidate.label.split(" ", "-", "_", ".").any { word ->
                word.startsWith(query, ignoreCase = true)
            }
        }
        if (wordMatches.isNotEmpty()) {
            return wordMatches.minByOrNull { it.label.length }
        }

        return null
    }
}
