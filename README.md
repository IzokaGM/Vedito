# Vedito Patch 18 — Advanced Color / GPU Compositor Foundation

Version: **0.18.0** (`versionCode 18`)  
Package: **`com.vedito.app`**

## Added
- Per-main-clip renderer-independent `ColorGradeSpec`.
- Color controls: exposure, contrast, saturation, temperature, tint and fade + reset.
- Shared deterministic `ColorGradeEngine` normalization and 4×5 color-matrix generation for preview/export reuse.
- Live hardware color preview through Android `RenderEffect` on API 31+.
- Chroma and color preview now share one render pipeline; API 33+ chroma can be chained with the color grade instead of overwriting it.
- New renderer-independent `FrameComposition` / `FrameCompositionBuilder` resolves the canonical main-frame state: source time, keyframed + stabilized transform, mask, chroma, color, active timed effects and transition stage order.
- Timeline sanitization normalizes color state.
- Undo/redo + autosave/reopen for all color controls.
- Project persistence schema **v18**.

## GPU compositor scope
This patch establishes the **data flow and render-pass contract**, not the final off-screen export renderer. `FrameCompositionBuilder` contains no Android/GPU API so the future export engine can consume exactly the same resolved frame state as preview.

Current ordered stages are source → transform → mask → chroma → color → timed effects → transition, with inactive stages omitted deterministically.

## Current limits
- HSL channels, curves, color wheels and LUT import are still pending; `ColorGradeSpec`/`ColorGradeEngine` are the base they should extend.
- Live color RenderEffect preview requires API 31+. State still saves correctly on older supported devices.
- Chroma live shader preview remains API 33+; its renderer-independent state exists on all supported devices.
- The existing native FX overlay preview remains separate until the production GPU compositor replaces it.
- No final video export in Patch 18.

## Acceptance path
1. Open a project and select an active main video clip.
2. Change exposure/contrast/saturation/temperature/tint/fade and confirm preview reacts on API 31+.
3. Enable chroma on API 33+ and confirm color adjustment remains active at the same time.
4. Scrub/play, split and duplicate the clip; confirm color state remains tied to the clip.
5. Undo/redo color edits.
6. Close/reopen project and confirm all color values persist.
7. Reset color and confirm the clip returns to neutral state.

No workflow `.yml/.yaml` files are included in this patch ZIP.
