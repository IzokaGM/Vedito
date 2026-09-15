# Vedito AI Handoff

Before editing this repo, read **`PROJECT_CONTEXT.md`** then **`WORKPLAN.md`**.

Current state: **Patch 10 / v0.10.0 / schema v10**. Native Android/Kotlin, package `com.vedito.app`. The current milestone added independent image/video visual overlay/PIP tracks, z-order, timed overlay editing, shared transforms, preview composition and persistence.

Do not rewrite the native foundation, do not copy Cutrim/CapCut source/assets, do not put workflow YAML inside patch ZIPs, and do not create preview-only state that cannot later be consumed by the deterministic export compositor.

Next planned milestone: **Patch 11 — Text & Caption Track Foundation**, unless the owner changes priority.
