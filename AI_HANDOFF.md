# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.25.0 / versionCode 25 / project schema v19**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 24 makes production export recoverable without adding project JSON state. `ExportRecoveryPlanner` splits output on frame boundaries and fingerprints only render-relevant project/export state plus the exact encoder and a render-ABI salt. Any future patch that changes encoded visual/timing semantics must bump that recovery fingerprint revision or otherwise invalidate old checkpoints.

`ExportRecoveryStore` owns cache-backed finalized segment files and the full AAC checkpoint. Never treat `.part` files as resumable. Completed files are validated through `Mp4SegmentMerger` before reuse. Recovery files may disappear because Android cache is not durable storage; code must always tolerate a missing checkpoint and rerender it.

Video checkpoints are video-only MP4s. Audio is mixed/encoded once into one audio-only AAC/MP4 checkpoint. `Mp4SegmentMerger` performs final no-reencode remux. Do not encode AAC separately per video segment because repeated encoder priming/padding can create audible boundary errors.

Patch 24 preflight is resume-aware: completed checkpoint bytes reduce extra cache-space requirements, and a valid audio checkpoint removes the large temporary PCM working reserve. Destination storage is checked again immediately before final assembly. Thermal pressure is checked between checkpoints so expensive decoder/GPU state has already been released when backing off or failing safely.

Patch 25 extends the existing canonical color state rather than creating a parallel render model. `ColorGradeEngine` order is base matrix → Master/R/G/B five-anchor curves → global HSL → built-in LUT look. `GpuSourceGraphPlanner`, API 33+ `PreviewPlayer` RuntimeShader and the software export fallback consume the same saved state. Project schema is v19; missing fields from older projects normalize to neutral.

Important limits: no persistent foreground export service yet; process death can lose only the active segment, not finalized checkpoints. Android may clear app cache. Zero-copy OES/SurfaceTexture source decode is still pending.

Patch 25 changes encoded pixels, so `ExportRecoveryPlanner` render salt is `vedito-render-p25-r1`; never reuse older Patch 24 checkpoints across this renderer boundary. External `.cube` LUT import, manual graph curve UI, per-band HSL/color wheels and pre-API-33 advanced live preview remain pending.

Next planned milestone: **Patch 26 — Advanced Audio / Text Expansion Foundation** unless CI/device testing exposes a Patch 25 regression first.
