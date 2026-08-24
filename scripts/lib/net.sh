# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/net.sh -- Host/gateway IPv4 discovery.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_NET:-}" ]]; then return 0; fi
_SX_LIB_NET=1

# NOTE: these helpers must not use `awk ... exit`. Exiting early closes the pipe
# while `ip route` is still writing, so it dies from SIGPIPE (rc 141); under
# `pipefail` that aborts the whole script. We read every line and keep the first
# match with a flag instead, and `|| true`-guard the call sites for good measure.
host_ipv4() {
    local route_ip
    route_ip="$(ip route get 1.1.1.1 2>/dev/null | awk '!f { for (i=1;i<=NF;i++) if ($i=="src") { print $(i+1); f=1 } }')"
    if [[ -n "$route_ip" ]]; then
        printf '%s' "$route_ip"
        return
    fi
    hostname -I 2>/dev/null | awk '{ print $1; exit }' || true
}

gateway_ipv4() {
    ip route 2>/dev/null | awk '$1 == "default" && !f { print $3; f=1 }'
}

# Namespaced aliases, so callers outside the legacy scripts read clearly.
net::host_ipv4() { host_ipv4; }
net::gateway_ipv4() { gateway_ipv4; }
