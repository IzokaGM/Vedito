# Vedito Patch 17 — Motion Tracking / Stabilization Foundation

Version: **0.17.0** (`versionCode 17`)  
Package: **`com.vedito.app`**

## Added
- Renderer-independent `MotionTrackSpec` + timed `TrackingPoint` model on each main clip.
- Manual motion tracking workflow: enable tracking, add/remove anchors, jump previous/next point, and drag the reticle directly on preview.
- Deterministic linear interpolation between tracking anchors via `MotionTrackingEngine`.
- Stabilization state with enable/disable, strength presets and auto-crop option.
- Real preview stabilization foundation: inverse translation from the tracking path plus deterministic auto-crop scale.
- Tracking data follows trim, split, speed change and reverse semantics; freeze bakes the current stabilized transform and clears live tracking.
- Undo/redo + project save/reopen support.
- Project persistence schema **v17**.

## Current foundation limits
- Tracking is **manual-anchor first**. There is no automatic detector/optical-flow pass yet; future assistance must write the same `TrackingPoint` model.
- Stabilization is a deterministic translation foundation, not final production gyro/optical-flow stabilization.
- Tracking currently belongs to main clips. Overlay/object attachment and detector-assisted tracking are later milestones.
- Final export must consume the same `MotionTrackingEngine` state instead of implementing separate timing math.

## Acceptance path
1. Open a project and select the active main clip.
2. Turn **Track On**. A reticle appears in preview.
3. Drag the reticle on the object at the current playhead; move the playhead and add/drag more anchors.
4. Use previous/next point navigation and confirm the reticle interpolates while scrubbing.
5. Enable **Stabilize**, cycle strength and auto-crop, then scrub/play to confirm framing compensation.
6. Undo/redo anchor and stabilization edits.
7. Split, trim, change speed and reverse the tracked clip; confirm tracking state remains valid.
8. Close/reopen the project and confirm anchors/stabilization persist.

No workflow `.yml/.yaml` files are included in this patch ZIP.
