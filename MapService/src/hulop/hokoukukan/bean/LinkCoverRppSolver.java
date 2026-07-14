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
import java.util.Set;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.MatchingAlgorithm;
import org.jgrapht.alg.interfaces.SpanningTreeAlgorithm;
import org.jgrapht.alg.matching.blossom.v5.KolmogorovWeightedPerfectMatching;
import org.jgrapht.alg.matching.blossom.v5.ObjectiveSense;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.alg.spanning.KruskalMinimumSpanningTree;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.jgrapht.graph.WeightedMultigraph;

/**
 * Builds one open Rural Postman augmentation from required and optional edges.
 *
 * Required-component connection follows the MST construction described by
 * G. N. Frederickson, "Approximation Algorithms for Some Postman Problems"
 * (JACM, 1979). This implementation deliberately constructs one metric-closure
 * MST and does not claim Frederickson's approximation guarantee. Odd-degree
 * correction follows J. Edmonds and E. L. Johnson, "Matching, Euler Tours and
 * the Chinese Postman" (Mathematical Programming, 1973). For distinct endpoints
 * it directly corrects O symmetric-difference {from,to}; this is equivalent to
 * the virtual-edge open-route construction described by W. Gao et al., "Active
 * Loop Closure for OSM-guided Robotic Mapping in Large-Scale Urban
 * Environments" (2024).
 */
final class LinkCoverRppSolver<T> {

	static final class EdgeData<T> {
		final String id;
		final T payload;
		final boolean required;

		EdgeData(String id, T payload, boolean required) {
			this.id = id;
			this.payload = payload;
			this.required = required;
		}
	}

	static final class Result<T> {
		final WeightedMultigraph<String, DefaultWeightedEdge> graph = new WeightedMultigraph<String, DefaultWeightedEdge>(
				DefaultWeightedEdge.class);
		final Map<DefaultWeightedEdge, EdgeData<T>> edgeData = new LinkedHashMap<DefaultWeightedEdge, EdgeData<T>>();
		final List<DefaultWeightedEdge> edgeOrder = new ArrayList<DefaultWeightedEdge>();
	}

	private static final double WEIGHT_EPS = 1.0e-9;

	private final WeightedMultigraph<String, DefaultWeightedEdge> allowedGraph;
	private final Map<DefaultWeightedEdge, EdgeData<T>> allowedEdgeData;
	private final String from;
	private final String to;

	LinkCoverRppSolver(WeightedMultigraph<String, DefaultWeightedEdge> allowedGraph,
			Map<DefaultWeightedEdge, EdgeData<T>> allowedEdgeData, String from, String to) {
		this.allowedGraph = allowedGraph;
		this.allowedEdgeData = allowedEdgeData;
		this.from = from;
		this.to = to;
	}

	Result<T> solve() throws Exception {
		if (!allowedGraph.containsVertex(from) || !allowedGraph.containsVertex(to)) {
			throw new Exception("Start or end node is not connected to an allowed link");
		}

		Result<T> result = new Result<T>();
		for (String vertex : allowedGraph.vertexSet()) {
			result.graph.addVertex(vertex);
		}

		List<DefaultWeightedEdge> requiredEdges = new ArrayList<DefaultWeightedEdge>();
		for (DefaultWeightedEdge edge : allowedGraph.edgeSet()) {
			EdgeData<T> data = allowedEdgeData.get(edge);
			if (data != null && data.required) {
				requiredEdges.add(edge);
			}
		}
		sortEdges(requiredEdges);

		if (requiredEdges.isEmpty()) {
			if (!from.equals(to)) {
				PathData path = findShortestPath(from, to);
				if (path == null) {
					throw new Exception("End node is not reachable through allowed links");
				}
				addPath(result, path);
			}
			validateOddVertices(result);
			return result;
		}

		for (DefaultWeightedEdge edge : requiredEdges) {
			copyEdge(result, edge);
		}

		List<Set<String>> components = buildRequiredComponents(requiredEdges);
		addEndpointComponent(components, from);
		addEndpointComponent(components, to);
		sortComponents(components);
		connectComponentsWithMst(result, components);
		correctParity(result);
		validateOddVertices(result);
		return result;
	}

	private List<Set<String>> buildRequiredComponents(List<DefaultWeightedEdge> requiredEdges) {
		Map<String, List<DefaultWeightedEdge>> adjacency = new HashMap<String, List<DefaultWeightedEdge>>();
		for (DefaultWeightedEdge edge : requiredEdges) {
			addAdjacent(adjacency, allowedGraph.getEdgeSource(edge), edge);
			addAdjacent(adjacency, allowedGraph.getEdgeTarget(edge), edge);
		}

		List<String> vertices = new ArrayList<String>(adjacency.keySet());
		Collections.sort(vertices);
		Set<String> visited = new LinkedHashSet<String>();
		List<Set<String>> components = new ArrayList<Set<String>>();
		for (String start : vertices) {
			if (!visited.add(start)) {
				continue;
			}
			Set<String> component = new LinkedHashSet<String>();
			List<String> open = new ArrayList<String>();
			open.add(start);
			for (int i = 0; i < open.size(); i++) {
				String node = open.get(i);
				component.add(node);
				List<DefaultWeightedEdge> edges = adjacency.get(node);
				if (edges == null) {
					continue;
				}
				for (DefaultWeightedEdge edge : edges) {
					String next = opposite(edge, node);
					if (visited.add(next)) {
						open.add(next);
					}
				}
			}
			components.add(component);
		}
		return components;
	}

	private void addAdjacent(Map<String, List<DefaultWeightedEdge>> adjacency, String node, DefaultWeightedEdge edge) {
		List<DefaultWeightedEdge> edges = adjacency.get(node);
		if (edges == null) {
			adjacency.put(node, edges = new ArrayList<DefaultWeightedEdge>());
		}
		edges.add(edge);
	}

	private void addEndpointComponent(List<Set<String>> components, String endpoint) {
		for (Set<String> component : components) {
			if (component.contains(endpoint)) {
				return;
			}
		}
		Set<String> singleton = new LinkedHashSet<String>();
		singleton.add(endpoint);
		components.add(singleton);
	}

	private void sortComponents(List<Set<String>> components) {
		Collections.sort(components, new Comparator<Set<String>>() {
			@Override
			public int compare(Set<String> a, Set<String> b) {
				return componentKey(a).compareTo(componentKey(b));
			}
		});
	}

	private String componentKey(Set<String> component) {
		List<String> nodes = new ArrayList<String>(component);
		Collections.sort(nodes);
		return nodes.toString();
	}

	private void connectComponentsWithMst(Result<T> result, List<Set<String>> components) throws Exception {
		if (components.size() <= 1) {
			return;
		}

		// Frederickson's RPP framework connects required components through a
		// minimum spanning tree over their shortest-path metric closure. We use one
		// such tree as a practical construction, without claiming the paper's ratio.
		SimpleWeightedGraph<Integer, DefaultWeightedEdge> closure = new SimpleWeightedGraph<Integer, DefaultWeightedEdge>(
				DefaultWeightedEdge.class);
		Map<DefaultWeightedEdge, PathData> paths = new HashMap<DefaultWeightedEdge, PathData>();
		for (int i = 0; i < components.size(); i++) {
			closure.addVertex(i);
		}
		for (int i = 0; i < components.size(); i++) {
			for (int j = i + 1; j < components.size(); j++) {
				PathData path = findShortestPath(components.get(i), components.get(j));
				if (path == null) {
					continue;
				}
				DefaultWeightedEdge edge = closure.addEdge(i, j);
				closure.setEdgeWeight(edge, path.weight);
				paths.put(edge, path);
			}
		}

		SpanningTreeAlgorithm.SpanningTree<DefaultWeightedEdge> tree = new KruskalMinimumSpanningTree<Integer, DefaultWeightedEdge>(
				closure).getSpanningTree();
		if (tree.getEdges().size() != components.size() - 1) {
			throw new Exception("Required links and endpoints are not connected through allowed links");
		}
		List<DefaultWeightedEdge> treeEdges = new ArrayList<DefaultWeightedEdge>(tree.getEdges());
		Collections.sort(treeEdges, new Comparator<DefaultWeightedEdge>() {
			@Override
			public int compare(DefaultWeightedEdge a, DefaultWeightedEdge b) {
				return closureEdgeKey(closure, a).compareTo(closureEdgeKey(closure, b));
			}
		});
		for (DefaultWeightedEdge edge : treeEdges) {
			addPath(result, paths.get(edge));
		}
	}

	private String closureEdgeKey(SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph, DefaultWeightedEdge edge) {
		Integer source = graph.getEdgeSource(edge);
		Integer target = graph.getEdgeTarget(edge);
		return Math.min(source, target) + "|" + Math.max(source, target);
	}

	private void correctParity(Result<T> result) throws Exception {
		// Edmonds and Johnson reduce CPP parity correction to a minimum-weight
		// perfect matching. Toggling from and to directly yields an open trail;
		// Gao et al. describe the equivalent construction using a virtual edge.
		Set<String> correction = new LinkedHashSet<String>();
		for (String vertex : result.graph.vertexSet()) {
			if ((result.graph.degreeOf(vertex) & 1) == 1) {
				correction.add(vertex);
			}
		}
		toggle(correction, from);
		toggle(correction, to);
		if (correction.isEmpty()) {
			return;
		}
		if ((correction.size() & 1) == 1) {
			throw new Exception("Parity correction requires an even number of vertices");
		}

		List<String> vertices = new ArrayList<String>(correction);
		Collections.sort(vertices);
		SimpleWeightedGraph<String, DefaultWeightedEdge> complete = new SimpleWeightedGraph<String, DefaultWeightedEdge>(
				DefaultWeightedEdge.class);
		Map<DefaultWeightedEdge, PathData> paths = new HashMap<DefaultWeightedEdge, PathData>();
		for (String vertex : vertices) {
			complete.addVertex(vertex);
		}
		for (int i = 0; i < vertices.size(); i++) {
			for (int j = i + 1; j < vertices.size(); j++) {
				PathData path = findShortestPath(vertices.get(i), vertices.get(j));
				if (path == null) {
					throw new Exception("Failed to connect parity-correction vertices");
				}
				DefaultWeightedEdge edge = complete.addEdge(vertices.get(i), vertices.get(j));
				complete.setEdgeWeight(edge, path.weight);
				paths.put(edge, path);
			}
		}

		MatchingAlgorithm.Matching<String, DefaultWeightedEdge> matching = new KolmogorovWeightedPerfectMatching<String, DefaultWeightedEdge>(
				complete, ObjectiveSense.MINIMIZE).getMatching();
		if (matching == null || !matching.isPerfect()) {
			throw new Exception("Failed to compute parity-correction matching");
		}
		List<DefaultWeightedEdge> matched = new ArrayList<DefaultWeightedEdge>(matching.getEdges());
		Collections.sort(matched, new Comparator<DefaultWeightedEdge>() {
			@Override
			public int compare(DefaultWeightedEdge a, DefaultWeightedEdge b) {
				return graphEdgeKey(complete, a).compareTo(graphEdgeKey(complete, b));
			}
		});
		for (DefaultWeightedEdge edge : matched) {
			addPath(result, paths.get(edge));
		}
	}

	private String graphEdgeKey(SimpleWeightedGraph<String, DefaultWeightedEdge> graph, DefaultWeightedEdge edge) {
		String source = graph.getEdgeSource(edge);
		String target = graph.getEdgeTarget(edge);
		return source.compareTo(target) <= 0 ? source + "|" + target : target + "|" + source;
	}

	private void toggle(Set<String> vertices, String vertex) {
		if (!vertices.remove(vertex)) {
			vertices.add(vertex);
		}
	}

	private PathData findShortestPath(String source, String target) {
		GraphPath<String, DefaultWeightedEdge> path = DijkstraShortestPath.findPathBetween(allowedGraph, source, target);
		return path != null ? new PathData(path) : null;
	}

	private PathData findShortestPath(Set<String> sources, Set<String> targets) {
		List<String> sortedSources = new ArrayList<String>(sources);
		List<String> sortedTargets = new ArrayList<String>(targets);
		Collections.sort(sortedSources);
		Collections.sort(sortedTargets);
		PathData best = null;
		for (String source : sortedSources) {
			for (String target : sortedTargets) {
				PathData candidate = findShortestPath(source, target);
				if (candidate != null && (best == null || candidate.compareTo(best) < 0)) {
					best = candidate;
				}
			}
		}
		return best;
	}

	private void addPath(Result<T> result, PathData path) {
		for (DefaultWeightedEdge edge : path.edges) {
			copyEdge(result, edge);
		}
	}

	private void copyEdge(Result<T> result, DefaultWeightedEdge sourceEdge) {
		String source = allowedGraph.getEdgeSource(sourceEdge);
		String target = allowedGraph.getEdgeTarget(sourceEdge);
		result.graph.addVertex(source);
		result.graph.addVertex(target);
		DefaultWeightedEdge copy = result.graph.addEdge(source, target);
		result.graph.setEdgeWeight(copy, allowedGraph.getEdgeWeight(sourceEdge));
		result.edgeData.put(copy, allowedEdgeData.get(sourceEdge));
		result.edgeOrder.add(copy);
	}

	private void sortEdges(List<DefaultWeightedEdge> edges) {
		Collections.sort(edges, new Comparator<DefaultWeightedEdge>() {
			@Override
			public int compare(DefaultWeightedEdge a, DefaultWeightedEdge b) {
				return allowedEdgeKey(a).compareTo(allowedEdgeKey(b));
			}
		});
	}

	private String allowedEdgeKey(DefaultWeightedEdge edge) {
		EdgeData<T> data = allowedEdgeData.get(edge);
		String source = allowedGraph.getEdgeSource(edge);
		String target = allowedGraph.getEdgeTarget(edge);
		String endpoints = source.compareTo(target) <= 0 ? source + "|" + target : target + "|" + source;
		return (data != null ? data.id : "") + "|" + endpoints;
	}

	private String opposite(DefaultWeightedEdge edge, String node) {
		String source = allowedGraph.getEdgeSource(edge);
		return node.equals(source) ? allowedGraph.getEdgeTarget(edge) : source;
	}

	private void validateOddVertices(Result<T> result) throws Exception {
		Set<String> odd = new LinkedHashSet<String>();
		for (String vertex : result.graph.vertexSet()) {
			if ((result.graph.degreeOf(vertex) & 1) == 1) {
				odd.add(vertex);
			}
		}
		Set<String> expected = new LinkedHashSet<String>();
		if (!from.equals(to)) {
			expected.add(from);
			expected.add(to);
		}
		if (!odd.equals(expected)) {
			throw new Exception("Failed to construct an Eulerian path from start to end");
		}
	}

	private final class PathData implements Comparable<PathData> {
		final List<DefaultWeightedEdge> edges;
		final double weight;
		final String signature;

		PathData(GraphPath<String, DefaultWeightedEdge> path) {
			this.edges = new ArrayList<DefaultWeightedEdge>(path.getEdgeList());
			this.weight = path.getWeight();
			StringBuilder sb = new StringBuilder();
			for (DefaultWeightedEdge edge : edges) {
				if (sb.length() > 0) {
					sb.append('|');
				}
				sb.append(allowedEdgeKey(edge));
			}
			this.signature = sb.toString();
		}

		@Override
		public int compareTo(PathData other) {
			int cmp = Double.compare(weight, other.weight);
			if (Math.abs(weight - other.weight) <= WEIGHT_EPS) {
				cmp = 0;
			}
			return cmp != 0 ? cmp : signature.compareTo(other.signature);
		}
	}
}
