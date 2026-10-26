# Texture fill catalog: 40×40mm square and circle

Research branch `agent/texture-catalog`, base `a9cc250`. This is an audit of the
existing Kotlin ports, not a new stitch algorithm, UI, sewing approval, or claim
of PE-Design programmable-fill compatibility.

## Reproduce

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 tools/research/texture-catalog.sh
```

The script runs `:engine:test --tests '*TextureCatalogTest'` with the existing
`tools/inkstitch-fill-preview.gradle` integration. That explicitly compiles the
existing `FillStitch` adapter from `src/inkstitchPreview`; it does not promote it
into the normal app source set. Only our JVM port is executed. Ink/Stitch source
was read, not executed; no upstream dependencies were installed.

Artifacts: `/tmp/myembro-texture/` contains `results.json`, `results.csv`, the full
160-row `results.md`, `case-output.txt`, `summary.txt`, two source SVGs, per-case PNGs,
`square-contact.png`, `circle-contact.png`, and safe-output PES files. Contact sheets
contain 80 cases per shape, in test order. Error thumbnails are labeled ERROR and
have no completed stitch path. Failed/blocked cases have no PES; stale PES for
such a case is deleted on rerun. Output location may be changed with
`TEXTURE_CATALOG_OUTPUT`.

Committed evidence: [test output](texture-catalog-output.txt) and
[per-case measurements](texture-catalog-results.csv). A passing JUnit result means
**all requested cases were accounted for**, not that every tile generated safely.

## Inventory and fixed inputs

- The reported **77 resource files** are **75 SVG tiles + `index.txt` + `ORIGIN.txt`**,
  not 77 different patterns. The test asserts that every SVG is indexed and every
  indexed SVG is tested. Existing notices identify 75 original tile SVGs:
  [THIRD_PARTY_NOTICES](../../THIRD_PARTY_NOTICES.md).
- Primary matrix: 2 shapes × (75 meander patterns + tatami + contour + circular +
  linear gradient without an explicit gradient) = **158 cases**.
- Supplemental input: 2 shapes with an explicit horizontal red→blue 2-stop gradient,
  with algorithm parameters still at defaults = **2 cases**, total **160**.
- Square: exact 40×40mm polygon. Circle: 40mm diameter, JTS buffer with 64 segments
  per quadrant (256-edge polygon), in the same 40×40mm bounding box. Geometry uses
  SVG px, `96/25.4 px/mm`; only the existing adapter quantizes to 0.1mm.
- Source SVG node IDs are `texture-square` / `texture-circle`. Upstream's empty
  `random_seed` falls back to the element ID (`fill_stitch.py:770–789`); this resolved
  default is passed to the pure-geometry adapter. No seed search or parameter tuning.
- Common source parameters: `FillParameters` defaults, including **underlay on**,
  0.25mm row spacing, 4.0mm maximum fill stitch, 2.5mm running length, and actual
  getter tolerance **0.2mm**. The meander tile ID is the only per-pattern choice.
- Shared S1 processing is unchanged: 3mm collapse threshold, 0.1mm minimum stitch,
  default locks, and the existing app output cleanup/viewport normalization.
  `SewoutCheck` is run on the final output copy and on PES where export succeeds,
  with `APP_GENERATED` and `HoopSpec.H100`. Reported needle counts and peak
  penetrations include underlay and locks, not just the texture's top stitches.
- Peak density is the existing `SewoutStatistics.peakCellPenetrations` for a 0.5mm
  cell, not an invented new gate. Generation errors have **unmeasured** counts/density
  (`null` in JSON / `—` below), not a measured zero.

## Original default parameters and UI definition

Pinned source: **Ink/Stitch v3.3.0**, commit
`b0edd96311ece82ee48816dc93466274b12c2a9c`, GPL-3.0-or-later.
The following links point to that commit, not a moving branch. Parameter definitions
are in [`lib/elements/fill_stitch.py`](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py).

| Parameter / mode | Original default | Original UI options / runtime boundary | Source file:lines |
|---|---|---|---|
| `fill_method` | index 0, `tatami_fill` | combo: tatami, circular, contour, cross, guided, linear gradient, meander, tartan, legacy; only requested modes audited here | [fill_stitch.py:166–184](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L166-L184) |
| `angle` | 0° | float degrees; negative allowed; no finite slider interval declared in this decorator | [fill_stitch.py:299–310](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L299-L310) |
| `row_spacing_mm` / `max_stitch_length_mm` | 0.25mm / 4.0mm | float inputs; getters clamp to at least 0.1mm, no upper bound declared by these params | [fill_stitch.py:348–379](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L348-L379) |
| `end_row_spacing_mm` | None | optional float, absent/0 means unset; otherwise clamped ≥0 | [fill_stitch.py:381–394](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L381-L394) |
| `staggers` / `skip_last` | 4 / false | float / boolean, no upper stagger bound declared here | [fill_stitch.py:418–444](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L418-L444) |
| `running_stitch_length_mm` | 2.5mm | float, getter lower bound 0.01 SVG px | [fill_stitch.py:498–514](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L498-L514) |
| `running_stitch_tolerance_mm` | **UI decorator 0.1mm; actual missing-value getter 0.2mm** | float, getter lower bound 0.01 SVG px; the audit uses getter default, as the port does | [fill_stitch.py:517–533](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L517-L533) |
| `fill_underlay` | true | boolean; applies to these modes, including meander | [fill_stitch.py:681–684](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L681-L684), [1087–1091](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L1087-L1091) |
| Underlay angle / spacing / maximum length | angle+90°, 3× row spacing (0.75mm), fill maximum length (4mm) | optional float/list inputs; missing values use those defaults | [fill_stitch.py:687–732](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L687-L732) |
| `contour_strategy` / `join_style` | 0 / 0 | strategy: Inner to Outer / Single spiral / Double spiral; joins: Round / Mitered / Beveled | [fill_stitch.py:195–205](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L195-L205) |
| `avoid_self_crossing` / `clockwise` | false / true | booleans | [fill_stitch.py:207–220](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L207-L220) |
| `meander_pattern` | first sorted tile; port default `N3-11a` | tile combo, not a numeric slider; all 75 bundled SVGs tested | [fill_stitch.py:222–226](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L222-L226) |
| `meander_angle` / `meander_scale_percent` / `clip` | 0° / 100%×100% / false | angle float; scale accepts one/two percentages, runtime clamp ≥1% per axis; no upper scale bound declared here | [fill_stitch.py:228–258](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L228-L258) |
| `smoothness_mm` | 0 | float, tooltip suggests 0.2 as an example, not a validated interval | [fill_stitch.py:260–273](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L260-L273) |
| `repeats` / `bean_stitch_repeats` | 1 / 0 | integer (runtime ≥1) / space-separated list; bean 1 adds a back-and-forth trip; no upper bound declared here | [fill_stitch.py:568–597](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L568-L597) |
| `zigzag_spacing_mm` / `zigzag_width_mm` | 0 (off) / 3mm | float inputs, only meander; no numeric slider bounds declared here | [fill_stitch.py:600–623](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L600-L623) |
| `random_seed` | blank → SVG element ID | random-seed control, fixed IDs recorded in the measurements | [fill_stitch.py:770–789](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L770-L789) |

A source parameter's input type or runtime clamp is **not a verified mobile UI
range**. No new slider range is proposed. Port guards (e.g. contour/meander repeats
1–16, circular repeats 1–20, resource limits) are implementation limits, not original
UI maxima. No parameter was reduced to make a failing tile pass.

### Modes requiring additional input

- `guided_fill` is **excluded**, not counted as a successful guided texture. The
  upstream default strategy is Copy (other options Parallel Offset/Buffer):
  `fill_stitch.py:186–193`. Missing guide lines produce a warning at
  [1425–1434](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L1425-L1434)
  and the generation path falls back to tatami at
  [1285–1291](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L1285-L1291).
- A missing linear gradient is **not tatami fallback**: upstream uses one color
  fading toward `none`, i.e. changing row occupancy. See
  [linear_gradient_fill.py:29–44](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/linear_gradient_fill.py#L29-L44)
  and `fill_stitch.py:1436–1438` (warning). This is measured as `no_gradient`.
  Supplemental `two_stops` rows use explicit input red `#ed171f` at 0 and blue
  `#0e1f7c` at 1, left→right across 40mm, without changing fill parameters.
- Gradient underlay angle follows the gradient direction default rather than
  angle+90° when an actual gradient exists: `fill_stitch.py:694–711`.

## Measurements

Final command output: `BUILD SUCCESSFUL`; JUnit **1 test, 0 failures/errors**.
The matrix completed **160 cases: 157 SewoutCheck PASS, 3 generation ERROR,
0 SewoutCheck BLOCK**. Primary defaults: **155/158 PASS, 3 ERROR**; supplemental
2-stop gradients: **2/2 PASS**. Completed cases have peak cell density **≤7**
(the existing app limit is 15). This is not an independent oracle/release verdict.

### Fill methods (default meander tile shown here)

| Method/input | Square stitches | Square peak | Square check | Circle stitches | Circle peak | Circle check |
|---|---:|---:|---|---:|---:|---|
| tatami_fill | 2580 | 4 | PASS | 2138 | 6 | PASS |
| meander_fill__N3-11a | 1111 | 3 | PASS | 887 | 3 | PASS |
| contour_fill | 2445 | 5 | PASS | 1934 | 4 | PASS |
| circular_fill | 3498 | 6 | PASS | 2658 | 6 | PASS |
| linear_gradient_fill_no_gradient | 1694 | 5 | PASS | 1370 | 5 | PASS |
| linear_gradient_fill_two_stops | 2712 | 7 | PASS | 2182 | 7 | PASS |

### All 75 meander tiles

Every column uses the same 40mm input and all other source defaults. A PASS is
specific to the shape/element ID/default seed and parameters tested here, not a
validated height/scale range for arbitrary designs. `—` means generation did not
complete, so no final count or density is claimed.

| Tile | Square stitches | Square peak | Square check | Circle stitches | Circle peak | Circle check | Failure cause |
|---|---:|---:|---|---:|---:|---|---|
| N3-11a | 1111 | 3 | PASS | 887 | 3 | PASS |  |
| N3-12 | 1000 | 3 | PASS | 783 | 3 | PASS |  |
| N3-16a | 1065 | 3 | PASS | 869 | 3 | PASS |  |
| N3-17 | 1107 | 3 | PASS | 897 | 3 | PASS |  |
| N3-18-modified | 1017 | 3 | PASS | 824 | 3 | PASS |  |
| N3-18 | 1041 | 3 | PASS | 809 | 3 | PASS |  |
| N3-20 | 1040 | 3 | PASS | 834 | 3 | PASS |  |
| N3-23b | 1147 | 3 | PASS | 930 | 3 | PASS |  |
| N3-25c | 1160 | 3 | PASS | 933 | 3 | PASS |  |
| N3-26b | 1272 | 3 | PASS | 993 | 4 | PASS |  |
| N3-27 | 1030 | 3 | PASS | 820 | 3 | PASS |  |
| N3-30a | 1099 | 3 | PASS | 902 | 3 | PASS |  |
| N3-51b | 1152 | 3 | PASS | 924 | 3 | PASS |  |
| N3-57f-modified | 1013 | 3 | PASS | 794 | 3 | PASS |  |
| N3-58b | 1133 | 3 | PASS | 917 | 3 | PASS |  |
| N3-6 | 962 | 3 | PASS | 777 | 3 | PASS |  |
| N3-7 | 1130 | 3 | PASS | 907 | 3 | PASS |  |
| N3-8a-modified | 1024 | 3 | PASS | 817 | 3 | PASS |  |
| N3-8a | 1102 | 3 | PASS | 882 | 3 | PASS |  |
| N3-8b | 1094 | 3 | PASS | 879 | 3 | PASS |  |
| N4-13b | 1215 | 3 | PASS | 961 | 4 | PASS |  |
| N4-13c | 1171 | 4 | PASS | 937 | 3 | PASS |  |
| N4-13d | 1144 | 3 | PASS | 916 | 3 | PASS |  |
| N4-13e | 1040 | 3 | PASS | 831 | 3 | PASS |  |
| N4-13f | 1020 | 3 | PASS | 837 | 3 | PASS |  |
| N4-16a | 1133 | 3 | PASS | 913 | 3 | PASS |  |
| N4-19 | 1235 | 4 | PASS | 983 | 3 | PASS |  |
| N4-20 | 1267 | 3 | PASS | 1016 | 4 | PASS |  |
| N4-21c | 1176 | 4 | PASS | 910 | 4 | PASS |  |
| N4-22 | 1128 | 3 | PASS | 911 | 3 | PASS |  |
| N4-23a | 1078 | 3 | PASS | 852 | 3 | PASS |  |
| N4-23c | 1083 | 3 | PASS | 868 | 3 | PASS |  |
| N4-27 | 1086 | 3 | PASS | 860 | 3 | PASS |  |
| N4-29e | 1303 | 3 | PASS | 1023 | 3 | PASS |  |
| N4-29f | 1249 | 3 | PASS | 967 | 3 | PASS |  |
| N4-31 | 1110 | 3 | PASS | 898 | 3 | PASS |  |
| N4-38 | 1020 | 3 | PASS | 825 | 3 | PASS |  |
| N4-42e | 1258 | 3 | PASS | 997 | 3 | PASS |  |
| N4-44 | 1158 | 3 | PASS | 935 | 4 | PASS |  |
| N4-52 | 1060 | 3 | PASS | 866 | 3 | PASS |  |
| N4-54d | 1133 | 3 | PASS | 909 | 3 | PASS |  |
| N4-5a-2 | 1011 | 3 | PASS | 798 | 3 | PASS |  |
| N4-5a | 1096 | 3 | PASS | 871 | 3 | PASS |  |
| N4-82 | 1122 | 3 | PASS | 898 | 3 | PASS |  |
| N4-85d | 1192 | 3 | PASS | 968 | 3 | PASS |  |
| N5-1e1 | 1068 | 3 | PASS | 846 | 3 | PASS |  |
| N5-1q2 | 1056 | 3 | PASS | 879 | 3 | PASS |  |
| N5-1t | 1048 | 3 | PASS | 844 | 3 | PASS |  |
| N6-1 | 900 | 3 | PASS | 730 | 3 | PASS |  |
| N6-2 | 944 | 3 | PASS | 764 | 3 | PASS |  |
| N6-5b | 978 | 3 | PASS | 794 | 3 | PASS |  |
| N6-6a | 1021 | 3 | PASS | 832 | 3 | PASS |  |
| N6-6c | 1028 | 3 | PASS | 844 | 3 | PASS |  |
| NC5-11a | — | — | ERROR | — | — | ERROR | square: existing 2,000,000 path-search-step guard exceeded; circle: existing 2,000,000 path-search-step guard exceeded |
| NC5-20a | 1085 | 3 | PASS | 835 | 3 | PASS |  |
| P3-1 | 884 | 3 | PASS | — | — | ERROR | circle: existing 2,000,000 path-search-step guard exceeded |
| P3-12 | 955 | 3 | PASS | 749 | 3 | PASS |  |
| P4-1 | 936 | 3 | PASS | 784 | 3 | PASS |  |
| P4-10 | 930 | 3 | PASS | 765 | 3 | PASS |  |
| P4-15 | 843 | 3 | PASS | 690 | 3 | PASS |  |
| P4-19 | 1099 | 3 | PASS | 883 | 3 | PASS |  |
| P4-23 | 992 | 3 | PASS | 797 | 3 | PASS |  |
| P4-24 | 959 | 3 | PASS | 788 | 3 | PASS |  |
| P4-25 | 1006 | 3 | PASS | 799 | 3 | PASS |  |
| P4-43-modified | 1127 | 3 | PASS | 886 | 3 | PASS |  |
| P4-43 | 1189 | 3 | PASS | 945 | 3 | PASS |  |
| P4-47 | 1075 | 3 | PASS | 872 | 3 | PASS |  |
| P5-10_11 | 979 | 3 | PASS | 799 | 3 | PASS |  |
| P5-19 | 1054 | 3 | PASS | 864 | 3 | PASS |  |
| P5-23_24 | 1079 | 3 | PASS | 872 | 3 | PASS |  |
| P5-4 | 1009 | 3 | PASS | 813 | 3 | PASS |  |
| P6-6_11 | 1113 | 3 | PASS | 890 | 3 | PASS |  |
| diamond-square | 993 | 3 | PASS | 768 | 3 | PASS |  |
| hexagon | 930 | 3 | PASS | 762 | 3 | PASS |  |
| weird-one | 1197 | 3 | PASS | 951 | 3 | PASS |  |

### Failure classification and limits

- **NC5-11a, square and circle:** `IllegalStateException: Meander path search
  resource limit exceeded` — the existing Kotlin search guard reaches 2,000,000
  steps (`MeanderFill.kt:52,264`). No PES is exported and peak density is unmeasured.
- **P3-1, circle:** the same existing path-search guard. Its square case passes.
- These are port resource-limit failures, not demonstrated upstream algorithm
  rejection and not a measured sewing-density failure. No Ink/Stitch output was
  executed to attribute the failure to the original. Defaults/limits are unchanged.
- Guided is omitted for lack of a guide; cross, legacy and tartan are outside the
  requested scope. Missing-gradient behavior is recorded rather than silently
  presenting it as a two-color gradient.
- This catalog demonstrates available texture appearances and bounded functional
  results only. It does **not** establish upstream coordinate equivalence or
  real-machine sewing approval. The UI and any safe parameter ranges remain a
  separate decision; no fail-tile exclusion policy is installed here.
- PNGs show the full output including the source-default tatami underlay. Sparse
  meander top texture should not be mistaken for an underlay-free design.

## Test/code scope

- [TextureCatalogTest.kt](../../engine/src/inkstitchPreviewTest/kotlin/io/github/linker2k/myembro/engine/research/TextureCatalogTest.kt)
  covers the complete inventory, both shapes, all 160 accounted outcomes, and PNG
  presence; normal engine tests do not acquire the experimental adapter implicitly.
- [Runner](../../tools/research/texture-catalog.sh) and
  [stdlib summary exporter](../../tools/research/summarize-textures.py).
- Only research/test code and this document/evidence are changed. Stitch generators,
  resource inventory, source defaults, exporter, gate, and app UI are unchanged.
- No push or merge. No personal files or upstream runtime code are used.
