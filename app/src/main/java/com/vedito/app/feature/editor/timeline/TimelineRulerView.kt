package com.vedito.app.feature.editor.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.vedito.app.R
import kotlin.math.ceil
import kotlin.math.max

/** Compact ruler shared by the persistent multi-track timeline. */
class TimelineRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_muted)
        strokeWidth = density
        alpha = 150
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_text_secondary)
        strokeWidth = density
        alpha = 190
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_muted)
        textSize = 8f * scaledDensity
    }

    private var durationMs = 0
    private var zoom = 1f
    private var viewportStartMs = 0

    fun setState(durationMs: Int, zoom: Float, viewportStartMs: Int) {
        this.durationMs = durationMs.coerceAtLeast(0)
        this.zoom = zoom.coerceIn(1f, 8f)
        this.viewportStartMs = viewportStartMs.coerceAtLeast(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0 || width <= 0) return

        val left = HORIZONTAL_INSET_DP * density
        val right = width - left
        val usable = (right - left).coerceAtLeast(1f)
        val visible = (durationMs / zoom).toInt().coerceAtLeast(1)
        val end = (viewportStartMs + visible).coerceAtMost(durationMs)
        val step = chooseStep(visible)
        val minor = (step / 2).coerceAtLeast(1)

        var tick = (viewportStartMs / minor) * minor
        while (tick <= end + minor) {
            if (tick >= viewportStartMs - minor) {
                val x = left + ((tick - viewportStartMs).toFloat() / visible) * usable
                val major = tick % step == 0
                val h = if (major) 7f * density else 3.5f * density
                canvas.drawLine(x, height.toFloat() - h, x, height.toFloat(), if (major) majorTickPaint else tickPaint)
                if (major && x <= right - 18f * density) {
                    canvas.drawText(formatTime(tick), x + 2f * density, 9f * density, textPaint)
                }
            }
            tick += minor
        }
    }

    private fun chooseStep(visibleMs: Int): Int {
        val target = max(500, visibleMs / 5)
        return when {
            target <= 500 -> 500
            target <= 1_000 -> 1_000
            target <= 2_000 -> 2_000
            target <= 5_000 -> 5_000
            target <= 10_000 -> 10_000
            target <= 30_000 -> 30_000
            target <= 60_000 -> 60_000
            else -> ceil(target / 60_000.0).toInt() * 60_000
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    companion object {
        private const val HORIZONTAL_INSET_DP = 8f
    }
}
