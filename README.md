# Vedito Patch 28C — Editor UI Overhaul

Version: **0.28.2** (`versionCode 30`)  
Project persistence schema: **v20**

## What Patch 28C changes
- Rebuilds the Editor shell around the approved premium mockup direction while preserving the existing editing engine.
- Clean centered project header + gradient Export action.
- Preview becomes the visual focus instead of sharing space with a long control dump.
- Timeline is promoted into a dedicated stage with video always visible and one contextual auxiliary lane for Text, Audio, Effects, Overlay or Captions.
- Adds a fixed bottom tool dock: Edit, Speed, Text, Audio, Effects, Color, Overlay and Captions.
- Only the selected tool opens its relevant context drawer, so advanced controls stay available without scrolling through unrelated sections.
- Tapping text/overlay/caption directly in preview switches to its matching tool mode.
- Functional accents remain semantic: video/overlay blue, text/captions purple, audio green, effects amber, brand/export cyan-blue.

## Engine compatibility
- No project-model or persistence changes; schema remains **v20**.
- No render/export semantic changes; recovery salt remains **`vedito-render-p26-r1`**.
- Patch 27 foreground export and Patch 28B Home behavior are unchanged.

## Next UI milestone
**Patch 28D — Export UI Overhaul** following the approved mockup direction.
