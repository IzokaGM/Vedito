# Vedito — Master Workplan

## North-star
Build a stable, premium, native Android editor with CapCut-class breadth over iterative releases. Each milestone follows:

**BUILD → FUNCTIONAL TEST → STRESS TEST → FIX → LOCK → NEXT**

A feature is complete only when its behavior, project persistence, preview integration and later export integration are correct.

## Master capability stages

### Stage 0 — Architecture / foundation
Status: **Foundation locked; evolve without unnecessary rewrites.**
- Native Android/Kotlin app shell.
- Design system and system insets.
- Modular project/timeline/player structure.
- Persistent project schema with migrations.
- CI patch/unzip/build flow.

### Stage 1 — Editing timeline engine
Status: **Core complete; optimization continues.**
- Multi-video timeline.
- Thumbnail timeline and playhead.
- Trim/split/delete/duplicate/replace/reorder.
- Ripple sequence behavior.
- Frame-aware scrub and snapping.
- Pinch zoom and viewport.
- Undo/redo.
- Autosave/reopen.
- Stress target: 50+ clips without state corruption.

### Stage 2 — Core visual editing
Status: **Pending.**
- Crop/rotate/flip.
- Scale/position/opacity.
- Canvas/aspect ratio/background.
- Freeze frame/reverse.
- Normal speed and speed curves.
- Picture-in-picture / visual overlay tracks.

### Stage 3 — Pro visual editing
Status: **Pending.**
- Keyframes + easing/graphs.
- Masks + feather.
- Chroma key.
- Motion/object tracking.
- Stabilization.
- Motion blur.
- Advanced color: HSL, curves, wheels, LUT.

### Stage 4 — Audio
Status: **Patch 07 pro foundation completed after device verification.**
Completed through Patch 07:
- Audio import and audio asset/clip project model.
- Multiple overlapping audio preview.
- Audio lane view and cached PCM waveforms.
- Select, volume, mute, delete.
- Drag/move with snapping, left/right trim, split.
- Fade in/out preview + persistence.
- Extract audio from selected video clip.
- Save/reopen + undo/redo.

Next audio milestones after visual-core work:
- Voice-over recording.
- Audio ducking.
- Noise reduction/voice enhancement.
- Pitch/voice effects.
- Beat detection/markers.

### Stage 5 — Text & captions
Status: **Pending.**
- Rich text layers.
- Font/style/stroke/shadow/background.
- Text transform/timing.
- Text animation.
- Subtitle track.
- SRT import/export.
- Auto captions and correction workflow.
- Karaoke/per-word highlighting.
- Text-to-speech.

### Stage 6 — Effects / transitions
Status: **Pending.**
- Modular effect system.
- Filters and adjustments.
- Transitions.
- Video/body effects.
- Stickers/overlay assets.
- Effect timing and parameters.

### Stage 7 — Production render/export engine
Status: **Pending — critical milestone.**
- Deterministic off-screen compositor.
- Preview ≈ export behavior.
- H.264/H.265 + AAC, MP4.
- 720p/1080p/2K/4K where device supports it.
- FPS/bitrate/quality controls.
- Hardware encoding.
- Background export, progress, cancellation and recovery.
- Thermal/memory/storage handling.

### Stage 8 — AI suite
Status: **Pending after core editor/export maturity.**
- Auto captions.
- Silence remover / smart cut.
- Background removal.
- Auto reframe.
- Scene detection.
- Object/face tracking assistance.
- Smart search.
- Upscale/frame interpolation/AI slow motion.
- Later: script-to-video, image-to-video, AI voice/avatar/B-roll.

### Stage 9 — Templates & asset ecosystem
Status: **Pending.**
Server-delivered templates, effects, transitions, stickers, fonts, music, filters, LUTs and text styles without requiring an APK update for every content addition.

### Stage 10 — Account & cloud
Status: **Pending.**
Login, project backup/sync, cross-device workflow, asset download/favorites/history and entitlement state.

### Stage 11 — Anti-flop performance program
Status: **Continuous.**
Mandatory stress cases:
- 5/30/60-minute projects.
- 50+ and 100+ clips.
- Multiple audio layers.
- 4K sources.
- Many text/effect layers later.
- Low/mid/high-end Android.
- Background/restore, interrupted export, low storage and corrupt media.
Track FPS, RAM, CPU/GPU, dropped frames, decoder latency, export speed and crash rate.

### Stage 12 — Production release
Status: **Pending.**
Onboarding, project management polish, account/subscription if required, analytics, crash reporting, remote config/feature flags, privacy/security and Play Store readiness.

---

## Execution roadmap from current state

### Patch 07 — Audio Editing Pro Foundation
Current patch.
Acceptance: waveform → move/snap → trim → split → fades → extract audio → undo/redo → save/reopen.

### Patch 08 — Core Visual Transform Engine
Next planned patch after Patch 07 device verification.
- Per-clip transform model.
- Crop/rotate/flip.
- Scale/position/opacity.
- Canvas/aspect ratio/background.
- Preview transform pipeline designed to be reusable by exporter.

### Patch 09 — Speed / Freeze / Reverse Foundation
- Speed model and duration mapping.
- Normal speed controls.
- Freeze frame.
- Reverse pipeline design.
- Speed curve groundwork.

### Patch 10 — Visual Overlay / PIP Tracks
- Independent visual layers with timeline timing.
- Image/video overlays.
- Z-order/layer selection.
- Transform controls on overlays.

### Patch 11+ — Text, effects, transitions, pro tools
Continue through master stages while protecting timeline/render architecture.

## Release gates
Before declaring a major stage stable:
- No reproducible crash in its acceptance suite.
- Saved project reopens accurately.
- Undo/redo cannot corrupt the project.
- UI respects system insets and remains usable on small screens.
- Feature has a path to deterministic export; avoid preview-only architecture that cannot be rendered later.
