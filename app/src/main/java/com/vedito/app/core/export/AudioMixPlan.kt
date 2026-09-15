package com.vedito.app.core.export

import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.Project
import com.vedito.app.core.timeline.TimelineIndex
import kotlin.math.min

/** Renderer-independent description of every audible timeline layer used by preview/export parity. */
enum class AudioMixSegmentKind {
    SOURCE_VIDEO,
    AUDIO_TRACK
}

data class AudioMixSegment(
    val id: String,
    val uri: String,
    val kind: AudioMixSegmentKind,
    val timelineStartMs: Int,
    val timelineEndMs: Int,
    val sourceStartMs: Int,
    val sourceEndMs: Int,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0
) {
    val timelineDurationMs: Int get() = (timelineEndMs - timelineStartMs).coerceAtLeast(0)
    val sourceDurationMs: Int get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0)
}

data class AudioMixPlan(
    val durationMs: Int,
    val segments: List<AudioMixSegment>
) {
    val audibleUris: List<String>
        get() = segments.asSequence()
            .filter { !it.muted && it.volume > 0f && it.timelineDurationMs > 0 && it.sourceDurationMs > 0 }
            .map { it.uri }
            .distinct()
            .toList()
}

object AudioMixPlanner {
    fun build(project: Project): AudioMixPlan {
        val timeline = TimelineIndex(project.clips)
        val duration = timeline.totalDurationMs.coerceAtLeast(0)
        val segments = mutableListOf<AudioMixSegment>()

        project.clips.forEach { clip ->
            // PreviewPlayer intentionally mutes source sound for reverse/freeze virtual playback.
            // Export follows the same ownership rule rather than inventing preview-only behavior.
            if (clip.timing.mode != ClipPlaybackMode.FORWARD) return@forEach
            val asset = project.asset(clip.assetId) ?: return@forEach
            val start = timeline.startOf(clip.id)
            val end = (start + clip.durationMs).coerceAtMost(duration)
            if (end <= start || clip.sourceEndMs <= clip.sourceStartMs) return@forEach
            segments += AudioMixSegment(
                id = "video:${clip.id}",
                uri = asset.uri,
                kind = AudioMixSegmentKind.SOURCE_VIDEO,
                timelineStartMs = start,
                timelineEndMs = end,
                sourceStartMs = clip.sourceStartMs,
                sourceEndMs = clip.sourceEndMs,
                speed = clip.timing.speed.coerceIn(0.5f, 2f)
            )
        }

        project.audioClips.forEach { clip ->
            val asset = project.audioAsset(clip.assetId) ?: return@forEach
            val start = clip.timelineStartMs.coerceAtLeast(0)
            val end = min(clip.timelineEndMs, duration)
            if (end <= start || clip.sourceEndMs <= clip.sourceStartMs) return@forEach
            val sourceTrimAtStart = (start - clip.timelineStartMs).coerceAtLeast(0)
            segments += AudioMixSegment(
                id = "audio:${clip.id}",
                uri = asset.uri,
                kind = AudioMixSegmentKind.AUDIO_TRACK,
                timelineStartMs = start,
                timelineEndMs = end,
                sourceStartMs = clip.sourceStartMs + sourceTrimAtStart,
                sourceEndMs = min(clip.sourceEndMs, clip.sourceStartMs + sourceTrimAtStart + (end - start)),
                speed = 1f,
                volume = clip.volume.coerceIn(0f, 1f),
                muted = clip.muted,
                fadeInMs = clip.fadeInMs.coerceAtLeast(0),
                fadeOutMs = clip.fadeOutMs.coerceAtLeast(0)
            )
        }

        return AudioMixPlan(duration, segments.sortedBy { it.timelineStartMs })
    }
}

object AudioMixMath {
    fun gainAt(segment: AudioMixSegment, timelinePositionMs: Float): Float {
        if (segment.muted || segment.volume <= 0f) return 0f
        if (timelinePositionMs < segment.timelineStartMs || timelinePositionMs >= segment.timelineEndMs) return 0f
        val local = (timelinePositionMs - segment.timelineStartMs).coerceAtLeast(0f)
        val remaining = (segment.timelineEndMs - timelinePositionMs).coerceAtLeast(0f)
        val fadeIn = if (segment.fadeInMs > 0) (local / segment.fadeInMs).coerceIn(0f, 1f) else 1f
        val fadeOut = if (segment.fadeOutMs > 0) (remaining / segment.fadeOutMs).coerceIn(0f, 1f) else 1f
        return (segment.volume.coerceIn(0f, 1f) * minOf(fadeIn, fadeOut)).coerceIn(0f, 1f)
    }

    fun directSourcePositionMs(segment: AudioMixSegment, timelinePositionMs: Float): Float {
        val local = (timelinePositionMs - segment.timelineStartMs).coerceIn(0f, segment.timelineDurationMs.toFloat())
        return (segment.sourceStartMs + local * segment.speed)
            .coerceIn(segment.sourceStartMs.toFloat(), segment.sourceEndMs.toFloat())
    }
}
