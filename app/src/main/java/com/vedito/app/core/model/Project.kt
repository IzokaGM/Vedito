package com.vedito.app.core.model

data class MediaAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Int = 0
)

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
    val selectedClipId: String? = null
) {
    fun asset(id: String): MediaAsset? = assets.firstOrNull { it.id == id }
}
