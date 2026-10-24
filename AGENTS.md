# myembro — shared agent rules

All coding agents(Codex, Claude Code) read this file before working. The same rules exist in code(`engine/.../sewout/SewoutCheck`, `/tmp/myembro-quality-gate` gate). Violating files are blocked from building, saving, and transfer. This document exists to require **avoiding violations from the outset**. When changing a rule, update documentation, code, and tests together in one commit.

## PES/PEC output invariants (confirmed in an SE400 hardware incident)

| # | Rule | Evidence |
|---|---|---|
| P1 | PEC stitch coordinates must have **minimum x = 0, minimum y = 0**. The coordinate origin is the design's top-left corner; **negative coordinates are prohibited**. | All 334/334 user PE-Design designs use a top-left origin. Files written with a centered origin shifted stitches toward the top left by half the design size on SE400. At the hoop limit, the needle entered the same location more than 20 times(2026-10-24). |
| P2 | Maximum x ≤ hoop width, maximum y ≤ hoop height. Compare **integer coordinates** in 0.1mm units without floating-point tolerance or allowance. Coordinates are 0-based integers, so the allowed interval is **0 ~ (hoop unit count − 1)**. For a 100mm hoop, allow 0–999 and prohibit 1000 and above. Whether the machine can reach exactly the 100.0mm boundary is unconfirmed; no corpus design reaches 1000, so this is conservative. **Apply identically to every hoop**, including default, machine-specific, and custom hoops. Obtain hoop values only from the app's `HoopSpec`. | The machine's hoop travel range is fixed and cannot be exceeded even by 0.1mm(user instruction 2026-10-24). |
| P2a | **The safety margin is 0.1mm(1 unit); P2's 0–999 rule itself provides this margin.** No additional margin(the 2mm margin is discarded). Maximum design size is 99.9mm(999 units). | User decision(2026-10-24): "0.1mm is enough." Giving up 0.1mm is not a loss. |
| P3 | PEC header width/height must exactly equal actual stitch extents. Even a 1 unit(0.1mm) difference is prohibited. | The machine uses header dimensions to position the design and determine travel limits. |
| P4 | **0-length JUMPs and 0-length needle stitches are prohibited**, including thread starts and after trims. If restarting at the same point after trimming, remove both TRIM and JUMP and sew continuously. | The PE-Design corpus has no 0-length commands. Starts without these commands(A/B trial T1·T3) sewed normally on hardware. |
| P5 | Consecutive entries at the same needle position must be **2 or fewer**. Include 0-length needle stitches in this count. | Corpus distribution: 1 entry in 173 files, 2 in 158, 3 in 1, and 5 in 2; p99 is 2. |
| P6 | Use Ink/Stitch default `half_stitch` locks. Custom-designed locks are prohibited. For `ties=3`, as on a font's first element, guarantee a start lock through upstream parameters. | Equivalent to upstream Ink/Stitch v3.3.0. Normal in hardware trial T3. |
| P7 | Needle entries must not exceed 15 in one 0.5mm cell(app output). Imported PES are blocked only above 30. | Set from corpus p99. The birdnest file had 38 entries. |
| P8 | Maximum stitch length ≤ 12.0mm. | Corpus maximum is 12.0mm. |

## Working principles

- Do not create new algorithms. Port verified originals(Ink/Stitch v3.3.0, potrace, Wu quantization) with their defaults and prove matching results through automated comparison.
- Models do not judge measurable quantities. Code and tests determine results; attach commands or test output to reports.
- Numbers in rules need evidence: upstream defaults, corpus measurements, or original code. Without evidence, label them "unconfirmed" and do not use them.
- Never commit private designs(user PE-Design libraries, uploaded files) or use them as test fixtures. Analyze only in local `/tmp`.
- Give hardware-test files new names every time. Use one color(40wt) per file and keep designs small, within 60×60mm.
