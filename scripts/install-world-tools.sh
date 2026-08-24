#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# install-world-tools.sh
#
# Installs the world-editing toolchain used to build what this repo ships in
# assets/worlds/** (the lobby and the TNT War battle maps) and to import
# downloaded .schem builds into them. Everything here is Linux-native.
#
# Two halves, because Minecraft map editing has two:
#
#   client   in-game editing. Fabric loader for the pinned Minecraft version plus
#            Fabric API, Axiom (visual editor: sculpting, blueprints, infinite
#            undo), WorldEdit (the same //commands in single-player, so a map can
#            be built with no server running) and WorldEdit CUI (draws the
#            selection box that WorldEdit/FAWE otherwise leaves invisible).
#
#   desktop  offline editing of world FOLDERS with no game and no server:
#            MCA Selector (delete/prune/relocate chunks, export selections,
#            change region files in bulk) and, optionally, Amulet.
#
# The SERVER half is not here: scripts/init-paper.sh installs FastAsyncWorldEdit
# and the Axiom Paper plugin into test/paper/plugins as part of provisioning, so
# a normal `./scripts/init-paper.sh` already gives you //wand on the test server.
#
# Usage:
#   scripts/install-world-tools.sh              # client + desktop
#   scripts/install-world-tools.sh client       # Fabric + editing mods only
#   scripts/install-world-tools.sh desktop      # MCA Selector only
#   scripts/install-world-tools.sh amulet       # Amulet (heavy, best-effort)
#   scripts/install-world-tools.sh status       # what is installed where
#
# Environment:
#   MC_DIR=~/.minecraft     Minecraft install the Fabric version is registered in
#   GAME_DIR=$MC_DIR/sexidium-world-editing   isolated game directory holding the mods
#   MC_VERSION=26.1.2       overrides the version read from test/paper/.mc-version
#   TOOLS_DIR=<repo>/tools  where the desktop tools land (gitignored)
#   FORCE=1                 re-download even when a file is already present
# -----------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="${TOOLS_DIR:-$ROOT_DIR/tools}"
MC_DIR="${MC_DIR:-$HOME/.minecraft}"
# The mods live in their OWN game directory, not $MC_DIR/mods, and that is not tidiness.
# ~/.minecraft/mods is a single shared folder that every profile of every loader reads: this machine's
# already holds NeoForge mods for a 1.21.1 profile. Fabric jars dropped in beside them are read by
# NeoForge as broken mod files and take that profile's launch down (and vice versa). A per-profile
# gameDir is the supported way to keep two loaders installed at once — worlds, screenshots, options and
# mods all live under it, and the existing profiles never see any of it.
GAME_DIR="${GAME_DIR:-$MC_DIR/sexidium-world-editing}"
MODS_DIR="$GAME_DIR/mods"
LAUNCHER_PROFILES="$MC_DIR/launcher_profiles.json"
LAUNCHER_PROFILE_NAME="${LAUNCHER_PROFILE_NAME:-Sexidium World Editing}"
FORCE="${FORCE:-0}"
# Set by install_fabric_loader so the launcher profile can name the exact version it installed.
RESOLVED_LOADER=""

# The client mods MUST match the server's Minecraft version — a mod jar built for another version does not
# degrade, it aborts the launch — so the pin is read from the same place the test server records it, and
# falls back to init-paper's own default when the server has never been provisioned.
VERSION_STAMP_SRC="$ROOT_DIR/test/paper/.mc-version"
if [[ -z "${MC_VERSION:-}" && -s "$VERSION_STAMP_SRC" ]]; then
    MC_VERSION="$(<"$VERSION_STAMP_SRC")"
fi
MC_VERSION="${MC_VERSION:-26.1.2}"
MC_VERSION="${MC_VERSION//[$'\t\r\n ']/}"

JAVA_BIN="${JAVA_BIN:-java}"
MODRINTH_API="https://api.modrinth.com/v2"
FABRIC_META="https://meta.fabricmc.net/v2/versions"
MCASELECTOR_RELEASE="https://api.github.com/repos/Querz/mcaselector/releases/latest"
MAVEN_CENTRAL="https://repo1.maven.org/maven2"
HTTP_UA="${HTTP_UA:-sexidium-install-world-tools/1.0 (sexidium world-editing toolchain)}"

# Marks which Minecraft version the managed mod jars were installed for. Without it a version bump would
# leave last version's Axiom sitting in mods/ and the game would refuse to start with a stack trace that
# names neither this script nor the version mismatch.
MODS_STAMP="$MODS_DIR/.sexidium-world-tools"
# Modrinth slug -> the fixed filename we manage in mods/. Fixed rather than upstream's own filename so a
# re-run replaces the old jar instead of leaving two versions of the same mod side by side.
CLIENT_MODS=(
    "fabric-api:fabric-api.jar:Fabric API"
    "axiom:axiom.jar:Axiom"
    "worldedit:worldedit.jar:WorldEdit"
    "worldedit-cui:worldedit-cui.jar:WorldEdit CUI"
)

log() {
    printf '[world-tools] %s\n' "$*" >&2
}

die() {
    log "ERROR: $*"
    exit 1
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

api_get() {
    curl -fsSL --retry 3 --retry-delay 2 -A "$HTTP_UA" "$1"
}

# Downloads through a .tmp file so an interrupted transfer never leaves a half jar that the launcher or
# the JVM then reports as a corrupt archive.
download_to() {
    local url="$1" dest="$2" tmp="${2}.tmp"
    mkdir -p "$(dirname "$dest")"
    rm -f "$tmp"
    if ! curl -fL --retry 3 --retry-delay 2 -A "$HTTP_UA" -o "$tmp" "$url"; then
        rm -f "$tmp"
        return 1
    fi
    mv "$tmp" "$dest"
}

# -----------------------------------------------------------------------------
# client: Fabric + editing mods
# -----------------------------------------------------------------------------

# Newest Fabric loader that lists $MC_VERSION, preferring a stable build.
resolve_fabric_loader() {
    api_get "$FABRIC_META/loader/$MC_VERSION" 2>/dev/null | python3 -c '
import json, sys

entries = json.load(sys.stdin)
for entry in [e for e in entries if e["loader"].get("stable")] or entries:
    print(entry["loader"]["version"])
    break
' 2>/dev/null
}

resolve_fabric_installer() {
    api_get "$FABRIC_META/installer" 2>/dev/null | python3 -c '
import json, sys

entries = json.load(sys.stdin)
for entry in [e for e in entries if e.get("stable")] or entries:
    print(entry["url"])
    break
' 2>/dev/null
}

# Newest Fabric file for a Modrinth project that declares $MC_VERSION. Prints "<url> <version>".
#
# Unlike the server-side helper in init-paper.sh this does NOT fall back to a build for another Minecraft
# version: a plugin on a mismatched version is refused by the server and logged, a client mod on a
# mismatched version takes the whole game down at startup. Skipping one mod is the better failure.
modrinth_fabric_download() {
    local slug="$1"
    api_get "$MODRINTH_API/project/$slug/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22$MC_VERSION%22%5D" 2>/dev/null \
        | python3 -c '
import json, sys

versions = json.load(sys.stdin)
if not versions:
    raise SystemExit(0)
# Modrinth returns newest-first; prefer a full release over an alpha/beta of the same version.
chosen = next((v for v in versions if v.get("version_type") == "release"), versions[0])
files = [f for f in chosen["files"] if f.get("primary")] or chosen["files"]
if files:
    print(files[0]["url"], chosen.get("version_number", "?"))
' 2>/dev/null
}

# Drops the mods we manage when the Minecraft pin moved. Only ours, by their fixed names — a hand-installed
# mod in the same folder is the user's, and deleting it is not this script's call.
refresh_mods_on_version_change() {
    [[ -d "$MODS_DIR" ]] || return 0
    local previous=""
    [[ ! -s "$MODS_STAMP" ]] || previous="$(<"$MODS_STAMP")"
    [[ "$previous" != "$MC_VERSION" ]] || return 0
    if [[ -n "$previous" ]]; then
        log "Mods were installed for Minecraft $previous; replacing them for $MC_VERSION"
        local entry
        for entry in "${CLIENT_MODS[@]}"; do
            rm -f "$MODS_DIR/${entry#*:}" 2>/dev/null || true
        done
    fi
}

install_fabric_loader() {
    local loader installer version_dir installer_jar profile_args=()
    loader="$(resolve_fabric_loader)"
    [[ -n "$loader" ]] || die "No Fabric loader lists Minecraft $MC_VERSION"
    RESOLVED_LOADER="$loader"

    version_dir="$MC_DIR/versions/fabric-loader-$loader-$MC_VERSION"
    if [[ -d "$version_dir" && "$FORCE" != "1" ]]; then
        log "Fabric loader $loader for $MC_VERSION already installed"
        return 0
    fi

    installer="$(resolve_fabric_installer)"
    [[ -n "$installer" ]] || die "Could not resolve the Fabric installer from $FABRIC_META/installer"
    installer_jar="$TOOLS_DIR/fabric/$(basename "$installer")"
    [[ -s "$installer_jar" ]] || download_to "$installer" "$installer_jar" \
        || die "Failed to download the Fabric installer"

    # -noprofile always: the installer's own profile would run in the shared ~/.minecraft game directory,
    # which is exactly what GAME_DIR exists to avoid. ensure_launcher_profile writes the profile instead.
    profile_args=(-noprofile)

    log "Installing Fabric loader $loader for Minecraft $MC_VERSION into $MC_DIR"
    mkdir -p "$MC_DIR"
    "$JAVA_BIN" -jar "$installer_jar" client \
        -dir "$MC_DIR" -mcversion "$MC_VERSION" -loader "$loader" "${profile_args[@]}" \
        || die "Fabric installer failed"
}

# True while any launcher (or the game itself) is running out of $MC_DIR.
#
# This matters because launcher_profiles.json is not a config file anyone may edit at will: launchers hold
# their profile list in memory and write the whole file back on their own schedule. Editing it under a
# running launcher does not conflict or fail — the write lands, and is then silently replaced by the
# launcher's copy seconds later, taking with it every profile the launcher does not know about (which is
# every profile an installer added while it was open). Detect it and refuse, rather than report success.
launcher_is_running() {
    pgrep -f "sklauncher|minecraft-launcher|MinecraftLauncher|net\.minecraft\.client\.main\.Main" \
        >/dev/null 2>&1
}

# Adds (or refreshes) one launcher profile pointing at the Fabric version and the isolated game directory.
#
# Additive and backed up: every other profile in the file is copied through untouched, and the previous
# file is kept as launcher_profiles.json.bak-<stamp>.
#
# Note that some third-party launchers (SKLauncher, Prism, MultiMC) do not use this file as their source
# of truth at all — they list whatever is in versions/ and keep their own settings — so the profile is a
# convenience, never the thing that makes the install work. The mods are installed either way.
ensure_launcher_profile() {
    if [[ ! -f "$LAUNCHER_PROFILES" ]]; then
        log "No launcher_profiles.json in $MC_DIR (third-party launcher?). Point your launcher at:"
        log "  version fabric-loader-$RESOLVED_LOADER-$MC_VERSION, game directory $GAME_DIR"
        return 0
    fi
    [[ -n "$RESOLVED_LOADER" ]] || return 0

    if launcher_is_running; then
        log "A launcher (or Minecraft) is running and owns $LAUNCHER_PROFILES — it rewrites the whole"
        log "  file from memory, so a profile written now would be discarded within seconds. Not writing."
        log "  Either close the launcher and re-run \`scripts/install-world-tools.sh client\`, or set this"
        log "  up in the launcher's own UI:"
        log "    version:        fabric-loader-$RESOLVED_LOADER-$MC_VERSION"
        log "    game directory: $GAME_DIR"
        return 0
    fi

    cp "$LAUNCHER_PROFILES" "$LAUNCHER_PROFILES.bak-$(date +%Y%m%d-%H%M%S)"
    PROFILES="$LAUNCHER_PROFILES" NAME="$LAUNCHER_PROFILE_NAME" GAME_DIR="$GAME_DIR" \
    VERSION_ID="fabric-loader-$RESOLVED_LOADER-$MC_VERSION" python3 -c '
import datetime, json, os

path = os.environ["PROFILES"]
name = os.environ["NAME"]
with open(path) as handle:
    data = json.load(handle)

profiles = data.setdefault("profiles", {})
# Keyed by name so a re-run after a Minecraft bump updates the same entry instead of adding another.
key = next((k for k, v in profiles.items() if v.get("name") == name), "sexidium-world-editing")
now = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
existing = profiles.get(key, {})
profiles[key] = {
    **existing,
    "name": name,
    "type": "custom",
    "icon": "Diamond_Pickaxe",
    "created": existing.get("created", now),
    "lastUsed": existing.get("lastUsed", now),
    "lastVersionId": os.environ["VERSION_ID"],
    "gameDir": os.environ["GAME_DIR"],
}

with open(path, "w") as handle:
    json.dump(data, handle, indent=2)
' || die "Could not update $LAUNCHER_PROFILES"
    log "Launcher profile '$LAUNCHER_PROFILE_NAME' -> fabric-loader-$RESOLVED_LOADER-$MC_VERSION (game dir $GAME_DIR)"
    log "  Restart the Minecraft launcher if it was open — it rewrites this file on exit."
}

install_client_mods() {
    mkdir -p "$MODS_DIR"
    refresh_mods_on_version_change

    local entry slug filename label dest resolved url version installed=0
    for entry in "${CLIENT_MODS[@]}"; do
        IFS=: read -r slug filename label <<<"$entry"
        dest="$MODS_DIR/$filename"
        if [[ -s "$dest" && "$FORCE" != "1" ]]; then
            log "$label already present: $dest"
            installed=$((installed + 1))
            continue
        fi
        resolved="$(modrinth_fabric_download "$slug")"
        read -r url version <<<"$resolved" || true
        if [[ -z "${url:-}" ]]; then
            log "No $label build lists Minecraft $MC_VERSION yet; skipping it rather than shipping a jar"
            log "  that would abort the game at launch. Re-run once upstream updates."
            continue
        fi
        log "Downloading $label $version"
        if download_to "$url" "$dest"; then
            installed=$((installed + 1))
        else
            log "Failed to download $label; skipping"
            rm -f "$dest" 2>/dev/null || true
        fi
    done

    printf '%s\n' "$MC_VERSION" > "$MODS_STAMP"
    log "$installed client mod(s) in $MODS_DIR"
}

install_client() {
    need_cmd curl
    need_cmd python3
    need_cmd "$JAVA_BIN"
    install_fabric_loader
    install_client_mods
    ensure_launcher_profile
    log "Client ready. Launch fabric-loader-${RESOLVED_LOADER:-*}-$MC_VERSION with game directory"
    log "  $GAME_DIR (the '$LAUNCHER_PROFILE_NAME' profile, where the launcher accepts one), then:"
    log "  Axiom     — open the editor with its hotkey (default \`, the key above Tab); on the test server"
    log "              it needs op (axiom.all defaults to op)"
    log "  WorldEdit — //wand works in single-player through the mod, and on the server through FAWE"
    log "  Worlds/screenshots/options for this profile live in $GAME_DIR, not ~/.minecraft"
}

# -----------------------------------------------------------------------------
# desktop: MCA Selector
# -----------------------------------------------------------------------------

# MCA Selector's GUI is JavaFX, and JavaFX has not shipped inside the JDK since Java 11 — Temurin 25 (the
# JDK this repo builds and runs the server with) has no javafx.* modules. The failure mode is worth
# knowing because it looks like nothing at all: `java -jar mcaselector.jar` with no JavaFX on the module
# path exits 0, silently, no window and no stack trace. The CLI (--mode/--query) keeps working, which is
# what makes it look like the jar is fine.
#
# Upstream's answer is the .deb/.rpm, which bundle a whole JRE and want root. This instead fetches the
# JavaFX modules from Maven Central into tools/javafx and puts them on the launcher's --module-path: no
# root, no second JDK, and it stays inside the repo.
install_javafx() {
    local dest="$TOOLS_DIR/javafx" version module file
    version="$(api_get "$MAVEN_CENTRAL/org/openjfx/javafx-controls/maven-metadata.xml" 2>/dev/null \
        | python3 -c '
import re, sys

# Release versions only: the metadata also carries early-access builds (27-ea+20, 28-ea+2) which sort
# highest by string but are not something to hand a user as "the desktop editor works now".
versions = [v for v in re.findall(r"<version>([^<]+)</version>", sys.stdin.read())
            if re.fullmatch(r"\d+(\.\d+)*", v)]
versions.sort(key=lambda v: [int(part) for part in v.split(".")])
if versions:
    print(versions[-1])
' 2>/dev/null)"
    [[ -n "$version" ]] || die "Could not resolve a JavaFX version from Maven Central"

    # base+graphics carry the native libraries; controls/fxml/swing/media are what the app links against.
    for module in base graphics controls fxml swing media; do
        file="javafx-$module-$version-linux.jar"
        [[ ! -s "$dest/$file" ]] || continue
        log "Downloading JavaFX $module $version"
        download_to "$MAVEN_CENTRAL/org/openjfx/javafx-$module/$version/$file" "$dest/$file" \
            || die "Failed to download $file"
    done
    # Older versions left behind by a JavaFX bump would sit on the module path beside the new ones and
    # make the JVM refuse to start on a duplicate module.
    shopt -s nullglob
    for file in "$dest"/javafx-*.jar; do
        [[ "$(basename "$file")" == *"-$version-linux.jar" ]] || rm -f "$file"
    done
    shopt -u nullglob
    log "JavaFX $version in $dest"
}

# MCA Selector edits world FOLDERS directly (test/paper/world, test/paper/worlds/**, an unzipped
# assets/worlds/** map): select chunks on a rendered map and delete, prune, relocate or export them. It is
# the tool for "this bundled map carries 400MB of chunks nobody visits", which no in-game editor can do.
install_mcaselector() {
    need_cmd curl
    need_cmd python3
    local dest="$TOOLS_DIR/mcaselector/mcaselector.jar" url

    if [[ -s "$dest" && "$FORCE" != "1" ]]; then
        log "MCA Selector already present: $dest"
    else
        url="$(api_get "$MCASELECTOR_RELEASE" 2>/dev/null | python3 -c '
import json, sys

release = json.load(sys.stdin)
for asset in release.get("assets", []):
    # The platform installers (.deb/.rpm/.exe/.dmg) bundle a JRE and want root; the plain jar does not.
    if asset["name"].endswith(".jar"):
        print(asset["browser_download_url"])
        break
' 2>/dev/null)"
        [[ -n "$url" ]] || die "Could not resolve an MCA Selector jar from $MCASELECTOR_RELEASE"
        log "Downloading MCA Selector"
        download_to "$url" "$dest" || die "Failed to download MCA Selector"
    fi

    install_javafx

    # A wrapper rather than a bare jar: without the --module-path below the GUI does not open at all (see
    # install_javafx), so `tools/mcaselector/mcaselector <world-dir>` is the only invocation worth
    # documenting.
    local launcher="$TOOLS_DIR/mcaselector/mcaselector"
    cat > "$launcher" <<EOF
#!/usr/bin/env bash
# Generated by scripts/install-world-tools.sh — MCA Selector launcher.
# With no argument it opens empty; pass a world directory to load it, e.g.
#   ./mcaselector ../../test/paper/world
# JavaFX is not in the JDK, so it comes from tools/javafx; without it this exits silently with no window.
set -Eeuo pipefail
here="\$(dirname "\$(readlink -f "\$0")")"
exec "\${JAVA_BIN:-java}" \\
    --module-path "\$here/../javafx" \\
    --add-modules javafx.controls,javafx.fxml,javafx.swing,javafx.media \\
    --enable-native-access=javafx.graphics \\
    -jar "\$here/mcaselector.jar" "\$@"
EOF
    chmod +x "$launcher"
    log "MCA Selector ready: $launcher [world-directory]"
}

# -----------------------------------------------------------------------------
# amulet (best effort)
# -----------------------------------------------------------------------------

# Amulet is the block-level offline editor: open a world folder with no game running, select a region,
# copy/paste between worlds, import or export .schem / .litematic, run its operations.
#
# Kept out of the default install and behind its own subcommand because upstream publishes Windows binaries
# and a source-only sdist. On Linux it is a pip build that needs wxPython, which has no Linux wheel on
# PyPI — so this uses the distro's wxPython (apt python3-wxgtk4.0) through a --system-site-packages venv.
# If that package is missing, this stops and says so instead of starting a multi-hour wxWidgets build.
install_amulet() {
    need_cmd python3
    local venv="$TOOLS_DIR/amulet/venv"

    if ! python3 -c 'import wx' >/dev/null 2>&1; then
        log "Amulet needs wxPython, and PyPI publishes no Linux wheel for it. Install the distro build:"
        log "    sudo apt install python3-wxgtk4.0"
        log "  then re-run: scripts/install-world-tools.sh amulet"
        log "  (MCA Selector, installed by the 'desktop' subcommand, needs none of this and covers"
        log "   chunk-level offline work; Amulet is only needed for block-level offline edits.)"
        return 1
    fi

    if [[ ! -d "$venv" ]]; then
        # --system-site-packages is the whole point: the venv must see the apt-installed wx.
        python3 -m venv --system-site-packages "$venv" || die "Could not create $venv"
    fi
    log "Installing amulet-map-editor into $venv (source build; this takes a while)"
    "$venv/bin/pip" install --upgrade pip >/dev/null 2>&1 || true
    if ! "$venv/bin/pip" install amulet-map-editor; then
        log "amulet-map-editor failed to build here. Nothing else is affected — MCA Selector covers"
        log "  offline chunk work, and in-game editing is FAWE/Axiom. Upstream ships no Linux binary."
        return 1
    fi

    local launcher="$TOOLS_DIR/amulet/amulet"
    cat > "$launcher" <<EOF
#!/usr/bin/env bash
# Generated by scripts/install-world-tools.sh — Amulet launcher.
exec "\$(dirname "\$(readlink -f "\$0")")/venv/bin/amulet_map_editor" "\$@"
EOF
    chmod +x "$launcher"
    log "Amulet ready: $launcher"
}

# -----------------------------------------------------------------------------
# status
# -----------------------------------------------------------------------------

report_status() {
    local entry slug filename label
    log "Minecraft version: $MC_VERSION (from ${VERSION_STAMP_SRC/#$ROOT_DIR\//} or MC_VERSION)"
    log "Client (game dir $GAME_DIR):"
    for entry in "${CLIENT_MODS[@]}"; do
        IFS=: read -r slug filename label <<<"$entry"
        if [[ -s "$MODS_DIR/$filename" ]]; then
            log "  [x] $label"
        else
            log "  [ ] $label"
        fi
    done
    log "Desktop ($TOOLS_DIR):"
    if [[ -s "$TOOLS_DIR/mcaselector/mcaselector.jar" ]]; then
        log "  [x] MCA Selector — $TOOLS_DIR/mcaselector/mcaselector"
    else
        log "  [ ] MCA Selector"
    fi
    if [[ -x "$TOOLS_DIR/amulet/amulet" ]]; then
        log "  [x] Amulet — $TOOLS_DIR/amulet/amulet"
    else
        log "  [ ] Amulet (optional: scripts/install-world-tools.sh amulet)"
    fi
    log "Server: FastAsyncWorldEdit + Axiom are installed by scripts/init-paper.sh"
    if [[ -s "$ROOT_DIR/test/paper/plugins/FastAsyncWorldEdit.jar" ]]; then
        log "  [x] FastAsyncWorldEdit"
    else
        log "  [ ] FastAsyncWorldEdit (run scripts/init-paper.sh)"
    fi
    if [[ -s "$ROOT_DIR/test/paper/plugins/AxiomPaper.jar" ]]; then
        log "  [x] Axiom (Paper plugin)"
    else
        log "  [ ] Axiom (Paper plugin) (run scripts/init-paper.sh)"
    fi
}

# The header comment IS the help text — printed from the file rather than duplicated, so the two cannot
# drift apart. Reads from the line after the opening rule to the closing one.
usage() {
    awk 'NR > 4 && /^# -{10,}/ { exit } NR > 4 { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

main() {
    case "${1:-all}" in
        all)     install_client; install_mcaselector; report_status ;;
        client)  install_client ;;
        desktop) install_mcaselector ;;
        amulet)  install_amulet ;;
        status)  report_status ;;
        -h|--help|help) usage ;;
        *)       die "Unknown subcommand: $1 (try: all | client | desktop | amulet | status)" ;;
    esac
}

main "$@"
