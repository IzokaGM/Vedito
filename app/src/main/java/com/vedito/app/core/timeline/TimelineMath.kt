package com.vedito.app.core.timeline

import com.vedito.app.core.model.Clip

object TimelineMath {
    data class Location(
        val clipIndex: Int,
        val clip: Clip,
        val timelineStartMs: Int,
        val offsetMs: Int
    ) {
        val sourcePositionMs: Int
            get() = clip.sourceStartMs + offsetMs
    }

    fun totalDurationMs(clips: List<Clip>): Int = clips.sumOf { it.durationMs }

    fun locate(clips: List<Clip>, timelinePositionMs: Int): Location? {
        if (clips.isEmpty()) return null
        val total = totalDurationMs(clips)
        val target = timelinePositionMs.coerceIn(0, total)
        var cursor = 0

        clips.forEachIndexed { index, clip ->
            val end = cursor + clip.durationMs
            val isLast = index == clips.lastIndex
            if (target < end || isLast) {
                return Location(
                    clipIndex = index,
                    clip = clip,
                    timelineStartMs = cursor,
                    offsetMs = (target - cursor).coerceIn(0, clip.durationMs)
                )
            }
            cursor = end
        }
        return null
    }

    fun clipStartMs(clips: List<Clip>, clipId: String): Int {
        var cursor = 0
        clips.forEach { clip ->
            if (clip.id == clipId) return cursor
            cursor += clip.durationMs
        }
        return 0
    }

    fun sanitized(clips: List<Clip>, sourceDurationMs: Int): List<Clip> {
        if (sourceDurationMs <= 0) return clips.filter { it.durationMs > 0 }
        return clips.mapNotNull { clip ->
            val start = clip.sourceStartMs.coerceIn(0, sourceDurationMs)
            val end = clip.sourceEndMs.coerceIn(start, sourceDurationMs)
            if (end > start) clip.copy(sourceStartMs = start, sourceEndMs = end) else null
        }
    }
}
