# Vedito Patch 28A — Branding Foundation

Version: **0.28.0** (`versionCode 28`)  
Project persistence schema: **v20**

## Locked brand direction
- Approved mark: **V + play + cut-frame**.
- Wordmark: **Vedito**.
- Tagline: **Shape the cut.**
- Premium dark-first UI direction based on the approved mockup.
- Brand signature leads with cyan/electric blue; indigo is a gradient tail, not the app's single secondary color.
- Editor UI uses functional multi-accent semantics: video blue, text purple, audio green, effects amber and export cyan.

## What Patch 28A adds
- Isolated production `vedito_brand_mark.png` asset derived from the approved logo direction.
- Adaptive launcher/round icon on a deep-dark background.
- Android 12+ branded splash.
- Home header now displays the official Vedito mark and the approved tagline.
- Central color, radius, typography and control-style tokens for the next UI overhaul patches.
- Existing legacy accent references resolve to brand cyan so old screens stop inheriting the previous violet-heavy identity.
- Transparent edge-to-edge system bars keep dark icon contrast enforcement disabled where supported.

## Intentionally not in 28A
- Full Home/dashboard redesign.
- Editor timeline/tool hierarchy redesign.
- Dedicated Color/Effects screen redesign.
- Export screen redesign.

Those are the next UI-overhaul patches built on this brand foundation instead of another parallel style system.

## Engine compatibility
- No project-model changes; schema remains **v20**.
- No render/export semantic changes; recovery salt remains **`vedito-render-p26-r1`**.
- Patch 27 foreground export behavior is unchanged.
