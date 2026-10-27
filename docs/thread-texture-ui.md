# Thread texture UI and engine hookup

The UI seam (`5da15b6`) now connects to `agent/thread-texture` at `328bcf8`.
The default remains off: ordinary color drawing uses its unchanged contour/fill
path. The experimental texture switch appears only in Color drawing.

## Presets and input

Detail defaults to Fine. The approved mapping uses engine constants, without
changing engine thresholds or its 108-case sweep:

| UI detail | Engine constant | Requested raster pitch |
|---|---|---|
| Fine (default) | `TextureRasterPolicy.fineMinimumMmPerPixel` | 0.45mm/px |
| Normal | `TextureRasterPolicy.coarseMmPerPixel.first()` | 0.50mm/px |
| Coarse | `TextureRasterPolicy.coarseMmPerPixel.last()` | 0.55mm/px |

Raster width is `TextureRasterPolicy.edge(pictureWidthMm, pitch)`; height follows
the existing hoop crop aspect, rounded down. Thus actual physical pitch is never
finer than requested. This texture-only sampling leaves the ordinary photo raster
policy untouched. See [engine pitch evidence](research/thread-texture-raster-pitch.md).

The previous stitch-length choices are removed from UI, state and interface.
The engine's unchanged Ink/Stitch running default is 2.5mm; no unmeasured length
presets are introduced.

Step 2 applies the existing noise/contrast correction and optional BT.709 grayscale
at the texture working size, then the engine's Wu/Brother palette helper with the
selected color count (2–6). Grayscale selects the existing neutral Brother family.
The preview retains corrected pre-quantization pixels and alpha, so tonal variation
is available to DrawingBot. It performs no contour tracing or stitching. Transparent
color input retains the existing correction bypass. No fake is used in production.

`ThreadTextureGenerator.generate(pixels,w,h,palette,detail)` takes copied ARGB pixels
and matched Brother threads. `EngineThreadTextureGenerator` binds physical width
and hoop, delegates unchanged plotting/running/locking to `ThreadTextureAdapter`,
and forwards cancellation/progress. Step 3 runs on `Dispatchers.Default` through
`runInterruptible` with the existing timeout. Cancellation invalidates the revision,
interrupts the worker, and discards partial output. The UI shows progress and Cancel.

## Blocking and editing

`ThreadTextureOutputGate` checks the candidate against the selected machine/hoop
before invoking the PES writer, then checks the resulting PES bytes again. Any
blocker or incomplete analysis returns no bytes. The candidate remains visible
with a blocked sewing-check badge. `DENSITY_HOTSPOT` shows “Too dense: try Coarse detail.”
`STITCH_LIMIT_EXCEEDED` shows “Too many stitches: reduce the size or try Coarse
detail.” When both block, stitch-count guidance takes precedence. Selection uses
finding codes/severity, never engine message text. Other blockers show sewing-check guidance. Sending
and editing are disabled. MainActivity guards the edit callback too, so a disabled
button cannot be bypassed by calling its callback.

Texture results leave `shapeObject` null and enter the editor as `StitchObject`,
named “Thread picture texture” / “실 그림 텍스처”. The existing message explains
that individual color blocks cannot be edited. Incoming AI titles do not replace
this texture name. Ordinary color block conversion is unchanged.

## Tests

`ThreadTexturePreviewTest` covers default-off pattern/PES byte identity, shared
correction/grayscale input, all 3 preset mappings, real engine generation plus PES
checks at each preset, cancellation, and density/hoop blockers with no output bytes
or edit permission. Synthetic images only. No emulator execution in this unit.

Validation command: `./gradlew :app:testDebugUnitTest :engine:test --console=plain`.
The initial full run returned `BUILD SUCCESSFUL in 2m 32s`; final verification after
empty-result/edit-callback hardening returned `BUILD SUCCESSFUL in 50s`.
XML totals: app 453 tests, engine 986 tests, 0 failures and 0 errors in both.
The 6 UI adapter tests include real 40mm synthetic-ramp runs:
Coarse 1,833 stitches, Normal 1,923 stitches, Fine 2,209 stitches; all 3 written PES
checks have no blockers. These measurements are synthetic regression evidence,
not a guarantee for arbitrary photos or an actual sew-out verdict.

Device-review guidance correction: density blockers recommend Coarse detail only;
stitch-count blockers recommend a smaller size or Coarse detail and take precedence
when both occur. `blockerMessagesUseCodesWithStitchLimitPriority` tests both codes,
both insertion orders, warning-only codes and generic fallback. Validation:
`./gradlew :app:testDebugUnitTest --console=plain` → `BUILD SUCCESSFUL in 58s`;
454 tests, 0 failures, 0 errors.
