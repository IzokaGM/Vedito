# Vedito Patch 03 — Real Timeline Core

Native Android patch for `com.vedito.app`.

## Included
- Correct edge-to-edge system bar + cutout insets on Home and Editor.
- Real video thumbnail extraction from the imported source.
- Timeline playhead synchronized with preview playback.
- Live scrub-to-preview seeking.
- Clip selection.
- Drag left/right clip edges to trim.
- Split at playhead.
- Delete selected clip while preserving the remaining timeline.
- Edited timeline playback skips removed source ranges.
- Project autosave for clips, trims, split/delete state, selection and playhead.
- Existing 0.2 projects migrate automatically when reopened.

## Patch rules
- No workflow files are included in this ZIP.
- Package remains `com.vedito.app`.
- Existing two-workflow GitHub Actions chain can stay unchanged.

## Acceptance path
Import video → scrub → trim → split → delete → leave editor → reopen recent project → edited state remains.
