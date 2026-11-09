package com.pixel.classicsearch

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants

class SearchTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        suppressTransitions()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        triggerHapticFeedback()

        val target = intent?.getStringExtra(EXTRA_TAP_TARGET)
        when (target) {
            TARGET_LOGO -> launchGoogleMain()
            TARGET_SEARCH -> launchGoogleSearch(intent)
            TARGET_MIC -> dispatchAction(WidgetPrefs.getMicAction(this), WidgetPrefs.ACTION_VOICE_SEARCH)
            TARGET_MIDDLE -> dispatchAction(WidgetPrefs.getMiddleAction(this), WidgetPrefs.ACTION_GEMINI)
            TARGET_AI -> dispatchAction(WidgetPrefs.getAiAction(this), WidgetPrefs.ACTION_AI_MODE)
            else -> {
                // Stock search dock entry point from Pixel Launcher (via selected_search_engine)
                launchGoogleSearch(intent)
            }
        }

        finish()
        suppressTransitions()
    }

    private fun triggerHapticFeedback() {
        if (!WidgetPrefs.isHapticEnabled(this)) return

        val style = WidgetPrefs.getHapticStyle(this)
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val effectId = if (style == WidgetPrefs.HAPTIC_CONTEXT_CLICK) {
                VibrationEffect.EFFECT_CLICK
            } else {
                VibrationEffect.EFFECT_TICK
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18L)
            }
        } catch (_: Exception) {}
    }

    private fun launchGoogleMain() {
        val primary = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(
                GOOGLE_PACKAGE,
                "com.google.android.googlequicksearchbox.SearchActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fallback = (packageManager.getLaunchIntentForPackage(GOOGLE_PACKAGE)
            ?: Intent(Intent.ACTION_WEB_SEARCH)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        safeStartActivity(resolveIntentWithFallback(primary, fallback))
    }

    private fun launchGoogleSearch(intent: Intent?) {
        val query = intent?.getStringExtra(SearchManager.QUERY)
            ?: intent?.getStringExtra("query")
            ?: intent?.getStringExtra("q")
            ?: intent?.getStringExtra("search_query")
            ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.data?.getQueryParameter("q")
            ?: intent?.data?.getQueryParameter("query")
            ?: intent?.data?.getQueryParameter(SearchManager.QUERY)

        val trimmedQuery = query?.trim()
        val defaultEngine = WidgetPrefs.getDefaultSearchAi(this)

        if (!trimmedQuery.isNullOrBlank()) {
            val bang = QuickCalculator.parseBangCommand(trimmedQuery)
            if (bang != null) {
                val bangIntent = if (bang.isYouTube) {
                    val encoded = Uri.encode(bang.cleanQuery)
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    AiSearchDispatcher.createSearchIntent(this, bang.engineOrTarget, bang.cleanQuery)
                }
                safeStartActivity(bangIntent)
                return
            }

            val matchedApp = findMatchingAppLaunchIntent(trimmedQuery)
            if (matchedApp != null) {
                safeStartActivity(matchedApp)
                return
            }

            val searchIntent = AiSearchDispatcher.createSearchIntent(this, defaultEngine, trimmedQuery)
            safeStartActivity(searchIntent)
            return
        }

        // Empty query (tap on search capsule or dock bar): open QuickSearch Overlay if enabled
        if (WidgetPrefs.isQuickOverlayEnabled(this)) {
            val overlayIntent = Intent(this, QuickSearchActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            safeStartActivity(overlayIntent)
            return
        }

        // Fallback when QuickSearch Overlay is disabled: open default AI or Google Search
        if (defaultEngine != WidgetPrefs.SEARCH_ENGINE_GOOGLE) {
            val emptyIntent = AiSearchDispatcher.createSearchIntent(this, defaultEngine, "")
            safeStartActivity(emptyIntent)
            return
        }

        val primary = Intent("com.google.android.googlequicksearchbox.GOOGLE_SEARCH").apply {
            component = ComponentName(
                GOOGLE_PACKAGE,
                "com.google.android.googlequicksearchbox.ImplicitGoogleSearchEntrypointInternal"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fallback = Intent("com.google.android.googlequicksearchbox.GOOGLE_SEARCH").apply {
            setPackage(GOOGLE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }.let { pkgIntent ->
            if (packageManager.resolveActivity(pkgIntent, 0) != null) {
                pkgIntent
            } else {
                Intent(Intent.ACTION_WEB_SEARCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        safeStartActivity(resolveIntentWithFallback(primary, fallback))
    }

    private fun findMatchingAppLaunchIntent(query: String): Intent? {
        return try {
            val candidates = AppLaunchCache.getCandidates(this)
            if (candidates.isEmpty()) return null

            val bestMatch = AppSearchMatcher.findBestMatch(candidates, query) ?: return null
            packageManager.getLaunchIntentForPackage(bestMatch.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun dispatchAction(action: String, defaultAction: String) {
        val resolvedAction = if (action == WidgetPrefs.ACTION_DEFAULT) defaultAction else action

        when {
            resolvedAction == WidgetPrefs.ACTION_VOICE_SEARCH -> {
                val primary = Intent("com.google.android.googlequicksearchbox.action.CLASSIC_GSA_VOICE_SEARCH").apply {
                    addCategory("com.google.android.googlequicksearchbox.category.SEARCH_WIDGET")
                    component = ComponentName(
                        GOOGLE_PACKAGE,
                        "com.google.android.googlequicksearchbox.ImplicitGsaVoiceSearchEntrypointInternal"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val fallback = Intent(RecognizerIntent.ACTION_WEB_SEARCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                safeStartActivity(resolveIntentWithFallback(primary, fallback))
            }
            resolvedAction == WidgetPrefs.ACTION_GEMINI -> {
                val primary = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(
                        GEMINI_PACKAGE,
                        "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val fallback = (packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com"))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                safeStartActivity(resolveIntentWithFallback(primary, fallback))
            }
            resolvedAction == WidgetPrefs.ACTION_GOOGLE_LENS -> {
                val lensUriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google://lens")).apply {
                    setPackage(LENS_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val lensLaunchIntent = packageManager.getLaunchIntentForPackage(LENS_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val fallbackWeb = Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val target = resolveIntentWithFallback(lensUriIntent, lensLaunchIntent ?: fallbackWeb)
                safeStartActivity(target)
            }
            resolvedAction == WidgetPrefs.ACTION_AI_MODE -> {
                val primary = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/gasearch?udm=50")).apply {
                    component = ComponentName(
                        GOOGLE_PACKAGE,
                        "com.google.android.apps.search.googleapp.search.deeplinks.SearchDeepLink"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?udm=50")).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                safeStartActivity(resolveIntentWithFallback(primary, fallback))
            }
            resolvedAction == WidgetPrefs.ACTION_CHATGPT -> {
                val appIntent = packageManager.getLaunchIntentForPackage(CHATGPT_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val fallbackWeb = Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                safeStartActivity(appIntent ?: fallbackWeb)
            }
            resolvedAction == WidgetPrefs.ACTION_PERPLEXITY -> {
                val appIntent = packageManager.getLaunchIntentForPackage(PERPLEXITY_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val fallbackWeb = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.perplexity.ai")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                safeStartActivity(appIntent ?: fallbackWeb)
            }
            resolvedAction == WidgetPrefs.ACTION_QR_SCANNER -> {
                val gmsProxy = Intent().apply {
                    component = ComponentName(
                        "com.google.android.gms",
                        "com.google.android.gms.mlkit.barcode.ui.PlatformBarcodeScanningActivityProxy"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val zxing = Intent("com.google.zxing.client.android.SCAN").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val lensApp = packageManager.getLaunchIntentForPackage(LENS_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                when {
                    packageManager.resolveActivity(gmsProxy, 0) != null -> safeStartActivity(gmsProxy)
                    packageManager.resolveActivity(zxing, 0) != null -> safeStartActivity(zxing)
                    lensApp != null -> safeStartActivity(lensApp)
                    else -> safeStartActivity(cameraIntent)
                }
            }
            resolvedAction == WidgetPrefs.ACTION_BROWSER_BRAVE -> {
                val appIntent = packageManager.getLaunchIntentForPackage(BRAVE_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://search.brave.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                safeStartActivity(appIntent ?: fallback)
            }
            resolvedAction == WidgetPrefs.ACTION_BROWSER_FIREFOX -> {
                val appIntent = packageManager.getLaunchIntentForPackage(FIREFOX_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                safeStartActivity(appIntent ?: fallback)
            }
            resolvedAction == WidgetPrefs.ACTION_BROWSER_CROMITE -> {
                val appIntent = packageManager.getLaunchIntentForPackage(CROMITE_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                safeStartActivity(appIntent ?: fallback)
            }
            resolvedAction.startsWith(WidgetPrefs.PREFIX_APP) -> {
                val packageName = resolvedAction.removePrefix(WidgetPrefs.PREFIX_APP)
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    safeStartActivity(launchIntent)
                } else {
                    launchGoogleSearch(null)
                }
            }
            else -> launchGoogleSearch(null)
        }
    }

    private fun resolveIntentWithFallback(primary: Intent, fallback: Intent): Intent {
        return if (packageManager.resolveActivity(primary, 0) != null) {
            primary
        } else {
            fallback
        }
    }

    private fun safeStartActivity(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_WEB_SEARCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
        }
    }

    private fun suppressTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_TAP_TARGET = "extra_tap_target"
        const val TARGET_LOGO = "logo"
        const val TARGET_SEARCH = "search"
        const val TARGET_MIC = "mic"
        const val TARGET_MIDDLE = "middle"
        const val TARGET_AI = "ai"

        const val REQUEST_CODE_LOGO = 200
        const val REQUEST_CODE_SEARCH = 201
        const val REQUEST_CODE_MIC = 202
        const val REQUEST_CODE_MIDDLE = 203
        const val REQUEST_CODE_AI = 204

        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val GEMINI_PACKAGE = "com.google.android.apps.bard"
        private const val LENS_PACKAGE = "com.google.ar.lens"
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val PERPLEXITY_PACKAGE = "ai.perplexity.app.android"
        private const val BRAVE_PACKAGE = "com.brave.browser"
        private const val FIREFOX_PACKAGE = "org.mozilla.firefox"
        private const val CROMITE_PACKAGE = "org.cromite.cromite"
    }
}
