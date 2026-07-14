package hulop.hokoukukan.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class LinkCoverCoverageStateTest {

	@Test
	public void parsesMissingStateAsEmpty() throws Exception {
		LinkCoverCoverageState state = LinkCoverCoverageState.parse(null);

		assertTrue(state.getCoveredLinkIds().isEmpty());
		assertTrue(state.getExcludedLinkIds().isEmpty());
		assertTrue(state.getTraversalHistory().isEmpty());
	}

	@Test
	public void parsesSetsAndOrderedHistory() throws Exception {
		LinkCoverCoverageState state = LinkCoverCoverageState.parse("{"
				+ "\"covered_link_ids\":[\"link-a\",\"link-a\",\"link-b\"],"
				+ "\"excluded_link_ids\":[\"link-b\"],"
				+ "\"traversal_history\":["
				+ "{\"link_id\":\"link-a\",\"source_node_id\":\"a\",\"target_node_id\":\"b\"},"
				+ "{\"link_id\":\"link-b\",\"source_node_id\":\"b\",\"target_node_id\":\"c\"}]} ");

		Map<String, LinkCoverCoverageState.LinkEndpoints> links = endpoints();
		state.validate(links);

		assertEquals(2, state.getCoveredLinkIds().size());
		assertTrue(state.isCovered("link-a"));
		assertTrue(state.isExcluded("link-b"));
		assertEquals(2, state.getTraversalHistory().size());
		assertEquals("link-a", state.getTraversalHistory().get(0).getLinkId());
		assertEquals("link-b", state.getTraversalHistory().get(1).getLinkId());
	}

	@Test
	public void routingFingerprintIgnoresTraversalHistory() throws Exception {
		LinkCoverCoverageState withoutHistory = LinkCoverCoverageState
				.parse("{\"covered_link_ids\":[\"link-a\"],\"excluded_link_ids\":[]}");
		LinkCoverCoverageState withHistory = LinkCoverCoverageState.parse("{"
				+ "\"covered_link_ids\":[\"link-a\"],\"excluded_link_ids\":[],"
				+ "\"traversal_history\":[{\"link_id\":\"link-a\","
				+ "\"source_node_id\":\"a\",\"target_node_id\":\"b\"}]}");

		assertEquals(withoutHistory.routingFingerprint(), withHistory.routingFingerprint());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsUnknownCoverageLink() throws Exception {
		LinkCoverCoverageState state = LinkCoverCoverageState
				.parse("{\"covered_link_ids\":[\"missing\"],\"excluded_link_ids\":[]}");
		state.validate(endpoints());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsDiscontinuousHistory() throws Exception {
		LinkCoverCoverageState state = LinkCoverCoverageState.parse("{"
				+ "\"covered_link_ids\":[\"link-a\",\"link-b\"],\"excluded_link_ids\":[],"
				+ "\"traversal_history\":["
				+ "{\"link_id\":\"link-a\",\"source_node_id\":\"a\",\"target_node_id\":\"b\"},"
				+ "{\"link_id\":\"link-b\",\"source_node_id\":\"c\",\"target_node_id\":\"b\"}]} ");
		state.validate(endpoints());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsHistoryLinkThatIsNotCovered() throws Exception {
		LinkCoverCoverageState state = LinkCoverCoverageState.parse("{"
				+ "\"covered_link_ids\":[],\"excluded_link_ids\":[],"
				+ "\"traversal_history\":[{\"link_id\":\"link-a\","
				+ "\"source_node_id\":\"a\",\"target_node_id\":\"b\"}]}");
		state.validate(endpoints());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsHistoryEndpointsThatDoNotMatchLink() throws Exception {
		LinkCoverCoverageState state = LinkCoverCoverageState.parse("{"
				+ "\"covered_link_ids\":[\"link-a\"],\"excluded_link_ids\":[],"
				+ "\"traversal_history\":[{\"link_id\":\"link-a\","
				+ "\"source_node_id\":\"a\",\"target_node_id\":\"c\"}]}");
		state.validate(endpoints());
	}

	private Map<String, LinkCoverCoverageState.LinkEndpoints> endpoints() {
		Map<String, LinkCoverCoverageState.LinkEndpoints> links = new LinkedHashMap<String, LinkCoverCoverageState.LinkEndpoints>();
		links.put("link-a", new LinkCoverCoverageState.LinkEndpoints("a", "b"));
		links.put("link-b", new LinkCoverCoverageState.LinkEndpoints("b", "c"));
		return links;
	}
}
