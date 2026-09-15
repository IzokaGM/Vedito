# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.26.0 / versionCode 26 / project schema v20**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 24 recovery rules still apply: finalized video segments + one full AAC checkpoint live in cache, `.part` files are never resumable, and final delivery is a no-reencode remux. Cache may disappear; recovery must always tolerate missing checkpoints.

Patch 25 advanced color remains canonical: base matrix → RGB curves → global HSL → built-in LUT. API 33+ preview, GLES export and CPU fallback consume the same saved color state.

Patch 26 extends canonical audio/text state instead of creating preview-only controls. Independent `AudioClip` now stores `AudioRole`, `pan` and `duckingAmount`. Only MUSIC tracks duck, and only around audible independent VOICE clips. Preview `AudioPlaybackEngine` and export `AudioMixPlan`/`OfflineAudioMixer` consume the same state. Source-video audio is not a VOICE trigger.

`TextClip` now stores a local `TransformKeyframeSet`. `TextKeyframeEngine` adapts the existing `KeyframeEngine`, so scale/position/rotation/opacity use the same interpolation/easing model as visual clips. Text preview and software export evaluate the same state. Left/right trim logic preserves the surviving animation boundary.

Patch 26 changes encoded audio/text output, so `ExportRecoveryPlanner` render salt is **`vedito-render-p26-r1`**. Do not reuse Patch 25 recovery checkpoints across this boundary.

Important limits: no foreground/WorkManager export service yet; no voice-over capture, NR/voice enhancement, pitch tools or beat detection; no auto captions/TTS/karaoke; text style/font/color/animation parameters are not keyframed yet.

Next planned milestone: **Patch 27 — Foreground Export / Release Hardening Foundation** unless CI/device testing exposes a Patch 26 regression first.
