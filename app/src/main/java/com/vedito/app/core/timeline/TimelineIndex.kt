package com.vedito.app.core.timeline

import com.vedito.app.core.model.Clip

/** Immutable prefix-sum index used by playback and the custom timeline view. */
class TimelineIndex(private val clips: List<Clip>) {
    private val starts = IntArray(clips.size)
    val totalDurationMs: Int

    init {
        var cursor = 0
        clips.forEachIndexed { index, clip ->
            starts[index] = cursor
            cursor += clip.durationMs
        }
        totalDurationMs = cursor
    }

    data class Location(
        val clipIndex: Int,
        val clip: Clip,
        val timelineStartMs: Int,
        val offsetMs: Int
    ) {
        val sourcePositionMs: Int get() = clip.sourceStartMs + offsetMs
    }

    fun locate(positionMs: Int): Location? {
        if (clips.isEmpty()) return null
        val target = positionMs.coerceIn(0, totalDurationMs)
        var low = 0
        var high = clips.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = starts[mid]
            val end = start + clips[mid].durationMs
            val isLast = mid == clips.lastIndex
            when {
                target < start -> high = mid - 1
                target >= end && !isLast -> low = mid + 1
                else -> return Location(mid, clips[mid], start, (target - start).coerceIn(0, clips[mid].durationMs))
            }
        }
        val last = clips.last()
        return Location(clips.lastIndex, last, starts.last(), last.durationMs)
    }

    fun startOf(clipId: String): Int {
        val index = clips.indexOfFirst { it.id == clipId }
        return if (index >= 0) starts[index] else 0
    }

    fun edgeTimes(): IntArray {
        if (clips.isEmpty()) return intArrayOf(0)
        val result = IntArray(clips.size + 1)
        starts.copyInto(result, endIndex = starts.size)
        result[result.lastIndex] = totalDurationMs
        return result
    }
}
