# Mac Dev Setup

This branch is for local macOS development of the CaBot map stack.

## Repositories expected next to this one

- `MapService`
- `QueryService`
- `cabot-app-server`

Expected layout:

```text
Accessibility_and_Mobility_Lab/
  MapService/
  QueryService/
  cabot-app-server/
```

## One-time setup

1. Install Java 17, Maven, and Docker Desktop.
2. Start Docker Desktop.
3. Run `./download-lib.sh` in this repository if the libraries are not present yet.

## Start everything

From the repository root:

```bash
./start-cabot-stack.sh
```

This script will:

- start MongoDB for `MapService`
- build `QueryService` in an isolated temporary workspace
- prepare Open Liberty for `MapService` and deploy both `map` and `query`
- start `cabot-app-server`

## Stop everything

```bash
./stop-cabot-stack.sh
```

## iPhone app setting

Set `PRIMARY_IP_ADDRESS` in `cabot-ios-app` to the Mac's Wi-Fi IP shown by `start-cabot-stack.sh`.

## Health checks

- MapService: `http://localhost:9090/map/api/config`
- QueryService: `http://localhost:9090/query/directory?user=test&lat=35.6195&lng=139.777&dist=2000&lang=ja-JP`
- App server: `http://localhost:5000/socket.io/?EIO=4&transport=polling`
