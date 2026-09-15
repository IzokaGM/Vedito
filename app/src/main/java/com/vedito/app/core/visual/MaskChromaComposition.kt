package com.vedito.app.core.visual

import com.vedito.app.core.model.ChromaKeySpec
import com.vedito.app.core.model.MaskShape
import com.vedito.app.core.model.MaskSpec
import kotlin.math.max

/**
 * Renderer-independent normalization/state helpers for masks and chroma key.
 * Preview and the future export compositor must consume the same normalized state.
 */
object MaskChromaComposition {
    fun normalize(mask: MaskSpec): MaskSpec {
        val width = mask.width.coerceIn(MIN_MASK_SIZE, 1f)
        val height = mask.height.coerceIn(MIN_MASK_SIZE, 1f)
        val halfW = width / 2f
        val halfH = height / 2f
        return mask.copy(
            centerX = mask.centerX.coerceIn(halfW, 1f - halfW),
            centerY = mask.centerY.coerceIn(halfH, 1f - halfH),
            width = width,
            height = height,
            feather = mask.feather.coerceIn(0f, MAX_FEATHER)
        )
    }

    fun normalize(chroma: ChromaKeySpec): ChromaKeySpec = chroma.copy(
        tolerance = chroma.tolerance.coerceIn(0.02f, 0.75f),
        softness = chroma.softness.coerceIn(0.01f, 0.50f),
        spill = chroma.spill.coerceIn(0f, 1f)
    )

    fun cycleShape(mask: MaskSpec): MaskSpec {
        val values = MaskShape.values()
        val index = values.indexOf(mask.shape).coerceAtLeast(0)
        return normalize(mask.copy(shape = values[(index + 1) % values.size]))
    }

    fun resize(mask: MaskSpec, delta: Float): MaskSpec {
        val current = normalize(mask)
        val nextW = (current.width + delta).coerceIn(MIN_MASK_SIZE, 1f)
        val aspect = current.height / max(current.width, 0.001f)
        val nextH = (nextW * aspect).coerceIn(MIN_MASK_SIZE, 1f)
        return normalize(current.copy(width = nextW, height = nextH))
    }

    fun move(mask: MaskSpec, dx: Float, dy: Float): MaskSpec = normalize(
        mask.copy(centerX = mask.centerX + dx, centerY = mask.centerY + dy)
    )

    const val MIN_MASK_SIZE = 0.18f
    const val MAX_FEATHER = 0.30f
}
