# Vedito Patch 28C.2 — Editor Density Cleanup

Version: **0.28.4** (`versionCode 32`)  
Project persistence schema: **v20**

## What Patch 28C.2 changes
- Targets the latest device screenshot directly: cleaner CapCut-class density without copying CapCut assets or proprietary UI.
- Editor now opens with the **context drawer collapsed**. Tap a tool to open its controls; tap the active tool again to collapse them.
- Removes the large bordered timeline card and reduces the timeline to a slimmer source-name + thumbnail lane.
- Removes technical `speed / scale / rotation / opacity` metadata from the main timeline header.
- Moves **Undo / Redo** into persistent compact transport controls.
- Converts common Clip actions to icon-first **Add / Split / Copy / Replace / Delete** controls instead of pill-heavy text buttons.
- Removes extra strokes/card framing from preview, tool dock and context drawer; hierarchy comes from spacing and dark-surface contrast.
- Auxiliary Text/Audio/Effects/Overlay/Captions lanes only consume space while their tool context is expanded.

## Engine compatibility
- UI-only patch. No project-model/persistence changes; schema remains **v20**.
- No render/export semantic changes; recovery salt remains **`vedito-render-p26-r1`**.
- Patch 27 foreground export and Patch 28A/28B branding/Home behavior are unchanged.

## Validation note
Full Android build remains the GitHub Actions gate because the local environment cannot download the Gradle 9.6 distribution. Local checks cover XML parsing, unique/binding IDs, resource references, changed Kotlin structure, patch overlay and ZIP integrity.

## Next UI milestone
**Patch 28D — Export UI Overhaul** after Patch 28C.3 is green and visually approved on device.


### Patch 28C.3 — Persistent Multi-Track Timeline
The editor now keeps Video, Audio and Text tracks visible together, adds real empty-lane add affordances, dynamically shows Overlay/Captions/Effects tracks when used, and synchronizes all lanes to one ruler/playhead.
