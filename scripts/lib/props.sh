# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/props.sh -- .properties read/write (server.properties).
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_PROPS:-}" ]]; then return 0; fi
_SX_LIB_PROPS=1

# -----------------------------------------------------------------------------
# server.properties helpers
# -----------------------------------------------------------------------------

prop_get() {
    local file="$1" key="$2" fallback="$3"
    if [[ -f "$file" ]]; then
        awk -F= -v key="$key" -v fallback="$fallback" '
            $1 == key { value = substr($0, length($1) + 2) }
            END { print value != "" ? value : fallback }
        ' "$file"
    else
        printf '%s' "$fallback"
    fi
}

set_property() {
    local file="$1" key="$2" value="$3"
    sx_trace "set_property $(sx_rel "$file") $key=$value"
    touch "$file"
    awk -F= -v key="$key" -v value="$value" '
        $1 == key { print key "=" value; found = 1; next }
        { print }
        END { if (!found) print key "=" value }
    ' "$file" >"$file.tmp"
    mv "$file.tmp" "$file"
}
