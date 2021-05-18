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
window.$hulop || eval('var $hulop={};');

$hulop.screen_filter = function() {
	var history = [], last;
	var button, a;
	var popup, exitLog;

	function onUpdateLocation(crd) {
		if ($hulop.mobile && $hulop.mobile.getPreference('user_mode') == 'user_blind') {
			// return;
		}
		var start_timer = $hulop.config.SCREEN_FILTER_START_TIMER;
		var walk_speed = $hulop.config.SCREEN_FILTER_SPEED;
		if (!(start_timer && walk_speed && crd.provider == 'bleloc')) {
			return;
		}
		if (isPopupOpen()) {
			return;
		}
		if ($hulop.config.SCREEN_FILTER_NO_BUTTON == 'true') {
			if (!popup) {
				showPopup($m('DONT_LOOK_WHILE_WALKING'), 10 * 1000);
				popup = true;
				if ($hulop.mobile && $hulop.mobile.getPreference('user_mode') == 'user_blind') {
					$hulop.util.speak($m('DONT_LOOK_WHILE_WALKING'), true);
				}
				return;
			}
		} else {
			button || showButton();
			if (!use_filter()) {
				filter();
				return;
			}
		}
		if (!checkArea([ crd.longitude, crd.latitude ])) {
			return;
		}
		var stop_timer = $hulop.config.SCREEN_FILTER_STOP_TIMER || (start_timer / 2);
		var visible = $('#screen_filter').size() > 0;
		var timer = visible ? stop_timer : start_timer;
		if (last) {
			crd.distance = Math.min($hulop.util.computeDistanceBetween([ crd.longitude, crd.latitude ], [ last.longitude,
					last.latitude ]), 8 * walk_speed * (crd.timestamp - last.timestamp) / 1000);
		} else {
			crd.distance = 0;
		}
		history.push(last = crd);
		var distance = 0
		history = history.filter(function(data) {
			if (data.timestamp + timer * 1000 > crd.timestamp) {
				distance += data.distance;
				return true;
			}
			return false;
		});
		var show = distance > walk_speed * timer;
		if (show != visible) {
			filter(show ? {
				'message' : $m('DONT_LOOK_WHILE_WALKING'),
				'enterLog' : 'startPreventWalking',
				'exitLog' : 'endPreventWalking'
			} : null)
		}
	}

	function showButton() {
		var map = $hulop.map.getMap();
		button = $('<div>', {
			'class' : 'ol-unselectable ol-control TOP',
			'css' : {
				'z-index' : 10000
			}
		});
		a = $('<a>', {
			'href' : '#',
			'class' : 'ui-btn ui-mini ui-shadow ui-corner-all ui-btn-icon-top',
			'css' : {
				'margin' : '0px',
				'width' : '22px'
			},
			'on' : {
				'click' : function(e) {
					e.preventDefault();
					e.target.blur();
					a.toggleClass('ui-icon-forbidden');
					a.toggleClass('ui-icon-alert');
					localStorage.setItem('screen_filter', use_filter());
					use_filter() || showPopup($m('DONT_LOOK_WHILE_WALKING'), 3 * 1000);
				}
			}
		}).appendTo(button);
		a.addClass(localStorage.getItem('screen_filter') == 'false' ? 'ui-icon-forbidden' : 'ui-icon-alert');
		map.addControl(new ol.control.Control({
			'element' : button[0]
		}));
		showPopup($m('DONT_LOOK_WHILE_WALKING'), 10 * 1000);
	}

	function use_filter() {
		return a && a.hasClass('ui-icon-alert');
	}

	function showPopup(text, timeout) {
		$('#popupText').text(text);
		$('#popupDialog').css({
			'z-index' : 10000
		});
		$('#popupDialog').popup('open');
		timeout && setTimeout(function() {
			$('#popupDialog').popup('close');
		}, timeout);
	}

	function isPopupOpen() {
		return $('#popupDialog').parent().hasClass("ui-popup-active");
	}

	function checkArea(loc) {
		let areaList = $hulop.indoor.areaList || [];
		var prevent = false;
		for (i in areaList) {
			let area = areaList[i];
			if (area.properties.hulop_area_height == $hulop.indoor.getCurrentFloor()) {
				let poly = new ol.geom.Polygon(area.geometry.coordinates);
				if (poly.intersectsCoordinate(loc)) {
					if (Number(area.properties.hulop_area_navigation) == 3) {
						var message = area.properties['hulop_area_alert_message:' + $hulop.messages.defaultLang] || area.properties.hulop_area_alert_message;
						filter({
							'message' : message || $m('ALERT_RESTRICTED_AREA'),
							'enterLog' : 'enterRestrictedArea',
							'exitLog' : 'exitRestrictedArea'
						});
						return false;
					} else if (Number(area.properties.hulop_area_prevent_while_walking) == 2) {
						prevent = true;
					}
				}
			}
		}
		prevent || filter();
		return prevent;
	}

	function filter(options) {
		// console.log([ 'filter', options ]);
		if (options) {
			var color = options.color || 'black';
			var opacity = isNaN(options.opacity) ? 1.0 : options.opacity;
			var css = {
				'position' : 'fixed',
				'top' : '0px',
				'left' : '0px',
				'height' : '100%',
				'width' : '100%',
				'z-index' : 9999,
				'background-color' : color,
				'filter' : 'alpha(opacity=' + (opacity * 100) + ')',
				'-moz-opacity' : opacity,
				'opacity' : opacity
			};
			if ($('#screen_filter').size() == 0) {
				$('<div>', {
					'id' : 'screen_filter',
					'on' : {
						'click' : function(event) {
							console.log([ 'click', event ])
							// filter();
						}
					}
				}).appendTo($('body'));
				$hulop.util.logText(options.enterLog + ': ' + (options.message || ''));
				exitLog = options.exitLog;
				if (options.message) {
					$('<div>', {
						'text': options.message,
						'id': 'filter_message',
						'css': {
							'color': '#fff',
							'text-align': 'center',
							'position': 'relative',
							'padding': '1em',
							'top': '50%',
							'left': '50%',
							'transform': 'translate(-50%, -50%)'
						}
					}).appendTo($('#screen_filter'));
				}
			}
			$('#screen_filter').css(css);
			// $('a[href="#control"]').attr('href', '#control_nop')
		} else {
			if ($('#screen_filter').size() > 0) {
				$('#screen_filter').remove();
				$hulop.util.logText(exitLog);
			}
			history = [];
			// $('a[href="#control_nop"]').attr('href', '#control')
		}
	}

	function isRestricted() {
		return $('#screen_filter').size() > 0 && exitLog == 'exitRestrictedArea';
	}

	return {
		'filter' : filter,
		'isRestricted' : isRestricted,
		'onUpdateLocation' : onUpdateLocation
	}
}();