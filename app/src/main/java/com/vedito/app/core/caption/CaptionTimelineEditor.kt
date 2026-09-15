package com.vedito.app.core.caption

import com.vedito.app.core.model.CaptionSegment
import com.vedito.app.core.text.TextMotion

object CaptionTimelineEditor {
    const val MIN_DURATION_MS = 250
    const val MAX_TEXT_LENGTH = 500

    fun normalized(segment: CaptionSegment, projectDurationMs: Int): CaptionSegment? {
        if (projectDurationMs <= 0) return null
        val text = segment.text.trim().take(MAX_TEXT_LENGTH)
        if (text.isBlank()) return null
        val start = segment.timelineStartMs.coerceIn(0, projectDurationMs)
        val available = (projectDurationMs - start).coerceAtLeast(0)
        if (available < MIN_DURATION_MS) return null
        val duration = segment.durationMs.coerceIn(MIN_DURATION_MS, available)
        return segment.copy(
            text = text,
            timelineStartMs = start,
            durationMs = duration,
            animation = TextMotion.normalize(segment.animation, duration)
        )
    }

    fun normalizeAll(segments: List<CaptionSegment>, projectDurationMs: Int): List<CaptionSegment> =
        segments.mapNotNull { normalized(it, projectDurationMs) }
            .sortedWith(compareBy<CaptionSegment> { it.timelineStartMs }.thenBy { it.id })

    fun move(segment: CaptionSegment, timelineStartMs: Int, projectDurationMs: Int): CaptionSegment {
        val maxStart = (projectDurationMs - segment.durationMs).coerceAtLeast(0)
        return segment.copy(timelineStartMs = timelineStartMs.coerceIn(0, maxStart))
    }

    fun trimLeft(segment: CaptionSegment, requestedStartMs: Int, projectDurationMs: Int): CaptionSegment {
        val oldEnd = segment.timelineEndMs.coerceAtMost(projectDurationMs)
        val latestStart = (oldEnd - MIN_DURATION_MS).coerceAtLeast(0)
        val start = requestedStartMs.coerceIn(0, latestStart)
        return segment.copy(
            timelineStartMs = start,
            durationMs = (oldEnd - start).coerceAtLeast(MIN_DURATION_MS)
        )
    }

    fun trimRight(segment: CaptionSegment, requestedEndMs: Int, projectDurationMs: Int): CaptionSegment {
        val minEnd = segment.timelineStartMs + MIN_DURATION_MS
        val end = requestedEndMs.coerceIn(minEnd, projectDurationMs)
        return segment.copy(durationMs = (end - segment.timelineStartMs).coerceAtLeast(MIN_DURATION_MS))
    }

    fun split(segment: CaptionSegment, positionMs: Int): Pair<CaptionSegment, CaptionSegment>? {
        if (positionMs - segment.timelineStartMs < MIN_DURATION_MS) return null
        if (segment.timelineEndMs - positionMs < MIN_DURATION_MS) return null
        val left = segment.copy(durationMs = positionMs - segment.timelineStartMs)
        val right = segment.copy(
            id = "${segment.id}-split-$positionMs",
            timelineStartMs = positionMs,
            durationMs = segment.timelineEndMs - positionMs
        )
        return left to right
    }

    fun shiftAll(segments: List<CaptionSegment>, deltaMs: Int, projectDurationMs: Int): List<CaptionSegment> {
        if (segments.isEmpty() || deltaMs == 0 || projectDurationMs <= 0) return segments
        val minStart = segments.minOf { it.timelineStartMs }
        val maxEnd = segments.maxOf { it.timelineEndMs }
        val boundedDelta = deltaMs.coerceIn(-minStart, projectDurationMs - maxEnd)
        if (boundedDelta == 0) return segments
        return segments.map { it.copy(timelineStartMs = it.timelineStartMs + boundedDelta) }
    }
}
