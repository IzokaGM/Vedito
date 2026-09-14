package com.vedito.app.feature.editor.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.R
import com.vedito.app.core.model.Clip
import com.vedito.app.core.timeline.TimelineMath
import kotlin.math.abs
import kotlin.math.roundToInt

class TimelineScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var positionMs: Int = 0
        set(value) {
            field = value.coerceIn(0, durationMs)
            invalidate()
        }

    val durationMs: Int
        get() = TimelineMath.totalDurationMs(clips)

    var onSeekFinished: ((Int) -> Unit)? = null
    var onScrubbed: ((Int) -> Unit)? = null
    var onClipSelected: ((String) -> Unit)? = null
    var onTrimChanged: ((clipId: String, sourceStartMs: Int, sourceEndMs: Int, finished: Boolean) -> Unit)? = null

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

    private var clips: List<Clip> = emptyList()
    private var selectedClipId: String? = null
    private var thumbnails: List<Bitmap?> = emptyList()
    private var touchMode = TouchMode.NONE
    private var downX = 0f
    private var trimOriginalStart = 0
    private var trimOriginalEnd = 0
    private var trimDurationAtDown = 0
    private var lastTrimStart = 0
    private var lastTrimEnd = 0

    fun setClips(value: List<Clip>, selectedId: String?) {
        clips = value.toList()
        selectedClipId = selectedId?.takeIf { id -> clips.any { it.id == id } }
        positionMs = positionMs.coerceIn(0, durationMs)
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

        selectedClipBounds(body)?.let { selected ->
            canvas.drawRoundRect(selected, 8f * density, 8f * density, selectedPaint)
            drawTrimHandle(canvas, selected.left, body)
            drawTrimHandle(canvas, selected.right, body)
        }

        if (durationMs > 0) {
            val x = xForTime(positionMs, body)
            canvas.drawRect(x - density, body.top - 8f * density, x + density, body.bottom + 8f * density, playheadPaint)
            canvas.drawCircle(x, body.top - 8f * density, 4.5f * density, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (clips.isEmpty() || durationMs <= 0) return false
        val body = timelineRect()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                val selectedBounds = selectedClipBounds(body)
                val handleHit = 18f * density
                val selectedClip = clips.firstOrNull { it.id == selectedClipId }

                touchMode = when {
                    selectedBounds != null && selectedClip != null && abs(event.x - selectedBounds.left) <= handleHit -> {
                        beginTrim(selectedClip)
                        TouchMode.TRIM_LEFT
                    }
                    selectedBounds != null && selectedClip != null && abs(event.x - selectedBounds.right) <= handleHit -> {
                        beginTrim(selectedClip)
                        TouchMode.TRIM_RIGHT
                    }
                    else -> TouchMode.SCRUB
                }

                if (touchMode == TouchMode.SCRUB) updateScrub(event.x, body)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    TouchMode.SCRUB -> updateScrub(event.x, body)
                    TouchMode.TRIM_LEFT, TouchMode.TRIM_RIGHT -> updateTrim(event.x, finished = false)
                    TouchMode.NONE -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (touchMode) {
                    TouchMode.SCRUB -> {
                        updateScrub(event.x, body)
                        onSeekFinished?.invoke(positionMs)
                    }
                    TouchMode.TRIM_LEFT, TouchMode.TRIM_RIGHT -> updateTrim(event.x, finished = true)
                    TouchMode.NONE -> Unit
                }
                touchMode = TouchMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginTrim(clip: Clip) {
        trimOriginalStart = clip.sourceStartMs
        trimOriginalEnd = clip.sourceEndMs
        trimDurationAtDown = durationMs
        lastTrimStart = trimOriginalStart
        lastTrimEnd = trimOriginalEnd
    }

    private fun updateTrim(x: Float, finished: Boolean) {
        val id = selectedClipId ?: return
        val usable = timelineRect().width().coerceAtLeast(1f)
        val deltaMs = ((x - downX) / usable * trimDurationAtDown).roundToInt()
        val minimum = MIN_CLIP_MS

        if (touchMode == TouchMode.TRIM_LEFT) {
            lastTrimStart = (trimOriginalStart + deltaMs).coerceIn(0, trimOriginalEnd - minimum)
            lastTrimEnd = trimOriginalEnd
        } else {
            lastTrimStart = trimOriginalStart
            lastTrimEnd = (trimOriginalEnd + deltaMs).coerceAtLeast(trimOriginalStart + minimum)
        }
        onTrimChanged?.invoke(id, lastTrimStart, lastTrimEnd, finished)
    }

    private fun updateScrub(x: Float, body: RectF) {
        val next = timeForX(x, body)
        positionMs = next
        TimelineMath.locate(clips, next)?.clip?.id?.let { id ->
            if (id != selectedClipId) {
                selectedClipId = id
                onClipSelected?.invoke(id)
            }
        }
        onScrubbed?.invoke(next)
    }

    private fun drawThumbnails(canvas: Canvas, body: RectF) {
        if (thumbnails.isEmpty()) {
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

        val cellWidth = body.width() / thumbnails.size
        thumbnails.forEachIndexed { index, bitmap ->
            val left = body.left + index * cellWidth
            val right = if (index == thumbnails.lastIndex) body.right else left + cellWidth
            val destination = RectF(left, body.top, right, body.bottom)
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
        var cursor = 0
        clips.dropLast(1).forEach { clip ->
            cursor += clip.durationMs
            val x = xForTime(cursor, body)
            canvas.drawLine(x, body.top, x, body.bottom, dividerPaint)
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

    private fun selectedClipBounds(body: RectF): RectF? {
        val id = selectedClipId ?: return null
        var cursor = 0
        clips.forEach { clip ->
            val start = cursor
            val end = cursor + clip.durationMs
            if (clip.id == id) {
                return RectF(xForTime(start, body), body.top, xForTime(end, body), body.bottom)
            }
            cursor = end
        }
        return null
    }

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
        if (durationMs <= 0) return body.left
        val fraction = timeMs.toFloat().coerceIn(0f, durationMs.toFloat()) / durationMs
        return body.left + body.width() * fraction
    }

    private fun timeForX(x: Float, body: RectF): Int {
        val fraction = ((x - body.left) / body.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (durationMs * fraction).roundToInt()
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

    private enum class TouchMode { NONE, SCRUB, TRIM_LEFT, TRIM_RIGHT }

    companion object {
        private const val MIN_CLIP_MS = 300
    }
}
