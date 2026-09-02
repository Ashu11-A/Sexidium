#!/usr/bin/env bats
#
# Unit tests for the versioned build store and per-node jar pinning
# (scripts/lib/store.sh).
#
# What is worth testing here is not "does ln work". It is the handful of rules that,
# when they are silently wrong, produce a network that looks healthy and runs the wrong
# code -- or a rollback target that has been garbage-collected:
#
#   * a build id is content-addressed, so re-staging unchanged bytes is a no-op
#   * a pin refuses bytes that do not match the manifest, BEFORE it moves anything
#   * `previous=` is preserved across a repin-to-the-same-build (that is the rollback
#     target, and losing it is what turns one rename into a rebuild under pressure)
#   * the args file and the pin file move together, whoever moved them
#   * GC never collects a build any node still references
#   * a jar dropped from the shared install actually disappears from every node
#
# The one thing NOT tested here is the assumption underneath the whole layout -- that
# Paper loads a plugin through a symlink in --add-extra-plugin-dir. That needs a real
# JVM, so it lives in scripts/test/smoke-pin.sh and was confirmed against a Paper
# 26.1.2 boot.

setup() {
    SCRIPTS_DIR="$(cd -- "$BATS_TEST_DIRNAME/../.." && pwd -P)"
    set -u
    . "$SCRIPTS_DIR/lib/core.sh"
    sx::require sexidium store
    TMP="$BATS_TEST_TMPDIR/work"
    NETWORK_DIR="$TMP/nodes"
    SX_SHARED_INSTALL="$TMP/shared/install"
    SX_SHARED_PLUGINS="$SX_SHARED_INSTALL/plugins"
    SHARED_DIR="$TMP/shared"
    mkdir -p "$SX_SHARED_PLUGINS" "$NETWORK_DIR"
    # HERMÉTICO de propósito: o default de SX_PAPER_JAR_NAME deriva de
    # minecraft-targets.properties DO REPO, e o contador lá sobe a cada atualização.
    # Estes testes falam com jars fake de nome fixo -- que o nome do artefato mudasse
    # não pode reprovar lógica de store que não olha o nome. O teste do DEFAULT em si
    # está no bloco "canonical jar name" abaixo.
    SX_PAPER_JAR_NAME="Sexidium-Paper-1.0.0.jar"
    store::defaults
    SX_BUILD_STORE="$TMP/shared/install/builds"
}

# A "jar" here is any file: nothing in store.sh parses one, and using real zips would
# only make the tests slower and the digests harder to reason about.
fake_jar() {
    mkdir -p "$(dirname "$1")"
    printf '%s' "$2" >"$1"
}

node_dir() {
    mkdir -p "$NETWORK_DIR/$1"
    printf '%s' "$NETWORK_DIR/$1"
}

# --- build ids ----------------------------------------------------------------

@test "a build id is the counter plus the content hash" {
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    # Command substitution, not `run`: store::stage logs to stderr and bats folds
    # stderr into $output, so `run` would compare the id against the log too.
    id="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar" 2>/dev/null)"
    [[ "$id" =~ ^b0001-[0-9a-f]{12}$ ]]
}

@test "re-staging identical bytes reuses the build and does not burn a counter" {
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    first="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    second="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    [ "$first" = "$second" ]
    [ "$(cat "$SX_BUILD_STORE/COUNTER")" = "1" ]
}

@test "different bytes get a new build and a new counter" {
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    fake_jar "$TMP/b/Sexidium-Paper-1.0.0.jar" "two"
    first="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    second="$(store::stage "$TMP/b/Sexidium-Paper-1.0.0.jar")"
    [ "$first" != "$second" ]
    [[ "$second" =~ ^b0002- ]]
    [ "$(cat "$SX_BUILD_STORE/LATEST")" = "$second" ]
}

@test "a build directory appears complete or not at all" {
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    id="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    [ -s "$SX_BUILD_STORE/$id/Sexidium-Paper-1.0.0.jar" ]
    [ -s "$SX_BUILD_STORE/$id/manifest.txt" ]
    # No .staging left behind -- the assembly directory is renamed, not copied out of.
    run bash -c "ls -d '$SX_BUILD_STORE'/.staging* 2>/dev/null"
    [ "$status" -ne 0 ]
}

# --- pinning ------------------------------------------------------------------

@test "pinning a node writes the symlink, the pin file and the build-id argument" {
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    id="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    dir="$(node_dir worker-1)"
    printf -- '-Dsexidium.network.node.id=worker-1\n' >"$dir/sexidium-node.args"

    store::pin_node "$dir" "$id"
    [ -L "$dir/pluginjars/Sexidium-Paper-1.0.0.jar" ]
    [ "$(readlink "$dir/pluginjars/Sexidium-Paper-1.0.0.jar")" = "$SX_BUILD_STORE/$id/Sexidium-Paper-1.0.0.jar" ]
    [ "$(store::pin_get "$dir" build)" = "$id" ]
    grep -q -- "-Dsexidium.build.id=$id" "$dir/sexidium-node.args"
}

@test "SX_PIN_MODE=copy places a regular file, not a link" {
    SX_PIN_MODE=copy
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    id="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    dir="$(node_dir worker-1)"
    store::pin_node "$dir" "$id"
    [ -f "$dir/pluginjars/Sexidium-Paper-1.0.0.jar" ]
    [ ! -L "$dir/pluginjars/Sexidium-Paper-1.0.0.jar" ]
}

@test "a repin records where to roll back to" {
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    fake_jar "$TMP/b/Sexidium-Paper-1.0.0.jar" "two"
    old="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    new="$(store::stage "$TMP/b/Sexidium-Paper-1.0.0.jar")"
    dir="$(node_dir worker-1)"
    store::pin_node "$dir" "$old"
    store::pin_node "$dir" "$new" --record-previous
    [ "$(store::pin_get "$dir" build)" = "$new" ]
    [ "$(store::pin_get "$dir" previous)" = "$old" ]
}

@test "repinning to the build already pinned does not erase the rollback target" {
    # The resume case: a pipeline that is replayed re-runs PIN from the top, and if
    # that overwrote previous= with the current build the rollback would become a
    # no-op -- silently, and exactly when it is needed.
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    fake_jar "$TMP/b/Sexidium-Paper-1.0.0.jar" "two"
    old="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    new="$(store::stage "$TMP/b/Sexidium-Paper-1.0.0.jar")"
    dir="$(node_dir worker-1)"
    store::pin_node "$dir" "$old"
    store::pin_node "$dir" "$new" --record-previous
    store::pin_node "$dir" "$new" --record-previous
    [ "$(store::pin_get "$dir" previous)" = "$old" ]
}

@test "a pin refuses bytes that do not match the manifest" {
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    id="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    printf 'tampered' >"$SX_BUILD_STORE/$id/Sexidium-Paper-1.0.0.jar"
    dir="$(node_dir worker-1)"
    run store::pin_node "$dir" "$id"
    [ "$status" -ne 0 ]
    [[ "$output" == *"does not match manifest"* ]]
    [ ! -e "$dir/pluginjars/Sexidium-Paper-1.0.0.jar" ]
}

# --- the symlink farm ---------------------------------------------------------

@test "pluginjars links every third-party jar but never the Sexidium one" {
    fake_jar "$SX_SHARED_PLUGINS/Multiverse-Core.jar" mv
    fake_jar "$SX_SHARED_PLUGINS/FancyNpcs.jar" fn
    fake_jar "$SX_SHARED_PLUGINS/Sexidium-Paper-1.0.0.jar" stale
    dir="$(node_dir worker-1)"
    store::link_node_plugin_jars "$dir"
    [ -L "$dir/pluginjars/Multiverse-Core.jar" ]
    [ -L "$dir/pluginjars/FancyNpcs.jar" ]
    # The Sexidium jar is PINNED, per node. Linking the shared tree's copy would put
    # every node back on one build and silently defeat the whole layout.
    [ ! -e "$dir/pluginjars/Sexidium-Paper-1.0.0.jar" ]
}

@test "pluginjars skips BOTH naming eras of the Sexidium jar" {
    # Uma árvore compartilhada migrada pode carregar um resquício do nome velho ao lado
    # do canônico de hoje. O filtro que só conhecesse UM linkaria o outro como plugin de
    # terceiro -- e o Paper recusaria os dois por nome duplicado de plugin.
    fake_jar "$SX_SHARED_PLUGINS/Multiverse-Core.jar" mv
    fake_jar "$SX_SHARED_PLUGINS/Sexidium-Paper-1.0.0.jar" era-velha
    fake_jar "$SX_SHARED_PLUGINS/sexidium-paper-26.1.2+16.jar" era-nova
    dir="$(node_dir worker-1)"
    store::link_node_plugin_jars "$dir"
    [ -L "$dir/pluginjars/Multiverse-Core.jar" ]
    [ ! -e "$dir/pluginjars/Sexidium-Paper-1.0.0.jar" ]
    [ ! -e "$dir/pluginjars/sexidium-paper-26.1.2+16.jar" ]
}

# --- canonical jar name ---------------------------------------------------------

@test "the default paper jar name is derived, not a constant" {
    # Sem override, store::defaults deriva o nome de minecraft-targets.properties do
    # repo. O contador sobe a cada atualização, então o teste afirma a FORMA
    # (sexidium-paper-<versão>+<contador>.jar) e não um número que vira poeira.
    unset SX_PAPER_JAR_NAME
    store::defaults
    [[ "$SX_PAPER_JAR_NAME" =~ ^sexidium-paper-[0-9.]+\+[0-9]+\.jar$ ]]
}

# --- pinning across a canonical-name change -------------------------------------

@test "a pin falls back to the staged name when the store predates a rename" {
    # O cenário: o piso mudou (o canônico hoje é sexidium-paper-26.2+16.jar), e o
    # rollback precisa pinar um build estacionado ANTES, sob Sexidium-Paper-1.0.0.jar.
    # Os bytes têm de ser achados pelo manifesto; a ENTRADA em pluginjars/ continua no
    # nome de hoje, porque é por ele que o nó procura a sua cópia.
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    id="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"

    SX_PAPER_JAR_NAME="sexidium-paper-26.2+16.jar"
    dir="$(node_dir worker-1)"
    store::pin_node "$dir" "$id"
    [ -L "$dir/pluginjars/sexidium-paper-26.2+16.jar" ]
    [ "$(readlink "$dir/pluginjars/sexidium-paper-26.2+16.jar")" = "$SX_BUILD_STORE/$id/Sexidium-Paper-1.0.0.jar" ]
    [ "$(store::pin_get "$dir" build)" = "$id" ]

    # E o sha256 do pin é o do MANIFESTO do build antigo, não o de hoje.
    want="$(sed -n 's/^sha256=//p' "$SX_BUILD_STORE/$id/manifest.txt" | head -1)"
    [ "$(store::pin_get "$dir" sha256)" = "$want" ]
}

@test "staging records the jar name in the manifest" {
    fake_jar "$TMP/a/sexidium-paper-26.1.2+16.jar" "one"
    id="$(store::stage "$TMP/a/sexidium-paper-26.1.2+16.jar")"
    [ "$(sed -n 's/^paper-jar-name=//p' "$SX_BUILD_STORE/$id/manifest.txt" | head -1)" = "sexidium-paper-26.1.2+16.jar" ]
}

@test "a jar dropped from the shared install disappears from the node" {
    fake_jar "$SX_SHARED_PLUGINS/FastAsyncWorldEdit.jar" fawe
    dir="$(node_dir worker-1)"
    store::link_node_plugin_jars "$dir"
    [ -L "$dir/pluginjars/FastAsyncWorldEdit.jar" ]
    # INSTALL_WORLDEDIT=0 removes it from the shared tree; the node must follow.
    rm -f "$SX_SHARED_PLUGINS/FastAsyncWorldEdit.jar"
    store::link_node_plugin_jars "$dir"
    [ ! -e "$dir/pluginjars/FastAsyncWorldEdit.jar" ]
    [ ! -L "$dir/pluginjars/FastAsyncWorldEdit.jar" ]
}

# --- retention ----------------------------------------------------------------

@test "GC keeps the retention window and never collects a referenced build" {
    SX_BUILD_RETENTION=2
    for n in 1 2 3 4 5; do
        fake_jar "$TMP/j$n/Sexidium-Paper-1.0.0.jar" "build-$n"
        eval "id$n=\"\$(store::stage '$TMP/j$n/Sexidium-Paper-1.0.0.jar')\""
    done
    dir="$(node_dir worker-1)"
    # worker-1 is still on build 1 with build 2 as its rollback target -- both are
    # outside the retention window and both must survive.
    store::pin_node "$dir" "$id2"
    store::pin_node "$dir" "$id1" --record-previous

    store::gc
    [ -d "$SX_BUILD_STORE/$id1" ]
    [ -d "$SX_BUILD_STORE/$id2" ]
    [ -d "$SX_BUILD_STORE/$id4" ]
    [ -d "$SX_BUILD_STORE/$id5" ]
    [ ! -d "$SX_BUILD_STORE/$id3" ]
}

@test "the args file follows the pin whoever moved it" {
    # The pipeline flips pins through a helper container and never runs provision.sh,
    # so -Dsexidium.build.id has to be written by the pin primitive itself or it goes
    # stale exactly during a roll.
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    fake_jar "$TMP/b/Sexidium-Paper-1.0.0.jar" "two"
    old="$(store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar")"
    new="$(store::stage "$TMP/b/Sexidium-Paper-1.0.0.jar")"
    dir="$(node_dir worker-1)"
    printf -- '-Dsexidium.api.port=8810\n' >"$dir/sexidium-node.args"
    store::pin_node "$dir" "$old"
    store::pin_node "$dir" "$new" --record-previous
    [ "$(grep -c -- '-Dsexidium.build.id=' "$dir/sexidium-node.args")" = "1" ]
    grep -q -- "-Dsexidium.build.id=$new" "$dir/sexidium-node.args"
    grep -q -- '-Dsexidium.api.port=8810' "$dir/sexidium-node.args"
}

@test "GC on an empty store is a no-op, not a wipe" {
    # `printf '%s\n' "${all[@]}"` over an EMPTY array still emits one blank line, and a
    # blank build id would make the removal below expand to the store directory itself
    # -- taking every rollback target with it. Found by review, not by an incident.
    mkdir -p "$SX_BUILD_STORE"
    printf '3\n' >"$SX_BUILD_STORE/COUNTER"
    SX_BUILD_RETENTION=1
    run store::gc
    [ "$status" -eq 0 ]
    [ -d "$SX_BUILD_STORE" ]
    [ -f "$SX_BUILD_STORE/COUNTER" ]
}

@test "GC only ever removes directories whose name is a build id" {
    SX_BUILD_RETENTION=1
    fake_jar "$TMP/a/Sexidium-Paper-1.0.0.jar" "one"
    fake_jar "$TMP/b/Sexidium-Paper-1.0.0.jar" "two"
    store::stage "$TMP/a/Sexidium-Paper-1.0.0.jar" >/dev/null
    store::stage "$TMP/b/Sexidium-Paper-1.0.0.jar" >/dev/null
    # A directory the glob picks up but that is not a build. Anything unexpected here
    # must be left alone rather than guessed at.
    mkdir -p "$SX_BUILD_STORE/bogus-directory"
    store::gc
    [ -d "$SX_BUILD_STORE/bogus-directory" ]
}
