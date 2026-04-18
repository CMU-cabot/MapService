# Mac Dev Setup

This branch is for local macOS development of the CaBot server stack under a `cabot-servers/` workspace.

## Expected layout

```text
cabot-servers/
  MapService/
    MapService/
    SampleMap/
    _external/
      QueryService/
  cabot-app-server/
```

`QueryService` is treated as an external dependency of `MapService` and is cloned into `MapService/_external/QueryService`.

## Recommended flow

### 1. Initial MapService setup

From `cabot-servers/MapService`:

```bash
./setup-for-mac.sh
```

This script will:

- ensure the local macOS prerequisites needed by this workflow
- run `download-lib.sh` for MapService assets when required
- clone or update `QueryService` into `_external/QueryService`

### 2. Launch MapService + QueryService

From `cabot-servers/MapService`:

```bash
./launch-for-mac.sh
```

This script will:

- start MongoDB for MapService
- build `QueryService` in an isolated temporary workspace
- prepare Open Liberty for `MapService`
- write the local `server.xml` and `server.env` settings needed for macOS testing
- deploy both `map` and `query` under port `9090`

### 3. Launch cabot-app-server separately

`cabot-app-server` is expected to stay independently managed. Set it up and build it by following the sibling repository's README:

```bash
cd ../cabot-app-server
```

If you only need the app server on the local Mac, its normal `./launch.sh` flow is fine.

If you need an iPhone on the same Wi-Fi network to connect to the app server, publish port `5000` by launching it with the helper override from this repository:

```bash
cd ../cabot-app-server
docker compose -f docker-compose.yaml \
  -f ../MapService/support/cabot-app-server-mac-ports.override.yaml \
  --profile mac-prod up
```

To stop that app-server launch:

```bash
docker compose -f docker-compose.yaml \
  -f ../MapService/support/cabot-app-server-mac-ports.override.yaml \
  --profile mac-prod down
```

If you need the `mac-dev` profile instead, use the same override file with `mac-dev` after following the normal setup/build flow in `cabot-app-server`.

### 4. Stop MapService + QueryService

From `cabot-servers/MapService`:

```bash
./stop-for-mac.sh
```

## iPhone app setting

Set `PRIMARY_IP_ADDRESS` in `cabot-ios-app` to the Mac's Wi-Fi IP shown by `launch-for-mac.sh`.

## Health checks

- MapService: `http://localhost:9090/map/api/config`
- QueryService: `http://localhost:9090/query/directory?user=test&lat=35.6195&lng=139.777&dist=2000&lang=ja-JP`
- App server: `http://localhost:5000/socket.io/?EIO=4&transport=polling` when started with the port-publish override above

## Legacy scripts

These scripts are still kept for compatibility, but the Mac workflow above is the recommended path:

- `./start-cabot-stack.sh`
- `./stop-cabot-stack.sh`
