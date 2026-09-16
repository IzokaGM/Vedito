package com.vedito.app.feature.editor.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.vedito.app.R

/** One visual playhead spanning every visible timeline lane. Touches pass through to lanes below. */
class SharedTimelinePlayheadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_text)
        strokeWidth = 1.5f * density
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_video)
    }

    private var positionMs = 0
    private var durationMs = 0
    private var zoom = 1f
    private var viewportStartMs = 0

    init {
        isClickable = false
        isFocusable = false
    }

    fun setState(positionMs: Int, durationMs: Int, zoom: Float, viewportStartMs: Int) {
        this.durationMs = durationMs.coerceAtLeast(0)
        this.positionMs = positionMs.coerceIn(0, this.durationMs)
        this.zoom = zoom.coerceIn(1f, 8f)
        this.viewportStartMs = viewportStartMs.coerceAtLeast(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0 || width <= 0) return
        val visible = (durationMs / zoom).coerceAtLeast(1f)
        val fraction = (positionMs - viewportStartMs) / visible
        if (fraction < -0.01f || fraction > 1.01f) return

        val inset = HORIZONTAL_INSET_DP * density
        val usable = (width - inset * 2f).coerceAtLeast(1f)
        val x = inset + usable * fraction
        canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        canvas.drawCircle(x, 2.5f * density, 4.2f * density, capPaint)
    }

    companion object {
        private const val HORIZONTAL_INSET_DP = 8f
    }
}
