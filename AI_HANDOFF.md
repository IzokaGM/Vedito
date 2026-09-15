# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.13.0 / schema v13**.
- Do not copy Cutrim source; it was only a standalone-APK reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs must contain repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already said to start.

Patch 13 adds reusable text presets, renderer-independent font-family keys, deterministic basic text/caption animation (`TextMotion`), expanded caption presets and persistence/undo coverage.

Next planned milestone: **Patch 14 — Effects / Transitions Foundation**. Build renderer-independent state first so preview and future export use the same timing/parameter model.
