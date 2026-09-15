package com.vedito.app.core.effect

import com.vedito.app.core.model.EffectClip
import kotlin.math.max

object EffectTimelineEditor {
    const val MIN_DURATION_MS = 250
    const val DEFAULT_DURATION_MS = 2_000

    fun normalize(effect: EffectClip, projectDurationMs: Int): EffectClip? {
        if (projectDurationMs <= 0) return null
        val start = effect.timelineStartMs.coerceIn(0, projectDurationMs)
        val available = (projectDurationMs - start).coerceAtLeast(0)
        if (available < MIN_DURATION_MS) return null
        val duration = effect.durationMs.coerceIn(MIN_DURATION_MS, available)
        return effect.copy(
            timelineStartMs = start,
            durationMs = duration,
            intensity = effect.intensity.coerceIn(0f, 1f)
        )
    }

    fun normalizeAll(effects: List<EffectClip>, projectDurationMs: Int): List<EffectClip> =
        effects.mapNotNull { normalize(it, projectDurationMs) }
            .sortedWith(compareBy<EffectClip> { it.timelineStartMs }.thenBy { it.id })

    fun move(effect: EffectClip, timelineStartMs: Int, projectDurationMs: Int): EffectClip {
        val maxStart = max(0, projectDurationMs - effect.durationMs)
        return effect.copy(timelineStartMs = timelineStartMs.coerceIn(0, maxStart))
    }

    fun trimLeft(effect: EffectClip, requestedStartMs: Int, projectDurationMs: Int): EffectClip {
        val oldEnd = effect.timelineEndMs.coerceAtMost(projectDurationMs)
        val latestStart = (oldEnd - MIN_DURATION_MS).coerceAtLeast(0)
        val start = requestedStartMs.coerceIn(0, latestStart)
        return effect.copy(
            timelineStartMs = start,
            durationMs = (oldEnd - start).coerceAtLeast(MIN_DURATION_MS)
        )
    }

    fun trimRight(effect: EffectClip, requestedEndMs: Int, projectDurationMs: Int): EffectClip {
        val minEnd = effect.timelineStartMs + MIN_DURATION_MS
        val end = requestedEndMs.coerceIn(minEnd, projectDurationMs)
        return effect.copy(durationMs = (end - effect.timelineStartMs).coerceAtLeast(MIN_DURATION_MS))
    }
}
