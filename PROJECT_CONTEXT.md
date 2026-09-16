# Vedito — Canonical Project Context

> Read this file first before changing Vedito. It is the canonical handoff for another AI/developer. For visual/UI work, also read `BRAND.md`.

## Product goal
Vedito is a premium native Android video editor targeting CapCut-class breadth, reliability and performance over iterative releases. It must have its own identity and must not copy CapCut/Cutrim proprietary source/assets.

## Locked identity
- Brand/app: **Vedito**
- Android package/applicationId: **`com.vedito.app`**
- Android first
- Current patch: **0.28.6 / versionCode 34**
- Approved logo direction: **V + play + cut-frame** mark, paired with the `Vedito` wordmark.
- Approved tagline: **Shape the cut.**
- Visual direction: premium dark base with bright **functional multi-accent** color; do not reduce the product to a single violet/purple brand color.
- Signature brand motion/color may use cyan → electric blue → indigo as a gradient, while editor categories keep semantic accents (video blue, text purple, audio green, effects amber, export cyan).

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

## Branding foundation (Patch 28A)
- Resource-driven brand tokens live in `res/values/colors.xml`, `dimens.xml` and `styles.xml`.
- `vedito_brand_mark.png` is the approved icon-only production asset used by Home, launcher and splash.
- Android 12+ splash uses the Vedito adaptive launcher icon on the deep-dark brand background.
- Legacy `vedito_accent` remains only as a compatibility alias to brand cyan; new UI should use semantic brand/function tokens.
- This patch intentionally establishes branding, not the full Home/Editor/Export redesign.


## Home UI overhaul (Patch 28B)
- Home follows the approved mockup hierarchy: branded header, hero import CTA, quick dashboard cards, two-column recent-project grid, Featured Templates preview rail and fixed bottom navigation shell.
- Recent cards use the first source asset for asynchronous video-frame thumbnails; failures fall back to the branded card surface rather than blocking Home.
- New Project and Assets use the real system video picker. Draft/Edit open the latest real project. Export routes through the latest editor because export configuration remains editor-owned in this patch.
- Templates are explicitly preview-only and Profile explicitly reports that it is not implemented; do not present these as completed product features until their real data/actions exist.
- Home redesign changes no project schema, renderer behavior or export recovery fingerprint.

## Editor UI overhaul (Patch 28C)
- Editor layout is now structured as top bar → preview → transport → timeline stage → tool dock → context drawer.
- The old full-page vertical control dump is removed. Advanced controls still exist, but only the active category is exposed in the context drawer.
- Main video timeline stays visible. Text/Audio/Effects/Overlay/Captions share one contextual auxiliary timeline lane selected by the tool dock.
- Tool modes are UI-only state and do not change project schema, canonical timing, preview math or export behavior.
- Text/overlay/caption taps in preview automatically reveal the matching tool mode.
- Semantic accents remain category-owned rather than forcing all editor controls into one brand color.

## Editor visual correction (Patch 28C.1)
- Edit mode no longer exposes transform, mask/chroma and tracking controls as stacked raw button rows. It now uses a compact `Clip / Transform / Mask / Track` subtool switcher with one row visible at a time.
- `Clip` is the default edit subtool so common actions (add/split/duplicate/replace/undo/redo/delete) appear first; advanced controls stay available on demand.
- Timeline height is content-driven: the main video lane is compact, while the auxiliary lane only consumes space for Text/Audio/Effects/Overlay/Captions modes.
- Tool dock height, labels and selected treatment are reduced to recover preview space and remove the heavy outlined/debug-control feel.
- Mask/tracking labels are user-facing (`Size`, `Point`, arrows) instead of internal shorthand such as `M ←` or `Anchor`.
- This patch is UI-only: project schema remains v20 and renderer/export/recovery behavior is unchanged.

## Editor density cleanup (Patch 28C.2)
- Editor opens in a clean preview/timeline/tool-dock state; the context drawer is collapsed by default and opens only when a tool is tapped or a preview object explicitly selects its matching tool. Tapping the active tool again collapses the drawer.
- The video timeline no longer sits inside a large bordered card. Metadata is reduced to the source/clip name, the scrubber is slimmer, and technical scale/rotation/opacity/speed text is removed from the main editor chrome.
- Undo/redo move out of the Clip action row into persistent compact transport controls, while Clip actions become icon-first (`Add / Split / Copy / Replace / Delete`).
- Preview frame, tool dock and context drawer drop unnecessary strokes/rounded containers so separation comes from spacing and surface contrast rather than nested boxes.
- The context drawer still collapses with tool selection; **Patch 28C.3 supersedes the old auxiliary-lane collapse behavior** by keeping Video/Audio/Text timeline lanes persistent. Schema/render/export/recovery behavior remains unchanged.

## Persistent multi-track timeline (Patch 28C.3)
- The editor timeline is now structurally multi-track rather than showing only the main video lane. **Video + Audio + Text are persistent lanes** in the default editor chrome.
- Empty Audio/Text lanes remain useful affordances (`+ Add audio`, `+ Add text`) and invoke the real existing add flows; they are not decorative placeholders.
- Overlay, Captions and Effects lanes appear automatically when their canonical project collections contain content.
- A compact synced time ruler and one shared visual playhead span every visible lane. Individual lane playheads are suppressed so the stacked tracks read as one timeline.
- Timeline zoom/viewport remain owned by the main `TimelineScrubberView`; every secondary lane, ruler and shared playhead consume the same zoom/start state.
- Selecting an Audio/Text/Overlay/Caption/Effect block opens its matching context tool while preserving the canonical project model and edit behavior. Schema remains v20; renderer/export/recovery semantics are unchanged.

## Editor layout recovery (Patch 28C.3.1)
- Patch 28C.3 exposed an Android measurement regression: a `match_parent` shared-playhead overlay inside a `wrap_content` timeline frame could consume the editor's remaining vertical space and collapse the preview/tool dock on device.
- Patch 28C.3.1 bounds the persistent timeline to a **136dp viewport**. The ruler stays fixed; the track stack scrolls vertically inside the viewport when optional lanes exceed the base Video/Audio/Text set.
- The shared playhead overlays only the bounded timeline viewport, so it cannot influence the editor's remaining-height allocation.
- The intended shell is restored to top bar → preview → transport → compact multi-track timeline → tool dock → optional context drawer.
- This is layout-only: project schema stays v20 and render/export/recovery semantics remain unchanged.

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
- `AudioAsset` / `AudioClip`: independent overlapping audio timeline with trim, volume, mute, fades, MUSIC/VOICE/SFX role, stereo pan and configurable music ducking amount.
- `OverlayAsset` / `OverlayClip`: independent timed image/video PIP layers with transform and z-order.
- `OverlayComposition`: renderer-independent active-layer resolver shared concept for preview now and deterministic export later.
- `TextClip`: timed free-form text layer with `TextStyle`, `TextTransform`, local transform `TransformKeyframeSet`, reusable `TextPreset`, `TextAnimationSpec` and z-order.
- `TextFontFamily`: renderer-independent font family key (Sans/Serif/Mono/Rounded); Android preview maps keys to platform typefaces while export must map the same keys independently.
- `TextMotion`: deterministic renderer-independent None/Fade/Pop/Slide-Up animation evaluator shared concept for preview/export.
- `TextComposition`: renderer-independent active text-layer resolver for preview/export reuse.
- `CaptionSegment`: dedicated subtitle cue with project timing, caption-safe style preset, font family key and deterministic text animation state.
- `CaptionComposition` / `CaptionTimelineEditor` / `SrtCodec`: renderer-independent caption resolution, timing edits and SRT interchange.
- `EffectClip`: independent timed effect segment with kind/intensity.
- `EffectComposition`: renderer-independent active-effect and clip-boundary transition envelope resolver.
- `ColorGradeSpec`: base color matrix controls plus independent Master/R/G/B five-anchor curves, global HSL state and built-in LUT-look state.
- `ColorGradeEngine`: canonical normalize/matrix/curve/HSL/LUT evaluator shared by preview/export/software fallback.
- `Project`: video/audio/overlay/text/caption/effect state, canvas, playhead, selections and zoom/viewport.

Important modules:
- `core/projects/ProjectRepository.kt` — persistence, schema v20.
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
- `core/audio/*` + `feature/editor/audio/AudioTimelineView.kt` — audio timeline, role/pan/ducking preview and edit foundation.
- `core/overlay/OverlayComposition.kt`, `OverlayTimelineEditor.kt` — renderer-independent PIP layer resolution and timing edits.
- `feature/editor/overlay/OverlayTimelineView.kt`, `OverlayPreviewController.kt` — overlay editing/preview implementation.
- `core/text/TextComposition.kt`, `TextTimelineEditor.kt`, `TextPresetCatalog.kt`, `TextMotion.kt`, `TextKeyframeEngine.kt` — text timing/layer/preset/animation plus transform-keyframe rules.
- `feature/editor/text/TextTimelineView.kt`, `TextToolbarView.kt`, `TextPreviewController.kt` — native manual text editing/preview.
- `core/caption/*` + `feature/editor/caption/*` — subtitle segments, SRT import/export, caption timeline and preview.
- `core/effect/EffectTimelineEditor.kt`, `EffectComposition.kt` — timed effect editing plus renderer-independent effect/transition state.
- `feature/editor/effect/*` — FX timeline, controls and native preview composition.
- `feature/editor/EditorActivity.kt` — current editor orchestration.
- `core/export/ExportPlan.kt` / `ExportSupport.kt` — Android-free export sizing/capability contract.
- `core/export/AudioMixPlan.kt` — renderer-independent source-video/audio-track timing, volume, mute, fade, role, pan and automatic ducking contract for preview/export parity.
- `core/export/RenderPerformancePlan.kt` — Android-free streaming/random frame-access policy plus bounded GPU post-process plan.
- `feature/export/StreamingVideoFrameDecoder.kt` / `VideoFrameSourcePool.kt` — bounded MediaCodec streaming frame acquisition with automatic random-access fallback.
- `feature/export/SoftwareFrameComposer.kt` — hybrid base/overlay plane compositor consuming canonical project/composition state.
- `feature/export/CodecInputSurface.kt` — EGL/GLES post-effect + overlay compositor directly on the video encoder input Surface.
- `feature/export/PcmMediaDecoder.kt` / `OfflineAudioMixer.kt` — export audio decode, normalized PCM cache, overlap mixing and forward-speed time-stretch foundation.
- `feature/export/VideoExportEngine.kt` / `Mp4MuxSink.kt` — AVC/HEVC + AAC MediaCodec/MediaMuxer export pipeline, progress/cancel/error lifecycle.
- `core/export/GpuSourceGraphPlan.kt` — Android-free main-source crop/transform/chroma/color/mask GPU execution plan.
- `core/export/ReverseDecodeCachePlan.kt` — Android-free reverse cache memory/timing policy.
- `feature/export/ReverseVideoFrameDecoder.kt` — previous-sync forward decode + bounded reverse frame cache with fallback at pool layer.
- `core/export/ExportRecoveryPlan.kt` — Android-free checkpoint segmentation, load class, working-space budget and render fingerprint contract.
- `feature/export/ExportRecoveryStore.kt` / `ExportEnvironmentProbe.kt` — cache-backed checkpoint metadata plus resume-aware storage/memory/thermal guardrails.
- `feature/export/Mp4SegmentMerger.kt` — no-reencode final MP4 assembly from video checkpoints + one full AAC checkpoint.

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
- Patch 22: 720p/1080p/1440p/4K export profiles, 24/30/60fps, H.264/HEVC codec selection, deterministic bitrate/file-size planning, MediaCodec size/rate/surface preflight, exact encoder selection and high-resolution main-source decode support.
- Patch 23: encoder-surface GPU main-source graph for transform/chroma/color/mask, retained decoder leases, and bounded MediaCodec reverse GOP-tail cache with random-access fallback.
- Patch 24: frame-boundary video checkpoints, resumable cache-backed export sessions, single full-length AAC checkpoint, no-reencode final remux, resume-aware storage preflight, thermal checkpoint backoff and per-segment decoder/GPU reacquisition.
- Patch 25: schema v19 advanced color state with five-anchor RGB curves, global HSL, built-in LUT looks/intensity, API 33+ unified RuntimeShader preview, GLES export parity, CPU fallback parity and recovery fingerprint invalidation.
- Patch 26: schema v20 audio role/pan/voice-priority ducking for independent audio tracks plus text transform keyframes with shared easing, trim-safe timing, preview/export parity and recovery fingerprint invalidation.
- Patch 27: foreground export service + persistent task reconnect, notification cancel/progress, process/lifecycle resume and Android timeout-safe checkpoint preservation; schema remains v20.

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
- Studio-grade reverse source audio remains pending; reverse video now has the Patch 23 bounded MediaCodec cache foundation.
- Freeze duration UI beyond default insertion.
- Voice-over recording, NR/voice enhancement, pitch/voice effects, beat detection/markers and studio-grade time stretch remain pending. Patch 26 adds deterministic role-based music ducking for independent audio tracks.
- Auto captions, per-word/karaoke timing, TTS, custom downloaded font packs and keyframed text style/animation parameters remain pending. Patch 26 adds transform keyframes for text.
- Manual graph curve editor, color wheels, selective/per-band HSL, external `.cube` LUT import and dual-source cross-dissolve remain pending; Patch 25 already provides canonical curves/global HSL/built-in LUT preview-export parity.
- Overlay/PIP mask/chroma application, advanced/freeform masks and mask keyframes. Main-clip rectangle/ellipse masks + chroma foundation exist in Patch 16.
- Automatic detector/optical-flow tracking, overlay attachment tracking and production gyro/flow stabilization. Manual main-clip tracking/stabilization foundation exists in Patch 17.
- Zero-copy OES/SurfaceTexture decoder-to-GPU path remains pending. Patch 27 now owns export in a foreground service; WorkManager/vendor-specific fallback is still pending and Android may still clear cached checkpoints.
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

## Patch 22 behavior/limits
- `ExportSettings` now selects 720p/1080p/1440p/2160p, 24/30/60fps and `ExportVideoCodec` AVC/HEVC.
- `ExportPlanner` is Android-free and canonical for output dimensions, frame count, bitrate and conservative estimated output size.
- `ExportCapabilityProbe` checks Android MediaCodec encoder MIME, surface-input color format, exact size and size+rate support, prefers hardware acceleration and clamps bitrate to the encoder-advertised range.
- `VideoExportEngine` repeats preflight immediately before export and creates the chosen codec by name; unsupported profiles fail before encoding instead of relying on a late configure crash.
- 4K main-source decoding can reach 3840px; overlays/static media remain bounded around 2560px to avoid multiplying memory pressure.
- 4K/60 is capability-driven, not guaranteed. Preflight estimates output size but cannot prove free space for every SAF/cloud destination. Thermal throttling and long-project memory pressure remain possible.
- Project schema remains v18; export choices are not persisted into project JSON.

## Patch 23 behavior/limits
- `GpuSourceGraphPlanner` is the Android-free canonical execution plan for main-source crop/fit/transform, opacity, chroma, color matrix and mask geometry.
- `CodecInputSurface` evaluates that source graph in GLES, then supported timed effects/transitions, then mask visibility, then the reusable overlay/text/caption plane.
- `HybridComposedFrame` can retain a raw decoded source bitmap and its `FrameLease`; the export engine closes the lease immediately after the encoder draw.
- `VideoFrameSourcePool` pins streaming entries while leased, so overlay decoder churn cannot recycle the active main-source frame before upload.
- Reverse clips prefer `ReverseVideoFrameDecoder`: seek to previous sync → decode forward → retain a memory-bounded tail → serve descending reverse timestamps from cache. Unsupported device paths fall back to `MediaMetadataRetriever`.
- `ReverseDecodeCachePlanner` bounds cache depth by decoded dimensions/FPS and a default memory budget; 4K therefore keeps far fewer frames than 1080p.
- Grain still forces the CPU correctness path. Video overlays/text/captions still rasterize into the CPU overlay plane.
- Decoder YUV→RGB conversion still occurs on CPU before source texture upload; a future OES/SurfaceTexture path may remove that final conversion without changing canonical render state.
- Project schema remains v18; Patch 23 introduces no new saved project state.

## Patch 24 recovery behavior/limits
- `ExportRecoveryPlanner` splits video on exact frame boundaries into ~18s standard, ~12s heavy or ~8s extreme checkpoints and includes a render-ABI salt in its deterministic recovery fingerprint.
- Recovery fingerprint depends on render-relevant project state, effective export plan and exact encoder; playhead/selection/autosave timestamps do not invalidate a valid render.
- `ExportRecoveryStore` persists finalized video checkpoints + one full AAC checkpoint under app cache. `.part` files are never resumed and stale sessions are pruned after seven days.
- `VideoExportEngine` validates cached tracks before reuse, recreates compositor/decoder/GPU resources for every video segment, and preserves completed checkpoints on cancel/recoverable failure.
- Audio is mixed/encoded once for the full project. Final delivery is a no-reencode `Mp4SegmentMerger` remux, avoiding AAC priming at each video boundary.
- Storage preflight includes cache working budget, resume-aware reusable bytes and measurable destination free space; destination is checked again before final assembly.
- Severe thermal state backs off at safe segment boundaries; critical thermal pressure exits with completed checkpoints preserved.
- Recovery cache is not durable storage and may be cleared by Android. Patch 27 later adds foreground-service ownership and process redelivery; an abrupt kill can still lose only the in-flight `.part` segment while finalized checkpoints remain reusable.
- Project schema remains v18; Patch 24 adds no saved editor state.

## Patch 25 behavior/limits
- Canonical color order is base matrix → Master/R/G/B five-anchor curves → global HSL → built-in LUT look.
- The toolbar currently cycles deterministic curve presets; the stored model already supports independent curve anchors for a future graph editor.
- Built-in LUT looks are deterministic/procedural and require no external asset dependency. External `.cube` import is still pending.
- API 33+ preview uses one RuntimeShader for chroma + the full color pipeline; API 31–32 retain legacy matrix-only preview while export still renders all saved advanced state.
- Encoder GLES and software fallback both consume the same canonical `ColorGradeSpec`.
- Project persistence is schema v19. Older projects without advanced fields load neutral defaults.
- Export recovery render salt is bumped to `vedito-render-p25-r1`, invalidating stale Patch 24 checkpoints after the pixel pipeline change.

## Patch 26 behavior/limits
- Independent audio clips persist `AudioRole` (`MUSIC`, `VOICE`, `SFX`), stereo `pan` and `duckingAmount`; older projects load as MUSIC, centered, with 55% ducking ready but only applied when an audible VOICE clip overlaps.
- Automatic ducking is deterministic and voice-priority: only independent MUSIC clips are attenuated around overlapping independent VOICE clips, with fixed 180 ms attack and 360 ms release. Source-video audio does not automatically become a duck trigger.
- Preview `AudioPlaybackEngine` and export `AudioMixPlan`/`OfflineAudioMixer` apply the same role/pan/ducking state. Center pan preserves legacy left/right gain; pan attenuates only the opposite channel.
- Text clips now persist local transform keyframes for scale, position X/Y, rotation and opacity using the existing `TransformKeyframeSet` / `KeyframeEasing` model.
- `TextKeyframeEngine` adapts the canonical `KeyframeEngine`; preview and software export evaluate the same interpolation. Toolbar controls add/remove/navigate keyframes and cycle easing.
- Text left/right trims preserve animation timing at the surviving boundary, including an interpolated boundary keyframe when trimming inward. Timeline keyframe markers are visible on the selected text clip.
- Project persistence is schema v20. Missing Patch 26 fields from older projects normalize to safe defaults.
- Export recovery render salt is `vedito-render-p26-r1`, invalidating Patch 25 checkpoints after audio/text render semantics changed.
- Patch 26 does not add voice-over recording, NR/voice enhancement, pitch shifting, beat detection, auto captions, karaoke/per-word captions, TTS, custom font downloads or keyframed text style properties.

## Patch 27 behavior/limits
- `ExportForegroundService` owns long exports; closing/recreating `EditorActivity` no longer cancels the render.
- Android 15+ foreground execution uses `mediaProcessing`; API 29–34 uses `dataSync` for compatible local file processing/export. Manifest declares the matching foreground-service permissions.
- `ExportTaskStore` persists one active task's project id, output URI, export settings, progress and terminal state so editor UI can reconnect after lifecycle/process events.
- `START_REDELIVER_INTENT` lets Android recreate the service with the export request. `VideoExportEngine` then reopens the Patch 24 recovery fingerprint and reuses finalized video/AAC checkpoints.
- Notification progress has Cancel and deep-links back to the owning project. Terminal completion/failure/cancel/timeout remains visible after the foreground service detaches.
- Android 15+ `onTimeout(startId, fgsType)` cancels in-flight codec work, preserves finalized checkpoints, records a resumable timeout state and stops promptly to avoid ANR.
- Export no longer holds `keepScreenOn`; screen-off/background execution is expected. Force-stop, cache eviction, revoked SAF permission or OS/vendor kill can still interrupt work; finalized checkpoints remain best-effort cache state only.
- Patch 27 does not change render semantics, project JSON or export fingerprint: schema remains v20 and recovery salt remains `vedito-render-p26-r1`.

## Next milestone
**Patch 28D — Export UI Overhaul** following the approved mockup direction, after Patch 28C.2 is green on device.

See `WORKPLAN.md` for the full roadmap.
