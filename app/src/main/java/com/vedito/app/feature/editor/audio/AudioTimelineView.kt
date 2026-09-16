package com.vedito.app.feature.editor.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.R
import com.vedito.app.core.model.AudioClip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class AudioTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onAudioClipSelected: ((String) -> Unit)? = null
    var onAudioEditStart: ((String) -> Unit)? = null
    var onAudioClipEditChanged: ((AudioClip, Boolean) -> Unit)? = null
    var showPlayhead: Boolean = true
        set(value) { field = value; invalidate() }

    private enum class GestureMode { MOVE, TRIM_LEFT, TRIM_RIGHT }

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_surface) }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_audio_deep) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_audio) }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF30333E.toInt() }
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_audio_selected)
        strokeWidth = 1f * density
        alpha = 205
    }
    private val selectedWaveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 1.15f * density
        alpha = 235
    }
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 1f * density
        alpha = 145
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_text)
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_text)
        textSize = 9f * resources.displayMetrics.scaledDensity
    }

    private var clips: List<AudioClip> = emptyList()
    private var labelsByAssetId: Map<String, String> = emptyMap()
    private var assetDurationsById: Map<String, Int> = emptyMap()
    private var waveformsByAssetId: Map<String, FloatArray> = emptyMap()
    private var snapPointsMs: List<Int> = emptyList()
    private var selectedClipId: String? = null
    private var durationMs: Int = 0
    private var zoom: Float = 1f
    private var viewportStartMs: Int = 0
    private var positionMs: Int = 0

    private var gestureClipId: String? = null
    private var gestureOriginal: AudioClip? = null
    private var gestureMode = GestureMode.MOVE
    private var downX = 0f
    private var gestureStarted = false

    fun setState(
        clips: List<AudioClip>,
        labelsByAssetId: Map<String, String>,
        assetDurationsById: Map<String, Int>,
        waveformsByAssetId: Map<String, FloatArray>,
        snapPointsMs: List<Int>,
        selectedClipId: String?,
        durationMs: Int,
        zoom: Float,
        viewportStartMs: Int,
        positionMs: Int
    ) {
        this.clips = clips.sortedWith(compareBy<AudioClip> { it.timelineStartMs }.thenBy { it.id })
        this.labelsByAssetId = labelsByAssetId
        this.assetDurationsById = assetDurationsById
        this.waveformsByAssetId = waveformsByAssetId
        this.snapPointsMs = snapPointsMs.distinct().sorted()
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
        val laneHeight = max(22f * density, (height - 8f * density) / MAX_VISIBLE_LANES)

        clips.forEach { clip ->
            if (clip.timelineEndMs < viewportStartMs || clip.timelineStartMs > viewportEnd) return@forEach
            val lane = (lanes[clip.id] ?: 0).coerceAtMost(MAX_VISIBLE_LANES - 1)
            val rect = rectFor(clip, lane, laneHeight)
            val selected = clip.id == selectedClipId
            val paint = when {
                selected -> selectedPaint
                clip.muted -> mutedPaint
                else -> clipPaint
            }
            canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
            drawWaveform(canvas, clip, rect, selected)
            drawFades(canvas, clip, rect)

            if (selected) drawHandles(canvas, rect)
            if (rect.width() > 64f * density) {
                val label = labelsByAssetId[clip.assetId]?.substringBeforeLast('.')?.take(14).orEmpty()
                if (label.isNotBlank()) {
                    canvas.save()
                    canvas.clipRect(rect)
                    canvas.drawText(label, rect.left + 9f * density, rect.top + 12f * density, textPaint)
                    canvas.restore()
                }
            }
        }

        if (showPlayhead) {
            val playheadX = xForTime(positionMs)
            if (playheadX in 0f..width.toFloat()) {
                canvas.drawLine(playheadX, 0f, playheadX, height.toFloat(), playheadPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0 || width <= 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(event.x, event.y) ?: return true
                selectedClipId = hit.first.id
                onAudioClipSelected?.invoke(hit.first.id)
                gestureClipId = hit.first.id
                gestureOriginal = hit.first
                downX = event.x
                gestureStarted = false
                gestureMode = when {
                    abs(event.x - hit.second.left) <= HANDLE_HIT_DP * density -> GestureMode.TRIM_LEFT
                    abs(event.x - hit.second.right) <= HANDLE_HIT_DP * density -> GestureMode.TRIM_RIGHT
                    else -> GestureMode.MOVE
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val original = gestureOriginal ?: return true
                if (!gestureStarted && abs(event.x - downX) >= TOUCH_SLOP_DP * density) {
                    gestureStarted = true
                    onAudioEditStart?.invoke(original.id)
                }
                if (!gestureStarted) return true
                val edited = editedClip(original, event.x - downX)
                replaceInternal(edited)
                onAudioClipEditChanged?.invoke(edited, false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val original = gestureOriginal
                if (original != null && gestureStarted) {
                    val edited = clips.firstOrNull { it.id == original.id } ?: original
                    onAudioClipEditChanged?.invoke(edited, true)
                }
                clearGesture()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun editedClip(original: AudioClip, deltaX: Float): AudioClip {
        val rawDelta = ((deltaX / width.coerceAtLeast(1)) * visibleDurationMs()).roundToInt()
        return when (gestureMode) {
            GestureMode.MOVE -> moveClip(original, rawDelta)
            GestureMode.TRIM_LEFT -> trimLeft(original, rawDelta)
            GestureMode.TRIM_RIGHT -> trimRight(original, rawDelta)
        }
    }

    private fun moveClip(original: AudioClip, rawDelta: Int): AudioClip {
        val maxStart = (durationMs - original.durationMs).coerceAtLeast(0)
        var start = (original.timelineStartMs + rawDelta).coerceIn(0, maxStart)
        start = snapMoveStart(start, original.durationMs, original.id).coerceIn(0, maxStart)
        return original.copy(timelineStartMs = start)
    }

    private fun trimLeft(original: AudioClip, rawDelta: Int): AudioClip {
        val minDelta = maxOf(-original.timelineStartMs, -original.sourceStartMs)
        val maxDelta = original.durationMs - MIN_AUDIO_CLIP_MS
        var delta = rawDelta.coerceIn(minDelta, maxDelta.coerceAtLeast(minDelta))
        val targetStart = snapTime(original.timelineStartMs + delta, original.id)
        delta = (targetStart - original.timelineStartMs).coerceIn(minDelta, maxDelta.coerceAtLeast(minDelta))
        val duration = original.durationMs - delta
        return original.copy(
            timelineStartMs = original.timelineStartMs + delta,
            sourceStartMs = original.sourceStartMs + delta,
            fadeInMs = original.fadeInMs.coerceAtMost(duration),
            fadeOutMs = original.fadeOutMs.coerceAtMost(duration)
        )
    }

    private fun trimRight(original: AudioClip, rawDelta: Int): AudioClip {
        val assetDuration = assetDurationsById[original.assetId] ?: original.sourceEndMs
        val minDelta = MIN_AUDIO_CLIP_MS - original.durationMs
        val maxBySource = assetDuration - original.sourceEndMs
        val maxByProject = durationMs - original.timelineEndMs
        val maxDelta = minOf(maxBySource, maxByProject)
        var delta = rawDelta.coerceIn(minDelta, maxDelta.coerceAtLeast(minDelta))
        val targetEnd = snapTime(original.timelineEndMs + delta, original.id)
        delta = (targetEnd - original.timelineEndMs).coerceIn(minDelta, maxDelta.coerceAtLeast(minDelta))
        val duration = original.durationMs + delta
        return original.copy(
            sourceEndMs = original.sourceEndMs + delta,
            fadeInMs = original.fadeInMs.coerceAtMost(duration),
            fadeOutMs = original.fadeOutMs.coerceAtMost(duration)
        )
    }

    private fun snapMoveStart(start: Int, clipDuration: Int, clipId: String): Int {
        val candidates = snapCandidates(clipId)
        val threshold = snapThresholdMs()
        var bestStart = start
        var bestDistance = threshold + 1
        candidates.forEach { point ->
            val startDistance = abs(start - point)
            if (startDistance < bestDistance) {
                bestDistance = startDistance
                bestStart = point
            }
            val endDistance = abs((start + clipDuration) - point)
            if (endDistance < bestDistance) {
                bestDistance = endDistance
                bestStart = point - clipDuration
            }
        }
        return if (bestDistance <= threshold) bestStart else start
    }

    private fun snapTime(timeMs: Int, clipId: String): Int {
        val threshold = snapThresholdMs()
        val nearest = snapCandidates(clipId).minByOrNull { abs(it - timeMs) } ?: return timeMs
        return if (abs(nearest - timeMs) <= threshold) nearest else timeMs
    }

    private fun snapCandidates(clipId: String): List<Int> = buildList {
        add(0)
        add(durationMs)
        add(positionMs)
        addAll(snapPointsMs)
        clips.filterNot { it.id == clipId }.forEach {
            add(it.timelineStartMs)
            add(it.timelineEndMs)
        }
    }

    private fun snapThresholdMs(): Int = ((SNAP_DP * density / width.coerceAtLeast(1)) * visibleDurationMs())
        .roundToInt()
        .coerceAtLeast(20)

    private fun drawWaveform(canvas: Canvas, clip: AudioClip, rect: RectF, selected: Boolean) {
        val samples = waveformsByAssetId[clip.assetId] ?: return
        if (samples.isEmpty() || rect.width() < 5f) return
        val assetDuration = (assetDurationsById[clip.assetId] ?: 0).coerceAtLeast(1)
        val center = rect.centerY() + 3f * density
        val amplitude = (rect.height() * 0.32f).coerceAtLeast(2f)
        val spacing = 3f * density
        val count = max(1, (rect.width() / spacing).toInt())
        val paint = if (selected) selectedWaveformPaint else waveformPaint
        canvas.save()
        canvas.clipRect(rect.left + 3f * density, rect.top + 2f * density, rect.right - 3f * density, rect.bottom - 2f * density)
        for (i in 0 until count) {
            val fraction = if (count <= 1) 0f else i.toFloat() / (count - 1)
            val sourceTime = clip.sourceStartMs + (clip.durationMs * fraction).toInt()
            val sampleIndex = ((sourceTime.toFloat() / assetDuration) * (samples.size - 1)).roundToInt()
                .coerceIn(0, samples.lastIndex)
            val level = samples[sampleIndex].coerceIn(0.04f, 1f)
            val x = rect.left + i * spacing
            canvas.drawLine(x, center - amplitude * level, x, center + amplitude * level, paint)
        }
        canvas.restore()
    }

    private fun drawFades(canvas: Canvas, clip: AudioClip, rect: RectF) {
        if (clip.durationMs <= 0) return
        if (clip.fadeInMs > 0) {
            val fadeX = rect.left + rect.width() * (clip.fadeInMs.toFloat() / clip.durationMs)
            canvas.drawLine(rect.left, rect.bottom - 4f * density, fadeX, rect.top + 4f * density, fadePaint)
        }
        if (clip.fadeOutMs > 0) {
            val fadeX = rect.right - rect.width() * (clip.fadeOutMs.toFloat() / clip.durationMs)
            canvas.drawLine(fadeX, rect.top + 4f * density, rect.right, rect.bottom - 4f * density, fadePaint)
        }
    }

    private fun drawHandles(canvas: Canvas, rect: RectF) {
        val w = 3f * density
        canvas.drawRoundRect(RectF(rect.left + 2f * density, rect.top + 6f * density, rect.left + 2f * density + w, rect.bottom - 6f * density), w, w, handlePaint)
        canvas.drawRoundRect(RectF(rect.right - 2f * density - w, rect.top + 6f * density, rect.right - 2f * density, rect.bottom - 6f * density), w, w, handlePaint)
    }

    private fun hitTest(x: Float, y: Float): Pair<AudioClip, RectF>? {
        val lanes = assignLanes(clips)
        val laneHeight = max(22f * density, (height - 8f * density) / MAX_VISIBLE_LANES)
        return clips.asReversed().firstNotNullOfOrNull { clip ->
            val lane = (lanes[clip.id] ?: 0).coerceAtMost(MAX_VISIBLE_LANES - 1)
            val rect = rectFor(clip, lane, laneHeight)
            if (rect.contains(x, y)) clip to rect else null
        }
    }

    private fun rectFor(clip: AudioClip, lane: Int, laneHeight: Float): RectF {
        val left = xForTime(clip.timelineStartMs)
        val right = xForTime(clip.timelineEndMs)
        val top = 4f * density + lane * laneHeight
        val bottom = (top + laneHeight - 4f * density).coerceAtMost(height - 4f * density)
        return RectF(left, top, right.coerceAtLeast(left + 3f * density), bottom)
    }

    private fun replaceInternal(edited: AudioClip) {
        clips = clips.map { if (it.id == edited.id) edited else it }
            .sortedWith(compareBy<AudioClip> { it.timelineStartMs }.thenBy { it.id })
    }

    private fun clearGesture() {
        gestureClipId = null
        gestureOriginal = null
        gestureStarted = false
    }

    private fun visibleDurationMs(): Int = if (durationMs <= 0) 0 else max(1, (durationMs / zoom).toInt())

    private fun xForTime(timeMs: Int): Float {
        val visible = visibleDurationMs().coerceAtLeast(1)
        return ((timeMs - viewportStartMs).toFloat() / visible * width)
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
        private const val MIN_AUDIO_CLIP_MS = 150
        private const val HANDLE_HIT_DP = 13f
        private const val TOUCH_SLOP_DP = 4f
        private const val SNAP_DP = 10f
    }
}
