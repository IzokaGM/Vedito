package com.vedito.app.core.timeline

import com.vedito.app.core.model.Clip
import java.util.UUID

object TimelineEditor {
    data class Result(
        val clips: List<Clip>,
        val selectedClipId: String?,
        val playheadMs: Int
    )

    fun split(clips: List<Clip>, playheadMs: Int, minimumEdgeMs: Int): Result? {
        val location = TimelineMath.locate(clips, playheadMs) ?: return null
        if (location.offsetMs < minimumEdgeMs || location.clip.durationMs - location.offsetMs < minimumEdgeMs) {
            return null
        }
        val splitSource = location.clip.sourceStartMs + location.offsetMs
        val left = location.clip.copy(id = UUID.randomUUID().toString(), sourceEndMs = splitSource)
        val right = location.clip.copy(id = UUID.randomUUID().toString(), sourceStartMs = splitSource)
        val next = clips.toMutableList().apply {
            removeAt(location.clipIndex)
            add(location.clipIndex, right)
            add(location.clipIndex, left)
        }
        return Result(next, right.id, playheadMs.coerceIn(0, TimelineMath.totalDurationMs(next)))
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
        val max = assetDurationMs.takeIf { it > 1 } ?: Int.MAX_VALUE
        val safeStart = startMs.coerceIn(0, max - 1)
        val safeEnd = endMs.coerceIn(safeStart + 1, max)
        return clips.toMutableList().apply {
            this[index] = this[index].copy(sourceStartMs = safeStart, sourceEndMs = safeEnd)
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
        return Result(
            clips = next,
            selectedClipId = copy.id,
            playheadMs = TimelineMath.clipStartMs(next, copy.id)
        )
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
}
