# Vedito AI Handoff

Before changing code, read in this order:
1. `PROJECT_CONTEXT.md`
2. `WORKPLAN.md`
3. `README.md`

Current version: **0.9.0** / schema **v9**.
Current milestone: **Patch 09 — Speed / Freeze / Reverse Foundation**.
Next planned milestone: **Patch 10 — Visual Overlay / PIP Tracks**.

Non-negotiable rules:
- Native Android/Kotlin, package `com.vedito.app`.
- Do not copy Cutrim/CapCut source/assets.
- Do not regress to React Native/Metro.
- Patch ZIP extracts at repo root and contains no `.yml/.yaml`.
- Workflow files, if needed, are delivered separately.
- Preserve project migrations and undo/redo semantics.
- `ClipTimeMap` is canonical timing math; future speed curves/export should extend/reuse it.
- No fake UI: controls must perform real behavior.
