#!/bin/bash
chown -R root:root /data/db
chmod -R 777 /data/db
exec "$@"