# Vedito — Master Workplan

## North-star
Build a stable premium native Android editor with CapCut-class capability. Every milestone follows:

**BUILD → FUNCTIONAL TEST → STRESS TEST → FIX → LOCK → NEXT**

A feature is complete only when behavior, persistence, preview integration and eventual export architecture agree.

## Stage 0 — Architecture / foundation
**Status: locked foundation; evolve without unnecessary rewrites.**
- Native Android/Kotlin shell, design system, insets.
- Modular project/timeline/player structure.
- Persistent schema/migrations.
- ZIP → unzip → APK GitHub Action flow.

## Stage 1 — Editing timeline engine
**Status: core complete; optimization continuous.**
- Multi-video timeline, thumbnails/playhead.
- Trim/split/delete/duplicate/replace/reorder + ripple.
- Scrub, snapping, zoom/viewport.
- Undo/redo, autosave/reopen.
- 50+ clip stress target.

## Stage 2 — Core visual editing
**Status: core visual base completed through Patch 10; later speed curves still pending.**
Completed:
- Crop/rotate/flip.
- Scale/position/opacity.
- Canvas ratio/background + fit/fill.
- Uniform speed model + preview/timeline mapping.
- Freeze-frame clip insertion.
- Reverse timing semantics + seek-driven preview foundation.

Completed additionally:
- Visual overlay/PIP tracks with timed image/video layers, z-order, transform state and preview composition.

Next:
- Later speed curves built on `ClipTimeMap`.

## Stage 3 — Pro visual editing
**In progress — keyframes, masks/chroma, manual tracking/stabilization and advanced color foundation completed through Patch 18.**
Completed:
- Renderer-independent clip/overlay transform keyframes.
- Linear/Ease In/Ease Out/Ease In-Out/Hold interpolation.
- Real preview evaluation plus timeline-safe split/trim/speed handling.
- Main-clip rectangle/ellipse mask state with feather/invert and native preview.
- Main-clip chroma-key parameter model with API 33+ RuntimeShader preview foundation.
- Main-clip manual motion anchor model + draggable reticle workflow.
- Deterministic tracking interpolation and translation-based stabilization preview with strength/auto-crop state.
- Per-main-clip exposure/contrast/saturation/temperature/tint/fade with deterministic shared color math.
- Android-free frame composition planning for transform/mask/chroma/color/effects/transition preview-export convergence.

Pending:
- Advanced keyframe graph/custom Bezier editor and broader property coverage.
- Overlay/PIP masks, freeform masks and mask keyframes.
- Production shared GPU chroma compositor + eyedropper sampling.
- Automatic detector/optical-flow tracking and overlay attachment tracking.
- Production stabilization and motion blur.
- HSL/curves/wheels/LUT expansion on the Patch 18 color model.

## Stage 4 — Audio
**Advanced audio foundation completed through Patch 26; capture/processing tools still pending.**
Completed:
- Audio import/assets/clips, overlapping preview.
- Waveforms, selection, move/snap/trim/split.
- Volume/mute/delete, fades.
- Extract audio for normal 1× forward clips.
- Persistence + undo/redo.
- Major Patch 20 export parity: source-video sound + overlapping audio clips render to stereo AAC with volume/mute/fades.
- Patch 26 independent audio roles (MUSIC/VOICE/SFX), stereo pan and automatic music ducking under VOICE clips with preview/export parity.

Pending:
- Voice-over recording.
- Noise reduction/voice enhancement.
- Pitch/voice effects.
- Beat detection/markers.
- Retime/reverse audio model for retimed source extraction.

## Stage 5 — Text & captions
**Manual text/caption foundation plus text transform keyframes completed through Patch 26; advanced/AI text remains pending.**
Completed: timed manual text layers, dedicated caption segments, SRT import/export, segment move/trim/split, batch caption shift, reusable text presets, shared font-family keys, caption-safe presets, deterministic basic Fade/Pop/Slide-Up animation, Patch 26 transform keyframes with easing/navigation/trim-safe remap, preview/export composition, persistence + undo/redo.
Pending: custom/downloaded font packs, keyframed text style/animation parameters, auto captions, correction workflow, karaoke/per-word timing, TTS.

## Stage 6 — Effects / transitions
**Foundation through Patch 18; timed effects plus shared GPU-oriented color/composition state exist, advanced shader library still pending.**
Completed:
- Independent timed effect track + move/trim.
- Warm/Cool/Vignette/Dream/Grain native preview effects with intensity.
- Renderer-independent effect/transition composition state.
- Fade-Black/Flash/Wipe clip-boundary transitions with duration control.
- Persistence + undo/redo.

Completed through Patch 25:
- Five-anchor Master/R/G/B curve state, global HSL controls and built-in deterministic LUT looks/intensity.
- API 33+ unified RuntimeShader preview plus encoder GLES and software-fallback export parity.

Pending:
- Manual graph curve editor, color wheels, selective/per-band HSL and external `.cube` LUT import.
- Dual-source cross-dissolve and richer transition library.
- Effect parameter keyframes and downloadable effect packs.

## Stage 7 — Production render/export engine
**In progress — Patch 27 adds foreground/lifecycle-safe export ownership while retaining canonical Patch 24–26 render state.**
Completed through Patch 27:
- Deterministic off-screen compositor consuming canonical project state, now split into reusable base/overlay planes.
- Real H.264 + audible stereo AAC/MP4 output.
- 720p/1080p/1440p/2160p output profiles at 24/30/60fps with AVC/HEVC hardware-first encoder selection and safe bitrate planning.
- Shared visual timing parity for trim/speed/reverse/freeze.
- Main visual/keyframe/stabilization/mask/chroma/color/effects/transition + overlay/text/caption frame composition.
- Source-video audio + overlapping independent audio-track mix.
- Audio-track volume/mute/fade parity.
- Patch 26 audio role/pan/automatic music-ducking parity for independent tracks.
- Patch 26 text transform keyframes share canonical interpolation/easing between preview and software export.
- Forward speed-audio overlap-add stretch foundation; reverse/freeze source audio follows current muted preview policy.
- Progress/cancel/error handling, cancellation-aware audio decoding, bounded mux startup, PTS hardening and partial-file cleanup.
- Forward/freeze MediaCodec streaming video decode with bounded decoder pool + automatic random-access fallback.
- Encoder-surface GLES post processing for supported effects/transitions plus final overlay alpha composition.
- Reused decoded bitmaps and GL texture storage to reduce per-frame allocation churn.
- MediaCodec size/rate/surface preflight, effective bitrate clamping and estimated MP4 size before destination selection.
- High-resolution main-source decode path up to 3840px while overlay/static decode remains bounded for memory control.
- Encoder-surface main-source GPU graph for crop/fit/transform/keyframe+stabilization result/chroma/color/opacity/mask when post stack is GPU eligible.
- Streaming source-frame leases pin decoder-owned bitmaps until encoder draw completes.
- Reverse clips prefer previous-sync MediaCodec forward decode with a memory-bounded decoded-tail cache and automatic MMR fallback.
- Frame-boundary video checkpoints with deterministic render fingerprinting and cache-backed resume after cancel/recoverable interruption.
- One full-project AAC checkpoint plus no-reencode final MP4 remux, preventing per-segment AAC priming seams.
- Resume-aware cache budget, measurable destination-space preflight/recheck, memory warnings and thermal checkpoint backoff.
- Per-video-segment compositor/decoder/GPU teardown/reacquisition to reduce long-run resource accumulation.

Pending:
- Zero-copy OES/SurfaceTexture decoder-to-GPU source path and broader GPU overlay graph.
- WorkManager/user-initiated-job fallback strategy for vendor-specific foreground-service limits beyond the Patch 27 service path.
- Arbitrary/manual bitrate controls beyond the safe Patch 22 planner.
- Studio-grade time stretch/pitch tools, voice-over capture, NR/voice enhancement and voice effects.

## Stage 8 — AI suite
**Pending after core/export maturity.**
Auto captions, silence/smart cut, background removal, auto reframe, scene detection, tracking assistance, smart search, upscale/interpolation/AI slow-mo; later generative tools.

## Stage 9 — Templates & assets
**Pending.**
Server-delivered templates/effects/transitions/stickers/fonts/music/filters/LUT/text styles.

## Stage 10 — Account & cloud
**Pending.**
Login, backup/sync, cross-device, asset history/favorites, entitlements.

## Stage 11 — Anti-flop performance
**Continuous.**
Test 5/30/60 min projects, 50+/100+ clips, multi-audio, 4K, later many text/effects, low/mid/high Android, background restore, interrupted export, low storage/corrupt media. Track FPS/RAM/CPU/GPU/dropped frames/decoder latency/export speed/crash rate.

## Stage 12 — Production release
**Pending.**
Onboarding/project polish, analytics/crash reporting, remote config/feature flags, privacy/security, subscriptions if required, Play Store readiness.

---

## Patch roadmap
- **Patch 07:** Audio Editing Pro Foundation — locked after CI/device verification.
- **Patch 08:** Core Visual Transform Engine — locked after CI/device verification.
- **Patch 09:** Speed / Freeze / Reverse Foundation — locked after CI/device verification.
- **Patch 10:** Visual Overlay / PIP Tracks — locked after CI/device verification.
  - Timed independent image/video visual layers.
  - Drag/trim overlay timeline editing.
  - Persistent z-order/layer selection.
  - Shared transform controls.
  - `OverlayComposition` renderer-independent state for preview/export reuse.
  - Native synchronized PIP preview; overlay video audio intentionally muted.
- **Patch 11:** Text & Caption Track Foundation — locked after CI/device verification.
  - Timed manual text layers with move/trim/z-order.
  - Text style + transform controls and native preview.
  - Renderer-independent `TextComposition` for future export reuse.
  - Schema v11 persistence + undo/redo.
- **Patch 12:** Caption/Subtitles Foundation — locked after CI/device verification.
  - Dedicated caption segment track and renderer-independent caption composition.
  - Manual add/edit/move/trim/split/delete.
  - SRT import/export and batch ±0.25s timing shift.
  - Caption-safe BOXED/CLEAN/LARGE presets.
  - Schema v12 persistence + undo/redo.
- **Patch 13:** Text / Caption Expansion Foundation — locked after CI/device verification.
  - Reusable free-text presets and renderer-independent font family keys.
  - Basic deterministic None/Fade/Pop/Slide-Up animation via `TextMotion`.
  - Expanded caption presets plus caption font/animation controls.
  - Schema v13 persistence + undo/redo.
- **Patch 14:** Effects / Transitions Foundation — locked after CI/device verification.
  - Dedicated timed FX track with move/trim and intensity.
  - Warm/Cool/Vignette/Dream/Grain real native preview overlays.
  - Fade-Black/Flash/Wipe clip-boundary transition model + duration.
  - Renderer-independent `EffectComposition`, persistence and undo/redo.
- **Patch 15:** Keyframe Engine Foundation — locked after CI/device verification.
  - Renderer-independent local transform keyframe tracks for main clips and overlays.
  - Scale/position/rotation/opacity interpolation with Linear/Ease In/Ease Out/Ease In-Out/Hold.
  - Real preview evaluation plus add/remove/prev/next/easing controls.
  - Timeline-safe split/trim/uniform-speed remapping, persistence and undo/redo.
- **Patch 16:** Masks / Chroma Foundation — locked after CI/device verification.
  - Renderer-independent main-clip rectangle/ellipse mask + feather/invert state.
  - Native mask occlusion preview beneath overlays/text.
  - Chroma-key enabled/color/tolerance/softness/spill model.
  - API 33+ RuntimeShader live chroma preview, schema v16 persistence + undo/redo.
- **Patch 17:** Motion Tracking / Stabilization Foundation — locked after CI/device verification.
  - Renderer-independent manual tracking points + interpolation.
  - Draggable preview reticle, point navigation and undo/redo.
  - Stabilization state/preview with strength + auto-crop.
  - Tracking-safe trim/split/speed/reverse semantics, schema v17 persistence.
- **Patch 18:** Advanced Color / GPU Compositor Foundation — locked after CI/device verification.
  - Per-main-clip exposure/contrast/saturation/temperature/tint/fade.
  - Deterministic Android-free `ColorGradeEngine`.
  - API 31+ hardware color preview and API 33+ chroma+color RenderEffect chaining.
  - Android-free `FrameCompositionBuilder` resolving canonical transform/mask/chroma/color/effect/transition render state.
  - Schema v18 persistence + undo/redo.
- **Patch 19:** Production Render / Export Engine Foundation — locked after CI/device verification.
  - Android-free export plan/capability contract.
  - Real 720p/1080p H.264 + AAC MP4 save path.
  - Correctness-first off-screen software compositor using canonical timing/render state.
  - EGL/GLES encoder-surface bridge, progress/cancel/error handling and partial-file cleanup.
- **Major Patch 20:** Real Audio Mixer / Export Parity / Reliability — locked after CI/device verification.
  - Main-video source audio + independent overlapping audio clips render into stereo AAC.
  - Volume/mute/fade export parity plus forward speed-audio overlap-add handling.
  - Android-free audio mix plan, seekable normalized PCM decode cache and deterministic offline mixer.
  - Cancellation-aware audio prep, bounded mux startup, monotonic PTS and encoder stall hardening.
  - Project schema remains v18; no duplicate saved audio state introduced.
- **Patch 21:** Production Decoder / GPU Compositor Performance Foundation — locked after CI/device verification.
  - Forward/freeze main video + video overlays prefer bounded MediaCodec streaming decode.
  - Reverse/unsupported decoder paths retain correctness-first random-access fallback.
  - LRU decoder pool + reusable decoded/output bitmaps reduce long-project allocation pressure.
  - Hybrid base/overlay export planes move supported timed effects/transitions + final alpha composition onto the encoder GLES surface.
  - GL texture storage is reused across frames; schema remains v18.
- **Patch 22:** High-Resolution / Codec Controls & Export Preflight Foundation — locked after CI/device verification.
  - 720p/1080p/1440p/2160p output profiles with 24/30/60fps.
  - H.264 AVC + H.265 HEVC selection with deterministic profile-aware bitrate planning.
  - MediaCodec surface/size/rate preflight, hardware-first exact encoder selection and safe bitrate clamping.
  - Preflight summary includes estimated output size and compatibility/performance warnings before SAF destination selection.
  - Main-source high-resolution decode may reach 3840px while overlay/static decode remains bounded; schema stays v18.
- **Patch 23:** Full GPU Source Graph / Reverse Decode Cache Foundation — locked after CI/device verification.
  - Android-free `GpuSourceGraphPlanner` keeps crop/fit/transform/chroma/color/mask execution tied to canonical frame state.
  - Main-source stages run in encoder GLES when the timed post stack is GPU compatible; Grain preserves CPU fallback.
  - `FrameLease` pins decoder-owned main-source bitmaps through encoder upload.
  - Reverse clips prefer bounded MediaCodec previous-sync forward-decode cache before MMR fallback.
  - Schema remains v18.
- **Patch 24:** Export Recovery / Long-Project Hardening Foundation — locked after CI/device verification.
  - Frame-boundary recoverable video checkpoints with render-ABI fingerprinting.
  - Full-length AAC checkpoint + lossless final remux.
  - Resume-aware cache/destination storage preflight and thermal checkpoint hardening.
  - Per-segment decoder/GPU/compositor resource reacquisition; schema remains v18.
- **Patch 25:** Advanced Color / LUT / Curves Foundation — locked after CI/device verification.
  - Five-anchor Master/R/G/B curves, global HSL and built-in LUT look/intensity state.
  - Unified API 33+ preview shader + GLES export + CPU fallback parity.
  - Schema v19 persistence and Patch 24 recovery fingerprint invalidation.
- **Patch 26:** Advanced Audio / Text Expansion Foundation — locked after CI/device verification.
  - Independent audio role/pan/music-ducking controls with preview/export parity.
  - Text transform keyframes with shared easing, navigation, trim-safe remap and preview/export parity.
  - Schema v20 + `vedito-render-p26-r1` recovery invalidation.
- **Patch 27:** Foreground Export / Release Hardening Foundation — current patch.
  - Foreground service owns long export across Activity background/recreation.
  - Android 15+ mediaProcessing + Android 14 compatibility dataSync service typing and permissions.
  - Persistent task/progress state, notification Cancel/deep-link, process redelivery and checkpoint resume.
  - Android timeout/start-failure hardening; schema v20 and render salt unchanged.
- **Patch 28+:** capture/voice-over, then AI/templates/cloud.

## Release gates
Before locking a major stage:
- No reproducible crash in acceptance suite.
- Saved project reopens accurately.
- Undo/redo cannot corrupt project state.
- UI respects system insets/small screens.
- Feature has a deterministic export path; avoid preview-only state that cannot later render.
