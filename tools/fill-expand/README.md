# Color-picture fill expansion sweep

`Sweep.kt` uses the existing production trace/generate/PES/check route. Compile it against
`:engine:jar` and the engine runtime classpath with Kotlin 2.0.21/JDK 17, then run:

```sh
java -Djava.awt.headless=true -cp "$SWEEP_JAR:$ENGINE_RUNTIME_CLASSPATH" SweepKt \
  "$REPOSITORY" "$OUTPUT" [local-proxy.png]
python /tmp/myembro-quality-gate/gate.py check "$OUTPUT/pes" --json "$OUTPUT/gate.json"
python tools/fill-expand/report.py "$OUTPUT" --audit /tmp/myembro-overlap/audit.py
```

The Python interpreter must have the existing audit dependencies and pyembroidery 1.5.1.
No dependency installation is performed by these tools. `report.py` deliberately reuses the
independent local audit unchanged (0.1 mm raster, nominal thread footprint 0.37 mm).
`Sweep.kt` reuses the ColorCleanupSweep face/robot inputs at the retained 3.0 mm² minimum
area. The optional proxy is resized to 192 px preserving aspect ratio, at width 99.8 mm;
it receives no additional noise/contrast correction because it is already a preview crop.
Keep personal inputs and ALL their derived output outside the repository.

The fixed candidate values are 0/0.1/0.2/0.3/0.4 mm. The selection code requires both gates
PASS, penetration p50 ≥0.45 mm, and gap proxy ≤3.32% for every input; absent metrics fail.
It never changes production defaults. Table columns also expose fallback methods and excluded
regions: a passing final PES can still have lost/replaced an unsafe fill through the existing
per-region generation fallback. Inspect those counts before any later default decision.

Expansion reuses `FillGeometry.shrinkOrGrow`, as in `FontTatami`: mm → SVG pixels, top fill
only, original underlay. Source: Ink/Stitch v3.3.0 `lib/elements/fill_stitch.py` at
`b0edd96311ece82ee48816dc93466274b12c2a9c`, `expand_mm` (upstream default 0).
At 0 the original geometry reaches the original routing path without buffer/normalization.
