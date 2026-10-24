# Official default tatami oracle (3 cases)

Ink/Stitch v3.3.0, commit `b0edd96311ece82ee48816dc93466274b12c2a9c`.
Original public analytic rectangle, circle and hole inputs, nominal 20mm matrix size.
Copied unchanged from `/tmp/myembro-inkstitch-oracle/out/defaults-v2/fill_stitch/`;
SVGs from the same oracle's `inputs/defaults-v2--*.svg`. No personal design or font used.
Official execution receipts: `*.oracle.json`; bytes and coordinate fixtures remain unchanged.
SHA256: provenance.json. Source algorithms GPL-3.0-or-later (Ink/Stitch contributors).
These are the upstream-default inputs (no custom density or angle), including default underlay.

Tests compare every actual PES needle, including lock stitches, after translation to the
needle-bounds centre on each side. No scaling/rotation/alignment fit or outlier trimming.
Stitch count relative difference <=5%, symmetric point-set Hausdorff <=0.2mm.
Do not regenerate references from our engine or relax tolerances to make tests green.
