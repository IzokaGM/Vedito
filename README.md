# Vedito Patch 08 — Core Visual Transform Engine

Version: **0.8.0 / versionCode 8**  
Package: **`com.vedito.app`**

## What this patch adds
- Per-clip visual transform model with deterministic persistence.
- Scale and X/Y position.
- 90° rotation, horizontal flip and vertical flip.
- Opacity.
- Edge crop state and real preview cropping.
- Fit / Fill behavior.
- Project canvas ratios: Source, 9:16, 16:9, 1:1 and 4:5.
- Canvas backgrounds: Black, Charcoal, White and Violet.
- Source width/height metadata probing.
- Real-time `TextureView` transform preview.
- Undo/redo for clip transforms and canvas changes.
- Save/reopen migration through project schema **v8**.
- Compact horizontally-scrollable visual toolbar to avoid making the editor vertically heavy.
- Renderer-independent `VisualTransformMath` so the future export compositor consumes the same transform state.

## Visual toolbar behavior
Select a video clip, then use the horizontal toolbar above the video timeline. Crop edge buttons advance that edge in 5% steps and wrap after 40%; `Crop reset` clears all crop edges. Opacity cycles 100% → 75% → 50% → 25% → 100%. Canvas and BG buttons cycle through their available project-wide settings.

## Acceptance test
1. Open a project and select a video clip.
2. Change scale, position, rotation, flip, opacity, fit/fill and crop; preview must update immediately.
3. Change canvas ratio and background.
4. Split or duplicate the transformed clip; inherited visual state must remain consistent.
5. Undo/redo visual and canvas edits.
6. Close and reopen the project; visual state and canvas settings must persist.
7. Play across multiple clips with different transforms; each clip must switch to its own transform.
8. Open an older Patch 07 project; it must load with default visual transform values.

## Validation performed before packaging
- Pure Kotlin visual/timeline core compile: passed.
- Visual transform normalization + split inheritance smoke test: passed.
- XML well-formedness/static path checks: passed.
- Full Android Gradle build cannot run in the packaging environment because external Gradle distribution download is blocked; GitHub Actions remains the APK compile gate.

No `.yml` or `.yaml` files belong inside this patch ZIP.
