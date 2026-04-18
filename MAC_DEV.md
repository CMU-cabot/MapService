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
- install thin `launch-all.sh` and `stop-all.sh` wrappers in `cabot-servers/`

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

Manual edits under `MapService/target/liberty/...` are not required in this workflow. In particular, the following `server.env` values are written automatically during launch:

- `ENABLE_MAP_ACCESS=admin,auditor,editor`
- `HULOP_INITIAL_LOCATION={ "lat": 35.61950, "lng": 139.77700, "floor": 1 }`
- `HULOP_DO_NOT_USE_SAVED_CENTER=true`

### 3. Stop MapService + QueryService

From `cabot-servers/MapService`:

```bash
./stop-for-mac.sh
```

## Full stack launch

If `cabot-app-server` is also present in the same `cabot-servers/` directory, use:

```bash
cd ..
./launch-all.sh
```

To stop the full stack:

```bash
./stop-all.sh
```

## iPhone app setting

Set `PRIMARY_IP_ADDRESS` in `cabot-ios-app` to the Mac's Wi-Fi IP shown by `launch-for-mac.sh` or `launch-all.sh`.

## Health checks

- MapService: `http://localhost:9090/map/api/config`
- QueryService: `http://localhost:9090/query/directory?user=test&lat=35.6195&lng=139.777&dist=2000&lang=ja-JP`
- App server: `http://localhost:5000/socket.io/?EIO=4&transport=polling`

## Legacy scripts

These scripts are still kept for compatibility, but the Mac workflow above is the recommended path:

- `./start-cabot-stack.sh`
- `./stop-cabot-stack.sh`
