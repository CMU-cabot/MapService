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

## Replan a link-cover route

Use `POST` when sending `coverage_state`, especially when the ordered history is
long. The server remains stateless with respect to coverage: send the complete
current state on every replan request.

Each route Link has `properties.coverage_link_id`. Use this value in
`covered_link_ids`, `excluded_link_ids`, and `traversal_history`. For a temporary
link created at the current position, it is the ID of the original physical link,
not `_TEMP_LINK1_` or `_TEMP_LINK2_`.

```bash
curl -X POST 'http://localhost:9090/map/routesearch' \
  -d 'action=linkcover' \
  -d 'user=test-user' \
  -d 'lang=ja' \
  -d 'from=latlng:35.61960:139.77720:1' \
  -d 'to=latlng:35.61950:139.77700:1' \
  -d 'allow_subgraph=true' \
  -d 'solver=dopt' \
  -d 'attempts=100' \
  --data-urlencode 'coverage_state={"covered_link_ids":["EDITOR_link_1"],"excluded_link_ids":["EDITOR_link_2"],"traversal_history":[]}' \
  --data-urlencode 'preferences={"min_width":"9","slope":"9","road_condition":"9","stairs":"9","deff_LV":"9","esc":"9","mvw":"9","elv":"9"}'
```

- A covered link is no longer required, but can be traversed to connect required
  components or correct vertex parity.
- An excluded link is removed from all future route calculations. Excluded takes
  precedence when the same ID is also covered.
- Every other traversable link on the selected floor is required.
- `to=latlng:...` is rounded to the nearest node on the requested floor. Omitting
  `to` keeps the backward-compatible closed route ending at `from`.
- `traversal_history` is validated as an ordered, continuous sequence of complete
  physical-link traversals. This version does not include it in D-opt scoring.
- With `allow_subgraph=true`, only links reachable from `from` remain in scope;
  `to` must still be reachable. With `false`, every required component must be
  connectable.

To inspect candidate scores, use `all=true` with at most 200 attempts:

```bash
curl -X POST 'http://localhost:9090/map/routesearch' \
  -d 'action=linkcover' -d 'user=test-user' -d 'lang=ja' \
  -d 'from=latlng:35.61960:139.77720:1' \
  -d 'to=latlng:35.61950:139.77700:1' \
  -d 'solver=dopt' -d 'all=true' -d 'attempts=50' \
  --data-urlencode 'coverage_state={"covered_link_ids":[],"excluded_link_ids":[],"traversal_history":[]}' \
  --data-urlencode 'preferences={"min_width":"9","slope":"9","road_condition":"9","stairs":"9","deff_LV":"9","esc":"9","mvw":"9","elv":"9"}' \
  | jq '.routes | map(.d_optimality)'
```

Useful response checks are:

```bash
# Physical link IDs in traversal order
jq -r '.[] | select(.properties.coverage_link_id) | .properties.coverage_link_id' route.json

# Start and end features
jq '.[0], .[-1]' route.json

# Must print 0 for an excluded physical link
jq '[.[] | select(.properties.coverage_link_id == "EDITOR_link_2")] | length' route.json
```

Malformed state, unknown IDs, an excluded current-position link, an unreachable
end point, or unconnectable required components return HTTP 400. If every link is
complete and `from` equals `to`, the successful JSON result is
`{"error":"zero-distance"}`.

## LinkCover GUI coverage workflow

Open `http://localhost:9090/map/linkcover.jsp?id=<user>` to edit and inspect the
coverage state on the map. Start with an empty `coverage_state` and run the first
route search to populate the selectable physical-link layer. The layer remains
visible when the navigation route is cleared and uses these styles:

- green: Covered
- red: Excluded
- red with a narrower green line: both Covered and Excluded

Select **Covered** or **Excluded** in the map control, then select a physical
link. Covered and Excluded are independent sets; adding Excluded never removes
past Covered or traversal history. Excluded is applied to routing the next time
route search runs.

During navigation, reaching a turn or another instruction point marks every
physical link in the preceding instruction segment Covered. If navigation moves
past multiple instruction steps, every intervening planned link is included.
Each complete traversal is appended to `traversal_history` with the directed
`sourceNode` and `targetNode` values from the route. Repeated traversal of the
same link produces repeated history entries while `covered_link_ids` remains a
set. One temporary split fragment beginning at the projected current position
is not treated as traversal of its entire physical link. If both temporary
fragments are later traversed consecutively from one original endpoint to the
other, the GUI combines them into one complete physical-link traversal.

If a newly completed link would make the ordered history discontinuous, the GUI
still marks it Covered but displays a warning and does not append the invalid
history entry. Covered changes update only the current page state; they do not
automatically run route search. The JSON field is kept synchronized and is sent
in full on the next replan request.

Manual Covered selection advances the recorded traversal through the next
occurrence of that link in the initial plan. Removing a Covered link that is in
history rewinds history to before its first occurrence so the remaining history
stays continuous. **Reset coverage state** clears Covered, Excluded, and history;
the next successful route replaces the selectable-link catalog. State is not
persisted across a page reload.

## Algorithmic basis

The implementation combines established postman algorithms with the mapping
route evaluation proposed by Gao et al. The citations describe the origin of each
component; the implementation-specific differences below are intentional.

| Component | Reference | Implementation and difference |
| --- | --- | --- |
| CPP parity correction | Jack Edmonds and Ellis L. Johnson, **“Matching, Euler Tours and the Chinese Postman”**, *Mathematical Programming*, 1973 | Computes a minimum-weight perfect matching over shortest-path distances. For an open route it corrects `O symmetric-difference {from,to}` directly. |
| Required-component connection | Greg N. Frederickson, **“Approximation Algorithms for Some Postman Problems”**, *Journal of the ACM*, 1979 | Builds one shortest-path metric closure and expands one Kruskal MST. This is a Frederickson-type construction; MapService does not claim Frederickson's approximation guarantee. |
| Euler traversal | Carl Hierholzer, **“Ueber die Möglichkeit, einen Linienzug ohne Wiederholung und ohne Unterbrechung zu umfahren”**, *Mathematische Annalen*, 1873 | Uses Hierholzer traversal from `from` to `to`; `solver=cpp` uses stable edge order. |
| Candidate generation and D-opt | Wei Gao et al., **“Active Loop Closure for OSM-guided Robotic Mapping in Large-Scale Urban Environments”**, arXiv:2407.17078, 2024 | Implements the paper's random exhaustive candidate search by randomizing Hierholzer's next-edge order. D-opt evaluates only the newly planned route in this version, not historical traversal. |

Gao et al. describe open RPP conversion using a virtual edge. MapService instead
uses the equivalent direct parity target `O symmetric-difference {from,to}` and
therefore does not materialize a virtual route Link.
