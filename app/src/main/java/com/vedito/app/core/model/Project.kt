package com.vedito.app.core.model

data class Clip(
    val id: String,
    val sourceStartMs: Int,
    val sourceEndMs: Int
) {
    val durationMs: Int
        get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0)
}

data class Project(
    val id: String,
    val title: String,
    val sourceUri: String,
    val updatedAt: Long,
    val sourceDurationMs: Int = 0,
    val clips: List<Clip> = emptyList(),
    val playheadMs: Int = 0,
    val selectedClipId: String? = null
)
