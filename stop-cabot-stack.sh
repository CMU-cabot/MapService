#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Deprecated: stop-cabot-stack.sh now stops only MapService + QueryService."
echo "Stop cabot-app-server separately if you started it."

exec "$ROOT_DIR/stop-for-mac.sh" "$@"
