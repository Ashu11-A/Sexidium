#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# test-entry.sh -- entrypoint do container EFÊMERO de teste, irmão de node-entry.sh.
#
# Roda as três suítes do repo dentro da mesma imagem e do mesmo volume que a rede
# usa, e sai com o número de suítes que falharam:
#
#   scripts   scripts/test/run.sh          (shellcheck, shfmt, py, bats, golden trace)
#   gradle    ./gradlew build              (os testes JUnit vêm junto do build)
#   bot       bun install + bun run check  (tsc --noEmit)
#
# POR QUE UM CONTAINER PRÓPRIO E NÃO O `init`
#   O `init` termina em `Exited (0)` e é assim que ele deve ficar; ressuscitá-lo com
#   `docker exec` não funciona, e um serviço `test` permanente no stack seria RAM
#   parada. Este arquivo é criado/destruído por deploy (`scripts/remote.sh test`).
#
# SX_NODE NÃO é usado aqui de propósito: o `case` de produção de node-entry.sh não
# ganha um ramo `test` que jamais deveria rodar num nó de produção.
#
# O TOOLBOX MORA NO VOLUME, PINADO E CONFERIDO
#   Cada binário tem versão fixa e sha256 conferido. Versão fixa não é paranoia: o
#   estágio `shfmt` do run.sh compara FORMATAÇÃO, e formatadores mudam de opinião
#   entre versões -- um shfmt mais novo que o da estação de trabalho pinta o gate de
#   vermelho sem ninguém ter escrito uma linha errada. Os pins abaixo são exatamente
#   as versões usadas no desenvolvimento. Primeira execução ~2-3 min (download);
#   as seguintes, segundos (o toolbox fica em $SX_HOME/toolbox, no volume de build).
# -----------------------------------------------------------------------------
set -Eeuo pipefail

SX_HOME="${SX_HOME:-/srv/sexidium}"
export ROOT_DIR="${ROOT_DIR:-$SX_HOME/repo}"
NETWORK_DIR="${NETWORK_DIR:-$SX_HOME/run}"
TOOLBOX="${SX_TOOLBOX:-$SX_HOME/toolbox}"

# Um estágio pulado não é um estágio aprovado: sem isto o run.sh sai 0 anunciando
# "5 stage(s) OK" tendo executado os dois que não precisam de ferramenta externa.
export SX_TEST_STRICT="${SX_TEST_STRICT:-1}"

# Cache Gradle próprio: o `init` provisiona a rede a partir do MESMO volume, e dois
# daemons disputando o mesmo --project-cache-dir travam um ao outro.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$SX_HOME/gradle/home}"
export GRADLE_PROJECT_CACHE_DIR="${GRADLE_PROJECT_CACHE_DIR:-$SX_HOME/gradle/test-cache}"

export BUN_INSTALL="$TOOLBOX"
export PATH="$TOOLBOX/bin:$PATH"

SX_SUITES="${SX_SUITES:-scripts gradle bot}"
SX_TEST_RUN="${SX_TEST_RUN:-$(date +%Y%m%d-%H%M%S)}"
REPORTS_DIR="$NETWORK_DIR/test-reports/$SX_TEST_RUN"

SHELLCHECK_VERSION="0.10.0"
SHELLCHECK_SHA256="6c881ab0698e4e6ea235245f22832860544f17ba386442fe7e9d629f8cbedf87"
SHFMT_VERSION="3.10.0"
SHFMT_SHA256="1f57a384d59542f8fac5f503da1f3ea44242f46dff969569e80b524d64b71dbc"
BATS_VERSION="1.14.0"
BATS_SHA256="bb537b70b15b732f6d8827dd6578e3d8ce166636ce1f18ea9a074184fcce9177"
BUN_VERSION="1.3.14"
BUN_SHA256="951ee2aee855f08595aeec6225226a298d3fea83a3dcd6465c09cbccdf7e848f"

say() { printf '[test-entry] %s\n' "$*"; }
die() {
    printf '[test-entry] FATAL: %s\n' "$*" >&2
    exit 1
}

# Mesma lista de node-entry.sh mais `xz-utils` (o tarball do shellcheck é .tar.xz).
# ~15 s e não é cacheável: a imagem base é usada crua, sem build próprio.
install_toolchain() {
    # `import yaml` entra no guarda, e não só na lista de pacotes: a checagem existente
    # é "python3 está no PATH?", que responde SIM num container onde o MÓDULO falta --
    # e aí o apt-get é pulado e o módulo nunca chega. Foi assim que 7 testes do grupo
    # SkinsRestorer sumiram do gate sem uma linha de TAP cada (só o `bats warning:
    # Executed 99 instead of expected 106` os denunciou).
    if command -v curl >/dev/null && command -v git >/dev/null &&
        command -v python3 >/dev/null && command -v unzip >/dev/null &&
        command -v xz >/dev/null && python3 -c 'import yaml' 2>/dev/null; then
        return 0
    fi
    # diffutils/findutils nomeados de propósito: docker/provision.sh faz `need_cmd cmp`
    # e `need_cmd find` (a migração de mapas compara byte a byte antes de decidir que
    # uma cópia local é redundante), e o estágio golden-provision do run.sh roda esse
    # script aqui dentro. Ambos costumam vir na imagem base -- "costumam" é exatamente
    # a palavra que faz um gate falhar num rebase de imagem.
    say "instalando toolchain do container…"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    # python3-yaml (PyYAML) porque `sr_value`, em scripts/test/unit/lib.bats, lê o
    # config do SkinsRestorer com o MESMO parser que o plugin usa. Um leitor caseiro de
    # YAML aqui aprovaria justamente o arquivo que derrubou o plugin em produção, que é
    # o apagão que aquele grupo de testes existe para não repetir.
    apt-get install -y -qq --no-install-recommends \
        bash curl git unzip zip xz-utils python3 python3-yaml procps ca-certificates \
        diffutils findutils >/dev/null
    rm -rf /var/lib/apt/lists/*
}

# fetch <url> <destino> <sha256>
# Baixa para .part e só renomeia depois de conferir: um download interrompido não
# pode virar um binário meio escrito no volume, que sobreviveria a este container.
fetch() {
    local url="$1" dest="$2" want="$3" got
    curl -fsSL --retry 3 --retry-delay 2 -o "$dest.part" "$url" ||
        die "download falhou: $url"
    got="$(sha256sum "$dest.part" | cut -d' ' -f1)"
    if [[ "$got" != "$want" ]]; then
        rm -f "$dest.part"
        die "sha256 divergente em $url (esperado $want, obtido $got)"
    fi
    mv -f "$dest.part" "$dest"
}

ensure_toolbox() {
    # Os pins são binários linux/amd64. Num host arm o certo é falhar alto, não
    # baixar silenciosamente algo que não executa.
    [[ "$(uname -m)" == "x86_64" ]] || die "toolbox pinado para x86_64, não $(uname -m)"

    local bin="$TOOLBOX/bin" tmp="$TOOLBOX/.dl"
    mkdir -p "$bin" "$tmp"

    if [[ ! -x "$bin/shellcheck" ]]; then
        say "toolbox: shellcheck $SHELLCHECK_VERSION"
        fetch "https://github.com/koalaman/shellcheck/releases/download/v$SHELLCHECK_VERSION/shellcheck-v$SHELLCHECK_VERSION.linux.x86_64.tar.xz" \
            "$tmp/shellcheck.tar.xz" "$SHELLCHECK_SHA256"
        tar -xJf "$tmp/shellcheck.tar.xz" -C "$tmp"
        install -m 0755 "$tmp/shellcheck-v$SHELLCHECK_VERSION/shellcheck" "$bin/shellcheck"
    fi

    if [[ ! -x "$bin/shfmt" ]]; then
        say "toolbox: shfmt $SHFMT_VERSION"
        fetch "https://github.com/mvdan/sh/releases/download/v$SHFMT_VERSION/shfmt_v${SHFMT_VERSION}_linux_amd64" \
            "$tmp/shfmt" "$SHFMT_SHA256"
        install -m 0755 "$tmp/shfmt" "$bin/shfmt"
    fi

    # Tarball do release em vez de `git clone`: o clone traz a ponta do branch, que
    # não é uma versão, e depende de a rede ter git+ssh -- este só precisa de curl.
    if [[ ! -x "$bin/bats" ]]; then
        say "toolbox: bats-core $BATS_VERSION"
        fetch "https://github.com/bats-core/bats-core/archive/refs/tags/v$BATS_VERSION.tar.gz" \
            "$tmp/bats.tar.gz" "$BATS_SHA256"
        tar -xzf "$tmp/bats.tar.gz" -C "$tmp"
        "$tmp/bats-core-$BATS_VERSION/install.sh" "$TOOLBOX" >/dev/null
    fi

    # O zip do release, não o `curl … | bash` de bun.sh/install: o instalador oficial
    # é um script mutável servido por HTTP, ou seja, exatamente o que um sha256 não
    # consegue prender. O zip é imutável e versionado.
    if [[ ! -x "$bin/bun" ]]; then
        say "toolbox: bun $BUN_VERSION"
        fetch "https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION/bun-linux-x64.zip" \
            "$tmp/bun.zip" "$BUN_SHA256"
        unzip -qo "$tmp/bun.zip" -d "$tmp"
        install -m 0755 "$tmp/bun-linux-x64/bun" "$bin/bun"
    fi

    rm -rf "$tmp"

    # Um binário faltando aqui viraria um SKIP lá na frente -- e um SKIP lido como
    # aprovação é o bug que este container existe para não repetir.
    local t
    for t in shellcheck shfmt bats bun python3; do
        command -v "$t" >/dev/null || die "toolbox incompleto: $t ausente após o ensure"
    done
    # Um MÓDULO ausente é pior que um binário ausente: o binário vira um SKIP visível, o
    # módulo faz o bats engolir os testes que dependem dele sem uma linha sequer. Falhar
    # nomeadamente aqui é a diferença entre "o gate reprovou" e "o gate testou menos".
    python3 -c 'import yaml' 2>/dev/null || die "toolbox incompleto: PyYAML ausente após o ensure"
}

# --- suítes -------------------------------------------------------------------
# Cada uma roda isolada (`|| true` no status) e acumula em $failures: uma suíte
# vermelha não pode esconder o resultado das outras duas.
failures=0
declare -a SUMMARY=()

suite_scripts() {
    bash "$ROOT_DIR/scripts/test/run.sh"
}

suite_gradle() {
    cd "$ROOT_DIR"
    ./gradlew --no-daemon --project-cache-dir "$GRADLE_PROJECT_CACHE_DIR" build
}

suite_bot() {
    cd "$ROOT_DIR/bot"
    bun install --frozen-lockfile
    bun run check
}

# detail_<suíte> <arquivo-de-log> -> uma linha para o resumo final.
detail_scripts() {
    grep -E '^scripts/test/run\.sh:' "$1" | tail -1 || printf 'sem linha de resumo'
}

detail_gradle() {
    python3 - "$ROOT_DIR" <<'PY' 2>/dev/null || printf 'sem test-results'
import glob, os, sys, xml.etree.ElementTree as ET
tests = fails = 0
for f in glob.glob(os.path.join(sys.argv[1], "build/packages-*/test-results/test/*.xml")):
    try:
        r = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    tests += int(r.get("tests", 0))
    fails += int(r.get("failures", 0)) + int(r.get("errors", 0))
print(f"{tests} tests, {fails} failures")
PY
}

detail_bot() {
    local n
    n="$(grep -c 'error TS' "$1" || true)"
    if [[ "$n" -gt 0 ]]; then
        printf 'tsc --noEmit: %s errors' "$n"
    else
        printf 'tsc --noEmit clean'
    fi
}

run_suite() {
    local name="$1" started elapsed status log
    log="$REPORTS_DIR/$name.log"
    started="$SECONDS"
    printf '\n=== suite: %s ===\n' "$name"
    status=0
    # `tee`: o log vai para o arquivo E para o stdout do container, que é o que o
    # `logs?follow=1` do chamador está lendo em tempo real.
    "suite_$name" 2>&1 | tee "$log" || status="${PIPESTATUS[0]}"
    elapsed=$((SECONDS - started))
    local verdict="PASS"
    if [[ "$status" -ne 0 ]]; then
        verdict="FAIL"
        failures=$((failures + 1))
    fi
    SUMMARY+=("$(printf '%-8s %-4s %-42s %ss' \
        "$name" "$verdict" "$("detail_$name" "$log" | head -1)" "$elapsed")")
}

# Relatórios do Gradle. Os caminhos deste repo NÃO são os do layout padrão
# (build/<subprojeto>/…): o build junta tudo em build/packages-<nome>/.
collect_reports() {
    local d
    for d in "$ROOT_DIR"/build/packages-*/test-results/test \
        "$ROOT_DIR"/build/packages-*/reports/tests/test \
        "$ROOT_DIR"/build/packages-core/reports/jacoco; do
        [[ -d "$d" ]] || continue
        # Reproduz o sufixo build/... dentro do diretório do run, para dois
        # subprojetos com o mesmo nome de arquivo não se sobrescreverem.
        local rel="${d#"$ROOT_DIR"/build/}"
        mkdir -p "$REPORTS_DIR/$rel"
        cp -r "$d/." "$REPORTS_DIR/$rel/" 2>/dev/null || true
    done
}

main() {
    install_toolchain
    ensure_toolbox
    mkdir -p "$REPORTS_DIR"
    say "run=$SX_TEST_RUN suites='$SX_SUITES' relatórios em $REPORTS_DIR"

    local s
    for s in $SX_SUITES; do
        case "$s" in
            scripts | gradle | bot) run_suite "$s" ;;
            *) die "suíte desconhecida: $s (use: scripts gradle bot)" ;;
        esac
    done

    collect_reports

    # Bloco final ESTÁVEL: é o que o chamador raspa do log para reportar.
    printf '\n=== SEXIDIUM TEST SUMMARY ===\n'
    local line
    for line in "${SUMMARY[@]}"; do printf '%s\n' "$line"; done
    printf 'exit=%s\n' "$failures"
    exit "$failures"
}

main "$@"
