#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Uses a caller-supplied read-only source tree. No downloads, installation, or Ink/Stitch execution.
set -euo pipefail
source_dir=${1:?Usage: generate-stage4-goldens.sh /path/to/potrace-1.16 [output-directory]}
repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
stage=${3:-4}
[[ "$stage" == 4 || "$stage" == 5 ]]
optimized=$((stage - 4))
output_dir=${2:-/tmp/myembro-port-out/editor/potrace-stage$stage}
mkdir -p "$output_dir"
# Refuse a different upstream algorithm; source tree is never modified.
for entry in 'decompose.c a54e9a5af575ecdf974ed8686e0045628c03beda9d0869759963fefdb7efdf0f' 'trace.c f70964c69d8fec779e4f3968ddb5fcdc6c45a2666f9d96195fafd82543e195fe'; do
  read -r name digest <<< "$entry"
  actual=$(sha256sum "$source_dir/src/$name")
  test "${actual%% *}" = "$digest"
done
cc -std=c99 -O0 -DHAVE_INTTYPES_H=1 '-DVERSION="1.16"' -I "$source_dir/src" \
  "$repo_dir/tools/potrace/stage4_oracle.c" \
  "$source_dir/src/potracelib.c" "$source_dir/src/decompose.c" \
  "$source_dir/src/curve.c" "$source_dir/src/trace.c" -lm -o "$output_dir/stage4-oracle"
for fixture in rectangle staircase ring rounded; do
  "$output_dir/stage4-oracle" "$repo_dir/engine/src/test/resources/potrace/$fixture.pbm" "$optimized" > "$output_dir/$fixture.stage$stage.json"
done
printf 'Original stage %s paths: %s\n' "$stage" "$output_dir"
