# Vedito Patch 22 — High-Resolution / Codec Controls & Export Preflight Foundation

Version: **0.22.0** (`versionCode 22`)  
Package: **`com.vedito.app`**  
Project persistence schema: **v18** (unchanged; export settings are session-only)

## What Patch 22 adds

### Export profiles
- Resolution choices: **720p / 1080p / 1440p (2K) / 2160p (4K)**.
- Frame-rate choices: **24 / 30 / 60 fps**.
- Codec choices: **H.264/AVC** or **H.265/HEVC**.
- Deterministic bitrate planning scales by resolution, FPS and codec efficiency.
- HEVC uses a lower target bitrate than H.264 for the same profile.
- Export plan exposes a conservative estimated MP4 size before the file picker opens.

### Device-aware encoder preflight
- New `ExportCapabilityProbe` enumerates Android MediaCodec encoders for the selected MIME type.
- Verifies encoder-surface support, output size and size+frame-rate capability.
- Prefers hardware acceleration when available.
- Clamps requested bitrate into the chosen encoder's advertised range rather than sending an invalid configuration.
- Blocks unsupported profiles before the user chooses a save destination and explains whether the limitation is codec, resolution or FPS.
- Re-runs capability selection immediately before export so a stale UI result is never trusted as the final gate.

### Export engine integration
- `VideoExportEngine` now creates the exact preflight-selected encoder by codec name.
- MediaFormat video MIME follows the selected AVC/HEVC codec.
- Existing Patch 20 real AAC mixer and Patch 21 hybrid GPU/post pipeline remain intact.
- Main high-resolution video decode can scale up to 3840 px for 4K output; overlay/static-layer decode remains bounded to reduce memory pressure.

### Export UX
- Native stepped flow: resolution → FPS → codec → preflight summary → save destination.
- Preflight shows dimensions, FPS, codec, effective video bitrate, AAC bitrate, estimated file size and chosen encoder.
- Warns for 4K, 60 fps, HEVC compatibility and existing compositor/audio fallback cases.
- Generated filename includes profile, FPS and codec.

## Current limits
- 4K/60 support is **device-dependent**; unsupported encoder profiles are blocked by preflight.
- Preflight estimates output size but cannot guarantee free space at an arbitrary SAF/cloud destination before that destination is chosen.
- Long 4K exports can still thermal-throttle or hit memory pressure even when MediaCodec advertises the profile.
- Chroma/color base processing and reverse-video random access are still not the final all-GPU production path.
- No manual arbitrary bitrate slider yet; Patch 22 uses safe deterministic bitrate presets.
- Export resume/recovery is still pending.

## Acceptance path
1. Open Export and verify 720p/1080p/1440p/4K, 24/30/60fps and H.264/HEVC choices appear.
2. Select 1080p/30/H.264 and confirm preflight names an encoder and allows save.
3. Export and verify audible AAC audio + visual parity from Patch 20/21 remain intact.
4. Try HEVC; supported devices should export H.265 MP4, unsupported devices should be blocked before destination selection.
5. Try 4K and/or 60fps; verify device capability determines whether the profile can continue.
6. Cancel a long export; verify no broken partial MP4 remains.
7. Reopen the project; verify all Patch 1–21 persisted state is intact.

No workflow `.yml/.yaml` files are included in this patch ZIP.
