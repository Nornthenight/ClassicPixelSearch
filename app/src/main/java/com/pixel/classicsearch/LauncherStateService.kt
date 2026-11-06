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
import java.util.concurrent.Executors

/**
 * Lightweight, non-intrusive launcher state & theme synchronizer.
 *
 * Runs passively:
 * - Listens only to TYPE_WINDOW_STATE_CHANGED to ensure the dock search engine remains active.
 * - Observes wallpaper and Monet theme customizations to instantly adapt widget colors.
 * - Zero view sniffing, zero text interception, and zero battery overhead (canRetrieveWindowContent = false).
 */
class LauncherStateService : AccessibilityService() {

    companion object {
        private const val TAG = "LauncherStateService"
        private const val PIXEL_LAUNCHER_PKG = "com.google.android.apps.nexuslauncher"
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
        Log.i(TAG, "LauncherStateService connected in passive minimal mode")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = 0
        info.notificationTimeout = 100
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

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ensureClassicSearchEngine()
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
