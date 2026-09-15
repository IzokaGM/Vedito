package com.vedito.app.core.model

import kotlin.math.roundToInt



enum class KeyframeEasing {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    HOLD
}

data class FloatKeyframe(
    val timeMs: Int,
    val value: Float,
    val easing: KeyframeEasing = KeyframeEasing.LINEAR
)

data class TransformKeyframeSet(
    val scale: List<FloatKeyframe> = emptyList(),
    val positionX: List<FloatKeyframe> = emptyList(),
    val positionY: List<FloatKeyframe> = emptyList(),
    val rotationDegrees: List<FloatKeyframe> = emptyList(),
    val opacity: List<FloatKeyframe> = emptyList()
) {
    val isEmpty: Boolean
        get() = scale.isEmpty() && positionX.isEmpty() && positionY.isEmpty() &&
            rotationDegrees.isEmpty() && opacity.isEmpty()

    val pointCount: Int
        get() = sequenceOf(scale, positionX, positionY, rotationDegrees, opacity)
            .flatten()
            .map { it.timeMs }
            .distinct()
            .count()
}

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

enum class TransitionKind {
    NONE,
    FADE_BLACK,
    FLASH_WHITE,
    WIPE
}

data class TransitionSpec(
    val kind: TransitionKind = TransitionKind.NONE,
    val durationMs: Int = DEFAULT_DURATION_MS
) {
    companion object {
        const val MIN_DURATION_MS = 200
        const val DEFAULT_DURATION_MS = 500
        const val MAX_DURATION_MS = 1_500
    }
}



enum class MaskShape {
    NONE,
    RECTANGLE,
    ELLIPSE
}

data class MaskSpec(
    val shape: MaskShape = MaskShape.NONE,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val width: Float = 0.78f,
    val height: Float = 0.78f,
    val feather: Float = 0f,
    val inverted: Boolean = false
)

data class ChromaKeySpec(
    val enabled: Boolean = false,
    val keyColorArgb: Int = 0xFF00FF00.toInt(),
    val tolerance: Float = 0.22f,
    val softness: Float = 0.10f,
    val spill: Float = 0.15f
)


data class ToneCurveSpec(
    val black: Float = 0f,
    val shadows: Float = 0.25f,
    val midtones: Float = 0.50f,
    val highlights: Float = 0.75f,
    val white: Float = 1f
)

data class RgbCurveSpec(
    val master: ToneCurveSpec = ToneCurveSpec(),
    val red: ToneCurveSpec = ToneCurveSpec(),
    val green: ToneCurveSpec = ToneCurveSpec(),
    val blue: ToneCurveSpec = ToneCurveSpec()
)

data class HslAdjustSpec(
    val hueDegrees: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f
)

enum class ColorLutPreset {
    NONE,
    CINEMATIC,
    TEAL_ORANGE,
    FILM_FADE,
    CLEAN_POP
}

data class ColorLutSpec(
    val preset: ColorLutPreset = ColorLutPreset.NONE,
    val intensity: Float = 1f
)

data class ColorGradeSpec(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val curves: RgbCurveSpec = RgbCurveSpec(),
    val hsl: HslAdjustSpec = HslAdjustSpec(),
    val lut: ColorLutSpec = ColorLutSpec()
)


enum class TrackingPointSource {
    MANUAL,
    ASSISTED
}

data class TrackingPoint(
    val timeMs: Int,
    val x: Float,
    val y: Float,
    val confidence: Float = 1f,
    val source: TrackingPointSource = TrackingPointSource.MANUAL
)

data class MotionTrackSpec(
    val enabled: Boolean = false,
    val points: List<TrackingPoint> = emptyList()
)

data class StabilizationSpec(
    val enabled: Boolean = false,
    val strength: Float = 0.65f,
    val autoCrop: Boolean = true
)

data class Clip(
    val id: String,
    val assetId: String,
    val sourceStartMs: Int,
    val sourceEndMs: Int,
    val transform: ClipTransform = ClipTransform(),
    val keyframes: TransformKeyframeSet = TransformKeyframeSet(),
    val timing: ClipTiming = ClipTiming(),
    val transitionOut: TransitionSpec = TransitionSpec(),
    val mask: MaskSpec = MaskSpec(),
    val chromaKey: ChromaKeySpec = ChromaKeySpec(),
    val colorGrade: ColorGradeSpec = ColorGradeSpec(),
    val motionTrack: MotionTrackSpec = MotionTrackSpec(),
    val stabilization: StabilizationSpec = StabilizationSpec()
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
    val transform: ClipTransform = ClipTransform(scale = 0.45f, fitMode = ClipFitMode.FIT),
    val keyframes: TransformKeyframeSet = TransformKeyframeSet()
) {
    val timelineEndMs: Int
        get() = timelineStartMs + durationMs
}

enum class TextAlignment {
    LEFT,
    CENTER,
    RIGHT
}

enum class TextFontFamily {
    SANS,
    SERIF,
    MONO,
    ROUNDED
}

enum class TextPreset {
    CUSTOM,
    CLASSIC,
    TITLE,
    MINIMAL,
    IMPACT,
    LOWER_THIRD
}

enum class TextAnimationKind {
    NONE,
    FADE,
    POP,
    SLIDE_UP
}

data class TextAnimationSpec(
    val kind: TextAnimationKind = TextAnimationKind.NONE,
    val inDurationMs: Int = 300,
    val outDurationMs: Int = 250
)

data class TextStyle(
    val fontSizeSp: Float = 32f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundColorArgb: Int = 0x00000000,
    val bold: Boolean = true,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val fontFamily: TextFontFamily = TextFontFamily.SANS,
    val letterSpacingEm: Float = 0f,
    val shadowEnabled: Boolean = false
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
    val transform: TextTransform = TextTransform(),
    val keyframes: TransformKeyframeSet = TransformKeyframeSet(),
    val preset: TextPreset = TextPreset.CUSTOM,
    val animation: TextAnimationSpec = TextAnimationSpec()
) {
    val timelineEndMs: Int
        get() = timelineStartMs + durationMs
}



enum class CaptionPreset {
    BOXED,
    CLEAN,
    LARGE,
    YELLOW,
    SOFT
}

data class CaptionSegment(
    val id: String,
    val text: String,
    val timelineStartMs: Int,
    val durationMs: Int,
    val preset: CaptionPreset = CaptionPreset.BOXED,
    val fontFamily: TextFontFamily = TextFontFamily.SANS,
    val animation: TextAnimationSpec = TextAnimationSpec()
) {
    val timelineEndMs: Int
        get() = timelineStartMs + durationMs
}

enum class VideoEffectKind {
    WARM,
    COOL,
    VIGNETTE,
    DREAM,
    GRAIN
}

data class EffectClip(
    val id: String,
    val timelineStartMs: Int,
    val durationMs: Int,
    val kind: VideoEffectKind = VideoEffectKind.WARM,
    val intensity: Float = 0.6f
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

enum class AudioRole {
    MUSIC,
    VOICE,
    SFX
}

data class AudioClip(
    val id: String,
    val assetId: String,
    val timelineStartMs: Int,
    val sourceStartMs: Int,
    val sourceEndMs: Int,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0,
    val role: AudioRole = AudioRole.MUSIC,
    val pan: Float = 0f,
    val duckingAmount: Float = 0.55f
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
    val effectClips: List<EffectClip> = emptyList(),
    val canvasSettings: CanvasSettings = CanvasSettings(),
    val playheadMs: Int = 0,
    val selectedClipId: String? = null,
    val selectedAudioClipId: String? = null,
    val selectedOverlayClipId: String? = null,
    val selectedTextClipId: String? = null,
    val selectedCaptionSegmentId: String? = null,
    val selectedEffectClipId: String? = null,
    val timelineZoom: Float = 1f,
    val timelineViewportStartMs: Int = 0
) {
    fun asset(id: String): MediaAsset? = assets.firstOrNull { it.id == id }
    fun audioAsset(id: String): AudioAsset? = audioAssets.firstOrNull { it.id == id }
    fun overlayAsset(id: String): OverlayAsset? = overlayAssets.firstOrNull { it.id == id }
}
