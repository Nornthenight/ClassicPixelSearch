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

    private var themeCustomizationObserver: ContentObserver? = null
    private var colorsChangedListener: WallpaperManager.OnColorsChangedListener? = null
    private var debouncedUpdateRunnable: Runnable? = null
    private var highlightRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "LauncherStateService connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.notificationTimeout = 50
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
                ensureClassicSearchEngine()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleSearchInputEvent(event)
            }
        }
    }

    private fun handleSearchInputEvent(event: AccessibilityEvent) {
        val source = try { event.source } catch (_: Exception) { null } ?: return
        try {
            val viewId = source.viewIdResourceName
            if (viewId == ID_SEARCH_INPUT) {
                val text = source.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
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
            highlightFirstSearchResult()
            highlightRunnable = null
        }
        highlightRunnable = runnable
        mainHandler.postDelayed(runnable, 80L)
    }

    private fun highlightFirstSearchResult() {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            val resultContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_RESULTS)
                .ifEmpty { root.findAccessibilityNodeInfosByViewId(ID_APPS_LIST) }

            for (container in resultContainers) {
                if (container.childCount > 0) {
                    val firstChild = container.getChild(0)
                    if (firstChild != null) {
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

            // 1. Try clicking the first accessibility-focused or first child item in search results
            val resultContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_RESULTS)
                .ifEmpty { root.findAccessibilityNodeInfosByViewId(ID_APPS_LIST) }

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

            // 2. Direct match fallback: read query from ID_SEARCH_INPUT and launch best candidate
            val inputs = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_INPUT)
            val query = inputs.firstOrNull()?.text?.toString()?.trim()
            inputs.forEach { try { it.recycle() } catch (_: Exception) {} }

            if (!query.isNullOrEmpty()) {
                return launchBestMatchingApp(query)
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
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val candidates = resolveInfos.map {
                AppSearchCandidate(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString()
                )
            }
            val best = AppSearchMatcher.findBestMatch(candidates, query)
            if (best != null) {
                val launchIntent = pm.getLaunchIntentForPackage(best.packageName)?.apply {
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
