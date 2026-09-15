package com.vedito.app.core.timeline

import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.keyframe.KeyframeEngine

object TimelineMath {
    data class Location(
        val clipIndex: Int,
        val clip: Clip,
        val timelineStartMs: Int,
        val offsetMs: Int
    ) {
        val sourcePositionMs: Int
            get() = ClipTimeMap.sourcePositionAtTimelineOffset(clip, offsetMs)
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

    fun sanitized(clips: List<Clip>, assets: List<MediaAsset>): List<Clip> {
        val assetsById = assets.associateBy { it.id }
        return clips.mapNotNull { original ->
            val asset = assetsById[original.assetId] ?: return@mapNotNull null
            val duration = asset.durationMs
            if (duration <= 0) {
                return@mapNotNull original.takeIf { it.sourceDurationMs > 0 }?.let(ClipTimeMap::normalizeTiming)
            }
            val start = original.sourceStartMs.coerceIn(0, duration)
            val end = original.sourceEndMs.coerceIn(start, duration)
            if (end <= start) return@mapNotNull null
            val freezeSource = original.timing.freezeSourceMs.coerceIn(start, (end - 1).coerceAtLeast(start))
            val clip = original.copy(
                sourceStartMs = start,
                sourceEndMs = end,
                timing = original.timing.copy(freezeSourceMs = freezeSource)
            )
            val normalized = ClipTimeMap.normalizeTiming(clip)
                .let { it.copy(keyframes = KeyframeEngine.normalize(it.keyframes, it.durationMs)) }
            if (normalized.timing.mode == ClipPlaybackMode.FREEZE && normalized.durationMs <= 0) null else normalized
        }
    }
}
