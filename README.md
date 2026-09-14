# Vedito 0.6.0 — Patch 06 Audio Core

Native Android/Kotlin video editor foundation for `com.vedito.app`.

## Patch 06 adds
- Persistent audio asset + audio clip model (schema v6).
- Import multiple audio files at the current playhead.
- Overlapping audio clips displayed as lanes.
- Native multi-audio preview synchronized to video timeline.
- Select audio clip, volume ±10%, mute/unmute, delete.
- Audio state participates in undo/redo.
- Audio state autosaves and reopens with the project.
- Existing Patch 05 projects migrate with an empty audio timeline.

## Important project handoff files
- `PROJECT_CONTEXT.md` — architecture, locked decisions, current state.
- `WORKPLAN.md` — CapCut-class master roadmap and next patch direction.
- `AI_HANDOFF.md` — short instruction for continuing with another AI.

## Device test
1. Open a project.
2. Put playhead somewhere in the video.
3. Tap **Add audio** and select one or more audio files.
4. Play the project; imported audio should follow the timeline.
5. Tap an audio block to select it.
6. Test **Vol −**, **Vol +**, **Mute/Unmute**, **Delete**.
7. Test Undo/Redo.
8. Close and reopen the project; audio state should remain.

This ZIP intentionally contains no GitHub Actions YML/YAML files.
