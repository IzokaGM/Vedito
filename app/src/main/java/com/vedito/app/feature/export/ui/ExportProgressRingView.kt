package com.vedito.app.feature.export.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.vedito.app.R

/** Determinate, accessible progress indicator driven only by ExportTaskStore progress. */
class ExportProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_stroke)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_brand_cyan)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()
    var progressPercent: Int = 0
        set(value) {
            val next = value.coerceIn(0, 100)
            if (field != next) {
                field = next
                contentDescription = "Export progress $next percent"
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 7f * resources.displayMetrics.density
        track.strokeWidth = stroke
        active.strokeWidth = stroke
        val inset = stroke / 2f + 2f * resources.displayMetrics.density
        bounds.set(inset, inset, width - inset, height - inset)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        canvas.drawArc(bounds, -90f, 360f, false, track)
        canvas.drawArc(bounds, -90f, progressPercent / 100f * 360f, false, active)
    }
}
