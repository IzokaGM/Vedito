# Vedito — Canonical Project Context

> **Read this file first before changing Vedito.** This is the handoff document for any AI/developer continuing the project.

## Product goal
Vedito is a premium, native Android video editor targeting **CapCut-class capability and reliability** over time. It must not be a pixel-for-pixel CapCut clone and must not reuse CapCut/Cutrim proprietary assets or source. The target is comparable editing capability, smooth UX, strong performance, and a distinct Vedito identity.

## Locked identity
- App/brand: **Vedito**
- Android package/applicationId: **`com.vedito.app`**
- Platform focus: **Android first**
- Current version in this patch: **0.7.0 / versionCode 7**

## Locked technical decisions
- **Native Android/Kotlin**, not React Native.
- Standalone APK: **no Metro, Hermes, JavaScript runtime, or development server dependency**.
- Minimum SDK 26; target/compile SDK 37.
- Java 17 toolchain.
- Current UI is XML + ViewBinding + custom native Views.
- Current preview uses Android `MediaPlayer` + `TextureView`.
- Heavy editing/rendering can move to MediaCodec/OpenGL/C++ modules later when required by performance, but do not prematurely rewrite stable modules.
- Project persistence currently uses `SharedPreferences` + JSON through `ProjectRepository`; migration paths must preserve old projects.

## Repository / delivery rules
The owner works mainly from a phone and deploys patches through GitHub Actions.

Every code patch delivered by an AI should:
1. Be a ZIP whose internal paths are correct for extracting at repository root.
2. **Never include `.yml` or `.yaml` files inside the ZIP.**
3. If a workflow needs changing, provide that workflow file separately.
4. Prefer a small, focused patch rather than unrelated rewrites.
5. Do not claim a feature is finished merely because its button/UI exists; it must perform the real action and persist correctly where relevant.
6. Ask for confirmation before building a new patch unless the user has explicitly said to start/build that patch.

Current CI concept outside patch ZIP:
- `.github/workflows/01-auto-unzip.yml`: reacts to uploaded patch ZIP, extracts to repo root, rejects YML/YAML inside the ZIP, commits extracted files.
- `.github/workflows/02-build-apk.yml`: starts after successful unzip workflow and builds the native Android APK.

## Product quality rules
Vedito should be built as a real editor, not a demo:
- Engine correctness before feature count.
- Preview state, saved project state, and final export state must agree.
- Destructive timeline edits require undo/redo where practical.
- Avoid hardcoded screen-safe-area assumptions; use system insets.
- Avoid unnecessary dependencies and APK bloat.
- Test long/multi-clip projects and low/mid-range Android devices.
- Never copy Cutrim code. A previous Cutrim repo was supplied only to illustrate a simple standalone native APK build style.

## Current architecture
Primary model:
- `MediaAsset`: source video metadata.
- `Clip`: trimmed reference to a video asset. Main video clips are ripple-sequenced.
- `AudioAsset`: source audio metadata.
- `AudioClip`: timeline-positioned audio reference with source trim, volume/mute and fade state. Audio clips can overlap.
- `Project`: assets, video clips, audio assets/clips, playhead, selections, timeline zoom and viewport.

Important modules:
- `core/projects/ProjectRepository.kt` — project JSON persistence/migration.
- `core/timeline/TimelineIndex.kt` — maps project timeline time to video clips.
- `core/timeline/TimelineEditor.kt` — video timeline destructive operations.
- `core/timeline/EditorHistory.kt` — bounded runtime undo/redo snapshots.
- `feature/editor/timeline/TimelineScrubberView.kt` — video timeline gestures/rendering.
- `feature/editor/player/PreviewPlayer.kt` — video preview.
- `core/audio/AudioPlaybackEngine.kt` — multi-audio preview synchronization/mixing using native MediaPlayer slots, including fade gain.
- `core/audio/AudioTimelineEditor.kt` — pure split/normalization/fade edit math.
- `core/audio/AudioWaveformCache.kt` — background PCM waveform decode + disk cache.
- `feature/editor/audio/AudioTimelineView.kt` — audio lane visualization and selection.
- `feature/editor/EditorActivity.kt` — current editor orchestration.

## Completed progression
- Native clean rewrite with standalone APK and Vedito branding.
- Status/navigation bar insets fixed.
- Real video thumbnails.
- Frame-aware scrub/playhead.
- Trim, split, delete.
- Multi-video project model.
- Add video, reorder, ripple behavior.
- Autosave/reopen state.
- Undo/redo.
- Timeline pinch zoom, snapping, duplicate, replace.
- Patch 06: audio asset/clip model, audio import, overlapping audio preview, audio lanes, selection, volume, mute, delete, autosave and undo/redo integration.
- Patch 07: cached PCM waveforms, audio drag/move + snapping, trim handles, split, fades and extract-audio from selected video clip.

## Explicitly not completed yet
Do not assume these exist:
- Audio ducking, noise reduction/voice enhancement, pitch/voice effects, beat detection/markers and voice-over recording.
- Crop/rotate/flip/scale/position/opacity.
- Speed/speed curves/reverse/freeze.
- Multi-layer visual overlays/PIP.
- Text/captions.
- Effects/transitions/color grading.
- Keyframes/masks/chroma/tracking/stabilization.
- Production export/compositor.
- AI features, templates, cloud/account/subscription.

## Current patch acceptance target (0.7.0)
A valid Patch 07 test is:
1. Open/create a project with video and import audio.
2. Waveform appears after background decode/cache.
3. Drag audio clips and verify snapping to playhead/video edit boundaries.
4. Trim from left/right edges and split selected audio at playhead.
5. Cycle fade-in/out and verify preview volume ramps through the fade regions.
6. Select a video clip and extract its audio into an editable audio clip.
7. Undo/redo the audio edits.
8. Close/reopen project; trim/move/split/fade state must persist.
9. Existing Patch 06 and older projects must still open with fade defaults of zero.

## Non-goals / safety against architecture drift
- Do not return to React Native unless the owner explicitly reverses the decision.
- Do not build a giant FFmpeg dependency merely for basic preview/editing. Introduce heavy native components only for a clear rendering requirement.
- Do not rewrite the whole app just to add one feature.
- Do not make UI controls that are intentionally nonfunctional.

See `WORKPLAN.md` for the master roadmap and current next milestone.
