# Vedito Patch 11 — Text & Caption Track Foundation

Version: **0.11.0** · schema **v11** · package **`com.vedito.app`**

## Added
- Independent timed text clips above the base video and visual-overlay composition.
- Add/edit multiline text with the native Android dialog.
- Text timeline selection, drag-to-move and left/right edge trim.
- Persistent text layer order with Layer − / Layer +.
- Real preview composition using text, style and transform state.
- Text transform controls: scale, position, rotation and opacity.
- Text style controls: font size, color presets, background presets, bold and alignment.
- Renderer-independent `TextComposition` / `TextLayer` state for future export reuse.
- `TextTimelineEditor` normalization/timing/layer rules kept outside Android UI code.
- Text edits participate in undo/redo, autosave and project reopen.
- Existing schema v10 projects load with an empty text track; schema is now v11.
- Home recent-project metadata can show text-layer count.

## Intentional limits
- This patch is the manual text/caption foundation. SRT import/export, subtitle segmentation, auto captions, karaoke/per-word timing and text animation are later milestones.
- Text preview uses native Android `TextView` nodes, but future deterministic export must consume the renderer-independent text model rather than screenshotting preview Views.
- Text layers currently render above visual PIP layers. Unified cross-type z-order is deferred to the production compositor/layer stack milestone.
- Font-family packs, downloadable fonts and rich per-span styling are not included yet.

## Acceptance path
1. Open an existing project and add a text layer.
2. Edit the text content and confirm multiline text renders in preview.
3. Drag the text clip in the text timeline and trim both edges.
4. Change size/color/background/bold/alignment and transform position/scale/rotation/opacity.
5. Add a second overlapping text layer and change Layer − / Layer + order.
6. Scrub/play across the text intervals and verify timed visibility.
7. Undo/redo text content, timing, style and transform edits.
8. Close and reopen the project; text state must persist.

GitHub Actions remains the full Android build verifier. This ZIP intentionally contains **no `.yml` / `.yaml` files**.
