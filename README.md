# Vedito Patch 28C.1 — Editor Visual Correction

Version: **0.28.3** (`versionCode 31`)  
Project persistence schema: **v20**

## What Patch 28C.1 changes
- Corrects the first Patch 28C device result toward the approved premium editor mockup without changing the editing engine.
- Edit context is now split into **Clip / Transform / Mask / Track** subtools; only one control row is visible at a time.
- **Clip** is the default edit surface and prioritizes Add Video, Split, Duplicate, Replace, Undo, Redo and Delete.
- Removes the stacked `KF + Mask + Tracking` button dump seen in the device screenshot.
- Makes the main timeline materially shorter and only shows the auxiliary lane when the selected category needs it.
- Compacts the bottom tool dock and replaces the heavy selected outline with a subtle cyan surface state.
- Tightens generic tool pills and replaces internal Mask/Tracking shorthand with clearer labels.

## Engine compatibility
- No project-model or persistence changes; schema remains **v20**.
- No render/export semantic changes; recovery salt remains **`vedito-render-p26-r1`**.
- Patch 27 foreground export, Patch 28A branding and Patch 28B Home behavior are unchanged.

## Validation note
The local environment cannot download Gradle 9.6, so the full Android compile remains a GitHub Actions gate. XML/resource/binding/static patch checks are performed locally before packaging.

## Next UI milestone
**Patch 28D — Export UI Overhaul** after this correction is green on device.
