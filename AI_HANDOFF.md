# Vedito — AI Handoff

When continuing this project with another AI, give it repository access (or at minimum these files) and use this instruction:

> Read `PROJECT_CONTEXT.md` and `WORKPLAN.md` completely before proposing or changing code. Preserve all locked decisions, delivery rules, package ID, architecture direction and completed functionality. Inspect the current source before coding. Do not assume planned features already exist. Continue from the current patch/milestone, keep migrations backward-compatible, and do not include YML/YAML inside patch ZIPs.

Recommended context files to attach/share:
1. `PROJECT_CONTEXT.md` — canonical decisions/current state.
2. `WORKPLAN.md` — master roadmap and next milestones.
3. `README.md` — current patch usage/summary.
4. Relevant build log if the latest GitHub Action is red.

If a new patch changes architecture, schema, current status, or next milestone, update `PROJECT_CONTEXT.md` and `WORKPLAN.md` in that same patch so these files remain the source of truth.
