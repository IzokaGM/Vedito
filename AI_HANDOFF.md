# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.20.0 / versionCode 20 / project schema v18**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Major Patch 20 replaces Patch 19's silent AAC foundation with a real deterministic audio renderer. `AudioMixPlanner` owns renderer-independent source-video/audio-track timing and gain rules. `PcmMediaDecoder` converts supported Android audio streams to seekable normalized stereo PCM. `OfflineAudioMixer` mixes main-video source sound plus independent audio clips with volume/mute/fades and lightweight overlap-add handling for forward speed changes. `VideoExportEngine` feeds mixed PCM to AAC while video frames are being rendered, then muxes both tracks through the hardened `Mp4MuxSink`.

Preview/export ownership rules now intentionally match: main forward clips own their source sound, reverse/freeze source sound is muted, independent audio tracks can overlap and honor volume/mute/fades, and overlay-video sound stays muted. Do not add a second audio timing model in UI/export code; extend `AudioMixPlan`/`AudioMixMath`.

Critical visual architecture rule remains: do not bypass `ClipTimeMap`, `FrameCompositionBuilder`, `OverlayComposition`, `TextComposition`, `CaptionComposition`, shared color/keyframe/tracking models or the existing project state when improving export. Replace the software frame backend later, not the project-state math.

Current major remaining export bottleneck is frame decode/composition throughput: `MediaMetadataRetriever` + software Canvas is correctness-first and not the final long-project GPU path.

Next planned milestone: **Patch 21 — Production Decoder / GPU Compositor Performance Foundation** unless device/CI testing exposes a Patch 20 regression first.
