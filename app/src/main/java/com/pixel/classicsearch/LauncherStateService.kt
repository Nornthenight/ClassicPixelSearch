package com.pixel.classicsearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        private const val ID_SEARCH_CONTAINER_HOTSEAT = "com.google.android.apps.nexuslauncher:id/search_container_hotseat"
        private const val ID_HOTSEAT = "com.google.android.apps.nexuslauncher:id/hotseat"
        private const val ID_WORKSPACE = "com.google.android.apps.nexuslauncher:id/workspace"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var currentEngine: String? = null
    private var pendingResetRunnable: Runnable? = null
    private var wallpaperReceiver: BroadcastReceiver? = null
    private var colorsChangedListener: WallpaperManager.OnColorsChangedListener? = null

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

        // Register runtime receiver for wallpaper changes so widgets dynamically follow wallpaper
        try {
            val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.i(TAG, "ACTION_WALLPAPER_CHANGED received -> updating widgets")
                    ClassicSearchWidgetProvider.updateAllWidgets(this@LauncherStateService)
                }
            }
            registerReceiver(receiver, filter)
            wallpaperReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register wallpaper receiver", e)
        }

        // Register OnColorsChangedListener for instant Material You Monet color adaptation
        try {
            val wm = getSystemService(WallpaperManager::class.java)
            val listener = WallpaperManager.OnColorsChangedListener { _, _ ->
                Log.i(TAG, "Wallpaper colors changed -> updating widgets")
                ClassicSearchWidgetProvider.updateAllWidgets(this@LauncherStateService)
            }
            wm?.addOnColorsChangedListener(listener, mainHandler)
            colorsChangedListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "Cannot register OnColorsChangedListener", e)
        }

        // Make sure current state is synced
        evaluateState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()

        // Fast-path: When All Apps search input gains focus or text is typed, switch to Google instantly!
        val sourceNode = try { event.source } catch (_: Exception) { null }
        val viewId = sourceNode?.viewIdResourceName
        val hasText = !sourceNode?.text.isNullOrEmpty()
        sourceNode?.recycle()

        if (viewId == ID_SEARCH_INPUT && (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || hasText)) {
            cancelPendingReset()
            applyEngine(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
            return
        }

        // Never reset when the event originates from IME, SystemUI, clipboard overlay, or search overlays
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
                    val root = window.root ?: continue
                    val rootPkg = root.packageName?.toString()
                    if (rootPkg == PIXEL_LAUNCHER_PKG) {
                        launcherRoot = root
                        break
                    } else if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && !isTransientOrInputPackage(rootPkg)) {
                        // A genuine third-party/system app is in foreground
                        isAnotherAppInForeground = true
                        root.recycle()
                    } else {
                        root.recycle()
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
                    // Still in transient/input mode (floating toolbar, popup, IME); do not switch
                    activeRoot.recycle()
                    return
                } else {
                    isAnotherAppInForeground = true
                    activeRoot.recycle()
                }
            }
        }

        if (launcherRoot != null) {
            val isAllAppsActive = isAllAppsActiveOrVisible(launcherRoot)
            val isHotseatShown = isHotseatVisible(launcherRoot)
            val isWorkspaceShown = isWorkspaceVisible(launcherRoot)
            launcherRoot.recycle()

            if (isAllAppsActive) {
                // Rule 1 & 2: User is inside All Apps or opening it.
                // NEVER switch to Classic while in All Apps (prevents clearing text / closing copy menu)!
                // If hotseat is still momentarily visible at swipe start, delay by 90ms so the drawer covers it.
                // If hotseat is already hidden, apply Google engine immediately (0ms).
                cancelPendingReset()
                if (currentEngine != ServiceHelper.GOOGLE_SEARCH_PACKAGE) {
                    if (isHotseatShown) {
                        scheduleSwitch(ServiceHelper.GOOGLE_SEARCH_PACKAGE, delayMs = 90)
                    } else {
                        applyEngine(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
                    }
                }
            } else if (isHotseatShown || isWorkspaceShown) {
                // Rule 3: All Apps is closed and user is back on the home screen workspace.
                // Switch back to Classic INSTANTLY (0ms) so dock already has ClassicPixelSearch without any flash!
                cancelPendingReset()
                applyEngine(ServiceHelper.CLASSIC_SEARCH_PACKAGE)
            }
        } else if (isAnotherAppInForeground) {
            // User opened another app -> switch to Classic instantly (0ms)
            cancelPendingReset()
            applyEngine(ServiceHelper.CLASSIC_SEARCH_PACKAGE)
        }
    }

    private fun isTransientOrInputPackage(pkg: String?): Boolean {
        if (pkg == null) return true // Window events with null package are system/transient
        return pkg == "com.google.android.inputmethod.latin" ||
                pkg == "com.android.systemui" ||
                pkg == "android" ||
                pkg == "com.google.android.googlequicksearchbox" ||
                pkg.contains("inputmethod") ||
                pkg.contains("keyboard") ||
                pkg.contains("ime")
    }

    private fun isAllAppsActiveOrVisible(root: AccessibilityNodeInfo): Boolean {
        // 1. Search input in All Apps drawer
        val inputNodes = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_INPUT)
        val hasInput = inputNodes.any { it.isVisibleToUser || it.isFocused }
        inputNodes.forEach { it.recycle() }
        if (hasInput) return true

        // 2. Search container for All Apps
        val searchContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_ALL_APPS)
        val hasSearchContainer = searchContainers.any { it.isVisibleToUser }
        searchContainers.forEach { it.recycle() }
        if (hasSearchContainer) return true

        // 3. Search results list view
        val searchResults = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_RESULTS)
        val hasResults = searchResults.any { it.isVisibleToUser }
        searchResults.forEach { it.recycle() }
        if (hasResults) return true

        // 4. Apps view container (present and visible when drawer is open)
        val appsViews = root.findAccessibilityNodeInfosByViewId(ID_APPS_VIEW)
        val hasAppsView = appsViews.any { it.isVisibleToUser }
        appsViews.forEach { it.recycle() }
        if (hasAppsView) return true

        return false
    }

    private fun isHotseatVisible(root: AccessibilityNodeInfo): Boolean {
        val hotseatContainers = root.findAccessibilityNodeInfosByViewId(ID_SEARCH_CONTAINER_HOTSEAT)
        val hasHotseatContainer = hotseatContainers.any { it.isVisibleToUser }
        hotseatContainers.forEach { it.recycle() }
        if (hasHotseatContainer) return true

        val hotseats = root.findAccessibilityNodeInfosByViewId(ID_HOTSEAT)
        val hasHotseat = hotseats.any { it.isVisibleToUser }
        hotseats.forEach { it.recycle() }
        return hasHotseat
    }

    private fun isWorkspaceVisible(root: AccessibilityNodeInfo): Boolean {
        val workspaceNodes = root.findAccessibilityNodeInfosByViewId(ID_WORKSPACE)
        val hasWorkspace = workspaceNodes.any { it.isVisibleToUser }
        workspaceNodes.forEach { it.recycle() }
        return hasWorkspace
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
        wallpaperReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            wallpaperReceiver = null
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
