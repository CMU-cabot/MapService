<!--
The MIT License (MIT)

Copyright (c) 2014, 2017 IBM Corporation
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
-->


# MapService

MapService is a server-side component that provides Map related services for [NavCogIOSv3](https://github.com/hulop/NavCogIOSv3).
Please import 2 projects (MapService and SampleMap) by using Eclipse IDE for Java EE Developers Mars2 or later.

Please visit *MapService* folder for more details about MapService application.
*SampleMap* folder contains sample map and GeoJSON data.

## Local macOS workflow

This branch includes a simplified local workflow for macOS development under a `cabot-servers/` workspace.

- Initial setup for MapService + QueryService:
  - `./setup-for-mac.sh`
- Launch MapService + QueryService:
  - `./launch-for-mac.sh`
- Stop MapService + QueryService:
  - `./stop-for-mac.sh`

`./launch-for-mac.sh` automatically prepares the local Open Liberty runtime, including the `server.xml` and `server.env` values needed for macOS testing.
`cabot-app-server` should be set up and launched separately from `../cabot-app-server`. See [MAC_DEV.md](MAC_DEV.md) for the iPhone-facing port publish command used in this branch's local integration flow.

See [MAC_DEV.md](MAC_DEV.md) for the recommended setup and launch flow.

### Optional full-stack helper scripts

If you use the recommended sibling layout below and want one-command start/stop for `MapService + QueryService + cabot-app-server`, create `launch-all.sh` and `stop-all.sh` manually in the parent `cabot-servers/` directory by copying the examples here.

```text
cabot-servers/
  MapService/
  cabot-app-server/
  launch-all.sh
  stop-all.sh
```

`launch-all.sh`

```bash
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
```

`stop-all.sh`

```bash
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
```

Legacy scripts are still present for compatibility:

- `./start-cabot-stack.sh`
- `./stop-cabot-stack.sh`

-----

## About
[About HULOP](https://github.com/hulop/00Readme)

## License
[MIT](http://opensource.org/licenses/MIT)
