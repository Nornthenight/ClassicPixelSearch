package com.pixel.classicsearch

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

class SearchTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        suppressTransitions()
        super.onCreate(savedInstanceState)

        val query = intent?.getStringExtra(SearchManager.QUERY)
        val googleIntent = if (!query.isNullOrBlank()) {
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                setPackage(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } else {
            Intent("com.google.android.googlequicksearchbox.GOOGLE_SEARCH").apply {
                setPackage(ServiceHelper.GOOGLE_SEARCH_PACKAGE)
                intent?.extras?.let { putExtras(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        try {
            startActivity(googleIntent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                        if (!query.isNullOrBlank()) {
                            putExtra(SearchManager.QUERY, query)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }
        finish()
        suppressTransitions()
    }

    private fun suppressTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

