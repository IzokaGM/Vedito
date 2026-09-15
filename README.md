# Vedito Patch 14 — Effects / Transitions Foundation

Native Android/Kotlin patch for `com.vedito.app`.

## Included
- Dedicated timed video-effect track with move + trim editing.
- Real preview effects: Warm, Cool, Vignette, Dream and Grain.
- Per-effect intensity control.
- Clip-boundary transitions: None, Fade Black, Flash White and Wipe.
- Transition duration cycle: 300 / 500 / 800 / 1200 ms.
- Renderer-independent `EffectComposition` transition/effect timing state for future export reuse.
- Transition-safe split/duplicate behavior so old outgoing transitions stay on the final resulting piece.
- Undo/redo, autosave and reopen persistence for effect/transition state.
- Project schema v14.
- Version `0.14.0` / versionCode `14`.

## Architecture rule
Effect timing and transition envelopes live in `core/effect`, not Android Views. Preview Views consume that state. The future export compositor must consume the same state.

## Explicit limits
- Current effects are lightweight native compositing overlays, not the final GPU shader/color-grading engine.
- Fade/Flash/Wipe are real transition previews, but true dual-decoder cross-dissolve is intentionally deferred to the production compositor.
- No LUT/HSL/curves/masks/chroma/keyframes yet.
- No production export compositor yet.

## Acceptance path
1. Open a multi-clip project.
2. Add a timed effect and move/trim it on the FX timeline.
3. Cycle effect kind and intensity; scrub/play and verify preview changes only while the effect is active.
4. Select a non-final video clip and cycle Transition + duration.
5. Play/scrub through the clip boundary and verify the transition envelope.
6. Undo/redo effect and transition edits.
7. Leave/reopen project and verify state persists.

Workflow YAML is intentionally not included in this ZIP.
