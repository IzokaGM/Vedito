# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.23.0 / versionCode 23 / project schema v18**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 23 moves the canonical **main video source graph** onto the encoder GLES shader when the active post stack is GPU-compatible. `GpuSourceGraphPlanner` is Android-free and resolves the same `FrameCompositionBuilder` state for crop/fit/transform, chroma, color, opacity and mask. `CodecInputSurface` must consume that plan; do not create preview-only or shader-only transform/color state.

`HybridComposedFrame` may retain a raw main-source bitmap plus a `FrameLease`. The lease must remain alive until `CodecInputSurface.draw(...)` returns and must then be closed. `VideoFrameSourcePool` pins streaming entries while a lease is active so overlay decoder churn cannot recycle the main frame prematurely.

Reverse clips now prefer `ReverseVideoFrameDecoder`: previous-sync seek → forward MediaCodec decode → bounded decoded tail cache → descending reverse lookup. `ReverseDecodeCachePlanner` owns memory/timing policy. `MediaMetadataRetriever` remains the automatic compatibility fallback; do not delete it until reverse MediaCodec behavior has been proven across devices.

Patch 20 `AudioMixPlan`/`OfflineAudioMixer` remains the real stereo AAC path. Patch 22 `ExportPlanner` + `ExportCapabilityProbe` remains authoritative for resolution/FPS/AVC/HEVC/bitrate/encoder preflight. Do not bypass `ClipTimeMap`, `FrameCompositionBuilder`, shared keyframe/tracking/color/mask/chroma state, or persistence.

Important Patch 23 limit: the source graph is GPU evaluated **after** decoder YUV→RGB bitmap conversion. A later OES/SurfaceTexture decoder path may remove that CPU conversion without changing `GpuSourceGraphPlanner` semantics. Grain and unsupported post stacks keep the CPU correctness fallback.

Project schema stays v18.

Next planned milestone: **Patch 24 — Export Recovery / Long-Project Hardening Foundation** unless CI/device testing exposes a Patch 23 regression first.
