# Patch 28D.1 — Foreground export runtime fix

Version **0.28.10 / code 38**; project schema **v20**; recovery salt **`vedito-render-p26-r1`** unchanged.

- Root cause on targetSdk 37: `ServiceCompat.startForeground()` from AndroidX Core 1.16 filters the Android 15 `mediaProcessing` FGS type, causing the platform to reject the resulting `none` type. `ExportForegroundService` now calls the platform `Service.startForeground(id, notification, type)` directly on API 29+ (`mediaProcessing` API 35+, `dataSync` API 29–34), with the 2-argument overload on API 26–28.
- Foreground promotion now occurs before the project repository lookup, not after. Failure marks the matching active export task as failed without starting the codec engine; the existing cancel, timeout, checkpoint resume, progress notification and UI connections remain in place.
- The existing manifest already declares both types and their permissions; there is no permission or workflow change. Existing export UI and editor layout are not modified.
- GitHub Actions Android build and device export on Android 15+ are still required runtime gates; static checks alone cannot prove that an export finishes on a device.

---

# Latest handoff — Export UI 28D (0.28.9 / code 37)

Apply Patch 28D over the owner’s green Patch 28C.3.3 repo. `EditorActivity.showExportOptions()` now opens a branded export settings sheet, device/recovery review, then the existing document picker + `ExportForegroundService`. Progress ring reads the real `ExportTaskStore` snapshot; Background only dismisses UI; Cancel invokes the existing service action. While active, the editor Export button reads **Progress** and reopens the panel after background dismissal. Do not auto-reopen it on every status broadcast. ETA is explicitly unavailable until an actual reliable estimation source exists; do not fake one. Bitrate is derived by the planner/device selection, not an interactive setting; AAC options are 96/128/192/256 kbps. UI-only: project schema v20 and render recovery salt `vedito-render-p26-r1` unchanged. Brand and parked editor visual work remain unchanged. Full Android build must run in GitHub Actions; this workspace lacks the complete 25–27 source baseline and offline Gradle 9.6 distribution.

---

# AI Handoff — Patch 28D

Current version: **0.28.9 / code 37**. Schema remains **v20**.

Latest correction targets device density rather than new engine behavior: the editor now opens with the context drawer collapsed, the main timeline is borderless/slimmer, technical clip metadata is removed from chrome, Undo/Redo are persistent transport actions, and Clip actions are icon-first. Tool drawers open on demand and tapping the active tool collapses them.

Patch 28C.3.3 is the final editor-proportion correction after device validation. It **restores the original tool-tab sizing** (54dp, Captions 58dp) and lets the dock scroll only when a device genuinely needs it instead of shrinking every icon. The persistent timeline is back to a compact 136dp viewport, while the shared playhead height follows only the visible lane stack. The extra vertical space returns to Preview; aspect-ratio/transform/export semantics are unchanged.

---

# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `BRAND.md`, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.28.9 / versionCode 37 / project schema v20**.
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

Latest milestone: **Patch 28D — Export UI Overhaul**; Editor visual polish is parked for later review at owner request.


## Latest UI patch — 28C.3.3
Persistent multi-track timeline: Video/Audio/Text always visible; empty Audio/Text lanes launch real add flows; Overlay/Captions/Effects appear with content; ruler and shared playhead sync to the main timeline viewport. Version 0.28.5 / code 33; schema v20 unchanged. Patch 28C.3.1 follows with layout-only recovery at version 0.28.6 / code 34; Patch 28C.3.2 aligns the left-rail/right-canvas structure at version 0.28.7 / code 35; Patch 28C.3.3 restores original tool-tab size and tightens the persistent timeline at version 0.28.8 / code 36.
