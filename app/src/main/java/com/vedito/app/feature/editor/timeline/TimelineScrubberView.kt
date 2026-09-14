package com.vedito.app.feature.editor.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.vedito.app.R
import com.vedito.app.core.model.Clip
import com.vedito.app.core.timeline.FrameTimecode
import com.vedito.app.core.timeline.TimelineIndex
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TimelineScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var positionMs: Int = 0
        set(value) {
            field = value.coerceIn(0, durationMs)
            if (touchMode == TouchMode.NONE && !scaleDetector.isInProgress) ensurePositionVisible(field)
            invalidate()
        }

    val durationMs: Int get() = timelineIndex.totalDurationMs
    val currentZoom: Float get() = zoomScale
    val currentViewportStartMs: Int get() = viewportStartMs

    var onSeekFinished: ((Int) -> Unit)? = null
    var onScrubbed: ((Int) -> Unit)? = null
    var onClipSelected: ((String) -> Unit)? = null
    var onTrimChanged: ((clipId: String, sourceStartMs: Int, sourceEndMs: Int, finished: Boolean) -> Unit)? = null
    var onReorderRequested: ((clipId: String, targetIndex: Int, finished: Boolean) -> Unit)? = null
    var onViewportChanged: ((zoom: Float, startMs: Int, finished: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_surface_raised) }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_stroke) }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_bg)
        strokeWidth = 2f * density
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_accent)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_accent) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_text) }
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.vedito_accent)
        alpha = 125
        strokeWidth = density
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var clips: List<Clip> = emptyList()
    private var timelineIndex = TimelineIndex(emptyList())
    private var frameRatesByClipId: Map<String, Float> = emptyMap()
    private var selectedClipId: String? = null
    private var thumbnails: List<Bitmap?> = emptyList()
    private var touchMode = TouchMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var trimOriginalStart = 0
    private var trimOriginalEnd = 0
    private var trimDurationAtDown = 0
    private var lastTrimStart = 0
    private var lastTrimEnd = 0
    private var dragClipId: String? = null
    private var lastReorderTarget = -1
    private var zoomScale = 1f
    private var viewportStartMs = 0
    private var lastSnapMs: Int? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mainHandler.removeCallbacks(startReorderRunnable)
            touchMode = TouchMode.SCALE
            parent?.requestDisallowInterceptTouchEvent(true)
            return durationMs > 0
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (durationMs <= 0) return false
            val body = timelineRect()
            val oldVisible = visibleDurationMs()
            val focusFraction = ((detector.focusX - body.left) / body.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
            val focusTime = viewportStartMs + (oldVisible * focusFraction).roundToInt()

            zoomScale = (zoomScale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val newVisible = visibleDurationMs()
            viewportStartMs = clampViewportStart(focusTime - (newVisible * focusFraction).roundToInt())
            onViewportChanged?.invoke(zoomScale, viewportStartMs, false)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            viewportStartMs = clampViewportStart(viewportStartMs)
            onViewportChanged?.invoke(zoomScale, viewportStartMs, true)
            touchMode = TouchMode.NONE
            lastSnapMs = null
            parent?.requestDisallowInterceptTouchEvent(false)
            invalidate()
        }
    })

    private val startReorderRunnable = Runnable {
        if (touchMode != TouchMode.PENDING || clips.size < 2) return@Runnable
        val id = dragClipId ?: return@Runnable
        if (clips.none { it.id == id }) return@Runnable
        touchMode = TouchMode.REORDER
        lastReorderTarget = clips.indexOfFirst { it.id == id }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun setClips(value: List<Clip>, selectedId: String?, frameRates: Map<String, Float> = emptyMap()) {
        clips = value.toList()
        timelineIndex = TimelineIndex(clips)
        frameRatesByClipId = frameRates
        selectedClipId = selectedId?.takeIf { id -> clips.any { it.id == id } }
        zoomScale = zoomScale.coerceIn(MIN_ZOOM, maxAllowedZoom())
        viewportStartMs = clampViewportStart(viewportStartMs)
        positionMs = positionMs.coerceIn(0, durationMs)
        invalidate()
    }

    fun restoreViewport(zoom: Float, startMs: Int) {
        zoomScale = zoom.coerceIn(MIN_ZOOM, maxAllowedZoom())
        viewportStartMs = clampViewportStart(startMs)
        ensurePositionVisible(positionMs)
        invalidate()
    }

    fun setThumbnails(value: List<Bitmap?>) {
        thumbnails.filterNotNull().forEach { old ->
            if (value.none { it === old } && !old.isRecycled) old.recycle()
        }
        thumbnails = value
        invalidate()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(startReorderRunnable)
        thumbnails.filterNotNull().forEach { if (!it.isRecycled) it.recycle() }
        thumbnails = emptyList()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val body = timelineRect()
        val radius = 12f * density
        canvas.drawRoundRect(body, radius, radius, backgroundPaint)

        canvas.save()
        canvas.clipRect(body)
        drawThumbnails(canvas, body)
        drawClipDividers(canvas, body)
        canvas.restore()

        drawSelection(canvas, body)

        if (durationMs > 0) {
            lastSnapMs?.takeIf { it in viewportStartMs..viewportEndMs() }?.let { snapped ->
                val sx = xForTime(snapped, body)
                canvas.drawLine(sx, body.top, sx, body.bottom, snapPaint)
            }
            val x = xForTime(positionMs, body)
            if (x >= body.left - density && x <= body.right + density) {
                canvas.drawRect(x - density, body.top - 8f * density, x + density, body.bottom + 8f * density, playheadPaint)
                canvas.drawCircle(x, body.top - 8f * density, 4.5f * density, handlePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (clips.isEmpty() || durationMs <= 0) return false
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || event.pointerCount > 1 || touchMode == TouchMode.SCALE) {
            mainHandler.removeCallbacks(startReorderRunnable)
            return true
        }

        val body = timelineRect()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mainHandler.removeCallbacks(startReorderRunnable)
                downX = event.x
                downY = event.y
                lastSnapMs = null

                val touchedId = clipIdForX(event.x, body)
                if (touchedId != null && touchedId != selectedClipId) {
                    selectedClipId = touchedId
                    onClipSelected?.invoke(touchedId)
                }

                val selectedBounds = selectedClipBoundsRaw(body)
                val handleHit = 18f * density
                val selectedClip = clips.firstOrNull { it.id == selectedClipId }
                dragClipId = selectedClip?.id

                touchMode = when {
                    selectedBounds != null && selectedClip != null && selectedBounds.left in (body.left - handleHit)..(body.right + handleHit) && abs(event.x - selectedBounds.left) <= handleHit -> {
                        beginTrim(selectedClip)
                        TouchMode.TRIM_LEFT
                    }
                    selectedBounds != null && selectedClip != null && selectedBounds.right in (body.left - handleHit)..(body.right + handleHit) && abs(event.x - selectedBounds.right) <= handleHit -> {
                        beginTrim(selectedClip)
                        TouchMode.TRIM_RIGHT
                    }
                    else -> {
                        mainHandler.postDelayed(startReorderRunnable, LONG_PRESS_MS)
                        TouchMode.PENDING
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    TouchMode.PENDING -> {
                        val moved = abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                        if (moved) {
                            mainHandler.removeCallbacks(startReorderRunnable)
                            touchMode = TouchMode.SCRUB
                            updateScrub(event.x, body)
                        }
                    }
                    TouchMode.SCRUB -> updateScrub(event.x, body)
                    TouchMode.TRIM_LEFT, TouchMode.TRIM_RIGHT -> updateTrim(event.x, finished = false)
                    TouchMode.REORDER -> updateReorder(event.x, body, finished = false)
                    TouchMode.SCALE, TouchMode.NONE -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(startReorderRunnable)
                when (touchMode) {
                    TouchMode.PENDING -> {
                        updateScrub(event.x, body)
                        onSeekFinished?.invoke(positionMs)
                    }
                    TouchMode.SCRUB -> {
                        updateScrub(event.x, body)
                        onSeekFinished?.invoke(positionMs)
                    }
                    TouchMode.TRIM_LEFT, TouchMode.TRIM_RIGHT -> updateTrim(event.x, finished = true)
                    TouchMode.REORDER -> updateReorder(event.x, body, finished = true)
                    TouchMode.SCALE, TouchMode.NONE -> Unit
                }
                touchMode = TouchMode.NONE
                dragClipId = null
                lastReorderTarget = -1
                lastSnapMs = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginTrim(clip: Clip) {
        trimOriginalStart = clip.sourceStartMs
        trimOriginalEnd = clip.sourceEndMs
        trimDurationAtDown = visibleDurationMs()
        lastTrimStart = trimOriginalStart
        lastTrimEnd = trimOriginalEnd
    }

    private fun updateTrim(x: Float, finished: Boolean) {
        val id = selectedClipId ?: return
        val usable = timelineRect().width().coerceAtLeast(1f)
        val deltaMs = ((x - downX) / usable * trimDurationAtDown).roundToInt()
        val minimum = MIN_CLIP_MS
        val fps = frameRatesByClipId[id] ?: 30f

        if (touchMode == TouchMode.TRIM_LEFT) {
            val raw = (trimOriginalStart + deltaMs).coerceIn(0, trimOriginalEnd - minimum)
            lastTrimStart = FrameTimecode.quantizeOffsetMs(raw, fps).coerceIn(0, trimOriginalEnd - minimum)
            lastTrimEnd = trimOriginalEnd
        } else {
            lastTrimStart = trimOriginalStart
            val raw = (trimOriginalEnd + deltaMs).coerceAtLeast(trimOriginalStart + minimum)
            lastTrimEnd = FrameTimecode.quantizeOffsetMs(raw, fps).coerceAtLeast(trimOriginalStart + minimum)
        }
        onTrimChanged?.invoke(id, lastTrimStart, lastTrimEnd, finished)
    }

    private fun updateScrub(x: Float, body: RectF) {
        autoPanForTouch(x, body)
        val activeBody = timelineRect()
        val raw = timeForX(x, activeBody)
        val next = snapTimelineTime(raw, activeBody)
        positionMs = next
        timelineIndex.locate(next)?.clip?.id?.let { id ->
            if (id != selectedClipId) {
                selectedClipId = id
                onClipSelected?.invoke(id)
            }
        }
        onScrubbed?.invoke(next)
    }

    private fun updateReorder(x: Float, body: RectF, finished: Boolean) {
        val id = dragClipId ?: return
        autoPanForTouch(x, body)
        val target = clipIndexForX(x, timelineRect())
        if (target != lastReorderTarget || finished) {
            onReorderRequested?.invoke(id, target, finished)
            lastReorderTarget = target
        }
    }

    private fun snapTimelineTime(rawMs: Int, body: RectF): Int {
        val threshold = ((visibleDurationMs().toFloat() / body.width().coerceAtLeast(1f)) * SNAP_DISTANCE_DP * density)
            .roundToInt()
            .coerceAtLeast(1)
        var nearest: Int? = null
        var nearestDistance = Int.MAX_VALUE
        timelineIndex.edgeTimes().forEach { edge ->
            val distance = abs(edge - rawMs)
            if (distance < nearestDistance) {
                nearest = edge
                nearestDistance = distance
            }
        }
        if (nearest != null && nearestDistance <= threshold) {
            if (lastSnapMs != nearest) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastSnapMs = nearest
            return nearest!!.coerceIn(0, durationMs)
        }

        lastSnapMs = null
        val location = timelineIndex.locate(rawMs) ?: return rawMs.coerceIn(0, durationMs)
        val fps = frameRatesByClipId[location.clip.id] ?: 30f
        val quantizedOffset = FrameTimecode.quantizeOffsetMs(location.offsetMs, fps)
            .coerceIn(0, location.clip.durationMs)
        return (location.timelineStartMs + quantizedOffset).coerceIn(0, durationMs)
    }

    private fun autoPanForTouch(x: Float, body: RectF) {
        if (zoomScale <= 1.01f) return
        val zone = 22f * density
        val amount = (visibleDurationMs() * AUTO_PAN_FRACTION).roundToInt().coerceAtLeast(1)
        val next = when {
            x < body.left + zone -> viewportStartMs - amount
            x > body.right - zone -> viewportStartMs + amount
            else -> return
        }
        val clamped = clampViewportStart(next)
        if (clamped != viewportStartMs) {
            viewportStartMs = clamped
            onViewportChanged?.invoke(zoomScale, viewportStartMs, false)
            invalidate()
        }
    }

    private fun drawThumbnails(canvas: Canvas, body: RectF) {
        if (thumbnails.isEmpty() || durationMs <= 0) {
            val block = 28f * density
            var x = body.left
            var index = 0
            while (x < body.right) {
                placeholderPaint.alpha = if (index % 2 == 0) 255 else 185
                canvas.drawRect(x, body.top, (x + block).coerceAtMost(body.right), body.bottom, placeholderPaint)
                x += block
                index++
            }
            placeholderPaint.alpha = 255
            return
        }

        val count = thumbnails.size
        thumbnails.forEachIndexed { index, bitmap ->
            val startMs = (durationMs.toLong() * index / count).toInt()
            val endMs = (durationMs.toLong() * (index + 1) / count).toInt()
            val left = xForTime(startMs, body)
            val right = xForTime(endMs, body)
            if (right < body.left || left > body.right) return@forEachIndexed
            val destination = RectF(max(left, body.left), body.top, min(right, body.right), body.bottom)
            if (destination.width() <= 0f) return@forEachIndexed
            if (bitmap == null || bitmap.isRecycled) {
                canvas.drawRect(destination, placeholderPaint)
            } else {
                val source = centerCropSource(bitmap, destination.width() / destination.height())
                canvas.drawBitmap(bitmap, source, destination, bitmapPaint)
            }
        }
    }

    private fun drawClipDividers(canvas: Canvas, body: RectF) {
        if (clips.size <= 1 || durationMs <= 0) return
        timelineIndex.edgeTimes().drop(1).dropLast(1).forEach { edge ->
            val x = xForTime(edge, body)
            if (x in body.left..body.right) canvas.drawLine(x, body.top, x, body.bottom, dividerPaint)
        }
    }

    private fun drawSelection(canvas: Canvas, body: RectF) {
        val raw = selectedClipBoundsRaw(body) ?: return
        val visible = RectF(max(raw.left, body.left), body.top, min(raw.right, body.right), body.bottom)
        if (visible.right <= visible.left) return
        canvas.drawRoundRect(visible, 8f * density, 8f * density, selectedPaint)
        if (touchMode != TouchMode.REORDER) {
            if (raw.left in body.left..body.right) drawTrimHandle(canvas, raw.left, body)
            if (raw.right in body.left..body.right) drawTrimHandle(canvas, raw.right, body)
        }
    }

    private fun drawTrimHandle(canvas: Canvas, x: Float, body: RectF) {
        val half = 4f * density
        canvas.drawRoundRect(
            RectF(x - half, body.top - 2f * density, x + half, body.bottom + 2f * density),
            4f * density,
            4f * density,
            handlePaint
        )
    }

    private fun selectedClipBoundsRaw(body: RectF): RectF? {
        val id = selectedClipId ?: return null
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return null
        val start = timelineIndex.startOf(id)
        val end = start + clips[index].durationMs
        return RectF(xForTime(start, body), body.top, xForTime(end, body), body.bottom)
    }

    private fun clipIdForX(x: Float, body: RectF): String? =
        timelineIndex.locate(timeForX(x, body))?.clip?.id

    private fun clipIndexForX(x: Float, body: RectF): Int =
        timelineIndex.locate(timeForX(x, body))?.clipIndex ?: clips.lastIndex.coerceAtLeast(0)

    private fun timelineRect(): RectF {
        val horizontal = 12f * density
        val vertical = 16f * density
        return RectF(
            horizontal,
            vertical,
            (width - horizontal).coerceAtLeast(horizontal + 1f),
            (height - vertical).coerceAtLeast(vertical + 1f)
        )
    }

    private fun xForTime(timeMs: Int, body: RectF): Float {
        val visible = visibleDurationMs().coerceAtLeast(1)
        val fraction = (timeMs - viewportStartMs).toFloat() / visible
        return body.left + body.width() * fraction
    }

    private fun timeForX(x: Float, body: RectF): Int {
        val fraction = ((x - body.left) / body.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (viewportStartMs + visibleDurationMs() * fraction).roundToInt().coerceIn(0, durationMs)
    }

    private fun visibleDurationMs(): Int {
        if (durationMs <= 0) return 1
        return (durationMs / zoomScale).roundToInt().coerceIn(MIN_VISIBLE_MS.coerceAtMost(durationMs), durationMs)
    }

    private fun viewportEndMs(): Int = (viewportStartMs + visibleDurationMs()).coerceAtMost(durationMs)

    private fun maxAllowedZoom(): Float {
        if (durationMs <= 0) return MAX_ZOOM
        return min(MAX_ZOOM, max(1f, durationMs.toFloat() / MIN_VISIBLE_MS))
    }

    private fun clampViewportStart(value: Int): Int {
        val maxStart = (durationMs - visibleDurationMs()).coerceAtLeast(0)
        return value.coerceIn(0, maxStart)
    }

    private fun ensurePositionVisible(position: Int) {
        if (durationMs <= 0 || zoomScale <= 1.01f) {
            viewportStartMs = 0
            return
        }
        val visible = visibleDurationMs()
        val margin = (visible * FOLLOW_MARGIN_FRACTION).roundToInt()
        val leftGuard = viewportStartMs + margin
        val rightGuard = viewportStartMs + visible - margin
        val next = when {
            position < leftGuard -> position - margin
            position > rightGuard -> position - visible + margin
            else -> return
        }
        val clamped = clampViewportStart(next)
        if (clamped != viewportStartMs) {
            viewportStartMs = clamped
            onViewportChanged?.invoke(zoomScale, viewportStartMs, false)
        }
    }

    private fun centerCropSource(bitmap: Bitmap, targetRatio: Float): Rect {
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        return if (sourceRatio > targetRatio) {
            val wantedWidth = (bitmap.height * targetRatio).roundToInt().coerceAtLeast(1)
            val left = ((bitmap.width - wantedWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + wantedWidth).coerceAtMost(bitmap.width), bitmap.height)
        } else {
            val wantedHeight = (bitmap.width / targetRatio.coerceAtLeast(0.01f)).roundToInt().coerceAtLeast(1)
            val top = ((bitmap.height - wantedHeight) / 2).coerceAtLeast(0)
            Rect(0, top, bitmap.width, (top + wantedHeight).coerceAtMost(bitmap.height))
        }
    }

    private enum class TouchMode { NONE, PENDING, SCRUB, TRIM_LEFT, TRIM_RIGHT, REORDER, SCALE }

    companion object {
        private const val MIN_CLIP_MS = 300
        private const val LONG_PRESS_MS = 360L
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 8f
        private const val MIN_VISIBLE_MS = 1_500
        private const val SNAP_DISTANCE_DP = 10f
        private const val AUTO_PAN_FRACTION = 0.06f
        private const val FOLLOW_MARGIN_FRACTION = 0.18f
    }
}
