# Vedito Patch 25 — Advanced Color / LUT / Curves Foundation

Version: **0.25.0** (`versionCode 25`)  
Project persistence schema: **v19**

## What Patch 25 adds
- Canonical per-main-clip RGB tone-curve state: Master/Red/Green/Blue, each with five deterministic anchors.
- Global HSL adjustment state: hue, saturation and luminance.
- Built-in LUT-look foundation with Cinema, Teal+Orange, Film and Clean presets plus intensity.
- Existing exposure/contrast/saturation/temperature/tint/fade stays intact and is evaluated first.
- Canonical render order is now: legacy matrix → RGB curves → HSL → LUT look.
- API 33+ preview uses one RuntimeShader color/chroma pipeline so advanced color and chroma share the same ordering as export.
- Encoder GLES source graph evaluates the same curves/HSL/LUT state for deterministic export.
- CPU export fallback evaluates the full advanced color pipeline when Grain or another unsupported GPU post path forces software composition.
- Color toolbar exposes curve preset cycling, HSL controls, LUT preset cycling and LUT intensity while preserving undo/redo.
- Project persistence stores all advanced color state in schema v19; older saved projects load with neutral defaults.
- Patch 24 recovery fingerprint salt is bumped so old checkpoints can never be reused after the renderer semantics change.

## Current limits
- Curve editing UI is preset-driven in this foundation patch; the canonical model already stores independent Master/R/G/B curve anchors for a future graph editor.
- LUT support is currently Vedito built-in deterministic looks. External `.cube` LUT import is not included yet.
- Advanced live preview requires API 33+ RuntimeShader. API 31–32 retain the legacy color-matrix preview; export still renders the saved advanced color state deterministically.
- Zero-copy decoder-to-GPU source transport is still pending.
- Export recovery remains cache-backed rather than a persistent foreground/WorkManager job.

## Validation
Android-free Kotlin core compilation passed locally with `kotlinc`, including the Patch 25 color model/engine/GPU planning/recovery fingerprint path. A full local Android assemble could not run because this environment could not download the Gradle distribution, so Android/AGSL/GLES compilation and device validation remain the GitHub Actions/device gate.
