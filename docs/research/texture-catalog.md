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
After the [search-limit follow-up](meander-search-limit.md), the matrix completed
**160 cases: 160 SewoutCheck PASS, 0 generation ERROR, 0 SewoutCheck BLOCK**.
Primary defaults: **158/158 PASS**; supplemental 2-stop gradients: **2/2 PASS**.
The initial `eae1290` audit had 3 port-only search-budget errors; all 3 now pass. Completed cases have peak cell density **≤7**
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
| N3-11a | 1111 | 3 | PASS | 887 | 3 | PASS | |
| N3-12 | 1000 | 3 | PASS | 783 | 3 | PASS | |
| N3-16a | 1065 | 3 | PASS | 869 | 3 | PASS | |
| N3-17 | 1107 | 3 | PASS | 897 | 3 | PASS | |
| N3-18-modified | 1017 | 3 | PASS | 824 | 3 | PASS | |
| N3-18 | 1041 | 3 | PASS | 809 | 3 | PASS | |
| N3-20 | 1040 | 3 | PASS | 834 | 3 | PASS | |
| N3-23b | 1147 | 3 | PASS | 930 | 3 | PASS | |
| N3-25c | 1160 | 3 | PASS | 933 | 3 | PASS | |
| N3-26b | 1272 | 3 | PASS | 993 | 4 | PASS | |
| N3-27 | 1030 | 3 | PASS | 820 | 3 | PASS | |
| N3-30a | 1099 | 3 | PASS | 902 | 3 | PASS | |
| N3-51b | 1152 | 3 | PASS | 924 | 3 | PASS | |
| N3-57f-modified | 1013 | 3 | PASS | 794 | 3 | PASS | |
| N3-58b | 1133 | 3 | PASS | 917 | 3 | PASS | |
| N3-6 | 962 | 3 | PASS | 777 | 3 | PASS | |
| N3-7 | 1130 | 3 | PASS | 907 | 3 | PASS | |
| N3-8a-modified | 1024 | 3 | PASS | 817 | 3 | PASS | |
| N3-8a | 1102 | 3 | PASS | 882 | 3 | PASS | |
| N3-8b | 1094 | 3 | PASS | 879 | 3 | PASS | |
| N4-13b | 1215 | 3 | PASS | 961 | 4 | PASS | |
| N4-13c | 1171 | 4 | PASS | 937 | 3 | PASS | |
| N4-13d | 1144 | 3 | PASS | 916 | 3 | PASS | |
| N4-13e | 1040 | 3 | PASS | 831 | 3 | PASS | |
| N4-13f | 1020 | 3 | PASS | 837 | 3 | PASS | |
| N4-16a | 1133 | 3 | PASS | 913 | 3 | PASS | |
| N4-19 | 1235 | 4 | PASS | 983 | 3 | PASS | |
| N4-20 | 1267 | 3 | PASS | 1016 | 4 | PASS | |
| N4-21c | 1176 | 4 | PASS | 910 | 4 | PASS | |
| N4-22 | 1128 | 3 | PASS | 911 | 3 | PASS | |
| N4-23a | 1078 | 3 | PASS | 852 | 3 | PASS | |
| N4-23c | 1083 | 3 | PASS | 868 | 3 | PASS | |
| N4-27 | 1086 | 3 | PASS | 860 | 3 | PASS | |
| N4-29e | 1303 | 3 | PASS | 1023 | 3 | PASS | |
| N4-29f | 1249 | 3 | PASS | 967 | 3 | PASS | |
| N4-31 | 1110 | 3 | PASS | 898 | 3 | PASS | |
| N4-38 | 1020 | 3 | PASS | 825 | 3 | PASS | |
| N4-42e | 1258 | 3 | PASS | 997 | 3 | PASS | |
| N4-44 | 1158 | 3 | PASS | 935 | 4 | PASS | |
| N4-52 | 1060 | 3 | PASS | 866 | 3 | PASS | |
| N4-54d | 1133 | 3 | PASS | 909 | 3 | PASS | |
| N4-5a-2 | 1011 | 3 | PASS | 798 | 3 | PASS | |
| N4-5a | 1096 | 3 | PASS | 871 | 3 | PASS | |
| N4-82 | 1122 | 3 | PASS | 898 | 3 | PASS | |
| N4-85d | 1192 | 3 | PASS | 968 | 3 | PASS | |
| N5-1e1 | 1068 | 3 | PASS | 846 | 3 | PASS | |
| N5-1q2 | 1056 | 3 | PASS | 879 | 3 | PASS | |
| N5-1t | 1048 | 3 | PASS | 844 | 3 | PASS | |
| N6-1 | 900 | 3 | PASS | 730 | 3 | PASS | |
| N6-2 | 944 | 3 | PASS | 764 | 3 | PASS | |
| N6-5b | 978 | 3 | PASS | 794 | 3 | PASS | |
| N6-6a | 1021 | 3 | PASS | 832 | 3 | PASS | |
| N6-6c | 1028 | 3 | PASS | 844 | 3 | PASS | |
| NC5-11a | 1230 | 3 | PASS | 1007 | 3 | PASS | |
| NC5-20a | 1085 | 3 | PASS | 835 | 3 | PASS | |
| P3-1 | 884 | 3 | PASS | 725 | 3 | PASS | |
| P3-12 | 955 | 3 | PASS | 749 | 3 | PASS | |
| P4-1 | 936 | 3 | PASS | 784 | 3 | PASS | |
| P4-10 | 930 | 3 | PASS | 765 | 3 | PASS | |
| P4-15 | 843 | 3 | PASS | 690 | 3 | PASS | |
| P4-19 | 1099 | 3 | PASS | 883 | 3 | PASS | |
| P4-23 | 992 | 3 | PASS | 797 | 3 | PASS | |
| P4-24 | 959 | 3 | PASS | 788 | 3 | PASS | |
| P4-25 | 1006 | 3 | PASS | 799 | 3 | PASS | |
| P4-43-modified | 1127 | 3 | PASS | 886 | 3 | PASS | |
| P4-43 | 1189 | 3 | PASS | 945 | 3 | PASS | |
| P4-47 | 1075 | 3 | PASS | 872 | 3 | PASS | |
| P5-10_11 | 979 | 3 | PASS | 799 | 3 | PASS | |
| P5-19 | 1054 | 3 | PASS | 864 | 3 | PASS | |
| P5-23_24 | 1079 | 3 | PASS | 872 | 3 | PASS | |
| P5-4 | 1009 | 3 | PASS | 813 | 3 | PASS | |
| P6-6_11 | 1113 | 3 | PASS | 890 | 3 | PASS | |
| diamond-square | 993 | 3 | PASS | 768 | 3 | PASS | |
| hexagon | 930 | 3 | PASS | 762 | 3 | PASS | |
| weird-one | 1197 | 3 | PASS | 951 | 3 | PASS | |

### Initial errors and follow-up

The initial NC5-11a square/circle and P3-1 circle errors were caused by a port-only
2,000,000 cumulative DFS-visit guard, absent from Ink/Stitch v3.3.0. The follow-up
removes that guard, retains upstream 7/10-edge path cutoffs and restores the source
outer-loop cancellation checks. All three now generate and pass; the prior 157
completed cases retain exactly the same stitch counts and peak densities.
See [source-line comparison and tests](meander-search-limit.md).

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
- The initial catalog added research/test code only. The follow-up removes the
  non-upstream meander search budget and adds cancellation checks. Tile resources,
  fill parameters, exporter, gate, and app UI remain unchanged.
- No push or merge. No personal files or upstream runtime code are used.

## Desktop JVM timing and remaining resource guards

### Timing scope and distribution

These are the 160 `elapsed_ms` values from the **single, serial JVM catalog run**
committed with `10fbcef`, not a new benchmark or Android measurement. The interval
in `TextureCatalogTest.kt:50–90` includes fill generation, S1 processing, output
preparation, sew-out checks, PES encoding/decoding and saving, and the individual
PNG rendering/writing. JVM startup, compilation, source SVG writing, final contact
sheets, and the final summary are outside that interval. Lazy initialization/JIT
inside a case is included. There was no warm-up exclusion, repetition, randomized
order, or controlled GC. Do not interpret these numbers as fill-kernel latency or
as a mobile UI responsiveness guarantee.

Median is the ordinary median (mean of the middle 2 values for even counts).
The p95 is nearest-rank: sorted values at index `ceil(0.95 * n) - 1`.

| Cases | n | Median (ms) | p95 (ms) | Maximum (ms) |
|---|---:|---:|---:|---:|
| All | 160 | 75.0 | 243 | 6839 |
| Square | 80 | 69.5 | 252 | 6839 |
| Circle | 80 | 79.0 | 213 | 4871 |
| `tatami_fill` | 2 | 458.0 | 715 | 715 |
| `meander_fill` | 150 | 73.5 | 116 | 3652 |
| `contour_fill` | 2 | 252.5 | 262 | 262 |
| `circular_fill` | 2 | 5855.0 | 6839 | 6839 |
| Gradient without explicit gradient | 2 | 124.5 | 143 | 143 |
| Gradient with 2 stops | 2 | 137.5 | 156 | 156 |

For the groups with only 2 cases, p95 is simply the larger case, not an estimate
of repeated-run tail latency. The case intervals sum to **31.300 s**, compared with
**32.361 s** for the catalog JUnit test and **52 s** for Gradle in the committed
output; these measure different scopes.

| Slowest cases | Time (ms) |
|---|---:|
| Square, circular | 6839 |
| Circle, circular | 4871 |
| Circle, meander `NC5-11a` | 3652 |
| Square, meander `NC5-11a` | 2115 |
| Square, tatami | 715 |
| Circle, meander `P3-1` | 387 |

Recompute the distribution without generating stitches:

```sh
python3 - <<'PY'
import csv, math, statistics
from collections import defaultdict
from pathlib import Path
rows = list(csv.DictReader(Path('docs/research/texture-catalog-results.csv').open()))
groups = defaultdict(list)
for r in rows:
    method = r['method']
    if method == 'linear_gradient_fill':
        method = 'gradient_two_stops' if r['id'].endswith('two_stops') else 'gradient_no_gradient'
    for name in ('All', r['shape'], method):
        groups[name].append(int(r['elapsed_ms']))
for name, values in groups.items():
    values.sort()
    print(name, len(values), statistics.median(values),
          values[math.ceil(.95 * len(values)) - 1], max(values))
assert len(rows) == 160 and all(r['status'] == 'PASS' for r in rows)
PY
```

### Remaining port-only resource bounds

Scope: the catalog fill implementations and their shared fill/running/geometry
helpers, plus the tile SVG reader and the sew-out checker. This is not an audit of
unrelated lettering, ripple, tartan, import, or app limits. References below use
Ink/Stitch v3.3.0, commit
`b0edd96311ece82ee48816dc93466274b12c2a9c`, read without executing upstream code.
Kotlin stitch filenames are relative to
`engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/stitches/`.

Each row below is a port-specific explicit numerical bound: the corresponding
upstream implementation has no matching numerical bound. **The basis of every
listed numerical value is unconfirmed**: the inspected code and documentation do
not establish an upstream default, corpus measurement, or mobile CPU/RAM budget
for that number. Bounding work is a plausible purpose, not numerical calibration.
Upstream can still fail because of dependency, recursion, or memory limits; absence
of an explicit cap does not imply unlimited resources.

| Port location | Remaining bound | Corresponding upstream source | Numerical basis |
|---|---|---|---|
| `MeanderFill.kt:51,157` | Graph nodes ≤ 20,000 | `lib/tiles.py:145–175` | unconfirmed |
| `MeanderFill.kt:145–146` | Tile expansion radius `count` ≤ 225; at the maximum, up to `(2*225)^2 = 202500` candidate placements before bounding-box filtering, not 225 tiles | `lib/tiles.py:148–157` | unconfirmed |
| `MeanderFill.kt:86` | Tile SVG input ≤ 1,000,000 bytes | `lib/tiles.py:22–24` | unconfirmed |
| `MeanderFill.kt:348` | Clipping subdivision count and accumulated output each ≤ 1,000,000 | `lib/utils/clamp_path.py:17–29` | unconfirmed |
| `MeanderFill.kt:39`, `ContourFill.kt:40` | Repeats ≤ 16; bean repeat entries ≤ 20 | `lib/elements/fill_stitch.py:568–597` | unconfirmed |
| `ContourFill.kt:51,151,171` | Contours ≤ 20,000; nesting depth ≤ 512 | `lib/stitches/contour_fill.py:138–165,312–429` | unconfirmed |
| `ContourFill.kt:103` | Interpolation points ≤ 1,000,000 | `lib/stitches/contour_fill.py:436–479` | unconfirmed |
| `CircularFill.kt:64` | Check `rings.size < 100_000` before appending another ring | `lib/stitches/circular_fill.py:52–66` | unconfirmed |
| `CircularFill.kt:89,96,101` | Repeats ≤ 20; bean output ≤ 1,000,000; size ≤ 500,000 checked before repeat doubling (the doubled result may reach 1,000,000) | `lib/stitches/circular_fill.py:102–115`; fill repeat getter above | unconfirmed |
| `LinearGradientFill.kt:95–96,196` | Rows ≤ 200,000; stop row indices within ±1,000,000 | `lib/stitches/linear_gradient_fill.py:94–139,142–265` | unconfirmed |
| `LinearGradientFill.kt:208,217` | Per-row projection count checked below 1,000,000; total points ≤ 1,000,000 | `lib/stitches/linear_gradient_fill.py:127–139` | unconfirmed |
| `Fill.kt:39,71,82` | Row length / max stitch length ≤ 1,000,000; grating iterations ≤ 100,000; segments ≤ 200,000 | `lib/stitches/fill.py:55–180` | unconfirmed |
| `FillRouting.kt:41,144,182` | Input segments ≤ 200,000; routed stitches ≤ 1,000,000; graph edges ≤ 1,000,000 | `lib/stitches/tatami_fill.py:206–280,403–525` | unconfirmed |
| `FillRouting.kt:248` | Odd vertices ≤ 256 and odd vertices × graph edges ≤ 20,000,000 | `lib/stitches/tatami_fill.py:365–384` (NetworkX Euler augmentation) | unconfirmed |
| `FillRouting.kt:322,390` | Travel nodes ≤ 250,000; requested gap-fill repeats ≤ 100 | `lib/stitches/tatami_fill.py:720ff,930ff` gap filling and travel graph | unconfirmed |
| `FillRunning.kt:20,51` | Input points ≤ 200,000; accumulated running samples ≤ 1,000,000 | `lib/stitches/running_stitch.py:307–342,428ff` | unconfirmed |
| `FillGeometry.kt:40` | Smoothing iterations ≤ 10 (default 5 is upstream, upper bound 10 is not) | `lib/utils/smoothing.py:24,49` | unconfirmed |

The shared tile path parser also has local limits in
`engine/.../geometry/SvgPath.kt:14,30–32,49,152`: 200,000 emissions, default
200,000 vertices, Bézier subdivision depth below 24 when subdivision is needed,
and arc subdivisions ≤ 200,000. This parser is a local SVG flattening helper,
not a direct Ink/Stitch algorithm port; the numerical resource-budget basis is
**unconfirmed**. These limits differ from validity checks for finite coordinates,
positive spacing, supported SVG commands, and geometric progress, which are not
counted as resource budgets here.

The sew-out checker has separate analysis budgets in
`engine/.../sewout/SewoutModel.kt:117–118`: `maxCommands=250_000`,
`maxGridCells=200_000`, `maxTracedCells=1_000_000`, `maxFindings=1_000`.
These are app analysis limits, not Ink/Stitch fill parameters; their numerical
budget basis is also **unconfirmed**. An incomplete analysis must not be treated
as a safety PASS. These budgets are distinct from the P1–P8 sewing safety rules.

### Original algorithm cutoffs, not new resource caps

Do not conflate the inventory with the removed cumulative 2,000,000-search-step
cap. The remaining meander path limits of 7/10 edges come from
`lib/stitches/meander_fill.py:138–145,156–163`; cancellation follows its
`check_stop_flag` checks, mapped to JVM interruption. Likewise gradient's
`maxCount = 1000` (`LinearGradientFill.kt:124`) comes from
`lib/stitches/linear_gradient_fill.py:196,266–289`, rather than a new global point
budget. See the [search-limit comparison](meander-search-limit.md) for the removal.

Source references can be inspected at the pinned revision:
[tiles](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/tiles.py),
[fill parameters](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py),
[stitch modules](https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches),
[utility modules](https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/utils).

This follow-up changes documentation only: no cap, default, stitch path, gate, or
UI range is changed, and no catalog generation is repeated. The 160 default-case
PASS results do not establish headroom near these limits. Mobile timing and
memory measurements remain unconfirmed.
