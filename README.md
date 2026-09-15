# Vedito Patch 09 — Speed / Freeze / Reverse Foundation

Version: **0.9.0** (`versionCode 9`)
Package: **`com.vedito.app`**

## What changed
- Added renderer-independent `ClipTiming` state and `ClipTimeMap` source↔timeline mapping.
- Uniform speed presets: **0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×**.
- Timeline duration now reflects clip speed.
- Forward preview uses Android `MediaPlayer` playback speed.
- Reverse mode has a real seek-driven reverse preview foundation (source audio is muted while reversing).
- Freeze inserts a real **2-second hold clip at the playhead**, preserving the surrounding source pieces.
- Split, duplicate, reorder, delete and project persistence understand timing state.
- Reverse-aware split and trim mapping.
- Freeze clips can be split but intentionally do not expose source trim handles.
- Timing edits participate in undo/redo and autosave/reopen.
- Schema migrated to **v9**; older projects default to normal 1× forward timing.
- Extract-audio is disabled for retimed/reversed/freeze clips until retimed audio rendering exists, avoiding desync.

## Device acceptance test
1. Open an existing project and select a normal clip.
2. Change speed down/up; verify timeline width/duration and playback speed change together.
3. Save/leave/reopen; verify selected speed persists.
4. Toggle Reverse; verify timeline plays backward and reaches the next clip cleanly.
5. Pause/restart while reversing; verify playback resumes from the current playhead.
6. Put playhead inside a normal clip and tap Freeze; verify a 2s freeze clip is inserted at that exact point.
7. Play through normal → freeze → following clip and verify timeline/audio keep advancing.
8. Split/duplicate/delete/reorder speed/reverse/freeze clips and exercise undo/redo.
9. Reopen the project and verify all timing states remain intact.

## Important foundation note
Reverse preview is currently seek-driven rather than a production reverse decoder. That is deliberate: project state and deterministic time mapping are now correct, while the final reverse frames/audio will later be rendered by the production compositor/export engine.

## Delivery
This ZIP is intended to extract at repository root. It contains **no GitHub workflow YAML files**. Existing unzip/build workflows remain separate.
