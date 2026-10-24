# SE400 start-command A/B fixtures

`python3 tools/generate_ab_sewtest.py` creates `/tmp/myembro-sewtest/ab/` with three
PES v1 files, PNGs, raw PEC first-16-event hex dumps, byte summary and independent
gate output. Uses our unmodified engine and PesWriter. No private designs.

All share the same 10×10mm square, 1mm running spacing, single black 40wt thread,
no trims and an initial nonzero jump to (1,1)mm. Initial position, body, container,
writer and thread are controlled across cases.

- T1PEDS: two 0.3mm out-and-return stitches at both ends; zero moves removed.
- T2OURS: current generated half_stitch plus SamplerCli's redundant first jump;
  raw prefix includes `90 00 90 00` and `00 00`.
- T3NOZRO: T2 with only zero-displacement JUMP/STITCH commands removed, leaving
  the same nonzero half_stitch lock geometry.

Initial frozen-v5 check: PASS 3/3. Counts 44 / 49 / 48 needles; estimates 6.60 /
7.35 / 7.20 seconds at 400spm, excluding setup. Gate policy revisions may
intentionally block the diagnostic T2. This is a controlled fixture, not a
recommendation to keep zero moves. Needle positions are annotated in the PNGs;
preview labels are never stitched.
