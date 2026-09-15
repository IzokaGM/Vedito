package com.vedito.app.core.caption

import com.vedito.app.core.model.CaptionSegment

data class CaptionLayer(
    val segmentId: String,
    val segment: CaptionSegment,
    val localTimelineMs: Int
)

object CaptionComposition {
    fun activeLayers(positionMs: Int, segments: List<CaptionSegment>): List<CaptionLayer> =
        segments.asSequence()
            .filter { positionMs >= it.timelineStartMs && positionMs < it.timelineEndMs }
            .map { segment ->
                CaptionLayer(
                    segmentId = segment.id,
                    segment = segment,
                    localTimelineMs = (positionMs - segment.timelineStartMs).coerceAtLeast(0)
                )
            }
            .sortedBy { it.segment.timelineStartMs }
            .toList()
}
