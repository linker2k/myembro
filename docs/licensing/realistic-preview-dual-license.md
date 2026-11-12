<!-- SPDX-FileCopyrightText: 2026 HoonyJung -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later OR MIT -->

# Realistic preview: GPL-3.0-or-later OR MIT

The repository stays under [GPL-3.0-or-later](../../LICENSE). The project-written realistic preview code listed here
is additionally offered under the [MIT license](../../LICENSES/MIT.txt): you may use it under GPL-3.0-or-later **or**
MIT. For the MIT option: Copyright (c) 2026 HoonyJung.

Third-party code is not covered. The realistic renderer that this code calls (`engine/.../render/realistic/` except
`FixedRealisticPreview.kt`: `RealisticThreadRenderer`, `ThreadShape`, `ThreadFilterMath`, `InkscapeGaussian`,
`ThreadFilterRegion`, `PatternThreadSegments` and the rest, adapted from Ink/Stitch, Inkscape and resvg) keeps its own
licenses and notices in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md), including the lines this project added
to those files. A build of the app as a whole remains under GPL-3.0-or-later.

Basis: user decisions 2026-11-13 (project-written realistic preview code, including the first viewer integration;
copyright holder HoonyJung, the only commit author in the repository). Line numbers refer to commit `988bfad2`, as
attributed by `git blame`.

Commits covered (all project-written, author HoonyJung):

| commit | date | what |
|---|---|---|
| 80568745 | 2026-10-31 | first viewer integration (Android rasterizer adapter, fabric, tile compositing loop, Compose state, toggle) |
| a7f344fb | 2026-11-06 | tests use the current bundled sample |
| 06fe5aae | 2026-11-06 | fixed native density and box-resolved viewports, measurement task |
| 01b6b1cd | 2026-11-06 | frames retained during gestures, bounded native source allocation |
| ecb3bae7 | 2026-11-06 | serial 16px/mm preview, hoop fabric kept |
| 25df999e | 2026-11-07 | one-line note in `docs/realistic-speed-experiment.md` |
| d413877d | 2026-11-07 | preview starts automatically in the viewer |

## Whole files

Every line comes from the commits above; no third-party text is copied into them. Each carries
`SPDX-FileCopyrightText: 2026 HoonyJung` and `SPDX-License-Identifier: GPL-3.0-or-later OR MIT` (the CSV in a
`.license` sidecar file).

| file | commits |
|---|---|
| `app/src/main/kotlin/io/github/linker2k/myembro/ui/ViewerRealisticPreview.kt` | 80568745, 06fe5aae, 01b6b1cd, ecb3bae7 |
| `app/src/test/kotlin/io/github/linker2k/myembro/ui/ViewerRealisticPreviewTest.kt` | 80568745, a7f344fb, 06fe5aae, 01b6b1cd, ecb3bae7 |
| `app/src/androidTest/kotlin/io/github/linker2k/myembro/ui/RealisticViewerInstrumentation.kt` | 80568745, a7f344fb, d413877d |
| `app/src/main/res/values/strings_realistic.xml` | 80568745 |
| `app/src/main/res/values-ko/strings_realistic.xml` | 80568745 |
| `engine/src/main/kotlin/io/github/linker2k/myembro/engine/render/realistic/FixedRealisticPreview.kt` | 06fe5aae, ecb3bae7 |
| `engine/src/test/kotlin/io/github/linker2k/myembro/engine/render/realistic/FixedRealisticPreviewTest.kt` | 06fe5aae, ecb3bae7 |
| `docs/realistic-renderer.md` | 06fe5aae, 01b6b1cd, ecb3bae7 |
| `docs/realistic-speed-experiment.md` | ecb3bae7, 25df999e |
| `docs/realistic-fixed-preview-measurements.csv` (+ `.csv.license`) | 06fe5aae |
| this document | – |

## Parts of mixed files

These files stay `GPL-3.0-or-later` as a whole. Only the lines listed below are also offered under MIT
(Copyright (c) 2026 HoonyJung).

| file | commit | lines at 988bfad2 |
|---|---|---|
| `app/src/main/kotlin/io/github/linker2k/myembro/ui/StitchCanvas.kt` | 80568745 | 8, 10–16, 40–41, 56, 73, 127–130, 132, 134, 136–137, 189–191, 218–220, 231–232, 268–272 |
| same | 06fe5aae | 6–7, 131, 133, 192–196, 207–213, 216–217 |
| same | 01b6b1cd | 197–206, 214–215 |
| `app/src/main/kotlin/io/github/linker2k/myembro/ui/ViewerScreen.kt` | 80568745 | 38, 70, 255, 274, 282, 292, 302, 321–323 |
| same | d413877d | 254 |
| `engine/build.gradle.kts` (`measureFixedRealisticPreview` task) | 06fe5aae | 312–324 |

## Future changes

These files will keep changing. The plan is to keep later project-written changes to the whole files above, and to
the listed parts of mixed files, under the same GPL-3.0-or-later OR MIT terms. Contributions from others are accepted
into these files only after the contributor agrees to both licenses and the origin of the change is checked; code
adapted from third-party sources stays out of the MIT option and is recorded in
[THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). Line numbers above are a snapshot at `988bfad2`; this document
is updated when the covered code changes.

## Not covered

The engine renderer files changed in 80568745 (`RealisticThreadRenderer.kt`, `ThreadFilterMath.kt`, new
`PatternThreadSegments.kt` and its test) are adaptations of third-party code, so the lines this project added there
stay GPL-3.0-or-later only. `docs/research/realistic-preview-app.md` (80568745) is not part of this decision.
