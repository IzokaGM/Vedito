# Vedito Patch 13 — Text / Caption Expansion Foundation

Native Android/Kotlin patch for `com.vedito.app`.

## Included
- Free-text reusable presets: Classic, Title, Minimal, Impact, Lower Third.
- Shared renderer-independent font family state: Sans, Serif, Mono, Rounded.
- Text shadow + letter-spacing-ready style state.
- Deterministic basic text animation state and preview: None, Fade, Pop, Slide Up.
- Caption font selector and the same deterministic animation model.
- Caption presets expanded with Yellow and Soft styles.
- Undo/redo and project persistence cover all new text/caption state.
- Schema v13 migration defaults keep older projects readable.
- Version `0.13.0` / versionCode `13`.

## Architecture rule
`TextAnimationSpec` + `TextMotion` are renderer-independent. Future export must evaluate the same animation state rather than recreate timing rules inside Android Views.

## Explicitly not included yet
- Imported custom font files / online font packs.
- Per-word/karaoke caption timing.
- Auto-caption speech recognition.
- Advanced keyframed text animation graphs.
- Production export compositor.

## Acceptance path
1. Open a project with video.
2. Add text and cycle Preset / Font / Anim / Shadow.
3. Scrub/play through the text in/out points and verify animation is deterministic.
4. Add/select a caption; cycle Style, Font and Anim.
5. Save/leave/reopen project and verify all states persist.
6. Undo/redo style/font/animation edits.

Workflow YAML is intentionally not included in this ZIP.
