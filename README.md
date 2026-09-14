# Vedito Patch 04 — Multi-Clip Timeline Core

Native Android patch for `com.vedito.app`.

## Included
- Multi-video projects: add up to 12 videos from the editor.
- Proper project media model: reusable media assets are separated from timeline clips.
- Multi-source preview playback switches between source videos automatically.
- Multi-source thumbnail extraction across the complete timeline.
- Long-press a clip and drag it across the timeline to reorder.
- Ripple timeline behavior: trim/delete/reorder/add operations automatically shift following clips.
- Split/delete/trim continue to work across multi-video projects.
- Autosave now persists media assets, clip order, trims, playhead and selection.
- Patch 03 and earlier single-source projects migrate automatically on load.
- Existing system-bar / navigation-bar inset fixes remain included.

## Patch rules
- No workflow files are included in this ZIP.
- Package remains `com.vedito.app`.
- Existing two-workflow GitHub Actions chain stays unchanged.

## Acceptance path
Open existing project → Add video → add 2+ source videos → scrub across clips → play across clip boundary → trim → split → delete → long-hold/drag to reorder → leave editor → reopen project → clip order and edits remain.
