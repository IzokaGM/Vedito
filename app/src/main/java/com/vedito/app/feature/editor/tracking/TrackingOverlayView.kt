package com.vedito.app.feature.editor.tracking

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.core.model.MotionTrackSpec
import com.vedito.app.core.tracking.MotionTrackingEngine
import kotlin.math.hypot

/**
 * Manual motion-track anchor surface. Drag the reticle at the current playhead to upsert an anchor.
 * It only owns UI interaction; canonical timing/interpolation lives in MotionTrackingEngine.
 */
class TrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onAnchorCommitted: ((Float, Float) -> Unit)? = null

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88B9A7FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB9A7FF.toInt()
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB9A7FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val path = Path()
    private var track = MotionTrackSpec()
    private var localTimeMs = 0
    private var durationMs = 0
    private var editable = false
    private var dragX = 0.5f
    private var dragY = 0.5f
    private var dragging = false

    init {
        visibility = GONE
        isClickable = true
        isFocusable = false
    }

    fun render(track: MotionTrackSpec, localTimeMs: Int, durationMs: Int, editable: Boolean) {
        this.track = MotionTrackingEngine.normalize(track, durationMs)
        this.localTimeMs = localTimeMs.coerceIn(0, durationMs.coerceAtLeast(0))
        this.durationMs = durationMs.coerceAtLeast(0)
        this.editable = editable
        val evaluated = MotionTrackingEngine.evaluate(this.track, this.localTimeMs, this.durationMs)
        if (!dragging && evaluated != null) {
            dragX = evaluated.x
            dragY = evaluated.y
        }
        visibility = if (editable && this.track.enabled) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || visibility != VISIBLE) return
        val points = track.points
        if (points.isNotEmpty()) {
            path.reset()
            points.forEachIndexed { index, p ->
                val x = p.x * width
                val y = p.y * height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                canvas.drawCircle(x, y, dp(2.5f), pointPaint)
            }
            if (points.size > 1) canvas.drawPath(path, pathPaint)
        }
        val cx = dragX * width
        val cy = dragY * height
        val radius = dp(13f)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawLine(cx - radius - dp(5f), cy, cx + radius + dp(5f), cy, crossPaint)
        canvas.drawLine(cx, cy - radius - dp(5f), cx, cy + radius + dp(5f), crossPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable || visibility != VISIBLE || width <= 0 || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val currentX = dragX * width
                val currentY = dragY * height
                val close = hypot(event.x - currentX, event.y - currentY) <= dp(42f)
                if (!close) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                dragX = (event.x / width).coerceIn(0f, 1f)
                dragY = (event.y / height).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onAnchorCommitted?.invoke(dragX, dragY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
