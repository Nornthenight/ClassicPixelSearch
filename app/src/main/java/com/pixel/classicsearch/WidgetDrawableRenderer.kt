package com.pixel.classicsearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

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

    fun renderOuterRingBitmap(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        surfaceStyle: Int
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val fillColor = getOuterColor(context, surfaceStyle)
        val isAmoled = surfaceStyle == WidgetPrefs.SURFACE_AMOLED
        val strokeColor = if (isAmoled) getStrokeColor(context) else null
        val strokeWidthPx = if (isAmoled) (1f * density).coerceAtLeast(1f) else 0f
        return renderRoundedRectBitmap(widthPx, heightPx, cornerRadiusPx, fillColor, strokeColor, strokeWidthPx)
    }

    fun renderInnerPillBitmap(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        surfaceStyle: Int
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val fillColor = getInnerColor(context, surfaceStyle)
        val isAmoled = surfaceStyle == WidgetPrefs.SURFACE_AMOLED
        val strokeColor = if (isAmoled) getStrokeColor(context) else null
        val strokeWidthPx = if (isAmoled) (1f * density).coerceAtLeast(1f) else 0f
        return renderRoundedRectBitmap(widthPx, heightPx, cornerRadiusPx, fillColor, strokeColor, strokeWidthPx)
    }

    fun renderAiCircleBitmap(
        context: Context,
        sizePx: Int,
        cornerRadiusPx: Float,
        surfaceStyle: Int
    ): Bitmap {
        return renderInnerPillBitmap(context, sizePx, sizePx, cornerRadiusPx, surfaceStyle)
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
}
