#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

timestamp() {
  date "+%Y-%m-%d %H:%M:%S"
}

echo "[$(timestamp)] Deprecated: start-cabot-stack.sh now launches only MapService + QueryService."
echo "[$(timestamp)] Start cabot-app-server separately. See MAC_DEV.md for the recommended command."

exec "$ROOT_DIR/launch-for-mac.sh" "$@"
