package com.vedito.app.core.export

import com.vedito.app.core.effect.EffectComposition
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.EffectClip
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.model.VideoEffectKind

/**
 * Android-free performance policy for export frame acquisition and GPU post processing.
 * Project/render ownership still comes from FrameCompositionBuilder; this only chooses how to execute it.
 */
enum class FrameAccessMode {
    STREAMING,
    HOLD_FRAME,
    RANDOM_ACCESS
}

object FrameAccessPlanner {
    fun forPlaybackMode(mode: ClipPlaybackMode): FrameAccessMode = when (mode) {
        ClipPlaybackMode.FORWARD -> FrameAccessMode.STREAMING
        ClipPlaybackMode.FREEZE -> FrameAccessMode.HOLD_FRAME
        ClipPlaybackMode.REVERSE -> FrameAccessMode.RANDOM_ACCESS
    }

    fun shouldRestartStream(lastTargetUs: Long, targetUs: Long, maxForwardGapUs: Long = 1_500_000L): Boolean {
        if (lastTargetUs < 0L) return true
        if (targetUs + BACKWARD_TOLERANCE_US < lastTargetUs) return true
        return targetUs - lastTargetUs > maxForwardGapUs
    }

    fun decodeTarget(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        maxDimension: Int = 3_840
    ): Pair<Int, Int> {
        val safeSourceW = sourceWidth.coerceAtLeast(2)
        val safeSourceH = sourceHeight.coerceAtLeast(2)
        val cap = maxDimension.coerceIn(2, 3_840)
        val maxW = (outputWidth * 1.35f).toInt().coerceIn(2, cap)
        val maxH = (outputHeight * 1.35f).toInt().coerceIn(2, cap)
        val scale = minOf(1f, maxW.toFloat() / safeSourceW, maxH.toFloat() / safeSourceH)
        val width = ((safeSourceW * scale).toInt().coerceAtLeast(2) / 2) * 2
        val height = ((safeSourceH * scale).toInt().coerceAtLeast(2) / 2) * 2
        return width.coerceAtLeast(2) to height.coerceAtLeast(2)
    }

    private const val BACKWARD_TOLERANCE_US = 35_000L
}

data class GpuEffectSlot(
    val kind: VideoEffectKind,
    val intensity: Float
)

data class GpuPostProcessPlan(
    val gpuEligible: Boolean,
    val effects: List<GpuEffectSlot>,
    val transitionKind: TransitionKind,
    val transitionProgress: Float,
    val transitionStrength: Float
) {
    companion object {
        val NEUTRAL = GpuPostProcessPlan(
            gpuEligible = true,
            effects = emptyList(),
            transitionKind = TransitionKind.NONE,
            transitionProgress = 0f,
            transitionStrength = 0f
        )
    }
}

/**
 * Keeps the GPU contract intentionally small and deterministic.
 * Grain remains on the CPU fallback in Patch 21 so its sparse-point visual does not silently change.
 */
object GpuPostProcessPlanner {
    const val MAX_EFFECT_SLOTS = 4

    fun plan(
        activeEffects: List<EffectClip>,
        transition: EffectComposition.TransitionFrame?
    ): GpuPostProcessPlan {
        val normalized = activeEffects.map {
            GpuEffectSlot(it.kind, it.intensity.coerceIn(0f, 1f))
        }
        val eligible = normalized.size <= MAX_EFFECT_SLOTS && normalized.none { it.kind == VideoEffectKind.GRAIN }
        return GpuPostProcessPlan(
            gpuEligible = eligible,
            effects = if (eligible) normalized else emptyList(),
            transitionKind = transition?.kind ?: TransitionKind.NONE,
            transitionProgress = transition?.progress?.coerceIn(0f, 1f) ?: 0f,
            transitionStrength = transition?.strength?.coerceIn(0f, 1f) ?: 0f
        )
    }
}
