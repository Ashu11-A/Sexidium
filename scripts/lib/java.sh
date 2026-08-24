# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/java.sh -- JVM version enforcement and local-host resolution.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_JAVA:-}" ]]; then return 0; fi
_SX_LIB_JAVA=1

# Minecraft 26.x needs a Java 25 runtime; an older JVM dies at class-load time with an
# UnsupportedClassVersionError that reads like a corrupt jar. Fail here with the real reason instead.
ensure_java_version() {
    local raw major
    raw="$("$JAVA_BIN" -version 2>&1 | head -n1)"
    major="$(printf '%s\n' "$raw" | sed -nE 's/.*version "([0-9]+).*/\1/p')"
    if [[ -z "$major" ]]; then
        log "Could not parse a Java version from: $raw (continuing)"
        return 0
    fi
    ((major >= REQUIRED_JAVA)) ||
        die "Minecraft $PAPER_VERSION requires Java $REQUIRED_JAVA+, but $JAVA_BIN is Java $major. Set JAVA_BIN to a newer JDK."
    log "Java $major at $JAVA_BIN (Minecraft $PAPER_VERSION needs $REQUIRED_JAVA+)"
}

# What Java's InetAddress.getLocalHost() will resolve to: the first address the hostname maps to. This is
# NOT host_ipv4 — on a stock Debian/Ubuntu /etc/hosts the hostname maps to 127.0.1.1 while the machine's
# routable address is something else entirely. Anything that has to agree with a JVM's bind address (see
# configure_betterhud_if_present) must use this, not the LAN IP.
java_local_host() {
    getent hosts "$(hostname)" 2>/dev/null | awk '!f { print $1; f=1 }'
}
