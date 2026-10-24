# Fixed representative font oracle (test data only)

- Ink/Stitch v3.3.0, commit `b0edd96311ece82ee48816dc93466274b12c2a9c`.
- Original SVG/metadata: https://github.com/inkstitch/inkstitch/tree/b0edd96311ece82ee48816dc93466274b12c2a9c/tests/lettering/fonts/testfont .
- Official release: https://github.com/inkstitch/inkstitch/releases/tag/v3.3.0 .
- Official binary outputs were produced independently by embro-codex; coordinates came from
  pyembroidery 1.5.1 and a separate bounded PEC decoder agreeing on needle positions.
  Release archive/binary, original PES, decoded-coordinate, record and input SHA256 values are
  preserved in `oracle.json`. Coordinate hashes refer to the original complete compressed decoder
  snapshots; this file stores only their needle/control/thread subset. The test never regenerates
  its own expected data.
- Cases: AB 4/10/20mm; AB newline BA 10mm. Four cases, 331 needle coordinates.
- Font input is the upstream generic A/B test fixture, not a catalog or personal font.
  Upstream repository is GPL-3.0-or-later (`LICENSE-GPL-3.0.txt`); its font metadata declares
  SIL OFL-1.1 (standard text in `OFL.txt`). Copyright Ink/Stitch contributors; source metadata is unchanged.
- `font-newline.json` is the independently corrected oracle input: only reversible=false differs
  semantically from `font.json`, because the fixture has no reverse variant. This produces both
  lines; the obsolete one-line output is not included.
- Comparison: exact needle count, symmetric nearest-needle distance <=0.2mm, ordered trim/stop/color
  commands and their bbox-centered anchors <=0.2mm. These coordinate tolerances are from the
  pre-existing frozen oracle criteria. The observed residual is 0.1mm, documented in docs/inkstitch-port.md.
- The one extra official initial positioning JUMP is excluded; no needle, trim, stop or color command is omitted.
- Default source parameters and forceBoundaryLocks=false. Product thread-boundary overrides,
  satin catalog coverage, hidden underlay semantics and actual machine sew-out are separate checks.
- No Python/runtime dependency, network, external /tmp input, Ink/Stitch execution or user PES is used by CI.
