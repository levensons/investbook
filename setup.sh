#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v python3 >/dev/null || { echo 'Нужен Python 3.12 или новее.' >&2; exit 1; }
exec python3 scripts/linux_launcher.py setup "$@"
