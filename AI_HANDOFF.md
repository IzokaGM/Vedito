# Vedito — AI Handoff

Before changing anything, read these files in order:
1. `PROJECT_CONTEXT.md`
2. `WORKPLAN.md`
3. `README.md`

Treat them as canonical unless the owner explicitly changes direction.

Current release in this patch: **0.7.0 / Patch 07 — Audio Editing Pro Foundation**.
Next planned milestone after device verification: **Patch 08 — Core Visual Transform Engine**.

Non-negotiable delivery rules:
- Native Android/Kotlin; do not return to React Native.
- Package stays `com.vedito.app`.
- Never copy Cutrim source; it was only a reference for simple standalone APK delivery.
- Patch ZIP must extract at repo root and contain no `.yml`/`.yaml`.
- Workflow files, if changed, are delivered separately.
- Do not call UI-only placeholders "finished". Real behavior, persistence, undo/redo where relevant, preview integration, and a future deterministic export path matter.
- Avoid unrelated rewrites and unnecessary APK bloat.
- Ask for confirmation before building a new patch unless the owner already explicitly told you to start it.
