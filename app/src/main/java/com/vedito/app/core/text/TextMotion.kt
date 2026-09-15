package com.vedito.app.core.text

import com.vedito.app.core.model.TextAnimationKind
import com.vedito.app.core.model.TextAnimationSpec
import kotlin.math.max
import kotlin.math.min

/** Deterministic text motion math shared concept for preview and future export. */
data class TextMotionFrame(
    val alphaMultiplier: Float = 1f,
    val scaleMultiplier: Float = 1f,
    val translationYFraction: Float = 0f
)

object TextMotion {
    const val MIN_ANIMATION_MS = 80
    const val MAX_ANIMATION_MS = 2_000

    fun normalize(spec: TextAnimationSpec, clipDurationMs: Int): TextAnimationSpec {
        val maxHalf = max(MIN_ANIMATION_MS, clipDurationMs / 2)
        return spec.copy(
            inDurationMs = spec.inDurationMs.coerceIn(MIN_ANIMATION_MS, min(MAX_ANIMATION_MS, maxHalf)),
            outDurationMs = spec.outDurationMs.coerceIn(MIN_ANIMATION_MS, min(MAX_ANIMATION_MS, maxHalf))
        )
    }

    fun frame(spec: TextAnimationSpec, localMs: Int, clipDurationMs: Int): TextMotionFrame {
        if (spec.kind == TextAnimationKind.NONE || clipDurationMs <= 0) return TextMotionFrame()
        val safe = normalize(spec, clipDurationMs)
        val local = localMs.coerceIn(0, clipDurationMs)
        val inProgress = (local.toFloat() / safe.inDurationMs).coerceIn(0f, 1f)
        val outStart = (clipDurationMs - safe.outDurationMs).coerceAtLeast(0)
        val outProgress = if (local <= outStart) 1f else ((clipDurationMs - local).toFloat() / safe.outDurationMs).coerceIn(0f, 1f)
        val visibility = min(easeOut(inProgress), easeOut(outProgress))
        return when (safe.kind) {
            TextAnimationKind.NONE -> TextMotionFrame()
            TextAnimationKind.FADE -> TextMotionFrame(alphaMultiplier = visibility)
            TextAnimationKind.POP -> TextMotionFrame(
                alphaMultiplier = visibility,
                scaleMultiplier = 0.78f + 0.22f * easeOutBack(inProgress).coerceIn(0f, 1.15f)
            )
            TextAnimationKind.SLIDE_UP -> TextMotionFrame(
                alphaMultiplier = visibility,
                translationYFraction = (1f - easeOut(inProgress)) * 0.16f
            )
        }
    }

    private fun easeOut(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x)
    }

    private fun easeOutBack(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val t = x - 1f
        return 1f + c3 * t * t * t + c1 * t * t
    }
}
