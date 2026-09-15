# Vedito — Canonical Project Context

> Read this file first before changing Vedito. It is the canonical handoff for another AI/developer.

## Product goal
Vedito is a premium native Android video editor targeting CapCut-class breadth, reliability and performance over iterative releases. It must have its own identity and must not copy CapCut/Cutrim proprietary source/assets.

## Locked identity
- Brand/app: **Vedito**
- Android package/applicationId: **`com.vedito.app`**
- Android first
- Current patch: **0.21.0 / versionCode 21**

## Locked technical direction
- Native Android/Kotlin; do not return to React Native unless owner explicitly changes direction.
- Standalone APK: no Metro/Hermes/JS runtime/dev-server dependency.
- minSdk 26, target/compile SDK 37, Java 17.
- XML + ViewBinding + custom native Views currently.
- Preview currently uses native `MediaPlayer` + `TextureView`.
- Heavy deterministic rendering can later use MediaCodec/OpenGL/C++ where justified; avoid giant dependencies prematurely.
- Project persistence is JSON in `SharedPreferences` via `ProjectRepository`; migrations must preserve older projects.

## Delivery rules
Owner works mainly from phone/GitHub Actions.
1. Code patches are ZIPs with repository-root paths.
2. **Never put `.yml`/`.yaml` inside patch ZIP.**
3. Workflow changes are separate files.
4. Prefer focused patches, not unrelated rewrites.
5. A button is not a completed feature unless behavior is real and persistence/export path is considered.
6. Ask confirmation before a new patch unless user already explicitly told you to start.

Current external CI concept:
- `.github/workflows/01-auto-unzip.yml`: unzip uploaded patch ZIP + commit.
- `.github/workflows/02-build-apk.yml`: build after successful unzip workflow.

## Product quality rules
- Engine correctness before feature count.
- Preview/project/export state must converge on the same model.
- Destructive edits should support undo/redo.
- Use real system insets; no hardcoded safe areas.
- Keep dependencies/APK bloat controlled.
- Stress multi-clip/long projects and low/mid-range devices.
- Never copy Cutrim source; it was shown only as an example of simple standalone APK delivery.

## Current core model
- `MediaAsset`: video source metadata.
- `Clip`: source trim + `ClipTransform` + `ClipTiming` + outgoing `TransitionSpec`.
- `ClipTiming`: speed, playback mode (FORWARD/REVERSE/FREEZE), freeze source frame/duration.
- `ClipTimeMap`: canonical source↔timeline time mapping. Future speed curves/export must build on this rather than duplicating timing math in UI.
- `TransformKeyframeSet` / `FloatKeyframe` / `KeyframeEasing`: local-timeline transform animation state for scale/position/rotation/opacity.
- `MotionTrackSpec` / `TrackingPoint`: local-timeline tracking anchors for main clips; future detector assistance must populate the same model.
- `StabilizationSpec`: renderer-independent stabilization enable/strength/auto-crop state.
- `MotionTrackingEngine`: canonical tracking interpolation + trim/split/speed/reverse remapping + deterministic stabilization transform compensation.
- `KeyframeEngine`: canonical interpolation/easing evaluator shared by preview now and future export; keyframes are local to their owning clip/layer.
- `AudioAsset` / `AudioClip`: independent overlapping audio timeline with trim, volume, mute, fades.
- `OverlayAsset` / `OverlayClip`: independent timed image/video PIP layers with transform and z-order.
- `OverlayComposition`: renderer-independent active-layer resolver shared concept for preview now and deterministic export later.
- `TextClip`: timed free-form text layer with `TextStyle`, `TextTransform`, reusable `TextPreset`, `TextAnimationSpec` and z-order.
- `TextFontFamily`: renderer-independent font family key (Sans/Serif/Mono/Rounded); Android preview maps keys to platform typefaces while export must map the same keys independently.
- `TextMotion`: deterministic renderer-independent None/Fade/Pop/Slide-Up animation evaluator shared concept for preview/export.
- `TextComposition`: renderer-independent active text-layer resolver for preview/export reuse.
- `CaptionSegment`: dedicated subtitle cue with project timing, caption-safe style preset, font family key and deterministic text animation state.
- `CaptionComposition` / `CaptionTimelineEditor` / `SrtCodec`: renderer-independent caption resolution, timing edits and SRT interchange.
- `EffectClip`: independent timed effect segment with kind/intensity.
- `EffectComposition`: renderer-independent active-effect and clip-boundary transition envelope resolver.
- `Project`: video/audio/overlay/text/caption/effect state, canvas, playhead, selections and zoom/viewport.

Important modules:
- `core/projects/ProjectRepository.kt` — persistence, schema v18.
- `core/timeline/ClipTimeMap.kt` — timing mapping shared concept for preview/export.
- `core/timeline/TimelineIndex.kt`, `TimelineMath.kt`, `TimelineEditor.kt` — ripple timeline and destructive/timing operations.
- `core/timeline/EditorHistory.kt` — runtime undo/redo snapshots.
- `core/keyframe/KeyframeEngine.kt` — renderer-independent transform keyframe interpolation, easing, timing remap/split helpers.
- `core/tracking/MotionTrackingEngine.kt` — manual/assisted tracking anchors, timing remap and stabilization evaluator shared concept for preview/export.
- `feature/editor/tracking/*` — draggable tracking reticle + tracking/stabilization controls.
- `feature/editor/timeline/TimelineScrubberView.kt` — scrub/zoom/trim/reorder UI, timing-aware trim.
- `feature/editor/player/PreviewPlayer.kt` — forward speed preview plus seek-driven reverse/freeze playback foundation.
- `feature/editor/timing/TimingToolbarView.kt` — speed/freeze/reverse controls.
- `core/visual/VisualTransformMath.kt` / `TransformToolbarView.kt` — visual transforms/canvas.
- `core/audio/*` + `feature/editor/audio/AudioTimelineView.kt` — audio foundation.
- `core/overlay/OverlayComposition.kt`, `OverlayTimelineEditor.kt` — renderer-independent PIP layer resolution and timing edits.
- `feature/editor/overlay/OverlayTimelineView.kt`, `OverlayPreviewController.kt` — overlay editing/preview implementation.
- `core/text/TextComposition.kt`, `TextTimelineEditor.kt`, `TextPresetCatalog.kt`, `TextMotion.kt` — text timing/layer/preset/animation rules.
- `feature/editor/text/TextTimelineView.kt`, `TextToolbarView.kt`, `TextPreviewController.kt` — native manual text editing/preview.
- `core/caption/*` + `feature/editor/caption/*` — subtitle segments, SRT import/export, caption timeline and preview.
- `core/effect/EffectTimelineEditor.kt`, `EffectComposition.kt` — timed effect editing plus renderer-independent effect/transition state.
- `feature/editor/effect/*` — FX timeline, controls and native preview composition.
- `feature/editor/EditorActivity.kt` — current editor orchestration.
- `core/export/ExportPlan.kt` / `ExportSupport.kt` — Android-free export sizing/capability contract.
- `core/export/AudioMixPlan.kt` — renderer-independent source-video/audio-track timing, volume, mute and fade contract for preview/export parity.
- `core/export/RenderPerformancePlan.kt` — Android-free streaming/random frame-access policy plus bounded GPU post-process plan.
- `feature/export/StreamingVideoFrameDecoder.kt` / `VideoFrameSourcePool.kt` — bounded MediaCodec streaming frame acquisition with automatic random-access fallback.
- `feature/export/SoftwareFrameComposer.kt` — hybrid base/overlay plane compositor consuming canonical project/composition state.
- `feature/export/CodecInputSurface.kt` — EGL/GLES post-effect + overlay compositor directly on the video encoder input Surface.
- `feature/export/PcmMediaDecoder.kt` / `OfflineAudioMixer.kt` — export audio decode, normalized PCM cache, overlap mixing and forward-speed time-stretch foundation.
- `feature/export/VideoExportEngine.kt` / `Mp4MuxSink.kt` — H.264/AAC MediaCodec + MediaMuxer export pipeline, progress/cancel/error lifecycle.

## Completed progression
- Native clean rewrite and standalone APK.
- Proper status/navigation insets.
- Video import, real thumbnails, playback/scrub.
- Trim/split/delete, multi-video timeline, add/reorder/ripple.
- Autosave/reopen, undo/redo, zoom, snapping, duplicate/replace.
- Audio import, overlapping lanes, waveform, move/trim/split/fades, extract-audio for normal 1× forward clips.
- Per-clip scale/position/rotate/flip/opacity/crop/fit-fill + project canvas ratio/background.
- Patch 09: timing model, uniform speed, reverse foundation, freeze clips, timing persistence and timing-aware timeline math.
- Patch 10: independent image/video overlay/PIP clips, z-order, drag/trim, shared transforms, synchronized preview composition, undo/redo and persistence.
- Patch 11: timed manual text layers, content editing, text timeline move/trim, text z-order, style/transform controls, renderer-independent text composition, undo/redo and persistence.
- Patch 12: dedicated caption segments, native caption preview/timeline, manual segment editing/split, batch ±0.25s shift, SRT import/export, renderer-independent caption composition, undo/redo and schema v12 persistence.
- Patch 13: reusable free-text presets, shared font-family keys, shadow/letter-spacing style state, deterministic Fade/Pop/Slide-Up text motion, expanded caption presets plus caption font/animation controls, undo/redo and schema v13 persistence.
- Patch 14: timed FX track, real Warm/Cool/Vignette/Dream/Grain native preview overlays, effect intensity, Fade-Black/Flash/Wipe clip-boundary transitions, renderer-independent `EffectComposition`, transition-safe split/duplicate semantics, undo/redo and schema v14 persistence.
- Patch 15: renderer-independent transform keyframes for main clips + overlays, deterministic interpolation/easing, real preview evaluation, keyframe navigation/edit controls, timeline-safe split/trim/speed handling, undo/redo and schema v15 persistence.
- Patch 16: renderer-independent main-clip rectangle/ellipse mask state with size/position/feather/invert, real native occlusion preview, chroma-key state with tolerance/softness/spill, API 33+ RuntimeShader chroma preview, undo/redo and schema v16 persistence.
- Patch 17: renderer-independent main-clip motion tracking anchors + stabilization state, draggable manual reticle workflow, deterministic interpolation/inverse-translation stabilization preview, trim/split/speed/reverse timing preservation, undo/redo and schema v17 persistence.
- Patch 18: renderer-independent per-clip color grade state, deterministic color-matrix math, API 31+ hardware color preview, chroma+color RenderEffect chaining, and Android-free `FrameCompositionBuilder` for preview/export convergence; schema v18 persistence.
- Patch 19: first real off-screen MP4 export foundation. H.264 video is encoded from a deterministic composed frame pipeline that reuses timing/keyframe/stabilization/mask/chroma/color/effect/transition plus overlay/text/caption project state. 720p/1080p @ 30fps presets, AAC container track, progress/cancel/error handling and SAF save flow are integrated.
- Major Patch 20: real audible export mixer. Main-video source sound plus independent audio tracks now render to stereo AAC with timeline sync, volume/mute/fades, multi-track overlap and lightweight pitch-preserving forward-speed handling. Export cancellation/mux/encoder lifecycle is hardened.
- Patch 21: bounded MediaCodec streaming decode for forward/freeze main video and video overlays with random-access fallback, reusable frame/texture storage, hybrid base/overlay composition, and encoder-surface GLES post effects/transitions.

## Patch 15 behavior/limits
- Main video clips and overlay/PIP clips can animate scale, position X/Y, rotation and opacity with local-timeline keyframes.
- Easing presets are LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT and HOLD; no custom Bezier graph editor yet.
- Preview uses `KeyframeEngine.evaluate(...)` directly during scrub/playback. Export must reuse the same model/evaluator.
- Once keyframes exist on the selected clip/layer, scale/move/rotate/opacity edits update/create the keyframe at the current local time.
- Clip split, source trim and uniform speed changes preserve/remap keyframe timing. Freeze insertion bakes the evaluated transform into the new hold clip.
- Overlay left/right trim keeps local keyframe timing bounded to the surviving layer duration.
- Crop, flip and fit/fill are still static transform state in Patch 15.

## Explicitly not completed
- Speed curves UI/easing.
- Production reverse decoder or reversed source audio.
- Freeze duration UI beyond default insertion.
- Voice-over, ducking, NR/voice enhancement, pitch/voice effects, beat markers.
- Auto captions, per-word/karaoke timing, TTS, custom downloaded font packs and advanced/keyframed text animation.
- Final GPU shader/color-grading engine, LUT/HSL/curves and dual-source cross-dissolve.
- Overlay/PIP mask/chroma application, advanced/freeform masks and mask keyframes. Main-clip rectangle/ellipse masks + chroma foundation exist in Patch 16.
- Automatic detector/optical-flow tracking, overlay attachment tracking and production gyro/flow stabilization. Manual main-clip tracking/stabilization foundation exists in Patch 17.
- Full source-texture GPU composition, production reverse decoder/cache and export recovery/resume. Patch 21 now provides streaming forward/freeze decode + hybrid GPU post processing.
- AI/templates/cloud/account/subscription.

## Patch 17 behavior/limits
- Main clips persist `MotionTrackSpec` with local-timeline `TrackingPoint`s plus `StabilizationSpec`.
- `MotionTrackingEngine` is the canonical renderer-independent evaluator for point normalization/interpolation, split/trim/speed/reverse remapping and stabilization transform compensation.
- Manual workflow is real: enable tracking, add/remove anchors, jump between anchors and drag the reticle directly in preview at the current playhead.
- Stabilization preview applies inverse tracked motion with adjustable strength and optional deterministic auto-crop scale. This is a foundation, not final optical-flow/gyro stabilization.
- Future detector assistance must populate the same tracking points; export must consume the same engine/model rather than duplicate timing math.
- Main clips only in Patch 17. Overlay/object attachment tracking remains pending.

## Patch 18 behavior/limits
- Main clips persist `ColorGradeSpec`: exposure, contrast, saturation, temperature, tint and fade.
- `ColorGradeEngine` is canonical for normalization and deterministic 4×5 color matrix generation. Future HSL/curves/LUT work must extend this color domain rather than create preview-only settings.
- `PreviewPlayer` uses one RenderEffect pipeline so color and chroma can coexist; color preview is API 31+, chroma shader remains API 33+.
- `FrameCompositionBuilder` resolves canonical frame state (source time, evaluated/stabilized transform, mask, chroma, color, timed effects, transition and ordered render stages) without Android dependencies. Future off-screen export must consume this resolved state.
- This is compositor architecture + live color foundation, not the final off-screen GPU renderer.

## Patch 19 behavior/limits
- Export became real: SAF destination → off-screen frame composition → H.264 MediaCodec surface encode → AAC + MP4 MediaMuxer.
- Presets: 720p / 1080p at 30 fps with deterministic canvas sizing.
- Main source timing uses the same `ClipTimeMap`/`FrameCompositionBuilder`; visual state is not reimplemented in UI code.
- Patch 19 audio was intentionally silent and is superseded by Major Patch 20.

## Major Patch 20 behavior/limits
- `AudioMixPlan`/`AudioMixMath` are canonical for export audio ownership, timeline position, volume, mute and fades.
- Main forward-video source sound and independent audio clips decode to seekable stereo PCM and mix before AAC encoding.
- Reverse/freeze source sound remains muted to match preview; overlay-video sound remains muted.
- Forward speed-changed source audio uses a lightweight overlap-add stretcher; it is not a studio-grade time-stretch algorithm.
- Audio decode/mix is cancellation-aware with bounded mux startup, monotonic PTS and partial-file cleanup.

## Patch 21 behavior/limits
- `FrameAccessPlanner` is the Android-free execution policy: FORWARD → streaming, FREEZE → held streaming frame, REVERSE → random-access fallback.
- `StreamingVideoFrameDecoder` uses `MediaExtractor + MediaCodec + ImageReader` and reuses one decoded RGB bitmap per stream.
- `VideoFrameSourcePool` limits active hardware stream decoders and automatically falls back to `MediaMetadataRetriever` when a device cannot negotiate the streaming surface.
- `SoftwareFrameComposer` produces reusable base/overlay planes. Supported post effects/transitions are deferred to `CodecInputSurface` and composited in GLES on the encoder surface.
- Warm/Cool/Dream/Vignette + Fade/Flash/Wipe are GPU eligible; Grain intentionally remains CPU fallback in this patch.
- Existing mask/overlay/text/caption ordering is preserved by keeping those elements on the post-effect overlay plane.
- Project persistence schema stays v18; Patch 21 adds execution infrastructure only.
- Chroma/color base processing is still CPU-backed, reverse video remains random-access, and streaming decoder surface support remains device-dependent with automatic fallback.

## Next milestone
**Patch 22 — High-Resolution / Codec Controls & Export Preflight Foundation**
- capability-driven H.265/2K/4K where the device supports it,
- user-facing FPS/bitrate controls with safe presets,
- memory/thermal/storage preflight before long exports,
- preserve the existing `FrameCompositionBuilder` + `AudioMixPlan` + Patch 21 execution contracts.

See `WORKPLAN.md` for the full roadmap.
