package com.vedito.app.core.overlay

import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayClip
import com.vedito.app.core.model.OverlayMediaType
import com.vedito.app.core.keyframe.KeyframeEngine
import kotlin.math.max

object OverlayTimelineEditor {
    const val MIN_DURATION_MS = 250

    fun normalized(clip: OverlayClip, asset: OverlayAsset, projectDurationMs: Int): OverlayClip? {
        if (projectDurationMs <= 0) return null
        val start = clip.timelineStartMs.coerceIn(0, projectDurationMs)
        val maxDurationByTimeline = (projectDurationMs - start).coerceAtLeast(0)
        if (maxDurationByTimeline < MIN_DURATION_MS) return null

        val sourceStart = if (asset.type == OverlayMediaType.VIDEO) {
            clip.sourceStartMs.coerceIn(0, (asset.durationMs - MIN_DURATION_MS).coerceAtLeast(0))
        } else 0
        val maxDurationBySource = if (asset.type == OverlayMediaType.VIDEO) {
            (asset.durationMs - sourceStart).coerceAtLeast(0)
        } else maxDurationByTimeline
        val maxDuration = minOf(maxDurationByTimeline, maxDurationBySource)
        if (maxDuration < MIN_DURATION_MS) return null
        val duration = clip.durationMs
            .coerceAtLeast(MIN_DURATION_MS)
            .coerceAtMost(maxDuration)

        if (duration <= 0) return null
        return clip.copy(
            timelineStartMs = start,
            durationMs = duration,
            sourceStartMs = sourceStart,
            zIndex = clip.zIndex.coerceAtLeast(0),
            keyframes = KeyframeEngine.normalize(clip.keyframes, duration)
        )
    }

    fun move(clip: OverlayClip, timelineStartMs: Int, projectDurationMs: Int): OverlayClip {
        val maxStart = (projectDurationMs - clip.durationMs).coerceAtLeast(0)
        return clip.copy(timelineStartMs = timelineStartMs.coerceIn(0, maxStart))
    }

    fun trimLeft(
        clip: OverlayClip,
        asset: OverlayAsset,
        requestedStartMs: Int,
        projectDurationMs: Int
    ): OverlayClip {
        val oldEnd = clip.timelineEndMs
        val latestStart = oldEnd - MIN_DURATION_MS
        val start = requestedStartMs.coerceIn(0, latestStart.coerceAtLeast(0))
        val delta = start - clip.timelineStartMs
        val sourceStart = if (asset.type == OverlayMediaType.VIDEO) {
            max(0, clip.sourceStartMs + delta)
        } else 0
        val duration = oldEnd - start
        val shiftedKeyframes = KeyframeEngine.shiftForLeftTrim(
            clip.keyframes,
            removedMs = (start - clip.timelineStartMs).coerceAtLeast(0),
            newDurationMs = duration
        )
        return normalized(
            clip.copy(
                timelineStartMs = start,
                durationMs = duration,
                sourceStartMs = sourceStart,
                keyframes = shiftedKeyframes
            ),
            asset,
            projectDurationMs
        ) ?: clip
    }

    fun trimRight(
        clip: OverlayClip,
        asset: OverlayAsset,
        requestedEndMs: Int,
        projectDurationMs: Int
    ): OverlayClip {
        val minEnd = clip.timelineStartMs + MIN_DURATION_MS
        var end = requestedEndMs.coerceIn(minEnd, projectDurationMs)
        if (asset.type == OverlayMediaType.VIDEO) {
            val maxEnd = clip.timelineStartMs + (asset.durationMs - clip.sourceStartMs).coerceAtLeast(MIN_DURATION_MS)
            end = end.coerceAtMost(maxEnd)
        }
        val duration = (end - clip.timelineStartMs).coerceAtLeast(MIN_DURATION_MS)
        return clip.copy(
            durationMs = duration,
            keyframes = KeyframeEngine.normalize(clip.keyframes, duration)
        )
    }

    fun raise(clips: List<OverlayClip>, id: String): List<OverlayClip> {
        val ordered = clips.sortedWith(compareBy<OverlayClip> { it.zIndex }.thenBy { it.id })
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0 || index == ordered.lastIndex) return clips
        val other = ordered[index + 1]
        val target = ordered[index]
        return clips.map { clip ->
            when (clip.id) {
                target.id -> clip.copy(zIndex = other.zIndex)
                other.id -> clip.copy(zIndex = target.zIndex)
                else -> clip
            }
        }
    }

    fun lower(clips: List<OverlayClip>, id: String): List<OverlayClip> {
        val ordered = clips.sortedWith(compareBy<OverlayClip> { it.zIndex }.thenBy { it.id })
        val index = ordered.indexOfFirst { it.id == id }
        if (index <= 0) return clips
        val other = ordered[index - 1]
        val target = ordered[index]
        return clips.map { clip ->
            when (clip.id) {
                target.id -> clip.copy(zIndex = other.zIndex)
                other.id -> clip.copy(zIndex = target.zIndex)
                else -> clip
            }
        }
    }
}
