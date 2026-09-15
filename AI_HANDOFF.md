# Vedito AI Handoff

Read **`PROJECT_CONTEXT.md` first**, then **`WORKPLAN.md`** before changing code.

Current repository milestone: **Patch 08 / Vedito 0.8.0 — Core Visual Transform Engine**.

Locked rules:
- Native Android/Kotlin only unless the owner explicitly reverses that decision.
- Package: `com.vedito.app`.
- Do not copy Cutrim source; it was only a build-style reference.
- Patch ZIP must extract at repo root and must never contain `.yml`/`.yaml`. Workflows are delivered separately only when required.
- Do not create fake buttons/features. Preview, persistence, undo/redo and future export semantics must stay consistent.
- Ask for confirmation before building a new patch unless the owner already explicitly said to start.

Current capability includes multi-clip timeline, trim/split/delete/reorder/duplicate/replace, undo/redo, zoom/snapping, audio lanes/waveforms/editing/fades/extract, and Patch 08 per-clip visual transform + project canvas state.

Next planned milestone in `WORKPLAN.md`: **Patch 09 — Speed / Freeze / Reverse Foundation**.
