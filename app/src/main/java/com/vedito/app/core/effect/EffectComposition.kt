package com.vedito.app.core.effect

import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.EffectClip
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.model.TransitionSpec
import com.vedito.app.core.timeline.TimelineMath

/** Renderer-independent effect/transition state shared by preview and future export. */
object EffectComposition {
    data class TransitionFrame(
        val kind: TransitionKind,
        val progress: Float,
        val strength: Float
    )

    fun activeEffects(effects: List<EffectClip>, positionMs: Int): List<EffectClip> =
        effects.filter { positionMs >= it.timelineStartMs && positionMs < it.timelineEndMs }

    fun transitionFrame(clips: List<Clip>, positionMs: Int): TransitionFrame? {
        if (clips.size < 2) return null
        var boundary = 0
        for (index in 0 until clips.lastIndex) {
            boundary += clips[index].durationMs
            val spec = normalizeTransition(clips[index].transitionOut)
            if (spec.kind == TransitionKind.NONE) continue
            val requestedHalf = (spec.durationMs / 2).coerceAtLeast(1)
            val beforeMs = requestedHalf.coerceAtMost(clips[index].durationMs.coerceAtLeast(1))
            val afterMs = requestedHalf.coerceAtMost(clips[index + 1].durationMs.coerceAtLeast(1))
            val start = boundary - beforeMs
            val end = boundary + afterMs
            if (positionMs !in start..end) continue
            val progress = ((positionMs - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
            val strength = if (positionMs <= boundary) {
                ((positionMs - start).toFloat() / (boundary - start).coerceAtLeast(1)).coerceIn(0f, 1f)
            } else {
                ((end - positionMs).toFloat() / (end - boundary).coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            return TransitionFrame(spec.kind, progress, strength)
        }
        return null
    }

    fun normalizeTransition(spec: TransitionSpec): TransitionSpec = spec.copy(
        durationMs = spec.durationMs.coerceIn(TransitionSpec.MIN_DURATION_MS, TransitionSpec.MAX_DURATION_MS)
    )

    fun transitionBoundaryMs(clips: List<Clip>, clipId: String): Int? {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0 || index >= clips.lastIndex) return null
        return TimelineMath.clipStartMs(clips, clipId) + clips[index].durationMs
    }
}
