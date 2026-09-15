# Vedito AI Handoff

Before editing this repo, read **`PROJECT_CONTEXT.md`** then **`WORKPLAN.md`**.

Current state: **Patch 11 / v0.11.0 / schema v11**. Native Android/Kotlin, package `com.vedito.app`. Patch 11 added renderer-independent timed text layers, manual text editing, timeline move/trim, text z-order, style/transform controls, preview composition, persistence and undo/redo.

Do not rewrite the native foundation, do not copy Cutrim/CapCut source/assets, do not put workflow YAML inside patch ZIPs, and do not create preview-only state that cannot later be consumed by the deterministic export compositor.

Next planned milestone: **Patch 12 — Caption/Subtitles Foundation (caption segments + SRT import/export)**, unless the owner changes priority.
