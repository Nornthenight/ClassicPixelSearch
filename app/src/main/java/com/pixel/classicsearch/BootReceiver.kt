package com.pixel.classicsearch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("BootReceiver", "Received action: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ServiceHelper.ensureAccessibilityServiceEnabled(context)
            try {
                // At boot or app update, make sure the dock search engine is set to our widget
                Settings.Secure.putString(
                    context.contentResolver,
                    ServiceHelper.KEY_SELECTED_SEARCH_ENGINE,
                    ServiceHelper.CLASSIC_SEARCH_PACKAGE
                )
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to set default search engine", e)
            }
        }
    }
}

