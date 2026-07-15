window.$hulop || eval('var $hulop={};');

$hulop.routeOptions = {
	'routeAction' : 'linkcover',
	'allowSubgraph' : true,
	'solver' : 'dopt',
	'attempts' : '1000',
	'useDestination' : false,
	'destinationLabel' : 'Link Cover'
};

$hulop.linkcover = function() {
	var state = emptyState();
	var manualCovered = {};
	var initialPlan = [];
	var initialPlanIndex = 0;
	var catalogInitialized = false;
	var replaceCatalogOnNextRoute = false;
	var coverageLayer, coverageSource, format, map;
	var initialized = false;
	var catalogById = {};
	var styleCache = {};
	var pendingNavigationTemporary;

	function emptyState() {
		return {
			'covered_link_ids' : [],
			'excluded_link_ids' : [],
			'traversal_history' : []
		};
	}

	function hasOwn(obj, key) {
		return Object.prototype.hasOwnProperty.call(obj, key);
	}

	function addUnique(array, value) {
		if (array.indexOf(value) < 0) {
			array.push(value);
		}
	}

	function removeValue(array, value) {
		var index = array.indexOf(value);
		if (index >= 0) {
			array.splice(index, 1);
		}
	}

	function normalizeIds(value, name) {
		if (!Array.isArray(value)) {
			throw new Error(name + ' must be an array');
		}
		var result = [];
		value.forEach(function(id) {
			if (typeof id != 'string' || !id) {
				throw new Error(name + ' must contain non-empty strings');
			}
			addUnique(result, id);
		});
		return result;
	}

	function normalizeHistory(value) {
		if (!Array.isArray(value)) {
			throw new Error('traversal_history must be an array');
		}
		var result = [];
		value.forEach(function(item, index) {
			if (!item || typeof item != 'object' || Array.isArray(item)) {
				throw new Error('traversal_history[' + index + '] must be an object');
			}
			var linkId = item.link_id;
			var sourceNodeId = item.source_node_id;
			var targetNodeId = item.target_node_id;
			if (typeof linkId != 'string' || !linkId || typeof sourceNodeId != 'string' || !sourceNodeId || typeof targetNodeId != 'string' || !targetNodeId) {
				throw new Error('traversal_history[' + index + '] has invalid link or node IDs');
			}
			if (result.length && result[result.length - 1].target_node_id != sourceNodeId) {
				throw new Error('traversal_history is not continuous at index ' + index);
			}
			result.push({
				'link_id' : linkId,
				'source_node_id' : sourceNodeId,
				'target_node_id' : targetNodeId
			});
		});
		return result;
	}

	function parseState(text) {
		text = String(text || '').replace(/^\s+|\s+$/g, '');
		var value = text ? JSON.parse(text) : emptyState();
		if (!value || typeof value != 'object' || Array.isArray(value)) {
			throw new Error('coverage_state must be a JSON object');
		}
		var parsed = {
			'covered_link_ids' : normalizeIds(value.covered_link_ids || [], 'covered_link_ids'),
			'excluded_link_ids' : normalizeIds(value.excluded_link_ids || [], 'excluded_link_ids'),
			'traversal_history' : normalizeHistory(value.traversal_history || [])
		};
		// Every historical traversal must also be present in the covered set.
		parsed.traversal_history.forEach(function(item) {
			addUnique(parsed.covered_link_ids, item.link_id);
		});
		return parsed;
	}

	function applyState(parsed) {
		state = parsed;
		manualCovered = {};
		var historyIds = {};
		state.traversal_history.forEach(function(item) {
			historyIds[item.link_id] = true;
		});
		state.covered_link_ids.forEach(function(id) {
			if (!historyIds[id]) {
				manualCovered[id] = true;
			}
		});
		updateInitialPlanIndex();
		renderState();
	}

	function readTextarea() {
		var input = $('#coverage_state');
		var parsed = parseState(input.length ? input.val() : JSON.stringify(state));
		applyState(parsed);
		return parsed;
	}

	function syncTextarea() {
		$('#coverage_state').val(JSON.stringify(state, null, 2));
	}

	function setMessage(message, error) {
		$('#coverage_message').text(message || '').toggleClass('coverage_error', !!error);
	}

	function getCoverageId(geo) {
		var properties = geo && geo.properties || {};
		return properties.coverage_link_id;
	}

	function isTemporary(geo) {
		var properties = geo && geo.properties || {};
		var linkId = geo && geo._id || properties.link_id || '';
		var source = properties.sourceNode || '';
		var target = properties.targetNode || '';
		return String(linkId).indexOf('_TEMP_') == 0 || String(source).indexOf('_TEMP_') == 0 || String(target).indexOf('_TEMP_') == 0;
	}

	function isTemporaryNode(nodeId) {
		return String(nodeId || '').indexOf('_TEMP_') == 0;
	}

	function combineTemporaryGeos(first, second) {
		// One projected fragment is only a partial traversal. Two adjacent
		// fragments from one original endpoint to the other complete the physical
		// link and can therefore be represented in traversal_history.
		var firstProperties = first && first.properties || {};
		var secondProperties = second && second.properties || {};
		var linkId = getCoverageId(first);
		if (!linkId || linkId != getCoverageId(second) || firstProperties.targetNode != secondProperties.sourceNode || isTemporaryNode(firstProperties.sourceNode) || !isTemporaryNode(firstProperties.targetNode) || !isTemporaryNode(secondProperties.sourceNode) || isTemporaryNode(secondProperties.targetNode)) {
			return null;
		}
		var firstCoordinates = first.geometry && first.geometry.coordinates || [];
		var secondCoordinates = second.geometry && second.geometry.coordinates || [];
		var coordinates = firstCoordinates.slice();
		if (coordinates.length && secondCoordinates.length) {
			coordinates = coordinates.concat(secondCoordinates.slice(1));
		}
		var properties = JSON.parse(JSON.stringify(firstProperties));
		properties.link_id = linkId;
		properties.coverage_link_id = linkId;
		properties.sourceNode = firstProperties.sourceNode;
		properties.targetNode = secondProperties.targetNode;
		properties.sourceHeight = firstProperties.sourceHeight;
		properties.targetHeight = secondProperties.targetHeight;
		return {
			'_id' : linkId,
			'type' : 'Feature',
			'geometry' : {
				'type' : 'LineString',
				'coordinates' : coordinates
			},
			'properties' : properties
		};
	}

	function traversalFromGeo(geo) {
		if (!geo || isTemporary(geo)) {
			return null;
		}
		var properties = geo.properties || {};
		var linkId = getCoverageId(geo);
		if (!linkId) {
			return null;
		}
		return {
			'link_id' : linkId,
			'source_node_id' : properties.sourceNode,
			'target_node_id' : properties.targetNode,
			'geo' : geo
		};
	}

	function sameTraversal(left, right) {
		return left && right && left.link_id == right.link_id && left.source_node_id == right.source_node_id && left.target_node_id == right.target_node_id;
	}

	function updateInitialPlanIndex() {
		initialPlanIndex = 0;
		if (!initialPlan.length || !state.traversal_history.length) {
			return;
		}
		for (var start = 0; start < state.traversal_history.length; start++) {
			var matched = 0;
			while (start + matched < state.traversal_history.length && matched < initialPlan.length && sameTraversal(state.traversal_history[start + matched], initialPlan[matched])) {
				matched++;
			}
			initialPlanIndex = Math.max(initialPlanIndex, matched);
		}
	}

	function advanceInitialPlan(traversal) {
		if (initialPlanIndex < initialPlan.length && sameTraversal(initialPlan[initialPlanIndex], traversal)) {
			initialPlanIndex++;
		}
	}

	function addCovered(id) {
		addUnique(state.covered_link_ids, id);
	}

	function appendTraversal(traversal) {
		addCovered(traversal.link_id);
		if (!traversal.source_node_id || !traversal.target_node_id) {
			return false;
		}
		var history = state.traversal_history;
		if (history.length && history[history.length - 1].target_node_id != traversal.source_node_id) {
			return false;
		}
		history.push({
			'link_id' : traversal.link_id,
			'source_node_id' : traversal.source_node_id,
			'target_node_id' : traversal.target_node_id
		});
		advanceInitialPlan(traversal);
		return true;
	}

	function recordTraversals(traversals) {
		var completed = 0;
		var notRecorded = 0;
		traversals.forEach(function(traversal) {
			if (!traversal) {
				return;
			}
			completed++;
			if (!appendTraversal(traversal)) {
				notRecorded++;
			}
		});
		syncTextarea();
		renderState();
		return {
			'completed' : completed,
			'notRecorded' : notRecorded
		};
	}

	function onNavigationProgress(progress) {
		try {
			readTextarea();
		} catch (e) {
			setMessage('Navigation progress was not saved: ' + e.message, true);
			return;
		}
		var ignoredTemporaryCount = 0;
		var traversals = [];
		(progress.links || []).forEach(function(geo) {
			if (isTemporary(geo)) {
				var combined = pendingNavigationTemporary && combineTemporaryGeos(pendingNavigationTemporary, geo);
				if (combined) {
					traversals.push(traversalFromGeo(combined));
					pendingNavigationTemporary = null;
				} else {
					pendingNavigationTemporary && ignoredTemporaryCount++;
					pendingNavigationTemporary = geo;
				}
				return;
			}
			if (pendingNavigationTemporary) {
				ignoredTemporaryCount++;
				pendingNavigationTemporary = null;
			}
			var traversal = traversalFromGeo(geo);
			if (traversal) {
				traversals.push(traversal);
			}
		});
		if (progress.final && pendingNavigationTemporary) {
			ignoredTemporaryCount++;
			pendingNavigationTemporary = null;
		}
		var result = recordTraversals(traversals);
		var messages = [];
		result.completed && messages.push(result.completed + ' link(s) marked Covered');
		progress.skipped && messages.push('skipped navigation steps included');
		ignoredTemporaryCount && messages.push(ignoredTemporaryCount + ' partial temporary link(s) ignored');
		result.notRecorded && messages.push(result.notRecorded + ' link(s) not added to history because it would be discontinuous');
		setMessage(messages.join('; '), result.notRecorded > 0);
	}

	function flattenRoute(naviRoutes) {
		var links = [];
		(naviRoutes || []).forEach(function(route) {
			(route.links || []).forEach(function(link) {
				link.geo && links.push(link.geo);
			});
		});
		return links;
	}

	function completeTraversals(geos) {
		var traversals = [];
		var pendingTemporary;
		geos.forEach(function(geo) {
			if (isTemporary(geo)) {
				var combined = pendingTemporary && combineTemporaryGeos(pendingTemporary, geo);
				if (combined) {
					traversals.push(traversalFromGeo(combined));
					pendingTemporary = null;
				} else {
					pendingTemporary = geo;
				}
				return;
			}
			pendingTemporary = null;
			var traversal = traversalFromGeo(geo);
			traversal && traversals.push(traversal);
		});
		return traversals;
	}

	function captureInitialRoute(naviRoutes) {
		pendingNavigationTemporary = null;
		if (catalogInitialized && !replaceCatalogOnNextRoute) {
			return;
		}
		coverageSource.clear();
		catalogById = {};
		initialPlan = [];
		completeTraversals(flattenRoute(naviRoutes)).forEach(function(traversal) {
			initialPlan.push(traversal);
			if (hasOwn(catalogById, traversal.link_id)) {
				return;
			}
			var feature = format.readFeature(traversal.geo, {
				'featureProjection' : 'EPSG:3857'
			});
			feature.set('coverage_link_id', traversal.link_id);
			catalogById[traversal.link_id] = feature;
			coverageSource.addFeature(feature);
		});
		catalogInitialized = true;
		replaceCatalogOnNextRoute = false;
		updateInitialPlanIndex();
		renderState();
		setMessage(initialPlan.length ? 'Coverage links are ready for selection' : 'No complete physical links were found', !initialPlan.length);
	}

	function rebuildCovered() {
		var covered = [];
		state.covered_link_ids.forEach(function(id) {
			if (manualCovered[id]) {
				addUnique(covered, id);
			}
		});
		state.traversal_history.forEach(function(item) {
			addUnique(covered, item.link_id);
		});
		state.covered_link_ids = covered;
	}

	function addCoveredThrough(linkId) {
		var targetIndex = -1;
		for (var i = initialPlanIndex; i < initialPlan.length; i++) {
			if (initialPlan[i].link_id == linkId) {
				targetIndex = i;
				break;
			}
		}
		if (targetIndex < 0) {
			manualCovered[linkId] = true;
			addCovered(linkId);
			syncTextarea();
			renderState();
			setMessage('Covered was added without history because no pending route occurrence was found', false);
			return;
		}
		var result = recordTraversals(initialPlan.slice(initialPlanIndex, targetIndex + 1));
		setMessage(result.notRecorded ? result.notRecorded + ' link(s) could not be added to history' : result.completed + ' link(s) marked Covered', result.notRecorded > 0);
	}

	function removeCoveredAndRewind(linkId) {
		delete manualCovered[linkId];
		var rewindIndex = -1;
		for (var i = 0; i < state.traversal_history.length; i++) {
			if (state.traversal_history[i].link_id == linkId) {
				rewindIndex = i;
				break;
			}
		}
		if (rewindIndex >= 0) {
			state.traversal_history.splice(rewindIndex);
		}
		rebuildCovered();
		removeValue(state.covered_link_ids, linkId);
		updateInitialPlanIndex();
		syncTextarea();
		renderState();
		setMessage(rewindIndex >= 0 ? 'History was rewound to keep it continuous' : 'Covered was removed', false);
	}

	function toggleExcluded(linkId) {
		if (state.excluded_link_ids.indexOf(linkId) >= 0) {
			removeValue(state.excluded_link_ids, linkId);
			setMessage('Excluded was removed', false);
		} else {
			state.excluded_link_ids.push(linkId);
			setMessage('Excluded was added; run route search to apply it', false);
		}
		syncTextarea();
		renderState();
	}

	function onMapClick(event) {
		var mode = $('input[name="linkcover_edit_mode"]:checked').val();
		if (!mode || mode == 'view') {
			return;
		}
		var feature = map.forEachFeatureAtPixel(event.pixel, function(candidate, layer) {
			if (layer == coverageLayer) {
				return candidate;
			}
		}, {
			'hitTolerance' : 8
		});
		if (!feature) {
			return;
		}
		try {
			readTextarea();
		} catch (e) {
			setMessage('Map edit was not applied: ' + e.message, true);
			return;
		}
		var linkId = feature.get('coverage_link_id');
		if (mode == 'covered') {
			if (state.covered_link_ids.indexOf(linkId) >= 0) {
				removeCoveredAndRewind(linkId);
			} else {
				addCoveredThrough(linkId);
			}
		} else if (mode == 'excluded') {
			toggleExcluded(linkId);
		}
	}

	function isVisibleOnCurrentFloor(feature) {
		var floor = $hulop.indoor && $hulop.indoor.getCurrentFloor() || 0;
		if (!floor) {
			return true;
		}
		var source = Number(feature.get('sourceHeight'));
		var target = Number(feature.get('targetHeight'));
		return isNaN(source) && isNaN(target) || Math.abs(source - floor) < 0.5 || Math.abs(target - floor) < 0.5;
	}

	function makeLineStyle(color, width) {
		return new ol.style.Style({
			'stroke' : new ol.style.Stroke({
				'color' : color,
				'width' : width
			})
		});
	}

	function getCoverageStyle(feature) {
		if (!isVisibleOnCurrentFloor(feature)) {
			return null;
		}
		var status = feature.get('coverage_status') || 'available';
		if (!styleCache[status]) {
			switch (status) {
			case 'covered':
				styleCache[status] = makeLineStyle('#2e7d32', 7);
				break;
			case 'excluded':
				styleCache[status] = makeLineStyle('#d32f2f', 7);
				break;
			case 'both':
				styleCache[status] = [ makeLineStyle('#d32f2f', 10), makeLineStyle('#2e7d32', 5) ];
				break;
			default:
				styleCache[status] = makeLineStyle('rgba(80, 80, 80, 0.45)', 4);
				break;
			}
		}
		return styleCache[status];
	}

	function renderState() {
		var covered = {};
		var excluded = {};
		state.covered_link_ids.forEach(function(id) {
			covered[id] = true;
		});
		state.excluded_link_ids.forEach(function(id) {
			excluded[id] = true;
		});
		coverageSource && coverageSource.getFeatures().forEach(function(feature) {
			var id = feature.get('coverage_link_id');
			feature.set('coverage_status', covered[id] && excluded[id] ? 'both' : covered[id] ? 'covered' : excluded[id] ? 'excluded' : 'available', true);
		});
		coverageLayer && coverageLayer.changed();
		$('#covered_count').text(state.covered_link_ids.length);
		$('#excluded_count').text(state.excluded_link_ids.length);
		$('#history_count').text(state.traversal_history.length);
	}

	function resetState() {
		state = emptyState();
		manualCovered = {};
		initialPlanIndex = 0;
		pendingNavigationTemporary = null;
		replaceCatalogOnNextRoute = true;
		syncTextarea();
		renderState();
		setMessage('Coverage state was reset; the next route will replace the selectable link catalog', false);
	}

	function init() {
		if (initialized) {
			return;
		}
		map = $hulop.map && $hulop.map.getMap && $hulop.map.getMap();
		if (!map) {
			setTimeout(init, 100);
			return;
		}
		initialized = true;
		format = new ol.format.GeoJSON();
		coverageSource = new ol.source.Vector();
		coverageLayer = new ol.layer.Vector({
			'source' : coverageSource,
			'style' : getCoverageStyle,
			'zIndex' : 102
		});
		map.addLayer(coverageLayer);
		map.on('singleclick', onMapClick);
		$hulop.map.on('route', captureInitialRoute);
		$hulop.map.on('navigationProgress', onNavigationProgress);
		$('#coverage_reset').on('click', function(event) {
			event.preventDefault();
			resetState();
		});
		$('#coverage_state').on('change', function() {
			try {
				readTextarea();
				syncTextarea();
				setMessage('coverage_state JSON was applied', false);
			} catch (e) {
				setMessage('Invalid coverage_state: ' + e.message, true);
			}
		});
		try {
			readTextarea();
			syncTextarea();
		} catch (e) {
			setMessage('Invalid coverage_state: ' + e.message, true);
		}
	}

	return {
		'init' : init,
		'getState' : function() {
			return JSON.parse(JSON.stringify(state));
		},
		'getStateText' : function() {
			readTextarea();
			syncTextarea();
			return JSON.stringify(state);
		},
		'resetState' : resetState,
		'onNavigationProgress' : onNavigationProgress
	};
}();
