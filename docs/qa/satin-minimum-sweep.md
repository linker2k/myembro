# Satin lower-bound sweep (2026-10-24)

Purpose: determine `StitchTypeRule.SATIN_MIN_MM` (0.9mm) through computation. Narrow regions
were created at different widths, the rule's lower bound was lowered to 0, and the region-satin
path (`RegionSatin`, the same path as letter's Hangul satin) was forced. Default app PES output
was judged by `SewoutCheck` (including P1–P8).

Reproduction: `SATIN_SWEEP=<dir> ./gradlew :engine:test --tests '*SatinMinimumSweep*'` (about 8 seconds)

o = complete satin + 0 blockers, x = failure

| Shape \ width (mm) | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 | 1.0 | 1.1 | 1.2 | 1.3 | 1.4 | 1.5 | 1.6 | 1.7 | 1.8 | 1.9 | 2.0 | 2.5 | 3.0 | 4.0 | 5.0 | 6.0 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| bar20 | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o |
| arc | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | x |
| ring | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o |
| zigzag | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | x | o | x | x |
| bar6 | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | x |

Failure details:

- arc 6.0mm: no complete satin ([TATAMI])
- zigzag 3.0mm: no complete satin ([TATAMI])
- zigzag 5.0mm: no complete satin ([TATAMI])
- zigzag 6.0mm: no complete satin ([TATAMI])
- bar6 6.0mm: no complete satin ([TATAMI])

## Conclusions

- **The gate does not establish a lower bound.** All shapes produce complete satin down to
  0.2mm, with 0 blockers. Every failure is on the wide end (maximum width at a bend exceeds
  6mm and the rule sends it to tatami), unrelated to the lower bound.
- Therefore, this sweep cannot establish 0.9mm. There are two available bases.
  - 0.9mm: crossing-stitch length p5 from the user's 334 PE-Design designs
    (`/tmp/myembro-quality-gate/auto-rules.md`, adopted integrator policy). This is crossing-stitch
    length rather than column width; the same document notes that it is weak evidence for a
    lower bound because the detector excludes values below 0.7mm.
  - 1mm: the `min_satin_stroke_width` default in Ink/Stitch v3.3.0 `lib/utils/settings.py`
    (original default).
- The range in which the decision changes (0.9–1.0mm) is narrow. Choosing the value is a policy decision.
