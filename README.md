# Vedito Patch 28C.3.1 — Editor Layout Recovery

Version: **0.28.6** (`versionCode 34`)  
Project persistence schema: **v20**

## What Patch 28C.3.1 fixes
- Emergency UI recovery for the Patch 28C.3 device regression where the persistent timeline consumed the remaining editor height and collapsed the preview/tool dock.
- Gives the persistent multi-track timeline a **bounded 136dp viewport** instead of an unconstrained `wrap_content` measurement path.
- Keeps the ruler fixed and puts Video/Audio/Text plus optional Overlay/Captions/Effects lanes inside an internal vertical scroll area.
- Keeps the shared playhead over the **timeline viewport only**, so it no longer participates in measuring the whole remaining screen.
- Restores the intended editor hierarchy: **top bar → preview → transport → compact multi-track timeline → tool dock → optional context drawer**.
- Video + Audio + Text remain persistent; optional lanes still appear from real project content.

## Engine compatibility
- Layout-only recovery. No project-model/persistence changes; schema remains **v20**.
- No render/export semantic changes; recovery salt remains **`vedito-render-p26-r1`**.
- Existing Patch 28C.3 timeline data/views remain canonical; this patch only constrains their viewport and measurement behavior.

## Validation note
Full Android build remains the GitHub Actions gate because the local environment cannot download the Gradle 9.6 distribution. Local validation covers XML parsing, ID/resource checks, overlay application and ZIP integrity.

## Next UI milestone
Device screenshot validation first. **Do not start Patch 28D** until the Editor layout is visually approved.

### Patch 28C.3 — Persistent Multi-Track Timeline
The editor now keeps Video, Audio and Text tracks visible together, adds real empty-lane add affordances, dynamically shows Overlay/Captions/Effects tracks when used, and synchronizes all lanes to one ruler/playhead.

