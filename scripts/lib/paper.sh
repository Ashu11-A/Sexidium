# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/paper.sh -- provisioning and launching a Paper backend instance.
#
# paper::defaults    seeds every tunable, honouring the environment first so the
#                    documented `SERVER_DIR=... scripts/init-paper.sh` contract is
#                    unchanged.
# paper::provision   the 5-phase provisioning pipeline, in the order main() ran it.
#                    That ORDER is load-bearing -- see the "AFTER the warm-up"
#                    comment below, which records a bug where a config patch
#                    silently no-opped because it ran before the file existed.
# paper::start_foreground  execs the JVM; never returns.
# -----------------------------------------------------------------------------

if [[ -n "${_SX_LIB_PAPER:-}" ]]; then return 0; fi
_SX_LIB_PAPER=1

# Every path that hangs off SERVER_DIR, recomputed on demand.
#
# Split out of paper::defaults so ONE process can provision several backends:
# docker/provision.sh points SERVER_DIR at the next instance and calls this again.
# The single-server script could hold these at file scope because it managed
# exactly one server -- that, plus the hardcoded `trap cleanup EXIT`, is what
# made it structurally single-instance.
# shellcheck disable=SC2034  # cross-library globals; see paper::defaults
paper::derive_paths() {
    PLUGINS_DIR="$SERVER_DIR/plugins"
    PAPER_JAR="$SERVER_DIR/paper.jar"
    GEYSER_JAR="$PLUGINS_DIR/Geyser-Spigot.jar"
    GEYSER_CONFIG="$PLUGINS_DIR/Geyser-Spigot/config.yml"
    FANCYNPCS_JAR="$PLUGINS_DIR/FancyNpcs.jar"
    FANCYHOLOGRAMS_JAR="$PLUGINS_DIR/FancyHolograms.jar"
    MULTIVERSE_JAR="$PLUGINS_DIR/Multiverse-Core.jar"
    # SkinsRestorer: gives players a working /skin in offline/LAN mode and is the skin source the chest-GUI
    # player heads read, so a friend/party head shows that player's real skin instead of the default Steve.
    SKINSRESTORER_JAR="$PLUGINS_DIR/SkinsRestorer.jar"

    # BetterHud: the only surface that can draw text in the TOP-LEFT corner (vanilla has none — boss bars are
    # top-centre, the action bar bottom-centre, the scoreboard on the right). Death Resets renders its
    # played/days/resets readout there.
    #
    # NOT INSTALLED BY DEFAULT as of Minecraft 26.2, and this is a correctness problem rather than taste.
    # BetterHud draws its HUD by REPLACING the client's vanilla core shaders
    # (assets/minecraft/shaders/core/rendertype_text.vsh/.fsh) in the resource pack it sends. Which set it
    # sends comes from a hardcoded pack-format table in the plugin (its PackOverlay enum), whose last entry
    # in the newest published build (2.1.0-SNAPSHOT-447) is:
    #
    #     betterhud_26_1  ->  pack formats 84..99
    #
    # Minecraft 26.2 is pack format 88, so a 26.2 client gets 26.1's shaders. Every piece of GUI text goes
    # through rendertype_text, so the fallout is not confined to BetterHud: wrong text colours, and text and
    # buttons missing from vanilla screens like the pause menu. A Modrinth build listing 26.2 means the
    # plugin loads, not that its shaders match, so there is no version to upgrade to.
    #
    # Set INSTALL_BETTERHUD=1 to install it anyway; Sexidium's own hud.betterhud.enabled now ships true, so
    # the readout is driven through it as soon as the jar is there. Without the jar the readout renders on
    # the scoreboard sidebar, the fallback every Bedrock player already used, costing only the corner
    # placement. SX_SKIP_BETTERHUD=1 is the opposite lever: skip the install even on a covered pin.
    BETTERHUD_JAR="$PLUGINS_DIR/BetterHud.jar"
    FAWE_JAR="$PLUGINS_DIR/FastAsyncWorldEdit.jar"
    AXIOM_JAR="$PLUGINS_DIR/AxiomPaper.jar"
    FAWE_SCHEMATICS_DIR="$PLUGINS_DIR/FastAsyncWorldEdit/schematics"
    CONSOLE_FIFO="$SERVER_DIR/.init-paper-console"
    VERSION_STAMP="$SERVER_DIR/.mc-version"
}

# SC2034: every assignment below is a deliberate cross-library global -- the jar
# paths are read by lib/plugins.sh, the art paths by lib/sexidium.sh, the API
# bases by lib/papermc.sh and lib/modrinth.sh. shellcheck analyses one file at a
# time and cannot see those readers, so it reports each as unused.
# shellcheck disable=SC2034
paper::defaults() {
    SERVER_DIR="${SERVER_DIR:-$ROOT_DIR/test/paper}"
    SEXIDIUM_JAR="${SEXIDIUM_JAR:-$ROOT_DIR/build/libs/paper/Sexidium-Paper-1.0.0.jar}"
    # Pinned to 26.1.2 rather than 26.2 ON PURPOSE, and the reason is BetterHud (F62).
    #
    # BetterHud draws its HUD by replacing the client's core text shaders, and picks which set to send from a
    # hardcoded pack-format table. Its newest build's last entry is `betterhud_26_1` -> formats 84..99.
    # 26.1.2 IS format 84 — the version that overlay was written for: same `rendertype_text.vsh/.fsh` file
    # names, same unconditional lightmap path. 26.2 is format 88 and renamed those shaders to `text.*` and
    # split them into IS_GUI / IS_SEE_THROUGH variants, so the same overlay corrupts every vanilla GUI screen
    # there. Staying on 26.1.2 is what makes the Death Resets corner readout work at all.
    #
    # The plugin itself builds and runs on BOTH (paper-api is pinned to 26.1.2 and api-version to '26.1', so
    # Paper 26.2 loads it with legacy shims). Bump this back to 26.2 when upstream ships a betterhud_26_2
    # overlay, and bump PACK_FORMAT / plugin.yml / build.gradle.kts with it — see docs/known-issues.md F62.
    PAPER_VERSION="${PAPER_VERSION:-26.1.2}"
    # Minecraft 26.x refuses to boot on an older JVM (both 26.1.2 and 26.2 ask for java-runtime major 25).
    REQUIRED_JAVA="${REQUIRED_JAVA:-25}"
    JAVA_BIN="${JAVA_BIN:-java}"
    JAVA_ARGS="${JAVA_ARGS:--Xms1G -Xmx2G}"

    # Some agent-created worktrees have a .gradle/ directory owned by a different OS user. Force Gradle's
    # project cache into /tmp by default so init-paper remains runnable by the current shell user.
    GRADLE_PROJECT_CACHE_DIR="${GRADLE_PROJECT_CACHE_DIR:-${TMPDIR:-/tmp}/sexidium-gradle-project-cache-${USER:-$(id -u)}}"

    # World editing. Neither plugin is a runtime dependency of Sexidium — they are the tooling used to BUILD
    # what this repo bundles (assets/worlds/**: the lobby and the TNT War battle maps) and to paste downloaded
    # .schem builds into a map. Both run server-side, so the whole workflow stays in-game on Linux instead of
    # needing a Windows-only desktop editor.
    #
    #   FastAsyncWorldEdit — a drop-in WorldEdit superset (//wand, //copy, //paste, //schem load|save, //stack,
    #                        brushes, //undo) reading the schematic folder synced below.
    #   Axiom              — the visual half: Blender-style sculpting, blueprints, an infinite editing history.
    #                        It needs the Axiom CLIENT mod, which `scripts/install-world-tools.sh client`
    #                        installs; a vanilla client that never sends its plugin-channel handshake is
    #                        unaffected by the plugin being present.
    #
    # Both are op-gated by their own permissions, and this server boots with an empty ops.json — `op <you>` in
    # the console before reaching for //wand. INSTALL_WORLDEDIT=0 / INSTALL_AXIOM=0 skip them for a lean boot.
    INSTALL_WORLDEDIT="${INSTALL_WORLDEDIT:-1}"
    INSTALL_AXIOM="${INSTALL_AXIOM:-1}"
    # Schematics tracked in the repo, synced into FAWE's own folder so `//schem load <name>` finds them.
    # test/paper/ is gitignored, so assets/schematics/ is the only place a .schem survives a server wipe.
    SCHEMATICS_SRC="${SCHEMATICS_SRC:-$ROOT_DIR/assets/schematics}"

    # Which Minecraft version the downloaded jars were provisioned for. Everything that fetches a jar
    # short-circuits on "file already exists", so without this a PAPER_VERSION bump would silently keep
    # serving the previous version's jars. See refresh_jars_on_version_change.
    WARMUP_TIMEOUT="${WARMUP_TIMEOUT:-180}"
    STOP_TIMEOUT="${STOP_TIMEOUT:-90}"

    # PaperMC retired the v2 API (it answers 410 Gone now); v3 — "fill" — is the current one. It also hands
    # back a ready-to-use object URL plus a sha256 for every build, which is why this script no longer needs
    # the old two-step build lookup or the papermc.io HTML-scraping fallback that backed it up.
    PAPER_API="https://fill.papermc.io/v3/projects/paper"
    GEYSER_DOWNLOAD_URL="https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
    MODRINTH_API="https://api.modrinth.com/v2"
    # Both APIs ask callers to identify themselves and rate-limit generic agents harder.
    HTTP_UA="${HTTP_UA:-sexidium-init-paper/1.0 (sexidium test-server provisioner)}"

    # Menu art textures: Sexidium's own custom GUI icons + background panels live under ./assets
    # (item/ icon textures, ui/chest frames, ui/screens panels — one PNG per minigame, experience challenge,
    # menu button and hotbar nav item, plus per-screen panels). build_menu_pack_zip zips them and feeds
    # -PmenuPackZip to the build so the chest-GUI resource pack ships this real art instead of generated
    # placeholders. Regenerate the icon PNGs with `scripts/art.py gen-menu-art`.
    MENU_ART_DIR="${MENU_ART_DIR:-$ROOT_DIR/assets}"
    ICONS_DIR="${ICONS_DIR:-$ROOT_DIR/assets/icons}"
    CHEST_ART_DIR="${CHEST_ART_DIR:-$ROOT_DIR/assets/ui/chest}"
    SCREEN_ART_DIR="${SCREEN_ART_DIR:-$ROOT_DIR/assets/ui/screens}"
    # The unified art toolchain (scripts/art.py) regenerates committed textures via subcommands.
    ART_TOOL="${ART_TOOL:-$ROOT_DIR/scripts/art.py}"
    MENU_ART_SUBCOMMAND="${MENU_ART_SUBCOMMAND:-gen-menu-art}"     # re-imports assets/icons -> assets/item
    CHEST_ART_SUBCOMMAND="${CHEST_ART_SUBCOMMAND:-bake-medieval}"  # imports medieval generic_<slots> -> assets/ui/chest
    TYPO_ART_SUBCOMMAND="${TYPO_ART_SUBCOMMAND:-slice-typography}" # slices medieval font sheets -> item/font_{title,button}
    MENU_PACK_ZIP="${MENU_PACK_ZIP:-$ROOT_DIR/build/sexidium-menu-pack.zip}"
    # Filled by build_menu_pack_zip; an empty array = build with placeholder art.
    MENU_PACK_ARG=()

    # Where the Sexidium jar was actually INSTALLED, when that is not inside
    # $PLUGINS_DIR. Empty means "inside PLUGINS_DIR", which is the standalone
    # layout and the only one init-paper.sh ever sees. The network sets it to the
    # shared install, whose plugins/ holds the jars while each node's plugins/
    # holds only data folders -- see paper::provision_shared_install.
    SEXIDIUM_JAR_INSTALLED="${SEXIDIUM_JAR_INSTALLED:-}"

    # Tracks the managed warm-up server so the EXIT trap can always reap it.
    WARMUP_PID=""

    # Replaces the old file-scope `trap cleanup EXIT`.
    sx::on_exit console::cleanup

    paper::derive_paths
}

paper::provision() {
    need_cmd sed
    need_cmd awk
    need_cmd mkfifo
    need_cmd "$JAVA_BIN"

    log "Initializing Paper test server for Minecraft $PAPER_VERSION"
    sx_trace "phase preflight version=$PAPER_VERSION java=$REQUIRED_JAVA"
    ensure_java_version

    # 1. artifacts. A Minecraft bump invalidates every downloaded jar, so clear them before the ensure_*
    #    helpers get a chance to short-circuit on the previous version's files.
    sx_trace "phase artifacts"
    refresh_jars_on_version_change
    build_and_copy_plugin
    ensure_paper

    # 2. base configuration (must exist before any boot)
    sx_trace "phase base-config"
    configure_eula_and_server
    configure_sexidium_auth_if_present

    # 3. plugin dependencies (hard deps abort on failure)
    sx_trace "phase plugins"
    ensure_plugins

    # 4. warm up once for Geyser's config, then patch it. The warm-up boot also generates Sexidium's
    #    config, so point the menu resource-pack host at the LAN IP now that the file exists.
    sx_trace "phase warmup-and-patch"
    warmup_for_geyser_config
    configure_geyser_if_present
    # Gated on BetterHud being PRESENT, not on us having just installed it. Tying this to
    # INSTALL_BETTERHUD (which means "install on an unsupported version anyway") meant the supported
    # path — 26.1.2, auto-installed, no flag — never configured BetterHud at all: it kept
    # enable-self-host: false and so served the pack it had just built to nobody, leaving every client
    # with no font and a row of unknown-character boxes where its demo hud drew.
    configure_betterhud_if_present
    # AFTER the warm-up, not inside ensure_plugins: plugins/Sexidium/config.yml does not exist until a
    # boot has generated it, so the earlier call hit its `[[ -f ]]` guard and returned silently on every
    # fresh server — leaving hud.betterhud.enabled at its shipped value no matter what the pin was. That
    # value is now `true`, which is why the ordering still matters and matters the other way round: this
    # is the call that has to be able to turn the bridge OFF on a pin the overlay does not cover.
    if betterhud_overlay_matches && [[ -s "$BETTERHUD_JAR" ]]; then
        configure_sexidium_betterhud_if_present enabled
    else
        configure_sexidium_betterhud_if_present disabled
    fi
    configure_sexidium_menu_pack_if_present
    # Docker/debug: point the plugin at the networked DB + enable the bot from the environment.
    configure_sexidium_networked_backend_if_present
    [[ -f "$GEYSER_CONFIG" ]] || log "No Geyser config after warm-up; continuing without patching"

    # 5. report + foreground start
    sx_trace "phase report-and-start"
    print_progress_report
}

paper::start_foreground() {
    sx_trace "exec java $JAVA_ARGS -jar $(sx_rel "$PAPER_JAR") nogui"
    if sx_dry; then
        log "Dry run: not starting the server"
        return 0
    fi
    log "Starting Paper. Stop it with Ctrl+C or the 'stop' console command."

    # Drop the EXIT trap before exec: nothing left to clean, and the real server
    # owns the terminal from here on.
    trap - EXIT
    rm -f "$CONSOLE_FIFO"
    cd "$SERVER_DIR" || die "Server directory vanished before start: $SERVER_DIR"
    # Word splitting on $JAVA_ARGS is deliberate: it carries multiple JVM flags.
    # shellcheck disable=SC2086
    exec "$JAVA_BIN" $JAVA_ARGS -jar "$PAPER_JAR" nogui
}

# -----------------------------------------------------------------------------
# Networked-backend configuration (used by docker/provision.sh, never by standalone)
# -----------------------------------------------------------------------------

# Enable Velocity modern forwarding on a backend.
#
# ORDERING TRAP: config/paper-global.yml does not exist until Paper has booted
# once. The same `[[ -f ]]`-guard-returns-silently failure already bit the
# BetterHud path (see the "AFTER the warm-up" note in paper::provision). Rather
# than pay a warm-up boot per backend, seed a MINIMAL paper-global.yml up front --
# Paper merges any missing keys from its defaults on first load, so a partial file
# is accepted and then rewritten complete.
paper::configure_velocity_forwarding() {
    local secret="$1" config="$SERVER_DIR/config/paper-global.yml"
    if [[ -z "$secret" ]]; then
        # Standalone: leave proxies.velocity.enabled at its shipped false.
        return 0
    fi
    need_cmd python3
    mkdir -p "$SERVER_DIR/config"
    if [[ ! -f "$config" ]]; then
        sx_trace "seed paper-global.yml proxies-block"
        cat >"$config" <<'EOF'
# Seeded by docker/provision.sh before first boot so forwarding is active on
# the very first connection. Paper fills in every other key from its defaults.
_version: 29
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: ''
EOF
    fi
    yaml::set "$config" \
        proxies.velocity.enabled true \
        proxies.velocity.online-mode true \
        proxies.velocity.secret "$secret"
    log "Velocity forwarding enabled for $(basename "$SERVER_DIR")"
}

# -----------------------------------------------------------------------------
# The shared install: one paper.jar, one set of plugin jars, for every backend.
# -----------------------------------------------------------------------------

# Provision $SX_SHARED_INSTALL: paper.jar, the plugin jars, and the paperclip
# repository (libraries/ versions/ cache/). ONE copy, read by all four backends.
#
# Runs SERIALLY, before docker/provision.sh fans out. That is not a style choice:
# every ensure_* helper short-circuits on `[[ -s "$dest" ]]`, and with a single
# destination four parallel subshells would enter that window at the same instant
# -- the same race download_to's flock already prevents for the cache, but now on
# the final path. Serial also means the jars either all land or the run aborts,
# which shrinks a partial failure from "three nodes on the new build, one on the
# old" down to "no deploy happened".
#
# Nothing per-node is decided here. The nodes' own directories are built by
# paper::provision_instance, which downloads nothing at all.
paper::provision_shared_install() {
    SERVER_DIR="$SX_SHARED_INSTALL"
    paper::derive_paths
    # The two names must agree: docker/node-entry.sh passes SX_SHARED_PLUGINS to
    # --add-extra-plugin-dir without ever sourcing this file, so a drift between
    # them is a network that boots with no plugins and no error worth the name.
    [[ "$PLUGINS_DIR" == "$SX_SHARED_PLUGINS" ]] ||
        die "SX_SHARED_PLUGINS ($SX_SHARED_PLUGINS) is not $SX_SHARED_INSTALL/plugins; the nodes would load nothing"
    mkdir -p "$SERVER_DIR" "$PLUGINS_DIR"

    # Backends behind the proxy never run the Bedrock listener -- the proxy owns it.
    # shellcheck disable=SC2034  # read by ensure_geyser in lib/plugins.sh
    INSTALL_GEYSER=0
    # BetterHud IS installed on the network now, and the thing that changed is the only
    # thing that ever blocked it: the pack is reachable. It self-hosts its font over HTTP
    # and advertises the address it discovers for itself, which inside compose is a 172.x
    # container IP no client can reach -- so the font never arrived and the readout was a
    # row of unknown-character boxes. Each node now publishes its pack port on the host
    # loopback (stack.sexidium.yml) inside the range frpc maps 1:1 to the public host, and
    # SX_PACK_HOST_IP is what gets advertised.
    #
    # Still gated on the version pin: ensure_plugins only installs it when
    # betterhud_overlay_matches, because on a version its shader overlay does not cover the
    # pack corrupts every piece of vanilla GUI text (F62). Leave that gate alone.
    #
    # No public host configured = nothing reachable = keep the old behaviour exactly.
    if [[ -z "${SX_PACK_HOST_IP:-}" ]]; then
        # shellcheck disable=SC2034  # read by ensure_plugins in lib/plugins.sh
        SX_SKIP_BETTERHUD=1
    fi

    paper::prune_unwanted_shared_jars
    refresh_jars_on_version_change
    # NOT guarded by SX_SKIP_BUILD: this is the one build, and the single install of
    # its output. Everything downstream copies nothing -- the nodes read this file.
    build_and_copy_plugin
    ensure_paper
    ensure_plugins
    paper::warm_paperclip_repo
    log "Shared install ready: $SX_SHARED_INSTALL"
}

# BUILD-ONLY mode (SX_PROVISION_MODE=build).
#
# Runs the Gradle build and nothing else: the artifact lands in the store and no node
# directory, no shared plugin tree and no provisioning stamp is touched. That is the
# entire point -- it is the stage a rolling update runs FIRST, and a compile failure
# there has to leave the live network byte-identical to how it found it.
#
# A mode flag rather than a second entrypoint, matching the shape docker/node-entry.sh
# already uses: one file, one env var selecting behaviour.
paper::build_only() {
    SERVER_DIR="$SX_BUILD_STORE/.build"
    paper::derive_paths
    mkdir -p "$PLUGINS_DIR"
    build_and_copy_plugin
    local paper_jar
    paper_jar="$PLUGINS_DIR/$(basename "$SEXIDIUM_JAR")"
    [[ -s "$paper_jar" ]] || die "build-only: no $paper_jar after the Gradle build"
    paper::verify_jar_readable "$paper_jar"

    local velocity_jar="${SEXIDIUM_VELOCITY_JAR:-$ROOT_DIR/build/libs/velocity/$SX_VELOCITY_JAR_NAME}"
    if [[ -s "$velocity_jar" ]]; then
        paper::verify_jar_readable "$velocity_jar" velocity-plugin.json
    else
        velocity_jar=""
        log "No proxy plugin at $velocity_jar; the build carries the Paper jar only"
    fi

    SX_BUILD_ID="$(store::stage "$paper_jar" "$velocity_jar")"
    log "build-only complete: $SX_BUILD_ID"
    printf '%s\n' "$SX_BUILD_ID"
}

# A truncated jar is a real, observed failure mode and it is invisible until a JVM
# opens it: the file exists, `[[ -s ]]` is happy, and the plugin fails to load hours
# later. Cheapest possible proof that the zip is whole and is the right zip.
# O descritor é ARGUMENTO porque as duas plataformas não usam o mesmo: o Paper declara
# um plugin em `plugin.yml`, o Velocity em `velocity-plugin.json`. Fixar `plugin.yml`
# aqui fazia esta função reprovar TODO jar de proxy íntegro -- e como só o caminho do
# build store (paper::build_only) valida o jar do Velocity, e esse caminho é o do
# `pipeline`, o erro ficou latente enquanto os deploys eram por `update`.
paper::verify_jar_readable() {
    local jar="$1" marker="${2:-plugin.yml}" listing
    sx_dry && return 0
    command -v unzip >/dev/null 2>&1 || return 0
    # SEM pipe, e a razão é a inversão mais cruel possível: `unzip -l | grep -q` termina
    # o grep no PRIMEIRO acerto, o unzip morre de SIGPIPE (141) e o `pipefail` de
    # provision.sh transforma o ACERTO em falha. O tamanho decide -- com listagem curta o
    # unzip acaba de escrever antes de o grep sair e nada acontece, então isto passou por
    # meses e só apareceu quando o jar chegou a 30 MB / 1354 entradas, derrubando um
    # provisionamento com "not a readable jar" sobre um jar íntegro.
    listing="$(unzip -l "$jar" 2>/dev/null)" || listing=""
    [[ "$listing" == *"$marker"* ]] ||
        die "$jar is not a readable jar containing $marker (truncated build output?)"
}

# Stage what provision_shared_install just built into the versioned store, then take
# the Sexidium jar OUT of the shared plugin tree.
#
# That removal is the migration, and it is the whole design in one line: while the jar
# lived in $SX_SHARED_PLUGINS every node loaded it through --add-extra-plugin-dir and
# there was exactly one file, so "roll worker-3 forward and leave the rest" had nowhere
# to be expressed. It now lives in the store and each node reaches ITS build through a
# symlink in its own pluginjars/.
paper::stage_build() {
    local paper_jar
    paper_jar="$SX_SHARED_PLUGINS/$(basename "$SEXIDIUM_JAR")"
    if [[ ! -s "$paper_jar" ]]; then
        # Already migrated (a re-provision after the first one): the shared tree has no
        # Sexidium jar and the store is where it comes from.
        paper_jar="${SX_BUILD_JAR:-}"
        [[ -s "$paper_jar" ]] || die "paper::stage_build: no Sexidium jar to stage"
    fi
    paper::verify_jar_readable "$paper_jar"
    SX_BUILD_ID="$(store::stage "$paper_jar" "${VELOCITY_PLUGIN_JAR:-}")"
    SX_BUILD_JAR="$(store::path "$SX_BUILD_ID")/$(basename "$SEXIDIUM_JAR")"
    export SX_BUILD_ID SX_BUILD_JAR

    # Same shape and spirit as paper::link_shared_install's `find … -delete`: a jar left
    # here would be loaded through --add-extra-plugin-dir alongside the pinned one, and
    # Paper refuses a duplicate plugin name -- the node would come up with neither.
    rm -f "$SX_SHARED_PLUGINS"/Sexidium-Paper-*.jar
    store::snapshot_deps "$SX_BUILD_ID"
    log "Build $SX_BUILD_ID staged; the shared plugin tree now holds third-party jars only"
}

# Deletes jars from the shared install that this run does not want there.
#
# Skipping a DOWNLOAD has never removed anything: that is why FAWE and Axiom ran on
# all four production nodes for months while the stack said INSTALL_WORLDEDIT=0 --
# an earlier run had installed them and no code path ever looked again. With one
# install tree that is finally a three-line fix, so the declared intent becomes the
# state on disk instead of a comment about it.
#
# Only the shared tree, and only jars this provisioner is the author of. A node's
# own plugins/ is handled by paper::link_shared_install.
paper::prune_unwanted_shared_jars() {
    # SX_SKIP_BETTERHUD is phrased as a SKIP, so invert it into the same "1 = keep"
    # shape the INSTALL_* flags use rather than teaching the helper two polarities.
    local want_betterhud=1
    [[ "${SX_SKIP_BETTERHUD:-0}" != "1" ]] || want_betterhud=0

    paper::drop_jar_unless "${INSTALL_WORLDEDIT:-1}" "$FAWE_JAR"
    paper::drop_jar_unless "${INSTALL_AXIOM:-1}" "$AXIOM_JAR"
    paper::drop_jar_unless "$want_betterhud" "$BETTERHUD_JAR"
    paper::drop_jar_unless "${INSTALL_GEYSER:-1}" "$GEYSER_JAR"

    # The one lever that can re-resolve a Modrinth "latest". ensure_modrinth_plugin
    # returns early on `[[ -s "$dest" ]]` and nothing ever re-asks, so a jar resolved
    # once is frozen for the life of the tree -- fine for reproducibility, useless
    # when the point of the run is to pick up a plugin update. Opt-in because the
    # default has to stay "do not silently change what is running".
    [[ "${SX_REFRESH_PLUGINS:-0}" == "1" ]] || return 0
    log "SX_REFRESH_PLUGINS=1: dropping the third-party jars so Modrinth is re-resolved"
    rm -f "$MULTIVERSE_JAR" "$FANCYNPCS_JAR" "$FANCYHOLOGRAMS_JAR" "$SKINSRESTORER_JAR"
}

# paper::drop_jar_unless <wanted:0|1> <jar-path>
# Removes the jar when this run does not want it. Anything other than an explicit
# "0" keeps it, so a misspelt flag can never silently uninstall a plugin.
paper::drop_jar_unless() {
    local wanted="$1" jar="$2"
    [[ "$wanted" == "0" ]] || return 0
    [[ -e "$jar" ]] || return 0
    log "Removing $(basename "$jar") from the shared install (not wanted on this network)"
    rm -f "$jar"
}

# Materialises libraries/ versions/ cache/ inside the shared install.
#
# Paperclip resolves those three RELATIVE TO THE WORKING DIRECTORY and Paper offers
# no flag to move them, which is why each node reaches them through a symlink
# (paper::link_shared_install). Populating them HERE, once, is what keeps four JVMs
# from racing to download and patch the same server jar on a cold start.
#
# "Read-only at runtime" is ALMOST true and the exception is worth knowing: Paper's
# plugin library loader resolves the `libraries:` a plugin declares in its plugin.yml
# -- for Sexidium the JDBC drivers -- into <cwd>/libraries/, i.e. into this shared
# tree, at plugin load. Measured on a real boot here: the artifacts land once and
# every later boot only rewrites maven-resolver's small metadata beside them. So do
# NOT chmod this tree read-only, and see paper::link_shared_install for how a
# migrating node donates the copy it already has instead of racing for a fresh one.
paper::warm_paperclip_repo() {
    [[ -d "$SX_SHARED_INSTALL/libraries" && -d "$SX_SHARED_INSTALL/versions" ]] && return 0
    if sx_dry; then
        # The harness never has a real jar to run; make the directories so a
        # rehearsal still produces the layout the nodes expect.
        mkdir -p "$SX_SHARED_INSTALL"/{libraries,versions,cache}
        return 0
    fi
    log "Populating the shared libraries/versions/cache (paperclip, ~160MB, once)…"
    # --version first because it is the cheap answer if it happens to be enough;
    # --help is the one measured to download and patch. Both are argument-parsing
    # front doors that exit before binding a port, so neither can collide with a
    # running node.
    local probe
    for probe in --version --help; do
        [[ -d "$SX_SHARED_INSTALL/libraries" ]] && break
        (cd "$SX_SHARED_INSTALL" && "$JAVA_BIN" -jar paper.jar "$probe" >/dev/null 2>&1) || true
    done
    # die, not warn. A shared tree without libraries/ leaves every node pointing a
    # symlink at nothing, and a failed init deliberately preserves the previous
    # stamp (docker/node-entry.sh), so aborting here keeps the live network exactly
    # as it was instead of shipping a broken layout to it.
    [[ -d "$SX_SHARED_INSTALL/libraries" ]] ||
        die "paperclip did not populate $SX_SHARED_INSTALL/libraries; refusing to ship a shared install the nodes cannot boot"
    mkdir -p "$SX_SHARED_INSTALL/cache"
}

# Points a node's working directory at the shared install.
#
# libraries/ versions/ cache/ are read-only at runtime and resolved relative to the
# CWD with no flag to move them, so a symlink is the only way to aim them at the
# shared tree. paper.jar is symlinked for a different reason: it keeps
# `-jar paper.jar` working from the node's CWD while there is only ONE file on disk
# to be out of date.
#
# Recreated on every provision so a directory carrying the old layout (real
# directories, four real jars) is converted rather than left half-migrated.
paper::link_shared_install() {
    local item
    for item in libraries versions cache paper.jar; do
        # `derived` -- everything here is re-downloadable or re-patchable from the
        # network, so a local copy that survives the merge is redundant by
        # definition and gets removed. Contrast paper::link_shared_maps.
        paper::donate_and_link "$SERVER_DIR/$item" "$SX_SHARED_INSTALL/$item" "$item" derived
    done

    # A plugin jar inside the node's own plugins/ would be loaded TWICE -- once via
    # --plugins and once via --add-extra-plugin-dir -- and Paper refuses a duplicate
    # plugin name, so the node would come up with neither copy. A tree migrated from
    # the old layout has exactly that, which makes this line the migration itself.
    # The data folders beside them (Sexidium/, Multiverse-Core/, FancyNpcs/…) are the
    # node's live state and are never touched.
    find "$PLUGINS_DIR" -maxdepth 1 -name '*.jar' -delete
}

# paper::donate_and_link <local-path> <shared-path> <label> <derived|irreplaceable>
#
# The migration primitive: turn a node-local path into a symlink at the shared tree,
# handing whatever the node already has to that tree first. Extracted from
# paper::link_shared_install (which is still its first caller) so the map templates
# migrate by the SAME rule instead of a second, subtly different one.
#
# Donation is `cp -an` -- no-clobber, so the shared tree always wins and this can
# never downgrade it. What changes with the last argument is what happens to a local
# copy that the merge did NOT absorb, i.e. a file whose bytes differ from the shared
# one:
#
#   derived         delete it. libraries/versions/cache/paper.jar are re-downloadable
#                   and re-patchable; a divergent copy is stale, not precious.
#   irreplaceable   MOVE IT ASIDE and record what diverged. A map template can carry
#                   the only copy of work done in-game -- baked blocks from
#                   /sx admin map edit, or a sexidium-battlemap.yml / sexidium-tntwar.yml
#                   sidecar that exists on exactly one node and in no jar. `cp -an`
#                   alone would silently pick a winner among the nodes (whichever
#                   subshell got there first) and `rm -rf` would destroy the rest --
#                   the exact loss A4/F11 names. Losing a deploy's worth of disk is
#                   recoverable; losing a hand-built map is not.
paper::donate_and_link() {
    local target="$1" shared="$2" label="$3" mode="$4"
    local node divergence stamp aside
    node="$(basename "$SERVER_DIR")"

    if [[ -e "$target" && ! -L "$target" ]]; then
        # Only a directory can be merged. A stray FILE at this path (the old layout's
        # real paper.jar) has nothing to donate and falls through to the disposal
        # below, where `derived` deletes it and `irreplaceable` keeps it aside.
        if [[ -d "$target" ]]; then
            log "Merging $label from $node into the shared tree"
            mkdir -p "$shared"
            cp -an "$target/." "$shared/" 2>/dev/null || true
        fi

        divergence=""
        if [[ "$mode" == "irreplaceable" ]]; then
            divergence="$(paper::tree_divergence "$target" "$shared")"
        fi

        if [[ -n "$divergence" ]]; then
            stamp="$(date +%Y%m%d-%H%M%S)"
            # NOT ".replaced-<ts>": MapBundle prunes that suffix inside the map tree,
            # and this is precisely the copy nothing may prune.
            aside="$target.local-$stamp"
            mv "$target" "$aside"
            printf '%s\n' "$divergence" >"$aside.divergent.txt"
            log "KEPT $label from $node: it differs from the shared tree in $(printf '%s\n' "$divergence" | wc -l) file(s)"
            log "  moved to $(basename "$aside"); the differing paths are listed in $(basename "$aside").divergent.txt"
            log "  Re-export anything you want to keep into assets/worlds/ -- the shared copy is what the nodes now read."
        else
            log "Dropping local $label from $node (now shared)"
            rm -rf "${target:?}"
        fi
    fi
    ln -sfn "$shared" "$target"
}

# paper::tree_divergence <local-dir> <shared-dir>
#
# Prints, one per line, every path under <local-dir> whose bytes are NOT already
# present and identical under <shared-dir>. Empty output means the local copy is
# fully mirrored and therefore safe to delete.
#
# One-directional on purpose: the shared tree legitimately holds MORE than any one
# node (three maps' worth of donations, another node's sidecar), and that is not a
# reason to preserve this node's copy. Byte comparison, not mtime: the four nodes
# extract the same jar independently, so their directory timestamps always differ
# while their contents never do -- mistaking that for divergence is what made the
# maps look like they had drifted in the first place.
paper::tree_divergence() {
    local local_dir="$1" shared_dir="$2" file rel listing
    [[ -d "$local_dir" ]] || return 0
    # O `find` escreve num arquivo e o STATUS dele é conferido. Com `< <(find ...)` esse
    # status é INVISÍVEL -- `pipefail` não cobre substituição de processo -- e um find que
    # falha no meio (subárvore ilegível, erro de I/O) devolvia divergência VAZIA. Vazio
    # aqui significa "espelhado, pode apagar", e o chamador roda `rm -rf` na árvore que
    # ele nunca conseguiu enumerar. Falha tem de significar "não sei", nunca "não há nada".
    listing="$(mktemp)" || {
        printf '%s\n' "<could not allocate a listing for $local_dir>"
        return 0
    }
    if ! find "$local_dir" -type f -print0 >"$listing" 2>/dev/null; then
        rm -f "$listing"
        # Divergência NÃO-vazia: o chamador move de lado em vez de apagar. Preservar uma
        # cópia que talvez fosse redundante custa disco; apagar uma que talvez fosse única
        # custa o que ninguém pode devolver.
        printf '%s\n' "<unreadable: $local_dir>"
        return 0
    fi
    while IFS= read -r -d '' file; do
        rel="${file#"$local_dir/"}"
        cmp -s "$file" "$shared_dir/$rel" 2>/dev/null || printf '%s\n' "$rel"
    done <"$listing"
    rm -f "$listing"
}

# Seeds the map templates bundled in the plugin jar into the ONE shared tree.
#
# Called by docker/provision.sh, from the `init` container, after the shared install
# exists and BEFORE any node is provisioned or started. That ordering is the whole
# design (A5 §5.2): `init` is the only writer that has no players online, whose
# failure is already handled (`.provision-failed` keeps the nodes on the previous
# tree) and which the nodes already block on (`run/.provisioned`). A node writing
# these folders at boot instead would move a template aside while another node is
# mid-clone of it, and the clone failure degrades SILENTLY into a vanilla world.
#
# The rule lives in Java, not here: MapBundleCli is the same MapBundle the plugin
# runs, so the manifest digest / .sexidium-map-bundle stamp / move-aside logic has
# exactly one implementation. Re-implementing ~20 lines of it in shell would put the
# second copy in the container that is hardest to test.
paper::seed_shared_maps() {
    local jar sha stamp
    # The pinned build's jar once the store exists, the shared tree's copy before the
    # migration. MapBundleCli is the SAME MapBundle the plugin runs, so it must come
    # from the build the nodes are about to run -- seeding templates out of a different
    # build is how a map's manifest digest and the node reading it drift apart.
    jar="${SX_BUILD_JAR:-$SX_SHARED_PLUGINS/$(basename "$SEXIDIUM_JAR")}"
    mkdir -p "$SX_SHARED_MAPS"

    # Traced even though the dry branch below skips the work: the POSITION of this call
    # (after the build that produces the jar, before the node fan-out that reads the
    # templates) is the invariant, and a golden trace that omits the operation cannot
    # catch a reordering of it.
    sx_trace "seed_shared_maps $(sx_rel "$SX_SHARED_MAPS")"
    if sx_dry; then
        log "Dry run: not seeding map templates into $SX_SHARED_MAPS"
        return 0
    fi
    [[ -s "$jar" ]] ||
        die "No Sexidium jar at $jar; cannot seed the shared map templates"

    # Content stamp: MapBundleCli boots a WHOLE JVM to compare a manifest digest it
    # already knows how to compare -- pure JVM-startup cost, paid on every provision,
    # even the ones where the jar bundling the map templates is byte-identical to the
    # one that seeded $SX_SHARED_MAPS last time. Skip the boot when the jar's sha256
    # matches the stamp left by the last SUCCESSFUL seed.
    #
    # The sha256 is store::stage's, not recomputed here: paper::stage_build runs
    # immediately before this (docker/provision.sh) and already hashed this exact jar
    # to mint $SX_BUILD_ID, writing the full sha256 into that build's manifest --
    # rehashing a ~50-60MB jar a second time would spend the very seconds this stamp
    # exists to save. Falls back to hashing the jar directly only when SX_BUILD_ID is
    # unset, i.e. a caller reaching this function outside the normal
    # stage_build-then-seed order.
    if [[ -n "${SX_BUILD_ID:-}" ]]; then
        sha="$(store::manifest_get "$SX_BUILD_ID" sha256)"
    fi
    [[ -n "${sha:-}" ]] || sha="$(store::sha256 "$jar")"
    stamp="$SX_SHARED_MAPS/.seeded-$sha"

    # CONSERVATIVE ON PURPOSE. This is keyed on the JAR'S BYTES, not on whether the map
    # assets inside it changed: a jar whose only change is Java code still gets a new
    # sha and a full MapBundleCli re-run -- a few wasted seconds, never a wasted seed.
    # Skipping a seed that was genuinely needed reproduces exactly the move-aside-
    # while-mid-clone failure this function's own header warns about: a match
    # generating a vanilla world on top of a real team's coordinates. That costs a
    # save, not a few seconds, so this stamp only ever widens what gets redone, never
    # what gets skipped.
    if [[ -f "$stamp" ]]; then
        log "Shared map templates already seeded from this build ($sha); skipping MapBundleCli"
        return 0
    fi

    log "Seeding bundled map templates into $SX_SHARED_MAPS"
    # Deliberately WITHOUT --exit-code. "Nothing to do" is the steady state -- every
    # re-provision after the first one is a no-op -- and under `set -e` an exit 3
    # would turn the normal path into a failed deploy. So any non-zero IS fatal:
    # exit 1 means a half-seeded directory, and a node must never boot against one.
    "$JAVA_BIN" -cp "$jar" com.sexidium.core.world.MapBundleCli "$SX_SHARED_MAPS" ||
        die "MapBundleCli failed (exit $?) seeding $SX_SHARED_MAPS; refusing to provision nodes against a half-seeded map tree"

    # Stamped ONLY after MapBundleCli exits 0 -- a jar that dies mid-seed must be
    # retried on the very next run, never remembered as done. Old stamps are removed
    # first (harmless glob, this directory holds nothing else named .seeded-*) so a
    # redeploy onto a jar with a different sha doesn't leave one marker file behind
    # forever per build; the final marker itself is an empty file whose NAME is the
    # sha, written via temp-then-rename so a crash between `rm` and `mv` leaves zero
    # stamps (safe: the next run just re-seeds) rather than a half-written one.
    rm -f "$SX_SHARED_MAPS"/.seeded-*
    : >"$stamp.tmp"
    mv -f "$stamp.tmp" "$stamp"
}

# Points ONE node at the shared map templates.
#
# THE symlink is per BUNDLE FOLDER (worlds/tntwar), never at worlds/ itself, and this
# is the single most expensive thing to get wrong in this file. The core contract
# puts temp/ (worldRoot()/temp) and experience/ (worldRoot()/experience) INSIDE the
# world root; on Paper an override moves them elsewhere, but nothing declares that
# override as an invariant. Share the ROOT and one node's stale-temp cleanup deletes
# the LIVE match worlds of the other three (A4 F7). Sharing only the bundle folders
# keeps that impossible by construction, whatever the override does later.
#
# Idempotent: a node already linked is `[[ -L ]]`, so nothing is merged, nothing is
# moved and the loop just refreshes the link.
paper::link_shared_maps() {
    local worlds="$SERVER_DIR/worlds" bundle name
    [[ -n "${SX_SHARED_MAPS:-}" && -d "$SX_SHARED_MAPS" ]] || return 0

    # die, not repair: a symlinked worlds/ is the catastrophic layout above, and the
    # provisioner has no way to know which node owns the live worlds behind it. A
    # failed init preserves the previous stamp and leaves the running network alone,
    # which is the correct outcome for a layout nobody in this repo creates.
    [[ ! -L "$worlds" ]] ||
        die "$worlds is a symlink; the world ROOT must stay node-local (only worlds/<bundle> is shared)"
    mkdir -p "$worlds"

    for bundle in "$SX_SHARED_MAPS"/*/; do
        [[ -d "$bundle" ]] || continue # no match: the glob stayed literal
        name="$(basename "$bundle")"
        paper::donate_and_link "$worlds/$name" "$SX_SHARED_MAPS/$name" "worlds/$name" irreplaceable
    done
}

# Point this node's EXPERIENCE worlds at the tree every node shares.
#
# This is what makes the workers interchangeable rather than each being the permanent
# home of whatever it opened first: with one tree behind all of them, an idle world can
# be taken over by whichever worker is least loaded when the next player asks for it.
# The arbitration is NOT here -- it is the placement lease in the database
# (WorldPlacementService), and it is what keeps "shared folder" from meaning "two
# servers writing the same region files". This function only makes the bytes reachable.
#
# The link is at world/dimensions/experiences, NEVER at world/ or world/dimensions:
# `minecraft` (this node's own overworld/nether/end) and `sexidium_temp` (its live match
# worlds) are siblings in that directory, one server owns each of them, and each node
# deletes stale temp worlds at boot. Sharing the parent would have one node's cleanup
# delete another's live match -- the same rule, and the same reason, as worlds/<bundle>.
#
# The marker file is the interlock: the plugin only ever takes a world over when it can
# read this file, because the configuration cannot establish that a folder is shared and
# acting on a wrong belief there generates an empty world over somebody's save.
paper::link_shared_experiences() {
    local dims="$SERVER_DIR/world/dimensions" target="${SX_SHARED_WORLDS:-}/experiences"
    [[ -n "${SX_SHARED_WORLDS:-}" ]] || return 0

    [[ ! -L "$dims" ]] ||
        die "$dims is a symlink; only world/dimensions/experiences may be shared (temp worlds live beside it)"
    mkdir -p "$target" "$dims"

    # Minecraft REFUSES to boot with a symlink inside a level directory, and it is not a warning:
    # LevelStorageSource.validateAndCreateAccess throws ContentValidationException and the server
    # exits. The check exists because a world archive can otherwise carry a link that writes outside
    # the world folder, so the fix is not to defeat it -- it is to declare this one target, which is
    # what allowed_symlinks.txt is for. Written per node, next to server.properties, where vanilla
    # reads it. Without this line every backend crash-loops the moment the link appears.
    printf '# Sexidium: the shared experience-world tree (paper::link_shared_experiences).\n[prefix]%s\n' \
        "$SX_SHARED_WORLDS" >"$SERVER_DIR/allowed_symlinks.txt"
    # Written before the link, so a node that reads it has by definition reached the
    # shared tree -- the file cannot exist in a node-local folder this function created.
    printf 'shared-world-storage\nwritten-by=%s\n' "${SX_LOG_TAG:-provision}" >"$target/.shared-storage"
    paper::donate_and_link "$dims/experiences" "$target" "world/dimensions/experiences" irreplaceable
}

# Points this node's plugins/Sexidium/config.yml at the ONE shared copy.
#
# The four backends' configs were byte-identical except for six values describing the node
# itself, and those now travel as -D arguments (see the identity block in
# paper::provision_instance). What is left is genuinely one file, so it becomes one file:
# an option changed here is in force on every node at the next boot, instead of being
# changed on the lobby and silently forgotten on three workers.
#
# ONLY config.yml. The rest of plugins/ is per-node state that must NOT be shared -- most
# sharply Multiverse-Core/worlds.yml, which is a per-node registry of loaded worlds
# (measured live: 3526 / 5412 / 3444 / 3444 lines). Multiverse autoloads what is on its
# books BEFORE Sexidium is enabled, so a shared registry makes every node open every other
# node's worlds with no placement claim and no session.lock covering a keyed dimension
# folder -- two JVMs on one set of region files. Logs and the per-install bStats /
# FancyAnalytics ids are per-node for the same reason: they are state, not configuration.
paper::link_shared_sexidium_config() {
    local shared_dir="$SX_SHARED_INSTALL/config/Sexidium"
    local shared="$shared_dir/config.yml"
    local local_config="$PLUGINS_DIR/Sexidium/config.yml"

    mkdir -p "$shared_dir" "$(dirname "$local_config")"
    # First node to arrive donates the file it just generated; the rest link to it. Same
    # donate-don't-race shape the install and world trees already use, so a cold provision
    # does not depend on which node ran first.
    # PUBLISHED ON EVERY PROVISION, not only the first. The node edited a local copy (the link was
    # broken up in provision_instance), so a "seed only if absent" rule would strand every later
    # change -- turning hud.betterhud.enabled on would have edited four local files and left the
    # shared one untouched. All four nodes make the same edits, so last-writer-wins is correct;
    # the write is a rename, so a reader ever sees one whole file or the other, never a partial.
    if [[ -s "$local_config" && ! -L "$local_config" ]]; then
        # Keyed on the NODE TAG, which is what makes it per-subshell. `$$` was the original
        # bug: the nodes provision in parallel subshells and `$$` is the parent's PID,
        # identical in all four, so every node staged to the same path and each `rm -f`
        # deleted a sibling's file mid-write. $BASHPID fixed that but added a per-run
        # random component to a path the golden trace has to compare, and it was never the
        # thing providing uniqueness: there is exactly ONE subshell per node, so the tag
        # alone already separates the four writers -- and it is also what tells you which
        # node left a file behind after a crash, which a pid does not.
        local staged="$shared.staging.${SX_LOG_TAG:-node}"
        rm -f "$staged"
        cp "$local_config" "$staged"
        # BLANK the six identity keys in the shared copy. Whichever node happened to
        # provision first would otherwise bake its own id, role and ports into the file every
        # other node reads -- harmless while the -D arguments win, and deeply misleading to
        # anyone opening the file to see what worker-3 is running. Blank says the true thing:
        # identity is not in here.
        yaml::set "$staged" \
            network.node.id "" \
            network.node.role "" \
            network.node.address "" \
            network.node.port 0 \
            api.port 0 \
            api.rpc-port 0
        # VERIFY the blanking before publishing. yaml::set shells out to python3, and a failure
        # there is silent -- the first cut of this shipped a shared file still carrying the
        # donating node's id and ports, which is exactly the misleading state the blanking exists
        # to prevent. Checked rather than assumed, because the failure looks like success.
        local leftover=""
        local key
        for key in network.node.id network.node.role network.node.address; do
            [[ -z "$(yaml::get "$staged" "$key" 2>/dev/null)" ]] || leftover+=" $key"
        done
        if [[ -n "$leftover" ]]; then
            log "WARNING: could not blank$leftover in the shared config (is python3 available?);"
            log "         publishing anyway -- the -D arguments still decide identity at runtime,"
            log "         but this file will read as though it belongs to $SX_LOG_TAG."
        fi
        # mv, not ln: ln refuses an existing target, and this now runs on every provision.
        # A rename within one directory is atomic, so a peer reading the shared file during the
        # swap sees the old copy in full rather than a half-written one.
        if mv -f "$staged" "$shared" 2>/dev/null; then
            log "Published the shared Sexidium config from $SX_LOG_TAG -> $shared${leftover:+ (identity NOT blanked)}"
        else
            log "WARNING: could not publish the shared Sexidium config from $SX_LOG_TAG"
            rm -f "$staged"
        fi
    fi
    [[ -s "$shared" ]] || {
        log "No shared Sexidium config to link yet; leaving $local_config node-local"
        return 0
    }
    # A real file here is the node's own copy: it has served its purpose (donating above)
    # and keeping it would mean edits landing on a file nothing reads.
    [[ ! -L "$local_config" ]] || rm -f "$local_config"
    [[ ! -e "$local_config" ]] || mv "$local_config" "$local_config.node-local-$(date +%s)"
    ln -s "$shared" "$local_config"
    log "Linked $local_config -> $shared"
}

# Where this node's HTTP API listens.
#
# `ApiServer` binds 127.0.0.1 by default, which was right while every container shared
# one network namespace and is wrong now that each has its own: from outside the
# container there is then no way to reach /health, /node or /command at all, and
# `docker exec` cannot substitute -- the entrypoint is a bare `exec java … nogui` with
# no rcon, no stdin and no console to drive. The orchestrator's entire control path is
# this socket, so the bind is not optional.
#
# Nothing is published to the host: the API is reachable on the `sexidium` bridge
# network only, and the token gate (a constant-time compare against api.token, with
# every endpoint but /health refusing a blank or default value) is what actually keeps
# strangers out -- exactly as it already was on loopback.
paper::api_bind_arg() {
    printf -- '-Dsexidium.api.bind=%s' "${SX_API_BIND:-0.0.0.0}"
}

# paper::adopt_node_build <node-dir> <node-name>
#
# Decides whether THIS provision moves this node's pin, which is the one place where
# "staging a build" and "running a build" are allowed to become the same event.
#
#   SX_ADOPT_BUILD=1 (default)  pin every node to the freshly staged build. This is what
#     keeps `remote.sh update` meaning exactly what it has always meant: provision,
#     restart, and every node is running the code you just pushed.
#   SX_ADOPT_BUILD=0            leave existing nodes on the build they already resolve.
#     The rolling pipeline sets this: it stages fleet-wide and then flips pins ONE NODE
#     AT A TIME between a drain and a restart, and an `init` that moved all four at once
#     would delete the property that makes an interrupted run harmless.
#
# A node with NO pin at all is pinned regardless of the flag -- it has nothing to keep,
# and booting it without one is booting with no plugin.
paper::adopt_node_build() {
    local dir="$1" name="$2" current
    [[ -n "${SX_BUILD_ID:-}" ]] || return 0
    current="$(store::pin_get "$dir" build)"
    if [[ "${SX_ADOPT_BUILD:-1}" == "1" || -z "$current" ]]; then
        store::pin_node "$dir" "$SX_BUILD_ID" --record-previous
    else
        log "Leaving $name pinned to $current (SX_ADOPT_BUILD=0; the pipeline moves pins)"
        # Still refresh the args file: a node keeping its build must keep saying so.
        store::sync_node_args "$dir" "$current"
    fi
}

# Provision one backend's WORKING DIRECTORY.
#
# paper::provision_instance <name> <role> <dir> <port> <api-base> [<forwarding-secret>]
#
# Downloads nothing and builds nothing: every artifact came from
# paper::provision_shared_install, serially, before this ran. What is left is the
# handful of things that are genuinely per-node -- the world (never shared), the
# listening port, the plugin data folders, and the five scalars that say which node
# this is. That is also why running these in parallel is safe: they no longer touch
# a single shared path between them.
#
# Deliberately NOT via --port: Paper writes the flag's value back into
# server.properties before it checks the world lock, and moving the port out of the
# file would break the golden trace for no gain. The port stays in the file.
paper::provision_instance() {
    local name="$1" role="$2" dir="$3" port="$4" api_base="$5" secret="${6:-}"

    log "--- provisioning $name (role=$role, port=$port) ---"
    sx_trace "instance $name role=$role port=$port api=$api_base"

    SERVER_DIR="$dir"
    # shellcheck disable=SC2034  # read by lib/report.sh and lib/mcserver.sh
    SERVER_PORT="$port"
    paper::derive_paths
    mkdir -p "$SERVER_DIR" "$PLUGINS_DIR"

    # Kept even though no jar lives here any more: this is also what notices a
    # DOWNGRADE and moves this node's worlds aside before a server that cannot read
    # them opens them. Must run before the symlinks below, which it would delete.
    refresh_jars_on_version_change
    paper::link_shared_install
    # After refresh_jars_on_version_change for the same reason as the line above it:
    # a DOWNGRADE moves this node's whole worlds/ aside (mcserver.sh), symlink
    # included -- `mv` renames the link, it does not follow it, so the shared tree is
    # untouched -- and this call then recreates the link in the fresh worlds/.
    paper::link_shared_maps
    paper::link_shared_experiences

    configure_eula_and_server
    # Backends sit behind the proxy: narrow bind + offline mode + modern forwarding.
    set_property "$SERVER_DIR/server.properties" server-port "$port"
    set_property "$SERVER_DIR/server.properties" server-ip "$SX_BACKEND_BIND"
    set_property "$SERVER_DIR/server.properties" online-mode false
    paper::configure_velocity_forwarding "$secret"

    # THIS NODE'S PLUGIN JARS: a sibling directory of plugins/, holding symlinks only.
    #
    # Bukkit resolves a plugin's data folder from --plugins and its CODE from
    # --add-extra-plugin-dir, which is the whole reason the split works. What changed is
    # the second half: the extra dir used to be the SHARED plugin tree, so every node
    # loaded the same one file and there was no way to say "worker-3 runs the new build,
    # the rest do not". It is now per node, and the difference between two nodes is one
    # symlink.
    store::link_node_plugin_jars "$dir"
    paper::adopt_node_build "$dir" "$name"

    local config="$PLUGINS_DIR/Sexidium/config.yml"
    # The pinned build's jar, not the shared tree's: this is the file the node will
    # actually load, and it is the one whose default config.yml must be extracted.
    SEXIDIUM_JAR_INSTALLED="$dir/pluginjars/$(basename "$SEXIDIUM_JAR")"
    # BREAK THE SHARED LINK BEFORE ANY WRITER RUNS, and re-publish at the end.
    #
    # Every configure_* helper below is read-modify-write, several of them via
    # `awk file > tmp && mv tmp file`. Once config.yml is a symlink to ONE shared file and the
    # four nodes provision in PARALLEL, those become four concurrent writers of the same file:
    # a `>` redirect truncates the target through the link, and a reader that is midway through
    # it writes back what it managed to read. Measured, not theorised -- this truncated the
    # shared config to ONE BYTE on its first run.
    #
    # So provisioning always edits a node-LOCAL real file, which is race-free by construction,
    # and paper::link_shared_sexidium_config publishes and re-links once everything is written.
    # All four produce identical content, so whichever publishes last is correct.
    #
    # A DANGLING link is the other case handled here, and it is the normal aftermath of a
    # failed provision: the link outlives the shared file whenever a run aborts between the two
    # steps, and every writer then fails with a bare "no such file", because writing THROUGH a
    # dangling link needs the target's parent to exist.
    if [[ -L "$config" ]]; then
        if [[ -e "$config" ]]; then
            local shared_now
            shared_now="$(readlink -f "$config")"
            rm -f "$config"
            cp "$shared_now" "$config"
            log "Detached $config from the shared copy for provisioning; it is re-linked at the end"
        else
            log "Removing a dangling shared-config link at $config (its target is gone)"
            rm -f "$config"
        fi
    fi
    sexidium::ensure_config || true
    [[ -f "$config" ]] ||
        die "Could not create $config for $name; node identity would be left unseeded"
    sexidium::seed_node_identity "$config" "$name" "$role" "$api_base"
    # THE IDENTITY OF THIS NODE, on the command line rather than in the config file.
    #
    # Everything else in config.yml is byte-identical across the four backends; only these
    # six values describe the node itself. Keeping a 1700-line copy per node to carry six
    # numbers is how the copies drift -- a setting changed on the lobby and forgotten on the
    # workers stays invisible until the behaviour differs under load. With these the file can
    # be ONE shared copy (see paper::link_shared_sexidium_config).
    #
    # Written HERE because the port arithmetic (velocity::node_port / node_api_base) lives in
    # this provisioning tree; node-entry.sh reads the file verbatim rather than recomputing it,
    # so there is exactly one source of truth for who a node is.
    #
    # network.node.address is what the PROXY must dial, which is not where the node binds:
    # SX_BACKEND_BIND is 0.0.0.0 and the advertised name is the container hostname.
    local identity="$dir/sexidium-node.args"
    cat >"$identity" <<EOF
# Generated by paper::provision_instance. Read by docker/node-entry.sh, one -D per line.
# PaperConfigurationAdapter applies -Dsexidium.<path> ahead of the shared config file.
-Dsexidium.network.node.id=$name
-Dsexidium.network.node.role=$role
-Dsexidium.network.node.address=$(printf "${SX_BACKEND_ADVERTISE:-%s}" "$name")
-Dsexidium.network.node.port=$port
-Dsexidium.api.port=$api_base
-Dsexidium.api.rpc-port=$((api_base + 2))
$(paper::api_bind_arg)
EOF
    # WHICH BUILD THIS NODE RESOLVES, on the command line, appended by the same function
    # that flips the pin (store::pin_node -> store::sync_node_args). Two halves, each
    # doing the only thing it can: the pipeline KNOWS which build it pinned onto this
    # node -- nothing inside the JVM does -- and the JVM knows when it has finished
    # starting, which is when the value has to reach network_nodes.plugin_version.
    # Writing it here and reading it there is the whole handshake; no new transport.
    store::sync_node_args "$dir" "$(store::pin_get "$dir" build)"
    log "Wrote node identity args for $name -> $identity"
    # AFTER sexidium::ensure_config, never before: a backend runs no warm-up boot, so
    # until that call there is no config.yml to patch and this returned silently --
    # the node then came up on its own local SQLite while the operator believed the
    # network shared one database.
    configure_sexidium_networked_backend_if_present
    # SkinsRestorer's data folder is per-node, so its offline-skin settings are too.
    # `seed`, because this node is the first thing ever to touch its own
    # plugins/SkinsRestorer/. Without it the node's FIRST boot writes `type: FILE`
    # while the proxy -- which does seed -- is already on MYSQL, and SkinsRestorer
    # shares no skins at all while the two disagree. This used to be a no-op until
    # the second provision of a node; that is the trap, not a property to preserve.
    configure_skinsrestorer_offline_skins seed
    # Belt and braces: seed_node_identity already writes this key. Kept because the
    # cost is one awk pass and the failure it guards (a network node self-hosting
    # BetterHud's pack at a container-local address) is invisible until a player
    # reports a boss bar of unknown-character boxes.
    # BetterHud's OWN config: where to listen and what URL to advertise. Per node, because
    # plugins/BetterHud/ is per node -- only Sexidium's config.yml is shared.
    if [[ -n "${SX_PACK_HOST_IP:-}" ]]; then
        configure_betterhud_if_present "$SX_PACK_HOST_IP" "$(velocity::node_pack_port "$name")"
        configure_sexidium_betterhud_if_present enabled
    else
        configure_sexidium_betterhud_if_present disabled
    fi
    # LAST, once every writer above has finished with the node's own copy: from here the file
    # is a symlink, so anything that edited it would be editing the shared one for everybody.
    paper::link_shared_sexidium_config
}
