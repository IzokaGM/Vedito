package com.vedito.app.feature.editor.visual

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.vedito.app.core.model.MaskShape
import com.vedito.app.core.model.MaskSpec
import com.vedito.app.core.visual.MaskChromaComposition
import kotlin.math.roundToInt

/**
 * Lightweight native preview of the main-video mask.
 * It occludes the hidden area with the current canvas background, leaving overlays/text above it.
 * The export compositor should reproduce this from the renderer-independent MaskSpec.
 */
class MaskPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val featherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private var spec = MaskSpec()
    private var canvasColor: Int = 0xFF000000.toInt()

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    fun render(mask: MaskSpec, backgroundColor: Int) {
        val safe = MaskChromaComposition.normalize(mask)
        val nextVisibility = if (safe.shape == MaskShape.NONE) GONE else VISIBLE
        if (safe == spec && backgroundColor == canvasColor && visibility == nextVisibility) return
        spec = safe
        canvasColor = backgroundColor
        visibility = nextVisibility
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (spec.shape == MaskShape.NONE || width <= 0 || height <= 0) return

        val shape = shapeBounds()
        fillPaint.color = canvasColor
        fillPaint.alpha = 255
        path.reset()

        if (spec.inverted) {
            addShape(path, shape)
            canvas.drawPath(path, fillPaint)
            drawFeather(canvas, shape, outward = true)
        } else {
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addShape(path, shape)
            canvas.drawPath(path, fillPaint)
            drawFeather(canvas, shape, outward = false)
        }
    }

    private fun shapeBounds(): RectF {
        val safe = MaskChromaComposition.normalize(spec)
        val w = safe.width * width
        val h = safe.height * height
        val cx = safe.centerX * width
        val cy = safe.centerY * height
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    private fun addShape(target: Path, bounds: RectF) {
        when (spec.shape) {
            MaskShape.NONE -> Unit
            MaskShape.RECTANGLE -> target.addRoundRect(bounds, dp(8f), dp(8f), Path.Direction.CW)
            MaskShape.ELLIPSE -> target.addOval(bounds, Path.Direction.CW)
        }
    }

    private fun drawFeather(canvas: Canvas, bounds: RectF, outward: Boolean) {
        val featherPx = spec.feather * minOf(width, height)
        if (featherPx < 1f) return
        val steps = 7
        featherPaint.color = canvasColor
        for (index in 0 until steps) {
            val progress = (index + 1f) / steps
            featherPaint.strokeWidth = (featherPx / steps).coerceAtLeast(1f)
            featherPaint.alpha = ((1f - progress) * 150f).roundToInt().coerceIn(0, 180)
            val delta = featherPx * progress * if (outward) 0.5f else -0.5f
            val expanded = RectF(bounds).apply { inset(-delta, -delta) }
            path.reset()
            addShape(path, expanded)
            canvas.drawPath(path, featherPaint)
        }
        featherPaint.alpha = 255
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
