# myembro — Shared rules for agents

Every coding agent (Codex, Claude Code) reads this file before starting work. The same rules also exist in code (`engine/.../sewout/SewoutCheck`, the `/tmp/myembro-quality-gate` gate). A file that violates them is blocked from building, saving and sending. This document exists so that you **never produce a violation in the first place**. When a rule changes, change the document, the code and the tests together in one commit.

## PES/PEC output invariants (confirmed by real SE400 incidents)

| # | Rule | Basis |
|---|---|---|
| P1 | PEC stitch coordinates must have **minimum x = 0 and minimum y = 0**. The coordinate origin is the top-left corner of the design, and **negative coordinates are forbidden**. | All 334/334 of the user's PE-Design designs use a top-left origin. A file written with a center origin had its stitches shifted up and left on the SE400 by half the design size. In that state it hit the hoop limit and stitched the same spot more than 20 times (2026-10-24). |
| P2 | Maximum x ≤ hoop width, maximum y ≤ hoop height. Compare in **integer coordinates** of 0.1mm units, with no floating-point tolerance or margin. Coordinates are integers starting at 0, so the allowed range is **0 to (number of hoop units − 1)**. For a 100mm hoop, 0–999 is allowed and 1000 and above is forbidden. Whether the machine can reach exactly the 100.0mm boundary is unverified, and no design in the corpus reaches 1000, so the limit is set conservatively. **It applies identically to every hoop**, including the default hoop, machine-specific hoops and user-defined hoops. Hoop values come only from the app's `HoopSpec`, in one place. | The hoop travel range is fixed by the machine and cannot be exceeded by even 0.1mm (user instruction, 2026-10-24). |
| P2a | **The safety margin is 0.1mm (1 unit), and the 0–999 rule of P2 is that margin.** No extra margin is added (the 2mm margin is dropped). The maximum design size is 99.9mm (999 units). | User decision (2026-10-24): "0.1mm is enough". Giving up 0.1mm is not a loss. |
| P3 | The width/height in the PEC header must exactly equal the actual stitch extent. Not even a 1-unit (0.1mm) difference is allowed. | The machine places the design and sets its travel limits from the header size. |
| P4 | **0-length JUMPs and 0-length needle penetrations (stitches) are forbidden**, including at thread start and after a trim. When stitching restarts at the same point after a trim, remove both the trim and the JUMP and continue stitching. | The PE-Design corpus has no 0-length commands. Starts without them (T1 and T3 in the A/B test) sewed normally on the real machine. |
| P5 | Consecutive needle penetrations at the same position must be **at most 2**. 0-length penetrations count toward this. | Corpus distribution: 1 time 173 files, 2 times 158, 3 times 1, 5 times 2; p99 is 2. |
| P6 | Lock stitches use the Ink/Stitch default `half_stitch`. Self-invented locks are forbidden. Where `ties=3`, as with the first element of a font, the start lock is guaranteed through the upstream parameter. | Equivalent to the Ink/Stitch v3.3.0 original. It was normal in real-machine test T3. |
| P7 | A single 0.5mm cell must not receive more than 15 needle penetrations (app output). Imported PES are blocked only above 30. | Set from the corpus p99. The file that produced a bird's nest had 38. |
| P8 | Maximum stitch length ≤ 12.0mm. | The corpus maximum is 12.0mm. |

## Working principles

- Do not invent new algorithms. Port verified originals (Ink/Stitch v3.3.0, potrace, Wu quantization) with their default values as-is, and prove by automated comparison that the result matches the original.
- Models do not judge what can be measured. Code and tests judge, and reports attach the command or test output.
- Every number used in a rule needs a basis: an upstream default, a corpus measurement or the original code. Without a basis, mark it "unverified" and do not use it.
- Personal designs (the user's PE-Design library, uploaded files) are never committed and never used as test fixtures. Analyze them only locally in `/tmp`.
- Give every real-machine test file a new name. Use only one color (40wt) per file, and keep it small, within 60×60mm.
