#!/bin/bash
set -euo pipefail

function err {
    >&2 red "[ERROR] $*"
}

function red {
    echo -en "\033[31m"
    echo "$@"
    echo -en "\033[0m"
}

function blue {
    echo -en "\033[36m"
    echo "$@"
    echo -en "\033[0m"
}

function snore {
    local IFS
    [[ -n "${_snore_fd:-}" ]] || exec {_snore_fd}<> <(:)
    read ${1:+-t "$1"} -u $_snore_fd || :
}

HOST=${MAPSERVICE_INTERNAL_URL:-http://map_server:8080/map}
SERVER_DATA_DIR=${SERVER_DATA_DIR:-/server_data}
MAP_DATA_FILE=${MAP_DATA_FILE:-/map_data/MapData.geojson}
ADMIN_USER=${MAPSERVICE_ADMIN_USER:-hulopadmin}
ADMIN_PASSWORD=${MAPSERVICE_ADMIN_PASSWORD:-please change password}
EDITOR_USER=${MAPSERVICE_EDITOR_USER:-editor}
EDITOR_PASSWORD=${MAPSERVICE_EDITOR_PASSWORD:-editor}

work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

function compute_content_md5 {
    local lines="$work_dir/content-md5-lines"
    : > "$lines"
    if [[ -d "$SERVER_DATA_DIR" ]]; then
        (
            cd "$SERVER_DATA_DIR"
            find . -type f ! -name 'content-md5' ! -name 'attachments.zip' -print0 |
                LC_ALL=C sort -z |
                while IFS= read -r -d '' path; do
                    if [[ "$path" = "./MapData.geojson" && -e "$MAP_DATA_FILE" && "$SERVER_DATA_DIR/MapData.geojson" -ef "$MAP_DATA_FILE" ]]; then
                        continue
                    fi
                    md5sum "$path" | awk -v p="${path#./}" '{print $1 "  " p}'
                done
        ) >> "$lines"
    fi
    if [[ -f "$MAP_DATA_FILE" ]]; then
        if [[ ! -e "$SERVER_DATA_DIR/MapData.geojson" || ! "$SERVER_DATA_DIR/MapData.geojson" -ef "$MAP_DATA_FILE" ]]; then
            md5sum "$MAP_DATA_FILE" | awk '{print $1 "  MapData.geojson"}' >> "$lines"
        fi
    fi
    LC_ALL=C sort -k 2 "$lines" | md5sum
}

function build_attachments_zip {
    if [[ -f "$SERVER_DATA_DIR/attachments.zip" ]]; then
        cp "$SERVER_DATA_DIR/attachments.zip" "$work_dir/attachments.zip"
        return
    fi

    local source_dir="$SERVER_DATA_DIR"
    if [[ -d "$SERVER_DATA_DIR/attachments" ]]; then
        source_dir="$SERVER_DATA_DIR/attachments"
    fi

    mkdir -p "$work_dir/attachments"
    if [[ -d "$source_dir" ]]; then
        (
            cd "$source_dir"
            find . -type f ! -name 'content-md5' ! -name 'attachments.zip' ! -name 'MapData.geojson' ! -name 'server.env' -print0 |
                while IFS= read -r -d '' path; do
                    mkdir -p "$work_dir/attachments/$(dirname "$path")"
                    cp "$path" "$work_dir/attachments/$path"
                done
        )
    fi

    compute_content_md5 > "$work_dir/attachments/content-md5"
    (
        cd "$work_dir/attachments"
        zip -qr "$work_dir/attachments.zip" .
    )
}

count=0
echo "waiting server is up"
while [[ "$(curl -I "$HOST/login.jsp" 2>/dev/null | head -n 1 | cut -d' ' -f2)" != "200" ]]; do
    snore 1
    echo "waiting server is up ($count)"
    count=$((count + 1))
done

blue "adding editor user"
curl -fsS -b "$work_dir/admin-cookie.txt" -c "$work_dir/admin-cookie.txt" "$HOST/admin.jsp" > /dev/null || true
curl -fsS -b "$work_dir/admin-cookie.txt" -c "$work_dir/admin-cookie.txt" \
    --data-urlencode "redirect_url=admin.jsp" \
    --data-urlencode "user=$ADMIN_USER" \
    --data-urlencode "password=$ADMIN_PASSWORD" \
    "$HOST/login.jsp" > /dev/null
curl -fsS -b "$work_dir/admin-cookie.txt" -c "$work_dir/admin-cookie.txt" \
    --data-urlencode "user=$EDITOR_USER" \
    --data-urlencode "password=$EDITOR_PASSWORD" \
    --data-urlencode "password2=$EDITOR_PASSWORD" \
    --data-urlencode "role=editor" \
    --data-urlencode "role=auditor" \
    "$HOST/api/user?action=add-user" > /dev/null

blue "importing attachments.zip"
build_attachments_zip
curl -fsS -b "$work_dir/admin-cookie.txt" -c "$work_dir/admin-cookie.txt" \
    -F "file=@$work_dir/attachments.zip" \
    "$HOST/api/admin?action=import&type=attachment.zip" > /dev/null

if [[ -f "$MAP_DATA_FILE" ]]; then
    blue "importing $MAP_DATA_FILE"
    /home/runner_user/server-data.sh -i "$MAP_DATA_FILE"
else
    err "$MAP_DATA_FILE does not exist; skipping map data import"
fi
