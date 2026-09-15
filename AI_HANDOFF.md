# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.14.0 / schema v14**.
- Do not copy Cutrim source; it was only a standalone-APK reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs must contain repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already said to start.

Patch 14 adds a dedicated timed effect track, lightweight real preview effects (Warm/Cool/Vignette/Dream/Grain), effect intensity, clip-boundary Fade-Black/Flash/Wipe transitions, renderer-independent `EffectComposition`, persistence and undo/redo. True dual-source cross-dissolve and shader-grade color tools remain future work.

Next planned milestone: **Patch 15 — Keyframe Engine Foundation**. Keep keyframe interpolation renderer-independent so preview and future export consume the same state.
