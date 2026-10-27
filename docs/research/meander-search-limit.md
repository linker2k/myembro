# Meander ERROR 3: port-only cumulative search budget

Verdict **(a): the limit was not an Ink/Stitch v3.3.0 limit**. Do not classify
these inputs as failures of the original program. The fixed Kotlin cases now
complete with the original path-length cutoffs and pass SewoutCheck.

## Static source comparison

Original source was read only at stable v3.3.0 commit
`b0edd96311ece82ee48816dc93466274b12c2a9c`; it was not executed and no original
runtime/dependencies were installed. This establishes the source control-flow
comparison, not a new full coordinate-equivalence oracle.

| Behavior | Ink/Stitch original | Previous port (`eae1290`) | Minimal correction |
|---|---|---|---|
| Replace one edge | `nx.all_simple_edge_paths(..., 7)`; accept length >1, `lib/stitches/meander_fill.py:138–145` | Same 7-edge cutoff, but accumulated every DFS entry against a global budget | Keep 7 unchanged |
| Replace two edges | `nx.all_simple_edge_paths(..., 10)`; accept length >2, `meander_fill.py:156–163` | Same 10-edge cutoff sharing the same budget | Keep 10 unchanged |
| Cumulative search visits | No numeric visit/iteration limit in `generate_meander_path` or either replacement helper (`meander_fill.py:106–171`) | `MAX_SEARCH_STEPS=2_000_000`, `steps=0` once per whole `generatePath`, increment/check at every recursive DFS entry | Remove the constant, counter and throw; do not merely raise its value |
| Stop/cancel | `check_stop_flag()` in each edge/edge-pair loop (`meander_fill.py:116,123`); `utils/threading.py:19–23` raises `ExitThread` if the preview's stop event is set | No corresponding stop check in these loops | At the same loop positions, map stop to JVM thread interruption and throw `InterruptedException`; preserve the interrupt flag |

Pinned links:
[main loop, meander_fill.py:106–135](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/meander_fill.py#L106-L135),
[replacement helpers, 138–171](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/meander_fill.py#L138-L171),
[stop flag, utils/threading.py:19–23](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/utils/threading.py#L19-L23).

The removed guard counted **all alternatives cumulatively**, not just the length
of any one alternative. Many finite, source-allowed searches could therefore
consume it. Source replacements add unused nodes to the route; the finite graph
and 7/10-edge simple-path searches remain. Cancellation has the original outer-loop
scope, not a new timeout or altered search order. This does not promise bounded
wall-clock time on every shape.

No tile scale/angle/seed, stitch density, path-selection order, tie-breaking,
underlay, exporter or sewing thresholds changed. Existing node/tile-expansion and
other resource guards are outside this minimal change and are **not** claimed to
be original Ink/Stitch limits.

## Same inputs, before/after

Exactly the earlier 40mm square and 40mm-diameter circle, unchanged
`texture-square` / `texture-circle` IDs (resolved default seeds), 100% tile scale,
source defaults and S1 output/check path from [the catalog](texture-catalog.md).

| Tile / shape | Before | After | Needle count | Peak / 0.5mm cell | Measured generation + check + thumbnail |
|---|---|---|---:|---:|---:|
| NC5-11a / square | ERROR: 2,000,000 cumulative visits | PASS | 1,230 | 3 | 2.115s |
| NC5-11a / circle | ERROR: same guard | PASS | 1,007 | 3 | 3.652s |
| P3-1 / circle | ERROR: same guard | PASS | 725 | 3 | 0.387s |

Full repeat: **160/160 SewoutCheck PASS, no ERROR/BLOCK**, maximum observed cell
peak remains **7**. Every previously completed case (157) has unchanged needle
count and peak density. Timing is one measured run, not a speed guarantee.
[Exact before/after rows](meander-search-limit-comparison.json).

## Tests and output

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 tools/research/texture-catalog.sh
```

The runner now selects both `TextureCatalogTest` and `MeanderFillTest`:

- Catalog: 1 test, 0 failures/errors; all 160 outcomes recorded, with explicit
  regression assertions that the three formerly capped cases are PASS.
- Meander unit tests: **15 tests, 0 failures/errors**. New stop-check test interrupts
  the current thread before generation, asserts `InterruptedException` and the
  retained interrupt flag, and clears it in `finally` to protect the test worker.
- Combined: **16 tests, no failures/errors**; `BUILD SUCCESSFUL in 52s`.
- Existing seeded graph/path, PRNG, clipping, repeat and tile-loader tests still pass.

[Actual command and JUnit output](meander-search-limit-output.txt).
Updated individual PNG/PES files and contact sheets remain in
`/tmp/myembro-texture/`; the recovered three now have completed previews and PES.

The evidence selects (a), not (b): no unchanged-code claim that upstream rejects
these shapes and no app “keep previous settings” implementation is needed to
explain these errors. General UI failure/cancellation behavior remains separate;
this patch changes no app screen. No real sewout or full upstream-coordinate
identity is claimed. No push or merge.
