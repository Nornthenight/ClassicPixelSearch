package com.pixel.classicsearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.WallpaperManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

class LauncherStateService : AccessibilityService() {

    companion object {
        private const val TAG = "LauncherStateService"
        private const val PIXEL_LAUNCHER_PKG = "com.google.android.apps.nexuslauncher"
        private const val ID_SEARCH_INPUT = "com.google.android.apps.nexuslauncher:id/input"
        private const val ID_SEARCH_ALL_APPS = "com.google.android.apps.nexuslauncher:id/search_container_all_apps"
        private const val ID_SEARCH_RESULTS = "com.google.android.apps.nexuslauncher:id/search_results_list_view"
        private const val ID_SEARCH_CONTAINER_HOTSEAT = "com.google.android.apps.nexuslauncher:id/search_container_hotseat"
        private const val ID_HOTSEAT = "com.google.android.apps.nexuslauncher:id/hotseat"
        private const val ID_WORKSPACE = "com.google.android.apps.nexuslauncher:id/workspace"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var currentEngine: String? = null
    @Volatile private var lastEngineCheckTime: Long = 0L
    private var pendingResetRunnable: Runnable? = null

    private var themeCustomizationObserver: ContentObserver? = null
    private var colorsChangedListener: WallpaperManager.OnColorsChangedListener? = null
    private var debouncedUpdateRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "LauncherStateService connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 30
        serviceInfo = info

        // Register ContentObserver on theme_customization_overlay_packages
        // Fires reliably on modern Android (12-17) when user sets a new wallpaper or selects a color palette in Wallpaper & style
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

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!WidgetPrefs.isReplaceDockEnabled(this)) return

        val pkg = event.packageName?.toString()

        // 1. Fast-path: When All Apps / Swipe-Down search input gains focus or text is typed, switch to Google instantly!
        val sourceNode = try { event.source } catch (_: Exception) { null }
        val viewId = sourceNode?.viewIdResourceName
        val hasText = !sourceNode?.text.isNullOrEmpty()
        val isFocused = sourceNode?.isFocused == true
        try { sourceNode?.recycle() } catch (_: Exception) {}

        if (viewId == ID_SEARCH_INPUT && (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || isFocused || hasText)) {
            cancelPendingReset()
            applyEngine(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
            return
        }

        // 2. Ignore transient, IME, SystemUI, or search engine overlay events while user is typing/interacting
        if (isTransientOrInputPackage(pkg)) {
            return
        }

        // 3. Evaluate state for window and launcher state transitions
        evaluateLauncherState(pkg)
    }

    @Suppress("DEPRECATION")
    private fun evaluateLauncherState(eventPkg: String?) {
        var launcherRoot: AccessibilityNodeInfo? = null
        var isAnotherAppInForeground = false

        try {
            val windowList = windows
            if (windowList != null && windowList.isNotEmpty()) {
                for (window in windowList) {
                    val root = try { window.root } catch (_: Exception) { null } ?: continue
                    val rootPkg = root.packageName?.toString()
                    if (rootPkg == PIXEL_LAUNCHER_PKG) {
                        launcherRoot = root
                        break
                    } else if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && !isTransientOrInputPackage(rootPkg)) {
                        isAnotherAppInForeground = true
                        try { root.recycle() } catch (_: Exception) {}
                    } else {
                        try { root.recycle() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting windows", e)
        }

        if (launcherRoot == null) {
            val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
            if (activeRoot != null) {
                val activePkg = activeRoot.packageName?.toString()
                if (activePkg == PIXEL_LAUNCHER_PKG) {
                    launcherRoot = activeRoot
                } else if (isTransientOrInputPackage(activePkg)) {
                    try { activeRoot.recycle() } catch (_: Exception) {}
                    return
                } else {
                    isAnotherAppInForeground = true
                    try { activeRoot.recycle() } catch (_: Exception) {}
                }
            }
        }

        if (launcherRoot != null) {
            val isSearchActive = isSearchInputOrResultsActive(launcherRoot)
            val isHotseatShown = isHotseatVisible(launcherRoot)
            val isWorkspaceShown = isWorkspaceVisible(launcherRoot)
            try { launcherRoot.recycle() } catch (_: Exception) {}

            if (isSearchActive) {
                // Pixel Launcher search is active (swipe down or search input opened).
                // Must be Google Search so OneSearch candidate outline and Enter QuickLaunch work!
                cancelPendingReset()
                applyEngine(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
            } else if (isHotseatShown || isWorkspaceShown) {
                // User is on the home screen workspace.
                // Keep ClassicPixelSearch dock widget without any flash!
                cancelPendingReset()
                applyEngine(ServiceHelper.CLASSIC_SEARCH_PACKAGE)
            }
        } else if (isAnotherAppInForeground || (eventPkg != null && eventPkg != PIXEL_LAUNCHER_PKG && !isTransientOrInputPackage(eventPkg))) {
            // User opened another application (e.g. launched app from search results).
            // Reset to Classic immediately so when the user returns Home, the dock is already set!
            cancelPendingReset()
            applyEngine(ServiceHelper.CLASSIC_SEARCH_PACKAGE)
        }
    }

    private fun isTransientOrInputPackage(pkg: String?): Boolean {
        if (pkg == null) return true
        return pkg == "com.google.android.inputmethod.latin" ||
                pkg == "com.android.systemui" ||
                pkg == "android" ||
                pkg == "com.google.android.googlequicksearchbox" ||
                pkg.contains("inputmethod") ||
                pkg.contains("keyboard") ||
                pkg.contains("ime")
    }

    @Suppress("DEPRECATION")
    private fun isSearchInputOrResultsActive(root: AccessibilityNodeInfo): Boolean {
        // 1. Search input in All Apps / Swipe-down search has focus or text
        val inputNodes = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_INPUT)
        val hasActiveInput = inputNodes.any { it.isFocused || !it.text.isNullOrEmpty() }
        inputNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        if (hasActiveInput) return true

        // 2. Search results list view is visible to user
        val searchResults = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_RESULTS)
        val hasResults = searchResults.any { it.isVisibleToUser }
        searchResults.forEach { try { it.recycle() } catch (_: Exception) {} }
        if (hasResults) return true

        return false
    }

    @Suppress("DEPRECATION")
    private fun isHotseatVisible(root: AccessibilityNodeInfo): Boolean {
        val hotseatContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_CONTAINER_HOTSEAT)
        val hasHotseatContainer = hotseatContainers.any { it.isVisibleToUser }
        hotseatContainers.forEach { try { it.recycle() } catch (_: Exception) {} }
        if (hasHotseatContainer) return true

        val hotseats = root.findAccessibilityNodeInfosByViewId(ID_HOTSEAT)
        val hasHotseat = hotseats.any { it.isVisibleToUser }
        hotseats.forEach { try { it.recycle() } catch (_: Exception) {} }
        return hasHotseat
    }

    @Suppress("DEPRECATION")
    private fun isWorkspaceVisible(root: AccessibilityNodeInfo): Boolean {
        val workspaceNodes = root.findAccessibilityNodeInfosByViewId(ID_WORKSPACE)
        val hasWorkspace = workspaceNodes.any { it.isVisibleToUser }
        workspaceNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        return hasWorkspace
    }

    private fun cancelPendingReset() {
        pendingResetRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingResetRunnable = null
        }
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
                if (currentInSettings != targetEngine) {
                    val wrote = Settings.Secure.putString(
                        contentResolver,
                        ServiceHelper.KEY_SELECTED_SEARCH_ENGINE,
                        targetEngine
                    )
                    if (wrote) {
                        currentEngine = targetEngine
                        Log.i(TAG, "Switched selected_search_engine -> $targetEngine")
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

    private fun ensureClassicSearchEngine() {
        val now = SystemClock.elapsedRealtime()
        if (currentEngine == ServiceHelper.CLASSIC_SEARCH_PACKAGE && now - lastEngineCheckTime < 2000L) {
            return
        }
        lastEngineCheckTime = now
        applyEngine(ServiceHelper.CLASSIC_SEARCH_PACKAGE)
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
        cancelPendingReset()
        debouncedUpdateRunnable?.let {
            mainHandler.removeCallbacks(it)
            debouncedUpdateRunnable = null
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
