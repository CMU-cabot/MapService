#!/bin/bash
set -e

HOST_UID=${HOST_UID:-1000}
HOST_GID=${HOST_GID:-1000}
HOST_TZ=${HOST_TZ:-Etc/UTC}
USERNAME=${USERNAME:-runner_user}

if [[ ${TZ:-} != "$HOST_TZ" ]]; then
    ln -snf /usr/share/zoneinfo/$HOST_TZ /etc/localtime
    echo "$HOST_TZ" > /etc/timezone
    export TZ=$HOST_TZ
fi

CONT_UID=$(id -u "$USERNAME")
CONT_GID=$(id -g "$USERNAME")
if [[ "$CONT_GID" -ne "$HOST_GID" ]]; then
    groupmod -g "$HOST_GID" "$USERNAME"
fi
if [[ "$CONT_UID" -ne "$HOST_UID" ]]; then
    usermod -u "$HOST_UID" "$USERNAME"
fi

exec gosu "$USERNAME" "$@"
