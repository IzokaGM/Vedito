# Vedito Patch 28C.3.3 — Preview + Timeline Final Polish

Version: **0.28.8** (`versionCode 36`)  
Project persistence schema: **v20**

## What this patch changes
- Restores the **original 54dp/58dp bottom tool-tab sizing** from the pre-28C.3.2 editor instead of shrinking eight tabs into weighted cells.
- Keeps all 8 tools and uses a horizontal dock only when the device width genuinely needs it; icons/labels are no longer reduced just to force-fit.
- Returns the persistent timeline viewport to **136dp**, giving the preview more room while keeping Video + Audio + Text visible.
- Bounds the shared playhead to the actual visible track stack instead of drawing through unused timeline space. Optional Overlay/Captions/Effects extend the playhead only when those lanes exist.
- Preserves aspect-ratio-safe preview fitting and canonical transform/export behavior; the patch only improves available preview space and editor proportions.

## Engine compatibility
- UI/layout-only patch. No project-model/persistence changes; schema remains **v20**.
- No render/export semantic changes; recovery salt remains **`vedito-render-p26-r1`**.
- Video/Audio/Text lanes still consume the same canonical project collections and existing edit behavior.

## Validation note
Full Android build remains the GitHub Actions gate because the local environment cannot download the Gradle 9.6 distribution. Local validation covers XML parsing, resource/binding ID checks, Kotlin structural checks, patch overlay application and ZIP integrity.

## Next UI milestone
Device screenshot validation first. **Do not start Patch 28D** until the Editor is visually approved.
