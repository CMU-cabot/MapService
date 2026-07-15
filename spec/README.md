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
- An excluded link is removed from the RPP graph. Excluded takes precedence when
  the same ID is also covered. The only exception is the temporary prefix used
  to leave an excluded link on which the current position is already located,
  as described below.
- Every other traversable link on the selected floor is required.
- `to=latlng:...` is rounded to the nearest node on the requested floor. Omitting
  `to` keeps the backward-compatible closed route ending at `from`, except that
  an explicit `to` is required when leaving a current-position excluded link.
- `traversal_history` is validated as an ordered, continuous sequence of complete
  physical-link traversals. When the current position is on an excluded link,
  the last history entry identifies the endpoint from which that link was
  entered. This version does not include history in D-opt scoring.
- With `allow_subgraph=true`, only links reachable from `from` remain in scope;
  `to` must still be reachable. With `false`, every required component must be
  connectable.

### Replan from the middle of an excluded link

If `from=latlng:...` projects onto a physical link that is included in
`excluded_link_ids`, MapService can first retreat over the already traversed
part of that link and then start the normal RPP from its entry endpoint. This
keeps the returned route physically continuous without putting the excluded
physical link back into the RPP graph.

This fixed-egress behavior requires all of the following:

- `from` projects into the interior of the excluded link and creates the existing
  `_TEMP_NODE_` and temporary split links.
- `to` is explicitly supplied.
- `traversal_history` is non-empty, and its last `target_node_id` equals exactly
  one of the excluded link's physical endpoints. That endpoint is treated as the
  side from which the user entered the link.

The response starts with `_TEMP_NODE_` followed by exactly one
`_TEMP_LINK1_` or `_TEMP_LINK2_`, directed back to the inferred entry endpoint.
That temporary link retains the excluded physical link's
`properties.coverage_link_id`; it is the only allowed appearance of that ID.
The excluded physical link and both temporary fragments remain outside the RPP
graph, and the selected fragment is prepended only after route solving. The
fixed prefix is common to every candidate, so it is not included in distance,
route-signature, or D-optimality candidate comparison. If the RPP portion has
zero distance, the successful route consists only of the temporary start node,
the egress link, and the entry node. With `all=true`, that case returns one
candidate whose empty RPP suffix has `d_optimality=0.0`.

Replace the IDs below with links and nodes from the current site. In particular,
`EDITOR_node_entry` must be an endpoint of `EDITOR_link_current` and the target
of the last complete traversal:

```bash
curl -sS -X POST 'http://localhost:9090/map/routesearch' \
  -d 'action=linkcover' \
  -d 'user=test-user' \
  -d 'lang=ja' \
  -d 'from=latlng:35.61960:139.77720:1' \
  -d 'to=latlng:35.61950:139.77700:1' \
  -d 'allow_subgraph=true' \
  -d 'solver=dopt' \
  -d 'attempts=100' \
  --data-urlencode 'coverage_state={"covered_link_ids":["EDITOR_link_previous"],"excluded_link_ids":["EDITOR_link_current"],"traversal_history":[{"link_id":"EDITOR_link_previous","source_node_id":"EDITOR_node_before","target_node_id":"EDITOR_node_entry"}]}' \
  --data-urlencode 'preferences={"min_width":"9","slope":"9","road_condition":"9","stairs":"9","deff_LV":"9","esc":"9","mvw":"9","elv":"9"}' \
  | tee egress-route.json
```

Verify that the first Link is the temporary egress, that it points from the
temporary node to the history endpoint, and that the excluded physical link does
not occur elsewhere:

```bash
jq '{
  start_id: .[0]._id,
  egress_link_id: .[1]._id,
  egress_coverage_link_id: .[1].properties.coverage_link_id,
  egress_source: .[1].properties.sourceNode,
  egress_target: .[1].properties.targetNode,
  end_id: .[-1]._id
}' egress-route.json

# Must print 0: the excluded ID may appear only on a temporary egress Link.
jq '[.[] | select(
  .properties.coverage_link_id == "EDITOR_link_current" and
  ((._id // "") | startswith("_TEMP_LINK") | not)
)] | length' egress-route.json

# With all=true, inspect the same prefix and the unchanged candidate scores.
jq '.best_route[0:2], (.routes | map(.d_optimality))' candidates.json
```

The request returns HTTP 400 if `to` is omitted, history is empty, the last
history target does not identify exactly one endpoint of the current excluded
link, or the inferred entry endpoint cannot reach `to` or the required
components. Projection directly onto an endpoint does not need fixed egress and
uses the normal node-start behavior.

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

# Must print 0 for an excluded physical link outside a fixed-egress prefix
jq '[.[] | select(
  .properties.coverage_link_id == "EDITOR_link_2" and
  ((._id // "") | startswith("_TEMP_LINK") | not)
)] | length' route.json
```

Malformed state, unknown IDs, an excluded current-position link without the
fixed-egress prerequisites above, an unreachable end point, or unconnectable
required components return HTTP 400. If every link is complete and `from` equals
`to`, the successful JSON result is `{"error":"zero-distance"}`. A fixed-egress
prefix is a real movement, so its result is a route rather than `zero-distance`
even when the RPP suffix is empty.

## LinkCover GUI coverage workflow

Open `http://localhost:9090/map/linkcover.jsp?id=<user>` to edit and inspect the
coverage state on the map. Start with an empty `coverage_state` and run the first
route search to populate the selectable physical-link layer. The layer remains
visible when the navigation route is cleared and uses these styles:

When the first GUI route search succeeds with an empty `to` field, the page
stores that request's `from` value and displays it in `to (return point)`.
Subsequent searches on the same page send the stored value as `to`, while
`from` continues to follow the current position. A non-empty value entered by
the user overrides the automatic return point. A failed first search does not
store its `from`. **Reset coverage state** clears both the coverage state and
the stored return point, and a page reload also starts a new in-memory session.
As with API requests, a `latlng` return point is resolved to its nearest node.
Reaching the final navigation destination also clears the stored return point
and the `to` field, while retaining Covered, Excluded, and traversal history.
Manually ending navigation does not clear the return point.

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
