# Vedito Patch 19 — Production Render / Export Engine Foundation

Version: **0.19.0** (`versionCode 19`)  
Package: **`com.vedito.app`**  
Project persistence schema: **v18** (unchanged)

## Added
- Real MP4 video export from the editor header.
- Export presets: **720p / 1080p · 30 fps**.
- Android-free `ExportPlanner` for deterministic canvas size, duration and frame count.
- Off-screen `SoftwareFrameComposer` consuming the same canonical project state used by preview architecture.
- Export composition covers:
  - trim / uniform speed / reverse / freeze source-time mapping,
  - main clip transform + keyframes + stabilization,
  - mask + chroma + color grade,
  - timed effects + current transitions,
  - image/video overlay layers,
  - free text + text motion,
  - captions + caption motion.
- H.264 `MediaCodec` surface encoder through EGL/GLES bitmap blit.
- AAC track + `MediaMuxer` MP4 writer.
- SAF `CreateDocument` destination; no storage permission needed.
- Export progress, cancel, failure reporting, partial-output cleanup and screen-awake handling.

## Important Patch 19 limit
The AAC track is structurally valid but **silent in this foundation patch**. Source-video sound and Vedito audio-track mixing are not silently dropped without warning: the export UI shows this limitation before the user continues. Patch 20 is reserved for the real export audio mixer and reliability pass.

The frame extractor/compositor is intentionally correctness-first (`MediaMetadataRetriever` + software Canvas, then GLES → MediaCodec). It is not yet the final high-throughput decoder/GPU path for hour-long projects.

## Acceptance path
1. Import a short video and make trim/speed/transform/color edits.
2. Add an image/video overlay, text or caption if desired.
3. Tap **EXPORT** in the editor header.
4. Choose 720p or 1080p, acknowledge the Patch 19 audio warning, choose an MP4 destination.
5. Confirm progress updates, cancel works, and failed/cancelled partial files are removed.
6. Let export finish and open the MP4.
7. Verify duration/source timing, canvas ratio, transforms/keyframes, overlays/text/captions and current effects visually match the preview intent.
8. Stress a short multi-clip project before moving to long-project export tests.

No workflow `.yml/.yaml` files are included in this patch ZIP.
