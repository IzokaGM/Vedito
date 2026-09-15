# Vedito Patch 12 — Caption / Subtitles Foundation

Version: **0.12.0** · schema **v12** · package **`com.vedito.app`**

## Added
- Dedicated subtitle/caption segment model separate from free-form text layers.
- Native caption preview layer with three safe presets: **BOXED / CLEAN / LARGE**.
- Caption timeline with segment selection, drag-to-move and left/right trim.
- Add/edit/delete caption segments manually.
- Split selected caption at the playhead.
- Batch shift all captions by **−0.25s / +0.25s** with project-bound clamping.
- **SRT import** into the caption track.
- **SRT export** from the current caption track.
- Renderer-independent `CaptionComposition`, `CaptionTimelineEditor` and `SrtCodec` for future deterministic export reuse.
- Caption edits participate in undo/redo, autosave and project reopen.
- Existing schema v11 projects load with an empty caption track; schema is now v12.

## Important behavior
- Importing an SRT replaces the current caption track in one undoable operation.
- Imported cues outside project duration are safely clamped/dropped by caption normalization.
- Captions use absolute project time and remain independent of manual text layers.
- Auto-caption speech recognition, word-level timing/karaoke, TTS and caption animation are intentionally not included yet.

## Acceptance path
1. Open a project and add a caption at the playhead.
2. Drag/trim it, edit its text and cycle caption style.
3. Split a caption with the playhead inside the segment.
4. Import a valid `.srt` file and verify cues appear at correct times.
5. Shift the full caption track ±0.25s and verify no cue exits project bounds.
6. Export SRT and re-import it; timing/text should round-trip correctly.
7. Undo/redo add/edit/import/shift/split/delete operations.
8. Close and reopen; caption state must persist.

Static validation in this patch includes pure Kotlin caption/SRT round-trip tests, core compile checks and XML validation. GitHub Actions remains the full Android build verifier. This ZIP intentionally contains **no `.yml` / `.yaml` files**.
