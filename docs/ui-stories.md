# Feature UI stories — designs that sew reliably

> Written by the integrator on 2026-10-24, revised the same day at the user's direction. Engine-port details: `docs/inkstitch-port.md`.

## User direction (reason for revision)

- "Use it as is" does not mean bringing over every Ink/Stitch feature and screen. It means **do not reinvent proven algorithms; first bring them over and apply them unchanged**.
- Listing settings as Ink/Stitch does is verbose and complicated on a phone app.
- The theme is **designs that sew reliably**. Apply only proven methods automatically, even at the cost of some freedom.
- Detailed settings belong in the next version.

## Principles

1. **Do not create new algorithms.** Port stitch generation directly from Ink/Stitch v3.3.0 and use its defaults. Image processing also uses proven public algorithms (for example, potrace-family contour tracing and standard color quantization). Remove custom heuristics from the default path.
2. **Automatic decisions, minimal input.** Users choose only what (text/font/image), where (position/size), and which colors. The app chooses stitch type, density, underlay, and lock stitches using proven rules.
3. **Make failing combinations unavailable from the outset.** Restrict ranges instead of displaying warnings. For example, a size slider moves only within the range verified for that font and stitch type.
4. **Attach evidence to decision rules.** Numeric values come only from Ink/Stitch defaults or measurements of the user's 334 PE-Design designs. Values without evidence remain "unconfirmed" and are not used. embro-codex calibrates and verifies rules against the measured corpus.
5. **Do not build detailed-settings screens.** Lock-stitch type, density, underlay, and fill-variant screens remain on the "next version" list.

## Stages

Each stage proceeds through gate pass → merge → smoke → alpha release → review of actual user sewing. Merge the next stage only after review of the preceding stage is complete.

| Stage | Engine (ported unchanged) | Visible UI change | Owner |
|---|---|---|---|
| S1 | Lock stitches (Ink/Stitch defaults), minimum stitch/jump lengths, trims | None. Only the output becomes safer | worker |
| S2 | Satin text = Ink/Stitch embroidery fonts (satin-column defaults). TTF fonts remain running; fill is S3 | "Verified embroidery fonts" first in the font list, restricted size ranges | letter + canvas |
| S3 | Tatami fill + underlay (defaults), including TTF text fill | Thick letters/shapes automatically use fill | model |
| S4 | Photo → embroidery (proven tracing/quantization + S2·S3 engines) | Choose photo → color count → result, 3 steps | editor |
| Ongoing | Sew-out check, stitch playback | Viewer badge, playback slider | editor |

**Deferred to the next version:** detailed settings (lock type, density, underlay, pull compensation, etc.), fill variants (contour·guided·wave·circular·gradient·tartan), ripple·bean stitch, SVG import, cloning, cross stitch.

---

## US-0 Check before sending (ongoing)

- The badge at the top of the viewer uses only `통과` / `차단` (Pass / Block). App-generated designs should normally never be blocked.
- If an imported PES file is blocked, show the reason in one line and request confirmation once before sending.
- Stitch playback: a slider and a single play button. No speed selector; speed is determined automatically from length.

## US-1 Automatic finishing (S1)

- No new screen. Always apply Ink/Stitch default lock stitches, minimum-length cleanup, and trims to app-generated stitches.
- Leave imported PES files untouched.
- Completion criteria: pass the quality gate and sew the test set on SE400 without tangling.

## US-2 Text: type, choose, size (S2)

- Only four inputs: text, font, height, color.
- The app chooses stitch type from stroke thickness and height, displaying only a label such as "Auto · Satin". Decision rules follow (codex calibrates values against the corpus).
  - Stroke width within the verified satin-column range: satin
  - Narrower than the verified range or small text: running (PE-Design's Small Text approach)
  - Wider than the verified range: fill (after S4). Until then, that size is unavailable.
- The height slider moves only within the verified range for the selected font. Out-of-range choices are unavailable, so no warning is necessary.
- Remove the custom automatic TTF-to-satin method from the default path.
- Completion criteria: a script name such as "Jung Min" passes the gate and sews without problems.

## US-3 Verified embroidery fonts (S3)

- Put "Verified embroidery fonts" first in the font list. Cards show only the name and an actual-stitch preview.
- Use the height range recorded in the font directly as the slider limit.
- If the font lacks the entered characters, dim it in the list and make it unavailable for selection.

## US-4 Automatic fill (S4)

- Automatically handle areas exceeding satin width in thick letters and shapes with tatami fill and default underlay.
- Use Ink/Stitch default angles and density. No adjustment screen.

## US-5 Photo → embroidery (S5, differentiation)

- The flow has 3 steps: **choose/crop photo → color count (2–6) → review result**.
- Determine contour simplification, removal of small fragments, and per-region stitch types automatically. Discard fragments too small to sew.
- Apply the current custom vectorization in codex/editor only after replacing it with a proven public algorithm.
- The result is a normal editor shape, so position/size/colors can change. Stitch settings cannot.

## Resizing safety rules (ongoing)

- App-generated shapes recalculate stitches on resize, so resizing is unrestricted. However, the US-2 height range still applies.
- Imported PES files cannot regenerate stitches, so restrict resizing to a verified range. Range values are unconfirmed; codex will determine them from evidence.


## Next version: restore the viewer design after process termination

The preview3 (1977bd8) investigation confirmed that returning through Home → process termination →
Recent apps opens MainActivity, but displays `No design open` because the viewer's saved screen/file
path is not reopened in the new ViewerViewModel. Restoring the viewer after successfully rereading
the file remains next-version work. This is not identified as the cause of the One UI Recent apps
entry-failure toast, and viewer restoration is not included in this USB history defensive fix.
