# Library original-file export

The selection toolbar's **Export files** action copies installed library files to a system document provider. Multiple selections open `ACTION_OPEN_DOCUMENT_TREE`. For one selection the menu offers either that folder picker or `ACTION_CREATE_DOCUMENT` with the original filename.

This is an archive operation: `OriginalFileExporter` streams bytes without opening an embroidery/project format, flattening a project, generating stitches, or running hoop/machine checks. An out-of-hoop `.myembro` project can therefore be backed up unchanged. USB sewing export keeps its existing checks.

- Names and stored formats are retained. Existing destination names are preserved; collisions use ` (1)`, ` (2)`, etc. before the extension, with case-insensitive comparison for external/FAT providers. A provider may apply its own additional naming rules.
- Installed sample designs and the logo follow the existing policy that makes these ordinary library files selectable/exportable.
- The selection is captured before opening the picker. Canceling the picker leaves it untouched. Completed copies leave selection; failed sources stay selected.
- Each failure shows its filename, failure stage and provider/IO reason. Other files continue. Partial destination files are deleted when possible; failed cleanup is reported. Source files are never modified.
- Copying runs on the IO dispatcher with cancellation checkpoints. Completion reports the number copied, including partial batch success.

## Automated coverage

`OriginalFileExporterTest` uses a repository PES fixture and synthetic PEC/DST/project bytes, including a synthetic project whose bounds exceed its hoop. It asserts byte identity, unchanged sources, multi-file copying, name collisions, missing sources, denied folders, creation/write/close failures, partial cleanup and cancellation.

The existing Compose color-sheet Activity test is scoped to `src/testDebug`, because its test Activity manifest is supplied by `debugImplementation`. It still runs in debug; release unit tests exercise the shared exporter tests without depending on that manifest.

Validation command:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk ./gradlew :app:test :engine:test :app:lint :app:assembleDebug --console=plain
```

No emulator or external/personal image is required for this change.

Verified on this branch: `BUILD SUCCESSFUL in 2m 21s`; debug 473, release 471, engine 1,021 tests, all with 0 failures/errors/skips. Lint reports 0 errors (174 warnings and 1 informational issue); `app-debug.apk` was produced. `git diff --check` is clean.

An earlier full invocation hit the existing `BlockApplyTest.cancellingInterruptsAMeanderGeneration` 3-second timeout. The identical full command passed on retry without changing that test or its threshold. Logs are kept locally at `/tmp/myembro-library-export-timeout.log` and `/tmp/myembro-library-export-validation.log`; machine-counted results are at `/tmp/myembro-library-export-results.txt`.
