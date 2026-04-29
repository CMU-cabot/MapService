#!/bin/bash
set -euo pipefail

trap ctrl_c INT TERM

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
    echo "  $0 [options]"
    echo ""
    echo "-h              show this help"
    echo "-p <site>       use <site>/server_data under CABOT_SITE_PKG_DIR"
    echo "-d              use ./cabot_sites instead of ./cabot_site_pkg with -p"
    echo "-D <dir>        attachment/server data directory"
    echo "-m <file>       GeoJSON map data file"
    echo "-E <1-10>       separate environment; shifts MAP_SERVER_PORT by E*10"
    echo "-f              ignore missing optional data errors"
    echo "-v              follow logs after launch"
    echo "-c              clean and relaunch when content-md5 differs"
    echo "-C              force clean containers and exit"
}

function snore {
    local IFS
    [[ -n "${_snore_fd:-}" ]] || exec {_snore_fd}<> <(:)
    read ${1:+-t "$1"} -u $_snore_fd || :
}

function compose {
    docker compose -p "$launch_prefix" --profile map "$@"
}

function backup_mapdata {
    if [[ -z "$(docker ps -f "name=${launch_prefix}-map_server" -q)" ]]; then
        return
    fi
    local log_name="MapData-$(date +%Y-%m-%d-%H-%M-%S).geojson"
    mkdir -p "$temp_dir"
    blue "saving map data to .tmp/$log_name"
    compose run --rm --no-deps map_data /home/runner_user/server-data.sh -e "/exports/$log_name" -f || true
}

function ctrl_c {
    blue "exit script is hooked"
    backup_mapdata
    cd "$scriptdir"
    compose down
    exit
}

function content_md5 {
    local lines="$temp_dir/content-md5-lines"
    : > "$lines"
    if [[ -d "$data_dir" ]]; then
        (
            cd "$data_dir"
            find . -type f ! -name 'content-md5' ! -name 'attachments.zip' -print0 |
                LC_ALL=C sort -z |
                while IFS= read -r -d '' path; do
                    if [[ "$path" = "./MapData.geojson" && -e "$map_data" && "$data_dir/MapData.geojson" -ef "$map_data" ]]; then
                        continue
                    fi
                    md5sum "$path" | awk -v p="${path#./}" '{print $1 "  " p}'
                done
        ) >> "$lines"
    fi
    if [[ -f "$map_data" ]]; then
        if [[ ! -e "$data_dir/MapData.geojson" || ! "$data_dir/MapData.geojson" -ef "$map_data" ]]; then
            md5sum "$map_data" | awk '{print $1 "  MapData.geojson"}' >> "$lines"
        fi
    fi
    LC_ALL=C sort -k 2 "$lines" | md5sum
}

function check_server {
    local server="http://localhost:${MAP_SERVER_PORT}/map"
    mkdir -p "$temp_dir"
    if ! curl "$server/content-md5" --fail > "$temp_dir/${launch_prefix}-content-md5" 2> /dev/null; then
        blue "There is no server or server data cannot be checked."
        return 1
    fi

    local md5sum
    md5sum=$(content_md5)
    blue "md5sum - $md5sum"
    blue "server - $(cat "$temp_dir/${launch_prefix}-content-md5")"
    if [[ "$(cat "$temp_dir/${launch_prefix}-content-md5")" = "$md5sum" ]]; then
        blue "md5 matched, do not relaunch server"
        return 0
    fi
    return 2
}

if [[ -e .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
fi

pwd=$(pwd)
scriptdir=$(dirname "$0")
cd "$scriptdir"
scriptdir=$(pwd)
cd "$pwd"

development=0
ignore_error=0
verbose=0
clean_server=0
environment=
data_dir=${MAPSERVICE_DATA_DIR:-}
map_data=${MAPSERVICE_MAP_DATA:-}
CABOT_SITE=${CABOT_SITE:-}
: "${MAP_SERVER_PORT:=9090}"

while getopts "hp:dD:m:E:fvcC" arg; do
    case $arg in
        h)
            help
            exit 0
            ;;
        p)
            CABOT_SITE=$OPTARG
            ;;
        d)
            development=1
            ;;
        D)
            data_dir=$OPTARG
            ;;
        m)
            map_data=$OPTARG
            ;;
        E)
            environment=$OPTARG
            ;;
        f)
            ignore_error=1
            ;;
        v)
            verbose=1
            ;;
        c)
            clean_server=1
            ;;
        C)
            clean_server=2
            ;;
    esac
done
shift $((OPTIND-1))

if [[ -n "$CABOT_SITE" && -z "$data_dir" ]]; then
    if [[ "$development" -eq 1 ]]; then
        CABOT_SITE_PKG_DIR=${CABOT_SITE_PKG_DIR:-$scriptdir/cabot_sites}
    else
        CABOT_SITE_PKG_DIR=${CABOT_SITE_PKG_DIR:-$scriptdir/cabot_site_pkg}
    fi
    blue "finding $CABOT_SITE in $CABOT_SITE_PKG_DIR"
    data_dir=$(find "$CABOT_SITE_PKG_DIR" -wholename "*/$CABOT_SITE/server_data" | head -1 || true)
fi

if [[ -z "$data_dir" ]]; then
    data_dir="$scriptdir/SampleMap/FilesForMapService"
fi
if [[ -z "$map_data" ]]; then
    if [[ -f "$data_dir/MapData.geojson" ]]; then
        map_data="$data_dir/MapData.geojson"
    else
        map_data="$scriptdir/SampleMap/MapData-sample.geojson"
    fi
fi

data_dir=$(realpath -m "$data_dir")
map_data=$(realpath -m "$map_data")
temp_dir="$scriptdir/.tmp"
mkdir -p "$temp_dir"

launch_prefix=$(basename "$scriptdir")
launch_prefix=${launch_prefix,,}
launch_prefix=${launch_prefix//[^a-z0-9_-]/-}
if [[ ! "$launch_prefix" =~ ^[a-z0-9] ]]; then
    launch_prefix="mapservice-${launch_prefix}"
fi
if [[ -n "$environment" ]]; then
    MAP_SERVER_PORT=$((MAP_SERVER_PORT + environment * 10))
    launch_prefix="${launch_prefix}-env${environment}"
fi

if [[ "$clean_server" -eq 2 ]]; then
    blue "Clean servers"
    backup_mapdata
    compose down --remove-orphans
    exit 0
fi

if [[ ! -d "$data_dir" ]]; then
    err "$data_dir does not exist"
    help
    exit 1
fi

if [[ ! -f "$map_data" ]]; then
    err "$map_data does not exist"
    if [[ "$ignore_error" -eq 0 ]]; then
        err "add -f option to ignore file errors"
        exit 2
    fi
fi

blue "using $data_dir for server attachments"
blue "using $map_data for map data"

if [[ "$clean_server" -eq 1 ]]; then
    if check_server; then
        exit 0
    fi
    blue "Clean servers"
    backup_mapdata
    compose down --remove-orphans
else
    if check_server; then
        exit 0
    fi

    flag=0
    for service in map_server map_data mongodb_ms; do
        if [[ "$(docker ps -f "name=${launch_prefix}-${service}" -q | wc -l)" -ne 0 ]]; then
            err "There is ${launch_prefix}-${service} server running"
            flag=1
        fi
    done
    if [[ "$flag" -eq 1 ]]; then
        red "Please stop the servers with '-C' or use '-c' to clean before launch"
        exit 1
    fi
fi

export HOST_UID=${HOST_UID:-$(id -u)}
export HOST_GID=${HOST_GID:-$(id -g)}
export HOST_TZ=${HOST_TZ:-${TZ:-$(cat /etc/timezone 2>/dev/null || echo Etc/UTC)}}
export MAP_SERVER_PORT
export MAPSERVICE_DATA_DIR="$data_dir"
export MAPSERVICE_MAP_DATA="$map_data"
export MAPSERVICE_EXPORT_DIR="$temp_dir"

if [[ "$verbose" -eq 1 ]]; then
    compose up -d --build
    compose logs -f
else
    compose up -d --build
fi
