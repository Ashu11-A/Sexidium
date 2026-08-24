#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# net.sh -- run the provisioned Sexidium network.
#
#   scripts/net.sh up        start backends, wait for them, then the proxy
#   scripts/net.sh down      stop the proxy first, then the backends
#   scripts/net.sh restart   down + up
#   scripts/net.sh status    what is listening, and what the shared database says
#   scripts/net.sh logs [n]  tail one node, or all of them
#   scripts/net.sh db-up     start the shared MySQL container and wait for it
#   scripts/net.sh db-wire   point every node (and the proxy) at that database
#   scripts/net.sh update    rebuild the plugin and roll it out
#
# ORDER MATTERS in both directions.
#
#   Up: backends first. A proxy that binds before its backends answers every join with
#   "Unable to connect you to lobby" -- the connection is refused because nothing is listening on
#   25566 yet. That is the single most likely first-run failure, so `up` waits for each backend to
#   finish booting before the proxy accepts anyone.
#
#   Down: proxy first, so players are disconnected cleanly BEFORE the backends start saving worlds.
#   The reverse strands players on a server that is mid-shutdown.
# -----------------------------------------------------------------------------

SX_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SX_LOG_TAG="net"
export SX_LOG_TAG

# shellcheck source=lib/core.sh
. "$SX_SCRIPT_DIR/lib/core.sh"
sx::require props http papermc java net yaml console plugins sexidium paper velocity report

paper::defaults
velocity::defaults

BOOT_TIMEOUT="${BOOT_TIMEOUT:-240}"

node_dir() { printf '%s' "$NETWORK_DIR/$1"; }
node_pidfile() { printf '%s' "$(node_dir "$1")/server.pid"; }

node_running() {
    local pidfile
    pidfile="$(node_pidfile "$1")"
    [[ -f "$pidfile" ]] || return 1
    kill -0 "$(cat "$pidfile")" 2>/dev/null
}

proxy_pidfile() { printf '%s' "$PROXY_DIR/proxy.pid"; }

proxy_running() {
    [[ -f "$(proxy_pidfile)" ]] || return 1
    kill -0 "$(cat "$(proxy_pidfile)")" 2>/dev/null
}

heap_for() {
    case "$1" in
        lobby) printf '%s' "$SX_LOBBY_MEMORY" ;;
        *) printf '%s' "$SX_WORKER_MEMORY" ;;
    esac
}

start_backend() {
    local node="$1" dir heap
    dir="$(node_dir "$node")"
    [[ -s "$dir/paper.jar" ]] || die "$node is not provisioned (no paper.jar). Provision the network first: docker/provision.sh, or the stack's \`init\` service in Portainer."
    if node_running "$node"; then
        log "$node already running (pid $(cat "$(node_pidfile "$node")"))"
        return 0
    fi
    # A stale lock from a killed JVM stops the next boot with "already locked", which reads like a
    # second server is running when nothing is.
    find "$dir" -name "session.lock" -delete 2>/dev/null || true
    heap="$(heap_for "$node")"
    read -r -a heap_args <<<"$heap"
    (
        cd "$dir" || exit 1
        nohup java "${heap_args[@]}" -jar paper.jar nogui >"$dir/console.log" 2>&1 &
        printf '%s' "$!" >"$dir/server.pid"
    )
    log "$node starting (pid $(cat "$(node_pidfile "$node")"))"
}

wait_for_backend() {
    local node="$1" dir waited=0
    dir="$(node_dir "$node")"
    while [[ "$waited" -lt "$BOOT_TIMEOUT" ]]; do
        if grep -q 'Done (' "$dir/console.log" 2>/dev/null; then
            log "$node ready"
            return 0
        fi
        if ! node_running "$node"; then
            log "$node DIED during boot; last lines:"
            tail -12 "$dir/console.log" >&2 2>/dev/null || true
            return 1
        fi
        sleep 3
        waited=$((waited + 3))
    done
    log "$node did not report ready within ${BOOT_TIMEOUT}s"
    return 1
}

start_proxy() {
    if proxy_running; then
        log "proxy already running (pid $(cat "$(proxy_pidfile)"))"
        return 0
    fi
    [[ -s "$VELOCITY_JAR" ]] || die "The proxy is not provisioned. Provision the network first: docker/provision.sh, or the stack's \`init\` service in Portainer."
    read -r -a proxy_args <<<"$PROXY_JAVA_ARGS"
    (
        cd "$PROXY_DIR" || exit 1
        nohup java "${proxy_args[@]}" -jar velocity.jar >"$PROXY_DIR/console.log" 2>&1 &
        printf '%s' "$!" >"$PROXY_DIR/proxy.pid"
    )
    log "proxy starting (pid $(cat "$(proxy_pidfile)"))"
}

stop_one() {
    local label="$1" pidfile="$2" pid waited=0
    [[ -f "$pidfile" ]] || return 0
    pid="$(cat "$pidfile")"
    if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$pidfile"
        return 0
    fi
    log "stopping $label (pid $pid)"
    kill "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null && [[ "$waited" -lt 60 ]]; do
        sleep 2
        waited=$((waited + 2))
    done
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$pidfile"
}

cmd_up() {
    local node failed=0
    log "starting backends: $SX_NODES"
    for node in $SX_NODES; do start_backend "$node"; done
    # The proxy must not accept players before its backends answer, or every join is refused.
    for node in $SX_NODES; do wait_for_backend "$node" || failed=1; done
    if [[ "$failed" -ne 0 ]]; then
        log "at least one backend failed to start; the proxy is NOT being started"
        return 1
    fi
    start_proxy
    log ""
    log "Network up. Connect to $(net::host_ipv4 || printf '127.0.0.1'):$PROXY_PORT"
}

cmd_down() {
    local node
    # Proxy first: disconnect players cleanly before backends begin saving.
    stop_one proxy "$(proxy_pidfile)"
    for node in $SX_NODES; do stop_one "$node" "$(node_pidfile "$node")"; done
    log "Network down"
}

cmd_status() {
    local node state port
    printf '  %-10s %-9s %-7s %s\n' NODE STATE PORT DIRECTORY
    if proxy_running; then state="up"; else state="down"; fi
    printf '  %-10s %-9s %-7s %s\n' proxy "$state" "$PROXY_PORT" "$PROXY_DIR"
    for node in $SX_NODES; do
        if node_running "$node"; then state="up"; else state="down"; fi
        port="$(velocity::node_port "$node")"
        printf '  %-10s %-9s %-7s %s\n' "$node" "$state" "$port" "$(node_dir "$node")"
    done
    local db
    db="$(yaml::get "$NETWORK_DIR/lobby/plugins/Sexidium/config.yml" database.file 2>/dev/null || true)"
    if [[ -n "$db" && -f "$db" ]] && command -v python3 >/dev/null 2>&1; then
        printf '\n  shared database: %s\n' "$db"
        DB="$db" python3 - <<'PY' || true
import os, sqlite3, time
c = sqlite3.connect(os.environ["DB"])
now = int(time.time() * 1000)
try:
    rows = list(c.execute("SELECT node_id, role, state, players, heartbeat_at FROM network_nodes ORDER BY node_id"))
except sqlite3.OperationalError:
    print("    (no network_nodes yet)")
    raise SystemExit
for r in rows:
    print(f"    {r[0]:<10} role={r[1]:<7} {r[2]:<5} players={r[3]}  heartbeat {(now - r[4]) / 1000.0:.0f}s ago")
PY
    fi
}

cmd_logs() {
    local node="${1:-}"
    if [[ -n "$node" ]]; then
        tail -f "$(node_dir "$node")/console.log"
        return
    fi
    # Prefix each line with its node so interleaved output stays readable.
    local f
    for f in "$PROXY_DIR/console.log" "$NETWORK_DIR"/*/console.log; do
        [[ -f "$f" ]] || continue
        tail -f "$f" | sed "s|^|[$(basename "$(dirname "$f")")] |" &
    done
    wait
}

# Point every node -- backends AND the proxy -- at the shared MySQL/Postgres.
#
# The proxy is included deliberately: it is the node that most needs the database (login auth gate,
# placement routing) and it is the one a shared SQLite file locks out entirely, because its plugin
# jar ships only the MySQL and Postgres drivers.
cmd_db_wire() {
    local type="${SEXIDIUM_DB_TYPE:-mysql}"
    local host="${SEXIDIUM_DB_HOST:-127.0.0.1}"
    local port="${SEXIDIUM_DB_PORT:-3307}"
    local name="${SEXIDIUM_DB_NAME:-sexidium}"
    local user="${SEXIDIUM_DB_USER:-sexidium}"
    local pass="${SEXIDIUM_DB_PASSWORD:-sexidium}"

    case "$type" in
        mysql | postgres) ;;
        *) die "db-wire needs SEXIDIUM_DB_TYPE=mysql or postgres (got '$type'). SQLite cannot be shared by the proxy." ;;
    esac

    local node config wired=0
    for node in $SX_NODES; do
        config="$(node_dir "$node")/plugins/Sexidium/config.yml"
        [[ -f "$config" ]] || continue
        yaml::set "$config" \
            database.type "$type" database.host "$host" database.port "$port" \
            database.name "$name" database.user "$user" database.password "$pass"
        wired=$((wired + 1))
    done

    config="$PROXY_DIR/plugins/sexidium/config.yml"
    if [[ -f "$config" ]]; then
        yaml::set "$config" \
            database.type "$type" database.host "$host" database.port "$port" \
            database.name "$name" database.user "$user" database.password "$pass"
        wired=$((wired + 1))
    fi

    log "Wired $wired node(s) to $type://$host:$port/$name"
    log "Apply with: scripts/net.sh restart"
}

cmd_db_up() {
    need_cmd docker
    log "starting the shared database container"
    (cd "$ROOT_DIR" && docker compose -f docker-compose.network.yml up -d) || die "docker compose failed"
    log "waiting for it to accept queries…"
    local waited=0
    while [[ "$waited" -lt 180 ]]; do
        if [[ "$(docker inspect --format '{{.State.Health.Status}}' sexidium-mysql 2>/dev/null)" == "healthy" ]]; then
            log "database ready on 127.0.0.1:${SEXIDIUM_DB_PORT:-3307}"
            return 0
        fi
        sleep 4
        waited=$((waited + 4))
    done
    die "the database did not become healthy; check: docker logs sexidium-mysql"
}

cmd_update() {
    log "rebuilding the plugin"
    (cd "$ROOT_DIR" && ./gradlew --project-cache-dir "$GRADLE_PROJECT_CACHE_DIR" build -q) || die "build failed"
    # sexidium::install_jar, never a bare `cp`: a rollout happens while the nodes are
    # still running, and copying over a live jar truncates it in the SAME inode the
    # JVM's URLClassLoader has open. The failure surfaces hours later as a
    # ZipException in the middle of a game, nowhere near this line.
    local node velocity_jar="$ROOT_DIR/build/libs/velocity/Sexidium-Velocity-1.0.0.jar"
    for node in $SX_NODES; do
        sexidium::install_jar "$SEXIDIUM_JAR" "$(node_dir "$node")/plugins"
    done
    [[ ! -s "$velocity_jar" ]] || sexidium::install_jar "$velocity_jar" "$PROXY_DIR/plugins"
    log "jars rolled out; restart with: scripts/net.sh restart"
}

case "${1:-}" in
    up) cmd_up ;;
    db-up) cmd_db_up ;;
    db-wire) cmd_db_wire ;;
    down) cmd_down ;;
    restart)
        cmd_down
        cmd_up
        ;;
    status) cmd_status ;;
    logs) cmd_logs "${2:-}" ;;
    update) cmd_update ;;
    *)
        sed -n '4,20p' "${BASH_SOURCE[0]}"
        exit 1
        ;;
esac
