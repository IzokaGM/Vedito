# Vedito Patch 24 — Export Recovery / Long-Project Hardening Foundation

Version: **0.24.0** (`versionCode 24`)  
Package: **`com.vedito.app`**  
Project persistence schema: **v18** (unchanged; Patch 24 adds export runtime/recovery infrastructure only)

## What Patch 24 adds

### Recoverable segmented video export
- Long video export is split on exact frame boundaries into independently finalized MP4 video checkpoints.
- Segment size adapts to output pressure:
  - standard profiles: ~18s,
  - heavy profiles: ~12s,
  - extreme/4K60 or very long profiles: ~8s.
- A cancelled, thermally interrupted or failed export keeps completed checkpoints in app cache.
- Re-running the **same render-relevant project state + export profile + exact encoder** reuses valid completed segments instead of starting from frame 0.
- Recovery fingerprint includes a Patch 24 render ABI salt so future renderer changes cannot accidentally reuse stale encoded pixels.
- Corrupt/incomplete `.part` files are removed; completed MP4 checkpoints are validated before reuse.

### Audio-safe checkpoint architecture
- Patch 20 real stereo mixer remains authoritative.
- Audio is decoded/mixed/encoded once as one full-length AAC checkpoint rather than restarting AAC at every video segment boundary.
- Final MP4 is assembled by remuxing all completed video segments plus the full AAC checkpoint — **no visual/audio re-encode during final assembly**.
- This avoids repeated AAC priming/padding at segment edges.

### Long-project storage / thermal hardening
- Export preflight now estimates recovery working storage in addition to final MP4 size.
- Resume preflight is cache-aware: existing reusable checkpoints reduce the extra free space required.
- Selected destination free space is checked when the provider exposes filesystem stats and checked again before final MP4 assembly.
- Memory-headroom warnings are surfaced before export.
- Thermal state is checked at checkpoint boundaries; severe heat triggers short cooling backoff, critical heat exits safely while preserving completed checkpoints.
- Decoder/GPU/compositor resources are recreated per video checkpoint, reducing long-run resource accumulation.
- Stale recovery sessions are pruned after seven days.
- SAF providers without `rwt` support use explicit destination truncation before final muxing to avoid stale tail bytes.

### Existing production contracts preserved
- Patch 23 GPU main-source graph + reverse MediaCodec cache remain intact.
- Patch 22 720p/1080p/1440p/4K, 24/30/60fps, AVC/HEVC capability preflight and exact encoder selection remain intact.
- Patch 20 source-video + overlapping audio-track AAC mix remains intact.
- Project JSON schema stays v18; recovery state lives only in app cache and never mutates project persistence.

## Current limits
- Recovery cache is stored under Android app cache; Android or the user may clear it.
- Retry currently asks the user to choose a destination again; Patch 24 does not run export as a persistent foreground service/WorkManager job.
- If Android kills the process during an active segment, that in-progress segment is lost, but previously finalized checkpoints remain reusable.
- A critical thermal state stops safely rather than silently lowering resolution/codec settings.
- Zero-copy OES/SurfaceTexture decoder-to-GPU upload is still pending; Patch 23 YUV→RGB bitmap upload remains the source path.
- Grain and unsupported post stacks still use the correctness-first CPU fallback.

## Acceptance path
1. Export a multi-minute 1080p/30 project and confirm progress reports video checkpoints, AAC checkpoint and final assembly.
2. Cancel after at least one video checkpoint; rerun the exact same profile and confirm preflight shows resumable segments.
3. Change a render-relevant edit or export codec/profile and confirm old checkpoints are **not** reused.
4. Corrupt/delete one cached checkpoint and confirm Vedito rerenders that segment rather than merging it.
5. Test a low-storage device/provider; export must block early when measurable space is insufficient and must not output a broken MP4.
6. Test a hot device: severe heat may back off; critical heat must preserve completed checkpoints and return a recoverable error.
7. Export reverse clips, overlays, masks/chroma/color/effects/text/captions and confirm Patch 23 visual path remains intact.
8. Export overlapping audio/source-video sound and confirm Patch 20 volume/mute/fade timing remains intact.
9. Reopen the project; all persisted Patch 1–23 state must remain unchanged.

No workflow `.yml/.yaml` files are included in this patch ZIP.
