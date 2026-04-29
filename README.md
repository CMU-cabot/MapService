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


# MapService

MapService is a server-side component that provides Map related services for [NavCogIOSv3](https://github.com/hulop/NavCogIOSv3).
Please import 2 projects (MapService and SampleMap) by using Eclipse IDE for Java EE Developers Mars2 or later.

Please visit *MapService* folder for more details about MapService application.
*SampleMap* folder contains sample map and GeoJSON data.

## Docker Launch

You can launch MapService with MongoDB and the bundled sample map data:

```
./server-launch.sh
```

The server is published at:

- `http://localhost:9090/map/mobile.jsp`
- `http://localhost:9090/map/admin.jsp`

The initial administrator account is:

- user: `hulopadmin`
- password: `please change password`

Stop and remove the containers with:

```
./server-launch.sh -C
```

The launch script accepts a data directory and a GeoJSON map file:

```
./server-launch.sh -D SampleMap/FilesForMapService -m SampleMap/MapData-sample.geojson
```

For CaBot site-package style data, use `-p <site>` and set `CABOT_SITE_PKG_DIR`
if the package directory is not `./cabot_site_pkg`. The script searches for
`*/<site>/server_data`, sources `server.env` when present, imports
`MapData.geojson`, and imports either `attachments.zip`, `attachments/`, or the
other files in the server data directory as MapService attachments.

Useful options:

- `-v`: follow Docker Compose logs after startup.
- `-c`: clean and relaunch if the running server content differs.
- `-C`: export current map data to `.tmp/` when possible, then stop containers.
- `-E <n>`: run a separate environment by shifting the port by `n * 10`.

-----

## About
[About HULOP](https://github.com/hulop/00Readme)

## License
[MIT](http://opensource.org/licenses/MIT)
