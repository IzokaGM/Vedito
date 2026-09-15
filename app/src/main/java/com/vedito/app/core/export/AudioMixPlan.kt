package com.vedito.app.core.export

import com.vedito.app.core.model.AudioRole
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.Project
import com.vedito.app.core.timeline.TimelineIndex
import kotlin.math.min

/** Renderer-independent description of every audible timeline layer used by preview/export parity. */
enum class AudioMixSegmentKind {
    SOURCE_VIDEO,
    AUDIO_TRACK
}

data class AudioDuckWindow(
    val startMs: Int,
    val endMs: Int
)

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
    val fadeOutMs: Int = 0,
    val role: AudioRole = AudioRole.MUSIC,
    val pan: Float = 0f,
    val duckingAmount: Float = 0f,
    val duckWindows: List<AudioDuckWindow> = emptyList()
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
                speed = clip.timing.speed.coerceIn(0.5f, 2f),
                role = AudioRole.SFX
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
                fadeOutMs = clip.fadeOutMs.coerceAtLeast(0),
                role = clip.role,
                pan = clip.pan.coerceIn(-1f, 1f),
                duckingAmount = clip.duckingAmount.coerceIn(0f, 0.9f)
            )
        }

        val voiceWindows = mergeWindows(
            segments.asSequence()
                .filter { it.role == AudioRole.VOICE && !it.muted && it.volume > 0f }
                .map { AudioDuckWindow(it.timelineStartMs, it.timelineEndMs) }
                .toList()
        )
        val withDucking = segments.map { segment ->
            if (segment.role != AudioRole.MUSIC || segment.duckingAmount <= 0f) segment
            else segment.copy(
                duckWindows = voiceWindows.mapNotNull { window ->
                    val start = maxOf(segment.timelineStartMs - AudioMixMath.DUCK_ATTACK_MS, window.startMs - AudioMixMath.DUCK_ATTACK_MS)
                    val end = minOf(segment.timelineEndMs + AudioMixMath.DUCK_RELEASE_MS, window.endMs + AudioMixMath.DUCK_RELEASE_MS)
                    if (end > start) AudioDuckWindow(start, end) else null
                }
            )
        }
        return AudioMixPlan(duration, withDucking.sortedBy { it.timelineStartMs })
    }

    private fun mergeWindows(input: List<AudioDuckWindow>): List<AudioDuckWindow> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedBy { it.startMs }
        val merged = mutableListOf<AudioDuckWindow>()
        sorted.forEach { window ->
            val last = merged.lastOrNull()
            if (last == null || window.startMs > last.endMs) {
                merged += window
            } else {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, window.endMs))
            }
        }
        return merged
    }
}

object AudioMixMath {
    const val DUCK_ATTACK_MS = 180
    const val DUCK_RELEASE_MS = 360

    fun gainAt(segment: AudioMixSegment, timelinePositionMs: Float): Float {
        if (segment.muted || segment.volume <= 0f) return 0f
        if (timelinePositionMs < segment.timelineStartMs || timelinePositionMs >= segment.timelineEndMs) return 0f
        val local = (timelinePositionMs - segment.timelineStartMs).coerceAtLeast(0f)
        val remaining = (segment.timelineEndMs - timelinePositionMs).coerceAtLeast(0f)
        val fadeIn = if (segment.fadeInMs > 0) (local / segment.fadeInMs).coerceIn(0f, 1f) else 1f
        val fadeOut = if (segment.fadeOutMs > 0) (remaining / segment.fadeOutMs).coerceIn(0f, 1f) else 1f
        val duck = duckMultiplier(segment, timelinePositionMs)
        return (segment.volume.coerceIn(0f, 1f) * minOf(fadeIn, fadeOut) * duck).coerceIn(0f, 1f)
    }

    fun stereoGains(segment: AudioMixSegment): Pair<Float, Float> {
        val pan = segment.pan.coerceIn(-1f, 1f)
        return if (pan < 0f) 1f to (1f + pan) else (1f - pan) to 1f
    }

    fun directSourcePositionMs(segment: AudioMixSegment, timelinePositionMs: Float): Float {
        val local = (timelinePositionMs - segment.timelineStartMs).coerceIn(0f, segment.timelineDurationMs.toFloat())
        return (segment.sourceStartMs + local * segment.speed)
            .coerceIn(segment.sourceStartMs.toFloat(), segment.sourceEndMs.toFloat())
    }

    private fun duckMultiplier(segment: AudioMixSegment, timelineMs: Float): Float {
        if (segment.role != AudioRole.MUSIC || segment.duckingAmount <= 0f || segment.duckWindows.isEmpty()) return 1f
        var strongest = 0f
        segment.duckWindows.forEach { window ->
            val coreStart = window.startMs + DUCK_ATTACK_MS
            val coreEnd = window.endMs - DUCK_RELEASE_MS
            val strength = when {
                timelineMs < window.startMs || timelineMs > window.endMs -> 0f
                timelineMs < coreStart -> ((timelineMs - window.startMs) / DUCK_ATTACK_MS).coerceIn(0f, 1f)
                timelineMs <= coreEnd -> 1f
                else -> ((window.endMs - timelineMs) / DUCK_RELEASE_MS).coerceIn(0f, 1f)
            }
            strongest = maxOf(strongest, strength)
        }
        return (1f - segment.duckingAmount.coerceIn(0f, 0.9f) * strongest).coerceIn(0.1f, 1f)
    }
}
