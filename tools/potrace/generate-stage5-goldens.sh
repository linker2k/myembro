#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
exec "$(dirname "$0")/generate-stage4-goldens.sh" "${1:?Usage: generate-stage5-goldens.sh /path/to/potrace-1.16 [output-directory]}" "${2:-/tmp/myembro-port-out/editor/potrace-stage5}" 5
