package com.vedito.app.core.text

import com.vedito.app.core.keyframe.KeyframeEngine
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.KeyframeEasing
import com.vedito.app.core.model.TextTransform
import com.vedito.app.core.model.TransformKeyframeSet

/**
 * Text-specific adapter around the canonical transform keyframe engine.
 * Keeps text preview/export on exactly the same interpolation/easing math as visual clips.
 */
object TextKeyframeEngine {
    fun evaluate(base: TextTransform, keyframes: TransformKeyframeSet, localTimeMs: Int, durationMs: Int): TextTransform {
        val evaluated = KeyframeEngine.evaluate(base.toClipTransform(), keyframes, localTimeMs, durationMs)
        return TextTimelineEditor.normalizeTransform(evaluated.toTextTransform())
    }

    fun upsertTransform(
        current: TextTransform,
        keyframes: TransformKeyframeSet,
        localTimeMs: Int,
        durationMs: Int,
        easing: KeyframeEasing = KeyframeEngine.easingAt(keyframes, localTimeMs) ?: KeyframeEasing.LINEAR
    ): TransformKeyframeSet = KeyframeEngine.upsertTransform(
        current = current.toClipTransform(),
        keyframes = keyframes,
        localTimeMs = localTimeMs,
        durationMs = durationMs,
        easing = easing
    )

    fun removeAt(keyframes: TransformKeyframeSet, localTimeMs: Int, durationMs: Int): TransformKeyframeSet =
        KeyframeEngine.removeAt(keyframes, localTimeMs, durationMs)

    fun setEasingAt(keyframes: TransformKeyframeSet, localTimeMs: Int, easing: KeyframeEasing, durationMs: Int): TransformKeyframeSet =
        KeyframeEngine.setEasingAt(keyframes, localTimeMs, easing, durationMs)

    fun easingAt(keyframes: TransformKeyframeSet, localTimeMs: Int): KeyframeEasing? =
        KeyframeEngine.easingAt(keyframes, localTimeMs)

    fun hasAt(keyframes: TransformKeyframeSet, localTimeMs: Int): Boolean =
        KeyframeEngine.hasAt(keyframes, localTimeMs)

    fun previousPosition(keyframes: TransformKeyframeSet, localTimeMs: Int): Int? =
        KeyframeEngine.previousPosition(keyframes, localTimeMs)

    fun nextPosition(keyframes: TransformKeyframeSet, localTimeMs: Int): Int? =
        KeyframeEngine.nextPosition(keyframes, localTimeMs)

    fun normalize(keyframes: TransformKeyframeSet, durationMs: Int): TransformKeyframeSet =
        KeyframeEngine.normalize(keyframes, durationMs)

    fun remapForLeftEdge(
        base: TextTransform,
        keyframes: TransformKeyframeSet,
        oldDurationMs: Int,
        startDeltaMs: Int,
        newDurationMs: Int
    ): TransformKeyframeSet {
        if (keyframes.isEmpty || startDeltaMs == 0) return normalize(keyframes, newDurationMs)
        if (startDeltaMs > 0) {
            val splitAt = startDeltaMs.coerceIn(0, oldDurationMs.coerceAtLeast(0))
            return KeyframeEngine.split(
                base = base.toClipTransform(),
                keyframes = keyframes,
                splitLocalMs = splitAt,
                leftDurationMs = splitAt,
                rightDurationMs = newDurationMs
            ).second
        }
        val shift = -startDeltaMs
        fun shifted(track: List<com.vedito.app.core.model.FloatKeyframe>) = track.map { it.copy(timeMs = it.timeMs + shift) }
        return normalize(
            TransformKeyframeSet(
                scale = shifted(keyframes.scale),
                positionX = shifted(keyframes.positionX),
                positionY = shifted(keyframes.positionY),
                rotationDegrees = shifted(keyframes.rotationDegrees),
                opacity = shifted(keyframes.opacity)
            ),
            newDurationMs
        )
    }

    fun remapForRightEdge(
        base: TextTransform,
        keyframes: TransformKeyframeSet,
        oldDurationMs: Int,
        newDurationMs: Int
    ): TransformKeyframeSet {
        if (keyframes.isEmpty || oldDurationMs <= newDurationMs) return normalize(keyframes, newDurationMs)
        return KeyframeEngine.split(
            base = base.toClipTransform(),
            keyframes = keyframes,
            splitLocalMs = newDurationMs,
            leftDurationMs = newDurationMs,
            rightDurationMs = (oldDurationMs - newDurationMs).coerceAtLeast(0)
        ).first
    }

    private fun TextTransform.toClipTransform() = ClipTransform(
        scale = scale,
        positionX = positionX,
        positionY = positionY,
        rotationDegrees = rotationDegrees,
        opacity = opacity
    )

    private fun ClipTransform.toTextTransform() = TextTransform(
        scale = scale,
        positionX = positionX,
        positionY = positionY,
        rotationDegrees = rotationDegrees,
        opacity = opacity
    )
}
