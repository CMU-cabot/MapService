#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORKSPACE_DIR="$(cd "$ROOT_DIR/.." && pwd)"
MAPSERVICE_DIR="$ROOT_DIR"
APP_SERVER_BUILD_DIR="$WORKSPACE_DIR/.build/cabot-app-server"
PID_DIR="$WORKSPACE_DIR/.run"

stop_pid_file() {
  local pid_file="$1"
  local name="$2"

  if [[ ! -f "$pid_file" ]]; then
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"
  if kill -0 "$pid" >/dev/null 2>&1; then
    echo "Stopping $name (PID $pid)"
    kill "$pid" || true
  fi
  rm -f "$pid_file"
}

stop_pid_file "$PID_DIR/app-server.pid" "cabot-app-server"

if [[ -d "$APP_SERVER_BUILD_DIR" ]]; then
  (cd "$APP_SERVER_BUILD_DIR" && docker compose -f docker-compose.yaml down >/dev/null 2>&1 || true)
fi

if [[ -x "$MAPSERVICE_DIR/stop-for-mac.sh" ]]; then
  (cd "$MAPSERVICE_DIR" && ./stop-for-mac.sh)
fi

echo "Stopped full local stack"
