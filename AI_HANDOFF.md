# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.16.0 / schema v16**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs must contain repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 16 adds main-video `MaskSpec` + `ChromaKeySpec` as renderer-independent clip state. Rectangle/ellipse masks have size, position, feather and invert controls with a real native occlusion preview. Chroma key has enabled/key-color/tolerance/softness/spill; live RuntimeShader keying is available on API 33+, while the same state persists on API 26–32 for the future shared GPU/export compositor. All edits participate in undo/redo and project persistence.

Important Patch 16 limits:
- Main clips only for mask/chroma in this milestone; overlay/PIP application is pending.
- Feather preview is lightweight/native, not the final GPU export implementation.
- No eyedropper yet.

Next planned milestone: **Patch 17 — Motion Tracking / Stabilization Foundation**. Keep tracking/stabilization state renderer-independent and owned by the project model so preview and export can consume the same data.
