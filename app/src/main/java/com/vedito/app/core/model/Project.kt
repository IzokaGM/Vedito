package com.vedito.app.core.model

import kotlin.math.roundToInt

enum class ClipFitMode {
    FIT,
    FILL
}

data class ClipTransform(
    val scale: Float = 1f,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val opacity: Float = 1f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
    val fitMode: ClipFitMode = ClipFitMode.FIT
)

enum class ClipPlaybackMode {
    FORWARD,
    REVERSE,
    FREEZE
}

/**
 * Renderer-independent timing state. The future speed-curve engine should extend
 * this model rather than placing timing logic inside UI/player code.
 */
data class ClipTiming(
    val speed: Float = 1f,
    val mode: ClipPlaybackMode = ClipPlaybackMode.FORWARD,
    val freezeSourceMs: Int = 0,
    val freezeDurationMs: Int = DEFAULT_FREEZE_DURATION_MS
) {
    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val DEFAULT_FREEZE_DURATION_MS = 2_000
        const val MIN_FREEZE_DURATION_MS = 250
        const val MAX_FREEZE_DURATION_MS = 10_000
    }
}

enum class CanvasAspect(val label: String, val widthUnits: Int, val heightUnits: Int) {
    SOURCE("Source", 0, 0),
    VERTICAL_9_16("9:16", 9, 16),
    LANDSCAPE_16_9("16:9", 16, 9),
    SQUARE_1_1("1:1", 1, 1),
    PORTRAIT_4_5("4:5", 4, 5);

    fun fixedRatioOrNull(): Float? = if (widthUnits > 0 && heightUnits > 0) {
        widthUnits.toFloat() / heightUnits.toFloat()
    } else {
        null
    }
}

enum class CanvasBackground(val label: String, val argb: Int) {
    BLACK("Black", 0xFF000000.toInt()),
    CHARCOAL("Charcoal", 0xFF171A23.toInt()),
    WHITE("White", 0xFFFFFFFF.toInt()),
    VIOLET("Violet", 0xFF241E45.toInt())
}

data class CanvasSettings(
    val aspect: CanvasAspect = CanvasAspect.SOURCE,
    val background: CanvasBackground = CanvasBackground.BLACK
)

data class MediaAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Int = 0,
    val frameRate: Float = DEFAULT_FRAME_RATE,
    val width: Int = 0,
    val height: Int = 0
) {
    companion object {
        const val DEFAULT_FRAME_RATE = 30f
    }
}

data class Clip(
    val id: String,
    val assetId: String,
    val sourceStartMs: Int,
    val sourceEndMs: Int,
    val transform: ClipTransform = ClipTransform(),
    val timing: ClipTiming = ClipTiming()
) {
    val sourceDurationMs: Int
        get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0)

    val durationMs: Int
        get() = when (timing.mode) {
            ClipPlaybackMode.FREEZE -> timing.freezeDurationMs
                .coerceIn(ClipTiming.MIN_FREEZE_DURATION_MS, ClipTiming.MAX_FREEZE_DURATION_MS)
            ClipPlaybackMode.FORWARD,
            ClipPlaybackMode.REVERSE -> {
                val speed = timing.speed.coerceIn(ClipTiming.MIN_SPEED, ClipTiming.MAX_SPEED)
                (sourceDurationMs / speed).roundToInt().coerceAtLeast(1)
            }
        }
}



enum class OverlayMediaType {
    IMAGE,
    VIDEO
}

data class OverlayAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val type: OverlayMediaType,
    val durationMs: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)

data class OverlayClip(
    val id: String,
    val assetId: String,
    val timelineStartMs: Int,
    val durationMs: Int,
    val sourceStartMs: Int = 0,
    val zIndex: Int = 0,
    val transform: ClipTransform = ClipTransform(scale = 0.45f, fitMode = ClipFitMode.FIT)
) {
    val timelineEndMs: Int
        get() = timelineStartMs + durationMs
}

enum class TextAlignment {
    LEFT,
    CENTER,
    RIGHT
}

data class TextStyle(
    val fontSizeSp: Float = 32f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundColorArgb: Int = 0x00000000,
    val bold: Boolean = true,
    val alignment: TextAlignment = TextAlignment.CENTER
)

data class TextTransform(
    val scale: Float = 1f,
    val positionX: Float = 0f,
    val positionY: Float = 0.55f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f
)

data class TextClip(
    val id: String,
    val text: String,
    val timelineStartMs: Int,
    val durationMs: Int,
    val zIndex: Int = 0,
    val style: TextStyle = TextStyle(),
    val transform: TextTransform = TextTransform()
) {
    val timelineEndMs: Int
        get() = timelineStartMs + durationMs
}



enum class CaptionPreset {
    BOXED,
    CLEAN,
    LARGE
}

data class CaptionSegment(
    val id: String,
    val text: String,
    val timelineStartMs: Int,
    val durationMs: Int,
    val preset: CaptionPreset = CaptionPreset.BOXED
) {
    val timelineEndMs: Int
        get() = timelineStartMs + durationMs
}

data class AudioAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Int = 0
)

data class AudioClip(
    val id: String,
    val assetId: String,
    val timelineStartMs: Int,
    val sourceStartMs: Int,
    val sourceEndMs: Int,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0
) {
    val durationMs: Int
        get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0)

    val timelineEndMs: Int
        get() = timelineStartMs + durationMs
}

data class Project(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val assets: List<MediaAsset> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val audioAssets: List<AudioAsset> = emptyList(),
    val audioClips: List<AudioClip> = emptyList(),
    val overlayAssets: List<OverlayAsset> = emptyList(),
    val overlayClips: List<OverlayClip> = emptyList(),
    val textClips: List<TextClip> = emptyList(),
    val captionSegments: List<CaptionSegment> = emptyList(),
    val canvasSettings: CanvasSettings = CanvasSettings(),
    val playheadMs: Int = 0,
    val selectedClipId: String? = null,
    val selectedAudioClipId: String? = null,
    val selectedOverlayClipId: String? = null,
    val selectedTextClipId: String? = null,
    val selectedCaptionSegmentId: String? = null,
    val timelineZoom: Float = 1f,
    val timelineViewportStartMs: Int = 0
) {
    fun asset(id: String): MediaAsset? = assets.firstOrNull { it.id == id }
    fun audioAsset(id: String): AudioAsset? = audioAssets.firstOrNull { it.id == id }
    fun overlayAsset(id: String): OverlayAsset? = overlayAssets.firstOrNull { it.id == id }
}
