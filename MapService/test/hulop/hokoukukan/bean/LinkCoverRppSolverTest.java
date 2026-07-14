package hulop.hokoukukan.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.WeightedMultigraph;
import org.junit.Test;

public class LinkCoverRppSolverTest {

	@Test
	public void connectsRequiredComponentsThroughOptionalEdge() throws Exception {
		GraphFixture fixture = new GraphFixture();
		fixture.add("required-ab", "a", "b", 1, true);
		fixture.add("optional-bc", "b", "c", 1, false);
		fixture.add("required-cd", "c", "d", 1, true);

		LinkCoverRppSolver.Result<String> result = fixture.solve("a", "d");

		assertEquals(3, result.edgeOrder.size());
		assertEquals(setOf("a", "d"), oddVertices(result));
		int optionalCopies = 0;
		for (DefaultWeightedEdge edge : result.edgeOrder) {
			if ("optional-bc".equals(result.edgeData.get(edge).id)) {
				optionalCopies++;
			}
		}
		assertEquals(1, optionalCopies);
	}

	@Test
	public void correctsParityAfterConnectingRequiredComponents() throws Exception {
		GraphFixture fixture = new GraphFixture();
		fixture.add("required-ab", "a", "b", 1, true);
		fixture.add("optional-bc", "b", "c", 1, false);
		fixture.add("required-cd", "c", "d", 1, true);

		LinkCoverRppSolver.Result<String> result = fixture.solve("a", "a");

		assertEquals(6, result.edgeOrder.size());
		assertTrue(oddVertices(result).isEmpty());
	}

	@Test
	public void usesShortestAllowedPathWhenNoRequiredEdgesRemain() throws Exception {
		GraphFixture fixture = new GraphFixture();
		fixture.add("ab", "a", "b", 1, false);
		fixture.add("bc", "b", "c", 1, false);
		fixture.add("ac", "a", "c", 5, false);

		LinkCoverRppSolver.Result<String> result = fixture.solve("a", "c");

		assertEquals(2, result.edgeOrder.size());
		assertEquals(setOf("a", "c"), oddVertices(result));
		for (DefaultWeightedEdge edge : result.edgeOrder) {
			assertFalse("ac".equals(result.edgeData.get(edge).id));
		}
	}

	@Test
	public void returnsEmptyAugmentationWhenCompleteAtDestination() throws Exception {
		GraphFixture fixture = new GraphFixture();
		fixture.add("ab", "a", "b", 1, false);

		LinkCoverRppSolver.Result<String> result = fixture.solve("a", "a");

		assertTrue(result.edgeOrder.isEmpty());
		assertTrue(oddVertices(result).isEmpty());
	}

	@Test(expected = Exception.class)
	public void rejectsUnreachableDestination() throws Exception {
		GraphFixture fixture = new GraphFixture();
		fixture.add("ab", "a", "b", 1, true);
		fixture.add("cd", "c", "d", 1, true);
		fixture.solve("a", "d");
	}

	private Set<String> oddVertices(LinkCoverRppSolver.Result<String> result) {
		Set<String> odd = new LinkedHashSet<String>();
		for (String vertex : result.graph.vertexSet()) {
			if ((result.graph.degreeOf(vertex) & 1) == 1) {
				odd.add(vertex);
			}
		}
		return odd;
	}

	private Set<String> setOf(String first, String second) {
		Set<String> result = new LinkedHashSet<String>();
		result.add(first);
		result.add(second);
		return result;
	}

	private static class GraphFixture {
		private final WeightedMultigraph<String, DefaultWeightedEdge> graph = new WeightedMultigraph<String, DefaultWeightedEdge>(
				DefaultWeightedEdge.class);
		private final Map<DefaultWeightedEdge, LinkCoverRppSolver.EdgeData<String>> data = new LinkedHashMap<DefaultWeightedEdge, LinkCoverRppSolver.EdgeData<String>>();

		void add(String id, String source, String target, double weight, boolean required) {
			graph.addVertex(source);
			graph.addVertex(target);
			DefaultWeightedEdge edge = graph.addEdge(source, target);
			graph.setEdgeWeight(edge, weight);
			data.put(edge, new LinkCoverRppSolver.EdgeData<String>(id, id, required));
		}

		LinkCoverRppSolver.Result<String> solve(String from, String to) throws Exception {
			return new LinkCoverRppSolver<String>(graph, data, from, to).solve();
		}
	}
}
