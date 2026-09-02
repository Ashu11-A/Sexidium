# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/console.sh -- Managed server console: a FIFO held open on a dedicated fd so the JVM
#   never sees a premature stdin EOF.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_CONSOLE:-}" ]]; then return 0; fi
_SX_LIB_CONSOLE=1

# -----------------------------------------------------------------------------
# Managed warm-up boot
#
# Boots Paper with stdin attached to a FIFO that we also hold open on fd 9, so
# the JVM never reads EOF and shuts down on its own. We poll until either the
# Geyser config appears or the server dies, then issue a clean "stop" and wait
# for a graceful exit (SIGTERM as a last resort). This determinism is the whole
# point: the old flow closed the FIFO early, so the warm-up sometimes hung and
# the real start never happened.
# -----------------------------------------------------------------------------

warmup_for_geyser_config() {
    if [[ -f "$GEYSER_CONFIG" ]]; then
        log "Geyser config already present; skipping warm-up boot"
        return 0
    fi
    if [[ ! -s "$GEYSER_JAR" ]]; then
        log "Geyser jar absent; skipping warm-up boot"
        return 0
    fi

    sx_trace "warmup_boot timeout=${WARMUP_TIMEOUT}"
    if sx_dry; then
        log "Dry run: skipping warm-up boot"
        return 0
    fi

    log "Warm-up boot: generating Geyser default config (timeout ${WARMUP_TIMEOUT}s)"
    rm -f "$CONSOLE_FIFO"
    mkfifo "$CONSOLE_FIFO"

    # fd 9 keeps the write end open -> server's stdin never hits EOF.
    exec 9<>"$CONSOLE_FIFO"

    (cd "$SERVER_DIR" && exec "$JAVA_BIN" $JAVA_ARGS -jar "$(sx_rel "$PAPER_JAR")" nogui \
        <"$(sx_rel "$CONSOLE_FIFO")" >/dev/null 2>&1) &
    WARMUP_PID=$!

    local i ready=0
    for ((i = 0; i < WARMUP_TIMEOUT; i++)); do
        if ! kill -0 "$WARMUP_PID" 2>/dev/null; then
            log "Warm-up server exited before generating config"
            break
        fi
        if [[ -f "$GEYSER_CONFIG" ]]; then
            ready=1
            log "Geyser config generated after ${i}s"
            break
        fi
        sleep 1
    done

    # Graceful stop via the console we are still holding open.
    if kill -0 "$WARMUP_PID" 2>/dev/null; then
        printf 'stop\n' >&9
    fi

    for ((i = 0; i < STOP_TIMEOUT; i++)); do
        kill -0 "$WARMUP_PID" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$WARMUP_PID" 2>/dev/null; then
        log "Warm-up server did not stop gracefully; terminating"
        kill "$WARMUP_PID" 2>/dev/null || true
        wait "$WARMUP_PID" 2>/dev/null || true
    else
        wait "$WARMUP_PID" 2>/dev/null || true
    fi

    exec 9>&-
    WARMUP_PID=""
    rm -f "$CONSOLE_FIFO"

    [[ "$ready" -eq 1 ]] || log "Warm-up finished without a Geyser config; continuing unpatched"
}

# Reaps a managed warm-up JVM and removes its FIFO. Registered through
# sx::on_exit (never `trap` directly) so several instances can be provisioned in
# one process without clobbering each other's handler -- the single hardcoded
# `trap cleanup EXIT` is exactly what made the old script single-instance.
console::cleanup() {
    if [[ -n "${WARMUP_PID:-}" ]] && kill -0 "$WARMUP_PID" 2>/dev/null; then
        kill "$WARMUP_PID" 2>/dev/null || true
        wait "$WARMUP_PID" 2>/dev/null || true
    fi
    rm -f "${CONSOLE_FIFO:-}"
}
