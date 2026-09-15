package com.vedito.app.core.keyframe

import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.FloatKeyframe
import com.vedito.app.core.model.KeyframeEasing
import com.vedito.app.core.model.TransformKeyframeSet
import com.vedito.app.core.timeline.ClipTimeMap
import com.vedito.app.core.visual.VisualTransformMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renderer-independent transform keyframe evaluator.
 *
 * Keyframe time is always local timeline time inside its owning clip/layer. The Android preview
 * consumes this state now; the future export compositor must consume the same evaluator/model.
 */
object KeyframeEngine {
    const val HIT_TOLERANCE_MS = 45

    fun evaluate(
        base: ClipTransform,
        keyframes: TransformKeyframeSet,
        localTimeMs: Int,
        durationMs: Int
    ): ClipTransform {
        val safeDuration = durationMs.coerceAtLeast(0)
        val t = localTimeMs.coerceIn(0, safeDuration)
        return VisualTransformMath.normalize(
            base.copy(
                scale = evaluateTrack(keyframes.scale, base.scale, t),
                positionX = evaluateTrack(keyframes.positionX, base.positionX, t),
                positionY = evaluateTrack(keyframes.positionY, base.positionY, t),
                rotationDegrees = evaluateTrack(keyframes.rotationDegrees, base.rotationDegrees, t),
                opacity = evaluateTrack(keyframes.opacity, base.opacity, t)
            )
        )
    }

    fun upsertTransform(
        current: ClipTransform,
        keyframes: TransformKeyframeSet,
        localTimeMs: Int,
        durationMs: Int,
        easing: KeyframeEasing = easingAt(keyframes, localTimeMs) ?: KeyframeEasing.LINEAR
    ): TransformKeyframeSet {
        val duration = durationMs.coerceAtLeast(0)
        val time = localTimeMs.coerceIn(0, duration)
        return normalize(
            keyframes.copy(
                scale = upsertTrack(keyframes.scale, time, current.scale, easing),
                positionX = upsertTrack(keyframes.positionX, time, current.positionX, easing),
                positionY = upsertTrack(keyframes.positionY, time, current.positionY, easing),
                rotationDegrees = upsertTrack(keyframes.rotationDegrees, time, current.rotationDegrees, easing),
                opacity = upsertTrack(keyframes.opacity, time, current.opacity, easing)
            ),
            duration
        )
    }

    fun removeAt(keyframes: TransformKeyframeSet, localTimeMs: Int, durationMs: Int): TransformKeyframeSet {
        val target = nearestPosition(keyframes, localTimeMs, HIT_TOLERANCE_MS) ?: return keyframes
        return normalize(
            keyframes.copy(
                scale = keyframes.scale.filterNot { it.timeMs == target },
                positionX = keyframes.positionX.filterNot { it.timeMs == target },
                positionY = keyframes.positionY.filterNot { it.timeMs == target },
                rotationDegrees = keyframes.rotationDegrees.filterNot { it.timeMs == target },
                opacity = keyframes.opacity.filterNot { it.timeMs == target }
            ),
            durationMs
        )
    }

    fun setEasingAt(
        keyframes: TransformKeyframeSet,
        localTimeMs: Int,
        easing: KeyframeEasing,
        durationMs: Int
    ): TransformKeyframeSet {
        val target = nearestPosition(keyframes, localTimeMs, HIT_TOLERANCE_MS) ?: return keyframes
        fun update(track: List<FloatKeyframe>) = track.map {
            if (it.timeMs == target) it.copy(easing = easing) else it
        }
        return normalize(
            keyframes.copy(
                scale = update(keyframes.scale),
                positionX = update(keyframes.positionX),
                positionY = update(keyframes.positionY),
                rotationDegrees = update(keyframes.rotationDegrees),
                opacity = update(keyframes.opacity)
            ),
            durationMs
        )
    }

    fun easingAt(keyframes: TransformKeyframeSet, localTimeMs: Int): KeyframeEasing? {
        val target = nearestPosition(keyframes, localTimeMs, HIT_TOLERANCE_MS) ?: return null
        return allTracks(keyframes).firstNotNullOfOrNull { track -> track.firstOrNull { it.timeMs == target }?.easing }
    }

    fun hasAt(keyframes: TransformKeyframeSet, localTimeMs: Int): Boolean =
        nearestPosition(keyframes, localTimeMs, HIT_TOLERANCE_MS) != null

    fun positions(keyframes: TransformKeyframeSet): List<Int> = allTracks(keyframes)
        .asSequence()
        .flatten()
        .map { it.timeMs }
        .distinct()
        .sorted()
        .toList()

    fun previousPosition(keyframes: TransformKeyframeSet, localTimeMs: Int): Int? =
        positions(keyframes).lastOrNull { it < localTimeMs - HIT_TOLERANCE_MS }

    fun nextPosition(keyframes: TransformKeyframeSet, localTimeMs: Int): Int? =
        positions(keyframes).firstOrNull { it > localTimeMs + HIT_TOLERANCE_MS }

    fun nearestPosition(keyframes: TransformKeyframeSet, localTimeMs: Int, toleranceMs: Int): Int? {
        return positions(keyframes)
            .minByOrNull { abs(it - localTimeMs) }
            ?.takeIf { abs(it - localTimeMs) <= toleranceMs }
    }

    fun normalize(keyframes: TransformKeyframeSet, durationMs: Int): TransformKeyframeSet {
        val duration = durationMs.coerceAtLeast(0)
        fun track(points: List<FloatKeyframe>): List<FloatKeyframe> = points
            .asSequence()
            .filter { it.value.isFinite() }
            .map { it.copy(timeMs = it.timeMs.coerceIn(0, duration)) }
            .sortedBy { it.timeMs }
            .groupBy { it.timeMs }
            .map { (_, values) -> values.last() }
        return TransformKeyframeSet(
            scale = track(keyframes.scale),
            positionX = track(keyframes.positionX),
            positionY = track(keyframes.positionY),
            rotationDegrees = track(keyframes.rotationDegrees),
            opacity = track(keyframes.opacity)
        )
    }

    /** Preserve animation progress when a clip's timeline duration changes (for speed changes). */
    fun rescaleDuration(keyframes: TransformKeyframeSet, oldDurationMs: Int, newDurationMs: Int): TransformKeyframeSet {
        if (keyframes.isEmpty || oldDurationMs <= 0) return normalize(keyframes, newDurationMs)
        val ratio = newDurationMs.coerceAtLeast(0).toFloat() / oldDurationMs
        fun track(points: List<FloatKeyframe>) = points.map { it.copy(timeMs = (it.timeMs * ratio).toInt()) }
        return normalize(
            keyframes.copy(
                scale = track(keyframes.scale),
                positionX = track(keyframes.positionX),
                positionY = track(keyframes.positionY),
                rotationDegrees = track(keyframes.rotationDegrees),
                opacity = track(keyframes.opacity)
            ),
            newDurationMs
        )
    }

    /**
     * Remap keyframes through a source trim using the canonical ClipTimeMap. Points outside the new
     * source window are discarded. Boundary values are re-created from the old animation so trims
     * do not cause a visible jump at the surviving edge.
     */
    fun remapForClipTrim(oldClip: Clip, newClip: Clip): TransformKeyframeSet {
        if (oldClip.keyframes.isEmpty) return oldClip.keyframes
        val oldDuration = oldClip.durationMs.coerceAtLeast(1)
        val newDuration = newClip.durationMs.coerceAtLeast(1)
        val old = normalize(oldClip.keyframes, oldDuration)

        fun remapTrack(track: List<FloatKeyframe>, base: Float): List<FloatKeyframe> {
            val kept = track.mapNotNull { point ->
                val source = ClipTimeMap.sourcePositionAtTimelineOffset(oldClip, point.timeMs)
                if (source < newClip.sourceStartMs || source > newClip.sourceEndMs) return@mapNotNull null
                val local = ClipTimeMap.timelineOffsetForSourcePosition(newClip, source).coerceIn(0, newDuration)
                point.copy(timeMs = local)
            }.toMutableList()

            val startSource = ClipTimeMap.sourcePositionAtTimelineOffset(newClip, 0)
            val startOldLocal = ClipTimeMap.timelineOffsetForSourcePosition(oldClip, startSource).coerceIn(0, oldDuration)
            val startValue = evaluateTrack(track, base, startOldLocal)
            kept.add(FloatKeyframe(0, startValue, easingAtTime(track, startOldLocal) ?: KeyframeEasing.LINEAR))

            val endSource = ClipTimeMap.sourcePositionAtTimelineOffset(newClip, newDuration)
            val endOldLocal = ClipTimeMap.timelineOffsetForSourcePosition(oldClip, endSource).coerceIn(0, oldDuration)
            val endValue = evaluateTrack(track, base, endOldLocal)
            kept.add(FloatKeyframe(newDuration, endValue, easingAtTime(track, endOldLocal) ?: KeyframeEasing.LINEAR))
            return kept
        }

        return normalize(
            TransformKeyframeSet(
                scale = remapTrack(old.scale, oldClip.transform.scale),
                positionX = remapTrack(old.positionX, oldClip.transform.positionX),
                positionY = remapTrack(old.positionY, oldClip.transform.positionY),
                rotationDegrees = remapTrack(old.rotationDegrees, oldClip.transform.rotationDegrees),
                opacity = remapTrack(old.opacity, oldClip.transform.opacity)
            ),
            newDuration
        )
    }

    /** Split local keyframe tracks while preserving an interpolated transform at the cut boundary. */
    fun split(
        base: ClipTransform,
        keyframes: TransformKeyframeSet,
        splitLocalMs: Int,
        leftDurationMs: Int,
        rightDurationMs: Int
    ): Pair<TransformKeyframeSet, TransformKeyframeSet> {
        if (keyframes.isEmpty) return TransformKeyframeSet() to TransformKeyframeSet()
        val split = splitLocalMs.coerceAtLeast(0)
        val originalDuration = max(split, split + rightDurationMs).coerceAtLeast(1)
        val normalized = normalize(keyframes, originalDuration)

        fun splitTrack(track: List<FloatKeyframe>, baseValue: Float): Pair<List<FloatKeyframe>, List<FloatKeyframe>> {
            val boundaryValue = evaluateTrack(track, baseValue, split)
            val boundaryEasing = easingAtTime(track, split) ?: KeyframeEasing.LINEAR
            val left = track.filter { it.timeMs < split }.toMutableList().apply {
                add(FloatKeyframe(leftDurationMs.coerceAtLeast(0), boundaryValue, boundaryEasing))
            }
            val right = track.filter { it.timeMs > split }
                .map { it.copy(timeMs = (it.timeMs - split).coerceAtLeast(0)) }
                .toMutableList()
                .apply { add(FloatKeyframe(0, boundaryValue, boundaryEasing)) }
            return left to right
        }

        val s = splitTrack(normalized.scale, base.scale)
        val x = splitTrack(normalized.positionX, base.positionX)
        val y = splitTrack(normalized.positionY, base.positionY)
        val r = splitTrack(normalized.rotationDegrees, base.rotationDegrees)
        val o = splitTrack(normalized.opacity, base.opacity)
        return normalize(TransformKeyframeSet(s.first, x.first, y.first, r.first, o.first), leftDurationMs) to
            normalize(TransformKeyframeSet(s.second, x.second, y.second, r.second, o.second), rightDurationMs)
    }

    /** Shift local animation after trimming the left edge of a timed overlay. */
    fun shiftForLeftTrim(keyframes: TransformKeyframeSet, removedMs: Int, newDurationMs: Int): TransformKeyframeSet {
        if (keyframes.isEmpty) return keyframes
        val removed = removedMs.coerceAtLeast(0)
        fun track(points: List<FloatKeyframe>) = points.mapNotNull {
            val next = it.timeMs - removed
            if (next < 0) null else it.copy(timeMs = next)
        }
        return normalize(
            keyframes.copy(
                scale = track(keyframes.scale),
                positionX = track(keyframes.positionX),
                positionY = track(keyframes.positionY),
                rotationDegrees = track(keyframes.rotationDegrees),
                opacity = track(keyframes.opacity)
            ),
            newDurationMs
        )
    }

    private fun upsertTrack(
        track: List<FloatKeyframe>,
        timeMs: Int,
        value: Float,
        easing: KeyframeEasing
    ): List<FloatKeyframe> {
        val next = track.toMutableList()
        val index = next.indexOfFirst { abs(it.timeMs - timeMs) <= HIT_TOLERANCE_MS }
        if (index >= 0) {
            val previous = next[index]
            next[index] = previous.copy(timeMs = timeMs, value = value)
        } else {
            next.add(FloatKeyframe(timeMs, value, easing))
        }
        return next
    }

    private fun evaluateTrack(track: List<FloatKeyframe>, base: Float, timeMs: Int): Float {
        if (track.isEmpty()) return base
        val sorted = track.sortedBy { it.timeMs }
        val first = sorted.first()
        if (timeMs < first.timeMs) return base
        if (timeMs == first.timeMs || sorted.size == 1) return first.value
        val last = sorted.last()
        if (timeMs >= last.timeMs) return last.value

        var left = first
        var right = last
        for (index in 1 until sorted.size) {
            if (timeMs <= sorted[index].timeMs) {
                left = sorted[index - 1]
                right = sorted[index]
                break
            }
        }
        val span = (right.timeMs - left.timeMs).coerceAtLeast(1)
        val raw = ((timeMs - left.timeMs).toFloat() / span).coerceIn(0f, 1f)
        val eased = easedProgress(raw, left.easing)
        return left.value + (right.value - left.value) * eased
    }

    private fun easedProgress(t: Float, easing: KeyframeEasing): Float = when (easing) {
        KeyframeEasing.LINEAR -> t
        KeyframeEasing.EASE_IN -> t * t
        KeyframeEasing.EASE_OUT -> 1f - (1f - t) * (1f - t)
        KeyframeEasing.EASE_IN_OUT -> t * t * (3f - 2f * t)
        KeyframeEasing.HOLD -> 0f
    }

    private fun easingAtTime(track: List<FloatKeyframe>, timeMs: Int): KeyframeEasing? {
        if (track.isEmpty()) return null
        return track.minByOrNull { abs(it.timeMs - timeMs) }?.easing
    }

    private fun allTracks(keyframes: TransformKeyframeSet): List<List<FloatKeyframe>> = listOf(
        keyframes.scale,
        keyframes.positionX,
        keyframes.positionY,
        keyframes.rotationDegrees,
        keyframes.opacity
    )
}
