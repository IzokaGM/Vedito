package com.vedito.app.feature.editor.caption

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.core.caption.CaptionTimelineEditor
import com.vedito.app.core.model.CaptionSegment
import kotlin.math.abs
import kotlin.math.roundToInt

class CaptionTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onCaptionSelected: ((String) -> Unit)? = null
    var onCaptionEditStart: ((String) -> Unit)? = null
    var onCaptionChanged: ((CaptionSegment, Boolean) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF72D6C9.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC8FFF7.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10211F.toInt(); textSize = sp(9f) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(1.5f) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = 220 }

    private var segments: List<CaptionSegment> = emptyList()
    private var selectedId: String? = null
    private var durationMs = 0
    private var zoom = 1f
    private var viewportStartMs = 0
    private var positionMs = 0

    private enum class Mode { NONE, MOVE, TRIM_LEFT, TRIM_RIGHT }
    private var mode = Mode.NONE
    private var activeSegment: CaptionSegment? = null
    private var original = CaptionSegment("", "", 0, 1)
    private var downX = 0f

    fun setState(
        segments: List<CaptionSegment>,
        selectedSegmentId: String?,
        durationMs: Int,
        zoom: Float,
        viewportStartMs: Int,
        positionMs: Int
    ) {
        this.segments = segments
        this.selectedId = selectedSegmentId
        this.durationMs = durationMs.coerceAtLeast(0)
        this.zoom = zoom.coerceIn(1f, 8f)
        this.viewportStartMs = viewportStartMs.coerceAtLeast(0)
        this.positionMs = positionMs.coerceIn(0, this.durationMs)
        invalidate()
    }

    fun updatePlayhead(positionMs: Int, zoom: Float, viewportStartMs: Int) {
        this.positionMs = positionMs.coerceIn(0, durationMs)
        this.zoom = zoom.coerceIn(1f, 8f)
        this.viewportStartMs = viewportStartMs.coerceAtLeast(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0) return
        segments.forEachIndexed { index, segment ->
            val x1 = timeToX(segment.timelineStartMs)
            val x2 = timeToX(segment.timelineEndMs)
            if (x2 < 0 || x1 > width) return@forEachIndexed
            val lane = index % 2
            val laneHeight = height / 2f
            val top = lane * laneHeight + dp(3f)
            val bottom = top + laneHeight - dp(6f)
            val rect = RectF(x1, top, x2, bottom)
            canvas.drawRoundRect(rect, dp(5f), dp(5f), if (segment.id == selectedId) selectedPaint else barPaint)
            if (segment.id == selectedId) {
                canvas.drawRect(x1, top, x1 + dp(4f), bottom, edgePaint)
                canvas.drawRect(x2 - dp(4f), top, x2, bottom, edgePaint)
            }
            val label = segment.text.replace('\n', ' ').take(20).ifBlank { "Caption" }
            canvas.drawText(label, x1 + dp(6f), top + laneHeight * 0.55f, textPaint)
        }
        val px = timeToX(positionMs)
        if (px in -2f..(width + 2f)) canvas.drawLine(px, 0f, px, height.toFloat(), playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitSegment(event.x, event.y) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                activeSegment = hit
                original = hit
                selectedId = hit.id
                onCaptionSelected?.invoke(hit.id)
                onCaptionEditStart?.invoke(hit.id)
                downX = event.x
                val left = timeToX(hit.timelineStartMs)
                val right = timeToX(hit.timelineEndMs)
                mode = when {
                    abs(event.x - left) <= dp(14f) -> Mode.TRIM_LEFT
                    abs(event.x - right) <= dp(14f) -> Mode.TRIM_RIGHT
                    else -> Mode.MOVE
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaMs = xDeltaToMs(event.x - downX)
                val edited = when (mode) {
                    Mode.MOVE -> CaptionTimelineEditor.move(original, original.timelineStartMs + deltaMs, durationMs)
                    Mode.TRIM_LEFT -> CaptionTimelineEditor.trimLeft(original, original.timelineStartMs + deltaMs, durationMs)
                    Mode.TRIM_RIGHT -> CaptionTimelineEditor.trimRight(original, original.timelineEndMs + deltaMs, durationMs)
                    Mode.NONE -> original
                }
                activeSegment = edited
                segments = segments.map { if (it.id == edited.id) edited else it }.sortedBy { it.timelineStartMs }
                onCaptionChanged?.invoke(edited, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeSegment?.let { onCaptionChanged?.invoke(it, true) }
                activeSegment = null
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitSegment(x: Float, y: Float): CaptionSegment? {
        val laneHeight = height / 2f
        return segments.withIndex().reversed().firstOrNull { (index, segment) ->
            val lane = index % 2
            val top = lane * laneHeight
            val bottom = top + laneHeight
            x >= timeToX(segment.timelineStartMs) - dp(8f) &&
                x <= timeToX(segment.timelineEndMs) + dp(8f) &&
                y in top..bottom
        }?.value
    }

    private fun visibleDuration(): Float = (durationMs / zoom).coerceAtLeast(1f)
    private fun timeToX(ms: Int): Float = ((ms - viewportStartMs) / visibleDuration()) * width
    private fun xDeltaToMs(dx: Float): Int = ((dx / width.coerceAtLeast(1)) * visibleDuration()).roundToInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
