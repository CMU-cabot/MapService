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

## Local macOS workflow

This branch includes a simplified local workflow for macOS development under a `cabot-servers/` workspace.

- Initial setup for MapService + QueryService:
  - `./setup-for-mac.sh`
- Launch MapService + QueryService:
  - `./launch-for-mac.sh`
- Stop MapService + QueryService:
  - `./stop-for-mac.sh`

`./launch-for-mac.sh` automatically prepares the local Open Liberty runtime, including the `server.xml` and `server.env` values needed for macOS testing.
`cabot-app-server` should be set up and launched separately from `../cabot-app-server`. See [MAC_DEV.md](MAC_DEV.md) for the iPhone-facing port publish command used in this branch's local integration flow.

See [MAC_DEV.md](MAC_DEV.md) for the recommended setup and launch flow.

Legacy scripts are still present for compatibility:

- `./start-cabot-stack.sh`
- `./stop-cabot-stack.sh`

-----

## About
[About HULOP](https://github.com/hulop/00Readme)

## License
[MIT](http://opensource.org/licenses/MIT)
