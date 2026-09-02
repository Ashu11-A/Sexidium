#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# node-entry.sh -- entrypoint ÚNICO de todos os containers da rede Sexidium.
#
# Uma imagem (eclipse-temurin:25-jdk), uma pasta-fonte (/srv/sexidium/server) e UM
# parâmetro decidindo o que aquela execução é:
#
#   SX_NODE=init       provisiona proxy + backends (um único build de Gradle) e sai
#   SX_NODE=proxy      roda o Velocity
#   SX_NODE=lobby      roda o Paper do lobby
#   SX_NODE=worker-N   roda o Paper daquele worker
#
# Os nós NÃO provisionam nada: esperam o carimbo que o `init` escreve. Isso mantém
# um só Gradle, um só cache de artefatos e um só ponto de atualização -- reprovisionar
# é rodar o `init` de novo e reiniciar os nós.
#
# Os backends não têm instalação própria: existe UMA pasta-fonte -- $SX_SHARED_INSTALL,
# que no stack é /srv/sexidium/server -- com o paper.jar e os jars de plugin, e por nó
# só um diretório de trabalho em $NETWORK_DIR/<nó>: mundos, logs, data folders e as
# configs que o Paper reescreve a cada boot. Esse diretório é um VOLUME próprio de cada
# container, montado no mesmo caminho aqui e no `init`. Ver run_backend e
# docs/operations/deployment.md.
#
# O mesmo vale para os TEMPLATES de mapa de minigame: existe uma árvore só,
# $SX_SHARED_MAPS/<bundle>/<id>, semeada pelo `init` a partir do jar, e cada nó chega
# nela por um symlink POR BUNDLE em <nó>/worlds/<bundle>. O que é compartilhado é
# o template, que nenhuma JVM abre como mundo -- uma partida COPIA dele para um mundo
# novo antes de tocar em qualquer chunk. A raiz worlds/ continua sendo um diretório
# REAL e local do nó, e é o que verify_world_root existe para garantir.
#
# O provisionamento de rede vive em docker/provision.sh (ao lado deste arquivo);
# scripts/lib/*.sh são usados exatamente como estão, e o golden trace de
# scripts/test/run.sh -- que cobre init-paper.sh -- continua válido.
# -----------------------------------------------------------------------------
set -Eeuo pipefail

# Sem core dumps. Este host corrompe memória: 7 SIGSEGV em 3 dias, em 5 subsistemas
# independentes da JVM (marca/relocação/barreira do ZGC, runtime do HotSpot e DUAS vezes
# a thread C2 do compilador, uma delas executando em RIP=0x8), em 3 containers. Cada
# crash grava um core de 4,5-5,1 GB em $NETWORK_DIR/<nó>/ -- no MESMO dispositivo de
# bloco da árvore de mundos COMPARTILHADA e do redo log do MariaDB. É isso que faz uma
# recuperação levar ~60s em vez de ~15s e o que espalha a lentidão para os outros nós,
# que não crasharam nada: 5 GB de escrita suja empurram o host além do dirty_ratio e
# todo escritor da máquina bloqueia em balance_dirty_pages.
#
# O core não é perdido, porque nunca foi lido: o diagnóstico dos 7 crashes saiu inteiro
# do hs_err_pid<pid>.log, que é pequeno, fica ao lado e continua sendo escrito. Um core
# de 5 GB de heap ZGC só ajudaria se a suspeita fosse um bug NOSSO de memória -- e a
# suspeita é hardware, que se investiga com memtest, não com core dump.
#
# Duas travas porque falham por caminhos diferentes: o ulimit impede o kernel de escrever
# o arquivo, a flag impede a JVM de tentar. Ver [[jvm-gc-crashes-not-the-collector]].
ulimit -c 0 2>/dev/null || true

SX_HOME="${SX_HOME:-/srv/sexidium}"
SX_NODE="${SX_NODE:?defina SX_NODE (init|proxy|lobby|worker-N)}"

export ROOT_DIR="${ROOT_DIR:-$SX_HOME/repo}"
export NETWORK_DIR="${NETWORK_DIR:-$SX_HOME/run}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$SX_HOME/gradle/home}"
export GRADLE_PROJECT_CACHE_DIR="${GRADLE_PROJECT_CACHE_DIR:-$SX_HOME/gradle/project-cache}"

# Lido por build_and_copy_plugin (scripts/lib/sexidium.sh), só quando SX_NODE=init chega a
# chamar Gradle. Default 1 AQUI, e não no default da lib, de propósito: um daemon Gradle
# nunca sobrevive entre duas execuções DESTE container, porque o `init` roda um `./gradlew`
# só, uma vez, e sai -- e a saída do PID 1 de um container derruba o namespace de PID inteiro,
# matando qualquer daemon que ele tenha bifurcado, mesmo que o registro do daemon (que fica no
# volume sexidium-build e por isso SOBREVIVE) continue apontando pra ele. É esse descompasso
# -- registro vivo, processo morto -- que produz o aviso "N incompatible and N stopped Daemons
# could not be reused" nos logs de produção a cada `init`. Como reaproveitar é estruturalmente
# impossível aqui, bifurcar um daemon só para descartá-lo é perda pura; --no-daemon roda o
# build na própria JVM do launcher e pula o fork+handshake (e o aviso some junto, porque
# --no-daemon nunca toca o registro). Fora do container -- alguém rodando scripts/init-paper.sh
# à mão, num host que persiste entre execuções -- esta variável fica por definir e o daemon
# volta a valer a pena, exatamente como hoje.
export SX_GRADLE_NO_DAEMON="${SX_GRADLE_NO_DAEMON:-1}"

# A instalação compartilhada: um paper.jar e um conjunto de jars de plugin para os
# 4 backends, escritos uma vez pelo `init` e só lidos pelos nós. Configurável por
# env porque o provisionador expõe a MESMA tunável (scripts/lib/velocity.sh) -- as
# duas pontas têm de poder ser movidas juntas, e um default divergente aqui só se
# manifestaria como um nó que não sobe.
SX_SHARED_INSTALL="${SX_SHARED_INSTALL:-$NETWORK_DIR/shared/install}"
SX_SHARED_PLUGINS="${SX_SHARED_PLUGINS:-$SX_SHARED_INSTALL/plugins}"

# A árvore compartilhada de TEMPLATES de mapa. Mesmo default de scripts/lib/velocity.sh,
# pela mesma razão do par acima: as duas pontas têm de poder ser movidas juntas. Aqui
# ela é só LIDA, e só para conferir o layout -- quem escreve é o `init`.
SX_SHARED_MAPS="${SX_SHARED_MAPS:-$NETWORK_DIR/shared/maps}"

# O jar do Sexidium recém-construído, na origem. O `init` tira daqui o sha256 do
# carimbo e os nós tiram daqui só o NOME do arquivo, para achar sua cópia instalada
# na árvore compartilhada. Uma variável, um default, os dois lados concordando.
#
# O default NÃO é mais uma constante: o artefato se chama
# sexidium-paper-<mc>+<contador>.jar e o nome sai de minecraft-targets.properties --
# a MESMA fonte que o build Gradle lê e que scripts/lib/sexidium.sh deriva via
# sexidium::paper_jar_name. Este entrypoint não carrega as libs (mesmo precedente
# do espelho de velocity.sh adiante), então o awk abaixo É o espelho daquele helper:
# piso = primeira entrada de `supported`, contador = a chave DELE. Mudou lá, muda aqui.
SEXIDIUM_JAR_DEFAULT="$(awk -F= '
    $1 == "sexidium.minecraft.supported"    { supported = $2 }
    /^sexidium\.minecraft\.[0-9.]+\.build=/ { counter[$1]   = $2 }
    END {
        split(supported, v, ",")
        floor = v[1]; gsub(/[[:space:]]/, "", floor)
        c = counter["sexidium.minecraft." floor ".build"]; gsub(/[[:space:]]/, "", c)
        if (floor == "" || c == "") exit 1
        printf "sexidium-paper-%s+%s.jar", floor, c
    }' "$ROOT_DIR/minecraft-targets.properties" 2>/dev/null)" || SEXIDIUM_JAR_DEFAULT=""
if [[ -n "${SEXIDIUM_JAR:-}" ]]; then
    SEXIDIUM_JAR_PATH="$SEXIDIUM_JAR"
else
    # Sem fonte e sem override não há nome para adivinhar: falhar AQUI, com a causa,
    # e não depois como "jar not found" num caminho terminando em barra.
    if [[ -z "$SEXIDIUM_JAR_DEFAULT" ]]; then
        printf '[node-entry:%s] FATAL: sem %s/minecraft-targets.properties e sem SEXIDIUM_JAR; de onde sairia o nome do artefato?\n' "$SX_NODE" "$ROOT_DIR" >&2
        exit 1
    fi
    SEXIDIUM_JAR_PATH="$ROOT_DIR/build/libs/paper/$SEXIDIUM_JAR_DEFAULT"
fi

# Onde vive o carimbo. TEM de ser um caminho que os seis containers enxergam, e
# NETWORK_DIR não é mais um deles: /srv/nodes é só um ponto de montagem e cada nó
# recebe ali apenas o SEU volume, então um carimbo escrito pelo `init` seria
# invisível para os quatro backends -- que esperariam os 1800 s inteiros por algo
# que já está escrito. O default é o de antes, para que o servidor standalone e o
# golden trace (NETWORK_DIR local, sem volume nenhum) sigam funcionando iguais.
SX_STATE_DIR="${SX_STATE_DIR:-$NETWORK_DIR}"
STAMP="$SX_STATE_DIR/.provisioned"
# Lápide de provisionamento quebrado. Existe para separar "ainda não terminou" de
# "terminou mal": sem ela, um init que falha é indistinguível de um init lento e os
# 5 nós esperam os 1800 s inteiros antes de morrer, um por um.
FAILED="$SX_STATE_DIR/.provision-failed"
STAMP_TIMEOUT="${STAMP_TIMEOUT:-1800}"

say() { printf '[node-entry:%s] %s\n' "$SX_NODE" "$*"; }
die() {
    printf '[node-entry:%s] FATAL: %s\n' "$SX_NODE" "$*" >&2
    exit 1
}

# O provisionamento precisa de mais que um JDK. A imagem base é usada crua (sem build
# próprio), então as ferramentas entram aqui -- só no `init`, nunca nos nós, que rodam
# apenas `java`.
install_toolchain() {
    command -v git >/dev/null && command -v python3 >/dev/null && command -v unzip >/dev/null && return 0
    say "instalando toolchain de provisionamento…"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends \
        bash curl git unzip zip python3 procps ca-certificates openssl \
        diffutils findutils >/dev/null
    rm -rf /var/lib/apt/lists/*
}

# Heap por papel, mesma regra do heap_for() de scripts/net.sh, mais dois flags que
# valem para qualquer nó:
#   ExitOnOutOfMemoryError -- sem ele a JVM sobrevive ao OOM em estado inconsistente
#     e continua "de pé" para o Docker enquanto grava chunks corrompidos. Morrer
#     rápido devolve o controle ao `restart: unless-stopped`.
#   MaxMetaspaceSize -- o metaspace é RSS FORA do heap; sem teto ele cresce com o
#     número de classes (plugins + scripts) até o cgroup nos matar com SIGKILL, e aí
#     não há shutdown hook nem save-all. O stop_grace_period do stack não protege
#     contra OOM-kill: só contra SIGTERM.
#   UseZGC -- o coletor. NAO e escolha de gosto nem busca de pausa menor: os cinco SIGSEGV
#     do worker-1 caem todos dentro do G1, nos frames G1CMOopClosure / G1ParScanThreadState /
#     oopDesc::size_given_klass, com um ponteiro-lixo DIFERENTE a cada crash (0xb9000008,
#     0xe5400008) sempre na klass word de um objeto fora do heap. Isso e corrupcao em nivel de
#     JVM -- familia conhecida de bugs do G1, ver JDK-8333015 -- e nada no plugin a produz: o
#     codigo nao tem Unsafe, nem JNI, nem allocateDirect.
#     No JDK 25 o ZGC ja e generacional por padrao; NAO passe -XX:+ZGenerational (removido).
#
#     ATENCAO -- A TESE ACIMA CAIU. O paragrafo anterior conclui que trocar de coletor
#     "REMOVE o caminho onde o bug existe, porque esses frames sao exclusivos do G1".
#     Nao remove. Sob ZGC os tres nos voltaram a crashar (ZMark::mark_and_follow,
#     ZRelocateWork::do_forwarding, ZMark::follow_object, ZBarrier::mark_from_young_slow_path),
#     e em 17/08/2026 o worker-1 caiu 4x em 14 minutos -- duas delas na thread C2 do
#     COMPILADOR (PhaseIterGVN / MachNode::in_RegMask), uma executando em RIP=0x8, que
#     nenhum coletor alcanca. Fora da JVM: o daemon do Gradle caiu igual, e um `cp` de 36 MB
#     no host produziu 1 bit trocado (offset 6162375, 0x1B -> 0x1A) com tamanho identico.
#     Duas bibliotecas independentes e um cp simples enxergando estruturas proprias de
#     forma impossivel = corrupcao de host, nao bug de coletor. O host nao reporta ECC
#     (/sys/devices/system/edac/mc vazio), entao RAM ruim e silenciosa por construcao.
#     MANTENHA o ZGC (nao ha motivo para voltar), mas NAO trate a troca como correcao:
#     a proxima acao honesta e memtest no host, nao mais uma flag de JVM.
#   ConcGCThreads -- 4, e nao os 2 que o G1 usava. Aqui a diferenca importa: o ZGC faz
#     praticamente todo o trabalho concorrente, entao threads concorrentes de menos nao
#     "atrasam a limpeza", elas produzem ALLOCATION STALL -- a thread do jogo para esperando
#     memoria, que e exatamente a pausa que se queria evitar. 4 da folga sem soltar os 24
#     cores que a JVM enxerga (o stack nao tem cpu_quota).
#   ParallelGCThreads -- 8, para as fases ainda parem do ZGC, pelo mesmo motivo de antes:
#     dimensionar pelo container, nao pelos 24 cores do host, porque cada worker carrega
#     estruturas em memoria NATIVA (fora do -Xmx) num no que ja vive perto do teto.
heap_args() {
    local heap
    case "$SX_NODE" in
        lobby) heap="${SX_LOBBY_MEMORY:--Xms1G -Xmx2G}" ;;
        *) heap="${SX_WORKER_MEMORY:--Xms1G -Xmx3G}" ;;
    esac
    printf '%s -XX:+ExitOnOutOfMemoryError -XX:MaxMetaspaceSize=512m -XX:+UseZGC -XX:ParallelGCThreads=8 -XX:ConcGCThreads=4 -XX:-CreateCoredumpOnCrash' "$heap"
}

wait_for_stamp() {
    local waited=0
    [[ -f "$STAMP" ]] || say "esperando o provisionamento (container init)…"
    while [[ ! -f "$STAMP" ]]; do
        # Fast-fail: se o init deixou a lápide e nenhum carimbo, esperar não conserta.
        # (Com carimbo antigo + lápide o nó segue em frente de propósito: a árvore boa
        # ainda está lá e vale mais que ficar fora do ar por causa de um deploy ruim.)
        # `if` explícito, não `[[ … ]] && die`: sob `set -e` a forma curta faz a função
        # inteira retornar 1 -- e abortar o nó -- justamente quando o teste é FALSO.
        if [[ -f "$FAILED" ]]; then
            die "provisionamento falhou; veja $FAILED e o log do container init"
        fi
        sleep 5
        waited=$((waited + 5))
        [[ "$waited" -lt "$STAMP_TIMEOUT" ]] || die "sem carimbo em $STAMP após ${STAMP_TIMEOUT}s"
    done
}

# "Existe um `java` vivo neste namespace de PID?" -- inventário por /proc, que é o
# único disponível: os nós rodam a imagem base crua e `procps` (pgrep/ps) só é
# instalado no `init`. Um nome de processo já basta porque a imagem roda uma coisa
# só; não há outro java aqui que não seja O servidor.
#
# Sem /proc legível a resposta é SIM. A função não responde "há um java?", responde
# "posso provar que NÃO há?" -- e não conseguir provar é, para quem chama, idêntico
# a haver dono.
java_process_alive() {
    [[ -r /proc/self/comm ]] || return 0
    local comm name
    for comm in /proc/[0-9]*/comm; do
        # Um PID pode morrer entre o glob e a leitura; aqui isso é ruído, não erro.
        # (E com /proc vazio o glob fica literal e cai neste mesmo `continue`.)
        [[ -r "$comm" ]] || continue
        read -r name <"$comm" 2>/dev/null || continue
        if [[ "$name" == "java" ]]; then
            return 0
        fi
    done
    return 1
}

# `session.lock` é a ÚNICA proteção do Minecraft contra dois servidores no mesmo
# mundo, e ela funciona: sem o arquivo o segundo servidor sobe feliz e as duas JVMs
# passam a gravar chunks no mesmo world/ -- reproduzido ao vivo. Este script apagava
# o lock em todo boot, ou seja, trocava uma falha alta e limpa
# (`DirectoryLock$LockException`, o nó não sobe e diz por quê) por corrupção
# silenciosa de dados.
#
# O lock também sobrevive a um SIGKILL/OOM da JVM, e aí ele é órfão: ninguém o
# detém e o nó não volta sozinho nunca mais. Daí o critério ser o DONO, não a
# existência: só removemos quando dá para provar que nenhum java vive aqui. Na
# dúvida o lock fica e o Paper falha alto -- perder um boot é barato, perder chunks
# não é.
# O `find` roda em -P (o default: NÃO segue symlink) e é disso que ele depende agora
# que worlds/<bundle> são links para a árvore compartilhada -- ele varre o diretório do
# nó e para na borda de cada link, então nenhum passe deste script pode apagar coisa
# alguma dentro de run/shared/maps. NUNCA acrescente -L nem -follow aqui: um único nó
# bootando passaria a mexer na árvore que os outros três estão lendo. (Template também
# não tem session.lock para apagar: ele nunca é aberto como mundo -- é copiado.)
clear_session_lock() {
    if java_process_alive; then
        say "AVISO: há um java vivo neste container; session.lock preservado" \
            "(se ele for de outro dono o Paper vai recusar o boot, e é o certo)"
        return 0
    fi
    find "$1" -name "session.lock" -delete 2>/dev/null || true
}

# O layout de mapas, conferido do lado do runtime.
#
# `die`, e não AVISO como verify_shared_jar -- a assimetria é deliberada e vale a pena
# entender. Lá, recusar o boot troca um deploy meio-torto por uma rede fora do ar, e
# nenhum dado está em jogo. Aqui é o contrário: com worlds/ apontando para uma árvore
# compartilhada, BOOTAR é a ação destrutiva. O contrato do core põe temp/ e experience/
# dentro de worldRoot(), então a limpeza de temporários deste nó -- que roda no start,
# antes de qualquer jogador -- apagaria as partidas VIVAS dos outros três. Perder um
# boot é barato; isso não tem desfazer. O provisionador recusa pelo mesmo motivo
# (paper::link_shared_maps), e o runtime tem de ser igualmente explícito: um nó que
# subisse torto aqui não daria erro nenhum, só destruiria mundo alheio em silêncio.
#
# O aviso do caso comum olha worlds/<bundle>, NUNCA worlds/: um bundle que ainda é
# diretório real quer dizer que este nó não foi reprovisionado depois da migração. Não
# é fatal (o Paper lê a cópia local e as partidas funcionam), mas é o estado em que uma
# edição de mapa feita na autoridade não chega aqui -- exatamente a divergência
# silenciosa que a árvore única existe para acabar.
verify_world_root() {
    local worlds="$1/worlds" bundle name local_copies=""
    if [[ -L "$worlds" ]]; then
        die "$worlds é um symlink; a raiz de mundos tem de ser local do nó" \
            "(só worlds/<bundle> é compartilhado -- temp/ e experience/ moram aqui dentro)"
    fi
    [[ -d "$SX_SHARED_MAPS" && -d "$worlds" ]] || return 0

    for bundle in "$SX_SHARED_MAPS"/*/; do
        # Sem match o glob fica literal e este teste o descarta.
        [[ -d "$bundle" ]] || continue
        name="$(basename "$bundle")"
        if [[ -d "$worlds/$name" && ! -L "$worlds/$name" ]]; then
            local_copies+=" $name"
        fi
    done
    if [[ -n "$local_copies" ]]; then
        say "AVISO: cópia local de mapa em worlds/ (esperava symlink para $SX_SHARED_MAPS):${local_copies};" \
            "este nó pode estar servindo um mapa antigo -- rode o \`init\` e reinicie"
    fi
}

# O log4j2 do Paper roda sem política de retenção: os .log.gz de cada boot se
# acumulam para sempre, dentro do mesmo volume que guarda os mundos. Podar por
# idade aqui é a política mais barata que existe; a alternativa
# (-Dlog4j2.configurationFile com um XML próprio) mata o console do Paper em
# silêncio quando o XML está errado, pelo mesmo ganho.
prune_old_logs() {
    local days="${SX_LOG_RETENTION_DAYS:-30}"
    # `|| return 0` cobre também um valor não-numérico: retenção mal configurada
    # não pode ser motivo para um nó não subir.
    [[ "$days" -gt 0 ]] 2>/dev/null || return 0
    find "$1/logs" -maxdepth 1 -name '*.log.gz' -mtime "+$days" -delete 2>/dev/null || true
}

# O carimbo descreve o jar que o `init` instalou. Com uma instalação compartilhada
# existe UM caminho onde esse jar pode estar, então a comparação fica barata e
# conclusiva -- e ela é, na prática, o teste de que o layout compartilhado está
# valendo: "um nó rodando outro build" deixa de ser improvável e passa a ser
# impossível, e este aviso é quem denuncia se não for.
#
# AVISO, nunca `die`: derrubar um nó por divergência de hash transforma um deploy
# meio-torto numa rede fora do ar -- exatamente o que a preservação do carimbo
# antigo em run_init existe para evitar.
#
# O QUE MUDOU: o jar não mora mais numa cópia única compartilhada. Cada nó resolve o
# SEU build por um symlink em <nó>/pluginjars/, e qual build é esse está escrito em
# <nó>/sexidium-build.pin. O carimbo passou a descrever o build que o `init` STAGEOU
# por último -- o ALVO de uma atualização -- e não o que cada nó roda; durante um
# rolling update os dois divergem de propósito, e é exatamente por isso que esta função
# imprime SEMPRE o build id: "worker-3 está no b0042, os outros no b0041" é uma frase
# que agora se pode ler no log em vez de deduzir.
#
# A ÚNICA exceção ao "avisa, não mata" é o link quebrado. Um symlink pendurado é
# indistinguível de "este nó não tem plugin nenhum": o Paper sobe, não carrega o
# Sexidium, e o nó fica de pé servindo nada -- sem erro que se pareça com um erro.
verify_pinned_jar() {
    local dir="$1" jar pin build want have stamp_build
    jar="$dir/pluginjars/$(basename "$SEXIDIUM_JAR_PATH")"
    pin="$dir/sexidium-build.pin"

    if [[ -L "$jar" && ! -e "$jar" ]]; then
        die "pin quebrado: $jar aponta para um build que não existe mais" \
            "(o store foi podado? rode o \`init\`)"
    fi
    [[ -e "$jar" ]] || die "sem jar do Sexidium em $jar (este nó nunca foi pinado; rode o \`init\`)"

    build="$(sed -n 's/^build=//p' "$pin" 2>/dev/null | head -1 || true)"
    want="$(sed -n 's/^sha256=//p' "$pin" 2>/dev/null | head -1 || true)"
    stamp_build="$(sed -n 's/^plugin-build=//p' "$STAMP" 2>/dev/null | head -1 || true)"
    have="$(sha256sum "$jar" | cut -d' ' -f1)"

    say "build ${build:-<sem pin>} (${have:0:12}) pinado; o carimbo descreve ${stamp_build:-<desconhecido>}"
    if [[ -n "$want" && "$want" != "$have" ]]; then
        say "AVISO: $jar ($have) diverge do pin ($want); o pin foi escrito por outro processo?"
    fi
}

run_init() {
    install_toolchain
    [[ -x "$ROOT_DIR/docker/provision.sh" ]] || die "repo ausente em $ROOT_DIR"

    # VARIÁVEIS DAQUELA EXECUÇÃO, escritas pelo pipeline antes de dar start no `init`.
    #
    # O env do serviço vem do compose e não dá para mudá-lo sem redeployar o stack --
    # o que recriaria containers e, com prune:true, derrubaria jogadores. Então o
    # pipeline deixa um arquivo no volume compartilhado e ele é carregado aqui.
    # SEMPRE apagado depois de lido: um override esquecido faria a PRÓXIMA execução
    # (a manual, a de alguém que não sabe que ele existe) rodar em modo de pipeline.
    if [[ -f "$SX_STATE_DIR/pipeline/init-env.sh" ]]; then
        say "carregando overrides do pipeline ($(grep -c '^export' "$SX_STATE_DIR/pipeline/init-env.sh" || true) variáveis)"
        # shellcheck source=/dev/null
        . "$SX_STATE_DIR/pipeline/init-env.sh"
        rm -f "$SX_STATE_DIR/pipeline/init-env.sh"
        # Reavaliado DEPOIS do source: o pipeline aponta SEXIDIUM_JAR para o artefato
        # já no store (SX_SKIP_BUILD=1), e o carimbo tem de descrever o que foi
        # realmente instalado, não o caminho de build que este arquivo leu no começo.
        SEXIDIUM_JAR_PATH="${SEXIDIUM_JAR:-$SEXIDIUM_JAR_PATH}"
    fi
    mkdir -p "$NETWORK_DIR" "$SX_STATE_DIR" "$GRADLE_USER_HOME" "$GRADLE_PROJECT_CACHE_DIR"

    # O carimbo antigo NÃO é apagado antes de provisionar. Ele é a prova de que
    # existe uma árvore boa em run/; apagá-lo primeiro significava que qualquer
    # init malsucedido (mirror fora do ar, Gradle sem rede) derrubava junto os 5
    # nós, que voltavam a esperar 1800 s por um carimbo que ninguém mais ia
    # escrever. Falha de deploy tem de deixar a rede EXATAMENTE como estava.
    say "provisionando: nós='${SX_NODES:-lobby worker-1 worker-2 worker-3}' db=${SEXIDIUM_DB_TYPE:-<nenhum>}"
    if ! "$ROOT_DIR/docker/provision.sh"; then
        printf 'failed-at=%s\n' "$(date -Is)" >"$FAILED"
        die "provisionamento falhou; carimbo anterior preservado (lápide em $FAILED)"
    fi
    rm -f "$FAILED"

    # Modo build-only NÃO carimba. O carimbo é a prova de que existe uma árvore BOA em
    # run/ e é o que os cinco nós esperam antes de subir; um build-only não provisionou
    # nada, então reescrevê-lo diria uma coisa que não aconteceu. O carimbo anterior
    # fica exatamente como estava -- a mesma regra que vale para um init que falha.
    if [[ "${SX_PROVISION_MODE:-full}" == "build" ]]; then
        say "modo build: artefato estacionado no store; carimbo anterior preservado"
        return 0
    fi

    # O carimbo tem de descrever o jar que foi REALMENTE instalado na instalação
    # compartilhada, porque é contra ele que se compara para responder "esse nó está
    # rodando o código do último deploy?" -- é o que verify_shared_jar faz no boot de
    # cada nó. Quem copia é sexidium::install_jar "$SEXIDIUM_JAR", cujo default
    # (paper::defaults) é este caminho -- então honramos a mesma variável em vez de repetir
    # a constante, e um override do stack continua descrevendo a verdade.
    #
    # NÃO use build/packages-module-paper/libs/: esse é o jar cru do subprojeto. O
    # `collectJars` (um Sync, wired em `tasks.build`) é que o publica em build/libs/paper/,
    # e é de lá que o provisionador copia. Os dois só divergem quando alguém roda uma
    # tarefa parcial do Gradle sem o `build`; apontar o carimbo para o diretório do
    # subprojeto trocaria "descreve um artefato velho" por "descreve um artefato que
    # ninguém instalou" -- pior, porque a comparação passaria a falhar sempre.
    local jar="$SEXIDIUM_JAR_PATH"
    # Escreve-e-renomeia: `mv` no mesmo filesystem é atômico, então nenhum nó pode
    # ler um carimbo pela metade e concluir que já pode subir. Redirecionar direto
    # para $STAMP truncaria o arquivo bom no instante em que o bloco começa.
    {
        printf 'provisioned-at=%s\n' "$(date -Is)"
        printf 'nodes=%s\n' "${SX_NODES:-lobby worker-1 worker-2 worker-3}"
        # `if`, não `[[ … ]] && printf`: sendo o último comando do grupo, a forma
        # curta faz o grupo inteiro sair 1 quando o jar não existe, e o `set -e`
        # mata o init DEPOIS de um provisionamento bem-sucedido, sem carimbo.
        if [[ -s "$jar" ]]; then
            printf 'plugin-sha256=%s\n' "$(sha256sum "$jar" | cut -d' ' -f1)"
        fi
        # QUAL BUILD FOI STAGEADO, por id. O sha256 acima responde "que bytes", este
        # responde "que entrada do store" -- e é essa a pergunta que se faz durante um
        # rolling update, quando os nós estão deliberadamente em builds diferentes e o
        # carimbo descreve o ALVO, não o estado. Sem esta linha o carimbo continuaria
        # dizendo uma verdade sobre a rede inteira que deixou de existir.
        if [[ -f "$SX_STATE_DIR/builds/LATEST" ]]; then
            printf 'plugin-build=%s\n' "$(<"$SX_STATE_DIR/builds/LATEST")"
        fi
    } >"$STAMP.tmp"
    mv -f "$STAMP.tmp" "$STAMP"
    say "provisionamento concluído; carimbo em $STAMP"
}

run_proxy() {
    wait_for_stamp
    local dir="$NETWORK_DIR/proxy"
    [[ -s "$dir/velocity.jar" ]] || die "$dir/velocity.jar ausente (o init falhou?)"
    read -r -a args <<<"${PROXY_JAVA_ARGS:--Xms256M -Xmx512M}"
    cd "$dir"
    say "iniciando Velocity em ${PROXY_BIND:-0.0.0.0}:${PROXY_PORT:-25565}"
    exec java "${args[@]}" -jar velocity.jar
}

run_backend() {
    wait_for_stamp
    local dir="$NETWORK_DIR/$SX_NODE"
    local jar="$SX_SHARED_INSTALL/paper.jar"
    # Duas falhas diferentes, duas mensagens diferentes: sem o diretório do nó o
    # SX_NODE é que está errado (fora de SX_NODES); sem a árvore compartilhada o
    # errado é o provisionamento, e um "paper.jar ausente" genérico mandaria quem
    # depura procurar no lugar errado.
    [[ -d "$dir" ]] || die "$dir ausente (SX_NODE fora de SX_NODES?)"
    [[ -s "$jar" ]] || die "instalação compartilhada ausente: $jar não existe (o init provisionou?)"
    [[ -d "$SX_SHARED_PLUGINS" ]] || die "instalação compartilhada incompleta: $SX_SHARED_PLUGINS não existe"
    # Tão fatal quanto o sexidium-node.args faltando, e pelo mesmo motivo: sem este
    # diretório o `--add-extra-plugin-dir` abaixo aponta para o vazio e o nó sobe SEM O
    # PLUGIN -- de pé para o Docker, inútil para os jogadores e sem erro nenhum no log.
    # Subir assim é subir o código errado.
    [[ -d "$dir/pluginjars" ]] || die "$dir/pluginjars ausente (nó nunca pinado; rode o \`init\`)"

    verify_pinned_jar "$dir"
    # ANTES do clear_session_lock, que é a primeira coisa que varre o diretório do nó:
    # se a raiz de mundos estiver compartilhada, a resposta é não subir, não "subir e
    # avisar depois".
    verify_world_root "$dir"
    prune_old_logs "$dir"
    clear_session_lock "$dir"
    read -r -a args <<<"$(heap_args)"
    # A IDENTIDADE do nó vai na linha de comando, não no arquivo de config.
    #
    # O config.yml é IDÊNTICO nos quatro backends menos seis valores que descrevem o
    # próprio nó, e uma cópia por nó de um arquivo de 1700 linhas existindo só para
    # carregar seis números é exatamente como as cópias divergem: uma opção mudada no
    # lobby e esquecida nos workers só aparece quando o comportamento difere sob carga.
    # Com estes -D o arquivo pode ser UM só, compartilhado (e somente-leitura para o nó).
    #
    # PaperConfigurationAdapter lê `-Dsexidium.<caminho>` antes do arquivo, para
    # qualquer chave -- não há lista a registrar quando uma opção nova nasce.
    # Os valores vêm de um arquivo ESCRITO PELO PROVISIONADOR, não recalculados aqui.
    # A aritmética de portas (SX_PORT_BASE + índice, SX_API_PORT_BASE + índice*stride)
    # mora em scripts/lib/velocity.sh, que este entrypoint não carrega -- reimplementá-la
    # aqui seria uma segunda fonte de verdade para a identidade de cada nó, que é
    # precisamente o tipo de divergência que esta mudança existe para eliminar.
    local identity="$dir/sexidium-node.args"
    if [[ -s "$identity" ]]; then
        local line
        while IFS= read -r line; do
            [[ -n "$line" && "$line" != \#* ]] && args+=("$line")
        done <"$identity"
        say "identidade do nó via linha de comando ($(grep -c . "$identity") argumentos)"
    else
        # Sem o arquivo o nó subiria lendo a identidade do config COMPARTILHADO, ou seja,
        # com o id e as portas de outro nó. Duas instâncias com o mesmo node.id disputam
        # as mesmas claims de mundo, então isso não pode ser um aviso.
        die "identidade ausente: $identity não existe (reprovisione este nó)"
    fi
    cd "$dir"
    say "iniciando Paper (heap: $(heap_args)) de $jar"
    # Um paper.jar e um conjunto de jars de plugin para os 4 backends; deste
    # diretório sai só o que o servidor escreve enquanto roda. As três peças:
    #
    #   -jar <shared>/paper.jar  -- o mesmo arquivo para todos. libraries/, versions/
    #     e cache/ o paperclip resolve RELATIVOS AO CWD e não há flag que os mova:
    #     são symlinks para a árvore compartilhada, criados pelo provisionador, e o
    #     runtime só os lê (provado contra uma árvore chmod a-w).
    #   --plugins <cwd>/plugins  -- governa o DATA FOLDER de cada plugin
    #     (Bukkit.getPluginsFolder()). É por isso que o estado quente -- sexidium.db,
    #     worlds.yml, npcs.yml -- continua sendo do nó, sem compartilhar um byte.
    #   --add-extra-plugin-dir <cwd>/pluginjars -- governa de onde os JARS carregam.
    #     Provado em boot real: o plugin carrega de fora e escreve em
    #     <cwd>/plugins/<Plugin>/, deixando este plugins/ com dados e ZERO jars. Um
    #     jar aqui dentro seria carga duplicada (visto pelas duas flags).
    #     É um diretório POR NÓ com SYMLINKS: os de terceiros apontam para a árvore
    #     compartilhada (um inode, N links) e o do Sexidium aponta para o build que ESTE
    #     nó tem pinado no store. É o que torna possível "worker-3 no build novo, os
    #     outros três no antigo" -- a diferença entre dois nós é um symlink.
    #     É IRMÃO de plugins/, nunca filho: o `find plugins/ -name '*.jar' -delete` do
    #     provisionador continua correto sem exceção nenhuma.
    #
    # Sem `--port` de propósito: o Paper persiste o valor da flag de volta no
    # server.properties ANTES de checar o session.lock, então até um nó que não sobe
    # reescreveria a porta. A porta continua no server.properties de cada nó.
    exec java "${args[@]}" -jar "$jar" \
        --plugins "$dir/plugins" \
        --add-extra-plugin-dir "$dir/pluginjars" \
        nogui
}

case "$SX_NODE" in
    init) run_init ;;
    proxy) run_proxy ;;
    *) run_backend ;;
esac
