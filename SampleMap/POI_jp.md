# Point of Interest (POI)の定義
POIは、特定の場所や地点に関する情報を示すもので、AIスーツケースにおいてユーザーが目的地や重要な地点を識別するために使用されます。CMU-CabotのMapServiceでは、POIはGeoJSON形式で定義されています。

### GeoJSON形式のPOI
GeoJSONは、地理空間データを表現するためのフォーマットで、JSON（JavaScript Object Notation）を基にしています。POIをGeoJSONで定義する際には、以下のような構造を持ちます。

```
{
  "type": "Feature",
  "geometry": {
    "type": "Point",
    "coordinates": [経度, 緯度]
  },
  "properties": {
    "name": "POIの名前",
    "description": "POIの説明",
    "category": "POIのカテゴリ"
  }
}
```

### CMU-CabotでのPOIの使用方法
CMU-Cabotのナビゲーションシステムでは、POIはユーザーが特定の場所に移動する際の目的地として使用されます。
サンプルのGeoJSONデータは以下のリンクから確認できます。ヴィジュアルとコードの切り替えも可能です。この中で![](figure1.png)このマークがPOIです。
[MapData-sample.geojson](MapData-sample.geojson)

一番下にあるPOIは以下のように定義されます。

```
{
	"type": "Feature",
	"geometry": {
		"type": "Point",
		"coordinates": [
			-0.00013880431652069092,
			-0.0002393871545791626
		]
	},
	"properties": {
		"出入口の名称:ja": "エントランス",
		"file": "EDITOR",
		"対応施設ID": "EDITOR_poi_1486112708102",
		"category": "出入口情報",
		"出入口ID": "EDITOR_exit_1486113974599",
		"対応ノードID": "EDITOR_node_1486112656238",
		"出入口の名称": "Entrance"
	},
	"_id": "EDITOR_exit_1486113974599"
},
```

# API 

## search アクション
ユーザーが提供したfromおよびtoパラメータを使用してルート検索を行います。


#### リクエスト例
```
action: search
preferences: {
	"dist":"4000",
	"preset":"1",
	"min_width":"9",
	"slope":"9",
	"road_condition":"9",
	"stairs":"9",
	"deff_LV":"9",
	"esc":"9",
	"mvw":"9",
	"elv":"9"
}
from: latlng:35.************:139.************:-1
to: EDITOR_node_*************
user: ******
lang: ja
```

#### パラメータの説明
- `action`: 実行するアクション名（この場合は`search`）。
- `from`: 出発地点の情報。
  - `latlng`: 緯度、経度、フロア（例：`35.6895:139.6917:-1`）。
- `to`: 目的地のノード（例：`EDITOR_node_12345`）。
- `preferences`: 経路検索におけるユーザーの好み。
  - `dist`: 距離の制限（例：`4000`）。
  - `preset`: プリセット設定（例：`1`）。
  - `min_width`: 最小幅（例：`9`）。
  - `slope`: 坂道の傾斜（例：`9`）。
  - `road_condition`: 道路の状態（例：`9`）。
  - `stairs`: 階段の使用（例：`9`）。
  - `deff_LV`: バリアフリーのレベル（例：`9`）。
  - `esc`: エスカレーターの使用（例：`9`）。
  - `mvw`: 移動歩道の使用（例：`9`）。
  - `elv`: エレベーターの使用（例：`9`）。
- `user`: ユーザーID（例：`user_abc`）。
- `lang`: 言語設定（例：`ja`）。

#### レスポンス例
[route-sample.json](route-sample.json)に示す。

## landmarks アクション
指定された緯度と経度の距離内のランドマークを取得します。

#### リクエスト例
```json
{
  "action": "landmarks",
  "lat": 35.6895,
  "lng": 139.6917,
  "radius": 500,
  "lang": "ja"
}
```

#### パラメータの説明
- `action`: 実行するアクション名（この場合は`landmarks`）。
- `lat`: 緯度（例：35.6895）。
- `lng`: 経度（例：139.6917）。
- `radius`: 検索する半径（メートル単位、例：500）。
- `lang`: 言語設定（例：`ja`）。

#### レスポンス例

[route-sample.json](route-sample.json)に示す。
