package com.vedito.app.feature.editor.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.R
import com.vedito.app.core.keyframe.KeyframeEngine
import com.vedito.app.core.model.TextClip
import com.vedito.app.core.text.TextTimelineEditor
import kotlin.math.abs
import kotlin.math.roundToInt

class TextTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onTextSelected: ((String) -> Unit)? = null
    var onTextEditStart: ((String) -> Unit)? = null
    var onTextChanged: ((TextClip, Boolean) -> Unit)? = null
    var showPlayhead: Boolean = true
        set(value) { field = value; invalidate() }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_text_track) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_text_selected) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF17131F.toInt(); textSize = sp(9f) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(1.5f) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = 220 }
    private val keyframePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_brand_indigo) }

    private var clips: List<TextClip> = emptyList()
    private var selectedId: String? = null
    private var durationMs = 0
    private var zoom = 1f
    private var viewportStartMs = 0
    private var positionMs = 0

    private enum class Mode { NONE, MOVE, TRIM_LEFT, TRIM_RIGHT }
    private var mode = Mode.NONE
    private var activeClip: TextClip? = null
    private var original = TextClip("", "", 0, 1)
    private var downX = 0f

    fun setState(
        clips: List<TextClip>,
        selectedClipId: String?,
        durationMs: Int,
        zoom: Float,
        viewportStartMs: Int,
        positionMs: Int
    ) {
        this.clips = clips
        this.selectedId = selectedClipId
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
        val laneHeight = height / 2f
        clips.sortedBy { it.zIndex }.forEach { clip ->
            val x1 = timeToX(clip.timelineStartMs)
            val x2 = timeToX(clip.timelineEndMs)
            if (x2 < 0 || x1 > width) return@forEach
            val lane = (clip.zIndex % 2).coerceAtLeast(0)
            val top = lane * laneHeight + dp(3f)
            val bottom = top + laneHeight - dp(6f)
            val rect = RectF(x1, top, x2, bottom)
            canvas.drawRoundRect(rect, dp(5f), dp(5f), if (clip.id == selectedId) selectedPaint else barPaint)
            if (clip.id == selectedId) {
                canvas.drawRect(x1, top, x1 + dp(4f), bottom, edgePaint)
                canvas.drawRect(x2 - dp(4f), top, x2, bottom, edgePaint)
                KeyframeEngine.positions(clip.keyframes).forEach { localMs ->
                    val keyX = timeToX(clip.timelineStartMs + localMs)
                    if (keyX in (x1 - dp(2f))..(x2 + dp(2f))) {
                        canvas.drawCircle(keyX, bottom - dp(6f), dp(3f), keyframePaint)
                    }
                }
            }
            val label = clip.text.replace('\n', ' ').take(18).ifBlank { "Text" }
            canvas.drawText("T${clip.zIndex + 1} $label", x1 + dp(6f), top + laneHeight * 0.55f, textPaint)
        }
        if (showPlayhead) {
            val px = timeToX(positionMs)
            if (px in -2f..(width + 2f)) canvas.drawLine(px, 0f, px, height.toFloat(), playheadPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitClip(event.x, event.y) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                activeClip = hit
                original = hit
                selectedId = hit.id
                onTextSelected?.invoke(hit.id)
                onTextEditStart?.invoke(hit.id)
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
                val base = activeClip ?: return false
                val deltaMs = xDeltaToMs(event.x - downX)
                val edited = when (mode) {
                    Mode.MOVE -> TextTimelineEditor.move(original, original.timelineStartMs + deltaMs, durationMs)
                    Mode.TRIM_LEFT -> TextTimelineEditor.trimLeft(original, original.timelineStartMs + deltaMs, durationMs)
                    Mode.TRIM_RIGHT -> TextTimelineEditor.trimRight(original, original.timelineEndMs + deltaMs, durationMs)
                    Mode.NONE -> original
                }
                activeClip = edited
                clips = clips.map { if (it.id == edited.id) edited else it }
                onTextChanged?.invoke(edited, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeClip?.let { onTextChanged?.invoke(it, true) }
                activeClip = null
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitClip(x: Float, y: Float): TextClip? {
        val laneHeight = height / 2f
        return clips.sortedByDescending { it.zIndex }.firstOrNull { clip ->
            val lane = (clip.zIndex % 2).coerceAtLeast(0)
            val top = lane * laneHeight
            val bottom = top + laneHeight
            x >= timeToX(clip.timelineStartMs) - dp(8f) &&
                x <= timeToX(clip.timelineEndMs) + dp(8f) &&
                y in top..bottom
        }
    }

    private fun visibleDuration(): Float = (durationMs / zoom).coerceAtLeast(1f)
    private fun timeToX(ms: Int): Float = ((ms - viewportStartMs) / visibleDuration()) * width
    private fun xDeltaToMs(dx: Float): Int = ((dx / width.coerceAtLeast(1)) * visibleDuration()).roundToInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
