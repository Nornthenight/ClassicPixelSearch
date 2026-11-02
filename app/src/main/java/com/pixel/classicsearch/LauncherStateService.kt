package com.pixel.classicsearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

class LauncherStateService : AccessibilityService() {

    companion object {
        private const val TAG = "LauncherStateService"
        private const val PIXEL_LAUNCHER_PKG = "com.google.android.apps.nexuslauncher"
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var currentEngine: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "LauncherStateService connected")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 100
        serviceInfo = info

        if (WidgetPrefs.isReplaceDockEnabled(this)) {
            ensureClassicSearchEngine()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!WidgetPrefs.isReplaceDockEnabled(this)) return

        val pkg = event.packageName?.toString()
        if (pkg == PIXEL_LAUNCHER_PKG && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ensureClassicSearchEngine()
        }
    }

    /**
     * Ensures `selected_search_engine` remains set to `com.pixel.classicsearch` when dock replacement
     * is enabled. Never switches `selected_search_engine` to `com.google.android.googlequicksearchbox`
     * during touch gestures, slow taps, or All Apps transitions, because `OseWidgetView` in Pixel Launcher
     * observes `selected_search_engine` and immediately unmounts our widget, flashing Google's stock widget
     * (first its classic-color initialLayout, then its wallpaper-adaptive Glance RemoteViews) before
     * re-mounting our widget.
     */
    private fun ensureClassicSearchEngine() {
        val targetEngine = ServiceHelper.CLASSIC_SEARCH_PACKAGE
        if (currentEngine == targetEngine) return

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
                        Log.i(TAG, "Restored selected_search_engine -> $targetEngine")
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
        executor.shutdown()
        super.onDestroy()
        Log.i(TAG, "LauncherStateService destroyed")
    }
}
