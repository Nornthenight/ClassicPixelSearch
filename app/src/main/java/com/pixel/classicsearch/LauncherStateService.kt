package com.pixel.classicsearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.WallpaperManager
import android.content.res.Configuration
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
 * Passive zero-flicker launcher state & theme synchronizer.
 *
 * Guarantees:
 * 1. ZERO FLICKER: selected_search_engine is never dynamically toggled to Google,
 *    keeping the desktop dock 100% solid and flicker-free during rapid swipes.
 * 2. LIVE MONET SYNC: reacts to wallpaper color changes, theme customization overlays,
 *    and relevant configuration changes (night mode, density, locales) to refresh widgets.
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

    private var lastNightMode: Int = 0
    private var lastDensityDpi: Int = 0
    private var lastLocalesTag: String = ""

    override fun onCreate() {
        super.onCreate()
        captureConfig(resources.configuration)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "LauncherStateService connected in passive zero-flicker mode")
        captureConfig(resources.configuration)

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.packageNames = arrayOf(PIXEL_LAUNCHER_PKG)
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.DEFAULT
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

    private fun captureConfig(config: Configuration) {
        lastNightMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        lastDensityDpi = config.densityDpi
        lastLocalesTag = config.locales.toLanguageTags()
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
        if (event.packageName?.toString() != PIXEL_LAUNCHER_PKG) return
        if (!WidgetPrefs.isReplaceDockEnabled(this)) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ensureClassicSearchEngine()
        }
    }

    private fun ensureClassicSearchEngine() {
        if (!ShizukuHelper.hasWriteSecureSettings(this)) return

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
        if (executor.isShutdown) return

        try {
            executor.execute {
                try {
                    if (!ShizukuHelper.hasWriteSecureSettings(this)) {
                        currentEngine = null
                        return@execute
                    }
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
                        } else {
                            currentEngine = null
                        }
                    } else {
                        currentEngine = targetEngine
                    }
                } catch (e: Exception) {
                    currentEngine = null
                    Log.e(TAG, "Failed to update selected_search_engine to $targetEngine", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Service teardown in progress, safe to drop trailing trigger
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val newDensityDpi = newConfig.densityDpi
        val newLocalesTag = newConfig.locales.toLanguageTags()

        if (newNightMode != lastNightMode ||
            newDensityDpi != lastDensityDpi ||
            newLocalesTag != lastLocalesTag
        ) {
            lastNightMode = newNightMode
            lastDensityDpi = newDensityDpi
            lastLocalesTag = newLocalesTag
            Log.i(TAG, "Relevant configuration changed -> updating widgets")
            ClassicSearchWidgetProvider.updateAllWidgets(this)
        }
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
