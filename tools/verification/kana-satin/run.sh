#!/usr/bin/env bash
set -euo pipefail
# Own harness only; downloaded font data is read, never executed. No upstream source execution.
# Usage: run.sh hangul|before|after WORKTREE ABSOLUTE_OUTPUT [ABSOLUTE_FONTS]
mode=${1:?hangul, before or after}; tree=${2:?worktree}; output=${3:?absolute output directory}
case "$mode" in hangul|before|after);; *) exit 2;; esac
root=$(cd -- "$(dirname -- "$0")" && pwd)
export MYEMBRO_KANA_PROBE_SOURCE="$root/$mode"
export JAVA_HOME=${JAVA_HOME:-/home/gosh2/.jdks/jdk-17.0.20.1+1}
mkdir -p -- "$output"
if [[ "$mode" == hangul ]]; then
 "$tree/gradlew" -p "$tree" -I "$root/probe.gradle" :app:testDebugUnitTest \
  --tests '*HangulInvarianceProbeTest' "-Phangul.out=$output" --console=plain
else
 "$tree/gradlew" -p "$tree" -I "$root/probe.gradle" :app:testDebugUnitTest \
  --tests '*KanaDakutenProbeTest' "-Pkana.out=$output" \
  "-Pkana.fonts=${4:-/tmp/myembro-cjk-satin-probe/fonts}" --console=plain
fi
