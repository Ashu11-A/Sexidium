# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/velocity.sh -- Velocity proxy provisioning: jar, forwarding secret, velocity.toml.
#
# Sourced by docker/provision.sh (the network provisioner) via lib/core.sh's
# sx::require. Libraries never set shell options and never install traps.
# -----------------------------------------------------------------------------

if [[ -n "${_SX_LIB_VELOCITY:-}" ]]; then return 0; fi
_SX_LIB_VELOCITY=1

# shellcheck disable=SC2034  # cross-library globals, same rationale as paper::defaults
velocity::defaults() {
    # 3.5.1 is pinned rather than 4.x after checking the ProtocolVersion enum in
    # both jars: BOTH ship MINECRAFT_26_1 and MINECRAFT_26_2, so either speaks to
    # this repo's pinned Paper 26.1.2 and the choice is free. 3.5.1 wins because
    # it is a real release on the RECOMMENDED channel, while the 4.x line's plugin
    # API is incompatible with 3.x -- pinning it would bind :packages:module-velocity
    # to an API that cannot fall back. The fill API exposes no supported-game-version
    # field for Velocity, so re-verify by inspection (not by trusting the tag) on a bump:
    #   unzip -p velocity-<v>.jar com/velocitypowered/api/network/ProtocolVersion.class | javap -p -
    VELOCITY_VERSION="${VELOCITY_VERSION:-3.5.1}"

    NETWORK_DIR="${NETWORK_DIR:-$ROOT_DIR/run}"
    PROXY_DIR="${PROXY_DIR:-$NETWORK_DIR/proxy}"
    SHARED_DIR="${SHARED_DIR:-$NETWORK_DIR/shared}"
    # Every backend needs the same ~113MB of jars; download each exactly once.
    SX_ARTIFACT_CACHE="${SX_ARTIFACT_CACHE:-$SHARED_DIR/artifacts}"

    # The SHARED INSTALL: one paper.jar, one set of plugin jars, one libraries/
    # versions/cache tree for every backend. Everything a node owns is its working
    # directory (world/, logs/, plugins/<Plugin>/ data folders, server.properties)
    # -- see docs/deployment.md.
    #
    # The artifact cache above and this are different things and both are needed:
    # the cache is keyed by sha1(url) and answers "did we already download these
    # bytes"; this is the single INSTALLED tree the JVMs actually read. Before it
    # existed, four nodes each got their own copy of every jar -- identical only by
    # accident, and the one artifact that is genuinely built rather than downloaded
    # (Sexidium's own jar) was four independent copies with nothing comparing them.
    #
    # docker/stack.sexidium.yml sets both explicitly, because docker/node-entry.sh
    # builds the java command line from them and never sources this file.
    SX_SHARED_INSTALL="${SX_SHARED_INSTALL:-$SHARED_DIR/install}"
    SX_SHARED_PLUGINS="${SX_SHARED_PLUGINS:-$SX_SHARED_INSTALL/plugins}"

    # The shared MAP TEMPLATE tree: one copy of the minigame maps the plugin jar
    # bundles (assets/worlds/tntwars -> <maps>/tntwar/<id>), seeded here ONCE by the
    # `init` container (paper::seed_shared_maps) and reached from every node through
    # a symlink at <node>/worlds/<bundle> (paper::link_shared_maps).
    #
    # Read this next to the two names above, because all three are different things:
    #   SX_ARTIFACT_CACHE   downloaded bytes, keyed by sha1(url)
    #   SX_SHARED_INSTALL   the one INSTALLED tree the JVMs execute
    #   SX_SHARED_MAPS      the one TEMPLATE tree the JVMs read and COPY OUT OF
    #
    # The last one is the load-bearing distinction, and it is why this is a separate
    # variable rather than a subdirectory of the install: nothing under here is ever
    # opened as a live world. A template is cloned into <node>/world/dimensions/…
    # before a match touches it, so there is no session.lock to contend for and no
    # second JVM writing the same chunks.
    #
    # NEVER point a node's worlds/ ROOT at this. The core contract puts temp/ and
    # experience/ INSIDE worldRoot(), so a shared root would let one node's stale-temp
    # cleanup delete the LIVE match worlds of the others. Only the bundle subfolders
    # (worlds/tntwar) are shared; worlds/ itself stays a real, node-local directory.
    SX_SHARED_MAPS="${SX_SHARED_MAPS:-$SHARED_DIR/maps}"
    # The one tree of LIVE experience worlds, reachable by every node. Distinct from
    # SX_SHARED_MAPS in the way that matters: a map template is copied and never opened,
    # while these ARE opened -- by one node at a time, arbitrated by the placement lease.
    SX_SHARED_WORLDS="${SX_SHARED_WORLDS:-$SHARED_DIR/worlds}"
    VELOCITY_JAR="$PROXY_DIR/velocity.jar"
    VELOCITY_TOML="$PROXY_DIR/velocity.toml"
    FORWARDING_SECRET_FILE="$PROXY_DIR/forwarding.secret"

    PROXY_PORT="${PROXY_PORT:-25565}"
    PROXY_BIND="${PROXY_BIND:-0.0.0.0}"
    PROXY_JAVA_ARGS="${PROXY_JAVA_ARGS:--Xms256M -Xmx512M}"

    # The topology the plan specifies: one lobby plus three workers. Workers carry
    # both experiences and minigames and host many worlds each.
    SX_NODES="${SX_NODES:-lobby worker-1 worker-2 worker-3}"
    SX_PORT_BASE="${SX_PORT_BASE:-25566}"
    # Sexidium binds three ports per backend (api.port, ui.resource-pack.port,
    # api.rpc-port) at 8787/8788/8789. A stride of 10 keeps four backends from
    # colliding while staying readable: lobby 8787.., worker-1 8797.., and so on.
    SX_API_PORT_BASE="${SX_API_PORT_BASE:-8787}"
    SX_API_PORT_STRIDE="${SX_API_PORT_STRIDE:-10}"

    # Where each backend LISTENS. Loopback by default, which is right for the
    # single-host layout: everything shares one network namespace.
    #
    # What actually keeps strangers out is NOT this socket. Modern forwarding makes
    # the backend demand an HMAC-SHA256-signed `velocity:player_info` payload
    # (com.destroystokyo.paper.proxy.VelocityProxy#checkIntegrity); a vanilla client
    # dialling a backend directly is cut off during login with "This server requires
    # you to connect with Velocity." — verified empirically against a running node,
    # not merely read off the config. The old comment here claimed a reachable
    # backend lets anyone in as any username: that is true of BungeeCord's LEGACY
    # forwarding without BungeeGuard, and false for the modern forwarding this repo
    # deploys. Binding narrowly is still worth doing as defence in depth (it hides
    # the port from scanners and from other tenants), so keep it narrow when the
    # topology allows.
    #
    # The honest caveat: the shared secret lives in run/proxy/forwarding.secret
    # inside the volume every node mounts, so code execution on ANY node already
    # yields the secret. The socket boundary never protected against that either.
    SX_BACKEND_BIND="${SX_BACKEND_BIND:-127.0.0.1}"
    # Where the PROXY dials each backend, which stops being the same address the
    # moment the nodes get their own network namespaces (one container per node:
    # each binds 0.0.0.0 inside itself and is reached by its service name). A `%s`
    # is substituted with the node name, so "%s" alone means "dial the node by its
    # own hostname". Empty keeps the historical single-host behaviour: dial
    # SX_BACKEND_BIND.
    SX_BACKEND_ADVERTISE="${SX_BACKEND_ADVERTISE:-}"

    SX_LOBBY_MEMORY="${SX_LOBBY_MEMORY:--Xms1G -Xmx2G}"
    SX_WORKER_MEMORY="${SX_WORKER_MEMORY:--Xms1G -Xmx3G}"
}

# Newest published non-snapshot version, used only when VELOCITY_VERSION is unset
# AND the caller asks for "latest". Velocity's /projects response groups versions
# by FAMILY ("4.0.0" -> [4.1.0-SNAPSHOT, 4.0.0, ...]), unlike Paper's flat list,
# so this needs its own picker rather than papermc::resolve_build's.
velocity::latest_version() {
    api_get "https://fill.papermc.io/v3/projects/velocity" 2>/dev/null | python3 -c '
import json, sys

data = json.load(sys.stdin).get("versions", {})
best = None
for family in sorted(data, key=lambda f: [int(p) for p in f.split(".") if p.isdigit()], reverse=True):
    for version in data[family]:
        if "-SNAPSHOT" not in version:
            best = best or version
    if best:
        break
print(best or "")
' 2>/dev/null
}

velocity::ensure_jar() {
    if [[ -s "$VELOCITY_JAR" ]]; then
        log "Velocity jar already present: $VELOCITY_JAR"
        return 0
    fi
    need_cmd curl
    need_cmd python3
    mkdir -p "$PROXY_DIR"

    local resolved build sha url
    resolved="$(papermc::resolve_build velocity "$VELOCITY_VERSION" STABLE RECOMMENDED)"
    read -r build sha url <<<"$resolved" || true
    [[ -n "${url:-}" ]] || die "Could not resolve a Velocity build for $VELOCITY_VERSION (is that version published?)"

    log "Downloading Velocity $VELOCITY_VERSION build $build"
    download_to "$url" "$VELOCITY_JAR" || die "Failed to download Velocity jar from $url"
    verify_sha256 "$VELOCITY_JAR" "$sha"
}

# Generated ONCE. Regenerating orphans every backend at the same instant: each one
# carries the old secret in its paper-global.yml and the proxy starts rejecting
# them all with an error that reads like a network fault.
velocity::ensure_secret() {
    mkdir -p "$PROXY_DIR"
    if [[ -n "${SEXIDIUM_FORWARDING_SECRET:-}" ]]; then
        printf '%s' "$SEXIDIUM_FORWARDING_SECRET" >"$FORWARDING_SECRET_FILE"
        chmod 600 "$FORWARDING_SECRET_FILE"
        sx_trace "forwarding_secret from-env"
        log "Forwarding secret taken from SEXIDIUM_FORWARDING_SECRET"
        return 0
    fi
    if [[ -s "$FORWARDING_SECRET_FILE" ]]; then
        sx_trace "forwarding_secret reused"
        log "Reusing existing forwarding secret"
        return 0
    fi
    local secret
    if command -v openssl >/dev/null 2>&1; then
        secret="$(openssl rand -hex 32)"
    else
        secret="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    fi
    printf '%s' "$secret" >"$FORWARDING_SECRET_FILE"
    chmod 600 "$FORWARDING_SECRET_FILE"
    sx_trace "forwarding_secret generated"
    log "Generated a new forwarding secret at $FORWARDING_SECRET_FILE"
}

velocity::secret() {
    cat "$FORWARDING_SECRET_FILE"
}

# Backend name -> port. Keeps the provisioner and the report in agreement.
velocity::node_port() {
    local want="$1" i=0 node
    for node in $SX_NODES; do
        if [[ "$node" == "$want" ]]; then
            printf '%s' "$((SX_PORT_BASE + i))"
            return 0
        fi
        i=$((i + 1))
    done
    return 1
}

# Backend name -> the address the PROXY dials (and the report prints). Kept apart
# from node_port so the two callers can never disagree about the topology.
#
# Plain string substitution, never `printf "$SX_BACKEND_ADVERTISE" "$node"`: the
# template arrives from the environment (the container stack sets it), and a stray
# `%` in it would make printf emit garbage or consume the argument silently.
velocity::node_address() {
    local node="$1"
    if [[ -z "${SX_BACKEND_ADVERTISE:-}" ]]; then
        printf '%s' "$SX_BACKEND_BIND"
        return 0
    fi
    printf '%s' "${SX_BACKEND_ADVERTISE//%s/$node}"
}

# velocity::node_pack_port <node>  -> the BetterHud pack port for that node
#
# Same index-into-SX_NODES shape as node_port/node_api_base, so the three stay in step.
# Deliberately one port PER NODE: whichever backend a player is on is the one that sends
# them the pack, so every node needs a reachable URL -- and separate ports mean we never
# depend on all four building a byte-identical pack for one shared URL to be valid.
velocity::node_pack_port() {
    local want="$1" i=0 node
    for node in $SX_NODES; do
        if [[ "$node" == "$want" ]]; then
            printf '%s' "$((${SX_PACK_PORT_BASE:-26011} + i))"
            return 0
        fi
        i=$((i + 1))
    done
    return 1
}

velocity::node_api_base() {
    local want="$1" i=0 node
    for node in $SX_NODES; do
        if [[ "$node" == "$want" ]]; then
            printf '%s' "$((SX_API_PORT_BASE + i * SX_API_PORT_STRIDE))"
            return 0
        fi
        i=$((i + 1))
    done
    return 1
}

# Writes velocity.toml, but NEVER clobbers a hand-edited one: on an existing file
# only bind / forwarding-secret-file / [servers] / try are rewritten, matching the
# discipline configure_sexidium_menu_pack_if_present already uses for config.yml.
# python 3.11's tomllib reads but cannot write, so this is a line-oriented rewriter
# like every other patcher in this tree rather than a real TOML serializer.
velocity::write_toml() {
    mkdir -p "$PROXY_DIR"
    local node port address servers=""
    for node in $SX_NODES; do
        port="$(velocity::node_port "$node")"
        # The ADVERTISED address, not the bind address: the proxy has to reach the
        # backend, and with one container per node those are different strings.
        address="$(velocity::node_address "$node")"
        servers+="$(printf '%s = "%s:%s"\n' "$node" "$address" "$port")"
        servers+=$'\n'
    done

    if [[ ! -f "$VELOCITY_TOML" ]]; then
        sx_trace "write velocity.toml fresh nodes=$(echo "$SX_NODES" | wc -w)"
        cat >"$VELOCITY_TOML" <<EOF
# Generated by docker/provision.sh. Hand edits are preserved on re-run:
# only bind, forwarding-secret-file and the [servers] table are rewritten.
config-version = "2.7"
bind = "$PROXY_BIND:$PROXY_PORT"
motd = "<#ff5f6d>Sexidium<#ffc371> Network"
show-max-players = 100

# online-mode is the PROXY's job now: the backends run online-mode=false and trust
# the proxy through modern forwarding. What actually keeps a stranger out of a
# backend is the HMAC-signed forwarding payload that Paper verifies in
# VelocityProxy.checkIntegrity -- a direct vanilla login is refused during the
# handshake with "This server requires you to connect with Velocity." -- and NOT
# the socket the backend happens to listen on. Binding the backends narrowly is
# still worth doing as defence in depth wherever the topology allows it (it does
# not, once each node lives in its own network namespace and is dialled by name),
# but it was never the gate. The gate is the secret below: guard that.
online-mode = ${SX_ONLINE_MODE:-false}
force-key-authentication = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
$servers
try = ["lobby"]

[forced-hosts]

[advanced]
haproxy-protocol = false

[query]
enabled = false
EOF
        log "Wrote $VELOCITY_TOML"
        return 0
    fi

    sx_trace "patch velocity.toml existing"
    log "velocity.toml exists; rewriting only bind / forwarding-secret-file / [servers]"
    SERVERS="$servers" BINDLINE="bind = \"$PROXY_BIND:$PROXY_PORT\"" python3 - "$VELOCITY_TOML" <<'PY'
import os, re, sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()

text = re.sub(r'(?m)^bind\s*=.*$', os.environ["BINDLINE"], text, count=1)
text = re.sub(r'(?m)^forwarding-secret-file\s*=.*$',
              'forwarding-secret-file = "forwarding.secret"', text, count=1)

servers = os.environ["SERVERS"].rstrip("\n")
# Replace the [servers] table body up to the next table header, keeping `try`.
def replace_servers(match):
    body = match.group(2)
    keep = "\n".join(l for l in body.splitlines() if l.strip().startswith("try"))
    return match.group(1) + "\n" + servers + ("\n" + keep if keep else "") + "\n"

# The `|\Z` alternative is load-bearing: without it the table body has to be
# followed by another table header, so the day [servers] becomes the LAST table in
# the file the substitution matches nothing and the route update fails SILENTLY --
# n == 0, no error, stale routes.
new, n = re.subn(r'(?ms)^(\[servers\])\n(.*?)(?=^\[|\Z)', replace_servers, text)
if n:
    text = new
open(path, "w", encoding="utf-8").write(text)
PY
}

velocity::start_foreground() {
    sx_trace "exec java $PROXY_JAVA_ARGS -jar $(sx_rel "$VELOCITY_JAR")"
    if sx_dry; then
        log "Dry run: not starting the proxy"
        return 0
    fi
    log "Starting Velocity on $PROXY_BIND:$PROXY_PORT. Stop it with Ctrl+C or 'shutdown'."
    trap - EXIT
    cd "$PROXY_DIR" || die "Proxy directory vanished before start: $PROXY_DIR"
    # Word splitting on $PROXY_JAVA_ARGS is deliberate: multiple JVM flags.
    # shellcheck disable=SC2086
    exec "$JAVA_BIN" $PROXY_JAVA_ARGS -jar "$VELOCITY_JAR"
}

# Install the built proxy plugin and seed its config.
#
# Found by booting: without a config.yml in the plugin's data directory,
# NetworkSettings falls back to standalone, and the proxy logs itself as node
# 'standalone' holding every capability -- including the world-bound ones it can
# never honour. The proxy must declare role: proxy explicitly.
# SkinsRestorer ON THE PROXY, which is where it actually has to run.
#
# It was only ever installed into the backends' shared tree (paper.sh's PLUGINS_DIR), and the plugin
# itself says why that cannot work: behind a proxy it boots into PROXY MODE and prints
#
#   "This plugin is running in PROXY mode! You have to put the same config.yml on all servers
#    and on the proxy. (<proxy>/plugins/SkinsRestorer/)"
#
# With modern forwarding the proxy is what builds the player's GameProfile -- and the skin IS a
# property of that profile (`textures`) -- then re-sends it on every server switch. So a skin set on a
# backend alone is overwritten by the next routing decision, and `/skin set` looks like it does nothing.
#
# The jar is the same universal artifact for both platforms (SkinsRestorer ships one build that detects
# Bukkit/Velocity), so this copies what the shared tree already downloaded rather than fetching twice.
velocity::install_skinsrestorer() {
    local source="${SKINSRESTORER_JAR:-$SX_SHARED_PLUGINS/SkinsRestorer.jar}"
    local plugins="$PROXY_DIR/plugins"
    if [[ ! -s "$source" ]]; then
        log "SkinsRestorer not present in the shared tree; the proxy is left without it (/skin will not work)"
        return 0
    fi
    mkdir -p "$plugins"
    # Rename-into-place for the same reason install_plugin does it: overwriting truncates the zip in
    # the inode a running proxy still has open.
    sexidium::install_jar "$source" "$plugins"
    sx_trace "install proxy-plugin SkinsRestorer.jar"
    log "Installed SkinsRestorer.jar into $plugins (proxy mode needs it on BOTH sides)"
    # The SAME keys as the backends get -- proxy mode refuses to share skins when the two disagree.
    # `skinsrestorer` MINÚSCULO, e isso não é detalhe cosmético. O Bukkit nomeia a pasta de dados
    # pelo nome declarado no plugin.yml (SkinsRestorer); o Velocity a nomeia pelo ID do plugin, que
    # é minúsculo. Apontar para a grafia do backend cria uma segunda pasta vazia ao lado da real e
    # o proxy segue em `type: FILE` enquanto os backends vão para MYSQL -- os dois lados discordando
    # é exatamente a condição em que o proxy mode não compartilha skin nenhuma, com tudo parecendo
    # configurado.
    #
    # `seed` é o que fecha a armadilha de DUAS PASSADAS. `configure_skinsrestorer_config` só sabe
    # corrigir um arquivo que o plugin já escreveu, e o plugin só escreve depois de subir uma vez: o
    # primeiro provisionamento deixava o proxy em `type: FILE` -- o desacordo exato em que o proxy mode
    # registra "Proxy mode API is enabled, but database storage is not set up" e `/skin set` não faz
    # nada. Cada nó semeia igual (paper.sh), senão a armadilha só troca de lado.
    configure_skinsrestorer_config "$plugins/skinsrestorer/config.yml" "proxy" seed
}

velocity::install_plugin() {
    # build/libs/velocity is the `collectJars` Sync destination, so it always holds
    # the CURRENT build's output. The subproject's own build/…/libs directory is not
    # synced and happily keeps a stale jar from an older build around, which is how
    # a "rebuilt" proxy ends up running last week's plugin.
    local jar="${SEXIDIUM_VELOCITY_JAR:-$ROOT_DIR/build/libs/velocity/Sexidium-Velocity-1.0.0.jar}"
    local plugins="$PROXY_DIR/plugins"
    mkdir -p "$plugins"

    # Under the harness the Gradle output does not exist, and skipping the install would
    # skip the proxy's PIN with it (its pinned jar is the file this function writes). Stub
    # it so the rehearsal walks the same branch a real run does.
    if sx_dry && [[ ! -s "$jar" ]]; then
        jar="$PROXY_DIR/.dry-build/Sexidium-Velocity-1.0.0.jar"
        sexidium::dry_stub_jar "$jar"
    fi

    if [[ -s "$jar" ]]; then
        # Rename-into-place, never `cp` over the live file: overwriting truncates the
        # zip in the SAME inode the running proxy's URLClassLoader still has open.
        sexidium::install_jar "$jar" "$plugins"
        # Where the SOURCE artifact is, for paper::stage_build -- the store keeps both
        # jars of a build together so a rollback restores a matching pair rather than a
        # new core against last week's proxy plugin.
        # shellcheck disable=SC2034  # read by paper::stage_build
        VELOCITY_PLUGIN_JAR="$jar"
        sx_trace "install proxy-plugin $(basename "$jar")"
        log "Installed $(basename "$jar") into $plugins"
    else
        log "Proxy plugin jar not found at $jar; build it with ./gradlew :packages:module-velocity:jar"
    fi

    local data="$plugins/sexidium"
    local config="$data/config.yml"
    mkdir -p "$data"
    if [[ ! -f "$config" ]]; then
        sx_trace "seed proxy config.yml"
        cat >"$config" <<'EOF'
# Sexidium proxy configuration. Seeded by docker/provision.sh.
#
# The proxy reads the same config shape a backend does, but only the keys that do
# not need a world. Its role must be `proxy`: leaving it unset makes NetworkSettings
# resolve a standalone identity holding every capability, including LOBBY and
# EXPERIENCES, which the proxy cannot honour.
network:
  enabled: true
  node:
    id: 'proxy'
    display-name: 'Sexidium Network'
    role: proxy
    capabilities: []

# Preenchida a partir de SEXIDIUM_DB_* logo abaixo. Sem ela o proxy resolve SQLite e
# recusa-se a subir o estado compartilhado: o driver do SQLite não pode ser relocado,
# então este jar não o embarca (packages/module-velocity/build.gradle.kts).
database:
  type: 'sqlite'
  host: '127.0.0.1'
  port: 3306
  name: 'sexidium'
  user: 'sexidium'
  password: ''

messages:
  default-language: en
  console-language: en

# The proxy IS the login gate, and it holds no bot config of its own -- `auto` resolves through
# bot.enabled/bot.token, which live on the bot node, so on a proxy `auto` silently means OFF and
# the gate logs "active" while allowing everybody. It is set explicitly here for that reason.
# See scripts/lib/sexidium.sh for the backend side.
#
# Everything below `require-for-login` is off by default and refreshed from SX_AUTH_* on every
# re-provision. premium.enabled is PROXY-ONLY by nature: online-mode is per-connection here and
# nowhere else, so it must NOT be copied into a backend config -- a backend sees every arrival as
# unverified and would refuse the premium players this had just let in.
auth:
  enabled: true
  require-for-login: true
  session:
    enabled: false
  approval:
    enabled: false
  premium:
    enabled: false
  hold:
    enabled: false
EOF
        log "Seeded proxy config at $config"
    else
        log "Proxy config already present; only the database block is refreshed"
    fi

    # O banco é semeado SEMPRE (config novo ou existente): o proxy é o único nó que não
    # passa por configure_sexidium_networked_backend_if_present, e sem isto ele sobe
    # apontando para SQLite -- sem auth gate e sem roteamento por placement.
    if [[ -n "${SEXIDIUM_DB_TYPE:-}" ]]; then
        yaml::set "$config" \
            database.type "$SEXIDIUM_DB_TYPE" \
            database.host "${SEXIDIUM_DB_HOST:-127.0.0.1}" \
            database.port "${SEXIDIUM_DB_PORT:-3306}" \
            database.name "${SEXIDIUM_DB_NAME:-sexidium}" \
            database.user "${SEXIDIUM_DB_USER:-sexidium}" \
            database.password "${SEXIDIUM_DB_PASSWORD:-}"
        log "Proxy pointed at the shared $SEXIDIUM_DB_TYPE database at ${SEXIDIUM_DB_HOST:-127.0.0.1}"
    fi
    [[ -z "${SEXIDIUM_API_TOKEN:-}" ]] || yaml::set "$config" api.token "$SEXIDIUM_API_TOKEN"

    # Refreshed on EVERY re-provision, for the same reason the database block above is: an existing
    # config is never regenerated, so a heredoc-only default would apply to fresh proxies and to
    # nobody else. Each key is an SX_AUTH_* override with the safe answer as its fallback.
    # O PORTÃO ESTÁ DESLIGADO POR PADRÃO, e isso é deliberado -- não é um descuido a "consertar".
    #
    # `auto` (e o `true` que estava aqui) exigem Discord sempre que ALGUM nó anuncia BOT_HOST. Mas
    # BOT_HOST é declarada pela ROLE do lobby (NodeIdentity), sem olhar `bot.enabled` -- então numa
    # rede sem SEXIDIUM_BOT_TOKEN o lobby anuncia um bot que não existe, o proxy acredita, e TODO
    # login é recusado pedindo `/auth <código>` a um bot que ninguém pode executar. A rede inteira
    # fica intransponível sem que nada esteja "quebrado".
    #
    # Para religar: SX_AUTH_GATE=true no ambiente do stack (e um bot de verdade no ar). A causa de
    # fundo -- anunciar uma capability que o nó não honra -- continua valendo uma correção em
    # NodeIdentity; enquanto ela não vem, o padrão seguro é o portão aberto.
    sx_trace "seed proxy auth block"
    yaml::set "$config" \
        auth.enabled true \
        auth.require-for-login "${SX_AUTH_GATE:-false}" \
        auth.session.enabled "${SX_AUTH_SESSIONS:-false}" \
        auth.approval.enabled "${SX_AUTH_APPROVAL:-false}" \
        auth.premium.enabled "${SX_AUTH_PREMIUM:-false}" \
        auth.hold.enabled "${SX_AUTH_HOLD:-false}"
    # The pepper salts the stored IP hashes. Left blank it is derived from api.token, which is
    # correct but shared; setting it per deployment is what stops the hashes being comparable
    # across deployments. SX_AUTH_IP_PEPPER is mirrored onto every backend by sexidium.sh, because a
    # pepper that only the proxy knows desyncs the hashes and the backends see no sessions.
    [[ -z "${SX_AUTH_IP_PEPPER:-}" ]] || yaml::set "$config" auth.session.ip-pepper "$SX_AUTH_IP_PEPPER"
}
