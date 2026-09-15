# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.15.0 / schema v15**.
- Do not copy Cutrim source; it was only a standalone-APK reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs must contain repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already said to start.

Patch 15 adds renderer-independent transform keyframes for main clips and overlays, deterministic interpolation/easing, real preview evaluation, keyframe navigation/edit controls, timeline-safe split/trim/speed behavior, persistence and undo/redo. The production export compositor must consume the same keyframe model/evaluator rather than reimplementing animation math.

Next planned milestone: **Patch 16 — Masks / Chroma Foundation**. Keep mask/chroma state renderer-independent and structured for the later GPU/export compositor.
