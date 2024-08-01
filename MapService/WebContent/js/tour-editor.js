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
	const DESTINATION_KEYS = [
		'floor',
		'value',
		// 'startMessage',
		// 'arrivalMessages',
		'arrivalAngle',
		'content',
		'subtour',
		'waitingDestination',
		'#waitingDestination',
		'waitingDestinationAngle'
	];
	const CATEGORY_KEYS = ['major_category', 'sub_category', 'minor_category', 'tags', 'building'];
	let lastData, map, source, callback, editingFeature, downKey, keyState = {};
	let imported_json;

	function getLanguages(pron) {
		// return pron ? ['ja', 'ja-pron', 'en', 'es', 'fr', 'ko', 'zh-CN'] : ['ja', 'en', 'es', 'fr', 'ko', 'zh-CN'];
		return pron ? ['ja', 'ja-pron', 'en'] : ['ja', 'en'];
	}

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
		// reader.onload = event => uploadJSONData(event.target.result, JSONDATA_PATH, () => location.reload());
		reader.onload = event => {
			imported_json = JSON.parse(event.target.result);
			prepareData($hulop.map.getCenter(), $hulop.config.MAX_RADIUS || 1000);
			$('#upload').show();
		};


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
			// 'lang': 'ja,en',
			'lang': getLanguages().join(','),
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
		return dest && (dest['title-' + $hulop.messages.defaultLang] || dest['title-ja'] || dest['title-en'] || Touri18n._('no_name'));
	}

	function getStyle(feature) {
		let floor = getFloor();
		let style, heights = getHeights(feature);
		let odd = heights.length > 0 && Math.round(Math.abs(heights[0])) % 2 == 1;
		if (heights.length > 0 && heights[0] > 0) {
			odd = !odd;
		}
		if (feature.get('node_id')) {
			let selected = feature === showingFeature;
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
			let title_stroke = 'white';
			let id = feature.getId();
			let ref_cell = $('#tour_properties td[key=ref], #tour_properties td[key=waitingDestination]').filter((i, e) => $(e).text() == id);
			let value_cell = $('#dest_properties td[key=waitingDestination]').filter((i, e) => $(e).text() == id);
			if (ref_cell.length) {
				title_stroke = 'yellow';
				let index = ref_cell.parents('td[key]').attr('key');
				if (index) {
					title = (ref_cell.length == 1 ? Number(index) + 1 : '*') + '. ' + title;
				}
			} else if (value_cell.length) {
				title_stroke = 'cyan';
			}
			if (title) {
				style = [style, new ol.style.Style({
					'text': new ol.style.Text({
						'textAlign': 'center',
						'textBaseline': 'bottom',
						'offsetY': -10,
						'text': title,
						'font': selected ? 'bold 16px arial' : '10px arial',
						'fill': new ol.style.Fill({
							'color': 'black'
						}),
						'stroke': new ol.style.Stroke({
							'color': title_stroke,
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

	$(document).ready(() => {
		let timeoutID;
		$('#search_text').on('input', event => {
			timeoutID && clearTimeout(timeoutID);
			timeoutID = setTimeout(showDestinationList, 250);
		});
	});

	function showDestinationList(floorFilter = null) {
		let selected_node = $('#list .destination_selected').attr('node_id');
		$('#list').empty();
		let items = Object.keys(lastData.destinations).map(node_id => lastData.destinations[node_id]);
		items = items.filter(item => {
			let text = $('#search_text').val().toLowerCase();
			if (text == '') {
				return true;
			}
			function find(target) {
				if (target) {
					if (Array.isArray(target)) {
						for (const child of target) {
							if (find(child)) {
								return true;
							}
						}
					} else {
						for (const [key, value] of Object.entries(target)) {
							if (typeof (value) == 'string' && value.toLowerCase().includes(text)) {
								return true;
							}
						}
					}
				}
			}
			return find(item) || find(item.facility) || find(item.messages);
		});

		// show floor option
		/*
		let allFloors = {};
		items.forEach((dest) => { allFloors[dest.floor] = true; });
		let floors = Object.keys(allFloors).sort();
		$("<span>Floor: </span>").appendTo($("#list"));
		let floorSelect = $('<select>', {
			'change': (e) => {
				showDestinationList(e.target.selectedOptions[0].label);
			}
		}).appendTo($("#list"));
		floorSelect.append($("<option>", { 'text': "All" }));
		floors.forEach((floor) => {
			$("<option>", { 'text': floor }).appendTo(floorSelect);
		});
		floorSelect.val(floorFilter ? floorFilter : "All");
		*/

		items = items.filter(item => {
			return floorFilter == null || floorFilter == "All" || item.floor == floorFilter;
		}).sort((a, b) => {
			let rc = a.floor - b.floor;
			return rc != 0 ? rc : a.label.localeCompare(b.label);
		});
		let table = $('<table>').appendTo($('#list'));
		$('<caption>', {
			'text': Touri18n._('destinations')
		}).appendTo(table);
		let thead = $('<thead>').appendTo(table);
		let tbody = $('<tbody>').appendTo(table);
		$('<tr>').append($('<th>', {
			'text': Touri18n._('floor')
		}), $('<th>', {
			'text': Touri18n._('title')
		})).appendTo(thead);
		items.forEach(item => {
			$('<tr>', {
				'click': () => {
					$hulop.map.animate(ol.proj.transform(item.node.getGeometry().getCoordinates(), 'EPSG:3857', 'EPSG:4326'), 300, () => {
						$hulop.indoor.showFloor(item.floor);
					});
					showProperty(item.node);
				}
			}).append($('<td>', {
				'text': item.floor
			}), $('<td>', {
				'text': item.label
			}).attr('node_id', item.value)).appendTo('#list tbody');
		});
		selected_node && destinationSelected(selected_node);
	}

	function showTourList() {
		$('#tour_list').empty();
		let table = $('<table>').appendTo($('#tour_list'));
		$('<caption>', {
			'text': Touri18n._('tours')
		}).appendTo(table);
		let thead = $('<thead>').appendTo(table);
		let tbody = $('<tbody>').appendTo(table);
		$('<tr>').append($('<th>', {
			'text': Touri18n._('title')
		})).appendTo(thead);
		lastData.tours.forEach(tour => {
			$('<tr>', {
				'click': () => {
					showTourProperty(tour);
					// $("#dest_properties").empty();
					showingFeature = null;
					showFeature(tour.destinations && tour.destinations[0] && tour.destinations[0].ref);
				}
			}).append($('<td>', {
				'text': getLabel(tour)
			})).appendTo('#tour_list tbody');
		});

		table.find('td').hover(event => {
			$element = $(event.target);
			// $element.find("i").remove();
			$element.parents('tbody').find("i").remove();
			let index = $(event.target).parents('tbody tr').index();
			let length = table.find('td').length
			let tag = $element.prop("tagName");
			if (event.type == 'mouseenter') {
				$element.css('position', 'relative');
				addIcon($element, 'fa-minus', Touri18n._('remove_tour'))
					.on('click', ((index) => {
						return (event) => {
							let removed = lastData.tours.splice(index, 1)[0];
							$('#tour_properties').empty();
							$hulop.map.refresh();
							showTourList();
							exportData();
						}
					})(index));
				addIcon($element, 'fa-arrow-down', Touri18n._('down_tour'))
					.addClass((tag == 'TH' || index == length - 1) ? 'disabled-icon' : null)
					.on('click', ((index) => {
						return (event) => {
							let removed = lastData.tours.splice(index, 1)[0];
							lastData.tours.splice(index + 1, 0, removed);
							// $('#tour_properties').empty();
							$hulop.map.refresh();
							showTourList();
							exportData();
						}
					})(index));
				addIcon($element, 'fa-arrow-up', Touri18n._('up_tour'))
					.addClass((tag == 'TH' || index == 0) ? 'disabled-icon' : null)
					.on('click', ((index) => {
						return (event) => {
							let removed = lastData.tours.splice(index, 1)[0];
							lastData.tours.splice(index - 1, 0, removed);
							// $('#tour_properties').empty();
							$hulop.map.refresh();
							showTourList();
							exportData();
						}
					})(index));
			}
			if (event.type == 'mouseleave') {
				$element.find("i").remove();
			}
		});

		addIcon(thead.find('th').css('position', 'relative'), 'fa-plus', Touri18n._('add_tour'))
			.on('click', event => {
				let new_tour = {
					'tour_id': 'tour_' + new Date().getTime(),
					'destinations': []
				};
				lastData.tours.push(new_tour);
				showTourList();
				showTourProperty(new_tour);
				exportData();
			});

		/* use icon instead of context menu
		table.on('contextmenu', event => {
			let index = $(event.target).parents('tbody tr').index();
			let items = [];
			items.push({
				'text': 'Add',
				'index': -1,
				'separator': index != -1
			});
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
						$('#tour_properties').empty();
					}
					$hulop.map.refresh();
				}
				showTourList();
				exportData();
			});
			return false;
		});
		*/
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
	let showingFeature = null;

	function showProperty(feature, skip_clear_event = false) {
		if (!skip_clear_event) {
			if (onNodeClick && onNodeClick(feature)) return;
			onNodeClick = null;
		}
		$('#dest_properties').empty();
		editingFeature && editingFeature != feature && editingFeature.changed();
		editingFeature = feature;
		$hulop.editor.editingFeature = feature; // for debug
		if (feature) {
			showDestinationTable(feature);
			destinationSelected(feature.getId());
		}
		showingFeature = feature;
		$hulop.map.refresh();
	}

	function destinationSelected(node_id) {
		$('#list .destination_selected').removeClass("destination_selected")
		let selector = $('#list td[node_id=' + node_id + ']');
		if (selector.length > 0) {
			selector[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
			selector.addClass("destination_selected")
		}
	}

	function showDestinationTable(feature) {
		let dest = lastData.destinations[feature.getId()];
		if (dest) {
			$hulop.indoor.showFloor(dest.floor);
			let table = $('<table>', { 'class': 'destination' }).appendTo($('#dest_properties'));
			$('<caption>', {
				'text': dest.label
			}).appendTo(table);
			let thead = $('<thead>').appendTo(table);
			let tbody = $('<tbody>').appendTo(table);
			$('<tr>').append($('<th>', {
				'text': Touri18n._('key')
			}), $('<th>', {
				'text': Touri18n._('value')
			})).appendTo(thead);
			let saveButton = $('<button>', {
				'text': Touri18n._('save'),
				'css': {
					'position': 'sticky',
					'bottom': '0px'
				},
				'on': {
					'click': () => {
						saveButton.hide();
						$('#dest_properties td[modified=true]').each((i, e) => {
							let key = $(e).attr('key');
							setValue(dest, key, $(e).text().trim(), $(e).attr('type'))
						});
						feature.changed();
						$('#dest_properties td').removeAttr('modified');
						$('#list table td[node_id=' + dest.value + ']').text(getLabel(dest));
						exportData();
					}
				}
			}).appendTo($('#dest_properties'));
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
			add('value');
			add('floor');
			// add('title-ja');
			// add('title-en');
			// add('title-ja-pron');
			getLanguages(true).forEach(lang => {
				add(`title-${lang}`);
			});
			// add('short_description-ja');
			// add('short_description-en');
			getLanguages().forEach(lang => {
				add(`short_description-${lang}`);
			});
			// add('long_description-ja');
			// add('long_description-en');
			getLanguages().forEach(lang => {
				add(`long_description-${lang}`);
			});
			CATEGORY_KEYS.forEach(key => {
				add(key);
			});
			// add('startMessage', { editable: true });
			// add('arrivalMessages', { editable: true });
			add('arrivalAngle', { editable: true, type: 'number' });
			add('content', { editable: true });
			add('waitingDestination', { 'hidden': true });
			add('#waitingDestination', { label: 'waitingDestination' });
			add('waitingDestinationAngle', { editable: true, type: 'number' });
			add('subtour', { editable: true });
			$('<tr>').append($('<td>').attr('colspan', 2).append($('<button>', { 'text': Touri18n._('messages___') }).css('width', '100%').on('click', event => {
				let template = [];
				template.push(['type', 'text', 'message_types']);
				template.push(['tags']);
				template.push(['age_group']);
				template.push(['timeFrom', 'time']);
				template.push(['timeUntil', 'time']);
				template.push(['dateFrom', 'date']);
				template.push(['dateUntil', 'date']);
				getLanguages(true).forEach(lang => {
					template.push([`text:${lang}`, 'textarea']);
				});

				MessageEditor.open(template, dest.messages, messages => {
					dest.messages = messages;
					exportData();
				});
			}))).appendTo(tbody);
			// Object.keys(dest).forEach(add);
			Touri18n.translate("#dest_properties");

			$('#dest_properties tr td[key=#waitingDestination]').parent().on('click', e => {
				$('.destination_selected').removeClass('destination_selected')
				console.log(e.target)
				$(e.target).parent().find('td').addClass('destination_selected')
				showFeature($('#dest_properties td[key=waitingDestination]').text());
				onNodeClick = feature => {
					if (keyState.altKey) {
						saveButton.show();
						let td = $('#dest_properties table td[key=waitingDestination]');
						let node_id = feature && feature.get('node_id');
						if (node_id && lastData.destinations[node_id]) {
							td.text(node_id);
							$hulop.indoor.showFloor(lastData.destinations[node_id].floor);
						} else {
							td.text(node_id || '');
						}
						td.attr('modified', true);
						setWaitingDestinationTitle();
						$hulop.map.refresh();
						return true;
					}
				}
			});
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
				value = false;
			} else {
				console.error('invalid boolean', value)
				return;
			}
		}
		obj[key] = value;
	}

	function showTourProperty(tour) {
		onNodeClick = null;
		tour.destinations = tour.destinations || [];
		tour.destinations.forEach(dest => {
			let node_id = dest.ref || '';
			let dest_data = node_id && lastData.destinations[node_id];
			dest['ref'] = node_id;
			dest['#ref'] = (dest_data && dest_data.label) || '';
		});
		$('#tour_properties').empty();
		let table = $('<table>', { 'class': 'tour' }).appendTo($('#tour_properties'));
		$('<caption>', {
			'text': getLabel(tour)
		}).appendTo(table);
		let thead = $('<thead>').appendTo(table);
		let tbody = $('<tbody>').appendTo(table);
		$('<tr>').append($('<th>', {
			'text': Touri18n._('key')
		}), $('<th>', {
			'text': Touri18n._('value')
		})).appendTo(thead);
		let saveButton = $('<button>', {
			'text': Touri18n._('save'),
			'css': {
				'position': 'sticky',
				'bottom': '0px'
			},
			'on': {
				'click': () => {
					applyChanges();
					let index = lastData.tours.indexOf(tour);
					$('#tour_list table tbody tr:nth-child(' + (index + 1) + ') td').text(getLabel(tour));
					exportData();
				}
			}
		}).appendTo($('#tour_properties'));
		saveButton.hide();
		let added = {
			'navigationSetting': true
		};

		function applyChanges() {
			saveButton.hide();
			$('#tour_properties td[modified=true]').each((i, e) => {
				let key = $(e).attr('key');
				let tree = [];
				$(e).parents('td[key]').each((i, e) => tree.unshift($(e).attr('key')));
				let target = tour;
				tree.forEach(key => target = target[key]);
				setValue(target, key, $(e).text().trim(), $(e).attr('type'))
			});
			$('#tour_properties td').removeAttr('modified');
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
				let editable = /^destinations\.\d+\.(ref|#ref)$/.test(name_key);
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
						'contenteditable': false,
						'text': value[key]
					});
				}
				let label = key;
				if (/^destinations\.\d+\.(#ref)$/.test(name_key)) {
					label = 'ref';
				} else if (/^destinations\.\d+$/.test(name_key)) {
					label = Number(key) + 1;
				}
				let cols = [$('<td>', {
					'text': label
				}), td.attr('key', key)];
				let row = $('<tr>', {
					'class': editable ? 'editable' : 'read_only'
				}).attr('name_key', name_key).append(cols).appendTo(tbody);
				if (/^destinations\.\d+\.(ref)$/.test(name_key)) {
					row.css('display', 'none');
				}
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
					row.find('td:not(:has(*)):last-child').hover(event => {
						$element = $(event.target);
						// $element.find("i").remove();
						$element.parents('tbody').find("td:last i").remove();
						let index = getDestinationIndex($element);
						let length = $('#tour_properties td[key=destinations] tr:has(table)').length

						if (event.type == 'mouseenter') {
							$element.css('position', 'relative');
							if (index != null) {
								addIcon($element, 'fa-minus', Touri18n._('remove_tour_destination'))
									.on('click', ((index) => {
										return (event) => {
											applyChanges();
											let removed = tour[name].splice(index, 1)[0];
											// $('#dest_properties').empty();
											$hulop.map.refresh();
											showTourProperty(tour);
											exportData();
										}
									})(index));
								addIcon($element, 'fa-arrow-down', Touri18n._('down_tour_destination'))
									.addClass((index == length - 1) ? 'disabled-icon' : null)
									.on('click', ((index) => {
										return (event) => {
											applyChanges();
											let removed = tour[name].splice(index, 1)[0];
											tour[name].splice(index + 1, 0, removed);
											// $('#dest_properties').empty();
											$hulop.map.refresh();
											showTourProperty(tour);
											exportData();
										}
									})(index));
								addIcon($element, 'fa-arrow-up', Touri18n._('up_tour_destination'))
									.addClass((index == 0) ? 'disabled-icon' : null)
									.on('click', ((index) => {
										return (event) => {
											applyChanges();
											let removed = tour[name].splice(index, 1)[0];
											tour[name].splice(index - 1, 0, removed);
											// $('#dest_properties').empty();
											$hulop.map.refresh();
											showTourProperty(tour);
											exportData();
										}
									})(index));
							}
						}
						if (event.type == 'mouseleave') {
							$element.find("i").remove();
						}
					});

					addIcon(row.find('td:first').css('position', 'relative'), 'fa-plus', Touri18n._('add_tour_destination'))
						.on('click', event => {
							applyChanges();
							if (!tour[name]) {
								tour[name] = [];
							}
							tour[name].push({ 'ref': '' });
							showTourProperty(tour);
							exportData();
							$('#tour_properties td[key=destinations] > table > tbody > tr:last td:first').trigger('click');
						});

					/* use icons instead of context menu
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
					*/
				}
				added[name] = true;
			}
		}

		function addTourDestination(feature) {
			let node_id = feature && feature.get('node_id');
			let dest = node_id && lastData.destinations[node_id];
			if (dest) {
				applyChanges();
				tour['destinations'].push({
					'ref': node_id,
					'#ref': dest.label || ''
				});
				showTourProperty(tour);
				exportData();
				$('#tour_properties td[key=destinations]').prev().trigger('click');
			}
		}

		add('tour_id', { editable: true });
		// add('title-ja', { editable: true });
		// add('title-en', { editable: true });
		// add('title-ja-pron', { editable: true });
		getLanguages(true).forEach(lang => {
			add(`title-${lang}`, { editable: true });
		});
		add('debug', { editable: true, type: 'boolean' });
		// add('introduction-ja', { editable: true });
		// add('introduction-en', { editable: true });
		// add('introduction-ja-pron', { editable: true });
		getLanguages(true).forEach(lang => {
			add(`introduction-${lang}`, { editable: true });
		});
		add('enableSubtourOnHandle', { editable: true, type: 'boolean' });
		add('showContentWhenArrive', { editable: true, type: 'boolean' });
		add('destinations', { editable: false, is_array: true, default: [] });
		// Object.keys(tour).forEach(add);
		Touri18n.translate("#tour_properties");

		$('#tour_properties td[key=destinations]').prev().on('click', e => {
			$('.destination_selected').removeClass('destination_selected');
			$(e.target).addClass('destination_selected');
			onNodeClick = feature => {
				if (keyState.altKey) {
					addTourDestination(feature);
					return true;
				}
				$('#tour_properties .destination_selected').removeClass("destination_selected");
			}
		});
		$('#tour_properties td[key=destinations] > table > tbody > tr').on('click contextmenu', e => {
			$('.destination_selected').removeClass('destination_selected')
			$(e.target).parents('#tour_properties td[key=destinations] > table > tbody > tr').addClass('destination_selected')
			node_id = $('.destination_selected td[key=ref]').text();
			showFeature(node_id);
			let feature = node_id && source.getFeatureById(node_id);
			showProperty(feature, true);
			onNodeClick = feature => {
				if (keyState.altKey) {
					let td = $('.destination_selected td[key=ref]');
					if (td.length == 0) {
						return true;
					}
					let node_id = feature && feature.get('node_id');
					let dest = node_id && lastData.destinations[node_id];
					saveButton.show();
					if (dest) {
						td.text(node_id);
						$hulop.indoor.showFloor(dest.floor);
					} else {
						td.text('');
					}
					td.attr('modified', true);
					td.parent().parent().find('td[key=#ref]').text((dest && dest.label) || '').attr('modified', true);
					$hulop.map.refresh();
					showProperty(feature, true);
					return true;
				}
				$('#tour_properties .destination_selected').removeClass("destination_selected");
			}
		});
		$hulop.map.refresh();
	}

	function showFeature(node_id) {
		let feature = node_id && source.getFeatureById(node_id);
		if (feature) {
			$hulop.map.animate(ol.proj.transform(feature.getGeometry().getCoordinates(), 'EPSG:3857', 'EPSG:4326'), 300, () => {
				$hulop.indoor.showFloor(feature.get('floor'));
			});
		}
	}

	function setWaitingDestinationTitle() {
		let node_id = $('#dest_properties table td[key=waitingDestination]').text();
		let dest = node_id && lastData.destinations[node_id];
		$('#dest_properties table td[key=#waitingDestination]').text((dest && dest.label) || node_id).attr('modified', true);
	}

	function getFloor() {
		return $hulop.indoor && $hulop.indoor.getCurrentFloor() || 0;
	}

	function initDestinations(landmarks) {
		let destinations = lastData.destinations = {};
		// ['ja', 'en'].forEach(lang => {
		getLanguages().forEach(lang => {
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
		if (!name && obj.properties && obj.properties.facil_type == 10) {
			let i = ['ja', 'en'].indexOf(lang);
			if (i < 0) {
				i = 1; // en
			}
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
			// Copy messages into destination
			(data && data.messages || []).forEach(message => {
				let destination = 'parent' in message && lastData.destinations[message.parent];
				if (destination) {
					destination.messages = destination.messages || [];
					destination.messages.push(message);
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

	$(document).ready(() => {
		$('#upload button').on('click', event => {
			exportData(true);
		});
	});

	function exportData(force) {
		console.log(lastData);
		let data = {};
		let destinations = data.destinations = [];
		let messages = data.messages = [];
		Object.keys(lastData.destinations).forEach(node_id => {
			let from = lastData.destinations[node_id];
			let to = {};
			DESTINATION_KEYS.forEach(key => {
				to[key] = from[key];
			});
			to = clean(to);
			// Extract messages from destination
			(from.messages || []).forEach(message => {
				message.parent = from.value;
				messages.push(message);
			});
			console.log([from, to]);
			if (Object.keys(to).length > 2) {
				to['#title'] = getLabel(from);
				destinations.push(to);
			}
		});
		console.log(destinations);
		destinations.sort((a, b) => {
			let rc = a.floor - b.floor;
			return rc != 0 ? rc : a.value.localeCompare(b.value);
		});
		data.tours = clean(lastData.tours) || [];
		// if (force) {
		if (force || $('#upload').is(':hidden')) {
			$('#upload').hide();
			uploadJSONData(JSON.stringify(data), JSONDATA_PATH)
			console.log(data);
		} else {
			$('#upload').show();
		}
	}

	function downloadJSONData(path, callback) {
		if (imported_json) {
			callback && callback(imported_json);
			imported_json = null;
			return;
		}
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

	/* use icon instead of context menu
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
					'class': item.separator ? 'separator' : 'no-separator',
					'on': {
						'click': e => callback(item)
					}
				}).appendTo(menu);
			});
			$('body').append(menu);
			$('#menu').css('top', Math.min(e.pageY, $('body').height() - $('#menu').height()) + 'px');
		}
	}
	*/

	function addIcon($element, className, title) {
		let count = $element.find("i").length
		let $icon = $('<i>')
			.addClass("fas")
			.addClass(className)
			.css('position', 'absolute')
			.css('right', (count * 20 + 5) + 'px')
			.css('top', '50%')
			.css('transform', 'translateY(-50%)')
			.css('cursor', 'pointer')
			.appendTo($element);
		return $icon.prop('title', title);
	}

	return {
		'init': init
	}
}();
