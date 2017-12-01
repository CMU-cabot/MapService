$('#export_button_2017').html() || $('#export_button').after([ ' | ', $('<button>', {
	'id' : 'export_button_2017',
	'text' : 'Export 2017',
	'on' : {
		'click' : function(event) {
			var v2 = $hulop.editor.exportV2();
			$hulop.editor.downloadFile(v2, 'SpatialNetwork2017.geojson');
		}
	}
}) ]);

$hulop.editor.exportV2 = function() {
	var v1features = JSON.parse($hulop.editor.toFeatureCollection()).features;

	// create index
	var nodeMap = {}, entranceMap = {};
	v1features.forEach(function(feature) {
		var fp = feature.properties;
		if (fp) {
			var id;
			(id = fp['ノードID']) && (nodeMap[id] = feature);
			(id = fp['対応施設ID']) && (entranceMap[id] || (entranceMap[id] = [])).push(feature);
		}
	});

	return JSON.stringify({
		'type' : 'FeatureCollection',
		'features' : convert(v1features)
	}, null, '\t');

	/*
	 * convert from v1 to v2
	 */
	function convert(v1) {
		console.log('==== v1 ====');
		console.log(v1);
		var v2 = [];
		v1.forEach(function(feature) {
			var fp = feature.properties;
			if (fp) {
				var newFeature;
				if (fp['リンクID']) {
					newFeature = convertLink(feature);
				} else if (fp['ノードID']) {
					newFeature = convertNode(feature);
				} else if (fp['施設ID']) {
					newFeature = convertFacility(feature);
					newFeature && (entranceMap[fp['施設ID']] || []).forEach(function(entrance, i) {
						addEntrance(newFeature, entrance, i + 1);
					});
				}
				if (newFeature) {
					v2.push(newFeature);
					console.log(newFeature.properties);
				}
			}
		});
		return v2;
	}

	/*
	 * convert link feature
	 */
	function convertLink(feature) {
		var fp = feature.properties;
		var tp = {};

		for ( var name in fp) {
			var value = fp[name];
			switch (name) {
			case 'リンクID':
				set(tp, 'link_id', value);
				break;
			case '起点ノードID':
				set(tp, 'start_id', value);
				break;
			case '終点ノードID':
				set(tp, 'end_id', value);
				break;
			case 'リンク延長':
				set(tp, 'distance', Number(value));
				break;
			case '経路の種類':
				var struct = 99;
				var type = Number(value);
				switch (type) {
				case 1: // 歩道
				case 2: // 歩行者専用道路
				case 3: // 園路
				case 8: // 自由通路
					struct = 1;
					break;
				case 4: // 歩車共存道路
					struct = 2;
					break;
				case 5: // 横断歩道
					struct = 3;
					break;
				case 6: // 横断歩道の路面標示の無い交差点の道路
					struct = 4;
					break;
				}
				switch (type) {
				case 7: // 動く歩道
					type = 1;
					break;
				case 9: // 踏切
					type = 2;
					break;
				case 10: // エレベーター
					type = 3;
					break;
				case 11: // エスカレーター
					type = 4;
					break;
				case 12: // 階段
					type = 5;
					break;
				case 13: // スロープ
					type = 6;
					break;
				case 99:
					break;
				default:
					type = 0;
					break;
				}
				set(tp, 'rt_struct', struct);
				set(tp, 'route_type', type);
				break;
			case '方向性':
				set(tp, 'direction', Code(value));
				break;
			case '段差':
				switch (value = Code(value)){
				case 2: // 5～10cm
				case 3: // 10cm 以上
					value = 1;
					break;
				}
				set(tp, 'lev_diff', value);
				break;
			case '信号の有無':
				set(tp, 'tfc_signal', Code(value));
				break;
			case '信号種別':
				set(tp, 'tfc_s_type', Code(value));
				break;
			case 'エスコートゾーン':
			case '視覚障害者誘導用ブロック':
				if (value == '1') {
					set(tp, 'brail_tile', 1);
				}
				break;
			case 'エレベーター種別':
				switch (value = Code(value)) {
				case 0: // 障害対応なし
					value = 1;
					break;
				case 1: // 点字・音声あり
				case 2: // 車イス対応
				case 3: // 1・2 両方
					value = 2;
					break;
				}
				set(tp, 'elevator', value);
				break;
			case '供用開始時間':
				set(tp, 'start_time', value);
				break;
			case '供用終了時間':
				set(tp, 'end_time', value);
				break;
			case '供用開始日':
				set(tp, 'start_date', value);
				break;
			case '供用終了日':
				set(tp, 'end_date', value);
				break;
			case '供用制限曜日':
				set(tp, 'no_serv_d', value);
				break;
			case '通行制限':
				set(tp, 'tfc_restr', Code(value));
				break;
			case '有効幅員':
				var w_min;
				switch (value = Code(value)) {
				case 0: // 1.0m 未満
					w_min = 0.9;
					break;
				case 1: // 1m 以上 1.5m 未満
					w_min = 1.4;
					break;
				case 2: // 1.5m 以上 2.0m 未満
					w_min = 1.9;
					value = 1;
					break;
				case 3: // 2.0m 以上
					w_min = 2.0;
					value = 2;
					break;
				}
				set(tp, 'width', value);
				set(tp, 'w_min', w_min);
				break;
			case '有効幅員緯度':
				set(tp, 'w_min_lat', DMS(value));
				break;
			case '有効幅員経度':
				set(tp, 'w_min_lon', DMS(value));
				break;
			case '縦断勾配1':
				set(tp, 'vSlope_max', value = Number(value));
				var vtcl_slope = isNaN(value) ? 99 : value < 5 ? 0 : 1;
				set(tp, 'vtcl_slope', vtcl_slope);
				break;
			case '縦断勾配1緯度':
				set(tp, 'vSlope_lat', DMS(value));
				break;
			case '縦断勾配1経度':
				set(tp, 'vSlope_lon', DMS(value));
				break;
			case '横断勾配':
				set(tp, 'hSlope_max', Number(value));
				break;
			case '横断勾配緯度':
				set(tp, 'hSlope_lat', DMS(value));
				break;
			case '横断勾配経度':
				set(tp, 'hSlope_lon', DMS(value));
				break;
			case '路面状況':
				set(tp, 'condition', Code(value));
				break;
			case '段差':
				set(tp, 'levDif_max', Number(value));
				break;
			case '段差緯度':
				set(tp, 'levDif_lat', DMS(value));
				break;
			case '段差経度':
				set(tp, 'levDif_lon', DMS(value));
				break;
			case '最大階段段数':
			case '最小階段段数':
				set(tp, 'stair', Number(value));
				break;
			case '手すり':
				set(tp, 'handrail', Code(value));
				break;
			case '屋根の有無':
				set(tp, 'roof', Code(value));
				break;
			case '蓋のない溝や水路の有無':
				set(tp, 'waterway', Code(value));
				break;
			case 'バス停の有無':
				set(tp, 'bus_stop', Code(value));
				break;
			case 'バス停の緯度':
				set(tp, 'bus_s_lat', DMS(value));
				break;
			case 'バス停の経度':
				set(tp, 'bus_s_lon', DMS(value));
				break;
			case '補助施設の設置状況':
				set(tp, 'facility', Code(value));
				break;
			case '補助施設の緯度':
				set(tp, 'facil_lat', DMS(value));
				break;
			case '補助施設の経度':
				set(tp, 'facil_lon', DMS(value));
				break;
			case 'エレベーターの緯度':
				set(tp, 'elev_lat', DMS(value));
				break;
			case 'エレベーターの経度':
				set(tp, 'elev_lon', DMS(value));
				break;
			case '信号の緯度':
				set(tp, 'tfc_s_lat', DMS(value));
				break;
			case '信号の経度':
				set(tp, 'tfc_s_lon', DMS(value));
				break;
			case '日交通量':
				set(tp, 'day_trfc', Number(value));
				break;
			case '主な利用者':
				set(tp, 'main_user', Code(value));
				break;
			case '通り名称または交差点名称':
				set(tp, 'st_name', value);
				break;
			case 'road_low_priority':
				set(tp, 'hulop_' + name, Number(value));
				break;
			case 'elevator_equipments':
				set(tp, 'hulop_' + name, value);
				break;
			case 'file':
			case 'category':
			case '縦断勾配2':
				break;
			default:
				console.error(name + '=' + value);
				break;
			}
		}

		return {
			'type' : 'Feature',
			'geometry' : feature.geometry,
			'properties' : tp,
		}
	}

	/*
	 * convert node feature
	 */
	function convertNode(feature) {
		var fp = feature.properties;
		var tp = {};

		for ( var name in fp) {
			var value = fp[name];
			switch (name) {
			case 'ノードID':
				set(tp, 'node_id', value);
				break;
			case '緯度':
				set(tp, 'lat', DMS(value));
				break;
			case '経度':
				set(tp, 'lon', DMS(value));
				break;
			case '高さ':
				set(tp, 'floor', Number(value));
				break;
			case 'file':
			case 'category':
			case '緯度経度桁数コード':
				break;
			default:
				var num = name.replace(/^接続リンクID(\d+)$/, '$1');
				if (!isNaN(num)) {
					set(tp, 'link' + num + '_id', value);
					break;
				}
				console.error(name + '=' + value);
				break;
			}
		}

		return {
			'type' : 'Feature',
			'geometry' : feature.geometry,
			'properties' : tp,
		}
	}

	/*
	 * convert facility feature
	 */
	function convertFacility(feature) {
		var fp = feature.properties;
		var tp = {};
		var facil_type, evacuation;
		var toilet = Code(fp['多目的トイレ']);
		var name_ja = fp['名称'];
		var name_en = fp['名称'];

		for ( var name in fp) {
			var value = fp[name];
			switch (name) {
			case '施設ID':
				set(tp, 'facil_id', value);
				break;
			case '緯度':
				set(tp, 'lat', DMS(value));
				break;
			case '経度':
				set(tp, 'lon', DMS(value));
				break;
			case 'category':
				switch (value) {
				case '公共用トイレの情報':
					facil_type = 10;
					break;
				case '病院の情報':
					facil_type = 3;
					break;
				case '指定避難場所の情報':
					evacuation = 2;
					break;
				default:
					break;
				}
				break;
			case '所在地':
				set(tp, 'address', value);
				break;
			case '電話番号':
				set(tp, 'tel', value);
				break;
			case 'ベビーベッド':
				if (value == '1') {
					toilet = toilet == 2 ? 4 : 3;
				}
				break;
			case '階層':
				set(tp, 'floors', Number(value));
				break;
			case '名称:ja':
				name_ja = value;
				break;
			case '名称:en':
				name_en = value;
				break;
			case '名称:ja-Pron':
				set(tp, 'name_hira', value);
				break;
			case '名称:es':
				set(tp, 'hulop_name_es', value);
				break;
			case '名称:fr':
				set(tp, 'hulop_name_fr', value);
				break;
			case '供用開始時間':
				set(tp, 'start_time', value);
				break;
			case '供用終了時間':
				set(tp, 'end_time', value);
				break;
			case '供用制限曜日':
				set(tp, 'no_serv_d', value);
				break;
			case '男女別':
				set(tp, 'sex', Code(value));
				break;
			case '有料無料の別':
				set(tp, 'fee', Code(value));
				break;
			case '診療科目':
				set(tp, 'subject', Code(value));
				break;
			case '休診日':
				set(tp, 'close_day', value);
				break;
			case '地区名':
				set(tp, 'med_dept', value);
				break;
			case '風水害対応':
				set(tp, 'flood', Code(value));
				break;
			case 'building':
			case 'major_category':
			case 'sub_category':
			case 'minor_category':
			case 'long_description':
			case 'long_description:en':
			case 'long_description:ja':
				set(tp, 'hulop_' + name, value);
				break;
			case 'heading':
			case 'angle':
			case 'height':
				set(tp, 'hulop_' + name, Number(value));
				break;
			case 'file':
			case '緯度経度桁数コード':
			case '名称':
			case '多目的トイレ':
			case '施設種別':
				break;
			default:
				console.error(name + '=' + value);
				break;
			}
		}

		set(tp, 'facil_type', facil_type);
		set(tp, 'evacuation', evacuation);
		set(tp, 'toilet', toilet);
		set(tp, 'name_ja', name_ja);
		set(tp, 'name_en', name_en);

		return {
			'type' : 'Feature',
			'geometry' : feature.geometry,
			'properties' : tp,
		}
	}

	/*
	 * add entrance properties to facility feature
	 */
	function addEntrance(feature, entrance, index) {
		var fp = feature.properties;
		var brr = 99;
		switch (entrance.properties['段差']) {
		case '0':
			brr = 1;
			break;
		case '1':
		case '2':
		case '3':
			brr = 0;
			break;
		}
		set(fp, 'ent' + index + '_n', entrance.properties['出入口の名称']);
		set(fp, 'ent' + index + '_w', Number(entrance.properties['出入口の有効幅員']));
		set(fp, 'ent' + index + '_d', Code(entrance.properties['扉の種類']));
		set(fp, 'ent' + index + '_brr', brr);
		var node = nodeMap[entrance.properties['対応ノードID']];
		if (node) {
			set(fp, 'ent' + index + '_lat', DMS(node.properties['緯度']));
			set(fp, 'ent' + index + '_lon', DMS(node.properties['経度']));
			set(fp, 'ent' + index + '_fl', Number(node.properties['高さ']));
			set(fp, 'hulop_ent' + index + '_node', node.properties['ノードID']);
		}
	}
	
	/*
	 * Utility functions
	 */
	function set(properties, key, value) {
		switch (typeof value) {
		case 'string':
			value.length == 0 || (properties[key] = value);
			break;
		case 'number':
			isNaN(value) || (properties[key] = value);
			break;
		}
	}
	
	function Code(value) {
		return value == '9' ? 99 : Number(value);
	}

	function DMS(value) {
		var m = /(-?)(\d+)\.(\d+)\.(.*)/.exec(value);
		return m && (m[1] == '-' ? -1 : 1) * (Number(m[2]) + (Number(m[3]) / 60) + (Number(m[4]) / 3600));
	}

};

console.log('OK!');
