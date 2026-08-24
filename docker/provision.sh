#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# provision.sh -- provision a Sexidium NETWORK: a Velocity proxy in front of one
# lobby and three worker backends, all against one central database.
#
#   proxy    :25565   routing, auth gate, resource pack, Discord bot
#   lobby    :25566   the lobby world, queue authority
#   worker-1 :25567 \
#   worker-2 :25568  >  experiences + minigames, many worlds each, own local disk
#   worker-3 :25569 /
#
# It provisions and EXITS. Nothing here starts a JVM: the containers do that
# (docker/node-entry.sh, one per node), and the `init` service that runs this file
# is meant to end in `Exited (0)`. That is why this lives in docker/ rather than
# scripts/ -- it is one half of the container contract, not a standalone tool.
#
# It shares every library with scripts/init-paper.sh (scripts/lib/*.sh); this file
# is only the NETWORK profile. init-paper.sh remains the supported way to run a
# single standalone server on SQLite and its contract is unchanged.
#
# It orchestrates paper::provision_instance in-process rather than shelling out to
# init-paper.sh, because init-paper.sh ends in `exec java` and never returns, and
# because building the plugin once beats four Gradle invocations contending on one
# project cache.
#
# LAYOUT. There is ONE source folder and one working directory per node. Under the
# container stack the source is /srv/sexidium/server (its own volume, holding that
# and nothing else) and each node directory is a volume of its own, mounted at the
# SAME path here and in the node that owns it:
#
#   $SX_SHARED_INSTALL/      paper.jar, the plugin jars, libraries/ versions/ cache/
#                            -- written here, read-only while the network runs
#   $SX_SHARED_MAPS/         the minigame map TEMPLATES bundled in the plugin jar
#                            (tntwar/<id>) -- written here, by this file, once
#   $NETWORK_DIR/<node>/     world/, logs/, plugins/<Plugin>/ data folders,
#                            server.properties, config/  -- everything the server
#                            writes, and nothing else
#   $NETWORK_DIR/<node>/worlds/  a REAL, node-local directory holding one SYMLINK per
#                            shared bundle: worlds/tntwar -> $SX_SHARED_MAPS/tntwar
#
# A node reaches the install through symlinks (libraries/versions/cache/paper.jar,
# which Paper resolves relative to the working directory and offers no flag to
# move) plus --add-extra-plugin-dir for the jars, while --plugins keeps each data
# folder local. That combination is the point: identical code everywhere, separate
# state everywhere, and no path where "worker-2 is running last month's build" can
# be true, because there is only one file to be out of date.
#
# Two kinds of shared directory, shared for OPPOSITE reasons. Confusing them is how
# you lose a save, so both are spelled out.
#
# 1. run/shared/maps holds map TEMPLATES, which no JVM ever opens as a world -- a
#    match COPIES region/entities/poi/data out of one into a fresh world first.
#    Nothing can contend because nothing is ever opened. Safe by construction.
#
# 2. world/dimensions/experiences IS a live world tree, and it IS shared, because
#    "any worker can take over any experience" is the load-balancing mechanism. This
#    one is NOT safe by construction, and Minecraft does not help: session.lock lives
#    at the LEVEL root (world/), which is node-local, and does not cover a keyed
#    dimension folder at all. Mutual exclusion is therefore ours to provide, and it
#    is the world_placements lease plus a per-grant fence token -- a holder whose
#    renew stops matching (node_id, node_epoch, fence) has been evicted, freezes its
#    writes and unloads. <folder>/sexidium/holder.json is a second, advisory opinion
#    so a database/clock disagreement surfaces as a refusal naming a node rather than
#    as two JVMs writing one region file.
#
# The link is per bundle folder (worlds/tntwar) and NEVER at worlds/ itself: temp/
# lives under the world root by the core's contract, and sharing the root would let
# one node's stale-temp cleanup delete the others' live matches.
#
# There is deliberately NO memory preflight. The predecessor of this file refused
# to provision when /proc/meminfo looked too small -- but inside a container
# /proc/meminfo reports the HOST's memory, so the check measured a number that has
# nothing to do with what these JVMs may use. What governs here is the per-service
# `mem_limit` in docker/stack.sexidium.yml; size the heaps to match it there.
#
# Environment: VELOCITY_VERSION, NETWORK_DIR, PROXY_PORT, SX_NODES, SX_PORT_BASE,
# SX_BACKEND_BIND, SX_BACKEND_ADVERTISE, SX_BOT_NODE, SEXIDIUM_FORWARDING_SECRET,
# SEXIDIUM_DB_* .
# -----------------------------------------------------------------------------

SX_DOCKER_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SX_LOG_TAG="provision"
export SX_LOG_TAG

# core.sh resolves ROOT_DIR from its OWN location (or honours an inherited
# ROOT_DIR), so sourcing it from a sibling directory of scripts/ is safe.
# shellcheck source=../scripts/lib/core.sh
. "$SX_DOCKER_DIR/../scripts/lib/core.sh"

sx::require props http modrinth papermc java net yaml mcserver console plugins sexidium store paper velocity report

# Folds the per-node trace parts back into $SX_TRACE, in SX_NODES order.
#
# The fan-out is genuinely parallel, so "what did the provisioner do" has no single true
# order across nodes -- but it has a stable one WITHIN a node, and the nodes themselves
# are a declared list. Concatenating by that list turns a racy interleave into a
# deterministic document without pretending the execution was serial.
sx_collect_node_traces() {
    [[ -n "$SX_TRACE" ]] || return 0
    local node part
    for node in $SX_NODES; do
        part="$SX_TRACE.node.$node"
        [[ -f "$part" ]] || continue
        cat "$part" >>"$SX_TRACE"
        rm -f "$part"
    done
}

main() {
    need_cmd sed
    need_cmd awk
    need_cmd curl
    need_cmd python3
    # The map-template migration compares byte for byte before it decides whether a
    # node's local copy is redundant (paper::tree_divergence). Without cmp every file
    # would read as divergent and every node would keep a 92MB copy aside -- safe, but
    # silently pointless, so fail loudly instead.
    need_cmd cmp
    need_cmd find

    # Read BEFORE paper::defaults, which is where the "unset means 1" default is
    # applied and the distinction is lost. On a network these two default the other
    # way: FAWE and Axiom are map-BUILDING tools, nothing in Sexidium calls them, and
    # a shared plugins/ would put 84MB of world editing on all four nodes. An
    # operator who genuinely wants them sets the variable and is still obeyed.
    INSTALL_WORLDEDIT="${INSTALL_WORLDEDIT:-0}"
    INSTALL_AXIOM="${INSTALL_AXIOM:-0}"

    paper::defaults
    velocity::defaults
    store::defaults
    need_cmd "$JAVA_BIN"
    ensure_java_version

    # BUILD-ONLY. The first stage of a rolling update: compile, stage into the versioned
    # store, and STOP -- no node directory, no shared plugin tree, no stamp. A compile
    # failure here leaves the live network byte-identical to how it was found, which is
    # what makes it safe to run against production at any time.
    if [[ "${SX_PROVISION_MODE:-full}" == "build" ]]; then
        log "SX_PROVISION_MODE=build: staging an artifact only, touching no node"
        sx_trace "phase build-only"
        paper::build_only
        return 0
    fi

    log "Provisioning Sexidium network under $NETWORK_DIR"
    mkdir -p "$NETWORK_DIR" "$PROXY_DIR" "$SHARED_DIR"

    # 1. proxy: jar, secret, config. The secret must exist before any backend is
    #    configured, since each one is handed a copy.
    sx_trace "phase proxy"
    velocity::ensure_jar
    velocity::ensure_secret
    velocity::write_toml
    local secret
    secret="$(velocity::secret)"

    # 2. the SHARED INSTALL: one Gradle build, one paper.jar, one set of plugin jars,
    #    one libraries/versions/cache tree -- for all four backends.
    #
    #    Serial, and before the fan-out, for two reasons. The ensure_* helpers all
    #    short-circuit on "the file is already there", so four subshells aiming at a
    #    single destination would race exactly where there is now only one file to
    #    race for. And one Gradle invocation beats four daemons contending on one
    #    --project-cache-dir, which is why the build was hoisted out of the loop in
    #    the first place.
    sx_trace "phase shared-install"
    paper::provision_shared_install
    # DEPOIS do build, nunca antes. Instalar primeiro fazia a PRIMEIRA provisão de uma
    # árvore limpa não achar Sexidium-Velocity-1.0.0.jar (ele só nasce neste build), e o
    # proxy subia sem o plugin -- sem roteamento, sem auth gate -- até alguém rodar o
    # provisionador uma segunda vez. paper::provision_shared_install é quem roda o
    # Gradle agora, então esta linha tem de vir depois DELA.
    velocity::install_plugin

    # Depois do shared install pela mesma razão: é ele que baixa o SkinsRestorer que esta
    # linha copia para o proxy. Sem isto o plugin roda só nos backends, onde o perfil que o
    # proxy reenvia a cada troca de servidor sobrescreve qualquer skin -- ver a função.
    #
    # EM BACKGROUND, ao contrário de velocity::install_plugin acima: install_skinsrestorer só
    # escreve em proxy/plugins/skinsrestorer/, uma árvore que nem paper::stage_build (o store
    # versionado) nem paper::seed_shared_maps (a árvore de mapas compartilhada) tocam -- então
    # ela roda em paralelo com as duas fases seriais abaixo e é esperada (`wait`) logo antes do
    # fan-out por nó, nunca depois: um nó lendo proxy/plugins não faz sentido, mas o resto do
    # `main` depois do fan-out (o pin do proxy) só é seguro depois que ISTO terminou.
    #
    # velocity::install_plugin, imediatamente acima, continua SÍNCRONO e não entra nesse
    # paralelismo: é ela quem seta VELOCITY_PLUGIN_JAR, e paper::stage_build lê essa VARIÁVEL
    # (não um arquivo) duas fases abaixo para parear o jar do Paper com o da Velocity numa
    # única entrada do store. Uma variável não tem lock para esperar -- rodar essa escrita em
    # background e correr o `stage_build` contra ela é exatamente como um build fica com o
    # Paper staged e NENHUM Velocity junto, o bug de rollback desemparelhado que o próprio
    # comentário de paper::stage_build descreve. Copiar um jar é barato; aquele modo de falha
    # não vale o risco por essa economia.
    local skinsrestorer_trace=""
    [[ -z "$SX_TRACE" ]] || skinsrestorer_trace="$SX_TRACE.skinsrestorer"
    (
        SX_LOG_TAG="skinsrestorer"
        [[ -z "$skinsrestorer_trace" ]] || SX_TRACE="$skinsrestorer_trace"
        velocity::install_skinsrestorer
    ) &
    local skinsrestorer_pid=$!

    # 2a. THE VERSIONED STORE. Everything built above is folded into one immutable,
    #     content-addressed directory and the Sexidium jar leaves the shared plugin tree.
    #     After this line "which build is worker-2 running" has an answer (its pin) and
    #     an answer that can be CHANGED for one node without touching the others.
    sx_trace "phase build-store"
    paper::stage_build

    # 2b. the SHARED MAP TEMPLATES: one copy of the minigame maps the jar bundles,
    #     seeded here and read by every node through a symlink at <node>/worlds/tntwar.
    #
    #     Here, and nowhere else, because `init` is the only writer with no players
    #     online. A node seeding at boot would move a template aside while another
    #     node is mid-clone of that same folder for a match that is starting, and the
    #     clone failure degrades into a freshly GENERATED world with the real map's
    #     team coordinates -- players spawned inside stone or into the void, with no
    #     error anywhere. Between the jar build above and the fan-out below is the one
    #     window in which that race cannot exist.
    #
    #     Serial, and before the fan-out, for the same reason the shared install is.
    sx_trace "phase shared-maps"
    paper::seed_shared_maps

    # Join the backgrounded SkinsRestorer install before doing anything that assumes the
    # proxy's plugin tree is finished (the fan-out below, and store::pin_proxy after it).
    wait "$skinsrestorer_pid" || die "velocity::install_skinsrestorer failed"
    # Fold its trace fragment back in at a FIXED position, same technique as
    # sx_collect_node_traces below: the real execution order between this background job
    # and the two serial phases above is racy by design, but the recorded order must not
    # be, or the golden trace would flake on an unchanged script.
    if [[ -n "$skinsrestorer_trace" && -f "$skinsrestorer_trace" ]]; then
        cat "$skinsrestorer_trace" >>"$SX_TRACE"
        rm -f "$skinsrestorer_trace"
    fi

    sx_trace "phase nodes"
    # 3. the nodes' working directories, IN PARALLEL, each in its own subshell.
    #
    #    Safe precisely because a subshell gets its own copy of the shell state:
    #    SERVER_DIR, the jar paths and the exit-trap registry are per-instance. And
    #    safer than it used to be: with every artifact already installed above, these
    #    calls download nothing, build nothing and share no destination path at all.
    local node role port api_base pids=() failed=0
    for node in $SX_NODES; do
        role="worker"
        [[ "$node" == "lobby" ]] && role="lobby"
        port="$(velocity::node_port "$node")"
        api_base="$(velocity::node_api_base "$node")"
        (
            # Per-instance log tag so interleaved output stays readable.
            SX_LOG_TAG="$node"
            # Per-instance TRACE FILE for a different reason: four subshells appending to
            # one file interleave in whatever order the scheduler picks, so the recorded
            # operation order would differ between two runs of an unchanged script and the
            # golden gate would be noise. Each node writes its own part; main() concatenates
            # them in SX_NODES order below. Untraced runs (production) keep SX_TRACE empty
            # and this whole mechanism is a no-op.
            [[ -z "$SX_TRACE" ]] || SX_TRACE="$SX_TRACE.node.$node"
            paper::provision_instance \
                "$node" "$role" "$NETWORK_DIR/$node" "$port" "$api_base" "$secret"
        ) &
        pids+=("$!")
    done

    for pid in "${pids[@]}"; do
        wait "$pid" || failed=1
    done
    [[ "$failed" -eq 0 ]] || die "One or more backends failed to provision (see the output above)"
    sx_collect_node_traces

    # The proxy's pin, last, and by the same primitive. Velocity has no
    # --add-extra-plugin-dir -- it scans plugins/ relative to its CWD -- so its pin IS
    # the symlink at that path. Only ever adopted when the backends adopt: a proxy on a
    # different build from the nodes it routes to is the one skew the protocol check
    # cannot repair, because the proxy is what would have to refuse the join.
    if [[ "${SX_ADOPT_BUILD:-1}" == "1" && -n "${SX_BUILD_ID:-}" ]]; then
        store::pin_proxy "$PROXY_DIR" "$SX_BUILD_ID"
    fi

    sx_trace "phase report"
    report::network

    log "Provisioning complete. Start the nodes (the stack does this for you)."
}

main
