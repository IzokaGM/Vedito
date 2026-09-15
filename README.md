# Vedito Patch 16 — Masks / Chroma Foundation

Version: **0.16.0** (`versionCode 16`)  
Package: **`com.vedito.app`**

## Added
- Renderer-independent `MaskSpec` on every main video clip.
- Rectangle + ellipse masks, position, size, invert and feather state.
- Real native mask preview layer that occludes hidden main-video areas while keeping overlay/text layers above it.
- Renderer-independent `ChromaKeySpec` with enable, key color, tolerance, softness and spill controls.
- Real RuntimeShader chroma preview on Android API 33+; the same project state is preserved on older Android for the future export/GPU compositor.
- Mask/chroma edits are covered by undo/redo, duplicate/split/freeze semantics and project save/reopen.
- Project persistence schema **v16**.

## Current foundation limits
- Patch 16 controls masks/chroma for the **main video clip**. Overlay/PIP mask/chroma will reuse the same state model in a later visual-compositor pass.
- Mask feather is a lightweight native preview approximation; production export will use the deterministic GPU mask compositor.
- Chroma live preview uses Android RuntimeShader on API 33+. API 26–32 keep/edit/persist the parameters, but final production preview parity will arrive with the shared GPU compositor.
- No eyedropper color sampling yet; current key presets are green, blue and magenta.

## Acceptance path
1. Open/import a video project.
2. Select a main clip.
3. Cycle Mask Off → Rectangle → Ellipse and resize/move it.
4. Toggle invert and cycle feather.
5. Enable Chroma; cycle key color/tolerance/softness/spill.
6. Undo/redo several mask/chroma changes.
7. Split or duplicate the clip and confirm state follows the new clip.
8. Close/reopen the project and confirm the state persists.

No workflow `.yml/.yaml` files are included in this patch ZIP.
