# Vedito Patch 26 — Advanced Audio / Text Expansion Foundation

Version: **0.26.0** (`versionCode 26`)  
Project persistence schema: **v20**

## What Patch 26 adds
- Independent audio clip roles: **MUSIC / VOICE / SFX**.
- Stereo pan controls with legacy-safe centered gain.
- Deterministic automatic ducking: MUSIC clips attenuate under overlapping VOICE clips with 180 ms attack / 360 ms release and per-music-clip duck amount.
- Preview and export consume the same saved audio role/pan/ducking state.
- Text transform keyframes for scale, X/Y position, rotation and opacity using the existing canonical easing model.
- Text keyframe add/remove, previous/next navigation and easing cycling in the text toolbar.
- Selected text clips show keyframe markers in the text timeline.
- Text preview and software export evaluate the same keyframe interpolation.
- Left/right text trims preserve surviving keyframe timing and interpolate the trim boundary instead of jumping.
- Project schema v20 remains backward-compatible: older projects load neutral/default Patch 26 fields.
- Export recovery salt is bumped to `vedito-render-p26-r1` so stale Patch 25 checkpoints cannot be reused.

## Current limits
- Ducking triggers only from independent audio clips explicitly marked VOICE. Source-video audio is not automatically treated as narration.
- No voice-over recording, noise reduction/voice enhancement, pitch shifting or beat detection yet.
- Text keyframes cover transform properties only; style/font/color/animation parameters are still static per text clip.
- Auto captions, karaoke/per-word timing, TTS and downloadable fonts remain pending.
- Export recovery is still cache-backed rather than a persistent foreground/WorkManager job.

## Validation
- Android-free Kotlin core compilation passed locally for model, audio mix planning/math, export recovery planning and text keyframe timing.
- Functional checks passed for voice-priority ducking, stereo pan, text interpolation and inward/outward text-trim remapping.
- `activity_editor.xml` parses successfully.
- Full Android Gradle compilation could not run locally because the wrapper distribution is not available offline; GitHub Actions remains the final Android build/device gate.
