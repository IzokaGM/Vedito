# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.18.0 / schema v18**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 18 adds `ColorGradeSpec`, deterministic `ColorGradeEngine`, a native RenderEffect color-preview pipeline, and renderer-independent `FrameComposition`/`FrameCompositionBuilder`. Main-clip exposure/contrast/saturation/temperature/tint/fade now persist and support undo/redo. Preview chroma + color no longer fight over one RenderEffect; on API 33+ they chain through the same preview pipeline.

Critical architecture rule: future export must consume the same canonical clip/color/composition state rather than recreate preview-only math. `FrameCompositionBuilder` is deliberately Android-free and is the bridge toward the off-screen GPU compositor.

Important limits:
- HSL/curves/wheels/LUT UI and math are not completed.
- Final GPU/off-screen renderer/export is not completed.
- API 31+ is required for live RenderEffect color preview; chroma live shader remains API 33+.

Next planned milestone: **Patch 19 — Production Render / Export Engine Foundation** unless device testing exposes a Patch 18 regression first.
