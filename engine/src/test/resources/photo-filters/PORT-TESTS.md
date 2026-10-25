# Kotlin filter-port comparisons

These 10 representative inputs are an unmodified subset of embro-codex's independent
original-output corpus generated at `/tmp/myembro-filter-oracle/goldens` on 2026-10-25.
The source runner uses actual scikit-image 0.25.2 and Pillow 11.3.0 with default arguments.
`provenance.json` records the upstream commits, package/wheel versions, generator hash,
source-file hashes, synthetic inputs and repository artwork provenance. No personal images.
The corpus generator and synthetic fixtures are GPL-3.0-or-later; the included repository
launcher artwork is GPL-3.0-or-later. Original algorithm licenses are BSD-2-Clause/MIT-CMU.

`port-manifest.json` selects the bounded CI subset and retains the independent file hashes.
It deliberately has a different name from the full oracle's manifest so the independently
maintained full corpus can be integrated without replacing this selection. Each selected PNG
and expected.json is byte-identical to the independent output; no Kotlin-generated expectation.

`PhotoFilterOracleTest` checks every source-file hash, input pixel hash, exact threshold,
autocontrast output bytes, and dark-mask bytes/hash. Grayscale reads use ImageIO raster
samples, avoiding getRGB's color-space conversion. Normal tests need no Python or `/tmp`.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*PhotoFilter*Test' --console=plain
```

An optional local check can compare the entire separately generated corpus without adding
more algorithm cases to the normal CI subset:

```sh
MYEMBRO_PHOTO_FILTER_ORACLE=/tmp/myembro-filter-oracle/goldens JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*PhotoFilterOracleTest' --rerun-tasks --console=plain
```

Successful comparisons print `ORACLE_EXACT <id> threshold/autocontrast/mask` and a total
in JUnit XML's system-out. These are raster-algorithm checks, not PES or sewing approval.
