# Vedito — Canonical Project Context

> Read this file first before changing Vedito. It is the canonical handoff for another AI/developer.

## Product goal
Vedito is a premium native Android video editor targeting CapCut-class breadth, reliability and performance over iterative releases. It must have its own identity and must not copy CapCut/Cutrim proprietary source/assets.

## Locked identity
- Brand/app: **Vedito**
- Android package/applicationId: **`com.vedito.app`**
- Android first
- Current patch: **0.12.0 / versionCode 12**

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
- `Clip`: source trim + `ClipTransform` + `ClipTiming`.
- `ClipTiming`: speed, playback mode (FORWARD/REVERSE/FREEZE), freeze source frame/duration.
- `ClipTimeMap`: canonical source↔timeline time mapping. Future speed curves/export must build on this rather than duplicating timing math in UI.
- `AudioAsset` / `AudioClip`: independent overlapping audio timeline with trim, volume, mute, fades.
- `OverlayAsset` / `OverlayClip`: independent timed image/video PIP layers with transform and z-order.
- `OverlayComposition`: renderer-independent active-layer resolver shared concept for preview now and deterministic export later.
- `TextClip`: timed free-form text layer with `TextStyle`, `TextTransform` and z-order.
- `TextComposition`: renderer-independent active text-layer resolver for preview/export reuse.
- `CaptionSegment`: dedicated subtitle cue with project timing and caption-safe style preset.
- `CaptionComposition` / `CaptionTimelineEditor` / `SrtCodec`: renderer-independent caption resolution, timing edits and SRT interchange.
- `Project`: video/audio/overlay/text/caption state, canvas, playhead, selections and zoom/viewport.

Important modules:
- `core/projects/ProjectRepository.kt` — persistence, schema v12.
- `core/timeline/ClipTimeMap.kt` — timing mapping shared concept for preview/export.
- `core/timeline/TimelineIndex.kt`, `TimelineMath.kt`, `TimelineEditor.kt` — ripple timeline and destructive/timing operations.
- `core/timeline/EditorHistory.kt` — runtime undo/redo snapshots.
- `feature/editor/timeline/TimelineScrubberView.kt` — scrub/zoom/trim/reorder UI, timing-aware trim.
- `feature/editor/player/PreviewPlayer.kt` — forward speed preview plus seek-driven reverse/freeze playback foundation.
- `feature/editor/timing/TimingToolbarView.kt` — speed/freeze/reverse controls.
- `core/visual/VisualTransformMath.kt` / `TransformToolbarView.kt` — visual transforms/canvas.
- `core/audio/*` + `feature/editor/audio/AudioTimelineView.kt` — audio foundation.
- `core/overlay/OverlayComposition.kt`, `OverlayTimelineEditor.kt` — renderer-independent PIP layer resolution and timing edits.
- `feature/editor/overlay/OverlayTimelineView.kt`, `OverlayPreviewController.kt` — overlay editing/preview implementation.
- `core/text/TextComposition.kt`, `TextTimelineEditor.kt` — text timing/layer/state rules.
- `feature/editor/text/TextTimelineView.kt`, `TextToolbarView.kt`, `TextPreviewController.kt` — native manual text editing/preview.
- `core/caption/*` + `feature/editor/caption/*` — subtitle segments, SRT import/export, caption timeline and preview.
- `feature/editor/EditorActivity.kt` — current editor orchestration.

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

## Patch 12 behavior/limits
- Captions are a dedicated track, separate from free-form `TextClip` layers.
- SRT import replaces the caption track as one undoable edit; export writes current normalized cues.
- Caption cues use absolute project time and are clamped to the current base-video project duration.
- Caption preview uses native Views, while `CaptionComposition` / model state remain Android-View-free for deterministic export reuse.
- Three caption-safe presets exist now: BOXED, CLEAN and LARGE. Rich font packs, outline/shadow, animation and per-word styling are later work.
- Auto captions / speech recognition and word-level timing remain future AI/text milestones.

## Explicitly not completed
- Speed curves UI/easing.
- Production reverse decoder or reversed source audio.
- Freeze duration UI beyond default insertion.
- Voice-over, ducking, NR/voice enhancement, pitch/voice effects, beat markers.
- Auto captions, per-word/karaoke timing, TTS and text animation.
- Effects/transitions/color grading.
- Keyframes/masks/chroma/tracking/stabilization.
- Production export compositor.
- AI/templates/cloud/account/subscription.

## Next milestone
**Patch 13 — Text / Caption Expansion Foundation**
- caption/text polish, reusable presets and font architecture,
- groundwork for text/caption animation without coupling state to Android Views,
- keep auto-caption speech recognition and per-word timing for later AI work unless owner changes priority.

See `WORKPLAN.md` for the full roadmap.
