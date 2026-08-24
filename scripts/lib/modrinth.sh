# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/modrinth.sh -- Modrinth project resolution and plugin installation.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_MODRINTH:-}" ]]; then return 0; fi
_SX_LIB_MODRINTH=1

# Resolve a Paper download for a Modrinth project, preferring a build that declares support for the
# Minecraft version we are provisioning. Prints "<url> <version-number> <gv-match|gv-mismatch>".
#
# The Minecraft version matters more than it looks: a plugin's newest build is often published against an
# older game version, and Paper refuses to enable a plugin whose api-version it considers unsupported. But
# the reverse happens too — right after a Minecraft release the only build declaring the new version can
# be a snapshot — so this degrades in steps rather than insisting: preferred channel + this Minecraft
# version -> this Minecraft version on any channel -> preferred channel, any version -> newest build. The
# caller logs which of those it settled for, so a mismatch is visible rather than a mystery at boot.
modrinth_paper_download_url() {
    local slug="$1" channel="${2:-}"
    api_get "$MODRINTH_API/project/$slug/version?loaders=%5B%22paper%22%5D" 2>/dev/null |
        CHANNEL="$channel" GAME_VERSION="$PAPER_VERSION" python3 -c '
import json, os, sys

versions = json.load(sys.stdin)
channel = os.environ.get("CHANNEL", "")
game_version = os.environ.get("GAME_VERSION", "")

def on_channel(candidates):
    if not channel:
        return candidates
    return [v for v in candidates if v.get("version_type") == channel]

def supports_game(candidates):
    return [v for v in candidates if game_version in v.get("game_versions", [])]

def first(*groups):
    for group in groups:
        if group:
            return group[0]
    return None

supported = supports_game(versions)
chosen = first(on_channel(supported), supported, on_channel(versions), versions)
if chosen is None:
    raise SystemExit(0)

files = [f for f in chosen["files"] if f.get("primary")] or chosen["files"]
if not files:
    raise SystemExit(0)
matched = "gv-match" if game_version in chosen.get("game_versions", []) else "gv-mismatch"
print(files[0]["url"], chosen.get("version_number", "?"), matched)
' 2>/dev/null
}

# Download a Modrinth Paper plugin. Fourth arg "required" aborts on failure; anything else treats the
# plugin as optional (warn + continue). Fifth arg optionally names the preferred release channel.
ensure_modrinth_plugin() {
    local slug="$1" dest="$2" label="$3" requirement="${4:-optional}" channel="${5:-}"

    if [[ -s "$dest" ]]; then
        log "$label already present: $dest"
        return 0
    fi

    local resolved url version matched
    resolved="$(modrinth_paper_download_url "$slug" "$channel")"
    read -r url version matched <<<"$resolved" || true
    if [[ -z "${url:-}" ]]; then
        if [[ "$requirement" == "required" ]]; then
            die "Could not resolve a Paper build of $label from Modrinth"
        fi
        log "Could not resolve a Paper build of $label from Modrinth; staying disabled"
        return 0
    fi

    log "Downloading $label $version (Paper) from Modrinth"
    if [[ "$matched" != "gv-match" ]]; then
        log "  NOTE: no $label build lists Minecraft $PAPER_VERSION yet; falling back to $version."
        log "  If Paper refuses to enable it, wait for an updated build or drop this plugin for now."
    fi
    if ! download_to "$url" "$dest"; then
        if [[ "$requirement" == "required" ]]; then
            die "Failed to download $label (hard dependency)"
        fi
        log "Failed to download $label; staying disabled"
        rm -f "$dest" 2>/dev/null || true
    fi
}
