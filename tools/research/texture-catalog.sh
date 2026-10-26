#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Run OUR JVM port only; no original Ink/Stitch execution/dependency installation.
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/home/gosh2/.jdks/jdk-17.0.20.1+1}
export TEXTURE_CATALOG_OUTPUT=${TEXTURE_CATALOG_OUTPUT:-/tmp/myembro-texture}
mkdir -p "$TEXTURE_CATALOG_OUTPUT"
cd "$repo_root"
./gradlew -I tools/inkstitch-fill-preview.gradle :engine:test \
  --tests '*TextureCatalogTest' --rerun-tasks --console=plain
python3 tools/research/summarize-textures.py "$TEXTURE_CATALOG_OUTPUT"
