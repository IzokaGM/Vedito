# Patch 01 — Product Foundation

## Scope
- React Native 0.87.1 source foundation
- Stable React Navigation 7 native stack
- Dark premium design system
- Home screen
- Projects screen
- Editor shell
- Functional mock playback clock
- Timeline zoom controls
- Tool selection state
- In-memory project creation

## Intentionally NOT included yet
- Media picker/import
- Native playback/decoder
- Real timeline clip model
- Persistent project database
- Export/render engine
- Effects/audio/text engines

Those are intentionally staged so the visible UI does not get mistaken for finished editor functionality.

## Expected repository paths
Copy this ZIP to repository root so `App.tsx`, `src/`, `package.json`, etc. sit at the root.

## Bootstrap note
This patch contains the application layer. Native Android/iOS project files should be generated from the official React Native 0.87.1 template. A separate GitHub Actions bootstrap workflow can do that on the CI runner for phone-only development.
