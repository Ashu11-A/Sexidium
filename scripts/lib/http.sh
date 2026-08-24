# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/http.sh -- HTTP fetch, download-to-file and checksum verification.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_HTTP:-}" ]]; then return 0; fi
_SX_LIB_HTTP=1

SX_ARTIFACT_CACHE="${SX_ARTIFACT_CACHE:-}"

# Fallback identity for entrypoints that have not loaded a profile (docker/provision.sh
# provisions a proxy and never calls paper::defaults). Deliberately NOT assigned to
# HTTP_UA: paper::defaults uses ${HTTP_UA:-...}, so assigning it here would make the
# generic agent win and change what init-paper.sh sends. Both the PaperMC and
# Modrinth APIs rate-limit generic agents harder, so this stays specific.
SX_HTTP_UA_DEFAULT="sexidium-provisioner/1.0 (sexidium network provisioner)"

# -----------------------------------------------------------------------------
# download helpers
# -----------------------------------------------------------------------------

api_get() {
    # Offline/deterministic mode: serve a recorded response keyed by the URL's sha1
    # so build-resolution logic is unit-testable without the network.
    if [[ -n "$SX_HTTP_FIXTURES" ]]; then
        local key fixture
        key="$(printf '%s' "$1" | sha1sum | awk '{ print $1 }')"
        fixture="$SX_HTTP_FIXTURES/$key.json"
        if [[ -f "$fixture" ]]; then
            cat "$fixture"
            return 0
        fi
        # Unknown URL under fixtures is a test bug, not a network problem.
        log "No HTTP fixture for $1 ($key.json)"
        return 1
    fi
    # Recording mode: capture live responses so the harness can replay them offline.
    if [[ -n "${SX_HTTP_RECORD:-}" ]]; then
        local rkey rbody
        rkey="$(printf '%s' "$1" | sha1sum | awk '{ print $1 }')"
        rbody="$(curl -fsSL --retry 3 --retry-delay 2 -A "${HTTP_UA:-$SX_HTTP_UA_DEFAULT}" "$1")" || return 1
        mkdir -p "$SX_HTTP_RECORD"
        printf '%s' "$rbody" >"$SX_HTTP_RECORD/$rkey.json"
        printf '%s\t%s\n' "$rkey" "$1" >>"$SX_HTTP_RECORD/INDEX.txt"
        printf '%s' "$rbody"
        return 0
    fi
    curl -fsSL --retry 3 --retry-delay 2 -A "${HTTP_UA:-$SX_HTTP_UA_DEFAULT}" "$1"
}

download_to() {
    local url="$1" dest="$2" tmp="${2}.tmp" cache_lock_fd=""
    sx_trace "download $(sx_rel "$dest") <- $url"
    if sx_dry; then
        # Stub with deterministic content: enough for `[[ -s ]]` probes to pass
        # without pulling ~50 MB of jars on every harness run.
        mkdir -p "$(dirname "$dest")"
        printf 'sexidium-dry-run-stub\n' >"$dest"
        return 0
    fi

    # Shared artifact cache. Every backend needs the SAME ~113MB of jars (Paper alone is 50MB), so
    # provisioning four nodes downloaded ~450MB of which three quarters was identical bytes. Keyed by
    # URL, so a version bump is a different key and still fetched exactly once.
    #
    # Linked rather than copied: a hard link costs no disk and no time, and these jars are immutable
    # inputs that nothing rewrites in place. Falls back to a copy across filesystems.
    local cache_entry=""
    if [[ -n "${SX_ARTIFACT_CACHE:-}" ]]; then
        mkdir -p "$SX_ARTIFACT_CACHE"
        cache_entry="$SX_ARTIFACT_CACHE/$(printf '%s' "$url" | sha1sum | awk '{ print $1 }')"
        # Serialize on the cache key. Without this, N instances provisioning in parallel all miss the
        # cache in the same instant and all download the same file -- measured as 33 downloads where
        # 9 were needed. The lock makes the first fetch and the rest wait for it, then link.
        if command -v flock >/dev/null 2>&1; then
            exec {cache_lock_fd}>"$cache_entry.lock"
            flock "$cache_lock_fd"
        fi
        if [[ -s "$cache_entry" ]]; then
            mkdir -p "$(dirname "$dest")"
            rm -f "$dest"
            ln "$cache_entry" "$dest" 2>/dev/null || cp "$cache_entry" "$dest"
            [[ -n "${cache_lock_fd:-}" ]] && exec {cache_lock_fd}>&-
            log "Reusing cached $(basename "$dest")"
            return 0
        fi
    fi

    rm -f "$tmp"
    if ! curl -fL --retry 3 --retry-delay 2 -A "${HTTP_UA:-$SX_HTTP_UA_DEFAULT}" -o "$tmp" "$url"; then
        rm -f "$tmp"
        return 1
    fi
    mv "$tmp" "$dest"
    if [[ -n "$cache_entry" ]]; then
        ln "$dest" "$cache_entry" 2>/dev/null || cp "$dest" "$cache_entry"
    fi
    [[ -n "${cache_lock_fd:-}" ]] && exec {cache_lock_fd}>&-
    return 0
}

# Aborts on a corrupt download instead of letting the JVM fail at boot with an unexplainable error. A
# missing checksum or sha256sum binary just skips the check.
verify_sha256() {
    local file="$1" expected="$2" actual
    [[ -n "$expected" ]] || return 0
    sx_trace "verify_sha256 $(sx_rel "$file") $expected"
    # A dry-run stub is deliberately not the real artifact; checking it would abort.
    sx_dry && return 0
    command -v sha256sum >/dev/null 2>&1 || return 0
    actual="$(sha256sum "$file" | awk '{ print $1 }')"
    if [[ "$actual" != "$expected" ]]; then
        rm -f "$file"
        die "Checksum mismatch for $(basename "$file"): expected $expected, got $actual"
    fi
}
