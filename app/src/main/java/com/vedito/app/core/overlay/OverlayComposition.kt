package com.vedito.app.core.overlay

import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayClip
import com.vedito.app.core.model.OverlayMediaType
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.keyframe.KeyframeEngine

/**
 * Renderer-independent visual layer description shared by preview now and export later.
 * Do not place Android View/MediaPlayer state in this model.
 */
data class OverlayLayer(
    val clipId: String,
    val asset: OverlayAsset,
    val clip: OverlayClip,
    val localTimelineMs: Int,
    val sourcePositionMs: Int,
    val transform: ClipTransform
)

object OverlayComposition {
    fun activeLayers(
        positionMs: Int,
        assets: List<OverlayAsset>,
        clips: List<OverlayClip>
    ): List<OverlayLayer> {
        val byId = assets.associateBy { it.id }
        return clips.asSequence()
            .filter { positionMs >= it.timelineStartMs && positionMs < it.timelineEndMs }
            .mapNotNull { clip ->
                val asset = byId[clip.assetId] ?: return@mapNotNull null
                val local = (positionMs - clip.timelineStartMs).coerceIn(0, clip.durationMs.coerceAtLeast(1))
                val source = if (asset.type == OverlayMediaType.VIDEO) {
                    (clip.sourceStartMs + local).coerceIn(0, (asset.durationMs - 1).coerceAtLeast(0))
                } else 0
                val transform = KeyframeEngine.evaluate(clip.transform, clip.keyframes, local, clip.durationMs)
                OverlayLayer(clip.id, asset, clip, local, source, transform)
            }
            .sortedWith(compareBy<OverlayLayer> { it.clip.zIndex }.thenBy { it.clip.timelineStartMs })
            .toList()
    }
}
