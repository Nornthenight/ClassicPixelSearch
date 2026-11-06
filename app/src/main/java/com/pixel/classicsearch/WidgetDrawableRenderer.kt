package com.pixel.classicsearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

object WidgetDrawableRenderer {

    fun getOuterColor(context: Context, surfaceStyle: Int): Int {
        val resId = when (surfaceStyle) {
            WidgetPrefs.SURFACE_DEEP_RING -> R.color.widget_outer_pill_bg
            WidgetPrefs.SURFACE_PASTEL -> R.color.widget_outer_pill_pastel
            WidgetPrefs.SURFACE_RICH_ACCENT -> R.color.widget_outer_pill_rich
            WidgetPrefs.SURFACE_AMOLED -> R.color.widget_outer_surface_amoled
            else -> R.color.widget_outer_pill_bg
        }
        return ContextCompat.getColor(context, resId)
    }

    fun getInnerColor(context: Context, surfaceStyle: Int): Int {
        val resId = when (surfaceStyle) {
            WidgetPrefs.SURFACE_AMOLED -> R.color.widget_inner_surface_amoled
            else -> R.color.widget_inner_pill_bg
        }
        return ContextCompat.getColor(context, resId)
    }

    fun getStrokeColor(context: Context): Int {
        return ContextCompat.getColor(context, R.color.widget_stroke_amoled)
    }

    fun renderRoundedRectBitmap(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        fillColor: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Float = 0f
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }

        val hasStroke = strokeColor != null && strokeWidthPx > 0f
        val halfStroke = if (hasStroke) strokeWidthPx / 2f else 0f
        val rect = RectF(halfStroke, halfStroke, w.toFloat() - halfStroke, h.toFloat() - halfStroke)
        val maxRadius = ((h.toFloat() - strokeWidthPx) / 2f).coerceAtLeast(0f)
        val radius = cornerRadiusPx.coerceIn(0f, maxRadius)

        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (hasStroke) {
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = strokeColor!!
                strokeWidth = strokeWidthPx
            }
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }

        return bitmap
    }

    data class ThreeSliceBitmaps(
        val left: Bitmap,
        val center: Bitmap,
        val right: Bitmap
    )

    fun renderThreeSlicePill(
        context: Context,
        heightPx: Int,
        cornerRadiusPx: Float,
        fillColor: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Float = 0f
    ): ThreeSliceBitmaps {
        val h = heightPx.coerceAtLeast(4)
        val capWidth = (h / 2).coerceAtLeast(2)
        val centerWidth = 4
        val totalWidth = capWidth * 2 + centerWidth
        val master = renderRoundedRectBitmap(
            widthPx = totalWidth,
            heightPx = h,
            cornerRadiusPx = cornerRadiusPx.coerceIn(0f, capWidth.toFloat()),
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidthPx = strokeWidthPx
        )
        val left = Bitmap.createBitmap(master, 0, 0, capWidth, h)
        val center = Bitmap.createBitmap(master, capWidth, 0, centerWidth, h)
        val right = Bitmap.createBitmap(master, capWidth + centerWidth, 0, capWidth, h)
        return ThreeSliceBitmaps(left, center, right)
    }

    fun renderOuterThreeSlice(
        context: Context,
        heightPx: Int,
        cornerRadiusPx: Float,
        surfaceStyle: Int
    ): ThreeSliceBitmaps {
        val density = context.resources.displayMetrics.density
        val fillColor = getOuterColor(context, surfaceStyle)
        val isAmoled = surfaceStyle == WidgetPrefs.SURFACE_AMOLED
        val strokeColor = if (isAmoled) getStrokeColor(context) else null
        val strokeWidthPx = if (isAmoled) (1f * density).coerceAtLeast(1f) else 0f
        return renderThreeSlicePill(context, heightPx, cornerRadiusPx, fillColor, strokeColor, strokeWidthPx)
    }

    fun renderInnerThreeSlice(
        context: Context,
        heightPx: Int,
        cornerRadiusPx: Float,
        surfaceStyle: Int
    ): ThreeSliceBitmaps {
        val density = context.resources.displayMetrics.density
        val fillColor = getInnerColor(context, surfaceStyle)
        val isAmoled = surfaceStyle == WidgetPrefs.SURFACE_AMOLED
        val strokeColor = if (isAmoled) getStrokeColor(context) else null
        val strokeWidthPx = if (isAmoled) (1f * density).coerceAtLeast(1f) else 0f
        return renderThreeSlicePill(context, heightPx, cornerRadiusPx, fillColor, strokeColor, strokeWidthPx)
    }

    fun renderAiCircleBitmap(
        context: Context,
        sizePx: Int,
        cornerRadiusPx: Float,
        surfaceStyle: Int
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val fillColor = getInnerColor(context, surfaceStyle)
        val isAmoled = surfaceStyle == WidgetPrefs.SURFACE_AMOLED
        val strokeColor = if (isAmoled) getStrokeColor(context) else null
        val strokeWidthPx = if (isAmoled) (1f * density).coerceAtLeast(1f) else 0f
        return renderRoundedRectBitmap(sizePx, sizePx, cornerRadiusPx, fillColor, strokeColor, strokeWidthPx)
    }

    fun createOuterDrawable(
        context: Context,
        cornerRadiusPx: Float,
        surfaceStyle: Int
    ): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = cornerRadiusPx
        d.setColor(getOuterColor(context, surfaceStyle))
        if (surfaceStyle == WidgetPrefs.SURFACE_AMOLED) {
            d.setStroke((1f * density).toInt().coerceAtLeast(1), getStrokeColor(context))
        }
        return d
    }

    fun createInnerDrawable(
        context: Context,
        cornerRadiusPx: Float,
        surfaceStyle: Int
    ): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = cornerRadiusPx
        d.setColor(getInnerColor(context, surfaceStyle))
        if (surfaceStyle == WidgetPrefs.SURFACE_AMOLED) {
            d.setStroke((1f * density).toInt().coerceAtLeast(1), getStrokeColor(context))
        }
        return d
    }

    sealed class ActionIcon {
        data class Resource(val resId: Int) : ActionIcon()
        data class AppBitmap(val bitmap: Bitmap) : ActionIcon()
    }

    fun resolveActionIcon(
        context: Context,
        action: String,
        iconStyle: Int,
        defaultAction: String,
        targetSizePx: Int = (24f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(48)
    ): ActionIcon {
        val resolvedAction = if (action == WidgetPrefs.ACTION_DEFAULT) defaultAction else action

        return when {
            resolvedAction == WidgetPrefs.ACTION_VOICE_SEARCH -> {
                val res = when (iconStyle) {
                    WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_mic_ref
                    WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_mic_mono
                    WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_mic_color
                    else -> R.drawable.ic_mic_ref
                }
                ActionIcon.Resource(res)
            }
            resolvedAction == WidgetPrefs.ACTION_GEMINI -> {
                val res = when (iconStyle) {
                    WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_gemini_ref
                    WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_gemini_mono
                    WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_gemini_color
                    else -> R.drawable.ic_gemini_ref
                }
                ActionIcon.Resource(res)
            }
            resolvedAction == WidgetPrefs.ACTION_GOOGLE_LENS -> {
                val res = if (iconStyle == WidgetPrefs.ICON_DYNAMIC_MONET) {
                    R.drawable.ic_lens_mono
                } else {
                    R.drawable.ic_lens_color
                }
                ActionIcon.Resource(res)
            }
            resolvedAction == WidgetPrefs.ACTION_AI_MODE -> {
                val res = when (iconStyle) {
                    WidgetPrefs.ICON_ADAPTIVE_EMERALD -> R.drawable.ic_ai_mode_ref
                    WidgetPrefs.ICON_DYNAMIC_MONET -> R.drawable.ic_ai_mode_mono
                    WidgetPrefs.ICON_CLASSIC_COLOR -> R.drawable.ic_ai_mode_color
                    else -> R.drawable.ic_ai_mode_ref
                }
                ActionIcon.Resource(res)
            }
            resolvedAction == WidgetPrefs.ACTION_QR_SCANNER -> {
                ActionIcon.Resource(R.drawable.ic_qr_code)
            }
            resolvedAction == WidgetPrefs.ACTION_CHATGPT -> {
                val bmp = getAppIconBitmap(context, "com.openai.chatgpt", targetSizePx)
                if (bmp != null) ActionIcon.AppBitmap(bmp) else ActionIcon.Resource(R.drawable.ic_chatgpt)
            }
            resolvedAction == WidgetPrefs.ACTION_PERPLEXITY -> {
                val bmp = getAppIconBitmap(context, "ai.perplexity.app.android", targetSizePx)
                if (bmp != null) ActionIcon.AppBitmap(bmp) else ActionIcon.Resource(R.drawable.ic_perplexity)
            }
            resolvedAction == WidgetPrefs.ACTION_BROWSER_BRAVE -> {
                val bmp = getAppIconBitmap(context, "com.brave.browser", targetSizePx)
                if (bmp != null) ActionIcon.AppBitmap(bmp) else ActionIcon.Resource(R.drawable.ic_browser)
            }
            resolvedAction == WidgetPrefs.ACTION_BROWSER_FIREFOX -> {
                val bmp = getAppIconBitmap(context, "org.mozilla.firefox", targetSizePx)
                if (bmp != null) ActionIcon.AppBitmap(bmp) else ActionIcon.Resource(R.drawable.ic_browser)
            }
            resolvedAction == WidgetPrefs.ACTION_BROWSER_CROMITE -> {
                val bmp = getAppIconBitmap(context, "org.cromite.cromite", targetSizePx)
                if (bmp != null) ActionIcon.AppBitmap(bmp) else ActionIcon.Resource(R.drawable.ic_browser)
            }
            resolvedAction.startsWith(WidgetPrefs.PREFIX_APP) -> {
                val pkg = resolvedAction.removePrefix(WidgetPrefs.PREFIX_APP)
                val bmp = getAppIconBitmap(context, pkg, targetSizePx)
                if (bmp != null) ActionIcon.AppBitmap(bmp) else ActionIcon.Resource(R.drawable.ic_browser)
            }
            else -> {
                if (resolvedAction != defaultAction) {
                    resolveActionIcon(context, defaultAction, iconStyle, defaultAction, targetSizePx)
                } else {
                    ActionIcon.Resource(R.drawable.ic_mic_ref)
                }
            }
        }
    }

    fun getAppIconBitmap(context: Context, packageName: String, sizePx: Int): Bitmap? {
        return try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val densityDpi = context.resources.displayMetrics.densityDpi
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            bitmap.density = densityDpi
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    fun applyActionIcon(views: android.widget.RemoteViews, viewId: Int, icon: ActionIcon) {
        when (icon) {
            is ActionIcon.Resource -> views.setImageViewResource(viewId, icon.resId)
            is ActionIcon.AppBitmap -> views.setImageViewBitmap(viewId, icon.bitmap)
        }
    }

    fun applyActionIconToView(imageView: android.widget.ImageView, icon: ActionIcon) {
        when (icon) {
            is ActionIcon.Resource -> imageView.setImageResource(icon.resId)
            is ActionIcon.AppBitmap -> imageView.setImageBitmap(icon.bitmap)
        }
    }
}
