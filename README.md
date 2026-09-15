# Vedito Patch 21 — Production Decoder / GPU Compositor Performance Foundation

Version: **0.21.0** (`versionCode 21`)  
Package: **`com.vedito.app`**  
Project persistence schema: **v18** (unchanged; Patch 21 adds no saved project fields)

## Patch 21
Patch 21 replaces the expensive frame-by-frame export extraction path for normal forward playback with a bounded streaming decode pipeline and moves supported timed post-processing onto the encoder GPU surface.

### Streaming video decode
- New `StreamingVideoFrameDecoder` uses `MediaExtractor + MediaCodec` with a bounded `ImageReader` surface.
- Normal forward main clips and video overlays prefer sequential hardware decode.
- Freeze clips reuse the held decoded frame instead of extracting it again for every output frame.
- Reverse clips intentionally use the correctness-first random-access fallback until a dedicated reverse frame cache/decoder lands.
- Unsupported decoder/surface combinations automatically fall back to `MediaMetadataRetriever` rather than aborting the export.
- Decoder pool is LRU-bounded to avoid pinning an unbounded number of hardware decoders in long/multi-overlay projects.
- Decoded YUV frames reuse one RGB bitmap per active stream, reducing frame-allocation churn.

### Hybrid GPU compositor
- `SoftwareFrameComposer` now emits reusable **base + overlay planes** instead of one repeatedly allocated final frame.
- Warm/Cool/Dream/Vignette timed effects and Fade/Flash/Wipe transitions can run in the encoder EGL/GLES shader.
- Grain stays on the CPU fallback in Patch 21 to preserve the existing sparse-grain look.
- Mask occlusion, overlay media, text and captions remain above the post-processed base plane, preserving the existing visual layer order.
- `CodecInputSurface` keeps GL texture storage alive and uses sub-image uploads after the first frame instead of reallocating texture storage every frame.
- The final overlay plane is alpha-composited in GLES immediately before the H.264 encoder surface swap.

### Ownership / compatibility
- `FrameCompositionBuilder` remains the canonical visual state resolver.
- `AudioMixPlan` / `OfflineAudioMixer` remain the canonical Patch 20 audio path.
- No duplicate preview/export project state was added.
- Project schema stays **v18**.

## Current limits
- Chroma-key pixel removal and base color matrix are still CPU-backed in the export compositor; a future full source-texture GPU graph can move these stages without changing the project model.
- Reverse video still uses random-access extraction and can be slower on long/high-resolution clips.
- MediaCodec → YUV ImageReader support varies by device; Vedito falls back automatically when the streaming surface cannot be negotiated.
- H.265, 2K/4K, custom FPS/bitrate, export resume/recovery and full thermal/storage preflight remain future work.

## Acceptance path
1. Export a normal forward project at 720p and 1080p; verify image + audio sync.
2. Test a 0.5× and 2× forward clip; verify repeated/skipped source frames remain visually aligned.
3. Add a freeze clip; verify the held frame remains stable.
4. Add a reverse clip; verify correctness even though that section can export slower.
5. Add Warm/Cool/Dream/Vignette effects and Fade/Flash/Wipe transitions; compare preview/export behavior.
6. Add overlays, text/captions and a mask; verify they remain above the base effect layer.
7. Cancel during a long export; verify no broken partial MP4 remains.
8. Reopen the project; verify Patch 1–20 state is intact.

No workflow `.yml/.yaml` files are included in this patch ZIP.
