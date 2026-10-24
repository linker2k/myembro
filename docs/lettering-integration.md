# Lettering integration request — 2026-10-23

Follow-up stage 2: added approximate-centerline/paired-rail AUTO fill to `codex/letter`.
Uses satin at 7mm or less / staggered 4mm splitting for wide columns, automatic 0.6mm spacing for small text,
and center/edge underlay. Existing files restore with TATAMI/manual density.
User reference materials and comparison results remain local and are not included in this branch.

To the integrator: please merge `codex/letter` outline-fill changes into main. Not pushed.

- Previous unpushed satin commit `db155ff` was confirmed as an ancestor of main `02fa8ec` at the start of work.
  The satin commit itself is already integrated; no duplicate cherry-pick is needed.
- This change covers FILL/FillOptions persistence, even-odd hole handling, existing TatamiFill reuse,
  hidden running connections, narrow-outline satin/tatami switching, and lettering/editor text-property UI.
- Changes outside the assigned area were limited to adding `TatamiSettings.zigzag` default false, `EditorPanels` mode selection,
  and `EditorViewModel.applyTextProperties` background computation/history wiring.
  Preserved existing Tatami defaults and `applyProperties(Transform)` calls.
- Shared `ui/format/Dimensions.kt` landed in main, so the previously pending secondary inch display for height was also connected.
- Check command: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug`.
  See the final section of [lettering.md](lettering.md) for actual Jua and SA431 validation.
