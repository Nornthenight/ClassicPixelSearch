package com.pixel.classicsearch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.roundToInt

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
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_REFRESH_WIDGET -> {
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

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.pixel.classicsearch.ACTION_REFRESH_WIDGET"

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

            // 1. Measure and resolve widget geometry
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val maxWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
            val widgetWidthDp = (if (minWidth > 0) minWidth else 320).coerceIn(220, 600)

            val density = context.resources.displayMetrics.density
            val outerHeightDp = 62
            val outerHeightPx = (outerHeightDp * density).roundToInt()
            val outerCapWidthPx = (outerHeightPx / 2).coerceAtLeast(2)

            val ringGapDp = WidgetPrefs.getRingGapDp(context)
            val ringGapPx = (ringGapDp * density).roundToInt()

            val cornerRadiusDp = WidgetPrefs.getCornerRadiusDp(context).coerceIn(12, 31)
            val outerRadiusPx = cornerRadiusDp * density

            val innerHeightDp = (outerHeightDp - 2 * ringGapDp).coerceAtLeast(24)
            val innerHeightPx = (innerHeightDp * density).roundToInt()
            val innerCapWidthPx = (innerHeightPx / 2).coerceAtLeast(2)
            val innerCornerRadiusDp = (cornerRadiusDp - ringGapDp).coerceIn(4, innerHeightDp / 2)
            val innerCornerRadiusPx = innerCornerRadiusDp * density

            val outerAlphaPercent = WidgetPrefs.getOuterAlphaPercent(context)
            val opacityPercent = WidgetPrefs.getOpacityPercent(context)
            val surfaceStyle = WidgetPrefs.getSurfaceStyle(context)

            // 2. Render width-independent 3-slice bitmaps for outer ring & inner pill + exact square bitmap for AI button
            val outerSlices = WidgetDrawableRenderer.renderOuterThreeSlice(
                context, outerHeightPx, outerRadiusPx, surfaceStyle
            )
            views.setImageViewBitmap(R.id.bg_outer_left, outerSlices.left)
            views.setImageViewBitmap(R.id.bg_outer_center, outerSlices.center)
            views.setImageViewBitmap(R.id.bg_outer_right, outerSlices.right)
            views.setViewLayoutWidth(R.id.bg_outer_left, outerCapWidthPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutWidth(R.id.bg_outer_right, outerCapWidthPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)

            val innerSlices = WidgetDrawableRenderer.renderInnerThreeSlice(
                context, innerHeightPx, innerCornerRadiusPx, surfaceStyle
            )
            views.setImageViewBitmap(R.id.bg_inner_left, innerSlices.left)
            views.setImageViewBitmap(R.id.bg_inner_center, innerSlices.center)
            views.setImageViewBitmap(R.id.bg_inner_right, innerSlices.right)
            views.setViewLayoutWidth(R.id.bg_inner_left, innerCapWidthPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutWidth(R.id.bg_inner_right, innerCapWidthPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)

            val aiBitmap = WidgetDrawableRenderer.renderAiCircleBitmap(
                context, innerHeightPx, innerCornerRadiusPx, surfaceStyle
            )
            views.setImageViewBitmap(R.id.bg_ai_circle_layer, aiBitmap)

            // 3. Apply dynamic paddings, dimensions, and alphas
            views.setViewPadding(R.id.search_outer_container, ringGapPx, ringGapPx, ringGapPx, ringGapPx)
            views.setViewLayoutWidth(R.id.ring_gap_spacer, ringGapPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutWidth(R.id.ai_button_wrapper, innerHeightPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)

            val totalOuterAlpha = (opacityPercent / 100f) * (outerAlphaPercent / 100f)
            views.setFloat(R.id.bg_outer_layer, "setAlpha", totalOuterAlpha)
            views.setFloat(R.id.search_outer_container, "setAlpha", opacityPercent / 100f)

            // 4. Apply icon styles and dynamic action icons for all 3 remappable buttons
            val iconStyle = WidgetPrefs.getIconStyle(context)
            val micAction = WidgetPrefs.getMicAction(context)
            val middleAction = WidgetPrefs.getMiddleAction(context)
            val aiAction = WidgetPrefs.getAiAction(context)

            val logoRes = when (iconStyle) {
                WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_google_logo_ref
                WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_google_logo_mono
                WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_google_logo_color
                else -> R.drawable.ic_google_logo_ref
            }
            views.setImageViewResource(R.id.img_google_logo, logoRes)

            val micIcon = WidgetDrawableRenderer.resolveActionIcon(
                context, micAction, iconStyle, WidgetPrefs.ACTION_VOICE_SEARCH
            )
            WidgetDrawableRenderer.applyActionIcon(views, R.id.btn_voice_search, micIcon)

            val middleIcon = WidgetDrawableRenderer.resolveActionIcon(
                context, middleAction, iconStyle, WidgetPrefs.ACTION_GEMINI
            )
            WidgetDrawableRenderer.applyActionIcon(views, R.id.btn_lens_search, middleIcon)

            val aiIcon = WidgetDrawableRenderer.resolveActionIcon(
                context, aiAction, iconStyle, WidgetPrefs.ACTION_AI_MODE
            )
            WidgetDrawableRenderer.applyActionIcon(views, R.id.btn_ai_mode, aiIcon)

            // 5. Route all 5 widget click targets through SearchTapActivity with distinct request codes
            views.setOnClickPendingIntent(
                R.id.img_google_logo,
                createTapPendingIntent(context, SearchTapActivity.REQUEST_CODE_LOGO, SearchTapActivity.TARGET_LOGO)
            )

            val searchPendingIntent = createTapPendingIntent(
                context, SearchTapActivity.REQUEST_CODE_SEARCH, SearchTapActivity.TARGET_SEARCH
            )
            views.setOnClickPendingIntent(R.id.search_pill_container, searchPendingIntent)
            views.setOnClickPendingIntent(R.id.search_touch_area, searchPendingIntent)

            views.setOnClickPendingIntent(
                R.id.btn_voice_search,
                createTapPendingIntent(context, SearchTapActivity.REQUEST_CODE_MIC, SearchTapActivity.TARGET_MIC)
            )

            views.setOnClickPendingIntent(
                R.id.btn_lens_search,
                createTapPendingIntent(context, SearchTapActivity.REQUEST_CODE_MIDDLE, SearchTapActivity.TARGET_MIDDLE)
            )

            views.setOnClickPendingIntent(
                R.id.btn_ai_mode,
                createTapPendingIntent(context, SearchTapActivity.REQUEST_CODE_AI, SearchTapActivity.TARGET_AI)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun createTapPendingIntent(
            context: Context,
            requestCode: Int,
            target: String
        ): PendingIntent {
            val intent = Intent(context, SearchTapActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(SearchTapActivity.EXTRA_TAP_TARGET, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
