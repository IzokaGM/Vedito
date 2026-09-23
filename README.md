# Vedito Patch 28D — Export UI Overhaul

Version **0.28.9** (`versionCode 37`), schema **v20**.

Export now uses branded settings, review, and progress panels in the approved dark multi-accent design. The settings offer only supported resolution/FPS/codec/AAC quality. Video bitrate is calculated, not independently selectable; output size is an estimate. Device encoder and recovery preflight remain mandatory before the real MP4 document picker. The progress ring uses the foreground-service task store; Run in background closes UI without stopping export, and Cancel invokes the existing service. Elapsed time is factual and ETA is not invented.

Patch contains only repo-root source/resource/doc updates; no `.yml/.yaml` and no workflow changes. Apply on top of the green **28C.3.3** repository; do not apply to the old 0.24 base. Full Android build and device UX validation remain GitHub Actions/device gates. Editor visual review is parked by owner and was not modified by 28D.

---

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
