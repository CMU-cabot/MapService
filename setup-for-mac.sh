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

if [[ ! -f "$QUERYSERVICE_APP_DIR/pom.xml" ]]; then
  echo "QueryService application directory not found: $QUERYSERVICE_APP_DIR" >&2
  exit 1
fi

log "Setup complete"
echo
echo "Next steps:"
echo "  MapService + QueryService: ./launch-for-mac.sh"
echo "  cabot-app-server: set it up separately in ../cabot-app-server"
echo "  Optional parent launch/stop helpers: copy the examples from README.md"
echo "  iPhone app-server access: see MAC_DEV.md"
