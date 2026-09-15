package com.vedito.app.core.compositor

import com.vedito.app.core.color.ColorGradeEngine
import com.vedito.app.core.effect.EffectComposition
import com.vedito.app.core.keyframe.KeyframeEngine
import com.vedito.app.core.model.ChromaKeySpec
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.ColorGradeSpec
import com.vedito.app.core.model.EffectClip
import com.vedito.app.core.model.MaskShape
import com.vedito.app.core.model.MaskSpec
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.timeline.ClipTimeMap
import com.vedito.app.core.timeline.TimelineMath
import com.vedito.app.core.tracking.MotionTrackingEngine

/**
 * Deterministic frame plan shared by preview now and the future off-screen GPU export compositor.
 * This class owns no Android/GPU APIs; it only resolves canonical render state for one timeline time.
 */
data class FrameComposition(
    val clipId: String,
    val clipIndex: Int,
    val localTimeMs: Int,
    val sourcePositionMs: Int,
    val transform: ClipTransform,
    val mask: MaskSpec,
    val chromaKey: ChromaKeySpec,
    val colorGrade: ColorGradeSpec,
    val activeEffects: List<EffectClip>,
    val transition: EffectComposition.TransitionFrame?,
    val stages: List<RenderStage>
)

enum class RenderStage {
    SOURCE,
    TRANSFORM,
    MASK,
    CHROMA_KEY,
    COLOR_GRADE,
    TIMED_EFFECTS,
    TRANSITION
}

object FrameCompositionBuilder {
    fun build(clips: List<Clip>, effects: List<EffectClip>, timelinePositionMs: Int): FrameComposition? {
        val location = TimelineMath.locate(clips, timelinePositionMs) ?: return null
        val clip = location.clip
        val local = location.offsetMs.coerceIn(0, clip.durationMs)
        val evaluated = KeyframeEngine.evaluate(clip.transform, clip.keyframes, local, clip.durationMs)
        val transformed = MotionTrackingEngine.applyStabilization(
            base = evaluated,
            track = clip.motionTrack,
            stabilization = clip.stabilization,
            localTimeMs = local,
            durationMs = clip.durationMs
        )
        val activeEffects = EffectComposition.activeEffects(effects, timelinePositionMs)
        val transition = EffectComposition.transitionFrame(clips, timelinePositionMs)
        val stages = buildList {
            add(RenderStage.SOURCE)
            add(RenderStage.TRANSFORM)
            if (clip.mask.shape != MaskShape.NONE) add(RenderStage.MASK)
            if (clip.chromaKey.enabled) add(RenderStage.CHROMA_KEY)
            if (!ColorGradeEngine.isNeutral(clip.colorGrade)) add(RenderStage.COLOR_GRADE)
            if (activeEffects.isNotEmpty()) add(RenderStage.TIMED_EFFECTS)
            if (transition != null && transition.kind != TransitionKind.NONE) add(RenderStage.TRANSITION)
        }
        return FrameComposition(
            clipId = clip.id,
            clipIndex = location.clipIndex,
            localTimeMs = local,
            sourcePositionMs = ClipTimeMap.sourcePositionAtTimelineOffset(clip, local),
            transform = transformed,
            mask = clip.mask,
            chromaKey = clip.chromaKey,
            colorGrade = ColorGradeEngine.normalize(clip.colorGrade),
            activeEffects = activeEffects,
            transition = transition,
            stages = stages
        )
    }
}
