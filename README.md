# Vedito Patch 28B — Home UI Overhaul

Version: **0.28.1** (`versionCode 29`)  
Project persistence schema: **v20**

## What Patch 28B changes
- Rebuilds Home into the approved premium mockup hierarchy instead of the old sparse utility screen.
- Brand header uses the official Vedito production mark and `Shape the cut.` tagline.
- Large gradient **New Project** hero CTA remains wired to the real Android video picker/project creation flow.
- Adds functional dashboard cards for Templates, Drafts and Recent Exports without inventing unsupported project data.
- Recent projects become a two-column visual grid with real source-video thumbnails, duration badges, clip/resolution metadata, and collapsed/expanded `See All` behavior.
- Adds a Featured Templates preview rail as visual product direction only; template taps explicitly report that packs are preview-only in this build.
- Adds a fixed premium bottom navigation shell. Edit opens the latest draft (or starts import), Assets opens the real picker, Export routes through the latest editor because export settings still live there, and Profile explicitly reports that the feature is not in this build.
- Keeps the Patch 28A dark premium + functional multi-accent design system intact.

## Engine compatibility
- No project-model or persistence changes; schema remains **v20**.
- No preview/render/export semantic changes; recovery salt remains **`vedito-render-p26-r1`**.
- Patch 27 foreground export behavior is unchanged.

## Next UI milestone
**Patch 28C — Editor UI Overhaul**: preview hierarchy, multi-track timeline presentation, grouped tool dock and removal of the current debug-button feel while preserving canonical editor behavior.
