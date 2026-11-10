package com.pixel.classicsearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.WallpaperManager
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * Monolithic zero-flicker launcher state & theme synchronizer with
 * non-intrusive Accessibility result-highlighting and Enter QuickLaunch.
 *
 * Guarantees:
 * 1. ZERO FLICKER: selected_search_engine is never dynamically switched to Google,
 *    keeping the desktop dock 100% solid and flicker-free during rapid swipes.
 * 2. APP HIGHLIGHT: highlights top matched app cards in Pixel Launcher (e.g. "АВ" -> "Авито").
 * 3. ENTER QUICKLAUNCH: pressing Enter activates the highlighted card or launches the matched app.
 */
class LauncherStateService : AccessibilityService() {

    companion object {
        private const val TAG = "LauncherStateService"
        private const val PIXEL_LAUNCHER_PKG = "com.google.android.apps.nexuslauncher"
        private const val ID_SEARCH_INPUT = "com.google.android.apps.nexuslauncher:id/input"
        private const val ID_SEARCH_ALL_APPS = "com.google.android.apps.nexuslauncher:id/search_container_all_apps"
        private const val ID_SEARCH_RESULTS = "com.google.android.apps.nexuslauncher:id/search_results_list_view"
        private const val ID_APPS_LIST = "com.google.android.apps.nexuslauncher:id/apps_list_view"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var currentEngine: String? = null
    @Volatile private var lastEngineCheckTime: Long = 0L
    @Volatile private var lastOverlayLaunchTime: Long = 0L

    private var themeCustomizationObserver: ContentObserver? = null
    private var colorsChangedListener: WallpaperManager.OnColorsChangedListener? = null
    private var debouncedUpdateRunnable: Runnable? = null
    private var highlightRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "LauncherStateService connected in active zero-flicker highlight mode")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.notificationTimeout = 0
        serviceInfo = info

        // Register ContentObserver on theme_customization_overlay_packages
        try {
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    Log.i(TAG, "Theme customization updated -> debounced update")
                    triggerDebouncedWidgetUpdate()
                }
            }
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor("theme_customization_overlay_packages"),
                false,
                observer
            )
            themeCustomizationObserver = observer
            Log.i(TAG, "Registered themeCustomizationObserver successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register themeCustomizationObserver", e)
        }

        // Register OnColorsChangedListener for instant Material You Monet color adaptation
        try {
            val wm = getSystemService(WallpaperManager::class.java)
            val listener = WallpaperManager.OnColorsChangedListener { _, _ ->
                Log.i(TAG, "Wallpaper colors changed -> debounced update")
                triggerDebouncedWidgetUpdate()
            }
            wm?.addOnColorsChangedListener(listener, mainHandler)
            colorsChangedListener = listener
            Log.i(TAG, "Registered colorsChangedListener successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot register OnColorsChangedListener", e)
        }

        if (WidgetPrefs.isReplaceDockEnabled(this)) {
            ensureClassicSearchEngine()
        }
        AppLaunchCache.warmUpAsync(this)
    }

    private fun triggerDebouncedWidgetUpdate() {
        debouncedUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.i(TAG, "Triggering widget update due to theme/wallpaper change")
            ClassicSearchWidgetProvider.updateAllWidgets(this)
            debouncedUpdateRunnable = null
        }
        debouncedUpdateRunnable = runnable
        mainHandler.postDelayed(runnable, 150L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!WidgetPrefs.isReplaceDockEnabled(this)) return

        val pkg = event.packageName?.toString()
        if (pkg != null && pkg != PIXEL_LAUNCHER_PKG) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                highlightRunnable?.let {
                    mainHandler.removeCallbacks(it)
                    highlightRunnable = null
                }
                ensureClassicSearchEngine()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleSearchInputEvent(event)
            }
        }
    }

    private fun launchQuickOverlayNow(queryText: String) {
        val overlayIntent = Intent(this, QuickSearchActivity::class.java).apply {
            if (queryText.isNotEmpty()) {
                putExtra("query", queryText)
            }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        startActivity(overlayIntent)
    }

    private fun handleSearchInputEvent(event: AccessibilityEvent) {
        val source = try { event.source } catch (_: Exception) { null } ?: return
        try {
            val viewId = source.viewIdResourceName
            if (viewId == ID_SEARCH_INPUT || viewId == ID_SEARCH_ALL_APPS) {
                val rawText = source.text?.toString()?.trim() ?: ""
                val hintText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    source.hintText?.toString()?.trim() ?: ""
                } else ""
                val queryText = if (rawText.isNotEmpty() && rawText != hintText) rawText else ""

                // Only intercept on explicit finger click on the App Drawer search bar (never hijack swipe-up!)
                if (WidgetPrefs.isQuickOverlayEnabled(this) &&
                    event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                ) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastOverlayLaunchTime > 500L) {
                        lastOverlayLaunchTime = now
                        try {
                            source.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                        } catch (_: Exception) {}
                        launchQuickOverlayNow(queryText)
                        return
                    }
                }

                if (queryText.isNotEmpty()) {
                    scheduleHighlightFirstResult()
                }
            }
        } finally {
            try { source.recycle() } catch (_: Exception) {}
        }
    }

    private fun scheduleHighlightFirstResult() {
        highlightRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            highlightFirstResult()
            highlightRunnable = null
        }
        highlightRunnable = runnable
        mainHandler.postDelayed(runnable, 50L)
    }

    private fun highlightFirstResult() {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            val pkg = root.packageName?.toString()
            if (pkg != PIXEL_LAUNCHER_PKG) return

            val resultContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_RESULTS)
            for (container in resultContainers) {
                if (container.childCount > 0) {
                    val firstChild = container.getChild(0)
                    if (firstChild != null) {
                        firstChild.performAction(AccessibilityNodeInfo.ACTION_SELECT)
                        firstChild.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                        try { firstChild.recycle() } catch (_: Exception) {}
                        break
                    }
                }
                try { container.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to highlight search result", e)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            if (launchFirstResultOrMatchingApp()) {
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun launchFirstResultOrMatchingApp(): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        try {
            val pkg = root.packageName?.toString()
            if (pkg != PIXEL_LAUNCHER_PKG) return false

            // Read query from ID_SEARCH_INPUT first to decide whether it's an app query or AI question
            val inputs = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_INPUT)
            val query = inputs.firstOrNull()?.text?.toString()?.trim()
            inputs.forEach { try { it.recycle() } catch (_: Exception) {} }

            if (!query.isNullOrEmpty()) {
                // 1. Check !bang command
                val bang = QuickCalculator.parseBangCommand(query)
                if (bang != null) {
                    val bangIntent = if (bang.isYouTube) {
                        val encoded = Uri.encode(bang.cleanQuery)
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    } else {
                        AiSearchDispatcher.createSearchIntent(this, bang.engineOrTarget, bang.cleanQuery)
                    }
                    startActivity(bangIntent)
                    return true
                }

                // 2. Check AppSearchMatcher via in-memory AppLaunchCache
                if (launchBestMatchingApp(query)) {
                    return true
                }

                // 3. If not an explicit question and single word, try clicking top card in ID_SEARCH_RESULTS
                if (!query.contains('?') && !query.contains(' ')) {
                    val resultContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_RESULTS)
                    for (container in resultContainers) {
                        if (container.childCount > 0) {
                            val firstChild = container.getChild(0)
                            if (firstChild != null) {
                                val clicked = firstChild.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                try { firstChild.recycle() } catch (_: Exception) {}
                                if (clicked) return true
                            }
                        }
                        try { container.recycle() } catch (_: Exception) {}
                    }
                }

                // 4. Route non-app query / question directly to the default AI service
                val defaultEngine = WidgetPrefs.getDefaultSearchAi(this)
                val aiIntent = AiSearchDispatcher.createSearchIntent(this, defaultEngine, query)
                startActivity(aiIntent)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch result on enter", e)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
        return false
    }

    private fun launchBestMatchingApp(query: String): Boolean {
        return try {
            val candidates = AppLaunchCache.getCandidates(this)
            val best = AppSearchMatcher.findBestMatch(candidates, query)
            if (best != null) {
                val launchIntent = packageManager.getLaunchIntentForPackage(best.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error matching app for query $query", e)
            false
        }
    }

    private fun ensureClassicSearchEngine() {
        val targetEngine = LauncherEnginePolicy.resolveTargetEngine(WidgetPrefs.isReplaceDockEnabled(this))
            ?: return
        val now = SystemClock.elapsedRealtime()
        if (currentEngine == targetEngine && now - lastEngineCheckTime < 2000L) {
            return
        }
        lastEngineCheckTime = now
        applyEngine(targetEngine)
    }

    private fun applyEngine(targetEngine: String) {
        if (currentEngine == targetEngine) return
        currentEngine = targetEngine

        executor.execute {
            try {
                if (!WidgetPrefs.isReplaceDockEnabled(this)) {
                    currentEngine = null
                    return@execute
                }
                val currentInSettings = Settings.Secure.getString(
                    contentResolver,
                    ServiceHelper.KEY_SELECTED_SEARCH_ENGINE
                )
                if (LauncherEnginePolicy.shouldUpdateEngine(currentInSettings, targetEngine)) {
                    val wrote = Settings.Secure.putString(
                        contentResolver,
                        ServiceHelper.KEY_SELECTED_SEARCH_ENGINE,
                        targetEngine
                    )
                    if (wrote) {
                        currentEngine = targetEngine
                        Log.i(TAG, "Enforced selected_search_engine -> $targetEngine")
                    }
                } else {
                    currentEngine = targetEngine
                }
            } catch (e: Exception) {
                currentEngine = null
                Log.e(TAG, "Failed to update selected_search_engine to $targetEngine", e)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "Configuration changed -> updating widgets")
        ClassicSearchWidgetProvider.updateAllWidgets(this)
    }

    override fun onInterrupt() {
        Log.w(TAG, "LauncherStateService interrupted")
    }

    override fun onDestroy() {
        debouncedUpdateRunnable?.let {
            mainHandler.removeCallbacks(it)
            debouncedUpdateRunnable = null
        }
        highlightRunnable?.let {
            mainHandler.removeCallbacks(it)
            highlightRunnable = null
        }
        themeCustomizationObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
            themeCustomizationObserver = null
        }
        colorsChangedListener?.let {
            try {
                val wm = getSystemService(WallpaperManager::class.java)
                wm?.removeOnColorsChangedListener(it)
            } catch (_: Exception) {}
            colorsChangedListener = null
        }
        executor.shutdown()
        super.onDestroy()
        Log.i(TAG, "LauncherStateService destroyed")
    }
}
