package com.pixel.classicsearch

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

object ServiceHelper {
    private const val TAG = "ServiceHelper"
    const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"
    const val CLASSIC_SEARCH_PACKAGE = "com.pixel.classicsearch"
    const val KEY_SELECTED_SEARCH_ENGINE = "selected_search_engine"

    private const val SERVICE_COMPONENT = "com.pixel.classicsearch/com.pixel.classicsearch.LauncherStateService"

    fun ensureAccessibilityServiceEnabled(context: Context) {
        try {
            val cr = context.contentResolver
            val enabledServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val servicesList = enabledServices.split(':').filter { it.isNotBlank() }.toMutableSet()

            var changed = false
            if (!servicesList.contains(SERVICE_COMPONENT)) {
                servicesList.add(SERVICE_COMPONENT)
                Settings.Secure.putString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    servicesList.joinToString(":")
                )
                changed = true
            }

            val a11yEnabled = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (a11yEnabled != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                changed = true
            }

            if (changed) {
                Log.i(TAG, "Auto-enabled LauncherStateService successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-enable LauncherStateService", e)
        }
    }
}

