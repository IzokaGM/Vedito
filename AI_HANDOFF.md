# Vedito AI Handoff

Read `PROJECT_CONTEXT.md` first, then `WORKPLAN.md`.

Current locked state:
- Vedito native Android/Kotlin, package `com.vedito.app`.
- Current patch: **0.22.0 / versionCode 22 / project schema v18**.
- Do not copy Cutrim source; it was only a standalone-APK/build-style reference.
- Do not reintroduce React Native/Metro.
- Patch ZIPs use repo-root paths and **must not contain `.yml/.yaml`**.
- New patch requires owner confirmation unless owner already explicitly said to start.

Patch 22 extends the production export contract without adding saved project state. `ExportSettings` now owns resolution preset, 24/30/60fps and AVC/HEVC choice. `ExportPlanner` deterministically resolves output dimensions, bitrate, frame count and estimated output bytes.

`ExportCapabilityProbe` is the authoritative Android MediaCodec preflight for a requested `ExportPlan`: it checks surface input, exact size/rate support, prefers hardware encoders and clamps bitrate to the selected encoder's advertised range. `VideoExportEngine` MUST re-run this selection before export and MUST create the selected encoder by codec name; do not bypass the preflight by going back to a blind `createEncoderByType(...)` call.

Patch 21 execution contracts remain: `FrameAccessPlanner` + `StreamingVideoFrameDecoder` + `VideoFrameSourcePool` own frame acquisition; reverse remains random-access fallback. Main high-resolution decode may reach 3840px for 4K output while overlay/static decoding remains deliberately bounded. `SoftwareFrameComposer` + `CodecInputSurface` still own hybrid CPU/GPU frame composition. Patch 20 `AudioMixPlan`/`OfflineAudioMixer` remains the real stereo AAC path.

Do not bypass `ClipTimeMap`, `FrameCompositionBuilder`, `OverlayComposition`, `TextComposition`, `CaptionComposition`, `AudioMixPlan`, shared color/keyframe/tracking models or persisted project state when extending export. Project schema stays v18.

Next planned milestone: **Patch 23 — Full GPU Source Graph / Reverse Decode Cache Foundation** unless CI/device testing exposes a Patch 22 regression first.
