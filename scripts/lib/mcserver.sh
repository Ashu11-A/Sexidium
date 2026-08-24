# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/mcserver.sh -- Minecraft server-directory lifecycle: version stamping, jar refresh,
#   world quarantine on downgrade, eula + server.properties.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_MCSERVER:-}" ]]; then return 0; fi
_SX_LIB_MCSERVER=1

refresh_jars_on_version_change() {
    mkdir -p "$SERVER_DIR"
    local previous=""
    if [[ -f "$VERSION_STAMP" ]]; then
        previous="$(<"$VERSION_STAMP")"
    else
        previous="$(installed_paper_version)"
    fi
    if [[ "$previous" == "$PAPER_VERSION" ]]; then
        printf '%s\n' "$PAPER_VERSION" >"$VERSION_STAMP"
        return 0
    fi
    if [[ -n "$previous" ]]; then
        log "Server was provisioned for Minecraft $previous; re-downloading jars for $PAPER_VERSION"
        rm -f "$PAPER_JAR" "$GEYSER_JAR" "$MULTIVERSE_JAR" "$FANCYNPCS_JAR" \
            "$FANCYHOLOGRAMS_JAR" "$SKINSRESTORER_JAR" "$BETTERHUD_JAR" \
            "$FAWE_JAR" "$AXIOM_JAR"
        quarantine_worlds_on_downgrade "$previous"
    fi
    printf '%s\n' "$PAPER_VERSION" >"$VERSION_STAMP"
}

# Moves existing worlds out of the way when the pin goes BACKWARDS.
#
# Minecraft upgrades world storage in place and never downgrades it. A world last opened by 26.2 carries
# DataVersion 4903; 26.1.2 knows 4790 and refuses to load it ("world was created by a newer version"),
# and forcing it past that corrupts chunks. Re-downloading the jars is therefore not enough on its own —
# the server would come back up and immediately fail on the lobby world.
#
# Moved, never deleted, same rule the bundled maps and BetterHud overlays follow: the directory is
# renamed with the version that wrote it, so going back to 26.2 is a `mv` away rather than a lost lobby.
# The lobby and bundled maps are re-extracted from the jar on the next boot (MapBundle), so an empty
# server directory is a supported starting state.
quarantine_worlds_on_downgrade() {
    local previous="$1"
    local older stamp target moved=0
    # sort -V puts the lower version first; if that is the version we are moving TO, this is a downgrade.
    older="$(printf '%s\n%s\n' "$previous" "$PAPER_VERSION" | sort -V | head -1)"
    [[ "$older" == "$PAPER_VERSION" && "$previous" != "$PAPER_VERSION" ]] || return 0
    stamp="$(date +%Y%m%d-%H%M%S)"
    for dir in "$SERVER_DIR/world" "$SERVER_DIR/world_nether" "$SERVER_DIR/world_the_end" "$SERVER_DIR/worlds"; do
        [[ -d "$dir" ]] || continue
        target="$dir.mc$previous.$stamp"
        mv "$dir" "$target"
        log "Moved $(basename "$dir") -> $(basename "$target")"
        moved=$((moved + 1))
    done
    [[ "$moved" -gt 0 ]] || return 0
    log "Minecraft $previous -> $PAPER_VERSION is a DOWNGRADE and worlds do not downgrade: $moved world"
    log "  director(ies) were moved aside rather than handed to a server that cannot read them. The lobby"
    log "  and bundled maps are re-extracted on boot; delete the moved copies once you are happy."
}

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

configure_eula_and_server() {
    mkdir -p "$SERVER_DIR"
    printf 'eula=true\n' >"$SERVER_DIR/eula.txt"

    local props="$SERVER_DIR/server.properties"
    set_property "$props" "online-mode" "false"
    set_property "$props" "enforce-secure-profile" "false"
    set_property "$props" "server-port" "${SERVER_PORT:-25565}"
    set_property "$props" "query.port" "${QUERY_PORT:-$(prop_get "$props" query.port 25565)}"
    set_property "$props" "rcon.port" "${RCON_PORT:-$(prop_get "$props" rcon.port 25575)}"

    # RENDER FAR, TICK NEAR -- and note they are two different costs, not one.
    #
    # view-distance is what the player SEES: chunks sent to the client. Paper's default
    # of 10 is a 21x21 box, which on a build/experience server reads as fog. 32 is the
    # protocol ceiling; 16 is the middle we settled on, and it is not a compromise picked
    # by feel -- the cost is quadratic in the radius, so 32 is 65x65 = 4225 chunks per
    # player against 16's 33x33 = 1089, i.e. 3.9x the chunk churn for a view most players
    # never look at. On the network that churn was what kept G1 concurrent marking running
    # continuously, which is the window the worker-1 SIGSEGVs landed in.
    #
    # simulation-distance is what the SERVER TICKS: mobs, redstone, growth, hoppers --
    # per player, every tick. Raising it with view-distance is the mistake, because the
    # cost is quadratic and the nodes run -Xmx3G with -XX:+ExitOnOutOfMemoryError, so the
    # failure mode is a worker vanishing rather than lagging. It stays at 10; chunks
    # beyond it are still rendered, just frozen, which is exactly what distant terrain is
    # for. Override either per node via the environment.
    set_property "$props" "view-distance" "${SX_VIEW_DISTANCE:-16}"
    set_property "$props" "simulation-distance" "${SX_SIMULATION_DISTANCE:-10}"
}
