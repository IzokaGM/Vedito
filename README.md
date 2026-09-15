# Vedito Patch 23 — Full GPU Source Graph / Reverse Decode Cache Foundation

Version: **0.23.0** (`versionCode 23`)  
Package: **`com.vedito.app`**  
Project persistence schema: **v18** (unchanged; Patch 23 adds render execution infrastructure only)

## What Patch 23 adds

### Main-source GPU graph
- Main decoded video can now be uploaded as a source texture instead of first being fully rasterized into the CPU base plane.
- Encoder GLES shader resolves the canonical main-source chain from `FrameCompositionBuilder`:
  - crop + fit/fill,
  - evaluated transform/keyframes/stabilization,
  - flip/rotation/position/scale/opacity,
  - chroma key + spill suppression,
  - Patch 18 color matrix,
  - rectangle/ellipse mask + invert/feather,
  - timed GPU effects/transitions,
  - then overlay/text/caption alpha composition.
- Existing Canvas base-plane path stays as a correctness fallback for unsupported post stacks such as Grain.
- Source graph uses `GpuSourceGraphPlanner`, an Android-free geometry/color contract, rather than duplicating editor timing/state in the GL layer.

### Decoder ownership / bitmap pressure
- `VideoFrameSourcePool.FrameLease` now pins a streaming decoder bitmap until the encoder surface has consumed it.
- LRU stream trimming skips pinned entries, preventing a main-source frame from being recycled while multiple video overlays are composed.
- `HybridComposedFrame` owns the retained source lease and releases it immediately after the GLES draw.
- Reused GL texture storage from Patch 21 remains intact.

### Reverse decode cache foundation
- Reverse clips now prefer `ReverseVideoFrameDecoder` instead of one `MediaMetadataRetriever` random access per exported frame.
- Reverse decoder seeks to a previous sync frame, decodes forward once and keeps a bounded tail of decoded frames for descending reverse requests.
- `ReverseDecodeCachePlanner` chooses cache capacity from output dimensions/FPS and a bounded memory budget.
- 1080p can retain several decoded frames; 4K automatically shrinks cache depth to protect RAM.
- Unsupported decoder/surface paths automatically fall back to the existing random-access retriever.
- Export progress reports whether frames are coming from stream decode, reverse cache or fallback decode.

### Existing production contracts preserved
- Patch 20 real source/audio-track stereo AAC mixer remains unchanged.
- Patch 22 720p/1080p/1440p/4K, 24/30/60fps, AVC/HEVC preflight and exact encoder selection remain intact.
- Project schema stays v18; no duplicate saved renderer state is introduced.

## Current limits
- Main-source shader execution is GPU-backed, but MediaCodec `ImageReader` decoding still converts YUV frames into reusable RGB bitmaps before texture upload. A future OES/SurfaceTexture path can remove that final CPU conversion.
- Video overlays, text and captions are still rasterized into the reusable CPU overlay plane before final GLES composition.
- Grain remains a CPU fallback to preserve the existing look.
- Reverse cache is memory-bounded and GOP-oriented; very high-resolution sources may retain only one or a few frames, so speedup is device/content dependent.
- Reverse/freeze source audio remains muted under the current preview/export policy.
- Export recovery/resume, destination-space recovery and deeper thermal adaptation are still pending.

## Acceptance path
1. Export a normal 1080p/30 project with crop/transform/color/chroma/mask and confirm visual parity.
2. Add Warm/Cool/Dream/Vignette and Fade/Flash/Wipe; export should stay on the GPU source/post path.
3. Add Grain and verify export still succeeds through the CPU correctness fallback.
4. Export a reverse clip and verify progress reports reverse-cache decode rather than only random-access fallback on a compatible device.
5. Export a project with several video overlays; verify no recycled-bitmap crash or corrupt main-source frame occurs.
6. Try 4K/60 and HEVC; Patch 22 preflight must still gate unsupported profiles before destination selection.
7. Cancel a long export; verify no broken partial MP4 remains.
8. Reopen the project; all persisted Patch 1–22 state must remain unchanged.

No workflow `.yml/.yaml` files are included in this patch ZIP.
