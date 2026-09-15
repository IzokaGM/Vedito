# Vedito 0.7.0 — Patch 07: Audio Editing Pro Foundation

Native Android/Kotlin patch. Extract this ZIP at repository root.

## Patch 07
- Cached real PCM waveform extraction for audio/video sources with audio tracks.
- Waveforms rendered inside audio clips.
- Drag audio clips horizontally with snapping.
- Trim audio from left/right edges.
- Split selected audio clip at playhead.
- Fade-in / fade-out model, persistence and preview gain.
- Extract audio track from the selected video clip into an editable audio clip.
- Audio edits participate in undo/redo and autosave.
- Project schema upgraded to v7 with backward-compatible defaults.
- Project context/workplan updated for AI handoff.

## Device acceptance test
1. Open a multi-video project.
2. Add audio and wait for waveform to appear.
3. Drag audio; verify it snaps to playhead/video cut boundaries.
4. Drag left/right audio edges; verify trim is preserved after reopen.
5. Put playhead inside selected audio and tap **Audio split**.
6. Cycle **Fade in** / **Fade out** and play through the fades.
7. Select a video clip and tap **Extract**; its audio should appear at that clip's timeline position.
8. Undo/redo each edit, leave editor, reopen project, and verify state.

## Delivery rule
This patch intentionally contains no `.yml` or `.yaml` workflow files.
