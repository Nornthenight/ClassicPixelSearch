package com.pixel.classicsearch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.RemoteViews

class ClassicSearchWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action != AppWidgetManager.ACTION_APPWIDGET_UPDATE && WidgetPrefs.isReplaceDockEnabled(context)) {
            ServiceHelper.ensureAccessibilityServiceEnabled(context)
        }
        when (action) {
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_WALLPAPER_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                updateAllWidgets(context)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (WidgetPrefs.isReplaceDockEnabled(context)) {
            ServiceHelper.ensureAccessibilityServiceEnabled(context)
        }
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val GEMINI_PACKAGE = "com.google.android.apps.bard"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ClassicSearchWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_classic_search)

            // 1. Apply outer frame surface contrast style
            when (WidgetPrefs.getSurfaceStyle(context)) {
                WidgetPrefs.SURFACE_DEEP_RING ->
                    views.setInt(R.id.search_outer_container, "setBackgroundResource", R.drawable.bg_outer_pill)
                WidgetPrefs.SURFACE_PASTEL ->
                    views.setInt(R.id.search_outer_container, "setBackgroundResource", R.drawable.bg_outer_pill_pastel)
                WidgetPrefs.SURFACE_RICH_ACCENT ->
                    views.setInt(R.id.search_outer_container, "setBackgroundResource", R.drawable.bg_outer_pill_rich)
            }

            // 2. Apply fragmented/gradient icon style
            when (WidgetPrefs.getIconStyle(context)) {
                WidgetPrefs.ICON_ADAPTIVE_EMERALD -> {
                    views.setImageViewResource(R.id.img_google_logo, R.drawable.ic_google_logo_ref)
                    views.setImageViewResource(R.id.btn_voice_search, R.drawable.ic_mic_ref)
                    views.setImageViewResource(R.id.btn_lens_search, R.drawable.ic_gemini_ref)
                    views.setImageViewResource(R.id.btn_ai_mode, R.drawable.ic_ai_mode_ref)
                }
                WidgetPrefs.ICON_DYNAMIC_MONET -> {
                    views.setImageViewResource(R.id.img_google_logo, R.drawable.ic_google_logo_mono)
                    views.setImageViewResource(R.id.btn_voice_search, R.drawable.ic_mic_mono)
                    views.setImageViewResource(R.id.btn_lens_search, R.drawable.ic_gemini_mono)
                    views.setImageViewResource(R.id.btn_ai_mode, R.drawable.ic_ai_mode_mono)
                }
                WidgetPrefs.ICON_CLASSIC_COLOR -> {
                    views.setImageViewResource(R.id.img_google_logo, R.drawable.ic_google_logo_color)
                    views.setImageViewResource(R.id.btn_voice_search, R.drawable.ic_mic_color)
                    views.setImageViewResource(R.id.btn_lens_search, R.drawable.ic_gemini_color)
                    views.setImageViewResource(R.id.btn_ai_mode, R.drawable.ic_ai_mode_color)
                }
            }

            // 3. Apply opacity
            val opacityPercent = WidgetPrefs.getOpacityPercent(context)
            views.setFloat(R.id.search_outer_container, "setAlpha", opacityPercent / 100f)

            // 4. Click on "G" logo -> Main Google App Home Feed (requestCode = 100)
            views.setOnClickPendingIntent(R.id.img_google_logo, createGoogleMainPendingIntent(context))

            // 5. Click on search text field -> Google Text Search Input (requestCode = 104)
            val textSearchPendingIntent = createTextSearchPendingIntent(context)
            views.setOnClickPendingIntent(R.id.search_pill_container, textSearchPendingIntent)
            views.setOnClickPendingIntent(R.id.search_touch_area, textSearchPendingIntent)

            // 6. Click on Microphone -> Classic Google Search Voice Mode (requestCode = 101)
            views.setOnClickPendingIntent(R.id.btn_voice_search, createVoiceSearchPendingIntent(context))

            // 7. Click on Gemini Sparkle -> Full standalone Gemini App, NOT Assistant overlay (requestCode = 102)
            views.setOnClickPendingIntent(R.id.btn_lens_search, createGeminiAppPendingIntent(context))

            // 8. Click on AI Mode button -> Google Search AI Mode (udm=50) (requestCode = 103)
            views.setOnClickPendingIntent(R.id.btn_ai_mode, createAiModePendingIntent(context))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun resolveIntentWithFallback(
            context: Context,
            primary: Intent,
            fallback: Intent
        ): Intent {
            val pm = context.packageManager
            if (pm.resolveActivity(primary, 0) != null) {
                return primary
            }
            return fallback
        }

        private fun createGoogleMainPendingIntent(context: Context): PendingIntent {
            val primary = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    GOOGLE_PACKAGE,
                    "com.google.android.googlequicksearchbox.SearchActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val fallback = (context.packageManager.getLaunchIntentForPackage(GOOGLE_PACKAGE)
                ?: Intent(Intent.ACTION_WEB_SEARCH)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                100,
                resolveIntentWithFallback(context, primary, fallback),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createTextSearchPendingIntent(context: Context): PendingIntent {
            val primary = Intent("com.google.android.googlequicksearchbox.GOOGLE_SEARCH").apply {
                component = ComponentName(
                    GOOGLE_PACKAGE,
                    "com.google.android.googlequicksearchbox.ImplicitGoogleSearchEntrypointInternal"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val fallback = Intent("com.google.android.googlequicksearchbox.GOOGLE_SEARCH").apply {
                setPackage(GOOGLE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.let { pkgIntent ->
                if (context.packageManager.resolveActivity(pkgIntent, 0) != null) {
                    pkgIntent
                } else {
                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }
            return PendingIntent.getActivity(
                context,
                104,
                resolveIntentWithFallback(context, primary, fallback),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createVoiceSearchPendingIntent(context: Context): PendingIntent {
            val primary = Intent("com.google.android.googlequicksearchbox.action.CLASSIC_GSA_VOICE_SEARCH").apply {
                addCategory("com.google.android.googlequicksearchbox.category.SEARCH_WIDGET")
                component = ComponentName(
                    GOOGLE_PACKAGE,
                    "com.google.android.googlequicksearchbox.ImplicitGsaVoiceSearchEntrypointInternal"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val fallback = Intent(RecognizerIntent.ACTION_WEB_SEARCH).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                101,
                resolveIntentWithFallback(context, primary, fallback),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Opens the full standalone Gemini app (BardEntryPointActivity MAIN/LAUNCHER),
         * NOT the voice assistant overlay. Falls back to Gemini web if app is not installed.
         */
        private fun createGeminiAppPendingIntent(context: Context): PendingIntent {
            val primary = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    GEMINI_PACKAGE,
                    "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val fallback = (context.packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com"))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                102,
                resolveIntentWithFallback(context, primary, fallback),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createAiModePendingIntent(context: Context): PendingIntent {
            val aiUri = Uri.parse("https://www.google.com/gasearch?udm=50")
            val primary = Intent(Intent.ACTION_VIEW, aiUri).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = ComponentName(
                    GOOGLE_PACKAGE,
                    "com.google.android.apps.search.googleapp.search.deeplinks.SearchDeepLink"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?udm=50")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                context,
                103,
                resolveIntentWithFallback(context, primary, fallback),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
