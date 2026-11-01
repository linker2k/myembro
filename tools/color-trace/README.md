# Color-drawing regions: parity with the sample-pack converter

`png2svg_claude_v2.py` is the sample-pack converter (cute-art pack, unchanged copy). The app's
color drawing ports its color-region steps in `SampleColorRegions.kt`: Lab k-means 10, folding
clusters under 0.5% of the sample, merging pairs under ΔE 18 down to the color slider (+1 white
slot), dropping border-connected white and repainting pieces under 2mm diameter. Its SVG
smoothing is not ported; the existing Potrace route traces each color instead.

`reference_labels.py` runs the same steps and dumps NumPy's 40000-pixel sample, the first k-means
index, the final labels and the palette. `Parity.kt` feeds that sample into the Kotlin port and
counts differing labels. The port's own sampler (Kotlin `Random`) replaces NumPy's generator.

```sh
python reference_labels.py input.png out/prefix SIZE_MM MAX_COLORS   # scikit-image 0.25.2, SciPy 1.11.4, NumPy 1.26.4
java -cp "$PARITY_CLASSES:$ENGINE_RUNTIME_CLASSPATH" ParityKt input.png out/prefix
```

Result 2026-11-01 (cute-art 01-skull, 07-sitting-cat, 20-baby-trex, 1024px): palettes equal and
0/1,048,576 labels differ with NumPy's sample; with the port's own sample, ≤60 pixels differ and
palette channels differ by ≤1.

## Contour budget

`ColorDrawingPipeline.MAX_REGION_CONTOURS` keeps the existing 128 mobile budget. Measured
2026-11-01 on the 127 cute-art fill inputs (1024px, 1254px inputs scaled down) at 99.9mm:
contour budget p50 25, p90 99, max 275; 7/127 exceed 128. The 275-contour picture took 143s to
generate stitches on a PC JVM and the 124-contour one 25s; both exceeded the 30,000-stitch sew-out limit at 99.9mm (43,073 for the 124-contour one).
