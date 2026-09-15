# Vedito AI Handoff

Before editing this repo, read **`PROJECT_CONTEXT.md`** then **`WORKPLAN.md`**.

Current state: **Patch 12 / v0.12.0 / schema v12**. Native Android/Kotlin, package `com.vedito.app`. Patch 12 added a dedicated caption/subtitle track, manual segment editing, split, batch ±0.25s shift, caption-safe presets, SRT import/export, preview composition, persistence and undo/redo.

Do not rewrite the native foundation, do not copy Cutrim/CapCut source/assets, do not put workflow YAML inside patch ZIPs, and do not create preview-only state that cannot later be consumed by the deterministic export compositor.

Next planned milestone: **Patch 13 — Text / Caption Expansion Foundation**, unless the owner changes priority.
