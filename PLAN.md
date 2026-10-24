# myembro — Android embroidery app development plan

> An open-source (GPL-3.0) Android embroidery app. Brother PES first, starting with a viewer.
> Revised 2026-10-22: incorporates Codex (GPT-6.1-Sol) cross-verification results — see "Revision history" below.

## Status notation

| Marker | Meaning |
|---|---|
| `[ ]` | Not started |
| `[~]` | Implemented, awaiting verification |
| `[x]` | Implemented + verified by automated tests |
| 🧵 | Verified on an actual machine/fabric |

## Confirmed decisions (2026-10-22)

| Item | Decision |
|---|---|
| Target machines | Brother → **PES** first (PEC/DST reading also supported) |
| License | **GPL-3.0-or-later**, publicly available open source on GitHub |
| MVP | **Viewer/simulator** first |
| Default hoop | **100×100 mm** |
| Stack | Kotlin + Jetpack Compose, `:engine` (pure Kotlin/JVM) + `:app` (Android) |

GPL permits directly porting Ink/Stitch (GPL-3) algorithms. pyembroidery (MIT) supplies file-I/O porting and the test reference (oracle, **pinned to 1.5.1**). Notices: `THIRD_PARTY_NOTICES.md`.

## Features to inherit from each original program

| Original | Strengths to inherit | App application |
|---|---|---|
| Ink/Stitch | Vector-to-stitch parameters (running/satin/tatami, density, pull compensation, underlay) | Digitizing engine |
| Embrilliance | Lightweight native app, lettering, modular design | App architecture / feature modules |
| PE-Design | Automatic image-to-embroidery digitizing | "Photo → embroidery" wizard |
| pyembroidery | I/O for many formats | File-I/O layer + reference-comparison tests |

## Project structure

```
myembro/
├── engine/        # 순수 Kotlin: 모델, PES/PEC/DST 리더 (JVM 단위 테스트)
├── app/           # Android 앱 (Compose 뷰어)
├── tools/         # make_fixtures.py: pyembroidery로 테스트 픽스처 생성
└── .github/       # CI (엔진 테스트 + APK 빌드)
```

## Work by phase

### Phase 0 — Define scope
- [x] Target PES, public GPL-3.0 release, viewer first, default hoop 100×100
- [ ] **Confirm target machine model and actual sewing area** (hoop name ≠ actual sewable area)
- [ ] Collect reference PES files (multiple authoring programs, including PE-Design·Embrilliance·Ink/Stitch, v1~v10)

### Phase 1 — Engine core: reading
- [x] Data model: Stitch / Jump / Trim / Stop / ColorChange / End, thread colors, read warnings
- [x] PES·PEC readers — compare v1·v6 + PEC with pyembroidery **at command/coordinate/color level** (3 designs)
- [~] PES v4/v5/v7~v10 headers — code exists; actual-file verification required
- [x] DST reader (+ consecutive-jump→trim inference, O(n) streaming implementation)
- [ ] Preserve DST sequin commands separately (currently reduced to jumps after a warning → may affect trim inference)
- [x] Restore STOP (duplicate colors), truncated-file warnings, PEC block boundaries, 0-color designs — hand-authored boundary-input tests

### Phase 2 — Viewer / simulator ← first public release
- [~] SAF file opening + "Open with another app"
- [~] Stitch rendering (thread colors, draw only between consecutive STITCH commands, pinch zoom/pan)
- [~] Playback simulation (slider, speed = commands/second)
- [~] Statistics: stitch count, dimensions (mm), color count, trim count, estimated time
- [~] Color-block list (STOP keeps the same thread)
- [~] Hoop overlay (default 100×100, menu options 130×180·160×260·200×200·200×300, warning on overflow)
- [~] UI images: launcher icon, README banner, Play feature graphic, empty-state illustration (Codex worker)
- [ ] Check large-design performance on an actual device (Android phone)

### Phase 2.5 — Output path (new: required before Phase 3)
A writing path is needed first to check digitizing results on a machine.
- [x] Command normalization (split long stitches/jumps, STOP → duplicate-color encoding, END)
- [x] PEC writer (block lengths, thumbnails)
- [~] Minimal PES v1 writer (minimal header implemented and automatically verified) → 🧵 **Awaiting machine recognition check** (size/orientation → colors/STOP → jumps/trims)
- [x] Read ↔ write round trips + pyembroidery 1.5.1 cross-comparison (commands/coordinates/colors/blocks/thumbnails, [usage and limits](docs/writers.md))
- [ ] PES v6 (custom thread information, editor compatibility)
- [~] Save/share: SAF saving, USB OTG, share sheet

### Phase 2.6 — Editor, lettering, fonts (new: 2026-10-22)
Provide a lightweight editor for combining/placing existing designs and adding text before the digitizing engine.
- [x] Editor model: immutable `Project`, undo/redo, duplicate/mirror/rotate/align, hoop fitting, `.myembro` JSON storage format (versioned), optional sewing-order optimization, statistics
- [~] Editor 1st UI: hoop canvas (pinch/pan), tap selection, drag movement, two-finger rotation/scaling, layers (order/hide), save to library as PES. Entry points: "Create" in the design list, "Edit" in the viewer (v0.1.0-alpha2)
- [~] Editor 2nd pass: multiselection, alignment/distribution, snapping, property panel, project save/reopen (v0.1.0-alpha3)
- [x] English single-line lettering (Hershey, running stitch)
- [x] Hangul outline lettering (OFL Jua and others, outline running stitch)
- [ ] Connect the editor's "Add text" UI (`TextObject`)
- [~] Font-download catalog (OFL·Hershey only, checksum verification, save license files alongside fonts) — screen entry point not connected
- [ ] Add user fonts + quality checks: minimum stroke thickness → recommended minimum letter height, rating (good/caution/unsuitable), outline simplification, license guidance
- [~] Settings screen (design list ⚙) / [ ] Connect export sheet (integrated library·USB·sharing)

### Phase 3a — Geometry foundation + minimal object model (before/in parallel with Phase 3)
- [ ] Coordinate units, curve flattening, fill rules (nonzero/evenodd), hole handling
- [ ] Shape operations/offsets (Shapely replacement: consider JTS, etc.), path graphs (NetworkX replacement)
- [ ] Minimal object/project model: shape + stitch type + parameters, storage format
- [ ] Small SVG input path (per-object stitch types, like Ink/Stitch)
- [ ] Long-running operation cancellation/resource limits (mobile)

### Phase 3b — Digitizing engine (Ink/Stitch port, 🧵 at every stage)
Each stage has comparison designs and actual-sewing acceptance criteria.
- [ ] Running stitch → 🧵
- [ ] Limited satin (2 rails, width-based density) → 🧵
- [ ] Simple fill (convex/simple polygons) → 🧵
- [ ] Complex tatami (holes, section splitting, travel paths) → 🧵
- [ ] Underlay / pull compensation / automatic trimming / order optimization → 🧵 per fabric
- [ ] Fabric-specific parameter presets

### Phase 4 — Editor
- [ ] Object selection/deletion/transformation, region editing, stitch type/order changes
- [ ] **Save/reopen editable projects** (preserve objects/parameters/vector originals, version the format) — complete before the photo wizard
- [ ] Undo/Redo, color-order editing, thread-brand palettes
- [ ] Freehand drawing (finger/stylus)
- [ ] Lettering: prioritize predigitized fonts. **Automatic TTF → satin conversion is a separate research topic**

### Phase 5 — Photo → embroidery wizard (after the editor)
Placed after the editor because users must be able to correct automatic results.
- [ ] Color quantization (k-means) → noise removal → vectorization → per-region stitch assignment
- [ ] Step-by-step wizard UI → send results to the editor

### Phase 6 — Expanded machine integration
- [ ] (Research) Wireless machine integration

### Phase 7 — Final regression & release
Actual sewing tests repeat at each stage from Phase 2.5 onward. This phase is only the final regression.
- [ ] Regression sewout of the full design set, final parameter tuning
- [ ] GitHub Releases APK → F-Droid / Play Store

## Risks
1. **Underestimating fill-algorithm scope** — Ink/Stitch depends heavily on Shapely·NetworkX. Replacement libraries must be selected first in Phase 3a
2. **Delayed actual-machine verification** — target model is unconfirmed. Must be confirmed in Phase 0
3. PES-version compatibility — actual files from multiple authoring programs are needed
4. Mobile performance — rendering/optimizing tens of thousands of stitches must run in the background and support cancellation

## Revision history
- **2026-10-22** Incorporated Codex cross-verification
  - Distinguished implementation/automated verification/actual-sewing verification checkboxes (the previous "byte-level comparison" claim was overstated → corrected to command/coordinate/color comparison)
  - Moved the PES writer before Phase 3 to **Phase 2.5**, prioritizing minimal PES v1 → machine confirmation
  - Changed actual-sewing tests from a final-stage task to **repetition at every stage**
  - Moved geometry foundation, minimal object model, and small SVG input ahead of Phase 3 (**3a**)
  - Placed the photo wizard after the editor and separated TTF→satin as a research topic
  - Fixed 9 engine bugs (STOP restoration, PEC block boundaries, truncated-file warnings, 0 colors, O(n²) trim inference, rendering start points, playback speed, etc.)
- **2026-10-22** Incorporated Codex re-verification: thread equality uses RGB+metadata (fixes regression merging distinct threads with the same RGB into STOP), reject invalid PEC block lengths, restore project save/reopen task, enforce oracle version
- **2026-10-22** Added Phase 2.6 "Editor, lettering, fonts": lightweight editor, lettering, and font catalog before digitizing (3b). Some Phase 4 lettering·Undo/Redo work moved forward to 2.6
