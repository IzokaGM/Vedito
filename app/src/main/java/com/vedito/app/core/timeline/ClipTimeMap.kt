package com.vedito.app.core.timeline

import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.ClipTiming
import kotlin.math.roundToInt

/** Shared source<->timeline time mapping for preview now and export later. */
object ClipTimeMap {
    fun normalizedSpeed(clip: Clip): Float = clip.timing.speed.coerceIn(ClipTiming.MIN_SPEED, ClipTiming.MAX_SPEED)

    fun sourcePositionAtTimelineOffset(clip: Clip, offsetMs: Int): Int {
        if (clip.sourceEndMs <= clip.sourceStartMs) return clip.sourceStartMs
        return when (clip.timing.mode) {
            ClipPlaybackMode.FREEZE -> clip.timing.freezeSourceMs
                .coerceIn(clip.sourceStartMs, (clip.sourceEndMs - 1).coerceAtLeast(clip.sourceStartMs))
            ClipPlaybackMode.FORWARD -> {
                val sourceOffset = (offsetMs.coerceIn(0, clip.durationMs) * normalizedSpeed(clip)).roundToInt()
                (clip.sourceStartMs + sourceOffset)
                    .coerceIn(clip.sourceStartMs, clip.sourceEndMs)
            }
            ClipPlaybackMode.REVERSE -> {
                val sourceOffset = (offsetMs.coerceIn(0, clip.durationMs) * normalizedSpeed(clip)).roundToInt()
                (clip.sourceEndMs - sourceOffset)
                    .coerceIn(clip.sourceStartMs, clip.sourceEndMs)
            }
        }
    }

    fun timelineOffsetForSourcePosition(clip: Clip, sourcePositionMs: Int): Int {
        if (clip.timing.mode == ClipPlaybackMode.FREEZE) return 0
        val speed = normalizedSpeed(clip)
        val sourceOffset = when (clip.timing.mode) {
            ClipPlaybackMode.FORWARD -> sourcePositionMs - clip.sourceStartMs
            ClipPlaybackMode.REVERSE -> clip.sourceEndMs - sourcePositionMs
            ClipPlaybackMode.FREEZE -> 0
        }.coerceIn(0, clip.sourceDurationMs)
        return (sourceOffset / speed).roundToInt().coerceIn(0, clip.durationMs)
    }

    fun normalizeTiming(clip: Clip): Clip {
        val safeSpeed = clip.timing.speed.coerceIn(ClipTiming.MIN_SPEED, ClipTiming.MAX_SPEED)
        val safeFreeze = clip.timing.freezeSourceMs.coerceIn(
            clip.sourceStartMs,
            (clip.sourceEndMs - 1).coerceAtLeast(clip.sourceStartMs)
        )
        val safeDuration = clip.timing.freezeDurationMs.coerceIn(
            ClipTiming.MIN_FREEZE_DURATION_MS,
            ClipTiming.MAX_FREEZE_DURATION_MS
        )
        return clip.copy(timing = clip.timing.copy(
            speed = safeSpeed,
            freezeSourceMs = safeFreeze,
            freezeDurationMs = safeDuration
        ))
    }
}
