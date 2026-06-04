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
import org.jgrapht.GraphPath;
import org.jgrapht.GraphTests;
import org.jgrapht.alg.cycle.ChinesePostman;
import org.jgrapht.alg.interfaces.MatchingAlgorithm;
import org.jgrapht.alg.matching.blossom.v5.KolmogorovWeightedPerfectMatching;
import org.jgrapht.alg.matching.blossom.v5.ObjectiveSense;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.SimpleWeightedGraph;
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

	Object build(String from, Map<String, String> conditions, boolean allowSubgraph, String solver, boolean all,
			int attempts) throws Exception {
		JSONObject fromPoint = RouteSearchBean.getPoint(from);
		Set<Double> floors;
		String originalLinkID = null;

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

		// Hand the filtered graph construction and solver-specific route generation
		// to LinkCoverHandler after the start node and target floor set are fixed.
		LinkCoverHandler lch = new LinkCoverHandler(from, conditions, floors, originalLinkID, allowSubgraph, solver, all,
				attempts);
		for (Object feature : features) {
			lch.add(feature);
		}
		if (owner.getTempNode() != null) {
			lch.add(owner.getTempLink1());
			lch.add(owner.getTempLink2());
		}
		return addStartAreaToLinkCoverResult(lch.getResult(), fromPoint);
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
		private String from;
		private Map<String, String> conditions;
		private Set<Double> floors;
		private String originalLinkID;
		private boolean allowSubgraph;
		private String solver;
		private boolean all;
		private int attempts;
		private Set<String> directionIgnoredLinkIds = new LinkedHashSet<String>();
		private final double elevator_weight;

		public LinkCoverHandler(String from, Map<String, String> conditions, Set<Double> floors, String originalLinkID,
				boolean allowSubgraph, String solver, boolean all, int attempts) {
			this.from = from;
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
			if (!(properties.has("link_id") && Hokoukukan.available(properties))) {
				return;
			}
			if (originalLinkID != null) {
				try {
					if (originalLinkID.equals(json.getString("_id")) || originalLinkID.equals(properties.getString("link_id"))) {
						return;
					}
				} catch (Exception e) {
				}
			}
			double weight = computeBaseWeight(properties);
			if (weight == RouteSearchBean.WEIGHT_IGNORE) {
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
			}
			if (endStart != null) {
				double add = elevatorNodes.contains(start) && !elevatorNodes.contains(end) ? elevator_weight : 0;
				g.setEdgeWeight(endStart, weight + add);
				linkMap.put(endStart, json);
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
			// Optionally shrink the graph to the part that is actually reachable from
			// the start node before running either solver.
			if (allowSubgraph) {
				restrictToReachableSubgraph();
			}

			// Reject graphs that cannot produce a closed traversal from the requested start.
			if (g.edgeSet().isEmpty()) {
				throw new Exception("No links to cover on the start floor");
			}
			if (!g.containsVertex(from)) {
				throw new Exception("Start node is not connected");
			}
			if (!GraphTests.isStronglyConnected(g)) {
				throw new Exception("Links on the start floor are not strongly connected");
			}

			// Keep the original CPP path as a compatibility mode that returns one
			// closed route without candidate enumeration or D-opt scoring.
			if ("cpp".equals(solver)) {
				if (all) {
					throw new Exception("all=true is only supported with solver=dopt");
				}
				LinkCoverCandidate candidate = buildCppCandidate(g, null);
				if (candidate == null) {
					throw new Exception("Failed to build link cover route");
				}
				return candidate.route;
			}

			if (!"dopt".equals(solver)) {
				throw new Exception("Unsupported solver: " + solver);
			}
			if (attempts <= 0) {
				throw new Exception("attempts must be positive");
			}

			// The D-opt solver enumerates candidate tours and then picks the
			// highest-scoring order-sensitive route.
			List<LinkCoverCandidate> candidates = buildDoptCandidates();
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

		private List<LinkCoverCandidate> buildDoptCandidates() throws Exception {
			Map<String, LinkCoverCandidate> candidates = new LinkedHashMap<String, LinkCoverCandidate>();

			// Build the undirected abstract graph used by the paper-inspired D-opt solver,
			// augment it once to become Eulerian, then sample candidate tours by
			// randomizing Hierholzer's next-edge choices.
			AbstractGraphContext context = buildAbstractGraphContext();
			MatchingSample sample = buildMatchingSample(context);
			AugmentedAbstractGraph augmentedGraph = buildAugmentedAbstractGraph(context, sample);
			Random random = new Random(from.hashCode() ^ g.edgeSet().size() ^ attempts);
			for (int i = 0; i < attempts; i++) {
				LinkCoverCandidate candidate = buildAbstractCandidate(context, augmentedGraph, random);
				if (candidate != null) {
					candidates.put(candidate.signature, candidate);
				}
			}
			return new ArrayList<LinkCoverCandidate>(candidates.values());
		}

		private LinkCoverCandidate buildCppCandidate(Graph<String, DefaultWeightedEdge> graph,
				Map<DefaultWeightedEdge, DefaultWeightedEdge> edgeMap) throws Exception {
			GraphPath<String, DefaultWeightedEdge> path = new ChinesePostman<String, DefaultWeightedEdge>().getCPPSolution(graph);
			if (path == null || path.getEdgeList().isEmpty()) {
				return null;
			}

			List<DefaultWeightedEdge> rotated = rotateCircuit(graph, path.getEdgeList());
			return buildDirectedCandidate(resolveOriginalEdges(rotated, edgeMap));
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
			}
			context.vertices = new ArrayList<String>(context.graph.vertexSet());
			Collections.sort(context.vertices);
			context.oddVertices = new ArrayList<String>();
			for (String vertex : context.vertices) {
				if ((context.graph.degreeOf(vertex) & 1) == 1) {
					context.oddVertices.add(vertex);
				}
			}

			// Precompute shortest paths between every odd-degree pair so the complete
			// graph can reuse the same distances and expanded edge sequences.
			for (int i = 0; i < context.oddVertices.size(); i++) {
				String source = context.oddVertices.get(i);
				for (int j = i + 1; j < context.oddVertices.size(); j++) {
					String target = context.oddVertices.get(j);
					GraphPath<String, DefaultWeightedEdge> path = DijkstraShortestPath.findPathBetween(context.graph, source, target);
					if (path == null || path.getEdgeList().isEmpty()) {
						throw new Exception("Failed to build odd-node shortest path");
					}
					ShortestPathInfo info = new ShortestPathInfo();
					info.source = source;
					info.target = target;
					info.distance = path.getWeight();
					info.edges = new ArrayList<DefaultWeightedEdge>(path.getEdgeList());
					context.shortestPathMap.put(getPairKey(source, target), info);
				}
			}
			return context;
		}

		private MatchingSample buildMatchingSample(AbstractGraphContext context) throws Exception {
			MatchingSample sample = new MatchingSample();
			if (context.oddVertices.isEmpty()) {
				return sample;
			}

			// Solve the odd-node pairing once to get the minimum augmentation needed
			// to turn the abstract graph into an Eulerian graph.
			CompleteGraphData completeGraph = buildOddCompleteGraph(context);
			MatchingAlgorithm.Matching<String, DefaultWeightedEdge> matching = new KolmogorovWeightedPerfectMatching<String, DefaultWeightedEdge>(
					completeGraph.graph, ObjectiveSense.MINIMIZE).getMatching();
			if (matching == null || !matching.isPerfect()) {
				throw new Exception("Failed to compute perfect matching");
			}
			for (DefaultWeightedEdge edge : matching.getEdges()) {
				ShortestPathInfo info = completeGraph.pathMap.get(edge);
				if (info == null) {
					throw new Exception("Failed to resolve matching path");
				}
				sample.paths.add(info);
				sample.cost += info.distance;
			}
			return sample;
		}

		private CompleteGraphData buildOddCompleteGraph(AbstractGraphContext context) throws Exception {
			CompleteGraphData data = new CompleteGraphData();
			data.graph = new SimpleWeightedGraph<String, DefaultWeightedEdge>(DefaultWeightedEdge.class);

			// The complete graph encodes the cost of pairing any two odd-degree vertices
			// by the shortest path distance between them in the abstract graph.
			for (String vertex : context.oddVertices) {
				data.graph.addVertex(vertex);
			}
			for (int i = 0; i < context.oddVertices.size(); i++) {
				String source = context.oddVertices.get(i);
				for (int j = i + 1; j < context.oddVertices.size(); j++) {
					String target = context.oddVertices.get(j);
					ShortestPathInfo info = context.shortestPathMap.get(getPairKey(source, target));
					if (info == null) {
						throw new Exception("Missing shortest path for odd nodes");
					}
					DefaultWeightedEdge edge = data.graph.addEdge(source, target);
					data.graph.setEdgeWeight(edge, info.distance);
					data.pathMap.put(edge, info);
				}
			}
			return data;
		}

		private String getPairKey(String source, String target) {
			return source.compareTo(target) <= 0 ? source + "|" + target : target + "|" + source;
		}

		private LinkCoverCandidate buildAbstractCandidate(AbstractGraphContext context, AugmentedAbstractGraph augmentedGraph, Random random)
				throws Exception {
			// Enumerate one Eulerian tour by running Hierholzer on the augmented graph
			// with randomized branch choices at vertices that still have multiple options.
			List<AbstractTraversalStep> steps = buildRandomEulerianCircuit(augmentedGraph, random);
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

		private AugmentedAbstractGraph buildAugmentedAbstractGraph(AbstractGraphContext context, MatchingSample sample) {
			AugmentedAbstractGraph augmented = new AugmentedAbstractGraph();
			augmented.graph = new WeightedMultigraph<String, DefaultWeightedEdge>(DefaultWeightedEdge.class);

			// Materialize the Eulerian graph by copying the abstract links and then
			// duplicating the shortest-path edges selected by the odd-node matching.
			for (String vertex : context.graph.vertexSet()) {
				augmented.graph.addVertex(vertex);
			}
			copyAbstractEdges(context.graph, context.linkMap, augmented.graph, augmented.linkMap);
			for (ShortestPathInfo path : sample.paths) {
				for (DefaultWeightedEdge edge : path.edges) {
					AbstractLink link = context.linkMap.get(edge);
					DefaultWeightedEdge copy = augmented.graph.addEdge(context.graph.getEdgeSource(edge),
							context.graph.getEdgeTarget(edge));
					augmented.graph.setEdgeWeight(copy, context.graph.getEdgeWeight(edge));
					augmented.linkMap.put(copy, link);
				}
			}
			return augmented;
		}

		private void copyAbstractEdges(WeightedMultigraph<String, DefaultWeightedEdge> sourceGraph,
				Map<DefaultWeightedEdge, AbstractLink> sourceMap, WeightedMultigraph<String, DefaultWeightedEdge> targetGraph,
				Map<DefaultWeightedEdge, AbstractLink> targetMap) {
			for (DefaultWeightedEdge edge : sourceGraph.edgeSet()) {
				DefaultWeightedEdge copy = targetGraph.addEdge(sourceGraph.getEdgeSource(edge), sourceGraph.getEdgeTarget(edge));
				targetGraph.setEdgeWeight(copy, sourceGraph.getEdgeWeight(edge));
				targetMap.put(copy, sourceMap.get(edge));
			}
		}

		private LinkCoverCandidate buildDirectedCandidate(List<DefaultWeightedEdge> edges) {
			LinkCoverCandidate candidate = new LinkCoverCandidate();
			double totalWeight = 0;
			for (DefaultWeightedEdge edge : edges) {
				totalWeight += g.getEdgeWeight(edge);
			}
			candidate.edges = edges;
			candidate.distance = totalWeight;
			candidate.signature = buildDirectedSignature(edges);
			candidate.dOptimality = Double.NEGATIVE_INFINITY;
			candidate.route = formatRoute(edges);
			return candidate;
		}

		private List<DefaultWeightedEdge> resolveOriginalEdges(List<DefaultWeightedEdge> edges,
				Map<DefaultWeightedEdge, DefaultWeightedEdge> edgeMap) throws Exception {
			List<DefaultWeightedEdge> result = new ArrayList<DefaultWeightedEdge>();
			for (DefaultWeightedEdge edge : edges) {
				DefaultWeightedEdge original = edgeMap != null ? edgeMap.get(edge) : edge;
				if (original == null) {
					throw new Exception("Failed to resolve link cover edge");
				}
				result.add(original);
			}
			return result;
		}

		private List<DefaultWeightedEdge> rotateCircuit(Graph<String, DefaultWeightedEdge> graph,
				List<DefaultWeightedEdge> edges) throws Exception {
			if (edges.isEmpty()) {
				throw new Exception("Empty link cover route");
			}
			int startIndex = -1;
			for (int i = 0; i < edges.size(); i++) {
				if (from.equals(graph.getEdgeSource(edges.get(i)))) {
					startIndex = i;
					break;
				}
			}
			if (startIndex == -1) {
				throw new Exception("Failed to align link cover route with start node");
			}
			List<DefaultWeightedEdge> rotated = new ArrayList<DefaultWeightedEdge>();
			for (int i = 0; i < edges.size(); i++) {
				rotated.add(edges.get((startIndex + i) % edges.size()));
			}
			if (!from.equals(graph.getEdgeSource(rotated.get(0)))
					|| !from.equals(graph.getEdgeTarget(rotated.get(rotated.size() - 1)))) {
				throw new Exception("Failed to rotate link cover route");
			}
			return rotated;
		}

		private List<AbstractTraversalStep> buildRandomEulerianCircuit(AugmentedAbstractGraph augmentedGraph, Random random)
				throws Exception {
			Map<String, List<DefaultWeightedEdge>> adjacency = new HashMap<String, List<DefaultWeightedEdge>>();

			// Prepare per-vertex edge lists and shuffle them once so Hierholzer explores
			// equally valid next edges in a random order on each trial.
			for (DefaultWeightedEdge edge : augmentedGraph.graph.edgeSet()) {
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
			for (List<DefaultWeightedEdge> list : adjacency.values()) {
				Collections.shuffle(list, random);
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
				throw new Exception("Failed to build Eulerian circuit");
			}
			if (!circuit.isEmpty() && (!from.equals(circuit.get(0).source)
					|| !from.equals(circuit.get(circuit.size() - 1).target))) {
				throw new Exception("Failed to align Hierholzer circuit with start node");
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

		private String buildDirectedSignature(List<DefaultWeightedEdge> edges) {
			StringBuilder sb = new StringBuilder();
			for (DefaultWeightedEdge edge : edges) {
				if (sb.length() > 0) {
					sb.append('|');
				}
				sb.append(g.getEdgeSource(edge)).append('>')
						.append(g.getEdgeTarget(edge)).append('#');
				try {
					sb.append(linkMap.get(edge).getString("_id"));
				} catch (Exception e) {
					sb.append(System.identityHashCode(edge));
				}
			}
			return sb.toString();
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

			// Build a route-ordered pose-graph-like Laplacian where every visit instance
			// becomes a node in time order, not just a unique map node.
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
						link = new JSONObject(link.toString());
						JSONObject properties = link.getJSONObject("properties");
						String edgeSource = g.getEdgeSource(edge);
						String edgeTarget = g.getEdgeTarget(edge);
						int sourceDoor = owner.getDoor(edgeSource);
						int targetDoor = owner.getDoor(edgeTarget);
						properties.put("sourceNode", edgeSource);
						properties.put("targetNode", edgeTarget);
						properties.put("sourceHeight", owner.getHeight(edgeSource));
						properties.put("targetHeight", owner.getHeight(edgeTarget));
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
					} catch (Exception e) {
						e.printStackTrace();
					}
					route.add(link);
				}
				JSONObject toNode = (JSONObject) owner.getNode(from).clone();
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
			}

			g = subgraph;
			linkMap = subLinkMap;
		}
	}

	private static class LinkCoverCandidate {
		private List<DefaultWeightedEdge> edges;
		private double distance;
		private double dOptimality;
		private String signature;
		private JSONArray route;
	}

	private static class AbstractLink {
		private String id;
		private JSONObject json;
		private String start;
		private String end;
		private double weight;
		private double length;
		private DefaultWeightedEdge forwardEdge;
		private DefaultWeightedEdge backwardEdge;
	}

	private static class ShortestPathInfo {
		private String source;
		private String target;
		private double distance;
		private List<DefaultWeightedEdge> edges = new ArrayList<DefaultWeightedEdge>();
	}

	private static class MatchingSample {
		private List<ShortestPathInfo> paths = new ArrayList<ShortestPathInfo>();
		private double cost;
	}

	private static class CompleteGraphData {
		private SimpleWeightedGraph<String, DefaultWeightedEdge> graph;
		private Map<DefaultWeightedEdge, ShortestPathInfo> pathMap = new HashMap<DefaultWeightedEdge, ShortestPathInfo>();
	}

	private static class AbstractGraphContext {
		private WeightedMultigraph<String, DefaultWeightedEdge> graph;
		private Map<DefaultWeightedEdge, AbstractLink> linkMap = new HashMap<DefaultWeightedEdge, AbstractLink>();
		private Map<String, ShortestPathInfo> shortestPathMap = new HashMap<String, ShortestPathInfo>();
		private List<String> vertices = new ArrayList<String>();
		private List<String> oddVertices = new ArrayList<String>();
	}

	private static class AugmentedAbstractGraph {
		private WeightedMultigraph<String, DefaultWeightedEdge> graph;
		private Map<DefaultWeightedEdge, AbstractLink> linkMap = new HashMap<DefaultWeightedEdge, AbstractLink>();
	}

	private static class AbstractTraversalStep {
		private DefaultWeightedEdge edge;
		private AbstractLink link;
		private String source;
		private String target;
	}
}
