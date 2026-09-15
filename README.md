# Vedito Patch 15 — Keyframe Engine Foundation

Native Android/Kotlin patch for `com.vedito.app`.

## Included
- Renderer-independent transform keyframe model for main video clips and visual overlays/PIP.
- Keyframed properties: scale, position X/Y, rotation and opacity.
- Real interpolation during scrub/playback using the same `KeyframeEngine` state future export will consume.
- Easing modes: Linear, Ease In, Ease Out, Ease In/Out and Hold.
- Visual toolbar controls to add/remove keyframes, jump previous/next, cycle easing and clear/bake keyframes.
- Once a clip/layer has keyframes, scale/move/rotate/opacity edits at a new playhead position automatically create/update the keyframe at that local time.
- Timeline-safe keyframe handling for clip speed changes, source trim, split and freeze insertion.
- Overlay left/right trim keeps local keyframe timing valid.
- Undo/redo, autosave and reopen persistence.
- Project schema v15.
- Version `0.15.0` / versionCode `15`.

## Architecture rule
Keyframes are stored as local timeline-time tracks. Android Views do not own animation truth. `KeyframeEngine` evaluates the transform for preview now; the production compositor must use the same model/evaluator later.

## Current keyframe limits
- Foundation currently animates scale, X/Y position, rotation and opacity only.
- Crop, flip and fit/fill remain static transform state in this patch.
- No graph editor/custom Bezier handles yet; easing uses deterministic presets.
- Text/effect parameter keyframes are not wired yet, but this engine is intended to expand to them.
- No production export compositor yet.

## Acceptance path
1. Open a project and select a main clip or overlay.
2. Move playhead inside it and tap `◇ Add KF`.
3. Move playhead later, then change Scale/position/Rotate/Opacity; Vedito should create/update the second keyframe automatically.
4. Scrub/play between both points and verify smooth animation.
5. Tap Ease to cycle interpolation modes and verify motion changes.
6. Use `◀ KF` / `KF ▶` to navigate keyframes.
7. Split/trim/change speed and verify surviving animation remains valid.
8. Undo/redo, leave/reopen project and verify keyframes persist.

Workflow YAML is intentionally not included in this ZIP.
