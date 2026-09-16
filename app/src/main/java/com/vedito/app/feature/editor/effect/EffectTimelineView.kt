package com.vedito.app.feature.editor.effect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vedito.app.R
import com.vedito.app.core.effect.EffectTimelineEditor
import com.vedito.app.core.model.EffectClip
import kotlin.math.abs
import kotlin.math.roundToInt

class EffectTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onEffectSelected: ((String) -> Unit)? = null
    var onEffectEditStart: ((String) -> Unit)? = null
    var onEffectChanged: ((EffectClip, Boolean) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_effect) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_effect_selected) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.vedito_bg_deep); textSize = sp(9f) }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(1.5f) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = 220 }

    private var effects: List<EffectClip> = emptyList()
    private var selectedId: String? = null
    private var durationMs = 0
    private var zoom = 1f
    private var viewportStartMs = 0
    private var positionMs = 0

    private enum class Mode { NONE, MOVE, TRIM_LEFT, TRIM_RIGHT }
    private var mode = Mode.NONE
    private var activeEffect: EffectClip? = null
    private var original = EffectClip("", 0, 1)
    private var downX = 0f

    fun setState(effects: List<EffectClip>, selectedEffectId: String?, durationMs: Int, zoom: Float, viewportStartMs: Int, positionMs: Int) {
        this.effects = effects
        this.selectedId = selectedEffectId
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
        effects.forEachIndexed { index, effect ->
            val x1 = timeToX(effect.timelineStartMs)
            val x2 = timeToX(effect.timelineEndMs)
            if (x2 < 0 || x1 > width) return@forEachIndexed
            val lane = index % 2
            val laneHeight = height / 2f
            val top = lane * laneHeight + dp(3f)
            val bottom = top + laneHeight - dp(6f)
            canvas.drawRoundRect(RectF(x1, top, x2, bottom), dp(5f), dp(5f), if (effect.id == selectedId) selectedPaint else barPaint)
            if (effect.id == selectedId) {
                canvas.drawRect(x1, top, x1 + dp(4f), bottom, edgePaint)
                canvas.drawRect(x2 - dp(4f), top, x2, bottom, edgePaint)
            }
            canvas.drawText(effect.kind.name.lowercase().replace('_', ' '), x1 + dp(6f), top + laneHeight * 0.55f, textPaint)
        }
        val px = timeToX(positionMs)
        if (px in -2f..(width + 2f)) canvas.drawLine(px, 0f, px, height.toFloat(), playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitEffect(event.x, event.y) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                activeEffect = hit
                original = hit
                selectedId = hit.id
                onEffectSelected?.invoke(hit.id)
                onEffectEditStart?.invoke(hit.id)
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
                    Mode.MOVE -> EffectTimelineEditor.move(original, original.timelineStartMs + deltaMs, durationMs)
                    Mode.TRIM_LEFT -> EffectTimelineEditor.trimLeft(original, original.timelineStartMs + deltaMs, durationMs)
                    Mode.TRIM_RIGHT -> EffectTimelineEditor.trimRight(original, original.timelineEndMs + deltaMs, durationMs)
                    Mode.NONE -> original
                }
                activeEffect = edited
                effects = effects.map { if (it.id == edited.id) edited else it }.sortedBy { it.timelineStartMs }
                onEffectChanged?.invoke(edited, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeEffect?.let { onEffectChanged?.invoke(it, true) }
                activeEffect = null
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitEffect(x: Float, y: Float): EffectClip? {
        val laneHeight = height / 2f
        return effects.withIndex().reversed().firstOrNull { (index, effect) ->
            val lane = index % 2
            val top = lane * laneHeight
            val bottom = top + laneHeight
            x >= timeToX(effect.timelineStartMs) - dp(8f) && x <= timeToX(effect.timelineEndMs) + dp(8f) && y in top..bottom
        }?.value
    }

    private fun visibleDuration(): Float = (durationMs / zoom).coerceAtLeast(1f)
    private fun timeToX(ms: Int): Float = ((ms - viewportStartMs) / visibleDuration()) * width
    private fun xDeltaToMs(dx: Float): Int = ((dx / width.coerceAtLeast(1)) * visibleDuration()).roundToInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
