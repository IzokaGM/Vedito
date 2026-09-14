package com.vedito.app.feature.editor.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.core.model.AudioClip
import kotlin.math.max

class AudioTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onAudioClipSelected: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF11131A.toInt() }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4B407E.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8D7BFF.toInt() }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF30333E.toInt() }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7F7FA.toInt(); strokeWidth = 1.5f * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF7F7FA.toInt()
        textSize = 9f * resources.displayMetrics.scaledDensity
    }

    private var clips: List<AudioClip> = emptyList()
    private var labelsByAssetId: Map<String, String> = emptyMap()
    private var selectedClipId: String? = null
    private var durationMs: Int = 0
    private var zoom: Float = 1f
    private var viewportStartMs: Int = 0
    private var positionMs: Int = 0

    fun setState(
        clips: List<AudioClip>,
        labelsByAssetId: Map<String, String>,
        selectedClipId: String?,
        durationMs: Int,
        zoom: Float,
        viewportStartMs: Int,
        positionMs: Int
    ) {
        this.clips = clips.sortedWith(compareBy<AudioClip> { it.timelineStartMs }.thenBy { it.id })
        this.labelsByAssetId = labelsByAssetId
        this.selectedClipId = selectedClipId
        this.durationMs = durationMs.coerceAtLeast(0)
        this.zoom = zoom.coerceAtLeast(1f)
        this.viewportStartMs = viewportStartMs.coerceAtLeast(0)
        this.positionMs = positionMs.coerceAtLeast(0)
        invalidate()
    }

    fun updatePlayhead(positionMs: Int, zoom: Float, viewportStartMs: Int) {
        this.positionMs = positionMs.coerceAtLeast(0)
        this.zoom = zoom.coerceAtLeast(1f)
        this.viewportStartMs = viewportStartMs.coerceAtLeast(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val body = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(body, 10f * density, 10f * density, backgroundPaint)
        if (durationMs <= 0 || width <= 0) return

        val visibleDuration = visibleDurationMs()
        val viewportEnd = viewportStartMs + visibleDuration
        val lanes = assignLanes(clips)
        val laneHeight = max(18f * density, (height - 8f * density) / MAX_VISIBLE_LANES)

        clips.forEach { clip ->
            if (clip.timelineEndMs < viewportStartMs || clip.timelineStartMs > viewportEnd) return@forEach
            val lane = (lanes[clip.id] ?: 0).coerceAtMost(MAX_VISIBLE_LANES - 1)
            val left = xForTime(clip.timelineStartMs)
            val right = xForTime(clip.timelineEndMs)
            val top = 4f * density + lane * laneHeight
            val bottom = (top + laneHeight - 4f * density).coerceAtMost(height - 4f * density)
            val rect = RectF(left, top, right.coerceAtLeast(left + 3f * density), bottom)
            val paint = when {
                clip.id == selectedClipId -> selectedPaint
                clip.muted -> mutedPaint
                else -> clipPaint
            }
            canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)

            if (rect.width() > 44f * density) {
                val label = labelsByAssetId[clip.assetId]?.substringBeforeLast('.')?.take(14).orEmpty()
                if (label.isNotBlank()) {
                    canvas.save()
                    canvas.clipRect(rect)
                    canvas.drawText(label, rect.left + 7f * density, rect.centerY() + 3f * density, textPaint)
                    canvas.restore()
                }
            }
        }

        val playheadX = xForTime(positionMs)
        if (playheadX in 0f..width.toFloat()) {
            canvas.drawLine(playheadX, 0f, playheadX, height.toFloat(), playheadPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP || durationMs <= 0) return true
        val time = timeForX(event.x)
        val laneHeight = max(18f * density, (height - 8f * density) / MAX_VISIBLE_LANES)
        val touchedLane = ((event.y - 4f * density) / laneHeight).toInt().coerceAtLeast(0)
        val lanes = assignLanes(clips)
        val hit = clips.lastOrNull { clip ->
            val lane = (lanes[clip.id] ?: 0).coerceAtMost(MAX_VISIBLE_LANES - 1)
            lane == touchedLane && time in clip.timelineStartMs..clip.timelineEndMs
        }
        hit?.let { onAudioClipSelected?.invoke(it.id) }
        return true
    }

    private fun visibleDurationMs(): Int = if (durationMs <= 0) 0 else max(1, (durationMs / zoom).toInt())

    private fun xForTime(timeMs: Int): Float {
        val visible = visibleDurationMs().coerceAtLeast(1)
        return ((timeMs - viewportStartMs).toFloat() / visible * width)
    }

    private fun timeForX(x: Float): Int {
        val visible = visibleDurationMs().coerceAtLeast(1)
        return (viewportStartMs + (x.coerceIn(0f, width.toFloat()) / width.coerceAtLeast(1) * visible)).toInt()
    }

    private fun assignLanes(input: List<AudioClip>): Map<String, Int> {
        val laneEnds = mutableListOf<Int>()
        val result = mutableMapOf<String, Int>()
        input.sortedBy { it.timelineStartMs }.forEach { clip ->
            var lane = laneEnds.indexOfFirst { it <= clip.timelineStartMs }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds += clip.timelineEndMs
            } else {
                laneEnds[lane] = clip.timelineEndMs
            }
            result[clip.id] = lane
        }
        return result
    }

    companion object {
        private const val MAX_VISIBLE_LANES = 3
    }
}
