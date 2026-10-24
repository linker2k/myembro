#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export JAVA_HOME="${JAVA_HOME:-/home/gosh2/.jdks/jdk-17.0.20.1+1}"
adb_bin="${ADB:-/home/gosh2/Android/Sdk/platform-tools/adb}"
runner=io.github.linker2k.myembro.ui.lettering.FontFillProbeInstrumentation
./gradlew :engine:cleanTest :engine:test --tests '*FontFillProbeTest' :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner="$runner"
"$adb_bin" -s emulator-5556 install -r -d -t app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s emulator-5556 install -r -d -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb_bin" -s emulator-5556 shell run-as io.github.linker2k.myembro rm -f files/font-fill-probe.json.gz
"$adb_bin" -s emulator-5556 shell am instrument -w io.github.linker2k.myembro.test/"$runner"
"$adb_bin" -s emulator-5556 exec-out run-as io.github.linker2k.myembro cat files/font-fill-probe.json.gz > /tmp/myembro-font-fill-probe/android.json.gz
python3 tools/font-snapshots/compare-fill-traces.py /tmp/myembro-font-fill-probe/jvm.json /tmp/myembro-font-fill-probe/android.json.gz | tee /tmp/myembro-font-fill-probe/comparison.txt
