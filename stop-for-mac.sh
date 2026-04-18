#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAPSERVICE_DIR="$ROOT_DIR/MapService"
PID_DIR="$ROOT_DIR/.run"

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

stop_pid_file "$PID_DIR/mapservice.pid" "MapService/QueryService"

(cd "$MAPSERVICE_DIR" && docker compose -f docker-compose-mongo.yaml down >/dev/null 2>&1 || true)

echo "Stopped MapService + QueryService"
