# Fix editor text-transform latency

Based on main `764c3d5`. Used synthetic fill contours and app-distributed OFL Noto Sans KR ‘가’, without private inputs.

## Identified causes

Each data-class copy in `TextObject.withTransform()` recreated `pattern by lazy`.
Selection/snapping `placedBounds()` requested new text generation and placement/S1; the canvas's whole-object key also
invalidated RenderData on each pointer event. Layer thumbnails rendered the same copy on main.
Existing default placement for rotation/fractional translation also stripped generated locks and replanned them.

## Changes

- Share lazy stitches belonging to text content/font/height/style across `withTransform/withId/withVisible`.
  Ordinary copies actually changing content/style get a new cache. Serialized fields/equals/public method signatures unchanged.
- Completed-stitch cache per size retains only the latest entry. When size changes, call existing placement/S1 once.
  Translation/rotation map completed coordinates and locks/anchors together without regenerating locks.
- Canvas draws existing line arrays with `현재 행렬 × 캐시 렌더 행렬의 역행렬` (current matrix × inverse cached-render matrix).
  No render generation during gestures. Selection/handles/snapping use conservative transformed bounds of the
  local rectangle's 4 corners. Pointer preview is not a precise sewout-check decision.
- After pointer-up for size-changing gestures, wait **300ms** (user-specified), then generate
  completed stitches/render arrays once on Default dispatcher. Cancel pending work if a new gesture starts.
  Provide progress indication; translation/rotation alone do not change render keys.
- Layer-thumbnail cache keys also exclude position/rotation. Precise hoop checks run in the background
  after pointer/render updates finish. Gestures still commit to History only once.
- Retain negative/centered coordinates in the edit model. P1 top-left normalization occurs in the existing PES save path.
  No new stitch-generation/lock algorithms or dependencies.

## Per-frame computation cost

| Fixed input/CPU measurement | Before-change path mean/max | Cached-matrix preview mean/max |
|---|---:|---:|
| JVM synthetic fill contours, 6 frames | 56.00 / 62.60ms | 0.0129 / 0.0475ms |
| API 35 x86_64 Android, Noto Sans KR ‘가’ 490 stitches, 4 frames | 433.37 / 484.79ms | 0.443 / 1.523ms |

Reproduced the previous JVM path by passing new TextObject copies to the previous default placement function.
Android compared text generation/RenderData generation/drawLines on new copies with shared-cache bounds calculations/
Canvas matrix/drawLines. This does not measure whole UI frames/Choreographer/GPU FPS or performance on
physical devices. Single-run samples; timing thresholds are not CI assertions.

Reproduction:
```
./gradlew :engine:test --tests '*TextTransformCacheTest'
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.editor.EditorTransformTimingInstrumentation
adb shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.editor.EditorTransformTimingInstrumentation
```

Original output:
```
TEXT_TRANSFORM_FRAME_JVM before_mean_ms=56.00373316666667 before_max_ms=62.599822 after_mean_ms=0.012889333333333336 after_max_ms=0.047454 frames=6
TEXT_TRANSFORM_ANDROID text=public_Noto_Sans_KR_glyph stitches=490 before_mean_ms=433.36711575000004 before_max_ms=484.788116 after_mean_ms=0.44265199999999993 after_max_ms=1.522849 frames=4
INSTRUMENTATION_CODE: -1
```

## Generated-output regression tests

- Translation/arbitrary rotation retain original local-stitch object identity and completed stitch commands/counts.
  Compare all output coordinates exactly against the simple rigid transform of existing `mapCoordinates`.
- Size changes (1.2×/0.9×) exactly match existing placement/S1 functions for all stitch coordinates/commands/metadata.
  Repeating the same size returns the same completed-pattern object. Changing letter height separates the cache.
- During 60-frame translation/rotation/size previews, local stitches remain the same object and History does not grow.
  Commit only one step at pointer-up; undo restores the original state.

Not replanning locks after rigid translation/rotation is an intentional change requested here.
No claim of identical results to previous 'relock after rotation'. Keep original generators/size-change
calculations; test rigid transforms against the rule preserving existing completed stitches.

## Actual editor smoke and final checks

Added the app's public Gowun Batang ‘봄날’ sample from lettering to the editor and verified through ADB touches.
`EditorTransform` debug logs record only new RenderData generation; disabled in release builds.

```
RIGID_MOVE_RENDER_REBUILDS_0
RESIZE_RENDER_REBUILDS_1 scale=1.1279402126526195,1.1279402126526195
EDITOR_MOVE_RESIZE_UNDO_SMOKE_PASS
```

Asserted through UI XML/logs: undo enabled after drag, 0 new render logs during/after translation, 1 log after
handle resizing, width 37.20→41.96mm / height 19.40→21.88mm, and undo operation.
Log `/tmp/myembro-editor-transform-smoke.log`; screenshots use only public font examples:
- `/tmp/myembro-photo-editor-transform-before.png`
- `/tmp/myembro-photo-editor-transform-moved.png`
- `/tmp/myembro-photo-editor-transform-resized.png`
- `/tmp/myembro-photo-editor-transform-undo.png`

```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.editor.EditorTransformTimingInstrumentation
BUILD SUCCESSFUL in 25s
113 actionable tasks: 19 executed, 94 up-to-date
```

Engine 650, app debug/release 225 tests each / failures/errors/skips 0.
Full log `/tmp/myembro-editor-transform-check.log`, actual Android metrics log
`/tmp/myembro-editor-transform-android.log`. Local code/emulator validation, not actual sewout/CI approval.
