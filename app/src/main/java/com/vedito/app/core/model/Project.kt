package com.vedito.app.core.model

data class MediaAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Int = 0,
    val frameRate: Float = DEFAULT_FRAME_RATE
) {
    companion object {
        const val DEFAULT_FRAME_RATE = 30f
    }
}

data class Clip(
    val id: String,
    val assetId: String,
    val sourceStartMs: Int,
    val sourceEndMs: Int
) {
    val durationMs: Int
        get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0)
}

data class Project(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val assets: List<MediaAsset> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val playheadMs: Int = 0,
    val selectedClipId: String? = null,
    val timelineZoom: Float = 1f,
    val timelineViewportStartMs: Int = 0
) {
    fun asset(id: String): MediaAsset? = assets.firstOrNull { it.id == id }
}
