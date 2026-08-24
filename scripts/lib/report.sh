# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/report.sh -- Operator-facing run report.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_REPORT:-}" ]]; then return 0; fi
_SX_LIB_REPORT=1

print_progress_report() {
    local host_ip gateway server_port query_port rcon_port geyser_port query_enabled rcon_enabled
    host_ip="$(host_ipv4 || true)"
    gateway="$(gateway_ipv4 || true)"
    server_port="$(prop_get "$SERVER_DIR/server.properties" server-port 25565)"
    query_port="$(prop_get "$SERVER_DIR/server.properties" query.port "$server_port")"
    rcon_port="$(prop_get "$SERVER_DIR/server.properties" rcon.port 25575)"
    query_enabled="$(prop_get "$SERVER_DIR/server.properties" enable-query false)"
    rcon_enabled="$(prop_get "$SERVER_DIR/server.properties" enable-rcon false)"
    geyser_port="${GEYSER_PORT:-19132}"

    log "Run analysis:"
    log "  Minecraft version: $PAPER_VERSION"
    log "  server directory: $SERVER_DIR"
    log "  computer LAN IP: ${host_ip:-unknown}"
    log "  default gateway/router: ${gateway:-unknown}"
    log "  Java Minecraft: ${host_ip:-127.0.0.1}:$server_port TCP"
    log "  Geyser Bedrock: ${host_ip:-127.0.0.1}:$geyser_port UDP"
    log "  Query: $query_port UDP (enable-query=$query_enabled)"
    log "  RCON: $rcon_port TCP (enable-rcon=$rcon_enabled)"
    log "  online-mode=$(prop_get "$SERVER_DIR/server.properties" online-mode false)"

    # The world editors are optional extras: an entrypoint that never installs them
    # (a networked backend, for one) leaves these paths unset, so the whole block
    # stays quiet rather than printing two "absent" lines nobody asked about.
    local fawe="absent" axiom="absent"
    [[ ! -s "${FAWE_JAR:-}" ]] || fawe="ready (op yourself, then //wand)"
    [[ ! -s "${AXIOM_JAR:-}" ]] || axiom="ready (needs the Axiom client mod)"
    if [[ -n "${FAWE_JAR:-}${AXIOM_JAR:-}" ]]; then
        log "  world editing: FastAsyncWorldEdit $fawe"
        log "                 Axiom $axiom"
    fi
}

# Operator-facing summary of a provisioned network.
report::network() {
    local node port api_base address host_ip
    host_ip="$(host_ipv4 || true)"
    log "Network layout:"
    log "  proxy        ${host_ip:-127.0.0.1}:$PROXY_PORT  (players connect here)"
    log "  proxy dir    $PROXY_DIR"
    log "  velocity     $VELOCITY_VERSION"
    log "  forwarding   modern (secret in $(sx_rel "$FORWARDING_SECRET_FILE"))"
    for node in $SX_NODES; do
        port="$(velocity::node_port "$node")"
        api_base="$(velocity::node_api_base "$node")"
        # The address the PROXY dials, so the report matches velocity.toml's [servers]
        # line by line. Printing SX_BACKEND_BIND instead told the operator "127.0.0.1"
        # while the proxy was actually dialling the node by hostname.
        address="$(velocity::node_address "$node")"
        log "  $(printf '%-11s' "$node")$address:$port  api=$api_base  dir=$NETWORK_DIR/$node"
    done
    log "  bot host     ${SX_BOT_NODE:-lobby}"
    log "  database     ${SEXIDIUM_DB_TYPE:-<unset: backends stay on their own SQLite>}"
    if [[ -z "${SEXIDIUM_DB_TYPE:-}" ]]; then
        log ""
        log "  NOTE: no SEXIDIUM_DB_TYPE set, so each backend uses its own local SQLite."
        log "  A network needs ONE central database -- set SEXIDIUM_DB_TYPE/HOST/NAME/USER/PASSWORD"
        log "  (mysql or postgres) and re-run, or ranks, friends and experiences will not be shared."
    fi
}
