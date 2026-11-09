package com.pixel.classicsearch

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

data class BangRoute(
    val prefix: String,
    val engineOrTarget: String,
    val displayTitle: String,
    val cleanQuery: String,
    val isYouTube: Boolean = false
)

/**
 * Pure Kotlin math expression evaluator and !bang command parser for QuickSearch Overlay.
 */
object QuickCalculator {

    private val PERCENT_OF_REGEX = Regex(
        """^\s*(-?\d+(?:[.,]\d+)?)\s*%\s*(?:of|от|\*)\s*(-?\d+(?:[.,]\d+)?)\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val ALLOWED_MATH_CHARS = Regex("""^[\d\s+*\-/^().,×÷%]+$""")
    private val OPERATOR_CHARS = setOf('+', '-', '*', '/', '^', '×', '÷', '%')

    /**
     * Evaluates a mathematical expression if the input is a valid compound arithmetic formula.
     * Returns formatted result string (e.g. "4 410" or "3.14159") or null if not a math expression.
     */
    fun evaluate(rawInput: String?): String? {
        val input = rawInput?.trim() ?: return null
        if (input.length < 3) return null

        // 1. Check "15% of 2400" / "15% от 2400"
        val percentMatch = PERCENT_OF_REGEX.matchEntire(input)
        if (percentMatch != null) {
            val pct = percentMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val base = percentMatch.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
            return formatNumber((pct / 100.0) * base)
        }

        // 2. General arithmetic expression check
        if (!ALLOWED_MATH_CHARS.matches(input)) return null

        val normalized = input
            .replace('×', '*')
            .replace('÷', '/')
            .replace(',', '.')
            .replace(" ", "")

        // Must contain at least one binary operator (not just a single number like "2026" or "-42")
        val withoutLeadingSign = if (normalized.startsWith("-") || normalized.startsWith("+")) {
            normalized.substring(1)
        } else {
            normalized
        }
        if (withoutLeadingSign.none { it in OPERATOR_CHARS }) return null
        if (normalized.last() in setOf('+', '-', '*', '/', '^', '.')) return null

        return try {
            val value = Parser(normalized).parse()
            if (value.isNaN() || value.isInfinite()) null else formatNumber(value)
        } catch (_: Exception) {
            null
        }
    }

    internal fun formatNumber(value: Double): String {
        val bd = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    /**
     * Parses !bang commands like "!p quantum physics", "!c write code", "!g news", "!y music".
     */
    fun parseBangCommand(rawInput: String?): BangRoute? {
        val trimmed = rawInput?.trim() ?: return null
        if (!trimmed.startsWith("!")) return null

        val spaceIdx = trimmed.indexOf(' ')
        val token = (if (spaceIdx > 0) trimmed.substring(0, spaceIdx) else trimmed).lowercase()
        val query = if (spaceIdx > 0) trimmed.substring(spaceIdx + 1).trim() else ""

        return when (token) {
            "!g", "!google" -> BangRoute(token, WidgetPrefs.SEARCH_ENGINE_GOOGLE, "Google Search", query)
            "!p", "!perplexity" -> BangRoute(token, WidgetPrefs.SEARCH_ENGINE_PERPLEXITY, "Perplexity", query)
            "!c", "!gpt", "!chatgpt" -> BangRoute(token, WidgetPrefs.SEARCH_ENGINE_CHATGPT, "ChatGPT", query)
            "!gem", "!gemini" -> BangRoute(token, WidgetPrefs.SEARCH_ENGINE_GEMINI, "Google Gemini", query)
            "!ai", "!aimode" -> BangRoute(token, WidgetPrefs.SEARCH_ENGINE_AI_MODE, "Search AI Mode", query)
            "!cl", "!claude" -> BangRoute(token, WidgetPrefs.SEARCH_ENGINE_CLAUDE, "Claude", query)
            "!cop", "!copilot" -> BangRoute(token, WidgetPrefs.SEARCH_ENGINE_COPILOT, "Microsoft Copilot", query)
            "!y", "!yt", "!youtube" -> BangRoute(token, "youtube", "YouTube", query, isYouTube = true)
            else -> null
        }
    }

    private class Parser(private val expr: String) {
        private var pos = -1
        private var ch = 0

        private fun nextChar() {
            ch = if (++pos < expr.length) expr[pos].code else -1
        }

        private fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < expr.length) throw IllegalArgumentException("Unexpected char: ${ch.toChar()}")
            return x
        }

        // Grammar:
        // expression = term | expression `+` term | expression `-` term
        // term = factor | term `*` factor | term `/` factor | term `%` factor
        // factor = `+` factor | `-` factor | `(` expression `)` | number | factor `^` factor | factor `%`
        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor()
                    eat('/'.code) -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) return Double.NaN
                        x /= divisor
                    }
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()

            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                if (!eat(')'.code)) throw IllegalArgumentException("Missing ')'")
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = expr.substring(startPos, pos).toDouble()
            } else {
                throw IllegalArgumentException("Unexpected: ${ch.toChar()}")
            }

            if (eat('^'.code)) {
                x = x.pow(parseFactor())
            }
            if (eat('%'.code)) {
                x /= 100.0
            }

            return x
        }
    }
}
