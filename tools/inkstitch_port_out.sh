#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Reproduces claude-canvas's port PES for the fixed Ink/Stitch matrix (fonts, svg_import, clone).
# Reads the matrix inputs as data only; nothing from Ink/Stitch is run or installed.
#
#   tools/inkstitch_port_out.sh            # all three features
#   FEATURES=clone tools/inkstitch_port_out.sh
#
# Output: $OUT/<feature>/<case output_basename>.pes and $OUT/results.json (matrix hash, commit, every case once).
set -euo pipefail

MATRIX=${MATRIX:-/tmp/myembro-quality-gate/matrix-inkstitch.json}
OUT=${OUT:-/tmp/myembro-port-out/claude-canvas}
FEATURES=${FEATURES:-fonts,svg_import,clone}
FONTS=${FONTS:-/tmp/myembro-quality-gate/inkstitch/inputs/upstream-fonts}
REPO=$(cd "$(dirname "$0")/.." && pwd)

commit=$(git -C "$REPO" rev-parse HEAD)
if [ -n "$(git -C "$REPO" status --porcelain -- engine)" ]; then commit="$commit-dirty"; fi

# The engine reads plain files; compressed upstream fixtures (.xz) are expanded as data first.
fixtures="$OUT/fixtures"
rm -rf "$fixtures"
mkdir -p "$fixtures"
(cd "$FONTS" && find . -type f) | while read -r f; do
  dest="$fixtures/${f#./}"
  mkdir -p "$(dirname "$dest")"
  case "$f" in
    *.xz) xz -dc "$FONTS/$f" > "${dest%.xz}" ;;
    *) cp "$FONTS/$f" "$dest" ;;
  esac
done

mkdir -p "$OUT"
cd "$REPO"
MYEMBRO_PORT_MATRIX="$MATRIX" MYEMBRO_PORT_OUT="$OUT" MYEMBRO_PORT_FIXTURES="$fixtures" \
MYEMBRO_PORT_FEATURES="$FEATURES" MYEMBRO_PORT_COMMIT="$commit" \
  ./gradlew -q :engine:cleanTest :engine:test --tests 'io.github.linker2k.myembro.engine.inkstitch.CanvasPortOutTool' -i \
  | grep -E '^claude-canvas port out' || true
echo "results: $OUT/results.json (commit $commit)"
