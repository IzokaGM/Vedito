# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.19.0 / project schema v18**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 19 adds the first real off-screen export path. `ExportPlanner` is Android-free; `SoftwareFrameComposer` consumes the canonical project/composition state; `CodecInputSurface` feeds composed frames through EGL/GLES into a surface-input H.264 encoder; `VideoExportEngine` muxes video with a valid AAC track into MP4 and exposes progress/cancel/error callbacks. The editor now has an EXPORT action with 720p/1080p presets and SAF destination creation.

Critical architecture rule: do not bypass `ClipTimeMap`, `FrameCompositionBuilder`, `OverlayComposition`, `TextComposition`, `CaptionComposition` or the shared color/keyframe/tracking models when improving export. Replace the software fallback with a faster GPU/decoder backend later, not with duplicate project-state math.

Important Patch 19 limit: the AAC track is currently silent. Audible source-video/audio-track mixing is the next milestone and the UI explicitly warns before export. `MediaMetadataRetriever` frame extraction is also a correctness-first fallback, not final long-project performance architecture.

Next planned milestone: **Patch 20 — Export Audio Mixer / Reliability Foundation** unless device testing exposes a Patch 19 regression first.
