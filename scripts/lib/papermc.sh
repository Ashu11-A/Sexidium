# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/papermc.sh -- PaperMC 'fill' API build resolution and server-jar installation.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_PAPERMC:-}" ]]; then return 0; fi
_SX_LIB_PAPERMC=1

# A PAPER_VERSION bump must not leave the previous version's jars in place: every ensure_* helper
# short-circuits on "file already exists", so an upgraded script would otherwise keep booting the old
# server jar and the plugins built for the old Minecraft. Drop the jars once, when the stamp disagrees.
# Only jars — plugin config directories, worlds and server.properties are left alone so a re-run after an
# upgrade keeps every hand-tuned setting (and Paper migrates its own configs on first boot).
#
# A server directory provisioned before the stamp existed has no stamp to compare against, so fall back to
# asking the jar itself: Paperclip ships a version.json at the jar root naming the Minecraft version it
# bootstraps. That keeps this honest in both directions — an upgrade is still detected on a pre-stamp
# directory, and one already on the target version is not made to re-download 60MB for nothing.
installed_paper_version() {
    [[ -s "$PAPER_JAR" ]] || return 0
    command -v python3 >/dev/null 2>&1 || return 0
    python3 - "$PAPER_JAR" <<'PY' 2>/dev/null || true
import json, sys, zipfile

with zipfile.ZipFile(sys.argv[1]) as jar:
    print(json.loads(jar.read("version.json"))["id"])
PY
}

# -----------------------------------------------------------------------------
# Paper jar resolution
# -----------------------------------------------------------------------------

# Newest usable build of $PAPER_VERSION, as "<build> <sha256> <url>". The v3 build list is newest-first
# and carries the download object URL inline, so this is a single request with no URL guessing.
#
# STABLE is preferred over what the list simply starts with: while a Minecraft version is still in its
# release-candidate phase Paper publishes EXPERIMENTAL builds for it, and picking one of those silently
# would run test code against a pinned "release" version. Falls back to the newest build of any channel so
# that pinning an RC version on purpose (PAPER_VERSION=26.3-rc-1) still resolves.
#
# papermc::resolve_build <project> <version> [preferred-channel ...]
#
# Generalized over the fill API's projects, because Velocity is served by the very
# same endpoint shape (/v3/projects/<project>/versions/<v>/builds, newest-first,
# downloads["server:default"].{url,checksums.sha256}).
#
# The channel list is a PREFERENCE ORDER, not a filter. Paper's newest 26.1.2
# build is STABLE, but Velocity 3.5.1's newest is RECOMMENDED -- a hard
# `channel == "STABLE"` test only lands on the right Velocity build by falling
# through to "newest of any channel", which would silently pick an EXPERIMENTAL
# build the day one is published. Asking for "STABLE, else RECOMMENDED, else
# newest" is the same answer for Paper and a deliberate one for Velocity.
papermc::resolve_build() {
    local project="$1" version="$2"
    shift 2
    local channels="${*:-STABLE RECOMMENDED}"
    CHANNELS="$channels" api_get "https://fill.papermc.io/v3/projects/$project/versions/$version/builds" 2>/dev/null | python3 -c '
import json, os, sys

builds = json.load(sys.stdin)
channels = os.environ.get("CHANNELS", "").split()

def emit(build):
    download = build.get("downloads", {}).get("server:default")
    if download and download.get("url"):
        print(build["id"], download.get("checksums", {}).get("sha256", ""), download["url"])
        return True
    return False

for channel in channels:
    if any(emit(b) for b in builds if b.get("channel") == channel):
        sys.exit(0)
for build in builds:
    if emit(build):
        break
' 2>/dev/null
}

resolve_paper_build() {
    papermc::resolve_build paper "$PAPER_VERSION" STABLE
}

ensure_paper() {
    if [[ -s "$PAPER_JAR" ]]; then
        log "Paper server jar already present: $PAPER_JAR"
        return
    fi
    need_cmd curl
    need_cmd python3
    mkdir -p "$SERVER_DIR"

    local resolved build sha url
    resolved="$(resolve_paper_build)"
    read -r build sha url <<<"$resolved" || true
    [[ -n "${url:-}" ]] || die "Could not resolve a Paper build for Minecraft $PAPER_VERSION from $PAPER_API (is that version published yet?)"

    log "Downloading Paper $PAPER_VERSION build $build"
    download_to "$url" "$PAPER_JAR" || die "Failed to download Paper jar from $url"
    verify_sha256 "$PAPER_JAR" "$sha"
}
