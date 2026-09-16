# Vedito Brand System

This file is the canonical visual-brand handoff for future UI work.

## Locked identity
- Name: **Vedito**
- Tagline: **Shape the cut.**
- Approved mark: **V + play + cut-frame**
- Product feel: premium, cinematic, clean, modern creator tool.
- Base theme: dark-first.

## Important color rule
Vedito is **not** a violet/purple-first brand and does not use a simplistic primary/secondary color pair.

The approved mockup direction is a dark premium base with bright **functional multi-accent** color:
- Signature / CTA: cyan → electric blue → indigo gradient.
- Video / overlay: blue.
- Text / captions: purple.
- Audio: green.
- Effects: amber.
- Export / active progress: cyan.
- Destructive/errors: red.

Indigo is the tail of the brand signature gradient, not a universal secondary color.

## Core tokens
- Background: `#07090E`
- Deep background: `#03050A`
- Surface: `#0E121A`
- Raised surface: `#151B26`
- Stroke: `#263142`
- Primary text: `#F6F8FC`
- Muted text: `#8C96A8`
- Brand cyan: `#16D9F2`
- Brand blue: `#3184FF`
- Brand indigo: `#6657F7`
- Video: `#3997FF`
- Text: `#B86CFF`
- Audio: `#27DFA0`
- Effects: `#F5B84B`
- Export: `#1BD5ED`
- Error: `#FF667A`

Android resource values in `app/src/main/res/values/colors.xml` are authoritative for implementation.

## Logo usage
- Use `vedito_brand_mark.png` for the launcher/splash and compact in-app brand mark.
- Keep the V/play/cut-frame proportions intact; do not recolor it to a single purple fill.
- Do not place the presentation board itself in production UI.
- Avoid heavy glow inside functional editor controls. Glow is presentation/hero treatment, not a default control effect.

## UI direction
- Strong hierarchy, compact professional controls, generous negative space where possible.
- Dark panels should be subtly separated by surface level and stroke, not thick borders.
- Use accent color to communicate function/status, not decoration everywhere.
- Future Home/Editor/Export overhaul should follow the approved four-screen mockup direction without copying another app 1:1.
