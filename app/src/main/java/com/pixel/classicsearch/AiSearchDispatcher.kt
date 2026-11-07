package com.pixel.classicsearch

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * Dispatches Enter keyboard queries and drawer searches to the configured
 * default Search or AI service (ChatGPT, Perplexity, Gemini, Copilot, Claude, Google).
 */
object AiSearchDispatcher {

    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    const val PERPLEXITY_PACKAGE = "ai.perplexity.app.android"
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"
    const val COPILOT_PACKAGE = "com.microsoft.copilot"
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /**
     * Builds the web query URL for the chosen engine.
     */
    fun buildSearchUrl(engine: String, query: String): String {
        val encoded = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            query
        }
        return when (engine) {
            WidgetPrefs.SEARCH_ENGINE_PERPLEXITY -> "https://www.perplexity.ai/search?q=$encoded"
            WidgetPrefs.SEARCH_ENGINE_CHATGPT -> "https://chatgpt.com/?q=$encoded"
            WidgetPrefs.SEARCH_ENGINE_AI_MODE -> "https://www.google.com/search?udm=50&q=$encoded"
            WidgetPrefs.SEARCH_ENGINE_GEMINI -> "https://gemini.google.com/app"
            WidgetPrefs.SEARCH_ENGINE_COPILOT -> "https://copilot.microsoft.com/?q=$encoded"
            WidgetPrefs.SEARCH_ENGINE_CLAUDE -> "https://claude.ai/new?q=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
    }

    /**
     * Returns the target Android package name associated with the engine, if any.
     */
    fun getTargetPackage(engine: String): String? {
        return when (engine) {
            WidgetPrefs.SEARCH_ENGINE_GOOGLE -> GOOGLE_PACKAGE
            WidgetPrefs.SEARCH_ENGINE_CHATGPT -> CHATGPT_PACKAGE
            WidgetPrefs.SEARCH_ENGINE_PERPLEXITY -> PERPLEXITY_PACKAGE
            WidgetPrefs.SEARCH_ENGINE_GEMINI -> GEMINI_PACKAGE
            WidgetPrefs.SEARCH_ENGINE_AI_MODE -> GOOGLE_PACKAGE
            WidgetPrefs.SEARCH_ENGINE_COPILOT -> COPILOT_PACKAGE
            WidgetPrefs.SEARCH_ENGINE_CLAUDE -> CLAUDE_PACKAGE
            else -> if (engine.startsWith(WidgetPrefs.PREFIX_APP)) {
                engine.removePrefix(WidgetPrefs.PREFIX_APP)
            } else {
                null
            }
        }
    }

    /**
     * Creates an Intent to launch the AI or search service with the query pre-filled.
     * Always resolves with fallback so execution is 100% crash-safe.
     */
    fun createSearchIntent(context: Context, engine: String, query: String): Intent {
        val trimmedQuery = query.trim()
        val pm = context.packageManager

        when (engine) {
            WidgetPrefs.SEARCH_ENGINE_GOOGLE -> {
                val queryIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    setPackage(GOOGLE_PACKAGE)
                    putExtra(SearchManager.QUERY, trimmedQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val fallback = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, trimmedQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return if (pm.resolveActivity(queryIntent, 0) != null) queryIntent else fallback
            }

            WidgetPrefs.SEARCH_ENGINE_PERPLEXITY -> {
                val url = buildSearchUrl(engine, trimmedQuery)
                val uri = Uri.parse(url)
                val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(PERPLEXITY_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return if (pm.resolveActivity(appIntent, 0) != null) appIntent else browserIntent
            }

            WidgetPrefs.SEARCH_ENGINE_CHATGPT -> {
                val url = buildSearchUrl(engine, trimmedQuery)
                val uri = Uri.parse(url)
                val appViewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(CHATGPT_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val appSendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, trimmedQuery)
                    setPackage(CHATGPT_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return when {
                    pm.resolveActivity(appViewIntent, 0) != null -> appViewIntent
                    pm.resolveActivity(appSendIntent, 0) != null -> appSendIntent
                    else -> browserIntent
                }
            }

            WidgetPrefs.SEARCH_ENGINE_GEMINI -> {
                val url = buildSearchUrl(engine, trimmedQuery)
                val uri = Uri.parse(url)
                val geminiMain = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(
                        GEMINI_PACKAGE,
                        "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                    )
                    putExtra(SearchManager.QUERY, trimmedQuery)
                    putExtra(Intent.EXTRA_TEXT, trimmedQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val geminiLaunch = pm.getLaunchIntentForPackage(GEMINI_PACKAGE)?.apply {
                    putExtra(SearchManager.QUERY, trimmedQuery)
                    putExtra(Intent.EXTRA_TEXT, trimmedQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return when {
                    pm.resolveActivity(geminiMain, 0) != null -> geminiMain
                    geminiLaunch != null -> geminiLaunch
                    else -> webIntent
                }
            }

            WidgetPrefs.SEARCH_ENGINE_AI_MODE -> {
                val url = buildSearchUrl(engine, trimmedQuery)
                val uri = Uri.parse(url)
                val primary = Intent(Intent.ACTION_VIEW, uri).apply {
                    component = ComponentName(
                        GOOGLE_PACKAGE,
                        "com.google.android.apps.search.googleapp.search.deeplinks.SearchDeepLink"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return if (pm.resolveActivity(primary, 0) != null) primary else fallback
            }

            WidgetPrefs.SEARCH_ENGINE_COPILOT -> {
                val url = buildSearchUrl(engine, trimmedQuery)
                val uri = Uri.parse(url)
                val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(COPILOT_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return if (pm.resolveActivity(appIntent, 0) != null) appIntent else browserIntent
            }

            WidgetPrefs.SEARCH_ENGINE_CLAUDE -> {
                val url = buildSearchUrl(engine, trimmedQuery)
                val uri = Uri.parse(url)
                val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(CLAUDE_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return if (pm.resolveActivity(appIntent, 0) != null) appIntent else browserIntent
            }

            else -> {
                if (engine.startsWith(WidgetPrefs.PREFIX_APP)) {
                    val packageName = engine.removePrefix(WidgetPrefs.PREFIX_APP)
                    val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
                        putExtra(SearchManager.QUERY, trimmedQuery)
                        putExtra(Intent.EXTRA_TEXT, trimmedQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launchIntent != null) return launchIntent
                }
                val queryIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    setPackage(GOOGLE_PACKAGE)
                    putExtra(SearchManager.QUERY, trimmedQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val fallback = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, trimmedQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return if (pm.resolveActivity(queryIntent, 0) != null) queryIntent else fallback
            }
        }
    }
}
