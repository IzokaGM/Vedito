# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.21.0 / versionCode 21 / project schema v18**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 21 changes export execution, not project ownership. `FrameAccessPlanner` selects STREAMING/HOLD/RANDOM access. `StreamingVideoFrameDecoder` + `VideoFrameSourcePool` prefer bounded `MediaExtractor + MediaCodec + ImageReader` decode for forward/freeze main video and overlay video, with automatic `MediaMetadataRetriever` fallback and an LRU cap on active stream decoders. Reverse remains random-access for correctness.

`SoftwareFrameComposer` now emits reusable base/overlay planes. `GpuPostProcessPlanner` routes supported Warm/Cool/Dream/Vignette effects and Fade/Flash/Wipe transitions to `CodecInputSurface`; Grain remains CPU fallback. `CodecInputSurface` reuses texture storage and performs GPU post-processing + final overlay alpha composition directly on the H.264 encoder surface.

Do not bypass `ClipTimeMap`, `FrameCompositionBuilder`, `OverlayComposition`, `TextComposition`, `CaptionComposition`, `AudioMixPlan`, shared color/keyframe/tracking models or persisted project state when extending export. Patch 21 intentionally keeps schema v18.

Next planned milestone: **Patch 22 — High-Resolution / Codec Controls & Export Preflight Foundation** unless CI/device testing exposes a Patch 21 regression first.
