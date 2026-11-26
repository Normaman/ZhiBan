#!/usr/bin/env bash
# Scope cleanup to duplicate PDD results, preserving download cache mounts.
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$project_root/scripts/docker-cache.py" "$@"
