#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORKSPACE_DIR="$(cd "$ROOT_DIR/.." && pwd)"
MAPSERVICE_DIR="$ROOT_DIR"
APP_SERVER_DIR="$WORKSPACE_DIR/cabot-app-server"
BUILD_DIR="$WORKSPACE_DIR/.build"
APP_SERVER_BUILD_DIR="$BUILD_DIR/cabot-app-server"
LOG_DIR="$WORKSPACE_DIR/log"
PID_DIR="$WORKSPACE_DIR/.run"

mkdir -p "$BUILD_DIR" "$LOG_DIR" "$PID_DIR"

timestamp() {
  date "+%Y-%m-%d %H:%M:%S"
}

log() {
  echo "[$(timestamp)] $*"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

start_background() {
  local pid_file="$1"
  shift

  if [[ -f "$pid_file" ]]; then
    local existing_pid
    existing_pid="$(cat "$pid_file")"
    if kill -0 "$existing_pid" >/dev/null 2>&1; then
      log "Already running with PID $existing_pid: $*"
      return 0
    fi
    rm -f "$pid_file"
  fi

  nohup "$@" >/dev/null 2>&1 &
  local pid=$!
  echo "$pid" > "$pid_file"
  log "Started PID $pid: $*"
}

wait_for_http() {
  local url="$1"
  local name="$2"
  local max_retry="${3:-60}"

  for _ in $(seq 1 "$max_retry"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      log "$name is ready: $url"
      return 0
    fi
    sleep 2
  done

  echo "$name did not become ready: $url" >&2
  return 1
}

require_command curl
require_command perl
require_command rsync

if [[ ! -x "$MAPSERVICE_DIR/launch-for-mac.sh" ]]; then
  echo "MapService launch script not found: $MAPSERVICE_DIR/launch-for-mac.sh" >&2
  exit 1
fi

if [[ ! -d "$APP_SERVER_DIR" ]]; then
  echo "cabot-app-server repo not found: $APP_SERVER_DIR" >&2
  exit 1
fi

log "Launching MapService + QueryService"
(cd "$MAPSERVICE_DIR" && ./launch-for-mac.sh)

log "Preparing isolated cabot-app-server workspace"
rm -rf "$APP_SERVER_BUILD_DIR"
mkdir -p "$APP_SERVER_BUILD_DIR"
rsync -a --delete --exclude '.git/' --exclude 'log/' "$APP_SERVER_DIR/" "$APP_SERVER_BUILD_DIR/"
perl -0pi -e 's#(app-server-mac:\n\s+image: [^\n]+\n)(\s+ports:\n\s+- "5000:5000"\n)?#$1    ports:\n      - "5000:5000"\n#s' \
  "$APP_SERVER_BUILD_DIR/docker-compose.yaml"
perl -0pi -e 's#(app-server-mac-dev:\n\s+image: [^\n]+\n)(\s+ports:\n\s+- "5000:5000"\n)?#$1    ports:\n      - "5000:5000"\n#s' \
  "$APP_SERVER_BUILD_DIR/docker-compose.yaml"

log "Starting cabot-app-server"
start_background \
  "$PID_DIR/app-server.pid" \
  bash -lc "cd '$APP_SERVER_BUILD_DIR' && exec ./launch.sh > '$LOG_DIR/cabot-app-server.log' 2>&1"

wait_for_http "http://localhost:5000/socket.io/?EIO=4&transport=polling" "cabot-app-server" 30

MAC_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"

log "Full local stack is up"
echo
echo "Mac Wi-Fi IP: ${MAC_IP:-not detected}"
echo "CaBot iPhone app:"
echo "  PRIMARY_IP_ADDRESS = ${MAC_IP:-<your-mac-ip>}"
echo "  SECONDARY_IP_ADDRESS = "
echo
echo "Health checks:"
echo "  MapService   http://localhost:9090/map/api/config"
echo "  QueryService http://localhost:9090/query/directory?user=test&lat=35.6195&lng=139.777&dist=2000&lang=ja-JP"
echo "  App server   http://localhost:5000/socket.io/?EIO=4&transport=polling"
echo
echo "Logs:"
echo "  $MAPSERVICE_DIR/log/mapservice.log"
echo "  $LOG_DIR/cabot-app-server.log"
