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

function help {
    echo "Usage:"
    echo "  $0 -i <file>  import GeoJSON map data"
    echo "  $0 -e <file>  export GeoJSON map data"
    echo "  $0 -d         delete all map data"
    echo ""
    echo "-h              show this help"
    echo "-f              overwrite output file when exporting"
}

action=
file_path=
force=0

while getopts "hi:e:df" arg; do
    case $arg in
        h)
            help
            exit 0
            ;;
        i)
            file_path=$(realpath "$OPTARG")
            action=import
            ;;
        e)
            file_path=$OPTARG
            action=export
            ;;
        d)
            action=delete-all
            ;;
        f)
            force=1
            ;;
    esac
done
shift $((OPTIND-1))

if [[ -z "$action" ]]; then
    help
    exit 2
fi

HOST=${MAPSERVICE_INTERNAL_URL:-http://map_server:8080/map}
API_KEY=${EDITOR_API_KEY:-local-server-editor-api-key}
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

function route_options {
    curl -fsS "$HOST/api/config" > "$work_dir/config.json"
    local lat lng
    lat=$(jq -r '.INITIAL_LOCATION.lat // 0' "$work_dir/config.json")
    lng=$(jq -r '.INITIAL_LOCATION.lng // 0' "$work_dir/config.json")
    echo "user=script&dist=1000&lat=$lat&lng=$lng&lang=en&cache=false&editor_api_key=$API_KEY"
}

if [[ "$action" = "import" ]]; then
    blue "importing $file_path"
    if [[ ! -e "$file_path" ]]; then
        err "import: $file_path does not exist"
        exit 3
    fi
    jq '.features' "$file_path" > "$work_dir/insert.json"
    curl -fsS -X POST \
        -H "Content-Type: application/x-www-form-urlencoded; charset=utf-8" \
        --data-urlencode "editor_api_key=$API_KEY" \
        --data-urlencode "user=script" \
        --data-urlencode "lang=en" \
        --data-urlencode "action=editdata" \
        --data-urlencode "remove=[]" \
        --data-urlencode "update=[]" \
        --data-urlencode "insert@$work_dir/insert.json" \
        "$HOST/api/editor" > /dev/null
elif [[ "$action" = "export" ]]; then
    blue "exporting MapData.geojson to $file_path"
    if [[ -e "$file_path" && "$force" -eq 0 ]]; then
        err "export: $file_path already exists, use -f to overwrite"
        exit 4
    fi
    mkdir -p "$(dirname "$file_path")"
    options=$(route_options)
    curl -fsS "$HOST/routesearch?action=start&$options" > /dev/null
    curl -fsS "$HOST/routesearch?action=nodemap&$options" | jq '[ .[] ]' > "$work_dir/nodemap.json"
    curl -fsS "$HOST/routesearch?action=features&$options" > "$work_dir/features.json"
    jq -s '. | add' "$work_dir/nodemap.json" "$work_dir/features.json" > "$work_dir/all.json"
    jq --tab '{"type": "FeatureCollection", "features": .}' "$work_dir/all.json" > "$file_path"
elif [[ "$action" = "delete-all" ]]; then
    blue "deleting all map data"
    options=$(route_options)
    curl -fsS "$HOST/routesearch?action=start&$options" > /dev/null
    curl -fsS "$HOST/routesearch?action=nodemap&$options" | jq '[ .[] ]' > "$work_dir/nodemap.json"
    curl -fsS "$HOST/routesearch?action=features&$options" > "$work_dir/features.json"
    jq -s '. | add' "$work_dir/nodemap.json" "$work_dir/features.json" > "$work_dir/remove.json"
    curl -fsS -X POST \
        --data-urlencode "editor_api_key=$API_KEY" \
        --data-urlencode "user=script" \
        --data-urlencode "lang=en" \
        --data-urlencode "action=editdata" \
        --data-urlencode "remove@$work_dir/remove.json" \
        --data-urlencode "update=[]" \
        --data-urlencode "insert=[]" \
        "$HOST/api/editor" > /dev/null
fi
