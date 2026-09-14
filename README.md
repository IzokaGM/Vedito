# Vedito Native Migration

This patch replaces the React Native runtime with a native Android foundation.

- App: Vedito
- Package: `com.vedito.app`
- Native Android / Kotlin
- No Metro server
- No JavaScript runtime required on device
- SAF video picker with persisted read access
- Native video preview, seek and play/pause
- Minimal dependency footprint

The repository may still contain old React Native source files after ZIP extraction because the unzip workflow overlays files rather than deleting them. They are not used by the native Gradle build and can be cleaned in a later patch.
