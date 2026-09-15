package com.vedito.app.core.visual

import com.vedito.app.core.model.CanvasAspect
import com.vedito.app.core.model.ClipTransform

/**
 * Renderer-independent normalization/math shared by preview now and the future export compositor.
 * Keep visual state deterministic here instead of burying editor semantics in Android Views.
 */
object VisualTransformMath {
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 4f
    const val MIN_OPACITY = 0f
    const val MAX_OPACITY = 1f
    const val MAX_POSITION = 1.5f
    const val MAX_CROP_PER_AXIS = 0.9f

    data class CropWindow(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val widthFraction: Float get() = (1f - left - right).coerceAtLeast(0.1f)
        val heightFraction: Float get() = (1f - top - bottom).coerceAtLeast(0.1f)
        val centerOffsetX: Float get() = left + widthFraction / 2f - 0.5f
        val centerOffsetY: Float get() = top + heightFraction / 2f - 0.5f
    }

    fun normalize(input: ClipTransform): ClipTransform {
        var left = input.cropLeft.coerceIn(0f, 0.85f)
        var right = input.cropRight.coerceIn(0f, 0.85f)
        var top = input.cropTop.coerceIn(0f, 0.85f)
        var bottom = input.cropBottom.coerceIn(0f, 0.85f)

        val horizontal = left + right
        if (horizontal > MAX_CROP_PER_AXIS) {
            val factor = MAX_CROP_PER_AXIS / horizontal
            left *= factor
            right *= factor
        }
        val vertical = top + bottom
        if (vertical > MAX_CROP_PER_AXIS) {
            val factor = MAX_CROP_PER_AXIS / vertical
            top *= factor
            bottom *= factor
        }

        val rotation = ((input.rotationDegrees % 360f) + 360f) % 360f
        return input.copy(
            scale = input.scale.coerceIn(MIN_SCALE, MAX_SCALE),
            positionX = input.positionX.coerceIn(-MAX_POSITION, MAX_POSITION),
            positionY = input.positionY.coerceIn(-MAX_POSITION, MAX_POSITION),
            rotationDegrees = rotation,
            opacity = input.opacity.coerceIn(MIN_OPACITY, MAX_OPACITY),
            cropLeft = left,
            cropTop = top,
            cropRight = right,
            cropBottom = bottom
        )
    }

    fun cropWindow(transform: ClipTransform): CropWindow {
        val safe = normalize(transform)
        return CropWindow(safe.cropLeft, safe.cropTop, safe.cropRight, safe.cropBottom)
    }

    fun canvasRatio(aspect: CanvasAspect, sourceWidth: Int, sourceHeight: Int): Float {
        aspect.fixedRatioOrNull()?.let { return it }
        if (sourceWidth > 0 && sourceHeight > 0) return sourceWidth.toFloat() / sourceHeight.toFloat()
        return 16f / 9f
    }
}
