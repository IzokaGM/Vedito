# Vedito Patch 27 — Foreground Export / Release Hardening Foundation

Version: **0.27.0** (`versionCode 27`)  
Project persistence schema: **v20**

## What Patch 27 adds
- Long MP4 exports are owned by an Android foreground service instead of `EditorActivity`.
- Android 15+ uses the dedicated `mediaProcessing` foreground-service type; Android 14 compatibility uses `dataSync` for local file processing/export.
- Persistent task/progress state reconnects the editor after backgrounding, Activity recreation and process recreation.
- Export notification shows live progress, deep-links to the owning project and exposes Cancel.
- `START_REDELIVER_INTENT` can recreate the service and re-enter the existing Patch 24 checkpoint engine, reusing finalized segments/AAC when valid.
- Android foreground-service timeout cancels the in-flight segment, preserves finalized checkpoints, records a resumable timeout state and stops cleanly before ANR.
- Foreground-start failures become terminal task state instead of leaving a stuck progress UI.
- Export no longer depends on keeping the editor screen awake.
- Project schema remains v20 and recovery salt remains `vedito-render-p26-r1`; Patch 27 changes execution ownership, not render semantics.

## Current limits
- Force-stop, cache eviction, revoked SAF access or aggressive vendor process killing can still interrupt work; recovery cache is best-effort, not durable storage.
- No WorkManager/vendor-specific fallback path yet.
- Android 15+ `mediaProcessing` foreground services are subject to the platform background time quota.
- Voice-over recording, NR/voice enhancement, pitch tools, beat detection, auto captions/TTS/karaoke and downloadable font packs remain pending.

## Validation
- Manifest XML parses and declares matching foreground-service types/permissions.
- Patch diff/static checks cover service lifecycle, persistent export state, notification actions, editor reconnect and unchanged recovery render salt/schema.
- Full Android Gradle compilation could not run locally because the Gradle 9.6 wrapper distribution is unavailable offline; GitHub Actions remains the final Android build/device gate.
