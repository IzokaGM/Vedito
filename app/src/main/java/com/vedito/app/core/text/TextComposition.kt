package com.vedito.app.core.text

import com.vedito.app.core.model.TextClip

/**
 * Renderer-independent text layer resolver. Preview and future export must consume
 * the same timing/style/transform state instead of duplicating text timing rules.
 */
data class TextLayer(
    val clipId: String,
    val clip: TextClip,
    val localTimelineMs: Int
)

object TextComposition {
    fun activeLayers(positionMs: Int, clips: List<TextClip>): List<TextLayer> {
        return clips.asSequence()
            .filter { positionMs >= it.timelineStartMs && positionMs < it.timelineEndMs }
            .map { clip ->
                TextLayer(
                    clipId = clip.id,
                    clip = clip,
                    localTimelineMs = (positionMs - clip.timelineStartMs).coerceIn(0, clip.durationMs.coerceAtLeast(1))
                )
            }
            .sortedWith(compareBy<TextLayer> { it.clip.zIndex }.thenBy { it.clip.timelineStartMs })
            .toList()
    }
}
