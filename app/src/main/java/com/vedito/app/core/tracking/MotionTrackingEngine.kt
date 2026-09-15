package com.vedito.app.core.tracking

import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.MotionTrackSpec
import com.vedito.app.core.model.StabilizationSpec
import com.vedito.app.core.model.TrackingPoint
import com.vedito.app.core.model.TrackingPointSource
import com.vedito.app.core.timeline.ClipTimeMap
import com.vedito.app.core.visual.VisualTransformMath
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renderer-independent motion tracking + stabilization state evaluator.
 *
 * Patch 17 intentionally starts with manual anchors. Future detector/optical-flow assistance must
 * write the same TrackingPoint model instead of creating a second tracking timeline.
 */
object MotionTrackingEngine {
    const val HIT_TOLERANCE_MS = 90
    private const val PREVIEW_POSITION_FACTOR = 0.42f

    fun normalize(track: MotionTrackSpec, durationMs: Int): MotionTrackSpec {
        val duration = durationMs.coerceAtLeast(0)
        val points = track.points.asSequence()
            .filter { it.x.isFinite() && it.y.isFinite() && it.confidence.isFinite() }
            .map {
                it.copy(
                    timeMs = it.timeMs.coerceIn(0, duration),
                    x = it.x.coerceIn(0f, 1f),
                    y = it.y.coerceIn(0f, 1f),
                    confidence = it.confidence.coerceIn(0f, 1f)
                )
            }
            .sortedBy { it.timeMs }
            .groupBy { it.timeMs }
            .map { (_, values) -> values.last() }
        return track.copy(enabled = track.enabled && points.isNotEmpty(), points = points)
    }

    fun normalize(stabilization: StabilizationSpec): StabilizationSpec = stabilization.copy(
        strength = stabilization.strength.coerceIn(0f, 1f)
    )

    fun evaluate(track: MotionTrackSpec, localTimeMs: Int, durationMs: Int): TrackingPoint? {
        val safe = normalize(track, durationMs)
        val points = safe.points
        if (points.isEmpty()) return null
        val time = localTimeMs.coerceIn(0, durationMs.coerceAtLeast(0))
        val exact = points.minByOrNull { abs(it.timeMs - time) }
        if (exact != null && abs(exact.timeMs - time) <= HIT_TOLERANCE_MS) return exact
        val left = points.lastOrNull { it.timeMs <= time }
        val right = points.firstOrNull { it.timeMs >= time }
        if (left == null) return right
        if (right == null) return left
        if (left.timeMs == right.timeMs) return left
        val span = (right.timeMs - left.timeMs).coerceAtLeast(1)
        val t = ((time - left.timeMs).toFloat() / span).coerceIn(0f, 1f)
        return TrackingPoint(
            timeMs = time,
            x = left.x + (right.x - left.x) * t,
            y = left.y + (right.y - left.y) * t,
            confidence = left.confidence + (right.confidence - left.confidence) * t,
            source = if (left.source == right.source) left.source else TrackingPointSource.ASSISTED
        )
    }

    fun upsert(
        track: MotionTrackSpec,
        localTimeMs: Int,
        x: Float,
        y: Float,
        durationMs: Int,
        source: TrackingPointSource = TrackingPointSource.MANUAL
    ): MotionTrackSpec {
        val duration = durationMs.coerceAtLeast(0)
        val time = localTimeMs.coerceIn(0, duration)
        val next = normalize(track, duration).points.toMutableList()
        val index = next.indexOfFirst { abs(it.timeMs - time) <= HIT_TOLERANCE_MS }
        val point = TrackingPoint(time, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), 1f, source)
        if (index >= 0) next[index] = point else next += point
        return normalize(MotionTrackSpec(enabled = true, points = next), duration)
    }

    fun removeNearest(track: MotionTrackSpec, localTimeMs: Int, durationMs: Int): MotionTrackSpec {
        val safe = normalize(track, durationMs)
        val target = safe.points.minByOrNull { abs(it.timeMs - localTimeMs) }
            ?.takeIf { abs(it.timeMs - localTimeMs) <= HIT_TOLERANCE_MS }
            ?: return safe
        return normalize(safe.copy(points = safe.points.filterNot { it.timeMs == target.timeMs }), durationMs)
    }

    fun previousPointTime(track: MotionTrackSpec, localTimeMs: Int, durationMs: Int): Int? =
        normalize(track, durationMs).points.lastOrNull { it.timeMs < localTimeMs - HIT_TOLERANCE_MS }?.timeMs

    fun nextPointTime(track: MotionTrackSpec, localTimeMs: Int, durationMs: Int): Int? =
        normalize(track, durationMs).points.firstOrNull { it.timeMs > localTimeMs + HIT_TOLERANCE_MS }?.timeMs

    /**
     * Apply deterministic stabilization from the manual/assisted track to a visual transform.
     * The first point is the reference framing anchor; later movement is cancelled by an inverse
     * translation. This is intentionally simple and export-compatible, not a production gyro/flow
     * stabilizer yet.
     */
    fun applyStabilization(
        base: ClipTransform,
        track: MotionTrackSpec,
        stabilization: StabilizationSpec,
        localTimeMs: Int,
        durationMs: Int
    ): ClipTransform {
        val stable = normalize(stabilization)
        if (!stable.enabled) return VisualTransformMath.normalize(base)
        val safeTrack = normalize(track, durationMs)
        if (safeTrack.points.size < 2) return VisualTransformMath.normalize(base)
        val current = evaluate(safeTrack, localTimeMs, durationMs) ?: return VisualTransformMath.normalize(base)
        val reference = safeTrack.points.first()
        val dx = (reference.x - current.x) / PREVIEW_POSITION_FACTOR * stable.strength
        val dy = (reference.y - current.y) / PREVIEW_POSITION_FACTOR * stable.strength
        val autoCropScale = if (stable.autoCrop) 1f + 0.12f * stable.strength else 1f
        return VisualTransformMath.normalize(
            base.copy(
                positionX = base.positionX + dx,
                positionY = base.positionY + dy,
                scale = base.scale * autoCropScale
            )
        )
    }

    /** Mirror local tracking time when forward/reverse source playback direction changes. */
    fun reverseTimeline(track: MotionTrackSpec, durationMs: Int): MotionTrackSpec {
        val duration = durationMs.coerceAtLeast(0)
        val safe = normalize(track, duration)
        return normalize(
            safe.copy(points = safe.points.map { it.copy(timeMs = (duration - it.timeMs).coerceIn(0, duration)) }),
            duration
        )
    }

    /** Preserve tracking sample timing when a clip duration changes because of uniform speed. */
    fun rescaleDuration(track: MotionTrackSpec, oldDurationMs: Int, newDurationMs: Int): MotionTrackSpec {
        if (oldDurationMs <= 0 || track.points.isEmpty()) return normalize(track, newDurationMs)
        val ratio = newDurationMs.coerceAtLeast(0).toFloat() / oldDurationMs
        return normalize(
            track.copy(points = track.points.map { it.copy(timeMs = (it.timeMs * ratio).roundToInt()) }),
            newDurationMs
        )
    }

    /** Map local tracking points through a source trim using the canonical ClipTimeMap. */
    fun remapForClipTrim(oldClip: Clip, newClip: Clip): MotionTrackSpec {
        val oldTrack = normalize(oldClip.motionTrack, oldClip.durationMs)
        if (oldTrack.points.isEmpty()) return oldTrack
        val newDuration = newClip.durationMs.coerceAtLeast(0)
        val kept = oldTrack.points.mapNotNull { point ->
            val source = ClipTimeMap.sourcePositionAtTimelineOffset(oldClip, point.timeMs)
            if (source < newClip.sourceStartMs || source > newClip.sourceEndMs) return@mapNotNull null
            point.copy(timeMs = ClipTimeMap.timelineOffsetForSourcePosition(newClip, source).coerceIn(0, newDuration))
        }
        return normalize(oldTrack.copy(points = kept), newDuration)
    }

    /** Split a local track into left/right ownership at a clip cut. */
    fun split(
        track: MotionTrackSpec,
        splitLocalMs: Int,
        leftDurationMs: Int,
        rightDurationMs: Int
    ): Pair<MotionTrackSpec, MotionTrackSpec> {
        val originalDuration = (leftDurationMs + rightDurationMs).coerceAtLeast(0)
        val safe = normalize(track, originalDuration)
        if (safe.points.isEmpty()) return MotionTrackSpec() to MotionTrackSpec()
        val split = splitLocalMs.coerceIn(0, originalDuration)
        val boundary = evaluate(safe, split, originalDuration)
        val leftPoints = safe.points.filter { it.timeMs < split }.toMutableList()
        val rightPoints = safe.points.filter { it.timeMs > split }
            .map { it.copy(timeMs = (it.timeMs - split).coerceAtLeast(0)) }
            .toMutableList()
        if (boundary != null) {
            leftPoints += boundary.copy(timeMs = leftDurationMs.coerceAtLeast(0))
            rightPoints += boundary.copy(timeMs = 0)
        }
        return normalize(safe.copy(points = leftPoints), leftDurationMs) to
            normalize(safe.copy(points = rightPoints), rightDurationMs)
    }
}
