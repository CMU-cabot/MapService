#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAPSERVICE_APP_DIR="$ROOT_DIR/MapService"
EXTERNAL_DIR="$ROOT_DIR/_external"
LEGACY_QUERYSERVICE_DIR="$ROOT_DIR/QueryService"
QUERYSERVICE_REPO_DIR="$EXTERNAL_DIR/QueryService"
QUERYSERVICE_APP_DIR="$QUERYSERVICE_REPO_DIR/QueryService"
QUERYSERVICE_URL="https://github.com/CMU-cabot/QueryService"
QUERYSERVICE_BRANCH="hokoukukan-2018"
WORKSPACE_DIR="$(cd "$ROOT_DIR/.." && pwd)"

timestamp() {
  date "+%Y-%m-%d %H:%M:%S"
}

log() {
  echo "[$(timestamp)] $*"
}

need_brew() {
  if ! command -v brew >/dev/null 2>&1; then
    echo "Homebrew is required for automatic setup on macOS." >&2
    echo "Please install Homebrew first, then rerun ./setup-for-mac.sh" >&2
    exit 1
  fi
}

ensure_formula() {
  local command_name="$1"
  local formula_name="$2"

  if command -v "$command_name" >/dev/null 2>&1; then
    return 0
  fi

  need_brew
  log "Installing $formula_name with Homebrew"
  brew install "$formula_name"
}

ensure_cask() {
  local check_cmd="$1"
  local cask_name="$2"

  if eval "$check_cmd" >/dev/null 2>&1; then
    return 0
  fi

  need_brew
  log "Installing $cask_name with Homebrew"
  brew install --cask "$cask_name"
}

use_java17() {
  local java_home17

  java_home17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -z "$java_home17" ]]; then
    echo "Java 17 was not found after setup." >&2
    exit 1
  fi

  export JAVA_HOME="$java_home17"
  export PATH="$JAVA_HOME/bin:$PATH"
  log "Using JAVA_HOME=$JAVA_HOME"
}

wait_for_docker() {
  local retries="${1:-90}"

  if docker info >/dev/null 2>&1; then
    return 0
  fi

  log "Starting Docker Desktop"
  open -a Docker >/dev/null 2>&1 || true

  for _ in $(seq 1 "$retries"); do
    if docker info >/dev/null 2>&1; then
      log "Docker Desktop is ready"
      return 0
    fi
    sleep 2
  done

  echo "Docker Desktop did not become ready. Please launch it and rerun setup." >&2
  exit 1
}

mapservice_assets_ready() {
  [[ -f "$MAPSERVICE_APP_DIR/WebContent/openlayers/v4.6.5/ol.js" ]] &&
  [[ -f "$MAPSERVICE_APP_DIR/WebContent/jquery/jquery-1.11.3.min.js" ]] &&
  [[ -f "$MAPSERVICE_APP_DIR/WebContent/js/lib/qrcode.js" ]]
}

prepare_queryservice_repo() {
  mkdir -p "$EXTERNAL_DIR"

  if [[ -d "$LEGACY_QUERYSERVICE_DIR/.git" ]] && [[ ! -e "$QUERYSERVICE_REPO_DIR" ]]; then
    log "Moving legacy QueryService checkout into _external/QueryService"
    mv "$LEGACY_QUERYSERVICE_DIR" "$QUERYSERVICE_REPO_DIR"
  fi

  if [[ ! -d "$QUERYSERVICE_REPO_DIR/.git" ]]; then
    log "Cloning QueryService ($QUERYSERVICE_BRANCH)"
    git clone --branch "$QUERYSERVICE_BRANCH" --single-branch "$QUERYSERVICE_URL" "$QUERYSERVICE_REPO_DIR"
    return 0
  fi

  if [[ -n "$(git -C "$QUERYSERVICE_REPO_DIR" status --porcelain)" ]]; then
    log "QueryService has local changes; skipping automatic branch update"
    return 0
  fi

  log "Updating QueryService checkout"
  git -C "$QUERYSERVICE_REPO_DIR" fetch origin "$QUERYSERVICE_BRANCH"
  if git -C "$QUERYSERVICE_REPO_DIR" show-ref --verify --quiet "refs/heads/$QUERYSERVICE_BRANCH"; then
    git -C "$QUERYSERVICE_REPO_DIR" checkout "$QUERYSERVICE_BRANCH"
  else
    git -C "$QUERYSERVICE_REPO_DIR" checkout -b "$QUERYSERVICE_BRANCH" "origin/$QUERYSERVICE_BRANCH"
  fi
  git -C "$QUERYSERVICE_REPO_DIR" pull --ff-only origin "$QUERYSERVICE_BRANCH"
}

install_workspace_helpers() {
  local launch_script="$WORKSPACE_DIR/launch-all.sh"
  local stop_script="$WORKSPACE_DIR/stop-all.sh"

  cat > "$launch_script" <<'EOF'
#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAPSERVICE_DIR="$ROOT_DIR/MapService"
APP_SERVER_DIR="$ROOT_DIR/cabot-app-server"
APP_SERVER_OVERRIDE="$MAPSERVICE_DIR/support/cabot-app-server-mac-ports.override.yaml"
APP_SERVER_PROFILE="mac-prod"

timestamp() {
  date "+%Y-%m-%d %H:%M:%S"
}

log() {
  echo "[$(timestamp)] $*"
}

usage() {
  cat <<USAGE
Usage: ./launch-all.sh [-d]

  -d    Launch cabot-app-server with the mac-dev profile
USAGE
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

wait_for_http() {
  local url="$1"
  local name="$2"
  local max_retry="${3:-30}"

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

while getopts "hd" arg; do
  case "$arg" in
    h)
      usage
      exit 0
      ;;
    d)
      APP_SERVER_PROFILE="mac-dev"
      ;;
  esac
done

require_command curl
require_command docker

if [[ ! -x "$MAPSERVICE_DIR/launch-for-mac.sh" ]]; then
  echo "MapService launch script not found: $MAPSERVICE_DIR/launch-for-mac.sh" >&2
  exit 1
fi

if [[ ! -d "$APP_SERVER_DIR" ]]; then
  echo "cabot-app-server repo not found: $APP_SERVER_DIR" >&2
  exit 1
fi

if [[ ! -f "$APP_SERVER_OVERRIDE" ]]; then
  echo "App-server override not found: $APP_SERVER_OVERRIDE" >&2
  exit 1
fi

log "Launching MapService + QueryService"
(cd "$MAPSERVICE_DIR" && ./launch-for-mac.sh)

if [[ "$APP_SERVER_PROFILE" == "mac-dev" ]]; then
  log "Building cabot-app-server for mac-dev"
  (cd "$APP_SERVER_DIR" && docker compose -f docker-compose.yaml -f "$APP_SERVER_OVERRIDE" --profile "$APP_SERVER_PROFILE" run --rm app-server-mac-dev /launch.sh build)
fi

log "Starting cabot-app-server ($APP_SERVER_PROFILE)"
(cd "$APP_SERVER_DIR" && docker compose -f docker-compose.yaml -f "$APP_SERVER_OVERRIDE" --profile "$APP_SERVER_PROFILE" up -d)

wait_for_http "http://localhost:5000/socket.io/?EIO=4&transport=polling" "cabot-app-server"

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
EOF

  cat > "$stop_script" <<'EOF'
#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAPSERVICE_DIR="$ROOT_DIR/MapService"
APP_SERVER_DIR="$ROOT_DIR/cabot-app-server"
APP_SERVER_OVERRIDE="$MAPSERVICE_DIR/support/cabot-app-server-mac-ports.override.yaml"

if [[ -x "$MAPSERVICE_DIR/stop-for-mac.sh" ]]; then
  (cd "$MAPSERVICE_DIR" && ./stop-for-mac.sh)
fi

if [[ -d "$APP_SERVER_DIR" ]] && [[ -f "$APP_SERVER_OVERRIDE" ]]; then
  (cd "$APP_SERVER_DIR" && docker compose -f docker-compose.yaml -f "$APP_SERVER_OVERRIDE" down >/dev/null 2>&1 || true)
fi

echo "Stopped full local stack"
EOF

  chmod +x "$launch_script" "$stop_script"
  log "Installed workspace launch/stop helpers in $WORKSPACE_DIR"
}

ensure_formula git git
ensure_formula curl curl
ensure_formula unzip unzip
ensure_formula mvn maven
ensure_cask "/usr/libexec/java_home -v 17" "temurin@17"
ensure_cask "command -v docker" "docker"
use_java17
wait_for_docker

if mapservice_assets_ready; then
  log "MapService front-end assets already exist; skipping download-lib.sh"
else
  log "Downloading MapService front-end assets"
  (cd "$ROOT_DIR" && bash ./download-lib.sh)
fi

prepare_queryservice_repo
install_workspace_helpers

if [[ ! -f "$QUERYSERVICE_APP_DIR/pom.xml" ]]; then
  echo "QueryService application directory not found: $QUERYSERVICE_APP_DIR" >&2
  exit 1
fi

log "Setup complete"
echo
echo "Next steps:"
echo "  MapService + QueryService: ./launch-for-mac.sh"
echo "  Full local stack: ../launch-all.sh"
echo "  cabot-app-server: set it up separately in ../cabot-app-server"
echo "  iPhone app-server access: see MAC_DEV.md"
