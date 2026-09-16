# Vedito Patch 28C.3.2 — CapCut Layout Alignment

Version: **0.28.7** (`versionCode 35`)  
Project persistence schema: **v20**

## What this patch changes
- Aligns the Editor timeline density with the approved CapCut reference while keeping Vedito branding and engine behavior.
- Splits the timeline into a **112dp left utility rail** and a dedicated right track canvas.
- Utility rail uses real actions: **Add / Split / Copy**, plus Audio/Text tool entry points.
- Raises timeline viewport to **184dp**; Video is 44dp and persistent Audio/Text lanes are 26dp.
- Ruler and the single shared playhead live only on the track canvas.
- Optional Overlay/Captions/Effects lanes remain data-driven and scroll vertically when present.
- Replaces the horizontally clipped bottom tool strip with a fixed weighted 8-item dock so every tool stays visible.
- Context drawer remains collapsed by default and opens only when a tool is selected.

## Engine compatibility
- UI/layout-only patch. No project-model/persistence changes; schema remains **v20**.
- No render/export semantic changes; recovery salt remains **`vedito-render-p26-r1`**.
- Video/Audio/Text lanes still consume the same canonical project collections and existing edit behavior.

## Validation note
Full Android build remains the GitHub Actions gate because the local environment cannot download the Gradle 9.6 distribution. Local validation covers XML parsing, resource/binding ID checks, Kotlin structural checks, patch overlay application and ZIP integrity.

## Next UI milestone
Device screenshot validation first. **Do not start Patch 28D** until the Editor is visually approved.
