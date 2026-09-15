package com.vedito.app.core.audio

import com.vedito.app.core.model.AudioClip
import com.vedito.app.core.model.AudioRole
import java.util.UUID

/** Pure audio-timeline mutations so persistence/UI never owns edit math. */
object AudioTimelineEditor {
    fun split(clips: List<AudioClip>, clipId: String, playheadMs: Int, minimumEdgeMs: Int): Pair<List<AudioClip>, String>? {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return null
        val clip = clips[index]
        val local = playheadMs - clip.timelineStartMs
        if (local < minimumEdgeMs || clip.durationMs - local < minimumEdgeMs) return null

        val sourceSplit = clip.sourceStartMs + local
        val left = clip.copy(
            id = UUID.randomUUID().toString(),
            sourceEndMs = sourceSplit,
            fadeOutMs = 0,
            fadeInMs = clip.fadeInMs.coerceAtMost(local)
        )
        val rightDuration = clip.sourceEndMs - sourceSplit
        val right = clip.copy(
            id = UUID.randomUUID().toString(),
            timelineStartMs = playheadMs,
            sourceStartMs = sourceSplit,
            fadeInMs = 0,
            fadeOutMs = clip.fadeOutMs.coerceAtMost(rightDuration)
        )
        val next = clips.toMutableList().apply {
            removeAt(index)
            add(index, right)
            add(index, left)
        }.sortedBy { it.timelineStartMs }
        return next to right.id
    }

    fun normalized(clip: AudioClip, assetDurationMs: Int, projectDurationMs: Int): AudioClip? {
        if (assetDurationMs <= 0 || projectDurationMs <= 0) return null
        if (clip.timelineStartMs >= projectDurationMs || clip.sourceStartMs >= assetDurationMs) return null
        val timelineStart = clip.timelineStartMs.coerceAtLeast(0)
        val sourceStart = clip.sourceStartMs.coerceAtLeast(0)
        val maxDuration = minOf(assetDurationMs - sourceStart, projectDurationMs - timelineStart)
        val duration = clip.durationMs.coerceAtMost(maxDuration)
        if (duration <= 0) return null
        return clip.copy(
            timelineStartMs = timelineStart,
            sourceStartMs = sourceStart,
            sourceEndMs = sourceStart + duration,
            volume = clip.volume.coerceIn(0f, 1f),
            fadeInMs = clip.fadeInMs.coerceIn(0, duration),
            fadeOutMs = clip.fadeOutMs.coerceIn(0, duration),
            pan = clip.pan.coerceIn(-1f, 1f),
            duckingAmount = clip.duckingAmount.coerceIn(0f, 0.9f)
        )
    }


    fun cycleRole(clip: AudioClip): AudioClip {
        val values = AudioRole.values()
        val current = values.indexOf(clip.role).coerceAtLeast(0)
        return clip.copy(role = values[(current + 1) % values.size])
    }

    fun cyclePan(clip: AudioClip): AudioClip {
        val values = listOf(0f, -0.65f, 0.65f)
        val current = values.indices.minByOrNull { kotlin.math.abs(values[it] - clip.pan) } ?: 0
        return clip.copy(pan = values[(current + 1) % values.size])
    }

    fun cycleDucking(clip: AudioClip): AudioClip {
        if (clip.role != AudioRole.MUSIC) return clip.copy(role = AudioRole.MUSIC, duckingAmount = 0.55f)
        val values = listOf(0f, 0.35f, 0.55f, 0.75f)
        val current = values.indices.minByOrNull { kotlin.math.abs(values[it] - clip.duckingAmount) } ?: 0
        return clip.copy(duckingAmount = values[(current + 1) % values.size])
    }

    fun cycleFadeIn(clip: AudioClip): AudioClip = clip.copy(fadeInMs = nextFade(clip.fadeInMs, clip.durationMs))
    fun cycleFadeOut(clip: AudioClip): AudioClip = clip.copy(fadeOutMs = nextFade(clip.fadeOutMs, clip.durationMs))

    private fun nextFade(current: Int, duration: Int): Int {
        val cap = duration / 2
        if (cap <= 0) return 0
        val options = (listOf(0, 500, 1_000, 2_000, 3_000, cap)
            .map { it.coerceAtMost(cap) })
            .distinct()
            .sorted()
        return options.firstOrNull { it > current + 10 } ?: 0
    }
}
