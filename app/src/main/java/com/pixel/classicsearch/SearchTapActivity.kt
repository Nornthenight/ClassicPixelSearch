package com.pixel.classicsearch

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SearchTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val googleIntent = Intent("com.google.android.googlequicksearchbox.GOOGLE_SEARCH").apply {
            setPackage("com.google.android.googlequicksearchbox")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(googleIntent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }
        finish()
    }
}

