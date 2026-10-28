package com.pixel.classicsearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
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
        private const val ID_APPS_VIEW = "com.google.android.apps.nexuslauncher:id/apps_view"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var currentEngine: String? = null
    private var pendingResetRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "LauncherStateService connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 30
        serviceInfo = info

        // Make sure current state is synced
        evaluateState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()

        // Fast-path: When the All Apps search input gains focus, switch to Google instantly!
        val sourceNode = try { event.source } catch (_: Exception) { null }
        val viewId = sourceNode?.viewIdResourceName
        sourceNode?.recycle()

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && viewId == ID_SEARCH_INPUT) {
            cancelPendingReset()
            applyEngine(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
            return
        }

        // Never reset when the event originates from IME, SystemUI, or search overlays
        if (isTransientOrInputPackage(pkg)) {
            return
        }

        evaluateState()
    }

    private fun evaluateState() {
        var launcherRoot: AccessibilityNodeInfo? = null
        var isAnotherAppInForeground = false

        // 1. Inspect all interactive windows
        try {
            val windowList = windows
            if (windowList != null && windowList.isNotEmpty()) {
                for (window in windowList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = window.root ?: continue
                        if (root.packageName == PIXEL_LAUNCHER_PKG) {
                            launcherRoot = root
                            break
                        } else {
                            // Another app is foreground application
                            isAnotherAppInForeground = true
                            root.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting windows", e)
        }

        // 2. Fallback to rootInActiveWindow if window list didn't yield a launcher root
        if (launcherRoot == null) {
            val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
            if (activeRoot != null) {
                val activePkg = activeRoot.packageName?.toString()
                if (activePkg == PIXEL_LAUNCHER_PKG) {
                    launcherRoot = activeRoot
                } else if (isTransientOrInputPackage(activePkg)) {
                    // Still in transient/input mode; do not switch
                    activeRoot.recycle()
                    return
                } else {
                    isAnotherAppInForeground = true
                    activeRoot.recycle()
                }
            }
        }

        if (launcherRoot != null) {
            val isAllAppsOrSearch = checkAllAppsActive(launcherRoot)
            launcherRoot.recycle()

            if (isAllAppsOrSearch) {
                cancelPendingReset()
                applyEngine(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
            } else {
                scheduleSwitch(ServiceHelper.CLASSIC_SEARCH_PACKAGE, delayMs = 150)
            }
        } else if (isAnotherAppInForeground) {
            // User opened another app (e.g. YouTube, Browser)
            scheduleSwitch(ServiceHelper.CLASSIC_SEARCH_PACKAGE, delayMs = 150)
        }
    }

    private fun isTransientOrInputPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        return pkg == "com.google.android.inputmethod.latin" ||
                pkg == "com.android.systemui" ||
                pkg == "android" ||
                pkg == "com.google.android.googlequicksearchbox" ||
                pkg.contains("inputmethod") ||
                pkg.contains("keyboard") ||
                pkg.contains("ime")
    }

    private fun checkAllAppsActive(root: AccessibilityNodeInfo): Boolean {
        // 1. Search input in All Apps drawer
        val inputNodes = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_INPUT)
        val hasInput = inputNodes.any { it.isFocused || it.isVisibleToUser }
        inputNodes.forEach { it.recycle() }
        if (hasInput) return true

        // 2. Search results list view
        val searchResults = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_RESULTS)
        val hasResults = searchResults.any { it.isVisibleToUser }
        searchResults.forEach { it.recycle() }
        if (hasResults) return true

        // 3. Search container for All Apps
        val searchContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_ALL_APPS)
        val hasSearchContainer = searchContainers.any { it.isVisibleToUser }
        searchContainers.forEach { it.recycle() }
        if (hasSearchContainer) return true

        // 4. Apps view container (present and visible when drawer is open)
        val appsViews = root.findAccessibilityNodeInfosByViewId(ID_APPS_VIEW)
        val hasAppsView = appsViews.any { it.isVisibleToUser }
        appsViews.forEach { it.recycle() }
        if (hasAppsView) return true

        return false
    }

    private fun cancelPendingReset() {
        pendingResetRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingResetRunnable = null
        }
    }

    private fun scheduleSwitch(targetEngine: String, delayMs: Long) {
        cancelPendingReset()
        val runnable = Runnable {
            applyEngine(targetEngine)
            pendingResetRunnable = null
        }
        pendingResetRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun applyEngine(targetEngine: String) {
        if (currentEngine == targetEngine) return
        currentEngine = targetEngine

        executor.execute {
            try {
                val currentInSettings = Settings.Secure.getString(
                    contentResolver,
                    ServiceHelper.KEY_SELECTED_SEARCH_ENGINE
                )
                if (currentInSettings != targetEngine) {
                    Settings.Secure.putString(
                        contentResolver,
                        ServiceHelper.KEY_SELECTED_SEARCH_ENGINE,
                        targetEngine
                    )
                    Log.i(TAG, "Switched selected_search_engine -> $targetEngine")
                }
            } catch (e: Exception) {
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
        cancelPendingReset()
        executor.shutdown()
        super.onDestroy()
        Log.i(TAG, "LauncherStateService destroyed")
    }
}
