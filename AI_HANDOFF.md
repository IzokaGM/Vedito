# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.17.0 / schema v17**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 17 adds renderer-independent `MotionTrackSpec`, `TrackingPoint`, `StabilizationSpec` and `MotionTrackingEngine`. Main clips now support a manual anchor workflow with a draggable preview reticle, point navigation, interpolation, persistence and undo/redo. Stabilization preview uses the same tracking data and deterministically applies inverse translation + optional auto-crop scale. Tracking timing is preserved through split/trim/uniform speed/reverse; freeze bakes the evaluated stabilized transform.

Important limits:
- No automatic object detector/optical-flow tracking yet.
- No production-grade gyro/flow stabilization yet.
- Main clips only; overlay attachment/tracking comes later.
- Export must reuse the same tracking/stabilization model/evaluator.

Next planned milestone: **Patch 18 — Advanced Color / GPU Compositor Foundation** unless device testing exposes a Patch 17 regression first.
