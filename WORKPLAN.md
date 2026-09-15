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
**Pending.**
- Keyframes + easing/graphs.
- Masks + feather.
- Chroma key.
- Motion/object tracking.
- Stabilization, motion blur.
- HSL/curves/wheels/LUT.

## Stage 4 — Audio
**Pro foundation completed through Patch 07; advanced audio pending.**
Completed:
- Audio import/assets/clips, overlapping preview.
- Waveforms, selection, move/snap/trim/split.
- Volume/mute/delete, fades.
- Extract audio for normal 1× forward clips.
- Persistence + undo/redo.

Pending:
- Voice-over recording.
- Ducking.
- Noise reduction/voice enhancement.
- Pitch/voice effects.
- Beat detection/markers.
- Retime/reverse audio model for retimed source extraction.

## Stage 5 — Text & captions
**Core manual text/caption foundation completed through Patch 13; advanced/AI text remains pending.**
Completed: timed manual text layers, dedicated caption segments, SRT import/export, segment move/trim/split, batch caption shift, reusable text presets, shared font-family keys, caption-safe presets, deterministic basic Fade/Pop/Slide-Up animation, preview composition, persistence + undo/redo.
Pending: custom/downloaded font packs, advanced/keyframed text animation, auto captions, correction workflow, karaoke/per-word timing, TTS.

## Stage 6 — Effects / transitions
**Pending.**
Modular effect system, filters/adjustments, transitions, effects, stickers/overlay assets, timing/parameters.

## Stage 7 — Production render/export engine
**Pending — critical.**
- Deterministic off-screen compositor.
- Preview ≈ export.
- H.264/H.265 + AAC/MP4.
- 720p/1080p/2K/4K where supported.
- FPS/bitrate controls, hardware encoding.
- Progress/cancel/recovery, thermal/memory/storage handling.
- Production reverse decode/audio and freeze/speed rendering consume the same timing model as preview.

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
- **Patch 13:** Text / Caption Expansion Foundation — current patch.
  - Reusable free-text presets and renderer-independent font family keys.
  - Basic deterministic None/Fade/Pop/Slide-Up animation via `TextMotion`.
  - Expanded caption presets plus caption font/animation controls.
  - Schema v13 persistence + undo/redo.
- **Patch 14:** Effects / Transitions Foundation — next.
  - Renderer-independent effect stack and transition timing/state.
  - Initial real preview adjustments/filters and transition model.
- **Patch 15+:** Pro visual tools, production export, advanced audio/text and AI stages.

## Release gates
Before locking a major stage:
- No reproducible crash in acceptance suite.
- Saved project reopens accurately.
- Undo/redo cannot corrupt project state.
- UI respects system insets/small screens.
- Feature has a deterministic export path; avoid preview-only state that cannot later render.
