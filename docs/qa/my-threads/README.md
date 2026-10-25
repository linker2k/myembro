# My threads verification

Branch: `feature/my-threads`, feature commit `c266e12`.
Screenshots and full local logs: `/tmp/myembro-mythreads-shots/`.
Only `emulator-5556` was used. No installation on release emulator 5554.
The input is the repository fixture `engine/src/test/resources/image-to-design/face.png`,
packaged through the existing androidTest asset source set. No personal images were used.

## Whole-project check

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check
```

The first completed run reported `BUILD SUCCESSFUL in 3m 53s`.
The final run, including the completed screen-smoke source in lint, reported
`BUILD SUCCESSFUL in 32s` (75 tasks: 4 executed, 71 up-to-date).
JUnit XML totals: engine 862, app debug 347, app release 347, MCP 10;
1,566 tests, 0 failures, 0 errors, 0 skipped. MCP stdio also reported
`MCP_STDIO PASS: JSON-only stdout; exact 69 schemas; parse error; EOF exit`.

## Repeatable actual-screen smoke

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew \
  -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.MyThreadsSmokeInstrumentation \
  :app:assembleDebug :app:assembleDebugAndroidTest
ADB="$HOME/Android/Sdk/platform-tools/adb"
"$ADB" -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s emulator-5556 shell cmd locale set-app-locales io.github.linker2k.myembro --locales ko
"$ADB" -s emulator-5556 shell am instrument -w \
  io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.photo.MyThreadsSmokeInstrumentation
# Restore the original app locale (empty before this run).
"$ADB" -s emulator-5556 shell cmd locale set-app-locales io.github.linker2k.myembro
```

The runner opens the real SettingsScreen and PhotoScreen. It searches `Blue`, checks
`2 · Blue`, and asserts the saved selection. It generates a 20mm color picture with
only that owned color enabled and asserts every required thread is Brother 2. It then
generates with restriction disabled while retaining the owned list, and asserts a
required color is missing and the actual ownership label is accessible on screen.
The runner restores the previous owned-thread preference in `finally`.

```text
MY_THREADS_UI_PASS search=Blue checked=2 owned_only_threads=1 missing_label=true input=engine/src/test/resources/image-to-design/face.png
INSTRUMENTATION_CODE: -1
```

| Screenshot | Verified content |
|---|---|
| `01-settings-search-checked.png` | Korean settings dialog, Blue search, Brother 2 checked |
| `02-color-owned-only.png` | Generated blue picture; required thread list contains only `2 · Blue` |
| `03-required-missing.png` | Unrestricted color result; `내 실에 없음` labels visible |

The runner writes files under app-private `files/mythreads-smoke/`. Copy each PNG with:

```sh
mkdir -p /tmp/myembro-mythreads-shots
"$ADB" -s emulator-5556 exec-out run-as io.github.linker2k.myembro \
  cat files/mythreads-smoke/01-settings-search-checked.png \
  > /tmp/myembro-mythreads-shots/01-settings-search-checked.png
# Repeat for 02-color-owned-only.png and 03-required-missing.png.
```

The three PNGs were opened for visual inspection. App language was restored to its
original empty override after capture. Production behavior was not changed in this QA pass.
