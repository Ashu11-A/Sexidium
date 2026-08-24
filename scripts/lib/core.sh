# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/core.sh -- logging, fatal errors, command probes, the exit-trap registry, and the
#   SX_TRACE / SEXIDIUM_DRY_RUN / SX_HTTP_FIXTURES test hooks.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_CORE:-}" ]]; then return 0; fi
_SX_LIB_CORE=1

# --- paths --------------------------------------------------------------------
# Resolved from THIS file, never from $PWD, so every entrypoint is runnable from
# any working directory. -P resolves a symlinked checkout.
SX_LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SX_SCRIPTS_DIR="$(cd -- "$SX_LIB_DIR/.." && pwd -P)"
ROOT_DIR="${ROOT_DIR:-$(cd -- "$SX_SCRIPTS_DIR/.." && pwd -P)}"
export SX_LIB_DIR SX_SCRIPTS_DIR ROOT_DIR

# --- library loader -----------------------------------------------------------
# sx::require props http ...   -> sources scripts/lib/<name>.sh once each.
sx::require() {
    local mod
    for mod in "$@"; do
        # shellcheck source=/dev/null
        . "$SX_LIB_DIR/$mod.sh" || {
            printf '[sexidium] FATAL: cannot source lib/%s.sh\n' "$mod" >&2
            exit 1
        }
    done
}

# --- exit-trap registry -------------------------------------------------------
# Bash has exactly ONE EXIT trap. The single-server script could hardcode
# `trap cleanup EXIT` because it managed one server; provisioning four backends
# needs one handler per instance, so handlers register here instead. This is what
# makes concurrent warm-ups safe, and it is why libraries must never call `trap`.
_SX_EXIT_HANDLERS=()

sx::on_exit() {
    _SX_EXIT_HANDLERS+=("$1")
}

sx::_run_exit_handlers() {
    local rc=$? h
    # Reverse order: last registered is unwound first, like nested resources.
    for ((h = ${#_SX_EXIT_HANDLERS[@]} - 1; h >= 0; h--)); do
        eval "${_SX_EXIT_HANDLERS[h]}" || true
    done
    return "$rc"
}
trap sx::_run_exit_handlers EXIT

# --- test hooks ---------------------------------------------------------------
# Defaulted HERE rather than in a profile, because sx_trace/sx_dry are called
# from every library and the entrypoints run under `set -u`: an unset SX_TRACE
# aborts the run. The golden-trace harness always sets SX_TRACE, so this is a
# path only a NORMAL run exercises -- exactly the kind of one-mode-only bug the
# dual-mode test strategy exists to catch.
SX_TRACE="${SX_TRACE:-}"
SEXIDIUM_DRY_RUN="${SEXIDIUM_DRY_RUN:-0}"
SX_HTTP_FIXTURES="${SX_HTTP_FIXTURES:-}"
SX_HTTP_RECORD="${SX_HTTP_RECORD:-}"

# --- logging ------------------------------------------------------------------
# The tag is a variable so docker/provision.sh can say [provision] while
# init-paper.sh keeps the exact `[init-paper]` prefix its callers grep for
# (scripts/docker-paper-entry.sh, docker-compose.yml, docs/known-issues.md).
SX_LOG_TAG="${SX_LOG_TAG:-init-paper}"

log() {
    printf '[%s] %s\n' "$SX_LOG_TAG" "$*" >&2
}

die() {
    log "ERROR: $*"
    exit 1
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

sx_dry() { [[ "$SEXIDIUM_DRY_RUN" == "1" ]]; }

# Repo/server-relative path so a trace recorded under /tmp/xxx compares equal to
# one recorded under test/paper.
# Repo/server-relative path so a trace recorded under /tmp/xxx compares equal to
# one recorded under test/paper.
#
# SERVER_DIR is defaulted, not assumed: it is set by a PROFILE (paper::defaults),
# and core.sh is also sourced by entrypoints that have no server directory at all
# (docker/provision.sh provisions a proxy). Under `set -u` a bare $SERVER_DIR here
# aborts those callers.
# NETWORK_DIR and SHARED_DIR are stripped too, and only when they are SET: the network
# profile writes outside any one SERVER_DIR (the proxy directory, the shared install's
# config tree, the per-node staging files), and an absolute path there makes the
# network golden trace depend on where the harness happened to mktemp. The explicit
# non-empty guard is load-bearing -- with an empty variable the prefix pattern degrades
# to a bare "/" and would eat the leading slash off every absolute path, which is
# exactly the case init-paper.sh is in (it never calls velocity::defaults).
sx_rel() {
    local p="$1"
    p="${p#"${SERVER_DIR:-}"/}"
    p="${p#"${ROOT_DIR:-}"/}"
    [[ -z "${NETWORK_DIR:-}" ]] || p="${p#"$NETWORK_DIR"/}"
    [[ -z "${SHARED_DIR:-}" ]] || p="${p#"$SHARED_DIR"/}"
    printf '%s' "$p"
}

sx_trace() {
    [[ -n "$SX_TRACE" ]] || return 0
    printf '%s\n' "$*" >>"$SX_TRACE"
}
