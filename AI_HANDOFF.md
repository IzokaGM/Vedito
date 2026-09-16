# AI Handoff — Patch 28C.3.1

Current version: **0.28.6 / code 34**. Schema remains **v20**.

Latest correction targets device density rather than new engine behavior: the editor now opens with the context drawer collapsed, the main timeline is borderless/slimmer, technical clip metadata is removed from chrome, Undo/Redo are persistent transport actions, and Clip actions are icon-first. Tool drawers open on demand and tapping the active tool collapses them.

Patch 28C.3.1 is an emergency layout recovery: bound the multi-track timeline to 136dp, move lane overflow into an internal vertical scroll area, and keep the shared playhead inside that bounded viewport. Device screenshot approval is required before Patch 28D.

---

# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `BRAND.md`, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.28.6 / versionCode 34 / project schema v20**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 24 recovery rules still apply: finalized video segments + one full AAC checkpoint live in cache, `.part` files are never resumable, and final delivery is a no-reencode remux. Cache may disappear; recovery must always tolerate missing checkpoints.

Patch 25 advanced color remains canonical: base matrix → RGB curves → global HSL → built-in LUT. API 33+ preview, GLES export and CPU fallback consume the same saved color state.

Patch 26 extends canonical audio/text state instead of creating preview-only controls. Independent `AudioClip` now stores `AudioRole`, `pan` and `duckingAmount`. Only MUSIC tracks duck, and only around audible independent VOICE clips. Preview `AudioPlaybackEngine` and export `AudioMixPlan`/`OfflineAudioMixer` consume the same state. Source-video audio is not a VOICE trigger.

`TextClip` now stores a local `TransformKeyframeSet`. `TextKeyframeEngine` adapts the existing `KeyframeEngine`, so scale/position/rotation/opacity use the same interpolation/easing model as visual clips. Text preview and software export evaluate the same state. Left/right trim logic preserves the surviving animation boundary.

Patch 26 changes encoded audio/text output, so `ExportRecoveryPlanner` render salt is **`vedito-render-p26-r1`**. Do not reuse Patch 25 recovery checkpoints across this boundary.

Patch 27 moves long export ownership into `ExportForegroundService`. Android 15+ uses `mediaProcessing`; API 29–34 uses `dataSync`. `ExportTaskStore` reconnects progress/terminal state and `START_REDELIVER_INTENT` re-enters the Patch 24 checkpoint engine after process recreation. Timeout/start failures must preserve finalized checkpoints and stop cleanly. Render semantics did not change, so recovery salt remains **`vedito-render-p26-r1`** and schema remains v20.

Important limits: no WorkManager/vendor-specific fallback yet; no voice-over capture, NR/voice enhancement, pitch tools or beat detection; no auto captions/TTS/karaoke; text style/font/color/animation parameters are not keyframed yet.

Patch 28A locks the Vedito brand direction before the UI overhaul: V + play + cut-frame logo, `Shape the cut.` tagline, dark premium base, cyan/blue signature gradient and functional multi-accent category colors. The generated presentation board is reference only; app resources use the isolated production mark.

Patch 28B rebuilds Home around that design system. The New Project/Assets routes are real picker flows; Draft/Edit open real projects; recent cards render real source-video thumbnails. Templates are preview-only and Profile is explicitly unimplemented. Export still belongs to Editor, so Home routes to the latest project rather than inventing an export-history model.

Patch 28C/28C.1/28C.2 rebuild and then tighten the Editor shell without changing canonical edit/render state. Preview is primary, the tool dock can collapse the active drawer, and Patch 28C.3 now keeps Video/Audio/Text timeline lanes persistent while Overlay/Captions/Effects lanes appear when content exists. Preview taps on text/overlay/caption reveal the matching tool mode. Technical clip metadata and stacked card chrome are intentionally kept out of the default editor view.

Next planned milestone: **Patch 28D — Export UI Overhaul**, unless CI/device testing exposes an Editor regression first.


## Latest UI patch — 28C.3
Persistent multi-track timeline: Video/Audio/Text always visible; empty Audio/Text lanes launch real add flows; Overlay/Captions/Effects appear with content; ruler and shared playhead sync to the main timeline viewport. Version 0.28.5 / code 33; schema v20 unchanged. Patch 28C.3.1 follows with layout-only recovery at version 0.28.6 / code 34.
