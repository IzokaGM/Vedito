# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.24.0 / versionCode 24 / project schema v18**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 24 makes production export recoverable without adding project JSON state. `ExportRecoveryPlanner` splits output on frame boundaries and fingerprints only render-relevant project/export state plus the exact encoder and a render-ABI salt. Any future patch that changes encoded visual/timing semantics must bump that recovery fingerprint revision or otherwise invalidate old checkpoints.

`ExportRecoveryStore` owns cache-backed finalized segment files and the full AAC checkpoint. Never treat `.part` files as resumable. Completed files are validated through `Mp4SegmentMerger` before reuse. Recovery files may disappear because Android cache is not durable storage; code must always tolerate a missing checkpoint and rerender it.

Video checkpoints are video-only MP4s. Audio is mixed/encoded once into one audio-only AAC/MP4 checkpoint. `Mp4SegmentMerger` performs final no-reencode remux. Do not encode AAC separately per video segment because repeated encoder priming/padding can create audible boundary errors.

Patch 24 preflight is resume-aware: completed checkpoint bytes reduce extra cache-space requirements, and a valid audio checkpoint removes the large temporary PCM working reserve. Destination storage is checked again immediately before final assembly. Thermal pressure is checked between checkpoints so expensive decoder/GPU state has already been released when backing off or failing safely.

Patch 23 `GpuSourceGraphPlanner`/reverse cache, Patch 22 `ExportPlanner`/`ExportCapabilityProbe`, Patch 20 `AudioMixPlan`/`OfflineAudioMixer`, `ClipTimeMap` and `FrameCompositionBuilder` remain authoritative. Project schema stays v18.

Important limits: no persistent foreground export service yet; process death can lose only the active segment, not finalized checkpoints. Android may clear app cache. Zero-copy OES/SurfaceTexture source decode is still pending.

Next planned milestone: **Patch 25 — Advanced Color / LUT / Curves Foundation** unless CI/device testing exposes a Patch 24 regression first.
