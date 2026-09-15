package com.vedito.app.core.timeline

import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.ClipTiming
import java.util.UUID

object TimelineEditor {
    data class Result(
        val clips: List<Clip>,
        val selectedClipId: String?,
        val playheadMs: Int
    )

    fun split(clips: List<Clip>, playheadMs: Int, minimumEdgeMs: Int): Result? {
        val location = TimelineMath.locate(clips, playheadMs) ?: return null
        if (location.offsetMs < minimumEdgeMs || location.clip.durationMs - location.offsetMs < minimumEdgeMs) return null

        val original = location.clip
        val pair = if (original.timing.mode == ClipPlaybackMode.FREEZE) {
            val left = original.copy(
                id = UUID.randomUUID().toString(),
                timing = original.timing.copy(freezeDurationMs = location.offsetMs)
            )
            val right = original.copy(
                id = UUID.randomUUID().toString(),
                timing = original.timing.copy(freezeDurationMs = original.durationMs - location.offsetMs)
            )
            left to right
        } else {
            val splitSource = ClipTimeMap.sourcePositionAtTimelineOffset(original, location.offsetMs)
                .coerceIn(original.sourceStartMs + 1, original.sourceEndMs - 1)
            if (original.timing.mode == ClipPlaybackMode.REVERSE) {
                original.copy(
                    id = UUID.randomUUID().toString(),
                    sourceStartMs = splitSource,
                    sourceEndMs = original.sourceEndMs
                ) to original.copy(
                    id = UUID.randomUUID().toString(),
                    sourceStartMs = original.sourceStartMs,
                    sourceEndMs = splitSource
                )
            } else {
                original.copy(
                    id = UUID.randomUUID().toString(),
                    sourceEndMs = splitSource
                ) to original.copy(
                    id = UUID.randomUUID().toString(),
                    sourceStartMs = splitSource
                )
            }
        }

        val next = clips.toMutableList().apply {
            removeAt(location.clipIndex)
            add(location.clipIndex, pair.second)
            add(location.clipIndex, pair.first)
        }
        return Result(next, pair.second.id, playheadMs.coerceIn(0, TimelineMath.totalDurationMs(next)))
    }

    fun delete(clips: List<Clip>, selectedId: String?, playheadMs: Int): Result? {
        if (clips.size <= 1 || selectedId == null) return null
        val index = clips.indexOfFirst { it.id == selectedId }
        if (index < 0) return null
        val removedStart = TimelineMath.clipStartMs(clips, selectedId)
        val next = clips.toMutableList().apply { removeAt(index) }
        return Result(
            clips = next,
            selectedClipId = next.getOrNull(index)?.id ?: next.lastOrNull()?.id,
            playheadMs = minOf(playheadMs, removedStart).coerceIn(0, TimelineMath.totalDurationMs(next))
        )
    }

    fun trim(clips: List<Clip>, clipId: String, startMs: Int, endMs: Int, assetDurationMs: Int): List<Clip> {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return clips
        val current = clips[index]
        if (current.timing.mode == ClipPlaybackMode.FREEZE) return clips
        val max = assetDurationMs.takeIf { it > 1 } ?: Int.MAX_VALUE
        val safeStart = startMs.coerceIn(0, max - 1)
        val safeEnd = endMs.coerceIn(safeStart + 1, max)
        return clips.toMutableList().apply {
            this[index] = current.copy(sourceStartMs = safeStart, sourceEndMs = safeEnd)
        }
    }

    fun insertAfter(clips: List<Clip>, selectedId: String?, additions: List<Clip>): List<Clip> {
        if (additions.isEmpty()) return clips
        val insertIndex = clips.indexOfFirst { it.id == selectedId }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: clips.size
        return clips.toMutableList().apply { addAll(insertIndex, additions) }
    }

    fun duplicate(clips: List<Clip>, selectedId: String?): Result? {
        val index = clips.indexOfFirst { it.id == selectedId }
        if (index < 0) return null
        val copy = clips[index].copy(id = UUID.randomUUID().toString())
        val next = clips.toMutableList().apply { add(index + 1, copy) }
        return Result(next, copy.id, TimelineMath.clipStartMs(next, copy.id))
    }

    fun reorder(clips: List<Clip>, clipId: String, targetIndex: Int): List<Clip> {
        val from = clips.indexOfFirst { it.id == clipId }
        if (from < 0 || clips.size < 2) return clips
        val to = targetIndex.coerceIn(0, clips.lastIndex)
        if (from == to) return clips
        val next = clips.toMutableList()
        val clip = next.removeAt(from)
        next.add(to.coerceIn(0, next.size), clip)
        return next
    }

    fun setSpeed(clips: List<Clip>, clipId: String, speed: Float): List<Clip> {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return clips
        val clip = clips[index]
        if (clip.timing.mode == ClipPlaybackMode.FREEZE) return clips
        val safe = speed.coerceIn(ClipTiming.MIN_SPEED, ClipTiming.MAX_SPEED)
        if (clip.timing.speed == safe) return clips
        return clips.toMutableList().apply {
            this[index] = clip.copy(timing = clip.timing.copy(speed = safe))
        }
    }

    fun toggleReverse(clips: List<Clip>, clipId: String): List<Clip> {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return clips
        val clip = clips[index]
        if (clip.timing.mode == ClipPlaybackMode.FREEZE) return clips
        val nextMode = if (clip.timing.mode == ClipPlaybackMode.REVERSE) ClipPlaybackMode.FORWARD else ClipPlaybackMode.REVERSE
        return clips.toMutableList().apply {
            this[index] = clip.copy(timing = clip.timing.copy(mode = nextMode))
        }
    }

    /** Inserts a real timeline hold at the playhead while preserving both surrounding source pieces. */
    fun insertFreeze(clips: List<Clip>, playheadMs: Int, freezeDurationMs: Int = ClipTiming.DEFAULT_FREEZE_DURATION_MS): Result? {
        val location = TimelineMath.locate(clips, playheadMs) ?: return null
        val source = ClipTimeMap.sourcePositionAtTimelineOffset(location.clip, location.offsetMs)
        val safeSource = source.coerceIn(location.clip.sourceStartMs, (location.clip.sourceEndMs - 1).coerceAtLeast(location.clip.sourceStartMs))
        val freeze = location.clip.copy(
            id = UUID.randomUUID().toString(),
            timing = ClipTiming(
                speed = 1f,
                mode = ClipPlaybackMode.FREEZE,
                freezeSourceMs = safeSource,
                freezeDurationMs = freezeDurationMs.coerceIn(ClipTiming.MIN_FREEZE_DURATION_MS, ClipTiming.MAX_FREEZE_DURATION_MS)
            )
        )

        val edgeTolerance = 2
        val next = clips.toMutableList()
        val insertIndex: Int
        when {
            location.offsetMs <= edgeTolerance -> {
                insertIndex = location.clipIndex
                next.add(insertIndex, freeze)
            }
            location.clip.durationMs - location.offsetMs <= edgeTolerance -> {
                insertIndex = location.clipIndex + 1
                next.add(insertIndex, freeze)
            }
            location.clip.timing.mode == ClipPlaybackMode.FREEZE -> {
                insertIndex = location.clipIndex + 1
                next.add(insertIndex, freeze)
            }
            else -> {
                val split = split(clips, playheadMs, 1) ?: return null
                val rightIndex = split.clips.indexOfFirst { it.id == split.selectedClipId }
                if (rightIndex < 0) return null
                next.clear()
                next.addAll(split.clips)
                insertIndex = rightIndex
                next.add(insertIndex, freeze)
            }
        }
        return Result(next, freeze.id, TimelineMath.clipStartMs(next, freeze.id))
    }
}
