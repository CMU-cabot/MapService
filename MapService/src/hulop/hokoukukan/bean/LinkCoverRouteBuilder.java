/*******************************************************************************
 * Copyright (c) 2026  Keio University and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *******************************************************************************/
package hulop.hokoukukan.bean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.CholeskyDecomposition;
import org.apache.commons.math3.linear.NonPositiveDefiniteMatrixException;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.WeightedMultigraph;

import hulop.hokoukukan.servlet.RouteSearchServlet;
import hulop.hokoukukan.utils.Hokoukukan;

final class LinkCoverRouteBuilder {

	private static final int LINKCOVER_ATTEMPTS = RouteSearchServlet.getEnvInt("LINKCOVER_ATTEMPTS", 1000);
	private static final double LINKCOVER_WEIGHT_EPS = 1.0e-9;
	private static final double LINKCOVER_ODOMETRY_WEIGHT_SCALE = RouteSearchServlet.getEnvDouble(
			"LINKCOVER_ODOMETRY_WEIGHT_SCALE", 10.0);
	private static final double LINKCOVER_LOOP_CLOSURE_WEIGHT = RouteSearchServlet.getEnvDouble(
			"LINKCOVER_LOOP_CLOSURE_WEIGHT", 100.0);

	private final RouteSearchBean owner;
	private final JSONObject nodeMap;
	private final JSONArray features;
	private final Set<String> elevatorNodes;

	LinkCoverRouteBuilder(RouteSearchBean owner, JSONObject nodeMap, JSONArray features, Set<String> elevatorNodes) {
		this.owner = owner;
		this.nodeMap = nodeMap;
		this.features = features;
		this.elevatorNodes = elevatorNodes;
	}

	Object build(String from, String to, LinkCoverCoverageState coverageState, Map<String, String> conditions,
			boolean allowSubgraph, String solver, boolean all, int attempts) throws Exception {
		JSONObject fromPoint = RouteSearchBean.getPoint(from);
		Set<Double> floors;
		String originalLinkID = null;
		JSONObject originalStartLink = null;
		boolean explicitTo = to != null && !to.trim().isEmpty();

		// Resolve the caller-provided start location into a graph start node.
		// Lat/lng input is converted into a temporary node on the nearest link so
		// linkcover can start and end at the actual current position.
		if (fromPoint != null) {
			if (!fromPoint.has("floors")) {
				throw new Exception("Floor is required for linkcover");
			}
			floors = getFloorSet(fromPoint.getJSONArray("floors").getDouble(0));
			from = owner.findNearestLink(fromPoint);
			if (from == null) {
				throw new Exception("No start link found");
			}
			if (!nodeMap.has(from)) {
				for (Object feature : features) {
					JSONObject json = (JSONObject) feature;
					if (from.equals(json.get("_id"))) {
						from = owner.createTempNode(fromPoint, json);
						if (owner.getTempNode() != null) {
							originalLinkID = json.getString("_id");
							originalStartLink = json;
						}
						break;
					}
				}
			}
		} else {
			from = RouteSearchBean.extractNode(from);
			if (from == null || !owner.isNode(from)) {
				throw new Exception("Invalid start node");
			}
			floors = getFloorSet(owner.getHeight(from));
		}

		if (to == null || to.trim().isEmpty()) {
			to = from;
		} else {
			JSONObject toPoint = RouteSearchBean.getPoint(to);
			if (toPoint != null) {
				JSONArray toFloors = toPoint.has("floors") ? toPoint.getJSONArray("floors") : new JSONArray();
				if (toFloors.length() == 0) {
					for (Double floor : floors) {
						toFloors.add(floor);
					}
				}
				to = RouteSearchBean.adapter.findNearestNode(
						new double[] { toPoint.getDouble("lng"), toPoint.getDouble("lat") }, toFloors);
			} else {
				to = RouteSearchBean.extractNode(to);
			}
			if (to == null || !owner.isNode(to)) {
				throw new Exception("Invalid end node");
			}
			if (!floors.contains(owner.getHeight(to))) {
				throw new Exception("End node must be on the start floor");
			}
		}

		FixedEgress fixedEgress = null;
		String solverFrom = from;
		if (originalLinkID != null && coverageState.isExcluded(originalLinkID)) {
			fixedEgress = resolveFixedEgress(originalStartLink, originalLinkID, coverageState, explicitTo);
			solverFrom = fixedEgress.entryNodeId;
		}

		// Hand the filtered graph construction and solver-specific route generation
		// to LinkCoverHandler after the start node and target floor set are fixed.
		LinkCoverHandler lch = new LinkCoverHandler(solverFrom, to, coverageState, conditions, floors, originalLinkID,
				allowSubgraph, solver, all, attempts);
		for (Object feature : features) {
			lch.add(feature);
		}
		if (owner.getTempNode() != null) {
			lch.add(owner.getTempLink1());
			lch.add(owner.getTempLink2());
		}
		Object result = lch.getResult();
		if (fixedEgress != null) {
			result = prependFixedEgress(result, fixedEgress, all, solver);
		}
		return addStartAreaToLinkCoverResult(result, fromPoint);
	}

	private FixedEgress resolveFixedEgress(JSONObject originalLink, String originalLinkId,
			LinkCoverCoverageState coverageState, boolean explicitTo) throws Exception {
		if (!explicitTo) {
			throw new Exception("to is required when current position is on an excluded link: " + originalLinkId);
		}
		List<LinkCoverCoverageState.TraversalStep> history = coverageState.getTraversalHistory();
		if (history.isEmpty()) {
			throw new Exception(
					"traversal_history is required to leave the current excluded link: " + originalLinkId);
		}

		JSONObject properties = originalLink.getJSONObject("properties");
		String start = properties.getString("start_id");
		String end = properties.getString("end_id");
		String historyTarget = history.get(history.size() - 1).getTargetNodeId();
		boolean enteredFromStart = historyTarget.equals(start);
		boolean enteredFromEnd = historyTarget.equals(end);
		if (enteredFromStart == enteredFromEnd) {
			throw new Exception("traversal_history target does not identify the entry endpoint of current excluded link: "
					+ originalLinkId);
		}

		FixedEgress egress = new FixedEgress();
		egress.coverageLinkId = originalLinkId;
		egress.entryNodeId = enteredFromStart ? start : end;
		egress.link = enteredFromStart ? owner.getTempLink1() : owner.getTempLink2();
		if (egress.link == null) {
			throw new Exception("Failed to create temporary egress link for current excluded link: " + originalLinkId);
		}
		return egress;
	}

	private Object prependFixedEgress(Object result, FixedEgress egress, boolean all, String solver) throws Exception {
		if (result instanceof JSONArray) {
			return prependFixedEgressRoute((JSONArray) result, egress);
		}
		if (!(result instanceof JSONObject)) {
			return result;
		}

		JSONObject json = (JSONObject) result;
		if (json.has("error") && "zero-distance".equals(json.optString("error"))) {
			if (all && (solver == null || "dopt".equalsIgnoreCase(solver))) {
				JSONObject allResult = new JSONObject();
				allResult.put("best_route", prependFixedEgressRoute(null, egress));
				JSONObject candidate = new JSONObject();
				candidate.put("d_optimality", 0.0);
				candidate.put("route", prependFixedEgressRoute(null, egress));
				allResult.put("routes", new JSONArray().put(candidate));
				return allResult;
			}
			return prependFixedEgressRoute(null, egress);
		}

		if (json.has("best_route")) {
			json.put("best_route", prependFixedEgressRoute(json.getJSONArray("best_route"), egress));
		}
		if (json.has("routes")) {
			JSONArray routes = json.getJSONArray("routes");
			for (int i = 0; i < routes.length(); i++) {
				JSONObject candidate = routes.getJSONObject(i);
				if (candidate.has("route")) {
					candidate.put("route", prependFixedEgressRoute(candidate.getJSONArray("route"), egress));
				}
			}
		}
		return json;
	}

	private JSONArray prependFixedEgressRoute(JSONArray solverRoute, FixedEgress egress) throws Exception {
		// The retreat is intentionally outside the RPP augmentation based on
		// Frederickson, "Approximation Algorithms for Some Postman Problems" (1979),
		// Edmonds and Johnson, "Matching, Euler Tours and the Chinese Postman" (1973),
		// Hierholzer, "Ueber die Moeglichkeit, einen Linienzug ohne Wiederholung und
		// ohne Unterbrechung zu umfahren" (1873), and Gao et al., "Active Loop Closure
		// for OSM-guided Robotic Mapping in Large-Scale Urban Environments" (2024).
		// Keeping both excluded fragments out of that graph prevents the solver from
		// crossing or reusing the excluded physical link.
		JSONArray route = new JSONArray();
		route.add(new JSONObject(owner.getTempNode().toString()));
		route.add(formatRouteLink(egress.link, egress.coverageLinkId, RouteSearchBean.tempNodeID,
				egress.entryNodeId));
		if (solverRoute == null || solverRoute.length() == 0) {
			route.add(new JSONObject(owner.getNode(egress.entryNodeId).toString()));
			return route;
		}

		JSONObject solverStart = solverRoute.getJSONObject(0);
		if (!egress.entryNodeId.equals(solverStart.optString("_id"))) {
			throw new Exception("RPP route does not start at the fixed-egress endpoint: " + egress.entryNodeId);
		}
		for (int i = 1; i < solverRoute.length(); i++) {
			route.add(solverRoute.get(i));
		}
		return route;
	}

	private JSONObject formatRouteLink(JSONObject sourceLink, String coverageLinkId, String source, String target)
			throws Exception {
		JSONObject link = new JSONObject(sourceLink.toString());
		JSONObject properties = link.getJSONObject("properties");
		properties.put("coverage_link_id", coverageLinkId);
		properties.put("sourceNode", source);
		properties.put("targetNode", target);
		properties.put("sourceHeight", owner.getHeight(source));
		properties.put("targetHeight", owner.getHeight(target));
		int sourceDoor = owner.getDoor(source);
		int targetDoor = owner.getDoor(target);
		if (sourceDoor != 100) {
			properties.put("sourceDoor", sourceDoor);
		} else {
			properties.remove("sourceDoor");
		}
		if (targetDoor != 100) {
			properties.put("targetDoor", targetDoor);
		} else {
			properties.remove("targetDoor");
		}
		return link;
	}

	private Set<Double> getFloorSet(double floor) {
		Set<Double> floors = new LinkedHashSet<Double>();
		floors.add(floor);
		if (floor == 1) {
			floors.add(0d);
		}
		return floors;
	}

	private boolean isSameFloor(String start, String end, Set<Double> floors) throws JSONException {
		return floors.contains(owner.getHeight(start)) && floors.contains(owner.getHeight(end));
	}

	private Object addStartAreaToLinkCoverResult(Object result, JSONObject startPos) {
		if (startPos == null || result == null) {
			return result;
		}
		try {
			if (result instanceof JSONArray) {
				return owner.addStartArea((JSONArray) result, startPos);
			}
			if (result instanceof JSONObject) {
				JSONObject json = (JSONObject) result;
				if (json.has("best_route")) {
					json.put("best_route", owner.addStartArea(json.getJSONArray("best_route"), startPos));
				}
				if (json.has("routes")) {
					JSONArray routes = json.getJSONArray("routes");
					for (int i = 0; i < routes.length(); i++) {
						JSONObject route = routes.getJSONObject(i);
						if (route.has("route")) {
							route.put("route", owner.addStartArea(route.getJSONArray("route"), startPos));
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	private class LinkCoverHandler {
		private DirectedWeightedMultigraph<String, DefaultWeightedEdge> g = new DirectedWeightedMultigraph<String, DefaultWeightedEdge>(
				DefaultWeightedEdge.class);
		private Map<Object, JSONObject> linkMap = new HashMap<Object, JSONObject>();
		private Map<Object, String> coverageLinkIdMap = new HashMap<Object, String>();
		private Map<String, LinkCoverCoverageState.LinkEndpoints> knownLinkEndpoints = new LinkedHashMap<String, LinkCoverCoverageState.LinkEndpoints>();
		private Set<String> availableCoverageLinkIds = new LinkedHashSet<String>();
		private String from;
		private String to;
		private LinkCoverCoverageState coverageState;
		private Map<String, String> conditions;
		private Set<Double> floors;
		private String originalLinkID;
		private boolean allowSubgraph;
		private String solver;
		private boolean all;
		private int attempts;
		private Set<String> directionIgnoredLinkIds = new LinkedHashSet<String>();
		private final double elevator_weight;

		public LinkCoverHandler(String from, String to, LinkCoverCoverageState coverageState,
				Map<String, String> conditions, Set<Double> floors, String originalLinkID, boolean allowSubgraph,
				String solver, boolean all, int attempts) {
			this.from = from;
			this.to = to;
			this.coverageState = coverageState;
			this.conditions = conditions;
			this.floors = floors;
			this.originalLinkID = originalLinkID;
			this.allowSubgraph = allowSubgraph;
			this.solver = solver != null ? solver.toLowerCase() : "dopt";
			this.all = all;
			this.attempts = attempts > 0 ? attempts : LINKCOVER_ATTEMPTS;
			this.elevator_weight = RouteSearchBean.ELEVATOR_WEIGHT * ("8".equals(conditions.get("elv")) ? 10 : 1);
		}

		public void add(Object feature) throws JSONException {
			JSONObject json = (JSONObject) feature;
			JSONObject properties = json.getJSONObject("properties");
			// Build the traversable solver graph that linkcover will solve on.
			// Only links that remain usable after accessibility filtering and start-floor
			// restriction are kept in the solver graph.
			if (!properties.has("link_id")) {
				return;
			}
			String start, end;
			try {
				start = properties.getString("start_id");
				end = properties.getString("end_id");
			} catch (Exception e) {
				return;
			}
			if (!owner.isNode(start) || !owner.isNode(end) || !isSameFloor(start, end, floors)) {
				return;
			}

			String featureId = getLinkId(json, properties);
			boolean isOriginalStartLink = originalLinkID != null && originalLinkID.equals(featureId);
			boolean isTemporaryStartLink = originalLinkID != null
					&& (RouteSearchBean.tempLink1ID.equals(featureId) || RouteSearchBean.tempLink2ID.equals(featureId));
			String coverageLinkId = isTemporaryStartLink ? originalLinkID : featureId;
			// Traversal history describes complete physical-link traversals. Always let
			// the original start link override temporary split endpoints, regardless of
			// feature iteration order.
			if (!isTemporaryStartLink || !knownLinkEndpoints.containsKey(coverageLinkId)) {
				knownLinkEndpoints.put(coverageLinkId, new LinkCoverCoverageState.LinkEndpoints(start, end));
			}
			if (!Hokoukukan.available(properties)) {
				return;
			}
			double weight = computeBaseWeight(properties);
			if (weight == RouteSearchBean.WEIGHT_IGNORE) {
				return;
			}
			availableCoverageLinkIds.add(coverageLinkId);
			if (isOriginalStartLink) {
				return;
			}
			if (coverageState.isExcluded(coverageLinkId)) {
				return;
			}
			g.addVertex(start);
			g.addVertex(end);

			int direction = owner.getCode(properties, "direction", 99);
			if (direction == 2 || direction == 3) {
				String linkId = getLinkId(json, properties);
				if (directionIgnoredLinkIds.add(linkId)) {
					System.err.println("linkcover ignores one-way direction and treats link as undirected: " + linkId
							+ " direction=" + direction);
				}
			}

			// Linkcover is a physical-link coverage route, so direction metadata is
			// intentionally ignored and every accepted link is traversable both ways.
			DefaultWeightedEdge startEnd = g.addEdge(start, end);
			DefaultWeightedEdge endStart = g.addEdge(end, start);
			if (startEnd != null) {
				double add = !elevatorNodes.contains(start) && elevatorNodes.contains(end) ? elevator_weight : 0;
				g.setEdgeWeight(startEnd, weight + add);
				linkMap.put(startEnd, json);
				coverageLinkIdMap.put(startEnd, coverageLinkId);
			}
			if (endStart != null) {
				double add = elevatorNodes.contains(start) && !elevatorNodes.contains(end) ? elevator_weight : 0;
				g.setEdgeWeight(endStart, weight + add);
				linkMap.put(endStart, json);
				coverageLinkIdMap.put(endStart, coverageLinkId);
			}
		}

		private double computeBaseWeight(JSONObject properties) throws JSONException {
			double weight = 10.0f;
			try {
				weight = properties.getDouble("distance");
				if (properties.getInt("hulop_road_low_priority") == 1) {
					weight *= 1.25;
				}
			} catch (Exception e) {
			}
			return owner.adjustAccWeight(properties, weight, conditions);
		}

		private double computeLinkLength(JSONObject properties, double fallback) {
			try {
				double length = properties.getDouble("distance");
				if (length > 0) {
					return length;
				}
			} catch (Exception e) {
			}
			return Math.max(fallback, LINKCOVER_WEIGHT_EPS);
		}

		private String getLinkId(JSONObject json, JSONObject properties) {
			try {
				return json.getString("_id");
			} catch (Exception e) {
			}
			try {
				return properties.getString("link_id");
			} catch (Exception e) {
			}
			return "<unknown>";
		}

		public Object getResult() throws Exception {
			coverageState.validate(knownLinkEndpoints);
			validateSolverOptions();

			// Optionally shrink the graph to the part that is actually reachable from
			// the start node before running either solver.
			if (allowSubgraph) {
				restrictToReachableSubgraph();
			}

			boolean hasRequiredKnownLink = false;
			for (String linkId : availableCoverageLinkIds) {
				if (!coverageState.isExcluded(linkId) && !coverageState.isCovered(linkId)) {
					hasRequiredKnownLink = true;
					break;
				}
			}
			if (g.edgeSet().isEmpty()) {
				if (!hasRequiredKnownLink && from.equals(to)) {
					return new JSONObject().put("error", "zero-distance");
				}
				throw new Exception("No links to cover on the start floor");
			}
			if (!g.containsVertex(from)) {
				throw new Exception("Start node is not connected");
			}
			if (!g.containsVertex(to)) {
				throw new Exception("End node is not reachable through allowed links");
			}

			AbstractGraphContext context = buildAbstractGraphContext();
			AugmentedAbstractGraph augmentedGraph = buildRppAugmentedGraph(context);
			if (augmentedGraph.edgeOrder.isEmpty()) {
				return new JSONObject().put("error", "zero-distance");
			}

			// CPP compatibility mode now uses the same RPP augmentation but returns one
			// deterministic Euler trail without D-opt candidate enumeration.
			if ("cpp".equals(solver)) {
				LinkCoverCandidate candidate = buildAbstractCandidate(augmentedGraph, null);
				if (candidate == null) {
					throw new Exception("Failed to build link cover route");
				}
				return candidate.route;
			}

			// The D-opt solver enumerates candidate tours and then picks the
			// highest-scoring order-sensitive route.
			List<LinkCoverCandidate> candidates = buildDoptCandidates(augmentedGraph);
			if (candidates.isEmpty()) {
				throw new Exception("Failed to build link cover route");
			}
			Collections.sort(candidates, new Comparator<LinkCoverCandidate>() {
				@Override
				public int compare(LinkCoverCandidate a, LinkCoverCandidate b) {
					return compareCandidates(a, b);
				}
			});
			if (!all) {
				return candidates.get(0).route;
			}
			return buildAllResponse(candidates);
		}

		private void validateSolverOptions() throws Exception {
			if (!("cpp".equals(solver) || "dopt".equals(solver))) {
				throw new Exception("Unsupported solver: " + solver);
			}
			if (all && !"dopt".equals(solver)) {
				throw new Exception("all=true is only supported with solver=dopt");
			}
			if ("dopt".equals(solver) && attempts <= 0) {
				throw new Exception("attempts must be positive");
			}
		}

		private List<LinkCoverCandidate> buildDoptCandidates(AugmentedAbstractGraph augmentedGraph) throws Exception {
			Map<String, LinkCoverCandidate> candidates = new LinkedHashMap<String, LinkCoverCandidate>();

			// Build the undirected abstract graph used by the paper-inspired D-opt solver,
			// augment it once to become Eulerian, then sample candidate tours by
			// randomizing Hierholzer's next-edge choices.
			int seed = from.hashCode() ^ to.hashCode() ^ coverageState.routingFingerprint().hashCode()
					^ g.edgeSet().size() ^ attempts;
			Random random = new Random(seed);
			for (int i = 0; i < attempts; i++) {
				LinkCoverCandidate candidate = buildAbstractCandidate(augmentedGraph, random);
				if (candidate != null) {
					candidates.put(candidate.signature, candidate);
				}
			}
			return new ArrayList<LinkCoverCandidate>(candidates.values());
		}

		private AbstractGraphContext buildAbstractGraphContext() throws Exception {
			AbstractGraphContext context = new AbstractGraphContext();
			Map<String, AbstractLink> abstractLinks = new LinkedHashMap<String, AbstractLink>();

			// Collapse the directed traversal graph into an undirected abstract graph
			// where each physical link is represented once and carries the travel cost
			// used for odd-node matching and augmentation.
			context.graph = new WeightedMultigraph<String, DefaultWeightedEdge>(DefaultWeightedEdge.class);
			for (String vertex : g.vertexSet()) {
				context.graph.addVertex(vertex);
			}
			for (DefaultWeightedEdge edge : g.edgeSet()) {
				JSONObject json = linkMap.get(edge);
				if (json == null) {
					continue;
				}
				String id = json.getString("_id");
				AbstractLink info = abstractLinks.get(id);
				if (info == null) {
					JSONObject properties = json.getJSONObject("properties");
					info = new AbstractLink();
					info.id = id;
					info.coverageId = coverageLinkIdMap.get(edge);
					info.json = json;
					info.start = properties.getString("start_id");
					info.end = properties.getString("end_id");
					info.weight = computeBaseWeight(properties);
					info.length = computeLinkLength(properties, info.weight);
					abstractLinks.put(id, info);
				}
				String source = g.getEdgeSource(edge);
				String target = g.getEdgeTarget(edge);
				if (info.start.equals(source) && info.end.equals(target)) {
					info.forwardEdge = edge;
				} else if (info.start.equals(target) && info.end.equals(source)) {
					info.backwardEdge = edge;
				}
			}
			for (AbstractLink info : abstractLinks.values()) {
				context.graph.addVertex(info.start);
				context.graph.addVertex(info.end);
				DefaultWeightedEdge edge = context.graph.addEdge(info.start, info.end);
				context.graph.setEdgeWeight(edge, info.weight);
				context.linkMap.put(edge, info);
				context.edgeData.put(edge, new LinkCoverRppSolver.EdgeData<AbstractLink>(info.id, info,
						!coverageState.isCovered(info.coverageId)));
			}
			return context;
		}

		private AugmentedAbstractGraph buildRppAugmentedGraph(AbstractGraphContext context) throws Exception {
			LinkCoverRppSolver<AbstractLink> solver = new LinkCoverRppSolver<AbstractLink>(context.graph,
					context.edgeData, from, to);
			LinkCoverRppSolver.Result<AbstractLink> solved = solver.solve();
			AugmentedAbstractGraph augmented = new AugmentedAbstractGraph();
			augmented.graph = solved.graph;
			augmented.edgeOrder.addAll(solved.edgeOrder);
			for (Map.Entry<DefaultWeightedEdge, LinkCoverRppSolver.EdgeData<AbstractLink>> entry : solved.edgeData
					.entrySet()) {
				augmented.linkMap.put(entry.getKey(), entry.getValue().payload);
			}
			return augmented;
		}

		private LinkCoverCandidate buildAbstractCandidate(AugmentedAbstractGraph augmentedGraph, Random random)
				throws Exception {
			// Enumerate one Eulerian tour by running Hierholzer on the augmented graph
			// with randomized branch choices at vertices that still have multiple options.
			List<AbstractTraversalStep> steps = buildEulerianTrail(augmentedGraph, random);
			if (steps == null || steps.isEmpty()) {
				return null;
			}

			// Map the abstract traversal back onto directed edges that are actually
			// traversable in the original solver graph before scoring the route.
			List<DefaultWeightedEdge> directedEdges = resolveDirectedEdges(steps);
			if (directedEdges == null) {
				return null;
			}
			LinkCoverCandidate candidate = new LinkCoverCandidate();
			double totalLength = 0;
			for (AbstractTraversalStep step : steps) {
				totalLength += step.link.length;
			}
			candidate.edges = directedEdges;
			candidate.distance = totalLength;
			candidate.signature = buildAbstractOrderedSignature(steps);
			candidate.dOptimality = computeDOptimality(steps);
			candidate.route = formatRoute(directedEdges);
			return candidate;
		}

		private List<AbstractTraversalStep> buildEulerianTrail(AugmentedAbstractGraph augmentedGraph, Random random)
				throws Exception {
			Map<String, List<DefaultWeightedEdge>> adjacency = new HashMap<String, List<DefaultWeightedEdge>>();

			// C. Hierholzer, "Ueber die Moeglichkeit, einen Linienzug ohne
			// Wiederholung und ohne Unterbrechung zu umfahren" (1873), supplies the
			// traversal. For D-opt mode, random next-edge order implements the random
			// exhaustive candidate search used by Gao et al., "Active Loop Closure for
			// OSM-guided Robotic Mapping in Large-Scale Urban Environments" (2024).
			for (DefaultWeightedEdge edge : augmentedGraph.edgeOrder) {
				String source = augmentedGraph.graph.getEdgeSource(edge);
				String target = augmentedGraph.graph.getEdgeTarget(edge);
				List<DefaultWeightedEdge> list = adjacency.get(source);
				if (list == null) {
					adjacency.put(source, list = new ArrayList<DefaultWeightedEdge>());
				}
				list.add(edge);
				list = adjacency.get(target);
				if (list == null) {
					adjacency.put(target, list = new ArrayList<DefaultWeightedEdge>());
				}
				list.add(edge);
			}
			if (random != null) {
				for (List<DefaultWeightedEdge> list : adjacency.values()) {
					Collections.shuffle(list, random);
				}
			}

			Map<String, Integer> nextIndex = new HashMap<String, Integer>();
			Set<DefaultWeightedEdge> usedEdges = new LinkedHashSet<DefaultWeightedEdge>();
			List<String> vertexStack = new ArrayList<String>();
			List<AbstractTraversalStep> stepStack = new ArrayList<AbstractTraversalStep>();
			List<AbstractTraversalStep> circuit = new ArrayList<AbstractTraversalStep>();
			vertexStack.add(from);

			// Standard Hierholzer traversal: grow and backtrack through unused edges
			// until every augmented abstract edge has been consumed exactly once.
			while (!vertexStack.isEmpty()) {
				String node = vertexStack.get(vertexStack.size() - 1);
				DefaultWeightedEdge edge = getNextEulerianEdge(adjacency, nextIndex, usedEdges, node);
				if (edge != null) {
					String target = getOppositeVertex(augmentedGraph.graph, edge, node);
					AbstractTraversalStep step = new AbstractTraversalStep();
					step.edge = edge;
					step.link = augmentedGraph.linkMap.get(edge);
					step.source = node;
					step.target = target;
					stepStack.add(step);
					vertexStack.add(target);
				} else {
					vertexStack.remove(vertexStack.size() - 1);
					if (!stepStack.isEmpty()) {
						circuit.add(stepStack.remove(stepStack.size() - 1));
					}
				}
			}

			Collections.reverse(circuit);
			if (circuit.size() != augmentedGraph.graph.edgeSet().size()) {
				throw new Exception("Failed to build Eulerian trail");
			}
			if (!circuit.isEmpty() && (!from.equals(circuit.get(0).source)
					|| !to.equals(circuit.get(circuit.size() - 1).target))) {
				throw new Exception("Failed to align Hierholzer trail with start and end nodes");
			}
			return circuit;
		}

		private DefaultWeightedEdge getNextEulerianEdge(Map<String, List<DefaultWeightedEdge>> adjacency,
				Map<String, Integer> nextIndex, Set<DefaultWeightedEdge> usedEdges, String node) {
			List<DefaultWeightedEdge> edges = adjacency.get(node);
			if (edges == null) {
				return null;
			}
			Integer index = nextIndex.get(node);
			int next = index != null ? index : 0;
			while (next < edges.size()) {
				DefaultWeightedEdge edge = edges.get(next++);
				if (usedEdges.add(edge)) {
					nextIndex.put(node, next);
					return edge;
				}
			}
			nextIndex.put(node, next);
			return null;
		}

		private String getOppositeVertex(Graph<String, DefaultWeightedEdge> graph, DefaultWeightedEdge edge, String node)
				throws Exception {
			String source = graph.getEdgeSource(edge);
			String target = graph.getEdgeTarget(edge);
			if (node.equals(source)) {
				return target;
			}
			if (node.equals(target)) {
				return source;
			}
			throw new Exception("Edge is not incident to the current node");
		}

		private List<DefaultWeightedEdge> resolveDirectedEdges(List<AbstractTraversalStep> steps) {
			List<DefaultWeightedEdge> directedEdges = new ArrayList<DefaultWeightedEdge>();
			for (AbstractTraversalStep step : steps) {
				DefaultWeightedEdge edge = resolveDirectedEdge(step);
				if (edge == null) {
					return null;
				}
				directedEdges.add(edge);
			}
			return directedEdges;
		}

		private DefaultWeightedEdge resolveDirectedEdge(AbstractTraversalStep step) {
			if (step.link == null) {
				return null;
			}
			if (step.link.start.equals(step.source) && step.link.end.equals(step.target)) {
				return step.link.forwardEdge;
			}
			if (step.link.start.equals(step.target) && step.link.end.equals(step.source)) {
				return step.link.backwardEdge;
			}
			return null;
		}

		private String buildAbstractOrderedSignature(List<AbstractTraversalStep> steps) {
			for (AbstractTraversalStep step : steps) {
				if (step.link == null) {
					return "";
				}
			}
			StringBuilder sb = new StringBuilder();
			for (AbstractTraversalStep step : steps) {
				if (sb.length() > 0) {
					sb.append('|');
				}
				sb.append(step.source).append('>')
						.append(step.target).append('#')
						.append(step.link.id);
			}
			return sb.toString();
		}

		private int compareCandidates(LinkCoverCandidate a, LinkCoverCandidate b) {
			int cmp = Double.compare(b.dOptimality, a.dOptimality);
			if (cmp != 0) {
				return cmp;
			}
			cmp = Double.compare(a.distance, b.distance);
			if (cmp != 0) {
				return cmp;
			}
			String aSignature = a.signature != null ? a.signature : "";
			String bSignature = b.signature != null ? b.signature : "";
			return aSignature.compareTo(bSignature);
		}

		private double computeDOptimality(List<AbstractTraversalStep> steps) {
			if (steps == null || steps.isEmpty()) {
				return Double.NEGATIVE_INFINITY;
			}

			// Gao et al., "Active Loop Closure for OSM-guided Robotic Mapping in
			// Large-Scale Urban Environments" (2024), evaluate candidate routes with
			// TOED-based D-optimality. This v1 intentionally scores only the newly
			// planned route and does not yet incorporate traversal_history.
			List<String> visits = new ArrayList<String>();
			visits.add(from);
			String current = from;
			for (AbstractTraversalStep step : steps) {
				if (!current.equals(step.source)) {
					return Double.NEGATIVE_INFINITY;
				}
				visits.add(step.target);
				current = step.target;
			}
			if (visits.size() <= 1) {
				return 0;
			}
			double[][] laplacian = new double[visits.size() - 1][visits.size() - 1];
			Map<String, Integer> lastVisitByCoordinate = new HashMap<String, Integer>();
			String startKey = getCoordinateKey(from);
			if (startKey == null) {
				return Double.NEGATIVE_INFINITY;
			}
			lastVisitByCoordinate.put(startKey, 0);
			for (int i = 1; i < visits.size(); i++) {
				AbstractTraversalStep step = steps.get(i - 1);

				// Consecutive traversals contribute odometry-style constraints whose
				// strength is inversely proportional to the traversed link length.
				double odometryWeight = LINKCOVER_ODOMETRY_WEIGHT_SCALE
						/ Math.max(step.link != null ? step.link.length : 0.0, LINKCOVER_WEIGHT_EPS);
				addLaplacianEdge(laplacian, i - 1, i, odometryWeight);

				String coordinateKey = getCoordinateKey(visits.get(i));
				if (coordinateKey == null) {
					return Double.NEGATIVE_INFINITY;
				}
				Integer previousVisit = lastVisitByCoordinate.put(coordinateKey, i);
				if (previousVisit != null) {
					// Revisiting the same coordinate adds a stronger loop-closure constraint
					// between the previous visit instance and the current one.
					addLaplacianEdge(laplacian, previousVisit, i, LINKCOVER_LOOP_CLOSURE_WEIGHT);
				}
			}
			try {
				// Use the log-determinant of the reduced Laplacian as the D-optimality
				// score so routes with richer revisit structure rank higher.
				RealMatrix matrix = new Array2DRowRealMatrix(laplacian, false);
				RealMatrix l = new CholeskyDecomposition(matrix, 1.0e-10, 1.0e-10).getL();
				double[][] data = l.getData();
				double logDet = 0;
				for (int i = 0; i < data.length; i++) {
					logDet += Math.log(Math.max(data[i][i], LINKCOVER_WEIGHT_EPS));
				}
				return 2 * logDet;
			} catch (NonPositiveDefiniteMatrixException e) {
				return Double.NEGATIVE_INFINITY;
			}
		}

		private void addLaplacianEdge(double[][] laplacian, int sourceVisit, int targetVisit, double weight) {
			if (sourceVisit == targetVisit) {
				return;
			}
			int sourceIndex = sourceVisit - 1;
			int targetIndex = targetVisit - 1;
			if (sourceIndex >= 0) {
				laplacian[sourceIndex][sourceIndex] += weight;
			}
			if (targetIndex >= 0) {
				laplacian[targetIndex][targetIndex] += weight;
			}
			if (sourceIndex >= 0 && targetIndex >= 0) {
				laplacian[sourceIndex][targetIndex] -= weight;
				laplacian[targetIndex][sourceIndex] -= weight;
			}
		}

		private String getCoordinateKey(String nodeId) {
			try {
				JSONObject node = owner.getNode(nodeId);
				JSONArray coordinates = node.getJSONObject("geometry").getJSONArray("coordinates");
				double floor = node.getJSONObject("properties").getDouble("floor");
				return coordinates.get(0).toString() + "," + coordinates.get(1).toString() + "," + floor;
			} catch (Exception e) {
				return null;
			}
		}

		private JSONArray formatRoute(List<DefaultWeightedEdge> edges) {
			JSONArray route = new JSONArray();
			try {
				// Convert the chosen directed edge sequence back into the same node/link
				// array shape used by the existing route-search response.
				JSONObject fromNode = (JSONObject) owner.getNode(from).clone();
				route.add(fromNode);
				for (DefaultWeightedEdge edge : edges) {
					JSONObject link = linkMap.get(edge);
					try {
						String edgeSource = g.getEdgeSource(edge);
						String edgeTarget = g.getEdgeTarget(edge);
						link = formatRouteLink(link, coverageLinkIdMap.get(edge), edgeSource, edgeTarget);
					} catch (Exception e) {
						e.printStackTrace();
					}
					route.add(link);
				}
				JSONObject toNode = (JSONObject) owner.getNode(to).clone();
				route.add(toNode);
			} catch (Exception e) {
				e.printStackTrace();
			}
			return route;
		}

		private JSONObject buildAllResponse(List<LinkCoverCandidate> candidates) throws JSONException {
			// Return both the best route and the scored candidate list when the caller
			// explicitly asks to inspect the full D-opt search result set.
			JSONObject result = new JSONObject();
			result.put("best_route", candidates.get(0).route);
			JSONArray routes = new JSONArray();
			for (LinkCoverCandidate candidate : candidates) {
				JSONObject item = new JSONObject();
				item.put("d_optimality", candidate.dOptimality);
				item.put("route", candidate.route);
				routes.add(item);
			}
			result.put("routes", routes);
			return result;
		}

		private void restrictToReachableSubgraph() {
			if (!g.containsVertex(from)) {
				return;
			}

			// Traverse only along currently allowed directed edges so optional subgraph
			// mode limits linkcover to what the start node can actually reach.
			Set<String> reachableVertices = new LinkedHashSet<String>();
			List<String> open = new ArrayList<String>();
			reachableVertices.add(from);
			open.add(from);

			for (int i = 0; i < open.size(); i++) {
				String node = open.get(i);
				for (DefaultWeightedEdge edge : g.edgeSet()) {
					if (!node.equals(g.getEdgeSource(edge))) {
						continue;
					}
					String target = g.getEdgeTarget(edge);
					if (reachableVertices.add(target)) {
						open.add(target);
					}
				}
			}

			DirectedWeightedMultigraph<String, DefaultWeightedEdge> subgraph = new DirectedWeightedMultigraph<String, DefaultWeightedEdge>(
					DefaultWeightedEdge.class);
			Map<Object, JSONObject> subLinkMap = new HashMap<Object, JSONObject>();
			Map<Object, String> subCoverageLinkIdMap = new HashMap<Object, String>();
			for (DefaultWeightedEdge edge : g.edgeSet()) {
				String source = g.getEdgeSource(edge);
				String target = g.getEdgeTarget(edge);
				if (!(reachableVertices.contains(source) && reachableVertices.contains(target))) {
					continue;
				}
				subgraph.addVertex(source);
				subgraph.addVertex(target);
				DefaultWeightedEdge subEdge = subgraph.addEdge(source, target);
				subgraph.setEdgeWeight(subEdge, g.getEdgeWeight(edge));
				subLinkMap.put(subEdge, linkMap.get(edge));
				subCoverageLinkIdMap.put(subEdge, coverageLinkIdMap.get(edge));
			}

			g = subgraph;
			linkMap = subLinkMap;
			coverageLinkIdMap = subCoverageLinkIdMap;
		}
	}

	private static class LinkCoverCandidate {
		private List<DefaultWeightedEdge> edges;
		private double distance;
		private double dOptimality;
		private String signature;
		private JSONArray route;
	}

	private static class FixedEgress {
		private JSONObject link;
		private String coverageLinkId;
		private String entryNodeId;
	}

	private static class AbstractLink {
		private String id;
		private String coverageId;
		private JSONObject json;
		private String start;
		private String end;
		private double weight;
		private double length;
		private DefaultWeightedEdge forwardEdge;
		private DefaultWeightedEdge backwardEdge;
	}

	private static class AbstractGraphContext {
		private WeightedMultigraph<String, DefaultWeightedEdge> graph;
		private Map<DefaultWeightedEdge, AbstractLink> linkMap = new HashMap<DefaultWeightedEdge, AbstractLink>();
		private Map<DefaultWeightedEdge, LinkCoverRppSolver.EdgeData<AbstractLink>> edgeData = new HashMap<DefaultWeightedEdge, LinkCoverRppSolver.EdgeData<AbstractLink>>();
	}

	private static class AugmentedAbstractGraph {
		private WeightedMultigraph<String, DefaultWeightedEdge> graph;
		private Map<DefaultWeightedEdge, AbstractLink> linkMap = new HashMap<DefaultWeightedEdge, AbstractLink>();
		private List<DefaultWeightedEdge> edgeOrder = new ArrayList<DefaultWeightedEdge>();
	}

	private static class AbstractTraversalStep {
		private DefaultWeightedEdge edge;
		private AbstractLink link;
		private String source;
		private String target;
	}
}
