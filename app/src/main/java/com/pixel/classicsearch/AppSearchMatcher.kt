package com.pixel.classicsearch

data class AppSearchCandidate(
    val packageName: String,
    val label: String
)

/**
 * Pure, deterministic app search matching engine for Pixel OneSearch fallback.
 * Resolves keyboard Enter and tap queries to the most relevant application launch target,
 * with support for cross-layout typing (QWERTY/JCUKEN), phonetic transliteration,
 * package segment matching, and prefix/word-boundary resolution.
 */
object AppSearchMatcher {

    private val LATIN_TO_CYRILLIC_PHONETIC = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е",
        'f' to "ф", 'g' to "г", 'h' to "х", 'i' to "и", 'j' to "дж",
        'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о",
        'p' to "п", 'q' to "к", 'r' to "р", 's' to "с", 't' to "т",
        'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "й", 'z' to "з"
    )

    private val QWERTY_TO_JCUKEN = mapOf(
        'q' to 'й', 'w' to 'ц', 'e' to 'у', 'r' to 'к', 't' to 'е', 'y' to 'н',
        'u' to 'г', 'i' to 'ш', 'o' to 'щ', 'p' to 'з', '[' to 'х', ']' to 'ъ',
        'a' to 'ф', 's' to 'ы', 'd' to 'в', 'f' to 'а', 'g' to 'п', 'h' to 'р',
        'j' to 'о', 'k' to 'л', 'l' to 'д', ';' to 'ж', '\'' to 'э', 'z' to 'я',
        'x' to 'ч', 'c' to 'с', 'v' to 'м', 'b' to 'и', 'n' to 'т', 'm' to 'ь',
        ',' to 'б', '.' to 'ю'
    )

    fun findBestMatch(candidates: List<AppSearchCandidate>, rawQuery: String?): AppSearchCandidate? {
        val query = rawQuery?.trim()?.lowercase() ?: return null
        if (query.isEmpty() || candidates.isEmpty()) return null

        val queryPhonetic = query.map { LATIN_TO_CYRILLIC_PHONETIC[it] ?: it.toString() }.joinToString("")
        val queryJcuken = query.map { QWERTY_TO_JCUKEN[it] ?: it }.joinToString("")

        val queryVariants = linkedSetOf(query, queryPhonetic, queryJcuken).filter { it.isNotBlank() }

        // 1. Exact match on app label
        for (q in queryVariants) {
            candidates.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return it }
        }

        // 2. Exact match on package name
        candidates.firstOrNull { it.packageName.equals(query, ignoreCase = true) }?.let { return it }

        // 3. Prefix match on app label (e.g. "Tele" -> "Telegram", "Ав" -> "Авито")
        for (q in queryVariants) {
            val prefixMatches = candidates.filter { it.label.startsWith(q, ignoreCase = true) }
            if (prefixMatches.isNotEmpty()) {
                return prefixMatches.minByOrNull { it.label.length }
            }
        }

        // 4. Word-boundary prefix match (e.g. "Play" -> "Google Play Store", "RU" -> "PROFI.RU")
        for (q in queryVariants) {
            val wordMatches = candidates.filter { candidate ->
                candidate.label.split(" ", "-", "_", ".").any { word ->
                    word.startsWith(q, ignoreCase = true)
                }
            }
            if (wordMatches.isNotEmpty()) {
                return wordMatches.minByOrNull { it.label.length }
            }
        }

        // 5. Package segment prefix match (e.g. "av" -> "com.avito.android", "profi" -> "ru.profi.client")
        for (q in queryVariants) {
            val pkgSegmentMatches = candidates.filter { candidate ->
                candidate.packageName.split(".").any { segment ->
                    segment.startsWith(q, ignoreCase = true)
                }
            }
            if (pkgSegmentMatches.isNotEmpty()) {
                return pkgSegmentMatches.minByOrNull { it.label.length }
            }
        }

        // 6. Contains match on app label
        for (q in queryVariants) {
            if (q.length >= 2) {
                val containsMatches = candidates.filter { it.label.contains(q, ignoreCase = true) }
                if (containsMatches.isNotEmpty()) {
                    return containsMatches.minByOrNull { it.label.length }
                }
            }
        }

        return null
    }
}
