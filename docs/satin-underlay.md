# Default satin underlay

User decision 2026-11-08: satins the app generates get underlay by default (satin without
underlay is rare; underlay limits pull distortion). Only the on/off choice depends on the column
width. Every underlay parameter stays the Ink/Stitch v3.3.0 default (`SatinParameters`,
checked against `lib/elements/satin_column.py` at `b0edd96`): center-walk 3mm stitches,
0.2mm tolerance, 2 repeats, 50% position; contour 3mm, 0.2mm, inset 0.4mm; zigzag spacing
3mm, inset half the contour inset.

## Rule

`SatinUnderlayPolicy.withDefault(parameters, widthMm)`, used only when no underlay is chosen:

| Column width | Underlay |
|---|---|
| < 4.0mm | center walk |
| ≥ 4.0mm | contour + zigzag ("German") |

Applied to: VectorArt stroke satin and region satin (`RegionSatin`, also block satin in the
editor), lettering TTF satin (`LetteringEngine`, `lettering/SatinColumn`), Hangul and Kana
centerline satin. Explicit underlay choices are kept. `SatinOptions.autoUnderlay=false`
(lettering, persisted only when false) keeps the top layer alone. Pre-digitized Ink/Stitch
font satins (`InkStitchLettering`) never pass through the policy: their SVG settings stay.

Width used per site: stroke width (VectorArt stroke), median measured width (`RegionSatin`),
maximum rail distance (`lettering/SatinColumn.generate`), satin option width (`LetteringEngine`),
per-character stroke width (Hangul/Kana).

## Evidence

| Boundary | mm | Source | Kind | Quote |
|---|---|---|---|---|
| Order thin → small/medium → wide | none | [Ink/Stitch satin column](https://inkstitch.org/docs/stitches/satin-column/) | official, no numbers | "This may be all you need for thin satin columns" (center-walk); contour "For small or medium width satin"; zigzag + contour "German Underlay … For wide columns" |
| Center run for narrow columns | 2–3 (example) | [Wilcom EmbroideryStudio 28, run stitch underlay](https://docs.wilcom.com/embroiderystudio/28/en/OnlineHelp/Quality/stabilizing/stabilizing-10.htm) | official | "It is used to stabilize narrow columns – e.g. 2-3 mm wide." |
| Edge run / zigzag | none | [Wilcom ES 28, automatic underlay](https://docs.wilcom.com/embroiderystudio/28/en/OnlineHelp/Quality/stabilizing/stabilizing-7.htm) | official, no numbers | Edge run "somewhat larger shapes such as letters"; zigzag "wide columns" |
| Zigzag / double from | 4 | [Embroidery Legacy](https://embroiderylegacy.com/?p=29571) | industry blog | "used when applying underlay to a column width of 4mm and up" |
| Zigzag | 4–8 | [ABDigitizing](https://abdigitizing.com/the-secret-to-perfect-embroidery-understanding-underlay-stitches/) | vendor blog | "perfect for wide satin columns (4.0mm to 8.0mm wide)" |
| Center run / edge run | 1.5–2 / 2.5–3.5 | Embroidery Legacy (same page) | industry blog | conflicts with Wilcom's 2–3mm center-run example; not used |
| No underlay | <5 letter **height** | [Wilcom ES 28, lettering underlay](https://docs.wilcom.com/embroiderystudio/28/en/OnlineHelp/Lettering/lettering_advanced/lettering_advanced-22.htm) | official | letter height, not column width; not used (user decision: underlay by default) |

Unverified, not used: a "<1.5mm center only, 1.5–3mm edge only" snippet without a findable page;
third-party claims about Hatch switching at 6–10mm. Hatch, Embrilliance, mySewnet and Brother
PE-Design documentation give no width numbers.

### PE-Design corpus (heuristic, aggregate only)

`tools/research/satin_underlay_corpus.py` on the user's 334 PE-Design files (243 with satin runs;
private data, numbers only). Satin column = frozen satin-run heuristic, merged across corners;
underlay kind = the moves sewn just before the column inside its footprint. The labels are
heuristic, not verified semantics.

| Width mm | Columns | none | center | contour | zigzag |
|---|---:|---:|---:|---:|---:|
| <1.5 | 1652 | 34% | 45% | 11% | 10% |
| 1.5–2 | 789 | 30% | 50% | 12% | 9% |
| 2–3 | 1057 | 21% | 51% | 10% | 18% |
| 3–4 | 427 | 19% | 44% | 19% | 18% |
| 4–5 | 271 | 28% | 32% | 18% | 22% |
| 5–7 | 156 | 33% | 38% | 15% | 14% |
| ≥7 | 33 | 42% | 21% | 12% | 24% |

Center walk is the most common kind below 4mm; zigzag shares rise from about 10% to about 20%
from 2mm. No band has contour alone as the most common kind, so the rule has no contour-only
band. Above 4mm the corpus is mixed (center still common); 4.0mm rests on the industry
sources plus Ink/Stitch's "wide → German", not on the corpus.

## Measurement (2026-11-08)

Before = main's own output: the 126 bundled SVG samples' PES and the Android-captured lettering PES
(`engine/src/test/resources/font-snapshots`). After = this branch, same sizes and hoops (each sample's
catalog hoop: 124 at 100×100, 12 at 130×180). The 10 line samples come from the photo outline path
(no satin) and have no SVG source; they were not regenerated.

- Samples: 81 of 126 contain satin and change; 45 are byte-identical. Stitches +0.08% to +5.89%
  (median +2.90%); peak per 0.5mm cell changes by 0 to +2, maximum after 23 (unchanged ice cream).
- Engine `writeChecked`/SewoutCheck: all 132 analyses complete, 0 blocking findings.
- Independent gate (`quality_gate.py`, hoop spec per sample hoop): 132 files, PASS 111, WARN 21
  (P7 16–30, allowed), BLOCK 0.

| Case | Before stitches | After stitches | Δ | Peak/0.5mm before → after | Blockers |
|---|---:|---:|---:|---|---:|
| lettering-jua-latin-fill | 1086 | 1086 | +0.0% | 7 → 7 | 0 |
| lettering-jua-안녕-satin | 601 | 683 | +13.6% | 8 → 8 | 0 |
| lettering-jua-정민-satin | 642 | 723 | +12.6% | 6 → 8 | 0 |
| lettering-noto-sans-kr-latin-fill | 944 | 944 | +0.0% | 9 → 9 | 0 |
| lettering-noto-sans-kr-안녕-satin | 613 | 704 | +14.8% | 5 → 7 | 0 |
| lettering-noto-sans-kr-정민-satin | 623 | 714 | +14.6% | 5 → 7 | 0 |

The comparison sheet shows the four samples with the largest increase and the four Hangul cases
(before | after, realistic preview, 1/4 scale). Not measured: physical coverage or a real-machine sew-out.

Reproduce:

```sh
./gradlew :engine:satinUnderlayMeasure --max-workers=6   # /tmp/myembro-satin-underlay/measure
./gradlew :engine:satinUnderlayMeasure -PmeasureArgs="png /tmp/myembro-satin-underlay/measure <ids>"
python3 tools/experiments/satin_underlay_measure.py --gate <quality_gate.py> --sheet <ids>
python3 tools/research/satin_underlay_corpus.py --gate <quality_gate.py> <pe-design dir>
```

Kept results: `docs/experiments/satin-underlay/results.json`, `docs/experiments/satin-underlay/comparison.png`.
The corpus aggregate JSON is kept privately with the user's data.

## Follow-ups

- `AndroidFontSnapshotTest.jvmPreparedObjectsAndPesMatchActualAppForTwoFonts`: the 4 pinned Hangul satin PES were
  captured in the app before this change. Until a re-capture on emulator-5556 with this build
  (`tools/font-snapshots/capture.sh`, then restore the plain comparison), the test matches them with
  `autoUnderlay=false` (byte-exact) and checks that the default output differs and has no blocking finding.
- `KanaHangulInvarianceTest` (app, 30 rows): `sha256TopOnly` = the f1db5974 bytes, reproduced byte-exact with
  `autoUnderlay=false` (`HangulSatin`/`KanaSatin.generate` now honour it, as `TextObject` passes it);
  `sha256` = the default underlay output, refrozen.
- The bundled sample pack (`app/src/main/assets/samples`) still holds the pre-underlay PES; regenerate it
  with `tools/samples/install_pack.py` before a release that includes this change.
- Legacy lettering projects whose satin had no underlay now get the width default on reload (user decision);
  `autoUnderlay=false` keeps the old top-only result.
