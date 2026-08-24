# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/sexidium.sh -- Sexidium-specific build and configuration steps.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_SEXIDIUM:-}" ]]; then return 0; fi
_SX_LIB_SEXIDIUM=1

# Turns Sexidium's BetterHud bridge on (or off) in an existing plugin config. The shipped default is now
# `true` (the pin is 26.1.2, the one version BetterHud's newest shader overlay genuinely covers), so this
# function's job has flipped: it is no longer the thing that turns the bridge ON for the test server, it is
# the thing that turns it OFF when the pin moved somewhere the overlay does not cover. The provisioner
# knows the pin, so it can decide for the config it just generated; the jar's default has to be right for
# whatever the operator is running, and on the pinned version it is.
configure_sexidium_betterhud_if_present() {
    local want="$1" config="$PLUGINS_DIR/Sexidium/config.yml" value
    [[ -f "$config" ]] || return 0
    [[ "$want" == "enabled" ]] && value="true" || value="false"
    log "Setting Sexidium hud.betterhud.enabled: $value (Minecraft $PAPER_VERSION)"
    # Two levels deep now (hud: -> betterhud: -> enabled:), so track both the top-level section and the
    # nested one; matching `enabled:` on indentation alone would hit every other block in the file.
    awk -v value="$value" '
        BEGIN { section = ""; sub_section = "" }
        /^[^[:space:]#][^:]*:/ { section = $1; sub(":", "", section); sub_section = "" }
        /^  [^[:space:]#][^:]*:/ { sub_section = $1; sub(":", "", sub_section) }
        section == "hud" && sub_section == "betterhud" && /^[[:space:]]*enabled:/ { print "    enabled: " value; next }
        { print }
    ' "$config" >"$config.tmp"
    mv "$config.tmp" "$config"
    # An existing config written before the HUD-driver rewrite has a flat `betterhud:` block and no
    # `hud:` section, so the patch above matches nothing and does it silently — which is how a server
    # ends up running a new jar against a config whose keys it no longer reads.
    if ! grep -q "^hud:" "$config"; then
        log "WARNING: $config predates the HUD-driver rewrite — it has no 'hud:' section, so"
        log "  hud.betterhud.enabled is missing and defaults to false. The patch above matched nothing."
        log "  Move the file aside and restart to regenerate it (re-apply your other settings), or add:"
        log "      hud:"
        log "        betterhud:"
        log "          enabled: $value"
    fi
}

# Force `auth.require-for-login: false` in an existing plugin config.
#
# This exists for the STANDALONE test server, where the point is to hop in with any nickname and try a
# minigame without registering first. On a real deployment it is a hole, and it was being punched on every
# node of the network: paper::provision calls this unconditionally, and a network node is provisioned again
# on every `init` run. The first run finds no config (backends have no warm-up boot, so nothing has
# generated one yet) and the guard below returns silently; the config is then extracted from the jar with
# the shipped `auto`. Every LATER run finds the file and rewrites it to `false` — so the gate regressed on
# each redeploy, silently, while the proxy still logged "Login auth gate active" and ran online-mode=false.
# Hence the opt-out: the network stack sets SX_DISABLE_AUTH_GATE=0 and keeps the shipped `auto`.
configure_sexidium_auth_if_present() {
    local config="$PLUGINS_DIR/Sexidium/config.yml"
    [[ -f "$config" ]] || return 0
    [[ "${SX_DISABLE_AUTH_GATE:-1}" == "1" ]] || return 0

    log "Disabling Sexidium login gate in existing plugin config"
    # yaml::set, not awk. The awk rewrote the FIRST `require-for-login:` under the `auth` section by
    # regex, which was fine while `auth:` was flat -- it now has nested `session:`/`premium:`/`hold:`
    # blocks, and a same-named key at any depth would have been silently rewritten too. Addressing
    # the key by path removes the whole class of mistake.
    yaml::set "$config" auth.require-for-login false
}

# Point the menu resource-pack host at this machine's LAN IP so the auto-served pack (the custom Sexidium
# menu art) is reachable by Java clients on the network. Only rewrites the still-default empty host, so
# an admin-set value is never clobbered. The unique 4-space `host:` key lives under ui.resource-pack.
configure_sexidium_menu_pack_if_present() {
    local config="$PLUGINS_DIR/Sexidium/config.yml"
    [[ -f "$config" ]] || return 0
    local ip
    ip="$(host_ipv4 || true)"
    [[ -n "$ip" ]] || return 0
    log "Pointing Sexidium menu resource-pack host at $ip for LAN testing"
    python3 - "$config" "$ip" <<'PY'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
ip = sys.argv[2]
lines = path.read_text().splitlines()

block = [
    "",
    "# ------------------------------------------------------------",
    "# Menu UI / custom GUI art.",
    "# ------------------------------------------------------------",
    "ui:",
    "  resource-pack:",
    "    enabled: true",
    "    url: \"\"",
    "    sha1: \"\"",
    f"    host: \"{ip}\"",
    "    bind: \"0.0.0.0\"",
    "    port: 8788",
    "    required: false",
    "    prompt: \"<gradient:#ff5f6d:#ffc371>Sexidium</gradient> <gray>menu art pack — accept for the full look.</gray>\"",
]

def top_section(name):
    for i, line in enumerate(lines):
        if line == f"{name}:":
            end = len(lines)
            for j in range(i + 1, len(lines)):
                if lines[j] and not lines[j].startswith((" ", "#")):
                    end = j
                    break
            return i, end
    return None

ui = top_section("ui")
if ui is None:
    out = lines + block
else:
    ui_start, ui_end = ui
    rp_start = None
    for i in range(ui_start + 1, ui_end):
        if lines[i].strip() == "resource-pack:" and lines[i].startswith("  "):
            rp_start = i
            break
    if rp_start is None:
        out = lines[:ui_start + 1] + block[5:] + lines[ui_start + 1:]
    else:
        rp_end = ui_end
        for j in range(rp_start + 1, ui_end):
            if lines[j].startswith("  ") and not lines[j].startswith("    ") and lines[j].strip():
                rp_end = j
                break
        host_line = None
        for i in range(rp_start + 1, rp_end):
            if re.match(r"^\s{4}host:\s*", lines[i]):
                host_line = i
                break
        out = list(lines)
        if host_line is None:
            out.insert(rp_end, f"    host: \"{ip}\"")
        elif re.match(r"^\s{4}host:\s*(['\"]{0,1})\1\s*(#.*)?$", lines[host_line]):
            out[host_line] = f"    host: \"{ip}\""

path.write_text("\n".join(out) + "\n")
PY
}

# Seeds the generated Sexidium config for a networked (MySQL/Postgres) backend + the Discord bot from
# environment variables. Used by the docker-compose debug stack so the plugin comes up on Postgres with
# the bot enabled without hand-editing the config. Skipped entirely unless SEXIDIUM_DB_TYPE is set, so a
# plain host `init-paper.sh` run is unaffected. Only runs once the config file exists (after warm-up).
configure_sexidium_networked_backend_if_present() {
    local config="$PLUGINS_DIR/Sexidium/config.yml"
    [[ -f "$config" ]] || return 0
    [[ -n "${SEXIDIUM_DB_TYPE:-}" ]] || return 0
    need_cmd python3
    log "Seeding Sexidium config for ${SEXIDIUM_DB_TYPE} backend + Discord bot (from environment)"
    python3 - "$config" <<'PY'
import os, re, sys
path = sys.argv[1]
lines = open(path, encoding="utf-8").read().splitlines()

def yaml_scalar(value):
    # Leave booleans/integers unquoted so Bukkit's getBoolean/getInt parse them; quote everything else.
    text = str(value)
    if text in ("true", "false") or re.fullmatch(r"-?\d+", text):
        return text
    return "'" + text.replace("'", "''") + "'"

def set_key(section, key, value):
    """Set `section: -> key: value` (2-space indented child), replacing or inserting the line."""
    if value is None:
        return
    yaml_value = yaml_scalar(value)
    header = None
    for i, line in enumerate(lines):
        if line.rstrip() == f"{section}:":
            header = i
            break
    if header is None:
        return
    end = len(lines)
    for j in range(header + 1, len(lines)):
        if lines[j] and not lines[j][0].isspace() and not lines[j].startswith("#"):
            end = j
            break
    for j in range(header + 1, end):
        if re.match(rf"^\s{{2}}{re.escape(key)}:", lines[j]):
            lines[j] = f"  {key}: {yaml_value}"
            return
    lines.insert(end, f"  {key}: {yaml_value}")

env = os.environ.get
set_key("database", "type", env("SEXIDIUM_DB_TYPE"))
set_key("database", "host", env("SEXIDIUM_DB_HOST"))
set_key("database", "port", env("SEXIDIUM_DB_PORT"))
set_key("database", "name", env("SEXIDIUM_DB_NAME"))
set_key("database", "user", env("SEXIDIUM_DB_USER"))
set_key("database", "password", env("SEXIDIUM_DB_PASSWORD"))
if env("SEXIDIUM_BOT_TOKEN"):
    set_key("bot", "enabled", "true")
    set_key("bot", "token", env("SEXIDIUM_BOT_TOKEN"))
    set_key("bot", "download-runtime", env("SEXIDIUM_BOT_DOWNLOAD_RUNTIME", "true"))
    set_key("bot", "runtime-command", env("SEXIDIUM_BOT_RUNTIME", "bun"))
if env("SEXIDIUM_BOT_GUILD_ID"):
    set_key("bot", "guild-id", env("SEXIDIUM_BOT_GUILD_ID"))
if env("SEXIDIUM_API_TOKEN"):
    set_key("api", "token", env("SEXIDIUM_API_TOKEN"))

open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
}

# -----------------------------------------------------------------------------
# Build + report
# -----------------------------------------------------------------------------

# sexidium_menu_pack_content_hash -> sha256 over the CONTENT of every file the zip is
# assembled from ($MENU_ART_DIR/item, $ICONS_DIR, $CHEST_ART_DIR, $SCREEN_ART_DIR).
#
# Content, not mtime: scripts/remote/ops.py re-syncs the whole repo as a tar on every
# `update`, and nothing here guarantees mtimes survive that trip identically -- an
# mtime-based stamp would "hit" or "miss" depending on the sync mechanics rather than
# on whether a single pixel actually changed, which is worse than no cache at all (a
# false hit ships stale art; a false miss just wastes the seconds we're trying to save).
# The four trees are ~11MB across ~380 files as of this writing -- hashing every byte of
# that is a fraction of a second, far cheaper than the Pillow subprocess or the zip
# rewrite this stamp exists to let us skip.
#
# `find ... -type f` (not `-printf`) piped through `sha256sum` per file and sorted
# before the outer hash: sha256sum's own output line is "<hash>  <path>", so sorting
# THAT keys the combination on path, and per-file hashing (rather than one hash over a
# tar/cpio of the tree) means a renamed-but-identical file changes the hash, which is
# the conservative direction to be wrong in here (a spurious rebuild, never a spurious
# skip).
sexidium_menu_pack_content_hash() {
    local dir dirs=()
    for dir in "$MENU_ART_DIR/item" "$ICONS_DIR" "$CHEST_ART_DIR" "$SCREEN_ART_DIR"; do
        [[ -d "$dir" ]] && dirs+=("$dir")
    done
    if [[ "${#dirs[@]}" -eq 0 ]]; then
        printf 'empty'
        return 0
    fi
    find "${dirs[@]}" -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}'
}

# Assemble the texture zip from ./assets/menu-art (button icons, item/<section>/<name>.png),
# ./assets/ui/chest (the per-row chest-frame backgrounds, ui/chest/chest_<rows>.png), and
# ./assets/ui/screens (baked screen backgrounds, ui/screens/<id>.png), then point
# -PmenuPackZip at it. These folders are already laid out at the pack texture paths SexidiumResourcePack
# expects, so the zip is a straight copy. When the PNGs are missing on a fresh checkout and Pillow is
# available they are regenerated — icons from `scripts/art.py gen-menu-art` (re-imports ./icons/), chest
# frames from `scripts/art.py bake-medieval` (UltimateGUI medieval 256x256 canvases). Degrades to
# placeholder art (empty MENU_PACK_ARG) if the textures or python3 are unavailable.
#
# CONTENT-STAMPED (see sexidium_menu_pack_content_hash above): once the sentinel-gated
# regeneration below has run, everything past that point -- the tile-backgrounds Pillow
# subprocess AND the full zip rebuild -- is skipped whenever the four source trees hash
# the same as they did on the last SUCCESSFUL build. On the common path (only Java
# changed) this call used to remount ~380 PNGs into a fresh zip and shell out to Python
# twice, every single provision; now it is one hash and an early return.
build_menu_pack_zip() {
    MENU_PACK_ARG=()
    local have_py=0
    command -v python3 >/dev/null 2>&1 && python3 -c "import PIL" >/dev/null 2>&1 && have_py=1
    # Regenerate the committed icons if any section sentinel is missing (one probe per icon section).
    local icon_sentinels=(item/gui_buttons/button_back_green.png item/system/redo.png
        item/currency/coin_gold_8.png item/elo_ranks/rank_gem_gold_tier1.png
        item/cursors/cursor_arrow.png item/font/char_A.png)
    local missing=0 s
    for s in "${icon_sentinels[@]}"; do [[ -f "$MENU_ART_DIR/$s" ]] || missing=1; done
    if [[ "$missing" -eq 1 && "$have_py" -eq 1 ]]; then
        log "Menu icons absent; generating via art.py $MENU_ART_SUBCOMMAND"
        python3 "$ART_TOOL" "$MENU_ART_SUBCOMMAND" >/dev/null 2>&1 || log "Menu icon import failed; using whatever exists"
    fi
    # Regenerate the chest-frame backgrounds if any row frame is missing.
    missing=0
    for s in chest_1.png chest_3.png chest_6.png; do [[ -f "$CHEST_ART_DIR/$s" ]] || missing=1; done
    if [[ "$missing" -eq 1 && "$have_py" -eq 1 ]]; then
        log "Chest frames absent; importing via art.py $CHEST_ART_SUBCOMMAND"
        python3 "$ART_TOOL" "$CHEST_ART_SUBCOMMAND" >/dev/null 2>&1 || log "Chest-frame import failed; using whatever exists"
    fi
    # Regenerate the medieval typography fonts (title/button caps) if either set is missing.
    missing=0
    for s in item/font_title/char_A.png item/font_button/char_A.png; do [[ -f "$MENU_ART_DIR/$s" ]] || missing=1; done
    if [[ "$missing" -eq 1 && "$have_py" -eq 1 ]]; then
        log "Typography fonts absent; slicing via art.py $TYPO_ART_SUBCOMMAND"
        python3 "$ART_TOOL" "$TYPO_ART_SUBCOMMAND" >/dev/null 2>&1 || log "Typography slice failed; using whatever exists"
    fi
    if [[ ! -d "$MENU_ART_DIR/item" ]] || [[ ! -d "$CHEST_ART_DIR" ]]; then
        log "Menu/chest art not found; menu pack will use placeholder art"
        return 0
    fi
    if [[ "$have_py" -eq 0 ]] && ! command -v python3 >/dev/null 2>&1; then
        log "python3 unavailable; menu pack will use placeholder art"
        return 0
    fi

    # Stamp check. Computed BEFORE tile-backgrounds runs, over whatever the four source
    # trees look like right now (post sentinel-regeneration, pre-tiling) -- that is the
    # same state the stamp was written FROM on the previous successful run (see below),
    # so a truly-unchanged tree compares equal here even though tile-backgrounds' own
    # .row strip output also lives inside CHEST_ART_DIR/SCREEN_ART_DIR.
    local stamp="$MENU_PACK_ZIP.stamp" want_hash
    want_hash="$(sexidium_menu_pack_content_hash)"
    if [[ -s "$MENU_PACK_ZIP" && -f "$stamp" && "$(cat "$stamp" 2>/dev/null)" == "$want_hash" ]]; then
        log "Menu texture pack content unchanged (stamp match); reusing $MENU_PACK_ZIP"
        MENU_PACK_ARG=(-PmenuPackZip="$MENU_PACK_ZIP")
        return 0
    fi

    # Split the 768px chest/screen backgrounds into <=256px font-glyph row strips. A single >256px bitmap
    # glyph cannot stitch into Minecraft's 256x256 glyph atlas and renders BLANK, so the live menu paints the
    # strips (one font provider per tile-row, reassembled by the title-trick — see MenuArt.TILE_*).
    # Deterministic; always re-slices from the 768px sources (never from a .row strip), so a just-regenerated
    # background can never leave stale strips before assembly.
    if [[ "$have_py" -eq 1 && -f "$CHEST_ART_DIR/chest_6.png" ]]; then
        python3 "$ART_TOOL" tile-backgrounds >/dev/null 2>&1 ||
            log "Background tiling failed; menu backgrounds may not render (>256px glyph)"
    fi

    mkdir -p "$(dirname "$MENU_PACK_ZIP")"
    rm -f "$MENU_PACK_ZIP"
    # Args: <menu-art dir> <chest dir> <screen dir> <out zip> <icons dir>. Legacy sheet icons live under
    # <menu-art>/item; the minigame/experience/ui sets (incl. *_disabled) ship straight from <icons dir>.
    # BOTH map onto pack path item/<section>/<name>.png (matching IconModel.texturePath()); chest/screen
    # backgrounds -> ui/chest|screens/<name>.png (matching MenuArt.Glyph.texturePath()).
    if python3 - "$MENU_ART_DIR" "$CHEST_ART_DIR" "$SCREEN_ART_DIR" "$MENU_PACK_ZIP" "$ICONS_DIR" <<'PY'; then
import os, sys, zipfile
menu_dir, chest_dir, screen_dir, out, icons_dir = sys.argv[1:6]
count = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(os.path.join(menu_dir, "item")):
        for name in sorted(files):
            if name.endswith(".png"):
                full = os.path.join(root, name)
                z.write(full, os.path.relpath(full, menu_dir).replace(os.sep, "/"))
                count += 1
    # assets/icons/<section>/<name>.png -> item/<section>/<name>.png
    for root, _, files in os.walk(icons_dir):
        for name in sorted(files):
            if name.endswith(".png"):
                full = os.path.join(root, name)
                z.write(full, "item/" + os.path.relpath(full, icons_dir).replace(os.sep, "/"))
                count += 1
    for name in sorted(os.listdir(chest_dir)):
        if name.endswith(".png"):
            z.write(os.path.join(chest_dir, name), "ui/chest/" + name)
            count += 1
    if os.path.isdir(screen_dir):
        for name in sorted(os.listdir(screen_dir)):
            if name.endswith(".png"):
                z.write(os.path.join(screen_dir, name), "ui/screens/" + name)
                count += 1
raise SystemExit(0 if count else 1)
PY
        log "Assembled Sexidium texture pack (icons + chest frames + baked screens) -> $MENU_PACK_ZIP"
        MENU_PACK_ARG=(-PmenuPackZip="$MENU_PACK_ZIP")
        # Stamped ONLY after the zip is fully assembled (this line is unreachable on a
        # failed assembly -- the `if` above took the `else` branch instead), and by
        # write-to-temp + rename so a process killed mid-write never leaves a stamp
        # claiming a hash it did not finish committing. Re-hash rather than reuse
        # $want_hash: tile-backgrounds may have just added or rewritten .row strip
        # files inside CHEST_ART_DIR/SCREEN_ART_DIR, and the NEXT run's pre-tiling
        # hash needs to see the tree in the state THIS run leaves it in, not the state
        # it started from -- storing $want_hash here would make the stamp never match
        # again even when nothing whatsoever changes.
        local new_hash
        new_hash="$(sexidium_menu_pack_content_hash)"
        printf '%s\n' "$new_hash" >"$stamp.new"
        mv -f "$stamp.new" "$stamp"
    else
        log "Failed to assemble menu texture pack (no PNGs?); using placeholder art"
        # No zip, so no stamp can describe one: a stale stamp here would make the NEXT
        # run's early-return check believe a zip exists that this run just deleted.
        rm -f "$stamp"
    fi
}

# sexidium::install_jar <src-jar> <dest-dir>
#
# Publish a freshly built jar into a plugins/ (or proxy) directory, keeping its basename.
#
# Write-to-temp + rename, never `cp` over the live file, and the reason is not tidiness. A running server
# holds the jar OPEN: Paper's URLClassLoader lazily reads class entries out of the zip for the whole life
# of the process. `cp` truncates and rewrites the SAME inode, so the loader's next read lands in a file
# whose central directory has moved — the symptom is a ZipException or NoClassDefFoundError hours later,
# in the middle of a minigame, with nothing in the logs pointing at the deploy that caused it. `mv` within
# the same filesystem is a rename: the open file keeps its old inode until the last handle closes, and the
# next JVM start opens the new one. Restarting a node is what picks the new jar up, exactly as before.
#
# The `.new` temp lives in the destination directory ON PURPOSE — rename is only atomic within a
# filesystem, and a temp under /tmp could be on another one.
sexidium::install_jar() {
    local src="$1" dir="$2" dest
    dest="$dir/$(basename "$src")"
    mkdir -p "$dir"
    cp "$src" "$dest.new"
    mv -f "$dest.new" "$dest"
}

# sexidium::dry_stub_jar <dest-jar>
#
# A REAL zip carrying a minimal config.yml, written in place of the Gradle output when
# SEXIDIUM_DRY_RUN=1. It exists so the rehearsal exercises the REAL downstream code
# rather than a second, dry-only branch of it: sexidium::ensure_config genuinely
# extracts config.yml out of this file, yaml::set genuinely patches it, and
# paper::link_shared_sexidium_config genuinely publishes and re-links it.
#
# The skeleton is a skeleton on purpose -- every top-level section the provisioner
# writes into, and nothing else. Two of the writers (the python `set_key` inside
# configure_sexidium_networked_backend_if_present, and the awk in
# configure_sexidium_betterhud_if_present) only ever REPLACE a line inside an existing
# section and return silently when the header is absent, so a section missing here
# would make the harness green over a patch that did nothing.
sexidium::dry_stub_jar() {
    local dest="$1"
    need_cmd python3
    mkdir -p "$(dirname "$dest")"
    python3 - "$dest" <<'PY_STUB'
import sys, zipfile

CONFIG = """# Dry-run stub config (scripts/lib/sexidium.sh: sexidium::dry_stub_jar).
network:
  enabled: false
  node:
    id: 'standalone'
    role: standalone
    address: '127.0.0.1'
    port: 25565
api:
  enabled: true
  port: 8787
  rpc-port: 8789
  token: 'change-me-please'
bot:
  enabled: false
  token: ''
database:
  type: 'sqlite'
  host: '127.0.0.1'
  port: 3306
  name: 'sexidium'
  user: 'sexidium'
  password: ''
auth:
  require-for-login: auto
ui:
  resource-pack:
    enabled: true
    host: ''
    port: 8788
worlds:
  map-bundle:
    extract-if-missing: true
    refresh-when-changed: true
hud:
  betterhud:
    enabled: true
messages:
  default-language: en
"""

PLUGIN = "name: Sexidium\nversion: 1.0.0\nmain: com.sexidium.paper.SexidiumPlugin\n"

# A FIXED mtime on every entry, because the store's build id is the sha256 of this
# file: zipfile stamps `now` by default, so the default would give the same source a
# different id on every run and the golden trace would never be stable.
def entry(name):
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    return info

with zipfile.ZipFile(sys.argv[1], "w") as jar:
    jar.writestr(entry("config.yml"), CONFIG)
    jar.writestr(entry("plugin.yml"), PLUGIN)
PY_STUB
}

build_and_copy_plugin() {
    # docker/provision.sh builds once up front and sets this, so N parallel instances do not each
    # enter Gradle (and contend on one --project-cache-dir) just to be told nothing changed.
    if [[ "${SX_SKIP_BUILD:-0}" == "1" ]]; then
        sexidium::install_jar "$SEXIDIUM_JAR" "$PLUGINS_DIR"
        log "Copied $(basename "$SEXIDIUM_JAR") into $PLUGINS_DIR"
        return 0
    fi
    build_menu_pack_zip
    # Trace only WHETHER a menu pack was assembled: its path is a scratch temp dir
    # under the harness, and an absolute path would make the golden trace unstable.
    local menu_pack_state="none"
    [[ "${#MENU_PACK_ARG[@]}" -gt 0 ]] && menu_pack_state="present"
    sx_trace "gradle build lobbyWorldZip=assets/worlds/lobbies/Medieval-BreadBuilds.zip menuPack=$menu_pack_state"
    if sx_dry; then
        # The build is exercised by the Java test suite, not by the provisioner harness.
        # What IS installed is a real (tiny) zip, so every consumer of the jar downstream
        # runs its production code path -- see sexidium::dry_stub_jar.
        mkdir -p "$PLUGINS_DIR"
        sexidium::dry_stub_jar "$PLUGINS_DIR/$(basename "$SEXIDIUM_JAR")"
        sx_trace "copy plugins/$(basename "$SEXIDIUM_JAR")"
        return 0
    fi
    mkdir -p "$GRADLE_PROJECT_CACHE_DIR"
    # SX_RUN_TESTS=1 devolve a suíte ao provisionamento; o default PULA os testes.
    #
    # O que se perde, dito com todas as letras: os testes eram o portão que impedia código quebrado
    # de virar um build no ar. Foi ele que barrou um deploy desta rede quando o WIRE_DIGEST do
    # protocolo não batia -- a rede ficou intacta porque o `init` abortou. Sem ele, um jar quebrado
    # é staged, pinado e reiniciado nos nós, e a primeira notícia disso é um servidor que não sobe.
    # A troca é deliberada (o build cai de ~12 min para ~2), e o contrapeso é rodar `./gradlew build`
    # na máquina de quem deploya ANTES de chamar o provisionamento.
    local -a gradle_targets=(build)
    if [[ "${SX_RUN_TESTS:-0}" != "1" ]]; then
        gradle_targets=(build -x test)
        log "SX_RUN_TESTS != 1: pulando os testes no provisionamento (rode ./gradlew build localmente antes)"
    fi
    # SX_GRADLE_NO_DAEMON=1 (docker/node-entry.sh sets it for every containerized node;
    # a plain local run of scripts/init-paper.sh leaves it unset). The `init` container
    # runs exactly ONE gradlew invocation per lifetime and then exits -- and exiting a
    # container's PID-1 process tears down its whole PID namespace, which kills any
    # Gradle daemon it forked regardless of Gradle's normal survive-the-parent daemon
    # design. $GRADLE_USER_HOME persists across runs (it is on the sexidium-build
    # volume), so the daemon REGISTRY on disk survives, but the daemon PROCESSES it
    # points at never do -- every `init` run finds only dead PIDs in that registry, which
    # is the literal cause of the "N incompatible and N stopped Daemons could not be
    # reused" warning seen in production logs. A daemon can structurally never be reused
    # here, so paying to fork and handshake with one and then discard it is pure loss:
    # --no-daemon runs the build in the launcher JVM directly, skipping that fork+socket
    # handshake and the entire daemon-registry dance (so the warning stops appearing
    # too). Left OFF by default for the non-containerized path, where a developer
    # re-running ./gradlew by hand on a persistent host genuinely benefits from a warm
    # daemon across runs, same as before this change.
    local -a gradle_flags=()
    [[ "${SX_GRADLE_NO_DAEMON:-0}" == "1" ]] && gradle_flags+=(--no-daemon)
    log "Building plugins with ./gradlew --project-cache-dir $GRADLE_PROJECT_CACHE_DIR ${gradle_flags[*]:-} ${gradle_targets[*]} -PlobbyWorldZip=assets/worlds/lobbies/Medieval-BreadBuilds.zip ${MENU_PACK_ARG[*]:-}"
    (cd "$ROOT_DIR" && ./gradlew --project-cache-dir "$GRADLE_PROJECT_CACHE_DIR" "${gradle_flags[@]}" "${gradle_targets[@]}" -PlobbyWorldZip=assets/worlds/lobbies/Medieval-BreadBuilds.zip "${MENU_PACK_ARG[@]}") || die "Gradle build failed"
    [[ -s "$SEXIDIUM_JAR" ]] || die "Build succeeded but jar not found: $SEXIDIUM_JAR"

    sexidium::install_jar "$SEXIDIUM_JAR" "$PLUGINS_DIR"
    sx_trace "copy plugins/$(basename "$SEXIDIUM_JAR")"
    log "Copied $(basename "$SEXIDIUM_JAR") into $PLUGINS_DIR"
}

# Write this instance's place in the network into its Sexidium config.
#
# sexidium::seed_node_identity <config> <node-id> <role> <api-base>
#
# Standalone never calls this: init-paper.sh leaves network.enabled at its shipped
# false, and every Db* port resolves to its Local* implementation.
# Make sure plugins/Sexidium/config.yml exists WITHOUT booting the server.
#
# It used to appear as a side effect of the Geyser warm-up boot. Backends behind a proxy no longer
# run Geyser, so that boot is gone -- and with it the config, which meant node identity was silently
# never seeded and every backend came up standalone. Extract the default straight from the jar
# instead: same file the plugin would have written, no 20s boot.
sexidium::ensure_config() {
    local config="$PLUGINS_DIR/Sexidium/config.yml"
    if [[ -f "$config" ]]; then
        return 0
    fi
    local jar
    # On a network the jar lives in the SHARED install, not in this PLUGINS_DIR --
    # that directory holds data folders only, and the config being extracted is one
    # of them. Standalone leaves SEXIDIUM_JAR_INSTALLED empty and keeps the old path.
    jar="${SEXIDIUM_JAR_INSTALLED:-$PLUGINS_DIR/$(basename "$SEXIDIUM_JAR")}"
    [[ -s "$jar" ]] || return 1
    need_cmd python3
    mkdir -p "$PLUGINS_DIR/Sexidium"
    python3 - "$jar" "$config" <<'PY_EXTRACT' || return 1
import shutil, sys, zipfile

with zipfile.ZipFile(sys.argv[1]) as jar, open(sys.argv[2], "wb") as out:
    with jar.open("config.yml") as src:
        shutil.copyfileobj(src, out)
PY_EXTRACT
    log "Extracted default config.yml from the plugin jar"
}

sexidium::seed_node_identity() {
    local config="$1" node_id="$2" role="$3" api_base="$4"
    yaml::set "$config" \
        network.enabled true \
        network.node.id "$node_id" \
        network.node.role "$role" \
        api.port "$api_base" \
        api.rpc-port "$((api_base + 2))"

    # Exactly one node hosts the Discord bot and exactly one serves the resource
    # pack. Four backends each starting a bot would open four Discord gateways on
    # one token; four pack hosts would hand clients four different SHA-1s and
    # re-prompt on every server switch.
    # The bot needs BOTH a token and a networked database -- the plugin refuses to enable at all on
    # bot+SQLite, so switching it on unconditionally for the bot node disabled Sexidium there
    # entirely and the node never registered. Enable only when it can actually work.
    local bot_capable=0
    case "${SEXIDIUM_DB_TYPE:-}" in
        mysql | postgres) [[ -n "${SEXIDIUM_BOT_TOKEN:-}" ]] && bot_capable=1 ;;
    esac
    if [[ "$node_id" == "${SX_BOT_NODE:-lobby}" && "$bot_capable" -eq 1 ]]; then
        yaml::set "$config" bot.enabled true
    else
        yaml::set "$config" bot.enabled false
        if [[ "$node_id" == "${SX_BOT_NODE:-lobby}" ]]; then
            log "Discord bot left disabled on $node_id (needs SEXIDIUM_BOT_TOKEN and a mysql/postgres database)"
        fi
    fi
    # The bot's relay channels, plus the fallback channel for a login approval when the player's
    # Discord DMs are closed. LOG/EVENTS have been read by the bot since the RPC relays landed and
    # were never injected by BotManager, so both relays were silently dead in production.
    [[ -z "${SEXIDIUM_BOT_LOG_CHANNEL_ID:-}" ]] ||
        yaml::set "$config" bot.log-channel-id "$SEXIDIUM_BOT_LOG_CHANNEL_ID"
    [[ -z "${SEXIDIUM_BOT_EVENTS_CHANNEL_ID:-}" ]] ||
        yaml::set "$config" bot.events-channel-id "$SEXIDIUM_BOT_EVENTS_CHANNEL_ID"
    [[ -z "${SEXIDIUM_AUTH_CHANNEL_ID:-}" ]] ||
        yaml::set "$config" auth.approval.channel-id "$SEXIDIUM_AUTH_CHANNEL_ID"

    # The gate's feature flags, mirrored onto backends so the fail-closed backend gate agrees with
    # the proxy about which layers are on.
    #
    # auth.premium.enabled is deliberately NOT mirrored: online-mode is per-connection at the proxy
    # and nowhere else, so a backend cannot verify anybody -- it sees every arrival as unverified,
    # and enforcing protect-verified-names there would refuse exactly the premium players the proxy
    # had just admitted.
    yaml::set "$config" \
        auth.session.enabled "${SX_AUTH_SESSIONS:-false}" \
        auth.approval.enabled "${SX_AUTH_APPROVAL:-false}" \
        auth.hold.enabled "${SX_AUTH_HOLD:-false}"

    # The IP-hash pepper MUST be identical on every node: the proxy mints sessions keyed by it and a
    # backend looks them up keyed by the same value, so a pepper set on the proxy alone desyncs every
    # hash and the backend sees no session at all. It is a single stack-level env (SX_AUTH_IP_PEPPER)
    # mirrored onto every backend here, exactly as velocity.sh mirrors it onto the proxy. Left unset
    # everywhere, both sides fall back to the same api.token-derived value -- the footgun was setting
    # it on one side only.
    [[ -z "${SX_AUTH_IP_PEPPER:-}" ]] ||
        yaml::set "$config" auth.session.ip-pepper "$SX_AUTH_IP_PEPPER"

    yaml::set "$config" ui.resource-pack.enabled false

    # Map templates are seeded ONCE, by the `init` container, into the shared tree
    # every node reaches through worlds/<bundle> (paper::seed_shared_maps). Leaving
    # the plugin's own seeding on would put four JVMs back in charge of one directory:
    # extract-if-missing would have each node try to fill a folder the others are
    # filling, and refresh-when-changed would have whichever node boots first MOVE A
    # TEMPLATE ASIDE and re-extract it -- while another node is copying that same
    # folder for a match that is starting. The clone then fails and degrades into a
    # generated world carrying the real map's team coordinates: no error, and players
    # spawned into stone or into the void.
    #
    # The keys exist for exactly this case and say so in config.yml ("set false to
    # manage the map folders yourself"), so this costs no Java at all. Standalone
    # (init-paper.sh) never reaches this function and keeps the shipped `true`, which
    # is right: there, the plugin IS the only writer.
    yaml::set "$config" \
        worlds.map-bundle.extract-if-missing false \
        worlds.map-bundle.refresh-when-changed false

    # Same reason, different plugin: the shipped default is now `true` (see hud.betterhud in
    # config.yml), and on a NETWORK node that default would be actively harmful. BetterHud renders its
    # HUDs in a custom font that only exists in the resource pack it self-hosts, and it advertises that
    # pack at the address it discovers for itself — inside the compose network that is a container-local
    # address (172.x), which no player's client can reach. The download fails silently and the readout
    # arrives as a boss bar of unknown-character boxes: precisely the symptom the whole BetterHud gate
    # exists to prevent, only now with the switch ON. Off until the pack is genuinely reachable, which
    # means publishing BetterHud's port on the node that serves it and advertising the PUBLIC host.
    # Standalone (init-paper.sh) never comes through here and keeps the shipped `true`.
    yaml::set "$config" hud.betterhud.enabled false

    log "Seeded node identity: $node_id (role=$role, api=$api_base)"
}
