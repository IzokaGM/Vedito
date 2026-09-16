package com.vedito.app.feature.editor.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.R
import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayClip
import com.vedito.app.core.overlay.OverlayTimelineEditor
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onOverlaySelected: ((String) -> Unit)? = null
    var onOverlayEditStart: ((String) -> Unit)? = null
    var onOverlayChanged: ((OverlayClip, Boolean) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_video) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9E5FF.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16131F.toInt(); textSize = sp(9f) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(1.5f) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = 210 }

    private var clips: List<OverlayClip> = emptyList()
    private var assetsById: Map<String, OverlayAsset> = emptyMap()
    private var labelsById: Map<String, String> = emptyMap()
    private var selectedId: String? = null
    private var durationMs = 0
    private var zoom = 1f
    private var viewportStartMs = 0
    private var positionMs = 0

    private enum class Mode { NONE, MOVE, TRIM_LEFT, TRIM_RIGHT }
    private var mode = Mode.NONE
    private var activeClip: OverlayClip? = null
    private var downX = 0f
    private var original = OverlayClip("", "", 0, 1)

    fun setState(
        clips: List<OverlayClip>,
        assets: List<OverlayAsset>,
        selectedClipId: String?,
        durationMs: Int,
        zoom: Float,
        viewportStartMs: Int,
        positionMs: Int
    ) {
        this.clips = clips
        this.assetsById = assets.associateBy { it.id }
        this.labelsById = assets.associate { it.id to it.displayName }
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
        val laneHeight = height / 3f
        clips.sortedBy { it.zIndex }.forEach { clip ->
            val x1 = timeToX(clip.timelineStartMs)
            val x2 = timeToX(clip.timelineEndMs)
            if (x2 < 0 || x1 > width) return@forEach
            val lane = (clip.zIndex % 3).coerceAtLeast(0)
            val top = lane * laneHeight + dp(3f)
            val bottom = top + laneHeight - dp(6f)
            val rect = RectF(x1, top, x2, bottom)
            canvas.drawRoundRect(rect, dp(5f), dp(5f), if (clip.id == selectedId) selectedPaint else barPaint)
            if (clip.id == selectedId) {
                canvas.drawRect(x1, top, x1 + dp(4f), bottom, edgePaint)
                canvas.drawRect(x2 - dp(4f), top, x2, bottom, edgePaint)
            }
            val label = labelsById[clip.assetId]?.substringBeforeLast('.')?.take(12).orEmpty().ifBlank { "Overlay" }
            canvas.drawText("L${clip.zIndex + 1} $label", x1 + dp(6f), top + laneHeight * 0.55f, textPaint)
        }
        val px = timeToX(positionMs)
        if (px in -2f..(width + 2f)) canvas.drawLine(px, 0f, px, height.toFloat(), playheadPaint)
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
                onOverlaySelected?.invoke(hit.id)
                onOverlayEditStart?.invoke(hit.id)
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
                val asset = assetsById[base.assetId] ?: return false
                val deltaMs = xDeltaToMs(event.x - downX)
                val edited = when (mode) {
                    Mode.MOVE -> OverlayTimelineEditor.move(original, original.timelineStartMs + deltaMs, durationMs)
                    Mode.TRIM_LEFT -> OverlayTimelineEditor.trimLeft(original, asset, original.timelineStartMs + deltaMs, durationMs)
                    Mode.TRIM_RIGHT -> OverlayTimelineEditor.trimRight(original, asset, original.timelineEndMs + deltaMs, durationMs)
                    Mode.NONE -> original
                }
                activeClip = edited
                clips = clips.map { if (it.id == edited.id) edited else it }
                onOverlayChanged?.invoke(edited, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeClip?.let { onOverlayChanged?.invoke(it, true) }
                activeClip = null
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitClip(x: Float, y: Float): OverlayClip? {
        val laneHeight = height / 3f
        return clips.sortedByDescending { it.zIndex }.firstOrNull { clip ->
            val lane = (clip.zIndex % 3).coerceAtLeast(0)
            val top = lane * laneHeight
            val bottom = top + laneHeight
            x >= timeToX(clip.timelineStartMs) - dp(8f) && x <= timeToX(clip.timelineEndMs) + dp(8f) && y in top..bottom
        }
    }

    private fun visibleDuration(): Float = (durationMs / zoom).coerceAtLeast(1f)
    private fun timeToX(ms: Int): Float = ((ms - viewportStartMs) / visibleDuration()) * width
    private fun xDeltaToMs(dx: Float): Int = ((dx / width.coerceAtLeast(1)) * visibleDuration()).roundToInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
