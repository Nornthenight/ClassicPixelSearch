package com.pixel.classicsearch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class ClassicSearchWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        ServiceHelper.ensureAccessibilityServiceEnabled(context)
        when (intent.action) {
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
        ServiceHelper.ensureAccessibilityServiceEnabled(context)
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

        private fun createGoogleMainPendingIntent(context: Context): PendingIntent {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    GOOGLE_PACKAGE,
                    "com.google.android.googlequicksearchbox.SearchActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                100,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createTextSearchPendingIntent(context: Context): PendingIntent {
            val textSearchIntent = Intent("com.google.android.googlequicksearchbox.GOOGLE_SEARCH").apply {
                component = ComponentName(
                    GOOGLE_PACKAGE,
                    "com.google.android.googlequicksearchbox.ImplicitGoogleSearchEntrypointInternal"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                104,
                textSearchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createVoiceSearchPendingIntent(context: Context): PendingIntent {
            val voiceIntent = Intent("com.google.android.googlequicksearchbox.action.CLASSIC_GSA_VOICE_SEARCH").apply {
                addCategory("com.google.android.googlequicksearchbox.category.SEARCH_WIDGET")
                component = ComponentName(
                    GOOGLE_PACKAGE,
                    "com.google.android.googlequicksearchbox.ImplicitGsaVoiceSearchEntrypointInternal"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                101,
                voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Opens the full standalone Gemini app (BardEntryPointActivity MAIN/LAUNCHER),
         * NOT the voice assistant overlay.
         */
        private fun createGeminiAppPendingIntent(context: Context): PendingIntent {
            val geminiIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    GEMINI_PACKAGE,
                    "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                102,
                geminiIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createAiModePendingIntent(context: Context): PendingIntent {
            val aiModeIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/gasearch?udm=50")
            ).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = ComponentName(
                    GOOGLE_PACKAGE,
                    "com.google.android.apps.search.googleapp.search.deeplinks.SearchDeepLink"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                103,
                aiModeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
