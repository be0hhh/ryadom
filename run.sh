#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if ! command -v python3 >/dev/null; then
    echo 'Нужен Python 3: sudo apt-get install python3' >&2
    exit 1
fi
exec python3 scripts/launcher.py "$@"
