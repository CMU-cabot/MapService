/*******************************************************************************
 * Copyright (c) 2014, 2017 IBM Corporation, Carnegie Mellon University and
 * others
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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
window.$hulop || eval('let $hulop={};');

$hulop.editor = function () {

	const MAX_INDEX = 99;
	const JSONDATA_PATH = 'cabot/tourdata.json';
	const DESTINATION_KEYS = ['floor', 'value', 'startMessage', 'arrivalMessages', 'content', 'subtour', 'waitingDestination', 'waitingDestinationAngle'];
	let lastData, map, source, callback, editingFeature, downKey, keyState = {};

	function init(cb) {
		callback = cb;
		map = $hulop.map.getMap();
		let routeLayer = $hulop.map.getRouteLayer();
		routeLayer.setStyle(getStyle);
		source = routeLayer.getSource();
		$('.ol-rotate').css({
			'cssText': 'right: .5em !important; left: initial !important;'
		});
		map.addControl(new ol.control.Zoom());

		// Browser event listeners
		$(window).on({
			'keydown': event => {
				keyState = event;
				if (event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) {
					return;
				}
				downKey = event.keyCode;
			},
			'keyup': event => {
				keyState = event;
				downKey = null;
			}
		});

		$('#upload_button').click(event => $('#upload_file').click());
		let reader = new FileReader();
		$('#upload_file').change(event => {
			reader.readAsText(event.target.files[0]);
		});
		reader.onload = event => uploadJSONData(event.target.result, JSONDATA_PATH, () => location.reload());


		// Map event listeners
		map.on('click', event => {
			let feature = getEventFeature(event);
			if (feature === null) {
				return;
			}
			showProperty(feature);
		});

		prepareData($hulop.map.getCenter(), $hulop.config.MAX_RADIUS || 1000);
	}

	function getEventFeature(event) {
		let candidate;
		return map.forEachFeatureAtPixel(event.pixel, feature => {
			candidate = candidate || null;
			if (feature.getId()) {
				if (!feature.getGeometry().getArea) {
					return feature;
				} else if (!candidate || feature.getGeometry().getArea() < candidate.getGeometry().getArea()) {
					candidate = feature;
				}
			}
		}) || candidate;
	}

	function prepareData(center, radius) {
		$hulop.route.callService({
			'action': 'landmarks',
			'cache': false,
			'lang': 'ja,en',
			'lat': center[1],
			'lng': center[0],
			'dist': radius
		}, landmarks => {
			console.log('landmarks', landmarks);
			$hulop.route.callService({
				'action': 'nodemap',
			}, nodemap => {
				console.log('nodemap', nodemap);
				$hulop.route.callService({
					'action': 'features',
				}, features => {
					console.log('features', features);
					initData(landmarks, nodemap, features);
				});
			});
		});
	}

	function initData(landmarks, nodemap, features) {
		lastData = {
			'exit': {},
			'original': {},
			'modified': []
		};
		callback();
		if (nodemap && features) {
			for (let id in nodemap) {
				addFeatureList(nodemap[id]);
			}
			features.forEach(feature => addFeatureList(feature));
		}
		initDestinations(landmarks);
		importData(() => {
			showFeatureList();
			$hulop.indoor && $hulop.indoor.setStyle(getStyle);
		});
	}

	function getLabel(dest) {
		return dest && (dest['title-' + $hulop.messages.defaultLang] || dest['title-ja'] || dest['title-en'] || '(No name)');
	}

	function getStyle(feature) {
		let floor = getFloor();
		let style, heights = getHeights(feature);
		let odd = heights.length > 0 && Math.round(Math.abs(heights[0])) % 2 == 1;
		if (heights.length > 0 && heights[0] > 0) {
			odd = !odd;
		}
		if (feature.get('node_id')) {
			let dest = lastData.destinations[feature.getId()];
			let title = getLabel(dest);
			style = new ol.style.Style({
				'image': new ol.style.Circle({
					'radius': dest ? 8 : 4,
					'fill': new ol.style.Fill({
						'color': odd ? '#0000ff' : '#ff0000'
					}),
					'stroke': new ol.style.Stroke({
						'color': dest ? '#00B4B4' : '#FFFFFF',
						'width': 2
					})
				}),
				'zIndex': floor == heights[0] ? 1.01 : 1
			});
			if (title) {
				style = [style, new ol.style.Style({
					'text': new ol.style.Text({
						'textAlign': 'center',
						'textBaseline': 'bottom',
						'offsetY': -10,
						'text': title,
						'fill': new ol.style.Fill({
							'color': 'black'
						}),
						'stroke': new ol.style.Stroke({
							'color': 'white',
							'width': 3
						})
					})
				})
				];
			}
		} else if (feature.get('link_id')) {
			if (feature.get('route_type') == 4) {
				let anchor = [0.5, 1];
				if (floor != 0 && heights.length == 2 && (heights[0] < floor || heights[1] < floor)) {
					anchor = [0, 0];
				}
				style = new ol.style.Style({
					'image': new ol.style.Icon({
						'anchor': anchor,
						'anchorXUnits': 'fraction',
						'anchorYUnits': 'fraction',
						'src': 'images/ev.png'
					}),
					'stroke': new ol.style.Stroke({
						'color': 'black',
						'width': 6
					})

				});
			} else {
				let b = '#0000ff', r = '#ff0000';
				if (feature.get('hulop_road_low_priority') == 1) {
					b = '#0000A0';
					r = '#A00000';
				}
				if (feature.get('brail_tile') == 2) {
					b = r = '#00A000';
				}
				style = [new ol.style.Style({
					'stroke': new ol.style.Stroke({
						'color': heights.length == 1 ? odd ? b : r : '#7f007f',
						'width': 1
					})
				})];
			}
		} else if (feature.get('hulop_major_category') == '_nav_poi_') {
			// ignore
		} else if (feature.get('facil_id')) {
			// ignore
		} else {
			console.log(feature);
		}
		if (floor != 0 && heights.length > 0 && style) {
			let visible = heights.filter(height => {
				return $hulop.indoor && $hulop.indoor.isVisible(height);
			}).length > 0;
			if (!visible) {
				style = null;
			}
		}
		return style;
	}

	function getHeights(feature) {
		let heights = [];
		function addNode(node) {
			let h = node.get('floor');
			heights.indexOf(h) == -1 && heights.push(h);
		}
		function addLink(link) {
			['start_id', 'end_id'].forEach(key => {
				let node = source.getFeatureById(link.get(key));
				node && addNode(node);
			});
		}
		if (feature.get('node_id')) {
			addNode(feature);
			for (let i = 1; i <= MAX_INDEX; i++) {
				let linkID = feature.get('link' + i + '_id');
				let link = linkID && source.getFeatureById(linkID);
				link && addLink(link);
			}
		} else if (feature.get('link_id')) {
			addLink(feature);
		}
		return heights;
	}

	function showFeatureList() {
		let items = Object.keys(lastData.destinations).map(node_id => lastData.destinations[node_id]);
		items.sort((a, b) => {
			let rc = a.floor - b.floor;
			return rc != 0 ? rc : a.label.localeCompare(b.label);
		});
		$('#list').empty();
		let table = $('<table>').appendTo($('#list'));
		$('<caption>', {
			'text': 'Destinations'
		}).appendTo(table);
		let thead = $('<thead>').appendTo(table);
		let tbody = $('<tbody>').appendTo(table);
		$('<tr>').append($('<th>', {
			'text': 'Floor'
		}), $('<th>', {
			'text': 'Name'
		})).appendTo(thead);
		items.forEach(item => {
			$('<tr>', {
				'click': () => {
					$hulop.map.setCenter(ol.proj.transform(item.node.getGeometry().getCoordinates(), 'EPSG:3857', 'EPSG:4326'));
					showProperty(item.node);
				}
			}).append($('<td>', {
				'text': item.floor
			}), $('<td>', {
				'text': item.label
			}).attr('node_id', item.value)).appendTo('#list tbody');
		});
	}

	let format = new ol.format.GeoJSON()

	function addFeatureList(obj) {
		let p = obj.properties;
		let id = p['node_id'] || p['link_id'] || p['facil_id'];
		if (!id) {
			return;
		}
		if (source.getFeatureById(id)) {
			console.error('Duplicated id' + id);
			return;
		}
		if (obj._id && obj._id != id) {
			console.error('Incorrect id ' + id + ', it should ' + obj._id);
			return;
		}
		obj.id = id;

		let feature = format.readFeature(obj, {
			'featureProjection': 'EPSG:3857'
		});
		source.addFeature(feature);
		return feature;
	}

	let onNodeClick = null;

	function showProperty(feature) {
		if (onNodeClick && onNodeClick(feature)) return;
		onNodeClick = null;
		$('#properties').empty();
		editingFeature && editingFeature != feature && editingFeature.changed();
		editingFeature = feature;
		$hulop.editor.editingFeature = feature; // for debug
		if (feature) {
			showDestinationTable(feature);
		}
	}

	function showDestinationTable(feature) {
		let dest = lastData.destinations[feature.getId()];
		if (dest) {
			$hulop.indoor.showFloor(dest.floor);
			let table = $('<table>').appendTo($('#properties'));
			$('<caption>', {
				'text': dest.label
			}).appendTo(table);
			let thead = $('<thead>').appendTo(table);
			let tbody = $('<tbody>').appendTo(table);
			$('<tr>').append($('<th>', {
				'text': 'Key'
			}), $('<th>', {
				'text': 'Value'
			})).appendTo(thead);
			let saveButton = $('<button>', {
				'text': 'Save',
				'on': {
					'click': () => {
						saveButton.hide();
						$('#properties td[modified=true]').each((i, e) => {
							let key = $(e).attr('key');
							let value = $(e).text().trim();
							if ($(e).attr('numeric') == 'true') {
								if (value == '' || isNaN(value)) {
									delete dest[key];
									return;
								}
								value = Number(value);
							}
							dest[key] = value;
						});
						feature.changed();
						$('#properties td').removeAttr('modified');
						$('#list table td[node_id=' + dest.value + ']').text(getLabel(dest));
						exportData();
					}
				}
			}).appendTo($('#properties'));
			saveButton.hide();
			let added = {
				'label': true,
				'node': true,
				'facility': true
			};
			function add(name, editable, numeric) {
				if (!added[name]) {
					let value = dest[name] || '';
					let row = $('<tr>', {
						'class': editable ? 'editable' : 'read_only'
					}).append($('<td>', {
						'text': name
					}), $('<td>', {
						'on': {
							'input': event => {
								saveButton.show();
								$(event.target).attr('modified', true);
							}
						},
						'contenteditable': editable,
						'text': value
					}).attr('key', name).attr('numeric', !!numeric));
					row.appendTo(tbody);
					added[name] = true;
				}
			}
			onNodeClick = feature => {
				if (keyState.shiftKey) {
					saveButton.show();
					let td = $('#properties table td[key=waitingDestination]');
					let node_id = feature && feature.get('node_id');
					if (node_id && lastData.destinations[node_id]) {
						td.text(node_id);
					} else {
						td.text('');
					}
					td.attr('modified', true);
					return true;
				}
			}
			add('value');
			add('floor');
			add('title-ja');
			add('title-en');
			add('title-ja-pron');
			add('short_description-ja');
			add('short_description-en');
			add('long_description-ja');
			add('long_description-en');
			add('startMessage', true);
			add('arrivalMessages', true);
			add('content', true);
			add('waitingDestination');
			add('waitingDestinationAngle', true, true);
			add('subtour', true);
			Object.keys(dest).forEach(key => {
				add(key);
			});
		}
	}

	function getFloor() {
		return $hulop.indoor && $hulop.indoor.getCurrentFloor() || 0;
	}

	function initDestinations(landmarks) {
		let destinations = lastData.destinations = {};
		['ja', 'en'].forEach(lang => {
			landmarks[lang].forEach(landmark => {
				landmark.long_description = landmark.properties['hulop_long_description_' + lang] || landmark.properties['hulop_long_description'];
				let node_id = landmark.node;
				let dest = destinations[node_id] = destinations[node_id] || {};
				dest['value'] = node_id;
				dest['facility'] = landmark.properties;
				dest['floor'] = landmark.node_height;
				landmark.short_description && (dest['short_description-' + lang] = landmark.short_description);
				landmark.long_description && (dest['long_description-' + lang] = landmark.long_description);
				let title = landmark.name || getPoiName(landmark, lang);
				title && landmark.exit && (title = title + ' ' + landmark.exit);
				title && (dest['title-' + lang] = title);
				if (lang == 'ja') {
					let pron = landmark.name_pron || getPoiName(landmark, lang, true);
					pron && landmark.exit_pron && (pron = pron + ' ' + landmark.exit_pron);
					pron && (dest['title-' + lang + '-pron'] = pron);
				}
			});
		});
		Object.keys(destinations).forEach(node_id => {
			let dest = destinations[node_id];
			dest.label = getLabel(dest);
			dest.node = source.getFeatureById(node_id);
		});
	}

	function getPoiName(obj, lang, pron) {
		const M = {
			'TOILET': ['トイレ', 'Restroom'],
			'FOR_MALE': ['男性用', 'Male '],
			'FOR_FEMALE': ['女性用', 'Female '],
			'FOR_DISABLED': ['多機能', 'Multi-functional ']
		};
		let name, exit;
		if (pron) {
			name = obj.name_pron || obj.name;
			exit = obj.exit_pron || obj.exit;
		} else {
			name = obj.name;
			exit = obj.exit;
		}
		if (exit) {
			name += ' ' + exit;
		}
		let i = ['ja', 'en'].indexOf(lang);
		if (!name && obj.properties && obj.properties.facil_type == 10) {
			name = '';
			switch (obj.properties['sex']) {
				case 1:
					name += M['FOR_MALE'][i];
					break;
				case 2:
					name += M['FOR_FEMALE'][i];
					break;
			}
			switch (obj.properties['toilet']) {
				case 3:
				case 4:
				case 5:
				case 6:
					name += M['FOR_DISABLED'][i];
					break;
			}
			name += M['TOILET'][i];
		}
		return name;
	}

	function importData(callback) {
		downloadJSONData(JSONDATA_PATH, data => {
			console.log('raw data', data);
			let destinations = data && data.destinations;
			(destinations || []).forEach(dest_in => {
				let node_id = dest_in.value;
				let dest_out = node_id && lastData.destinations[node_id];
				if (dest_out) {
					lastData.destinations[node_id] = Object.assign(dest_out, dest_in);
				}
			});
			callback();
		});
	}

	function exportData() {
		let data = {};
		let destinations = data.destinations = [];
		let tours = data.tours = [];
		Object.keys(lastData.destinations).forEach(node_id => {
			let from = lastData.destinations[node_id];
			let to = {};
			DESTINATION_KEYS.forEach(key => {
				key in from && (to[key] = from[key]);
			});
			destinations.push(to);
		});
		destinations.sort((a, b) => {
			let rc = a.floor - b.floor;
			return rc != 0 ? rc : a.value.localeCompare(b.value);
		});
		uploadJSONData(JSON.stringify(data), JSONDATA_PATH)
	}

	function downloadJSONData(path, callback) {
		$hulop.util.loading(true);
		$.ajax({
			'type': 'GET',
			'url': path,
			'dataType': 'json',
			'success': (data) => {
				callback && callback(data);
				$hulop.util.loading(false);
			},
			'error': (xhr, textStatus, errorThrown) => {
				console.error(textStatus + ' (' + xhr.status + '): ' + errorThrown);
				callback && callback();
				$hulop.util.loading(false);
			}
		});
	}

	function uploadJSONData(data, path, callback) {
		$hulop.util.loading(true);
		$.ajax({
			'type': 'POST',
			'url': 'api/admin?type=file&path=' + path,
			'contentType': 'application/json',
			'data': data,
			'processData': false,
			'success': () => {
				callback && callback();
				$hulop.util.loading(false);
			},
			'error': (xhr, textStatus, errorThrown) => {
				console.error(textStatus + ' (' + xhr.status + '): ' + errorThrown);
				callback && callback();
				$hulop.util.loading(false);
			}
		});
	}

	return {
		'init': init
	}
}();