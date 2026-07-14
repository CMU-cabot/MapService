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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;

/**
 * Immutable client-supplied state for link-cover replanning.
 *
 * Covered and excluded links are sets that control the future Rural Postman
 * graph. Traversal history is an ordered sequence retained for a future
 * history-aware D-optimality implementation; the first implementation validates
 * it but deliberately does not use it for route scoring.
 */
public final class LinkCoverCoverageState {

	public static final class LinkEndpoints {
		private final String start;
		private final String end;

		public LinkEndpoints(String start, String end) {
			this.start = start;
			this.end = end;
		}

		boolean matches(String source, String target) {
			return (start.equals(source) && end.equals(target)) || (start.equals(target) && end.equals(source));
		}
	}

	public static final class TraversalStep {
		private final String linkId;
		private final String sourceNodeId;
		private final String targetNodeId;

		private TraversalStep(String linkId, String sourceNodeId, String targetNodeId) {
			this.linkId = linkId;
			this.sourceNodeId = sourceNodeId;
			this.targetNodeId = targetNodeId;
		}

		public String getLinkId() {
			return linkId;
		}

		public String getSourceNodeId() {
			return sourceNodeId;
		}

		public String getTargetNodeId() {
			return targetNodeId;
		}
	}

	private final Set<String> coveredLinkIds;
	private final Set<String> excludedLinkIds;
	private final List<TraversalStep> traversalHistory;

	private LinkCoverCoverageState(Set<String> coveredLinkIds, Set<String> excludedLinkIds,
			List<TraversalStep> traversalHistory) {
		this.coveredLinkIds = Collections.unmodifiableSet(coveredLinkIds);
		this.excludedLinkIds = Collections.unmodifiableSet(excludedLinkIds);
		this.traversalHistory = Collections.unmodifiableList(traversalHistory);
	}

	public static LinkCoverCoverageState empty() {
		return new LinkCoverCoverageState(new LinkedHashSet<String>(), new LinkedHashSet<String>(),
				new ArrayList<TraversalStep>());
	}

	public static LinkCoverCoverageState parse(String text) throws Exception {
		if (text == null || text.trim().isEmpty()) {
			return empty();
		}
		JSONObject json;
		try {
			json = new JSONObject(text);
		} catch (Exception e) {
			throw new IllegalArgumentException("coverage_state must be a JSON object", e);
		}

		Set<String> covered = parseIdSet(json, "covered_link_ids");
		Set<String> excluded = parseIdSet(json, "excluded_link_ids");
		List<TraversalStep> history = new ArrayList<TraversalStep>();
		if (json.has("traversal_history")) {
			Object raw = json.get("traversal_history");
			if (!(raw instanceof JSONArray)) {
				throw new IllegalArgumentException("coverage_state.traversal_history must be an array");
			}
			JSONArray array = (JSONArray) raw;
			for (int i = 0; i < array.length(); i++) {
				Object item = array.get(i);
				if (!(item instanceof JSONObject)) {
					throw new IllegalArgumentException("coverage_state.traversal_history items must be objects");
				}
				JSONObject step = (JSONObject) item;
				history.add(new TraversalStep(requireString(step, "link_id"),
						requireString(step, "source_node_id"), requireString(step, "target_node_id")));
			}
		}
		return new LinkCoverCoverageState(covered, excluded, history);
	}

	private static Set<String> parseIdSet(JSONObject json, String key) throws Exception {
		Set<String> result = new LinkedHashSet<String>();
		if (!json.has(key)) {
			return result;
		}
		Object raw = json.get(key);
		if (!(raw instanceof JSONArray)) {
			throw new IllegalArgumentException("coverage_state." + key + " must be an array");
		}
		JSONArray array = (JSONArray) raw;
		for (int i = 0; i < array.length(); i++) {
			Object value = array.get(i);
			if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
				throw new IllegalArgumentException("coverage_state." + key + " must contain non-empty strings");
			}
			result.add(((String) value).trim());
		}
		return result;
	}

	private static String requireString(JSONObject json, String key) throws Exception {
		if (!json.has(key)) {
			throw new IllegalArgumentException("coverage_state.traversal_history." + key + " is required");
		}
		Object value = json.get(key);
		if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
			throw new IllegalArgumentException("coverage_state.traversal_history." + key + " must be a non-empty string");
		}
		return ((String) value).trim();
	}

	public void validate(Map<String, LinkEndpoints> links) {
		for (String id : coveredLinkIds) {
			requireKnownLink(links, id);
		}
		for (String id : excludedLinkIds) {
			requireKnownLink(links, id);
		}

		String previousTarget = null;
		for (TraversalStep step : traversalHistory) {
			LinkEndpoints endpoints = links.get(step.linkId);
			if (endpoints == null) {
				throw new IllegalArgumentException("Unknown traversal_history link_id: " + step.linkId);
			}
			if (!coveredLinkIds.contains(step.linkId)) {
				throw new IllegalArgumentException("traversal_history link must also be covered: " + step.linkId);
			}
			if (!endpoints.matches(step.sourceNodeId, step.targetNodeId)) {
				throw new IllegalArgumentException("traversal_history endpoints do not match link: " + step.linkId);
			}
			if (previousTarget != null && !previousTarget.equals(step.sourceNodeId)) {
				throw new IllegalArgumentException("traversal_history is not continuous at link: " + step.linkId);
			}
			previousTarget = step.targetNodeId;
		}
	}

	private void requireKnownLink(Map<String, LinkEndpoints> links, String id) {
		if (!links.containsKey(id)) {
			throw new IllegalArgumentException("Unknown coverage link id: " + id);
		}
	}

	public boolean isCovered(String linkId) {
		return coveredLinkIds.contains(linkId);
	}

	public boolean isExcluded(String linkId) {
		return excludedLinkIds.contains(linkId);
	}

	public Set<String> getCoveredLinkIds() {
		return coveredLinkIds;
	}

	public Set<String> getExcludedLinkIds() {
		return excludedLinkIds;
	}

	public List<TraversalStep> getTraversalHistory() {
		return traversalHistory;
	}

	public String routingFingerprint() {
		List<String> covered = new ArrayList<String>(coveredLinkIds);
		List<String> excluded = new ArrayList<String>(excludedLinkIds);
		Collections.sort(covered);
		Collections.sort(excluded);
		return covered.toString() + "|" + excluded.toString();
	}

	public JSONObject toJSONObject() throws Exception {
		JSONObject json = new JSONObject();
		json.put("covered_link_ids", toJSONArray(coveredLinkIds));
		json.put("excluded_link_ids", toJSONArray(excludedLinkIds));
		JSONArray history = new JSONArray();
		for (TraversalStep step : traversalHistory) {
			JSONObject item = new JSONObject();
			item.put("link_id", step.linkId);
			item.put("source_node_id", step.sourceNodeId);
			item.put("target_node_id", step.targetNodeId);
			history.add(item);
		}
		json.put("traversal_history", history);
		return json;
	}

	private JSONArray toJSONArray(Set<String> values) {
		JSONArray array = new JSONArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}
}
