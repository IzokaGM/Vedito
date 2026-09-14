package com.vedito.app.feature.editor.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.R
import kotlin.math.roundToInt

class TimelineScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var durationMs: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            invalidate()
        }

    var positionMs: Int = 0
        set(value) {
            field = value.coerceIn(0, durationMs.coerceAtLeast(0))
            invalidate()
        }

    var onSeekFinished: ((Int) -> Unit)? = null
    var onScrubbed: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_surface_raised) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_stroke) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_accent) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_text) }
    private var scrubbing = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 8f * density
        val right = width - 8f * density
        val top = 12f * density
        val bottom = height - 12f * density
        val radius = 12f * density

        val clip = RectF(left, top, right, bottom)
        canvas.drawRoundRect(clip, radius, radius, clipPaint)

        val usableWidth = (right - left).coerceAtLeast(1f)
        val blockWidth = 18f * density
        var x = left + 8f * density
        var index = 0
        while (x < right - 6f * density) {
            val barTop = top + if (index % 3 == 0) 10f * density else 17f * density
            val barBottom = bottom - if (index % 2 == 0) 10f * density else 15f * density
            canvas.drawRoundRect(
                RectF(x, barTop, (x + blockWidth).coerceAtMost(right - 6f * density), barBottom),
                5f * density,
                5f * density,
                framePaint
            )
            x += blockWidth + 5f * density
            index++
        }

        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        val playheadX = left + usableWidth * fraction.coerceIn(0f, 1f)
        canvas.drawRect(playheadX - density, top - 5f * density, playheadX + density, bottom + 5f * density, playheadPaint)
        canvas.drawCircle(playheadX, top - 5f * density, 4f * density, accentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrubbing) updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) {
                    updateFromTouch(event.x)
                    scrubbing = false
                    onSeekFinished?.invoke(positionMs)
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float) {
        if (durationMs <= 0) return
        val left = 8f * density
        val right = width - 8f * density
        val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        positionMs = (durationMs * fraction).roundToInt()
        onScrubbed?.invoke(positionMs)
    }
}
