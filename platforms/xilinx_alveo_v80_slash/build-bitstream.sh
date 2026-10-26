#!/usr/bin/env bash
# See LICENSE for license details.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec python3 "$script_dir/cl_firesim/scripts/run-build.py" "$@"
