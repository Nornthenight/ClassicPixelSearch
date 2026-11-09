package com.pixel.classicsearch

data class AppSearchCandidate(
    val packageName: String,
    val label: String
)

/**
 * Pure, deterministic app search matching engine for Pixel OneSearch and QuickSearch Overlay.
 * Resolves keyboard Enter and live queries to the most relevant application launch targets,
 * with support for bidirectional cross-layout typing (QWERTY <-> JCUKEN),
 * bidirectional phonetic transliteration (EN <-> RU), common phonetic aliases,
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

    private val CYRILLIC_TO_LATIN_PHONETIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
        'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
        'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private val QWERTY_TO_JCUKEN = mapOf(
        'q' to 'й', 'w' to 'ц', 'e' to 'у', 'r' to 'к', 't' to 'е', 'y' to 'н',
        'u' to 'г', 'i' to 'ш', 'o' to 'щ', 'p' to 'з', '[' to 'х', ']' to 'ъ',
        'a' to 'ф', 's' to 'ы', 'd' to 'в', 'f' to 'а', 'g' to 'п', 'h' to 'р',
        'j' to 'о', 'k' to 'л', 'l' to 'д', ';' to 'ж', '\'' to 'э', 'z' to 'я',
        'x' to 'ч', 'c' to 'с', 'v' to 'м', 'b' to 'и', 'n' to 'т', 'm' to 'ь',
        ',' to 'б', '.' to 'ю'
    )

    private val JCUKEN_TO_QWERTY: Map<Char, Char> =
        QWERTY_TO_JCUKEN.entries.associate { (k, v) -> v to k }

    /**
     * Popular Russian-to-English app name aliases and slang so typing "ютуб", "хром", "тг"
     * immediately resolves to YouTube, Chrome, Telegram, etc.
     */
    private val COMMON_ALIASES = mapOf(
        "ютуб" to "youtube",
        "ютьюб" to "youtube",
        "ютубчик" to "youtube",
        "хром" to "chrome",
        "гугл" to "google",
        "телега" to "telegram",
        "телеграм" to "telegram",
        "телеграмм" to "telegram",
        "тг" to "telegram",
        "ватсап" to "whatsapp",
        "вацап" to "whatsapp",
        "вотсап" to "whatsapp",
        "спотифай" to "spotify",
        "спотик" to "spotify",
        "инста" to "instagram",
        "инстаграм" to "instagram",
        "тикток" to "tiktok",
        "дискорд" to "discord",
        "гитхаб" to "github",
        "чатгпт" to "chatgpt",
        "чатджпт" to "chatgpt",
        "гпт" to "chatgpt",
        "перплексити" to "perplexity",
        "клод" to "claude",
        "джемини" to "gemini",
        "гемини" to "gemini",
        "копилот" to "copilot",
        "авито" to "avito",
        "озон" to "ozon",
        "вайлдберриз" to "wildberries",
        "вб" to "wildberries",
        "яндекс" to "yandex",
        "сбер" to "sber",
        "тинькофф" to "tinkoff",
        "тбанк" to "tbank",
        "госуслуги" to "gosuslugi"
    )

    fun findBestMatch(candidates: List<AppSearchCandidate>, rawQuery: String?): AppSearchCandidate? {
        return findMatches(candidates, rawQuery, limit = 1).firstOrNull()
    }

    fun findMatches(
        candidates: List<AppSearchCandidate>,
        rawQuery: String?,
        limit: Int = 5
    ): List<AppSearchCandidate> {
        val query = rawQuery?.trim()?.lowercase() ?: return emptyList()
        if (query.isEmpty() || candidates.isEmpty() || limit <= 0) return emptyList()

        // Bang commands (!g, !p, !c, !y, etc.) are explicitly for search/AI, never app matches
        if (query.startsWith("!")) return emptyList()

        // Explicit questions with '?' should never be intercepted by app matching
        if (query.contains('?')) return emptyList()

        val hasSpaces = query.contains(' ')
        val queryVariants = buildQueryVariants(query)

        val results = LinkedHashSet<AppSearchCandidate>()

        // 1. Exact match on app label
        for (q in queryVariants) {
            candidates.filter { it.label.equals(q, ignoreCase = true) }
                .forEach { results.add(it) }
        }
        if (results.size >= limit) return results.take(limit)

        // 2. Exact match on package name
        candidates.filter { it.packageName.equals(query, ignoreCase = true) }
            .forEach { results.add(it) }
        if (results.size >= limit) return results.take(limit)

        // 3. Prefix match on app label (e.g. "Tele" -> "Telegram", "Ав" -> "Авито")
        for (q in queryVariants) {
            candidates.filter { it.label.startsWith(q, ignoreCase = true) }
                .sortedBy { it.label.length }
                .forEach { results.add(it) }
        }
        if (results.size >= limit) return results.take(limit)

        // Multi-word queries with spaces must not be intercepted by loose single-word or segment matching
        if (hasSpaces) {
            return results.take(limit)
        }

        // 4. Word-boundary prefix match (e.g. "Play" -> "Google Play Store", "RU" -> "PROFI.RU")
        for (q in queryVariants) {
            candidates.filter { candidate ->
                candidate.label.split(" ", "-", "_", ".").any { word ->
                    word.startsWith(q, ignoreCase = true)
                }
            }
                .sortedBy { it.label.length }
                .forEach { results.add(it) }
        }
        if (results.size >= limit) return results.take(limit)

        // 5. Package segment prefix match (e.g. "av" -> "com.avito.android", "profi" -> "ru.profi.client")
        for (q in queryVariants) {
            candidates.filter { candidate ->
                candidate.packageName.split(".").any { segment ->
                    segment.startsWith(q, ignoreCase = true)
                }
            }
                .sortedBy { it.label.length }
                .forEach { results.add(it) }
        }
        if (results.size >= limit) return results.take(limit)

        // 6. Contains match on app label (restricted to >= 3 chars for single words)
        for (q in queryVariants) {
            if (q.length >= 3) {
                candidates.filter { it.label.contains(q, ignoreCase = true) }
                    .sortedBy { it.label.length }
                    .forEach { results.add(it) }
            }
        }

        return results.take(limit)
    }

    internal fun buildQueryVariants(query: String): List<String> {
        val variants = linkedSetOf<String>()
        variants.add(query)

        // Direct alias resolution or prefix alias resolution (e.g. "спот" -> "spotify", "ютуб" -> "youtube")
        COMMON_ALIASES[query]?.let { variants.add(it) }
        if (query.length >= 2) {
            for ((aliasKey, aliasTarget) in COMMON_ALIASES) {
                if (aliasKey.startsWith(query)) {
                    val ratio = query.length.toFloat() / aliasKey.length.toFloat()
                    if (ratio >= 0.5f) {
                        variants.add(aliasTarget)
                    }
                }
            }
        }

        // Latin -> Cyrillic phonetic & QWERTY -> JCUKEN
        val toCyrillicPhonetic = query.map { LATIN_TO_CYRILLIC_PHONETIC[it] ?: it.toString() }.joinToString("")
        val toJcuken = query.map { QWERTY_TO_JCUKEN[it] ?: it }.joinToString("")
        variants.add(toCyrillicPhonetic)
        variants.add(toJcuken)

        // Cyrillic -> Latin phonetic & JCUKEN -> QWERTY
        val toLatinPhonetic = query.map { CYRILLIC_TO_LATIN_PHONETIC[it] ?: it.toString() }.joinToString("")
        val toQwerty = query.map { JCUKEN_TO_QWERTY[it] ?: it }.joinToString("")
        variants.add(toLatinPhonetic)
        variants.add(toQwerty)

        // Also check if JCUKEN/QWERTY switched variant matches an alias (e.g., "телега" typed in QWERTY as "ntktuf")
        COMMON_ALIASES[toJcuken]?.let { variants.add(it) }

        return variants.filter { it.isNotBlank() }
    }
}
