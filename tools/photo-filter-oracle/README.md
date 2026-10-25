# Original photo-filter goldens

Reference generator for the design in `docs/photo-filters.md` at branch
`docs/photo-filters`, commit `582f447`. It executes scikit-image 0.25.2 Otsu and
Pillow 11.3.0 autocontrast, with their actual defaults, independently of Kotlin.
See the generated [fixture README](../../engine/src/test/resources/photo-filters/README.md).

## Reproduce in a scratch venv

The checked lock contains Python 3.12 Linux x86_64 wheel hashes. Nothing is
installed in the repository or globally. Installation is a separate explicit
step; the generator itself has no network or install behavior.

```sh
python3 -m venv /tmp/myembro-filter-oracle/venv
/tmp/myembro-filter-oracle/venv/bin/python -m pip install --only-binary=:all: --require-hashes --index-url https://pypi.org/simple -r tools/photo-filter-oracle/requirements.lock
/tmp/myembro-filter-oracle/venv/bin/python tools/photo-filter-oracle/generate.py --output /tmp/myembro-filter-oracle/fresh-goldens
```

The output directory must not already exist. Compare generated files against
`engine/src/test/resources/photo-filters/manifest.json`. The source artwork and
all package versions are pinned; `provenance.json` records their hashes and commits.
`wheels.json` records the actual official PyPI download URLs and SHA-256 values.
The generator verifies installed source-file hashes before running. It calls the
original APIs twice (with/without observation) and asserts identical results.
Otsu intermediate arrays are captured from original return-frame locals; Pillow's
actual `_lut` argument is observed without replacing its computation.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew --offline :engine:test --tests '*PhotoFilterGoldenIntegrityTest' --console=plain
```

This test checks fixture integrity/encoding and captured LUT/mask consistency.
It does **not** claim that a Kotlin filter port matches. That port's tests must
call the Kotlin implementation on the frozen pixels and require exact thresholds,
all mask pixels, and all autocontrast output bytes. No upstream source or binary,
Python dependency, private photo, or personal embroidery design is committed.
