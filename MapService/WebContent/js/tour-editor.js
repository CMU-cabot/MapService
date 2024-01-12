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
	const DESTINATION_KEYS = ['floor', 'value', 'startMessage', 'arrivalMessages', 'arrivalAngle', 'content', 'subtour', 'waitingDestination', '#waitingDestination', 'waitingDestinationAngle'];
	const CATEGORY_KEYS = ['major_category', 'sub_category', 'minor_category', 'tags'];
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
			'keyup focus': event => {
				keyState = event;
				downKey = null;
			}
		});
		$('body').on('click', event => $('#menu').remove());

		$('#upload_link').click(event => $('#upload_file').click());
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
			showDestinationList();
			showTourList();
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

	function showDestinationList() {
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
			'text': 'Title'
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

	function showTourList() {
		$('#tour_list').empty();
		let table = $('<table>').appendTo($('#tour_list'));
		$('<caption>', {
			'text': 'Tours'
		}).appendTo(table);
		let thead = $('<thead>').appendTo(table);
		let tbody = $('<tbody>').appendTo(table);
		$('<tr>').append($('<th>', {
			'text': 'Title'
		})).appendTo(thead);
		lastData.tours.forEach(tour => {
			$('<tr>', {
				'click': () => {
					showTourProperty(tour);
				}
			}).append($('<td>', {
				'text': getLabel(tour)
			})).appendTo('#tour_list tbody');
		});
		table.on('contextmenu', event => {
			let index = $(event.target).parents('tbody tr').index();
			let items = [];
			if (index != -1) {
				if (index > 0) {
					items.push({
						'text': 'Move to top',
						'index': index,
						'move_to': 0
					});
					items.push({
						'text': 'Move up',
						'index': index,
						'move_to': index - 1,
						'separator': true
					});
				}
				if (index < lastData.tours.length - 1) {
					items.push({
						'text': 'Move down',
						'index': index,
						'move_to': index + 1
					});
					items.push({
						'text': 'Move to bottom',
						'index': index,
						'move_to': lastData.tours.length - 1,
						'separator': true
					});
				}
				items.push({
					'text': 'Remove',
					'index': index
				});
			}
			items.push({
				'text': 'Add',
				'index': -1
			});
			createContextMenu(event, items, item => {
				if (item.index == -1) {
					let new_tour = {
						'tour_id': 'tour_' + new Date().getTime(),
						'destinations': []
					};
					lastData.tours.push(new_tour);
					showTourProperty(new_tour);
				} else {
					let removed = lastData.tours.splice(item.index, 1)[0];
					if ('move_to' in item) {
						lastData.tours.splice(item.move_to, 0, removed);
					} else {
						$('#properties').empty();
					}
				}
				showTourList();
				exportData();
			});
			return false;
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
							setValue(dest, key, $(e).text().trim(), $(e).attr('type'))
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
				'facility': true,
				'#title': true
			};
			function add(name, options = {}) {
				if (!added[name]) {
					let value = name in dest ? dest[name] : '';
					let row = $('<tr>', {
						'class': options.editable ? 'editable' : 'read_only'
					}).append($('<td>', {
						'text': options.label || name
					}), $('<td>', {
						'on': {
							'input': event => {
								saveButton.show();
								$(event.target).attr('modified', true);
							}
						},
						'contenteditable': !!options.editable,
						'text': value
					}).attr('key', name).attr('type', options.type || ''));
					if (options.hidden) {
						row.css('display', 'none');
					}
					row.appendTo(tbody);
					added[name] = true;
				}
			}
			onNodeClick = feature => {
				if (keyState.altKey) {
					saveButton.show();
					let td = $('#properties table td[key=waitingDestination]');
					let node_id = feature && feature.get('node_id');
					if (node_id && lastData.destinations[node_id]) {
						td.text(node_id);
						$hulop.indoor.showFloor(lastData.destinations[node_id].floor);
					} else {
						td.text('');
					}
					td.attr('modified', true);
					setWaitingDestinationTitle();
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
			CATEGORY_KEYS.forEach(key => {
				add(key);
			});
			add('startMessage', { editable: true });
			add('arrivalMessages', { editable: true });
			add('arrivalAngle', { editable: true, type: 'number' });
			add('content', { editable: true });
			add('waitingDestination', { 'hidden': true });
			add('#waitingDestination', { label: 'waitingDestination' });
			add('waitingDestinationAngle', { editable: true, type: 'number' });
			add('subtour', { editable: true });
			// Object.keys(dest).forEach(add);
		}
	}

	function setValue(obj, key, value, type) {
		type = type || typeof obj[key];
		if (type == 'number') {
			if (value == '') {
				delete obj[key];
				return;
			} else if (isNaN(value)) {
				console.error('invalid number', value)
				return;
			}
			value = Number(value);
		} else if (type == 'boolean') {
			if (value == '') {
				delete obj[key];
				return;
			} else if (value == 'true') {
				value = true;
			} else if (value == 'false') {
				vale = false;
			} else {
				console.error('invalid boolean', value)
				return;
			}
		}
		obj[key] = value;
	}

	function showTourProperty(tour) {
		$('#properties').empty();
		let table = $('<table>').appendTo($('#properties'));
		$('<caption>', {
			'text': getLabel(tour)
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
					applyChanges();
					let index = lastData.tours.indexOf(tour);
					$('#tour_list table tbody tr:nth-child(' + (index + 1) + ') td').text(getLabel(tour));
					exportData();
				}
			}
		}).appendTo($('#properties'));
		saveButton.hide();
		let added = {
			'navigationSetting': true
		};

		function applyChanges() {
			saveButton.hide();
			$('#properties td[modified=true]').each((i, e) => {
				let key = $(e).attr('key');
				let tree = [];
				$(e).parents('td[key]').each((i, e) => tree.unshift($(e).attr('key')));
				let target = tour;
				tree.forEach(key => target = target[key]);
				setValue(target, key, $(e).text().trim(), $(e).attr('type'))
			});
			$('#properties td').removeAttr('modified');
		}

		function getDestinationIndex(target) {
			let name_key = $(target.parents('tr[name_key]').slice(-1)[0]).attr('name_key');
			let reg = /destinations\.(\d+)/.exec(name_key);
			return reg && Number(reg[1]);
		}

		function getInnerTable(name, value, options) {
			let table = $('<table>');
			let tbody = $('<tbody>').appendTo(table);
			Object.keys(value).forEach(key => {
				let name_key = name + '.' + key;
				let editable = /^destinations\.\d+\.ref$/.test(name_key);
				if (typeof value[key] == 'object') {
					td = $('<td>').append(getInnerTable(name_key, value[key], options));
				} else {
					if (!editable) return;
					td = $('<td>', {
						'on': {
							'input': event => {
								saveButton.show();
								$(event.target).attr('modified', true);
							}
						},
						'contenteditable': editable,
						'text': value[key]
					});
				}
				let cols = [$('<td>', {
					'text': key
				}), td.attr('key', key)];
				$('<tr>', {
					'class': editable ? 'editable' : 'read_only'
				}).attr('name_key', name_key).append(cols).appendTo(tbody);
			});
			return table;
		}

		function add(name, options = {}) {
			if (!added[name]) {
				let value = name in tour ? tour[name] : options.default || '';
				let td;
				if (typeof value == 'object') {
					td = $('<td>').append(getInnerTable(name, value, options));
				} else {
					td = $('<td>', {
						'on': {
							'input': event => {
								saveButton.show();
								$(event.target).attr('modified', true);
							}
						},
						'contenteditable': !!options.editable,
						'text': value
					}).attr('type', options.type || '');
				}
				let row = $('<tr>', {
					'class': options.editable ? 'editable' : 'read_only'
				}).append($('<td>', {
					'text': name
				}), td.attr('key', name));
				row.appendTo(tbody);
				if (options.is_array) {
					row.on('contextmenu', event => {
						let index = getDestinationIndex($(event.target));
						let items = [];
						if (index == null) {
							items.push({
								'text': 'Add',
								'index': -1
							});
						} else {
							if (index > 0) {
								items.push({
									'text': 'Move to top',
									'index': index,
									'move_to': 0
								});
								items.push({
									'text': 'Move up',
									'index': index,
									'move_to': index - 1,
									'separator': true
								});
							}
							if (index < tour[name].length - 1) {
								items.push({
									'text': 'Move down',
									'index': index,
									'move_to': index + 1
								});
								items.push({
									'text': 'Move to bottom',
									'index': index,
									'move_to': tour[name].length - 1,
									'separator': true
								});
							}
							items.push({
								'text': 'Remove',
								'index': index
							});
						}
						createContextMenu(event, items, item => {
							applyChanges();
							if (item.index == -1) {
								if (!tour[name]) {
									tour[name] = [];
								}
								tour[name].push({ 'ref': '' });
							} else {
								let removed = tour[name].splice(item.index, 1)[0];
								if ('move_to' in item) {
									tour[name].splice(item.move_to, 0, removed);
								}
							}
							showTourProperty(tour);
							exportData();
						});
						return false;
					});
				}
				added[name] = true;
			}
		}
		add('tour_id', { editable: true });
		add('title-ja', { editable: true });
		add('title-en', { editable: true });
		add('title-ja-pron', { editable: true });
		add('debug', { editable: true, type: 'boolean' });
		add('introduction-ja', { editable: true });
		add('introduction-en', { editable: true });
		add('introduction-ja-pron', { editable: true });
		add('enableSubtourOnHandle', { editable: true, type: 'boolean' });
		add('showContentWhenArrive', { editable: true, type: 'boolean' });
		add('destinations', { editable: false, is_array: true, default: [] });
		// Object.keys(tour).forEach(add);
	}

	function setWaitingDestinationTitle() {
		let node_id = $('#properties table td[key=waitingDestination]').text();
		let dest = node_id && lastData.destinations[node_id];
		$('#properties table td[key=#waitingDestination]').text((dest && dest.label) || '').attr('modified', true);
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
				CATEGORY_KEYS.forEach(key => {
					let value = landmark.properties['hulop_' + key];
					value && (dest[key] = value);
				});
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
			// Reset #waitingDestination
			Object.keys(lastData.destinations).forEach(node_id => {
				let dest = lastData.destinations[node_id];
				let wd_id = dest.waitingDestination;
				let wd = wd_id && lastData.destinations[wd_id];
				let label = wd && wd.label;
				if (label) {
					dest['#waitingDestination'] = label;
				} else {
					delete dest['#waitingDestination'];
				}
			});
			lastData.tours = (data && data.tours) || [];
			callback();
		});
	}

	function clean(obj) {
		if (typeof obj != 'object') {
			return obj;
		}
		let result = Array.isArray(obj) ? [] : {};
		Object.keys(obj).forEach(key => {
			let value = clean(obj[key]);
			if ([undefined, null, ''].includes(value)) {
				return;
			} else if (Array.isArray(obj)) {
				result.push(value);
			} else {
				result[key] = value;
			}
		});
		if (Object.keys(result).length > 0) {
			return result;
		}
	}

	function exportData() {
		let data = {};
		let destinations = data.destinations = [];
		Object.keys(lastData.destinations).forEach(node_id => {
			let from = lastData.destinations[node_id];
			let to = {};
			DESTINATION_KEYS.forEach(key => {
				to[key] = from[key];
			});
			to = clean(to);
			if (Object.keys(to).length > 2) {
				to['#title'] = getLabel(from);
				destinations.push(to);
			}
		});
		destinations.sort((a, b) => {
			let rc = a.floor - b.floor;
			return rc != 0 ? rc : a.value.localeCompare(b.value);
		});
		data.tours = clean(lastData.tours) || [];
		uploadJSONData(JSON.stringify(data), JSONDATA_PATH)
	}

	function downloadJSONData(path, callback) {
		$hulop.util.loading(true);
		$.ajax({
			'type': 'GET',
			'url': path,
			'dataType': 'json',
			'success': data => {
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

	function createContextMenu(e, items, callback) {
		if ($('#menu').size() == 0) {
			let menu = $('<ul>', {
				'id': 'menu',
				'css': {
					'top': e.pageY + 'px',
					'left': e.pageX + 'px'
				}
			});
			items.forEach(item => {
				$('<li>', {
					'text': item.text,
					'class' : item.separator ? 'separator' : 'no-separator',
					'on': {
						'click': e => callback(item)
					}
				}).appendTo(menu);
			});
			$('body').append(menu);
		}
	}

	return {
		'init': init
	}
}();