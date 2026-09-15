package com.vedito.app.core.export

import com.vedito.app.core.color.ColorGradeEngine
import com.vedito.app.core.compositor.FrameComposition
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.MaskShape
import com.vedito.app.core.visual.MaskChromaComposition
import com.vedito.app.core.visual.VisualTransformMath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android-free source-texture graph used by Patch 23 export.
 *
 * The plan resolves the canonical main-source stages that used to be rasterized on Canvas:
 * crop/fit/transform -> chroma -> color -> opacity -> mask. Timed post effects/transitions remain
 * a separate GPU plan so preview/export ownership stays deterministic and extensible.
 */
data class GpuSourceGraphPlan(
    val enabled: Boolean,
    val cropLeft: Float,
    val cropTop: Float,
    val cropWidth: Float,
    val cropHeight: Float,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
    val centerXPx: Float,
    val centerYPx: Float,
    val rotationDegrees: Float,
    val flipX: Float,
    val flipY: Float,
    val opacity: Float,
    val chromaEnabled: Boolean,
    val chromaKeyR: Float,
    val chromaKeyG: Float,
    val chromaKeyB: Float,
    val chromaTolerance: Float,
    val chromaSoftness: Float,
    val chromaSpill: Float,
    val colorMatrix: FloatArray,
    val maskShapeCode: Int,
    val maskCenterX: Float,
    val maskCenterY: Float,
    val maskWidth: Float,
    val maskHeight: Float,
    val maskFeather: Float,
    val maskInverted: Boolean,
    val backgroundR: Float,
    val backgroundG: Float,
    val backgroundB: Float
) {
    companion object {
        fun disabled(backgroundArgb: Int = 0xFF000000.toInt()): GpuSourceGraphPlan = GpuSourceGraphPlan(
            enabled = false,
            cropLeft = 0f,
            cropTop = 0f,
            cropWidth = 1f,
            cropHeight = 1f,
            contentWidthPx = 1f,
            contentHeightPx = 1f,
            centerXPx = 0f,
            centerYPx = 0f,
            rotationDegrees = 0f,
            flipX = 1f,
            flipY = 1f,
            opacity = 1f,
            chromaEnabled = false,
            chromaKeyR = 0f,
            chromaKeyG = 1f,
            chromaKeyB = 0f,
            chromaTolerance = 0.22f,
            chromaSoftness = 0.10f,
            chromaSpill = 0.15f,
            colorMatrix = identityColorMatrix(),
            maskShapeCode = 0,
            maskCenterX = 0.5f,
            maskCenterY = 0.5f,
            maskWidth = 1f,
            maskHeight = 1f,
            maskFeather = 0f,
            maskInverted = false,
            backgroundR = channel(backgroundArgb, 16),
            backgroundG = channel(backgroundArgb, 8),
            backgroundB = channel(backgroundArgb, 0)
        )

        private fun identityColorMatrix(): FloatArray = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )

        private fun channel(argb: Int, shift: Int): Float = ((argb ushr shift) and 0xFF) / 255f
    }
}

object GpuSourceGraphPlanner {
    /**
     * Resolves the exact geometry used by the Patch 19-22 Canvas compositor, including its
     * position multiplier and crop/fit behavior, so switching execution to GLES does not create
     * a second visual model.
     */
    fun plan(
        frame: FrameComposition,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        backgroundArgb: Int,
        positionMultiplier: Float = 0.42f
    ): GpuSourceGraphPlan {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            return GpuSourceGraphPlan.disabled(backgroundArgb)
        }

        val transform = VisualTransformMath.normalize(frame.transform)
        val crop = VisualTransformMath.cropWindow(transform)
        val croppedW = sourceWidth * crop.widthFraction
        val croppedH = sourceHeight * crop.heightFraction
        val swapsAxes = transform.rotationDegrees.roundToInt() % 180 != 0
        val fittedW = if (swapsAxes) croppedH else croppedW
        val fittedH = if (swapsAxes) croppedW else croppedH
        val fitScale = when (transform.fitMode) {
            ClipFitMode.FIT -> min(outputWidth / fittedW, outputHeight / fittedH)
            ClipFitMode.FILL -> max(outputWidth / fittedW, outputHeight / fittedH)
        }
        val mask = MaskChromaComposition.normalize(frame.mask)
        val chroma = MaskChromaComposition.normalize(frame.chromaKey)
        val colorMatrix = ColorGradeEngine.colorMatrix(frame.colorGrade).copyOf()
        // Android ColorMatrix offsets are 0..255; shader RGB channels are 0..1.
        colorMatrix[4] /= 255f
        colorMatrix[9] /= 255f
        colorMatrix[14] /= 255f
        colorMatrix[19] /= 255f

        return GpuSourceGraphPlan(
            enabled = true,
            cropLeft = crop.left,
            cropTop = crop.top,
            cropWidth = crop.widthFraction,
            cropHeight = crop.heightFraction,
            contentWidthPx = (croppedW * fitScale * transform.scale).coerceAtLeast(1f),
            contentHeightPx = (croppedH * fitScale * transform.scale).coerceAtLeast(1f),
            centerXPx = outputWidth / 2f + transform.positionX * outputWidth * positionMultiplier,
            centerYPx = outputHeight / 2f + transform.positionY * outputHeight * positionMultiplier,
            rotationDegrees = transform.rotationDegrees,
            flipX = if (transform.flipHorizontal) -1f else 1f,
            flipY = if (transform.flipVertical) -1f else 1f,
            opacity = transform.opacity.coerceIn(0f, 1f),
            chromaEnabled = chroma.enabled,
            chromaKeyR = ((chroma.keyColorArgb ushr 16) and 0xFF) / 255f,
            chromaKeyG = ((chroma.keyColorArgb ushr 8) and 0xFF) / 255f,
            chromaKeyB = (chroma.keyColorArgb and 0xFF) / 255f,
            chromaTolerance = chroma.tolerance,
            chromaSoftness = chroma.softness,
            chromaSpill = chroma.spill,
            colorMatrix = colorMatrix,
            maskShapeCode = when (mask.shape) {
                MaskShape.NONE -> 0
                MaskShape.RECTANGLE -> 1
                MaskShape.ELLIPSE -> 2
            },
            maskCenterX = mask.centerX,
            maskCenterY = mask.centerY,
            maskWidth = mask.width,
            maskHeight = mask.height,
            maskFeather = mask.feather,
            maskInverted = mask.inverted,
            backgroundR = ((backgroundArgb ushr 16) and 0xFF) / 255f,
            backgroundG = ((backgroundArgb ushr 8) and 0xFF) / 255f,
            backgroundB = (backgroundArgb and 0xFF) / 255f
        )
    }
}
