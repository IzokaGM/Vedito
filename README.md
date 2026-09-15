# Vedito Patch 10 — Visual Overlay / PIP Tracks

Version: **0.10.0** · schema **v10** · package **`com.vedito.app`**

## Added
- Independent timed visual overlay clips above the base video timeline.
- Image and video overlay import through the Android system photo picker.
- Multi-layer z-order with Layer − / Layer + controls.
- Overlay timeline selection, drag-to-move, left/right edge trim.
- Shared transform controls for the selected overlay: scale, position, rotate, flip, opacity, crop and fit/fill.
- Image PIP preview plus muted video PIP preview synchronized to the project playhead.
- Renderer-independent `OverlayComposition` / `OverlayLayer` model intended to be reused by the later export compositor.
- Overlay persistence, schema migration, autosave/reopen and undo/redo.
- Overlay clips survive base-video timeline edits as long as they remain inside project duration; invalid tails are normalized.
- Editor controls now live in a vertically scrollable lower panel so added tracks do not clip on shorter phones.

## Intentional limits
- Video overlay audio is muted in preview. Use/extract audio as a separate audio track when needed; production overlay-audio routing belongs in the later audio/render engine.
- Overlay clips currently use normal 1× forward source time only; overlay speed/reverse/keyframes are later milestones.
- Overlay preview uses native Views/MediaPlayer as a foundation. The production export compositor will consume the same renderer-independent composition state, not screenshot the preview UI.
- At most three layer lanes are visually separated in the compact overlay strip; z-order itself is not limited to three layers.

## Acceptance path
1. Open an existing project.
2. Add an image or video overlay.
3. Move and trim it in the overlay strip.
4. Change layer order when two overlays overlap.
5. Select an overlay and use Scale / Move / Rotate / Flip / Opacity / Crop / Fit controls.
6. Scrub and play through the overlay interval.
7. Undo/redo an overlay edit.
8. Close and reopen the project; overlay timing, transform and layer order must persist.

GitHub Actions remains the build verifier. This ZIP intentionally contains **no `.yml` / `.yaml` files**.
