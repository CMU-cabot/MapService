#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAPSERVICE_DIR="$ROOT_DIR/MapService"
QUERYSERVICE_DIR="$ROOT_DIR/_external/QueryService/QueryService"
BUILD_DIR="$ROOT_DIR/.build"
QUERY_BUILD_DIR="$BUILD_DIR/QueryService"
RUNTIME_SERVER_DIR="$MAPSERVICE_DIR/target/liberty/wlp/usr/servers/defaultServer"
RUNTIME_APPS_DIR="$RUNTIME_SERVER_DIR/apps"
LOG_DIR="$ROOT_DIR/log"
PID_DIR="$ROOT_DIR/.run"
MAPSERVICE_WAR="MapService-0.0.1-SNAPSHOT.war"
QUERYSERVICE_WAR="QueryService-0.0.1-SNAPSHOT.war"

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

use_java17() {
  local java_home17

  java_home17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -z "$java_home17" ]]; then
    echo "Java 17 is required. Run ./setup-for-mac.sh first." >&2
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

  echo "Docker Desktop did not become ready. Please run ./setup-for-mac.sh after starting Docker." >&2
  exit 1
}

ensure_line() {
  local file="$1"
  local key="$2"
  local value="$3"
  local escaped_value

  touch "$file"
  if grep -q "^${key}=" "$file"; then
    escaped_value="${value//\\/\\\\}"
    escaped_value="${escaped_value//|/\\|}"
    escaped_value="${escaped_value//&/\\&}"
    perl -0pi -e "s|^${key}=.*\$|${key}=${escaped_value}|m" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
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

require_command mvn
require_command docker
require_command curl
require_command perl
require_command rsync

if [[ ! -f "$QUERYSERVICE_DIR/pom.xml" ]]; then
  echo "QueryService is not set up at $QUERYSERVICE_DIR" >&2
  echo "Run ./setup-for-mac.sh first." >&2
  exit 1
fi

use_java17
wait_for_docker

log "Preparing isolated QueryService build workspace"
rm -rf "$QUERY_BUILD_DIR"
mkdir -p "$QUERY_BUILD_DIR"
rsync -a --delete --exclude '.git/' --exclude 'target/' "$QUERYSERVICE_DIR/" "$QUERY_BUILD_DIR/"
perl -0pi -e 's#<artifactId>maven-war-plugin</artifactId>\s*<version>3\.0\.0</version>#<artifactId>maven-war-plugin</artifactId>\n\t\t\t\t<version>3.4.0</version>#s' \
  "$QUERY_BUILD_DIR/pom.xml"
perl -0pi -e 's#Directory cdd = new Directory\(featuresUrl, nodemapUrl, new Locale\(lang\), enableGroupBuilding, enableGroupFloor, enableGroupCategory, enableGroupNearbyFacility\);#Locale locale = Locale.forLanguageTag(lang.replace('\''_'\'', '\''-'\''));\n\t\t\tDirectory cdd = new Directory(featuresUrl, nodemapUrl, locale, enableGroupBuilding, enableGroupFloor, enableGroupCategory, enableGroupNearbyFacility);#' \
  "$QUERY_BUILD_DIR/src/org/hulop/servlet/DirectoryServlet.java"

log "Starting MongoDB for MapService"
(cd "$ROOT_DIR" && docker compose -f docker-compose-mongo.yaml up -d mongodb)

log "Building QueryService WAR"
(cd "$QUERY_BUILD_DIR" && mvn -q -DskipTests package)

log "Preparing Open Liberty runtime for MapService"
(cd "$MAPSERVICE_DIR" && mvn -q -DskipTests package liberty:create)

mkdir -p "$RUNTIME_APPS_DIR"
cp "$QUERY_BUILD_DIR/target/$QUERYSERVICE_WAR" "$RUNTIME_APPS_DIR/$QUERYSERVICE_WAR"

cat > "$RUNTIME_SERVER_DIR/server.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<server description="CaBot local test server">

    <featureManager>
        <feature>jsp-2.3</feature>
    </featureManager>

    <httpEndpoint id="defaultHttpEndpoint"
                  host="*"
                  httpPort="9090"
                  httpsPort="9443" />

    <applicationManager autoExpand="true"/>

    <webApplication location="$MAPSERVICE_WAR" contextRoot="map"/>
    <webApplication location="$QUERYSERVICE_WAR" contextRoot="query"/>

    <ssl id="defaultSSLConfig" trustDefaultCerts="true" />
</server>
EOF

ensure_line "$RUNTIME_SERVER_DIR/server.env" "ENABLE_MAP_ACCESS" "admin,auditor,editor"
ensure_line "$RUNTIME_SERVER_DIR/server.env" "HULOP_INITIAL_LOCATION" "{ \"lat\": 35.61950, \"lng\": 139.77700, \"floor\": 1 }"
ensure_line "$RUNTIME_SERVER_DIR/server.env" "HULOP_DO_NOT_USE_SAVED_CENTER" "true"
ensure_line "$RUNTIME_SERVER_DIR/server.env" "HULOP_MAP_SERVICE" "localhost:9090/map"
ensure_line "$RUNTIME_SERVER_DIR/server.env" "HULOP_MAP_SERVICE_USE_HTTP" "true"
ensure_line "$RUNTIME_SERVER_DIR/server.env" "SEARCH_BY_BUILDING_ENABLED" "false"
ensure_line "$RUNTIME_SERVER_DIR/server.env" "SEARCH_BY_FLOOR_ENABLED" "true"
ensure_line "$RUNTIME_SERVER_DIR/server.env" "SEARCH_BY_CATEGORY_ENABLED" "false"
ensure_line "$RUNTIME_SERVER_DIR/server.env" "SEARCH_BY_NEARBY_FACILITY_ENABLED" "false"

log "Starting MapService + QueryService on Open Liberty"
start_background \
  "$PID_DIR/mapservice.pid" \
  bash -lc "cd '$MAPSERVICE_DIR' && exec mvn liberty:run > '$LOG_DIR/mapservice.log' 2>&1"

wait_for_http "http://localhost:9090/map/api/config" "MapService"
wait_for_http "http://localhost:9090/query/directory?user=test&lat=35.6195&lng=139.777&dist=2000&lang=ja-JP" "QueryService"

MAC_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"

log "MapService + QueryService are up"
echo
echo "Mac Wi-Fi IP: ${MAC_IP:-not detected}"
echo "CaBot iPhone app:"
echo "  PRIMARY_IP_ADDRESS = ${MAC_IP:-<your-mac-ip>}"
echo "  SECONDARY_IP_ADDRESS = "
echo
echo "Health checks:"
echo "  MapService   http://localhost:9090/map/api/config"
echo "  QueryService http://localhost:9090/query/directory?user=test&lat=35.6195&lng=139.777&dist=2000&lang=ja-JP"
echo
echo "Logs:"
echo "  $LOG_DIR/mapservice.log"
