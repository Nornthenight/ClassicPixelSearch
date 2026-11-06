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

    private var themeCustomizationObserver: ContentObserver? = null
    private var colorsChangedListener: WallpaperManager.OnColorsChangedListener? = null
    private var debouncedUpdateRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "LauncherStateService connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
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
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleViewFocusOrTextEvent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged()
            }
        }
    }

    private fun handleViewFocusOrTextEvent(event: AccessibilityEvent) {
        val source = try { event.source } catch (_: Exception) { null } ?: return
        try {
            val viewId = source.viewIdResourceName
            if (viewId == ID_SEARCH_INPUT) {
                val isInAllApps = isNodeInsideContainer(source, ID_SEARCH_ALL_APPS)
                val isInHotseat = isNodeInsideContainer(source, ID_SEARCH_CONTAINER_HOTSEAT)

                if (isInAllApps && !isInHotseat) {
                    // Focused in All Apps / drawer OneSearch input -> activate Google OneSearch
                    applyEngine(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
                } else if (isInHotseat) {
                    // Touch/focus on home screen dock -> strictly keep ClassicSearch
                    ensureClassicSearchEngine()
                }
            }
        } finally {
            try { source.recycle() } catch (_: Exception) {}
        }
    }

    private fun handleWindowStateChanged() {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            val pkg = root.packageName?.toString()
            if (pkg != PIXEL_LAUNCHER_PKG) {
                // Leaving launcher -> ensure dock engine is restored
                ensureClassicSearchEngine()
                return
            }

            // Check if user is currently searching in All Apps
            val allAppsContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_ALL_APPS)
            val isSearchingInDrawer = allAppsContainers.any { container ->
                val inputs = container.findAccessibilityNodeInfosByViewId(ID_SEARCH_INPUT)
                val active = inputs.any { it.isFocused || !it.text.isNullOrEmpty() }
                inputs.forEach { try { it.recycle() } catch (_: Exception) {} }
                active
            }
            allAppsContainers.forEach { try { it.recycle() } catch (_: Exception) {} }

            if (isSearchingInDrawer) {
                applyEngine(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
            } else {
                ensureClassicSearchEngine()
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun isNodeInsideContainer(node: AccessibilityNodeInfo, containerId: String): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.viewIdResourceName == containerId) {
                return true
            }
            val parent = try { current.parent } catch (_: Exception) { null }
            if (current != node) {
                try { current.recycle() } catch (_: Exception) {}
            }
            current = parent
            depth++
        }
        return false
    }

    private fun ensureClassicSearchEngine() {
        val now = SystemClock.elapsedRealtime()
        if (currentEngine == ServiceHelper.CLASSIC_SEARCH_PACKAGE && now - lastEngineCheckTime < 2000L) {
            return
        }
        lastEngineCheckTime = now
        applyEngine(ServiceHelper.CLASSIC_SEARCH_PACKAGE)
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
