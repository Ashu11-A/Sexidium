#!/usr/bin/env bats
#
# Unit tests for scripts/lib/paper.sh helpers that guard the shared install.
#
# `paper::verify_jar_readable` is the gate between "gradle said BUILD SUCCESSFUL" and
# "every node on the network loads this file". It has exactly one job and it failed at
# it in the worst direction: it rejected an INTACT 30 MB jar and aborted a live
# provision, which left every node without a `pluginjars/` pin and therefore refusing to
# boot at all.
#
# The cause is worth pinning with a test forever, because it is invisible in review and
# it depends on SIZE: `unzip -l "$jar" | grep -q plugin.yml` under `set -o pipefail`
# (which docker/provision.sh sets) makes the grep exit at the FIRST match, the unzip die
# of SIGPIPE (141), and pipefail report that 141 as the pipeline's status -- so FINDING
# plugin.yml is what made the check fail. With a short listing the unzip finishes writing
# before the grep leaves, nothing dies, and the bug is dormant. It stayed dormant for
# months.
#
# So the test below does not test "does it find plugin.yml". It tests that a listing far
# larger than a pipe buffer, containing plugin.yml EARLY, is still accepted -- under
# pipefail, which is the condition that made this fire in production.

setup() {
    SCRIPTS_DIR="$(cd -- "$BATS_TEST_DIRNAME/../.." && pwd -P)"
    set -u
    . "$SCRIPTS_DIR/lib/core.sh"
    sx::require paper
    TMP="$BATS_TEST_TMPDIR/work"
    mkdir -p "$TMP/bin"
    JAR="$TMP/Sexidium-Paper-1.0.0.jar"
    printf 'not really a jar, the fake unzip decides\n' >"$JAR"
}

# Um `unzip` de mentira: imprime plugin.yml na SEGUNDA linha e depois despeja muito mais
# do que cabe no buffer do pipe (64 KiB no Linux). É essa combinação -- acerto cedo,
# escrita longa -- que mata o unzip real de SIGPIPE quando o outro lado é `grep -q`.
fake_unzip_big() {
    cat >"$TMP/bin/unzip" <<'EOF'
#!/usr/bin/env bash
echo "Archive:  $2"
echo "     1234  2026-08-13 11:35   plugin.yml"
for i in $(seq 1 20000); do
    echo "     1234  2026-08-13 11:35   com/sexidium/core/some/deeply/nested/Class$i.class"
done
EOF
    chmod +x "$TMP/bin/unzip"
    PATH="$TMP/bin:$PATH"
}

fake_unzip_without_plugin_yml() {
    cat >"$TMP/bin/unzip" <<'EOF'
#!/usr/bin/env bash
echo "Archive:  $2"
echo "     1234  2026-08-13 11:35   com/sexidium/core/Only.class"
EOF
    chmod +x "$TMP/bin/unzip"
    PATH="$TMP/bin:$PATH"
}

@test "a big listing containing plugin.yml is accepted under pipefail" {
    # pipefail é a condição de produção: docker/provision.sh roda com `set -Eeuo pipefail`.
    set -o pipefail
    fake_unzip_big
    run paper::verify_jar_readable "$JAR"
    [ "$status" -eq 0 ]
}

@test "a jar whose listing has no plugin.yml is still rejected" {
    set -o pipefail
    fake_unzip_without_plugin_yml
    run paper::verify_jar_readable "$JAR"
    [ "$status" -ne 0 ]
    [[ "$output" == *"not a readable jar"* ]]
}

# O jar do PROXY não tem plugin.yml e nunca teve: o Velocity declara um plugin em
# `velocity-plugin.json`. Enquanto o descritor era fixo, esta função reprovava todo jar de
# proxy íntegro -- e como só `paper::build_only` valida o do Velocity, e ele é o caminho do
# `pipeline`, isso ficou invisível enquanto os deploys iam por `update`. Custou um deploy
# abortado com "not a readable jar" sobre um jar perfeito.
fake_unzip_velocity() {
    cat >"$TMP/bin/unzip" <<'EOF'
#!/usr/bin/env bash
echo "Archive:  $2"
echo "      194  1980-02-01 00:00   velocity-plugin.json"
echo "     1234  2026-08-13 11:35   com/sexidium/velocity/Entry.class"
EOF
    chmod +x "$TMP/bin/unzip"
    PATH="$TMP/bin:$PATH"
}

@test "the proxy jar is verified by velocity-plugin.json, not by plugin.yml" {
    set -o pipefail
    fake_unzip_velocity
    run paper::verify_jar_readable "$JAR" velocity-plugin.json
    [ "$status" -eq 0 ]
}

@test "a proxy jar missing its own descriptor is still rejected" {
    set -o pipefail
    fake_unzip_without_plugin_yml
    run paper::verify_jar_readable "$JAR" velocity-plugin.json
    [ "$status" -ne 0 ]
    [[ "$output" == *"velocity-plugin.json"* ]]
}

# E o descritor do Paper continua sendo o padrão: um jar de Paper validado sem argumento
# não pode passar a ser aceito só por conter o descritor do outro lado.
@test "the default descriptor is still plugin.yml, so a proxy jar does not pass as a Paper one" {
    set -o pipefail
    fake_unzip_velocity
    run paper::verify_jar_readable "$JAR"
    [ "$status" -ne 0 ]
    [[ "$output" == *"plugin.yml"* ]]
}

@test "an unreadable jar is rejected, not silently accepted" {
    set -o pipefail
    cat >"$TMP/bin/unzip" <<'EOF'
#!/usr/bin/env bash
echo "caught: cannot find zipfile directory" >&2
exit 9
EOF
    chmod +x "$TMP/bin/unzip"
    PATH="$TMP/bin:$PATH"
    run paper::verify_jar_readable "$JAR"
    [ "$status" -ne 0 ]
}

# O quarto caso -- "sem unzip no host, pula a checagem em vez de reprovar o provisionamento"
# -- NÃO tem teste aqui de propósito: encurtar o PATH o bastante para esconder o unzip do
# host também esconde o que o próprio bats usa, e o teste derrubava a suíte inteira em vez
# de falhar. A linha que decide isso (`command -v unzip || return 0`) não foi tocada pela
# correção, então não há regressão a proteger.

# --- divergência de árvore: falhar tem de significar "não sei" -----------------
# `< <(find ...)` esconde o status do find -- pipefail não cobre substituição de
# processo. Um find que falha no meio devolvia divergência VAZIA, e vazio aqui
# significa "espelhado, pode apagar": o chamador roda `rm -rf` numa árvore que nunca
# conseguiu enumerar. Preservar uma cópia talvez redundante custa disco; apagar uma
# talvez única custa o que ninguém devolve.
@test "an unreadable tree is reported as divergent, never as mirrored" {
    mkdir -p "$TMP/local/sub" "$TMP/shared"
    echo hello >"$TMP/local/sub/only-here.txt"
    chmod 000 "$TMP/local/sub"
    run paper::tree_divergence "$TMP/local" "$TMP/shared"
    chmod 755 "$TMP/local/sub"
    [ -n "$output" ]
}

@test "a genuinely mirrored tree still reports no divergence" {
    mkdir -p "$TMP/l2" "$TMP/s2"
    echo same >"$TMP/l2/a.txt"
    echo same >"$TMP/s2/a.txt"
    run paper::tree_divergence "$TMP/l2" "$TMP/s2"
    [ -z "$output" ]
}

@test "a local-only file is still reported as divergent" {
    mkdir -p "$TMP/l3" "$TMP/s3"
    echo unique >"$TMP/l3/only.txt"
    run paper::tree_divergence "$TMP/l3" "$TMP/s3"
    [[ "$output" == *"only.txt"* ]]
}
