<!--
The MIT License (MIT)

Copyright (c) 2014, 2017 IBM Corporation
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
-->

# Setting up the MapService Server

## Requirements

Before you get started, make sure you have the following dependencies installed on your machine:

- [Eclipse IDE for Java EE Developers Mars2 or later](http://www.eclipse.org/downloads/packages/eclipse-ide-java-ee-developers/mars2)
- (Optional) Component from Eclipse Marketplace
  - IBM Eclipse Toolkit for Bluemix
  - IBM WebSphere Application Server Liberty Developer Tools
- (Optional) [Cloud Foundry cf command](https://console.ng.bluemix.net/docs/cli/reference/cfcommands/index.html)

## Deploy MapService Server

After you setup Eclipse, you have the following options to deploy server.

1. Export WAR file. Then execute `cf push your_app_name -p war_file_name`

2. Define IBM Bluemix Server. Then push MapService app to Bluemix

3. Setup your own J2EE Container (WAS Liberty recommneended). Then deploy server manually.
 - https://developer.ibm.com/wasdev/

After you deploy MapService Server, you need to add Cloudant NoSQL DB service
- For Bluemix, see https://console.ng.bluemix.net/catalog/services/cloudant-nosql-db/
- If you are not using Bluemix, you need to provide `VCAP_SERVICES` Environment variable

You need to add the following environment variable for administoration instances
- ENABLE_MAP_ACCESS=admin,auditor,editor

## Administration

Open https://`your_app_name`.mybluemix.net/admin.jsp

Please login with initial administrator account:
- User id : `hulopadmin`
- Password: `please change password`

**Important:** Please change admin password NOW!

You also need to create new editor account.

## Setting Indoor Floor Map (Optional)

Create Attachment zip file with the following files:

- map/floormaps.json file
```
[
	{
		"id": "<id of floor map>",
		"image": "<url of floor plan image>",
		"floor": <floor number>,
		"origin_x": <origin x>,
		"origin_y": <origin y>,
		"width": <width of image>,
		"height": <height of image>,
		"ppm_x": <pixels per meter x>,
		"ppm_y": <pixels per meter y>,
		"lat": <latitude>,
		"lng": <longitude>,
		"rotate": <rotate angle>
	},
	
	{
	    ... 
	},
	
	{
	    ... 
	}
]
```

- floor plan image files.

Import Attachment zip file using Administration console


## Edit Network Route

Open https://`your_app_name`.mybluemix.net/editor.jsp

Please login with editor account.

## Testing Navigation

Use https://`your_app_name`.mybluemix.net/mobile.jsp?id=`your_unique_device_id`

## Setting OpenLayers Tile Map Service

Open Street Map (OSM) is used for Tile Map Service by default. You can OSM for initial test. 

However, heavy use of OSM is prohibited. See https://operations.osmfoundation.org/policies/tiles/ 

You need to provide your Tile Map Service via Environment Variable as follows:

HULOP\_TILE\_SERVER
- URL of tile service.
- Example: `https://{a-c}.tile.example.com/{z}/{x}/{y}.png?apikey=your_api_key`

HULOP\_TILE\_ATTR
- Attribution information.
- Example: `Maps © <a href="http://www.example.com">Example</a>, Data © <a href="http://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>`

## Setting Optional Environment Variables
ENABLE\_MAP\_ACCESS=admin,auditor,editor (no default)
- access right for map DB

SUPPORT\_AGREEMENT=true (default: false)
- agreement is supported at initial use

SUPPORT\_QUESTION=true (default: false)
- question is supported at initial use

EDITOR\_API\_KEY=key (no default)
- API key for map edit API

AUDITOR\_API\_KEY=key (no default)
- API key for logging API

ESCALATOR\_WEIGHT=weight (default: 100)
- weight for escalator route

STAIR\_WEIGHT=weight (default: 300)
- weight for stair route

ELEVATOR\_WEIGHT=weight (default: 300)
- weight for elavator route

MAX\_START\_PARAMS=count (default: 10000)
- max cache count for user location

MAX\_ROUTE\_BEANS=count (default: 1000)
- max cache count for route data

HULOP\_VCAP\_SERVICES=json (default: VCAP\_SERVICE json)
- use alternative db credential

HULOP\_NAVI\_DB=name (default: navi_db)
- db name for MongoDB

HULOP\_INITIAL\_LOCATION={ "lat": latitude, "lng": longitude, "floor":floor } (default: COREDO Muromachi, no floor)
- initial map location

HULOP\_MAX\_RADIUS=radius (default: 500)
- max radius for loading route data

HULOP\_DEFAULT\_SHOW\_LABELS\_ZOOMLEVEL=level (no default)
- show facility name when map zoom level >= level

HULOP\_INITIAL\_ROTATION\_MODE=mode (default: 1)
- initial map rotation mode (0: north up, 1: head up, 2: route up)

HULOP\_DO\_NOT\_USE\_SAVED\_CENTER=true (default: false)
- do not restore last map location
