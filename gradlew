#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/scripts/env.sh"
exec "$RYADOM_ROOT/.tools/gradle-8.13/bin/gradle" -p "$RYADOM_ROOT" --init-script "$RYADOM_ROOT/scripts/network.init.gradle" "$@"
