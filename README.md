# Vedito — Clean Native Rewrite (0.2)

This patch replaces the active app architecture with an independent native Android implementation.

## Active stack
- Native Android / Kotlin
- Android Views + ViewBinding
- Activity Result Photo Picker for video import
- Framework MediaPlayer + TextureView preview
- Custom native timeline scrubber
- Local project index
- Package: `com.vedito.app`

## Important
- No React Native
- No Metro/Hermes runtime
- No Cutrim source is used as the application base
- Known files from the earlier migration bootstrap are overwritten with inert placeholders so an overlay unzip cannot compile the old implementation.
- No workflow/YAML file is included in this ZIP.

## Working features in this patch
1. Create a project by choosing a video.
2. Save/reopen recent projects.
3. Native video preview.
4. Play/pause.
5. Timeline drag/scrub and seek.
6. Autosave recent project timestamp.

The editing engine (trim/split/multitrack/export) is intentionally not faked in this foundation and will be added in subsequent patches.
