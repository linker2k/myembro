#!/usr/bin/env bash
set -euo pipefail
# Explicit dev device only: release 5554 and canvas 5558 must never be installed here.
cd "$(dirname "$0")/../.."
export JAVA_HOME="${JAVA_HOME:-/home/gosh2/.jdks/jdk-17.0.20.1+1}"
adb_bin="${ADB:-/home/gosh2/Android/Sdk/platform-tools/adb}"
output="${1:-engine/src/test/resources/font-snapshots}"
runner=io.github.linker2k.myembro.ui.lettering.FontSnapshotInstrumentation
"$adb_bin" -s emulator-5556 get-state
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner="$runner"
"$adb_bin" -s emulator-5556 install -r -d -t app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s emulator-5556 install -r -d -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb_bin" -s emulator-5556 shell run-as io.github.linker2k.myembro rm -rf files/font-snapshots
"$adb_bin" -s emulator-5556 shell am instrument -w io.github.linker2k.myembro.test/"$runner"
"$adb_bin" -s emulator-5556 shell run-as io.github.linker2k.myembro test -f files/font-snapshots/CAPTURE_OK
mkdir -p "$output"
archive="$(mktemp /tmp/myembro-font-snapshots.XXXXXX.tar)"
trap 'rm -f "$archive"' EXIT
"$adb_bin" -s emulator-5556 exec-out run-as io.github.linker2k.myembro tar -C files/font-snapshots -cf - . > "$archive"
python3 tools/font-snapshots/collect.py "$archive" "$output"
