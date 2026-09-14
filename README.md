# Vedito Patch 05 — Timeline Pro Foundation

Native Android/Kotlin patch for `com.vedito.app`.

## Included

- Bounded Undo / Redo history for destructive timeline edits
- Pinch-to-zoom timeline (1×–8×)
- Auto-follow viewport while playback moves
- Edge auto-pan while scrubbing/reordering on a zoomed timeline
- Clip-edge snapping + haptic snap feedback
- Frame-quantized scrubbing and trim gestures
- Frame-rate metadata probing with safe 30 fps fallback
- Frame-style timecode display
- Duplicate selected clip
- Replace selected clip media while preserving timeline position/order and as much trim duration as the replacement allows
- Zoom + viewport persisted with the project
- Runtime `TimelineIndex` prefix cache / binary lookup for smoother large timelines
- Thumbnail density scales with timeline zoom (capped to avoid runaway memory)
- Existing multi-clip add/reorder/trim/split/delete/autosave behavior retained

## Compatibility

- Projects created by previous Vedito patches still load.
- Project schema is bumped to v5; missing FPS/zoom data receives safe defaults.
- Undo/redo history is intentionally session-only. The latest committed project state is persisted; reopening starts a fresh history stack.

## Acceptance checks targeted by this patch

1. Add many clips and keep playback/scrub responsive.
2. Split, delete, duplicate, trim, reorder, add and replace clips; Undo/Redo should reverse/restore committed edits.
3. Pinch the timeline to zoom; the viewport should follow the playhead during playback.
4. Scrubbing near clip boundaries should snap to boundaries.
5. Close/reopen the project; clips, selection, playhead, zoom and viewport should persist.

## Build

No workflow/YAML is included in this ZIP. Keep using the existing GitHub Actions build workflow.
