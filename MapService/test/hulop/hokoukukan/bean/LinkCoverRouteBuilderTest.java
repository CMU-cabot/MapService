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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;
import org.junit.Test;

public class LinkCoverRouteBuilderTest {

	private static final String CURRENT_LINK = "current";
	private static final String HISTORY_LINK = "history";
	private static final String REQUIRED_LINK = "required";
	private static final String BLOCKED_LINK = "blocked";
	private static final String START = "a";
	private static final String END = "b";
	private static final String HISTORY_START = "h";
	private static final String TARGET = "t";
	private static final String BLOCKED_TARGET = "z";
	private static final String MIDPOINT = "latlng:0:0.001:1";

	@Test
	public void prependsStartSideEgressOutsideCppRoute() throws Exception {
		Fixture fixture = new Fixture(START);

		JSONArray route = fixture.buildRoute("cpp", fixture.validState(), TARGET);

		assertEgressRoute(route, RouteSearchBean.tempLink1ID, START);
		JSONArray coordinates = route.getJSONObject(1).getJSONObject("geometry").getJSONArray("coordinates");
		assertCoordinate(coordinates.getJSONArray(0), 0.0, 0.0);
		assertCoordinate(coordinates.getJSONArray(coordinates.length() - 1), 0.001, 0.0);
	}

	@Test
	public void prependsEndSideEgressOutsideCppRoute() throws Exception {
		Fixture fixture = new Fixture(END);

		JSONArray route = fixture.buildRoute("cpp", fixture.validState(), TARGET);

		assertEgressRoute(route, RouteSearchBean.tempLink2ID, END);
		JSONArray coordinates = route.getJSONObject(1).getJSONObject("geometry").getJSONArray("coordinates");
		assertCoordinate(coordinates.getJSONArray(0), 0.001, 0.0);
		assertCoordinate(coordinates.getJSONArray(coordinates.length() - 1), 0.002, 0.0);
	}

	@Test
	public void prependsEgressToDoptBestRoute() throws Exception {
		Fixture fixture = new Fixture(START);

		JSONArray route = fixture.buildRoute("dopt", fixture.validState(), TARGET);

		assertEgressRoute(route, RouteSearchBean.tempLink1ID, START);
	}

	@Test
	public void prependsEgressExactlyOnceToEveryAllResponseRoute() throws Exception {
		Fixture fixture = new Fixture(START);

		JSONObject response = fixture.buildAll(fixture.validState(), TARGET);

		assertEgressRoute(response.getJSONArray("best_route"), RouteSearchBean.tempLink1ID, START);
		JSONArray candidates = response.getJSONArray("routes");
		assertFalse(candidates.length() == 0);
		for (int i = 0; i < candidates.length(); i++) {
			JSONObject candidate = candidates.getJSONObject(i);
			assertEgressRoute(candidate.getJSONArray("route"), RouteSearchBean.tempLink1ID, START);
			// The fixed retreat is common to every candidate and must remain outside
			// Gao et al.'s D-optimality comparison. A single 10 m RPP edge scores 0.
			assertEquals(0.0, candidate.getDouble("d_optimality"), 1.0e-9);
		}
	}

	@Test
	public void convertsZeroDistanceSuffixToEgressOnlyRoute() throws Exception {
		Fixture fixture = new Fixture(START);
		LinkCoverCoverageState state = fixture.stateWithCoveredRequired();

		JSONArray route = fixture.buildRoute("dopt", state, START);

		assertEquals(3, route.length());
		assertEquals(RouteSearchBean.tempNodeID, route.getJSONObject(0).getString("_id"));
		assertEquals(RouteSearchBean.tempLink1ID, route.getJSONObject(1).getString("_id"));
		assertEquals(START, route.getJSONObject(2).getString("_id"));
		assertEquals(1, countLinks(route, RouteSearchBean.tempLink1ID));
	}

	@Test
	public void convertsZeroDistanceSuffixToSingleAllResponseCandidate() throws Exception {
		Fixture fixture = new Fixture(START);

		JSONObject response = fixture.buildAll(fixture.stateWithCoveredRequired(), START);

		assertEquals(1, response.getJSONArray("routes").length());
		assertEquals(0.0, response.getJSONArray("routes").getJSONObject(0).getDouble("d_optimality"), 1.0e-9);
		assertEquals(3, response.getJSONArray("best_route").length());
		assertEquals(1, countLinks(response.getJSONArray("best_route"), RouteSearchBean.tempLink1ID));
	}

	@Test
	public void rejectsAllWithCppBeforeReturningZeroDistanceSuffix() throws Exception {
		Fixture fixture = new Fixture(START);

		fixture.assertBuildFails("all=true is only supported with solver=dopt", fixture.stateWithCoveredRequired(),
				START, "cpp", true);
	}

	@Test
	public void rejectsUnsupportedSolverBeforeReturningZeroDistanceSuffix() throws Exception {
		Fixture fixture = new Fixture(START);

		fixture.assertBuildFails("Unsupported solver: unknown", fixture.stateWithCoveredRequired(), START, "unknown",
				false);
	}

	@Test
	public void requiresExplicitDestinationOnExcludedCurrentLink() throws Exception {
		Fixture fixture = new Fixture(START);

		fixture.assertBuildFails("to is required when current position is on an excluded link", fixture.validState(),
				null);
	}

	@Test
	public void requiresHistoryOnExcludedCurrentLink() throws Exception {
		Fixture fixture = new Fixture(START);
		LinkCoverCoverageState state = LinkCoverCoverageState.parse("{"
				+ "\"covered_link_ids\":[],"
				+ "\"excluded_link_ids\":[\"" + CURRENT_LINK + "\"]}");

		fixture.assertBuildFails("traversal_history is required to leave the current excluded link", state, TARGET);
	}

	@Test
	public void rejectsHistoryThatDoesNotIdentifyCurrentLinkEntry() throws Exception {
		Fixture fixture = new Fixture(START);
		LinkCoverCoverageState state = LinkCoverCoverageState.parse("{"
				+ "\"covered_link_ids\":[\"" + BLOCKED_LINK + "\"],"
				+ "\"excluded_link_ids\":[\"" + CURRENT_LINK + "\",\"" + BLOCKED_LINK + "\"],"
				+ "\"traversal_history\":[{"
				+ "\"link_id\":\"" + BLOCKED_LINK + "\","
				+ "\"source_node_id\":\"" + START + "\","
				+ "\"target_node_id\":\"" + BLOCKED_TARGET + "\"}]}");

		fixture.assertBuildFails("traversal_history target does not identify the entry endpoint", state, TARGET);
	}

	@Test
	public void rejectsDestinationUnreachableWithoutExcludedLinks() throws Exception {
		Fixture fixture = new Fixture(START);
		LinkCoverCoverageState state = fixture.stateExcludingRequired();

		fixture.assertBuildFails("End node is not reachable through allowed links", state, TARGET);
	}

	@Test
	public void startsNormallyWhenProjectionRoundsToExcludedLinkEndpoint() throws Exception {
		Fixture fixture = new Fixture(START);
		LinkCoverCoverageState state = LinkCoverCoverageState.parse("{"
				+ "\"covered_link_ids\":[\"" + HISTORY_LINK + "\"],"
				+ "\"excluded_link_ids\":[\"" + CURRENT_LINK + "\",\"" + BLOCKED_LINK + "\"]}");

		JSONArray route = fixture.buildRouteFrom("latlng:0:0:1", "cpp", state, TARGET);

		assertEquals(START, route.getJSONObject(0).getString("_id"));
		assertEquals(REQUIRED_LINK, route.getJSONObject(1).getString("_id"));
		assertEquals(0, countLinks(route, RouteSearchBean.tempLink1ID));
		assertEquals(0, countLinks(route, RouteSearchBean.tempLink2ID));
	}

	private void assertEgressRoute(JSONArray route, String egressLinkId, String entryNode) throws Exception {
		assertEquals(RouteSearchBean.tempNodeID, route.getJSONObject(0).getString("_id"));
		JSONObject egress = route.getJSONObject(1);
		assertEquals(egressLinkId, egress.getString("_id"));
		JSONObject properties = egress.getJSONObject("properties");
		assertEquals(CURRENT_LINK, properties.getString("coverage_link_id"));
		assertEquals(RouteSearchBean.tempNodeID, properties.getString("sourceNode"));
		assertEquals(entryNode, properties.getString("targetNode"));
		assertEquals(1, countLinks(route, egressLinkId));
		assertEquals(0, countLinks(route, CURRENT_LINK));
		assertEquals(0, countCoverageLinks(route, CURRENT_LINK, 2));
		assertEquals(0, countCoverageLinks(route, BLOCKED_LINK, 0));
		assertEquals(TARGET, route.getJSONObject(route.length() - 1).getString("_id"));
	}

	private int countLinks(JSONArray route, String linkId) throws Exception {
		int count = 0;
		for (int i = 0; i < route.length(); i++) {
			JSONObject feature = route.getJSONObject(i);
			if (linkId.equals(feature.optString("_id"))) {
				count++;
			}
		}
		return count;
	}

	private int countCoverageLinks(JSONArray route, String coverageLinkId, int startIndex) throws Exception {
		int count = 0;
		for (int i = startIndex; i < route.length(); i++) {
			JSONObject feature = route.getJSONObject(i);
			JSONObject properties = feature.optJSONObject("properties");
			if (properties != null && coverageLinkId.equals(properties.optString("coverage_link_id"))) {
				count++;
			}
		}
		return count;
	}

	private void assertCoordinate(JSONArray coordinate, double lng, double lat) throws Exception {
		assertEquals(lng, coordinate.getDouble(0), 1.0e-12);
		assertEquals(lat, coordinate.getDouble(1), 1.0e-12);
	}

	private static final class Fixture {
		private final JSONObject nodes = new JSONObject();
		private final JSONArray features = new JSONArray();
		private final String entryNode;
		private final TestRouteSearchBean owner;

		Fixture(String entryNode) throws Exception {
			this.entryNode = entryNode;
			addNode(START, 0.0, 0.0);
			addNode(END, 0.002, 0.0);
			addNode(HISTORY_START, START.equals(entryNode) ? -0.001 : 0.003, 0.0);
			addNode(TARGET, START.equals(entryNode) ? 0.0 : 0.002, 0.001);
			addNode(BLOCKED_TARGET, START.equals(entryNode) ? 0.0 : 0.002, -0.001);

			features.add(link(CURRENT_LINK, START, END));
			features.add(link(HISTORY_LINK, HISTORY_START, entryNode));
			features.add(link(REQUIRED_LINK, entryNode, TARGET));
			features.add(link(BLOCKED_LINK, entryNode, BLOCKED_TARGET));
			owner = new TestRouteSearchBean(nodes, CURRENT_LINK);
		}

		LinkCoverCoverageState validState() throws Exception {
			return LinkCoverCoverageState.parse("{"
					+ "\"covered_link_ids\":[\"" + HISTORY_LINK + "\"],"
					+ "\"excluded_link_ids\":[\"" + CURRENT_LINK + "\",\"" + BLOCKED_LINK + "\"],"
					+ "\"traversal_history\":[{"
					+ "\"link_id\":\"" + HISTORY_LINK + "\","
					+ "\"source_node_id\":\"" + HISTORY_START + "\","
					+ "\"target_node_id\":\"" + entryNode + "\"}]}");
		}

		LinkCoverCoverageState stateWithCoveredRequired() throws Exception {
			return LinkCoverCoverageState.parse("{"
					+ "\"covered_link_ids\":[\"" + HISTORY_LINK + "\",\"" + REQUIRED_LINK + "\"],"
					+ "\"excluded_link_ids\":[\"" + CURRENT_LINK + "\",\"" + BLOCKED_LINK + "\"],"
					+ "\"traversal_history\":[{"
					+ "\"link_id\":\"" + HISTORY_LINK + "\","
					+ "\"source_node_id\":\"" + HISTORY_START + "\","
					+ "\"target_node_id\":\"" + entryNode + "\"}]}");
		}

		LinkCoverCoverageState stateExcludingRequired() throws Exception {
			return LinkCoverCoverageState.parse("{"
					+ "\"covered_link_ids\":[\"" + HISTORY_LINK + "\"],"
					+ "\"excluded_link_ids\":[\"" + CURRENT_LINK + "\",\"" + REQUIRED_LINK + "\",\""
					+ BLOCKED_LINK + "\"],"
					+ "\"traversal_history\":[{"
					+ "\"link_id\":\"" + HISTORY_LINK + "\","
					+ "\"source_node_id\":\"" + HISTORY_START + "\","
					+ "\"target_node_id\":\"" + entryNode + "\"}]}");
		}

		JSONArray buildRoute(String solver, LinkCoverCoverageState state, String to) throws Exception {
			return buildRouteFrom(MIDPOINT, solver, state, to);
		}

		JSONArray buildRouteFrom(String from, String solver, LinkCoverCoverageState state, String to) throws Exception {
			Object result = build(from, to, state, solver, false);
			assertTrue(result instanceof JSONArray);
			return (JSONArray) result;
		}

		JSONObject buildAll(LinkCoverCoverageState state, String to) throws Exception {
			Object result = build(MIDPOINT, to, state, "dopt", true);
			assertTrue(result instanceof JSONObject);
			return (JSONObject) result;
		}

		void assertBuildFails(String messagePart, LinkCoverCoverageState state, String to) throws Exception {
			assertBuildFails(messagePart, state, to, "cpp", false);
		}

		void assertBuildFails(String messagePart, LinkCoverCoverageState state, String to, String solver, boolean all)
				throws Exception {
			try {
				build(MIDPOINT, to, state, solver, all);
				fail("Expected build to fail with: " + messagePart);
			} catch (Exception e) {
				assertTrue("Unexpected error: " + e.getMessage(), e.getMessage() != null
						&& e.getMessage().contains(messagePart));
			}
		}

		private Object build(String from, String to, LinkCoverCoverageState state, String solver, boolean all)
				throws Exception {
			return new LinkCoverRouteBuilder(owner, nodes, features, Collections.<String>emptySet()).build(from, to,
					state, new HashMap<String, String>(), false, solver, all, 8);
		}

		private void addNode(String id, double lng, double lat) throws Exception {
			nodes.put(id, node(id, lng, lat));
		}

		private JSONObject link(String id, String start, String end) throws Exception {
			JSONArray startCoordinate = nodes.getJSONObject(start).getJSONObject("geometry").getJSONArray("coordinates");
			JSONArray endCoordinate = nodes.getJSONObject(end).getJSONObject("geometry").getJSONArray("coordinates");
			JSONObject properties = new JSONObject();
			properties.put("link_id", id);
			properties.put("start_id", start);
			properties.put("end_id", end);
			properties.put("distance", 10.0);
			JSONObject geometry = new JSONObject();
			geometry.put("type", "LineString");
			geometry.put("coordinates", new JSONArray()
					.put(new JSONArray().put(startCoordinate.getDouble(0)).put(startCoordinate.getDouble(1)))
					.put(new JSONArray().put(endCoordinate.getDouble(0)).put(endCoordinate.getDouble(1))));
			return new JSONObject().put("_id", id).put("type", "Feature")
					.put("geometry", geometry).put("properties", properties);
		}
	}

	private static JSONObject node(String id, double lng, double lat) throws Exception {
		JSONObject properties = new JSONObject();
		properties.put("node_id", id);
		properties.put("floor", 1.0);
		JSONObject geometry = new JSONObject();
		geometry.put("type", "Point");
		geometry.put("coordinates", new JSONArray().put(lng).put(lat));
		return new JSONObject().put("_id", id).put("type", "Feature")
				.put("geometry", geometry).put("properties", properties);
	}

	private static final class TestRouteSearchBean extends RouteSearchBean {
		private final JSONObject nodes;
		private final String nearestLinkId;

		TestRouteSearchBean(JSONObject nodes, String nearestLinkId) {
			this.nodes = nodes;
			this.nearestLinkId = nearestLinkId;
		}

		@Override
		String findNearestLink(JSONObject fromPoint) {
			return nearestLinkId;
		}

		@Override
		boolean isNode(String id) {
			return RouteSearchBean.tempNodeID.equals(id) ? getTempNode() != null : nodes.has(id);
		}

		@Override
		JSONObject getNode(String id) throws JSONException {
			return RouteSearchBean.tempNodeID.equals(id) ? getTempNode() : nodes.getJSONObject(id);
		}

		@Override
		double getHeight(String node) throws JSONException {
			return getNode(node).getJSONObject("properties").getDouble("floor");
		}

		@Override
		int getDoor(String node) {
			return 100;
		}

		@Override
		double adjustAccWeight(JSONObject properties, double weight, Map<String, String> conditions) {
			return weight;
		}

		@Override
		JSONArray addStartArea(JSONArray route, JSONObject startPos) {
			return route;
		}
	}
}
