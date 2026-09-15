# Vedito Major Patch 20 — Real Audio Mix / Export Parity / Reliability

Version: **0.20.0** (`versionCode 20`)  
Package: **`com.vedito.app`**  
Project persistence schema: **v18** (unchanged; Patch 20 adds no saved fields)

## Major Patch 20
This is the larger combined audio/export milestone after Patch 19. It turns the previously silent AAC foundation into a real audible export path while keeping the existing editor model intact.

### Real export audio
- Main-video source audio is decoded and mixed into the MP4.
- Independent Vedito audio clips are decoded and mixed on their timeline positions.
- Audio-track **volume / mute / fade-in / fade-out** are honored during export.
- Multiple overlapping audio clips are mixed to stereo before AAC encoding.
- Reverse/freeze main-video source sound stays muted, matching the current preview policy.
- Speed-changed forward source audio uses a lightweight overlap-add time stretcher to keep timeline sync and reduce pitch shift.
- Overlay-video audio remains intentionally muted, matching current preview ownership.

### Export parity + reliability
- New Android-free `AudioMixPlan` owns audio layer timing/gain rules rather than UI code.
- `PcmMediaDecoder` decodes supported Android media audio streams to seekable normalized PCM.
- `OfflineAudioMixer` renders deterministic stereo chunks for the AAC encoder.
- Video + audio encoding now progress together instead of adding a silent AAC track after video.
- Safer destination truncation, bounded encoder/mux startup buffering, monotonic PTS normalization, cancellation checks through audio decode/mix, encoder stall guards and partial-output cleanup.
- Existing visual export path remains unchanged: timing/keyframes/stabilization/mask/chroma/color/effects/transitions/overlay/text/captions still use the canonical composition state.

## Current limits
- The frame compositor still uses correctness-first `MediaMetadataRetriever` + software Canvas before the GLES/H.264 encoder surface. Long/high-resolution projects still need the future production decoder/GPU compositor.
- The lightweight speed-audio time stretcher prioritizes sync and practical mobile cost; extreme speed edits can sound less clean than a dedicated studio-grade algorithm.
- H.265, 2K/4K, user FPS/bitrate controls, recovery/resume and thermal/storage preflight remain future work.

## Acceptance path
1. Import a video that has audible source sound.
2. Add one or more audio clips; change volume, mute and fades.
3. Export 720p first, then 1080p.
4. Verify source-video sound and audio tracks are audible and synchronized.
5. Verify muted/faded audio behaves like preview.
6. Test a speed-changed forward clip and confirm audio stays aligned to the clip duration.
7. Cancel an export during audio preparation and during frame rendering; confirm no broken partial MP4 remains.
8. Reopen the saved project and confirm existing Patch 1–19 state is intact.

No workflow `.yml/.yaml` files are included in this patch ZIP.
