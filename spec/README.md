## OpenAPI spec for MapService

- launch local Swagger UI

```
docker compose up
```

- access http://localhost
- example `linkcover` request

  Start a route search session first. `linkcover` uses the same session cache as
  `search`, so the `user` and `lang` values need to match the following request.

```bash
curl -X POST 'http://localhost:9090/map/routesearch' \
  -d 'action=start' \
  -d 'user=test-user' \
  -d 'lang=ja' \
  -d 'lat=35.61950' \
  -d 'lng=139.77700' \
  -d 'dist=1000'
```

```bash
curl -X POST 'http://localhost:9090/map/routesearch' \
  -d 'action=linkcover' \
  -d 'user=test-user' \
  -d 'lang=ja' \
  -d 'from=latlng:35.61950:139.77700:1' \
  -d 'allow_subgraph=true' \
  --data-urlencode 'preferences={"min_width":"9","slope":"9","road_condition":"9","stairs":"9","deff_LV":"9","esc":"9","mvw":"9","elv":"9"}'
```

`attempts` defaults to `LINKCOVER_ATTEMPTS` or `1000`, and is capped by
`LINKCOVER_MAX_ATTEMPTS` or `5000`. When `all=true`, it is capped by
`LINKCOVER_MAX_ALL_ATTEMPTS` or `200`.
The D-opt scoring weights can be tuned with
`LINKCOVER_ODOMETRY_WEIGHT_SCALE` or `10.0` and
`LINKCOVER_LOOP_CLOSURE_WEIGHT` or `100.0`.
