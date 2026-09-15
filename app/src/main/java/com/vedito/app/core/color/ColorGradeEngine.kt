package com.vedito.app.core.color

import com.vedito.app.core.model.ColorGradeSpec
import kotlin.math.pow

/**
 * Renderer-independent color normalization and matrix generation.
 * Values are intentionally normalized here so preview and future export use identical math.
 */
object ColorGradeEngine {
    const val MIN_EXPOSURE = -2f
    const val MAX_EXPOSURE = 2f
    const val MIN_ADJUST = -1f
    const val MAX_ADJUST = 1f

    fun normalize(spec: ColorGradeSpec): ColorGradeSpec = spec.copy(
        exposure = spec.exposure.coerceIn(MIN_EXPOSURE, MAX_EXPOSURE),
        contrast = spec.contrast.coerceIn(MIN_ADJUST, MAX_ADJUST),
        saturation = spec.saturation.coerceIn(MIN_ADJUST, MAX_ADJUST),
        temperature = spec.temperature.coerceIn(MIN_ADJUST, MAX_ADJUST),
        tint = spec.tint.coerceIn(MIN_ADJUST, MAX_ADJUST),
        fade = spec.fade.coerceIn(0f, 1f)
    )

    fun isNeutral(spec: ColorGradeSpec): Boolean {
        val safe = normalize(spec)
        return safe.exposure == 0f && safe.contrast == 0f && safe.saturation == 0f &&
            safe.temperature == 0f && safe.tint == 0f && safe.fade == 0f
    }

    /** Android-compatible 4x5 color matrix, but contains no Android dependencies. */
    fun colorMatrix(spec: ColorGradeSpec): FloatArray {
        val safe = normalize(spec)
        val saturation = 1f + safe.saturation
        val invSat = 1f - saturation
        val rLum = 0.2126f
        val gLum = 0.7152f
        val bLum = 0.0722f

        val sat = arrayOf(
            floatArrayOf(invSat * rLum + saturation, invSat * gLum, invSat * bLum),
            floatArrayOf(invSat * rLum, invSat * gLum + saturation, invSat * bLum),
            floatArrayOf(invSat * rLum, invSat * gLum, invSat * bLum + saturation)
        )

        val exposureGain = 2.0.pow(safe.exposure.toDouble()).toFloat()
        val contrastGain = 1f + safe.contrast * 0.55f
        val fadeGain = 1f - safe.fade * 0.22f
        val baseGain = exposureGain * contrastGain * fadeGain

        val redGain = (1f + safe.temperature * 0.10f + safe.tint * 0.045f).coerceAtLeast(0.1f)
        val greenGain = (1f - safe.tint * 0.08f).coerceAtLeast(0.1f)
        val blueGain = (1f - safe.temperature * 0.10f + safe.tint * 0.045f).coerceAtLeast(0.1f)
        val channelGain = floatArrayOf(redGain, greenGain, blueGain)

        val contrastOffset = 128f * (1f - contrastGain)
        val fadeOffset = 255f * safe.fade * 0.075f
        val temperatureOffset = 255f * safe.temperature * 0.018f
        val tintOffset = 255f * safe.tint * 0.012f
        val offsets = floatArrayOf(
            contrastOffset + fadeOffset + temperatureOffset + tintOffset,
            contrastOffset + fadeOffset - tintOffset * 1.5f,
            contrastOffset + fadeOffset - temperatureOffset + tintOffset
        )

        val out = FloatArray(20)
        for (row in 0..2) {
            val rowGain = baseGain * channelGain[row]
            out[row * 5] = sat[row][0] * rowGain
            out[row * 5 + 1] = sat[row][1] * rowGain
            out[row * 5 + 2] = sat[row][2] * rowGain
            out[row * 5 + 3] = 0f
            out[row * 5 + 4] = offsets[row]
        }
        out[15] = 0f
        out[16] = 0f
        out[17] = 0f
        out[18] = 1f
        out[19] = 0f
        return out
    }
}
