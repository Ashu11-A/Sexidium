# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/plugins.sh -- Third-party plugin installation and configuration
#   (Geyser, Multiverse, FancyNpcs/Holograms, SkinsRestorer, VeinMiner, InstantRestock,
#    CoreChestsort, CustomAnvil, FAWE, Axiom, BetterHud).
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_PLUGINS:-}" ]]; then return 0; fi
_SX_LIB_PLUGINS=1

# SkinsRestorer's config.yml is written through yaml::set, like every other config this
# repo patches -- see configure_skinsrestorer_config for why a hand-rolled awk is not an
# acceptable substitute for one that quotes its values.
sx::require yaml
# CoreChestsort vem do SpigotMC; ensure_spiget_plugin mora aqui.
sx::require spiget

# -----------------------------------------------------------------------------
# Plugin dependencies
# -----------------------------------------------------------------------------

ensure_geyser() {
    # A NETWORK has exactly one Bedrock listener and it lives on the proxy: four backends each
    # binding 19132/udp would fight over the port, and a Bedrock player reaching a backend directly
    # bypasses the proxy entirely. Skipping it here also removes the warm-up boot that only existed
    # to generate Geyser's config -- worth ~20s per instance.
    if [[ "${INSTALL_GEYSER:-1}" != "1" ]]; then
        log "Geyser not installed on this node (the proxy owns Bedrock)"
        return 0
    fi
    mkdir -p "$PLUGINS_DIR"
    need_cmd curl
    if [[ -s "$GEYSER_JAR" ]]; then
        log "Geyser already present: $GEYSER_JAR"
        return
    fi
    # Geyser has no per-Minecraft-version download: the latest build always targets the newest release, so
    # a PAPER_VERSION bump just needs the jar re-fetched (refresh_jars_on_version_change deletes it).
    log "Downloading latest Geyser-Spigot"
    download_to "$GEYSER_DOWNLOAD_URL" "$GEYSER_JAR" || die "Failed to download Geyser-Spigot"
}

ensure_plugins() {
    mkdir -p "$PLUGINS_DIR"
    need_cmd curl
    need_cmd python3

    ensure_geyser
    # Hard dependency: lobby world provisioning bridge. Abort if unavailable.
    #
    # `release`, and this is not taste. Nothing ever re-resolves a Modrinth "latest"
    # (ensure_modrinth_plugin returns early once the jar exists), so which build a
    # tree gets is decided by the DAY it was first provisioned -- and building the
    # shared install is precisely the act of provisioning a fresh tree. Without the
    # channel preference that fresh tree resolved 5.8.0-pre, a BETA, while the four
    # production nodes have been running 5.7.3 for months: the migration would have
    # swapped the one HARD dependency for a pre-release nobody asked for, invisibly.
    # Asking for a release build is what makes the new tree reproduce the old one.
    ensure_modrinth_plugin "multiverse-core" "$MULTIVERSE_JAR" "Multiverse-Core" required release
    # Optional: lobby fake-player NPCs (clickable dummies + live nameplate).
    ensure_modrinth_plugin "fancynpcs" "$FANCYNPCS_JAR" "FancyNpcs"
    ensure_modrinth_plugin "fancyholograms" "$FANCYHOLOGRAMS_JAR" "FancyHolograms"
    # Optional: SkinsRestorer powers /skin (offline/LAN safe) and feeds the chest-GUI player-head skins.
    ensure_modrinth_plugin "skinsrestorer" "$SKINSRESTORER_JAR" "SkinsRestorer"
    # Optional: VaultUnlocked is the broker other plugins read Sexidium's money THROUGH. Nothing in
    # Sexidium calls into it -- Sexidium is the provider, VaultUnlocked's own jar has no economy at
    # all -- so a node without it keeps working /pay, /balance and the sidebar balance, and only
    # shops and jobs lose sight of the balances. Its Modrinth slug is "vaultunlocked" while the
    # installed plugin names itself "Vault".
    ensure_modrinth_plugin "vaultunlocked" "$VAULTUNLOCKED_JAR" "VaultUnlocked" optional release
    # Optional: VeinMiner. Nada no Sexidium chama para dentro dele -- é um plugin de
    # jogabilidade que ouve o BlockBreakEvent -- então um nó sem ele perde a mecânica e
    # mais nada. `release` pelo mesmo motivo do Multiverse: nada re-resolve um "latest" do
    # Modrinth depois que o jar existe, então a build que uma árvore recebe é decidida pelo
    # DIA em que ela foi provisionada, e uma beta que caísse nesse dia ficaria congelada ali.
    ensure_modrinth_plugin "veinminer" "$VEINMINER_JAR" "VeinMiner" optional release
    # Optional: destrava o restock de trade de villager. Nada no Sexidium chama para dentro
    # dele; um nó sem ele volta ao restock vanilla e mais nada.
    ensure_modrinth_plugin "infinite-villager-trading" "$INSTANTRESTOCK_JAR" "InstantRestock" optional release
    # Optional, e pelo caminho do SPIGOTMC -- ver lib/spiget.sh para por que ele não pode
    # ser um ensure_modrinth_plugin. Sem resolução por versão de Minecraft: o que vem é o
    # arquivo mais recente do recurso 132579.
    ensure_spiget_plugin 132579 "$CORECHESTSORT_JAR" "CoreChestsort" optional
    # Optional: bigorna sem "Too Expensive!" e sem desgaste. Nada no Sexidium chama para
    # dentro dele -- ele ouve PrepareAnvilEvent/InventoryClickEvent -- então um nó sem ele
    # volta à bigorna vanilla e mais nada. `release` pela mesma razão dos outros: nada
    # re-resolve um "latest" depois que o jar existe, e o canal `alpha` deste projeto é uma
    # esteira de builds `dev-<sha>` que ninguém quer congelada num nó.
    ensure_modrinth_plugin "customanvil" "$CUSTOMANVIL_JAR" "CustomAnvil" optional release
    # Optional: totem automático a partir do inventário. Nada no Sexidium chama para dentro
    # dele -- ele ouve o EntityDamageEvent e cancela o dano fatal -- então um nó sem ele volta
    # ao totem vanilla (só mão/offhand) e mais nada. `release` pela mesma razão dos outros, e
    # aqui a diferença é grande: o outro canal publicado deste projeto é um `1.0-SNAPSHOT`
    # alpha que só lista Minecraft 1.20/1.20.1, ou seja, um jar que nem carregaria aqui.
    ensure_modrinth_plugin "autototem-plugin" "$AUTOTOTEM_JAR" "AutoTotem" optional release
    configure_skinsrestorer_offline_skins
    # BetterHud draws the Death Resets corner readout — the one surface Minecraft has no vanilla
    # equivalent for. Installed whenever the pinned Minecraft version is one its shader overlay actually
    # covers, and skipped (with its pack neutralised) when it is not. See betterhud_overlay_matches.
    #
    # SX_SKIP_BETTERHUD=1 opts a whole node out regardless of the pin, and it is deliberately NOT
    # INSTALL_BETTERHUD: that variable answers the opposite question ("install it even on a version the
    # overlay does not cover"), defaults to 0, and is only read on the unsupported branch below — setting
    # it to 0 on a supported pin has never skipped anything. Network workers use this to stay out of
    # BetterHud entirely: nothing on a worker draws the corner readout, and a second plugin self-hosting a
    # resource pack on a port every node also wants is cost with no benefit.
    if [[ "${SX_SKIP_BETTERHUD:-0}" == "1" ]]; then
        log "SX_SKIP_BETTERHUD=1: not installing BetterHud on this node"
    else
        adopt_manually_installed_betterhud
        if betterhud_overlay_matches; then
            ensure_modrinth_plugin "betterhud2" "$BETTERHUD_JAR" "BetterHud" optional release
        elif [[ "${INSTALL_BETTERHUD:-0}" == "1" ]]; then
            log "INSTALL_BETTERHUD=1: installing BetterHud on Minecraft $PAPER_VERSION anyway. Its resource"
            log "  pack replaces the client's core text shaders and its newest build ships no overlay for"
            log "  this version. Expect wrong text colours and missing text/buttons in vanilla screens."
            ensure_modrinth_plugin "betterhud2" "$BETTERHUD_JAR" "BetterHud" optional release
        fi
    fi
    # Runs on the skip path too, and on purpose: skipping the DOWNLOAD does nothing about a jar an earlier
    # run (or an operator) already put there, and an unneutralised one still ships its shader pack.
    neutralise_betterhud_pack_if_present
    ensure_world_editors
}

# configure_veinminer_groups <groups.json>
#
# SEMEIA os grupos de veia de um nó que ainda não tem nenhum, e NUNCA toca num arquivo que
# já existe. As duas metades importam:
#
#   semear  O plugin, sozinho, escreve UM grupo: "Ores" (#c:ores + #minecraft:pickaxes).
#           Árvore não está lá, então "instalar o VeinMiner" nunca significou derrubar
#           árvore -- é preciso dizer. `Logs` é o preset do próprio plugin (troncos +
#           cogumelo gigante); `Leaves` NÃO é: o preset de folha dele pede
#           #minecraft:hoes/shears, que não dispara com o machado que já está na mão de
#           quem está cortando. Folha não exige ferramenta correta em vanilla
#           (requiresCorrectToolForDrops = false), então needCorrectTool não a bloqueia.
#
#   nunca sobrescrever  Este arquivo é EDITÁVEL EM JOGO (`/veinminer`, permissão
#           veinminer.groups) e é estado do nó, não configuração derivada do repo.
#           Reescrevê-lo a cada provisionamento apagaria em silêncio o ajuste de quem
#           está operando o servidor -- que é exatamente o tipo de coisa que só se
#           descobre depois, sem nada no log a que voltar.
#
# Um nó JÁ NO AR não converge por aqui, e isso é de propósito: o data folder dele já tem o
# arquivo. Convergir os que estão de pé é uma ação deliberada (escrever o arquivo nos nós +
# `veinminer reload` pela API), não um efeito colateral de reprovisionar.
configure_veinminer_groups() {
    local config="$1"
    # Sem o jar não há plugin para ler isto. O caminho difere entre os dois layouts: no
    # servidor único o jar está no próprio plugins/; na rede ele mora na árvore
    # compartilhada e cada nó o alcança por um symlink em pluginjars/.
    [[ -s "$VEINMINER_JAR" || -s "${SX_SHARED_PLUGINS:-}/VeinMiner.jar" ]] || return 0
    if [[ -e "$config" ]]; then
        log "VeinMiner: $config já existe; preservado"
        return 0
    fi
    mkdir -p "$(dirname "$config")"
    log "VeinMiner: semeando grupos (Ores + Logs + Leaves) -> $config"
    cat >"$config" <<'VEINMINER_GROUPS'
[
    {
        "name": "Ores",
        "blocks": [
            "#c:ores"
        ],
        "tools": [
            "#minecraft:pickaxes"
        ],
        "override": {
            "cooldown": null,
            "mustSneak": null,
            "delay": null,
            "maxChain": null,
            "needCorrectTool": null,
            "searchRadius": null,
            "permissionRestricted": null,
            "decreaseDurability": null,
            "hungerPerBlock": null,
            "miningSpeedModifier": null,
            "separateGroupMining": null
        }
    },
    {
        "name": "Logs",
        "blocks": [
            "#minecraft:logs",
            "minecraft:mushroom_stem",
            "minecraft:brown_mushroom_block",
            "minecraft:red_mushroom_block"
        ],
        "tools": [
            "#minecraft:axes"
        ],
        "override": {
            "cooldown": null,
            "mustSneak": null,
            "delay": null,
            "maxChain": null,
            "needCorrectTool": null,
            "searchRadius": null,
            "permissionRestricted": null,
            "decreaseDurability": null,
            "hungerPerBlock": null,
            "miningSpeedModifier": null,
            "separateGroupMining": null
        }
    },
    {
        "name": "Leaves",
        "blocks": [
            "#minecraft:leaves"
        ],
        "tools": [
            "#minecraft:axes"
        ],
        "override": {
            "cooldown": null,
            "mustSneak": null,
            "delay": null,
            "maxChain": null,
            "needCorrectTool": null,
            "searchRadius": null,
            "permissionRestricted": null,
            "decreaseDurability": null,
            "hungerPerBlock": null,
            "miningSpeedModifier": null,
            "separateGroupMining": null
        }
    }
]
VEINMINER_GROUPS
}

# configure_veinminer_settings <settings.json>
#
# Semeia os ajustes GLOBAIS do VeinMiner, e existe por uma chave só: mustSneak.
#
# A hierarquia importa. Cada grupo em groups.json carrega um bloco `override` cujos campos
# são todos `null`, e `null` quer dizer "herda daqui". Então este arquivo é o único lugar
# onde "só funciona agachado" pode ser dito uma vez e valer para Ores, Logs e Leaves.
#
# O ARQUIVO É ESCRITO INTEIRO, não só a chave que nos interessa, e isso é deliberado. O
# plugin desserializa este JSON com kotlinx.serialization e, quando a leitura falha, o
# BaseConfigManager NÃO aborta: ele registra o erro e segue com a configuração PADRÃO. Um
# arquivo parcial que deixasse de parsear voltaria mustSneak para false sem nada quebrar --
# a falha apareceria como "o veinminer voltou a disparar sozinho", meses depois. Os valores
# abaixo são os defaults do próprio plugin, copiados do settings.json que ele gerou, com
# mustSneak virado.
#
# Semeia e nunca sobrescreve, pela mesma razão de configure_veinminer_groups: `/veinminer
# settings` (permissão veinminer.settings) edita este arquivo em jogo. Convergir um nó que
# já está de pé é ação deliberada -- escrever o arquivo e chamar `veinminer reload` --, não
# efeito colateral de reprovisionar.
configure_veinminer_settings() {
    local config="$1"
    [[ -s "$VEINMINER_JAR" || -s "${SX_SHARED_PLUGINS:-}/VeinMiner.jar" ]] || return 0
    if [[ -e "$config" ]]; then
        log "VeinMiner: $config já existe; preservado"
        return 0
    fi
    mkdir -p "$(dirname "$config")"
    log "VeinMiner: semeando ajustes globais (mustSneak=true) -> $config"
    cat >"$config" <<'VEINMINER_SETTINGS'
{
    "cooldown": 20,
    "mustSneak": true,
    "delay": 0,
    "maxChain": 100,
    "needCorrectTool": true,
    "searchRadius": 1,
    "permissionRestricted": false,
    "mergeItemDrops": false,
    "autoUpdate": false,
    "decreaseDurability": true,
    "hungerPerBlock": 0.0,
    "miningSpeedModifier": 0.0,
    "separateGroupMining": false,
    "dontDisplayUpdatesAndIWillNotAskForSupportBeforeUpdatingISwear": false,
    "debug": false,
    "client": {
        "allow": true,
        "require": false,
        "translucentBlockHighlight": true,
        "allBlocks": false,
        "overrides": {
            "cooldown": null,
            "mustSneak": null,
            "delay": null,
            "maxChain": null,
            "needCorrectTool": null,
            "searchRadius": null,
            "permissionRestricted": null,
            "decreaseDurability": null,
            "hungerPerBlock": null,
            "miningSpeedModifier": null,
            "separateGroupMining": null
        }
    }
}
VEINMINER_SETTINGS
}

# configure_customanvil <config.yml>
#
# Três chaves, e as três são a razão de o plugin estar instalado:
#
#   remove_repair_limit    Tira o TETO de custo. Sem ela a bigorna se recusa a concluir
#                          qualquer trabalho de 40 níveis ou mais -- o "Too Expensive!"
#                          não é só um texto, é uma parede.
#   replace_too_expensive  Tira o TEXTO. As duas são necessárias e nenhuma basta: o cliente
#                          vanilla decide desenhar "Too Expensive!" sozinho, com
#                          `custo >= 40 && !abilities.instabuild` -- um teste que o servidor
#                          não alcança por setMaximumRepairCost. O plugin contorna mandando
#                          um ClientboundPlayerAbilitiesPacket com instabuild=true enquanto
#                          essa tela está aberta, e é SÓ isso que apaga o texto. Ele repõe
#                          o valor verdadeiro assim que o custo cai abaixo de 40.
#   anvil_degradation      Chance de a bigorna se danificar a cada uso. Zero = ela nunca
#                          lasca, nunca quebra, nunca precisa ser reposta.
#
# ESCRITO COMO `0`, INTEIRO, e não como `0.0`. yamlkv só deixa sem aspas o que casa com
# bool/int/null (ver scalar() em lib/py/yamlkv.py); `0.0` sairia como a STRING '0.0', e
# FileConfiguration.getDouble devolve o DEFAULT quando o valor não é um Number -- ou seja,
# a bigorna continuaria se desgastando a 0.12 e o arquivo diria que não. Um `0` sem aspas
# chega como Integer e getDouble o converte.
#
# CONVERGE A CADA PROVISIONAMENTO, ao contrário do VeinMiner ao lado, e a diferença é o que
# cada arquivo É. O settings.json do VeinMiner é inteiro do operador; aqui são três chaves
# entre duzentas linhas que continuam sendo dele. Quem mudar estas três pelo `/ca` (permissão
# ca.config.edit) as vê voltarem no próximo provision -- é o mesmo trato do
# configure_skinsrestorer_config, e é o preço de a política morar no repo.
configure_customanvil() {
    local config="$1" jar="$CUSTOMANVIL_JAR"
    [[ -s "$jar" ]] || jar="${SX_SHARED_PLUGINS:-}/CustomAnvil.jar"
    [[ -s "$jar" ]] || return 0
    mkdir -p "$(dirname "$config")"
    # O plugin só escreve o config.yml no primeiro enable, e yaml::set exige um arquivo que
    # já exista -- sem esta semente as três chaves só entrariam em vigor no SEGUNDO restart
    # depois da instalação. Extrair do próprio jar (em vez de versionar uma cópia aqui) é o
    # que mantém as outras ~200 linhas iguais às da versão que está instalada; é o mesmo
    # caminho de sexidium::extract_default_config, e por python3, que yaml::set já exige --
    # `unzip` não existe garantidamente na imagem de provisionamento.
    if [[ ! -f "$config" ]]; then
        need_cmd python3
        if python3 - "$jar" "$config" <<'PY_EXTRACT'
import shutil, sys, zipfile

with zipfile.ZipFile(sys.argv[1]) as jar, open(sys.argv[2], "wb") as out:
    with jar.open("config.yml") as src:
        shutil.copyfileobj(src, out)
PY_EXTRACT
        then
            log "CustomAnvil: config.yml padrão extraído do jar -> $config"
        else
            rm -f "$config"
            log "CustomAnvil: não consegui extrair o config.yml de $jar; o plugin escreve o dele"
            log "  no primeiro boot e as chaves entram no provisionamento seguinte"
            return 0
        fi
    fi
    yaml::set "$config" \
        remove_repair_limit true \
        replace_too_expensive true \
        anvil_degradation 0
}

# configure_autototem <config.yml>
#
# As quatro chaves do plugin, escritas por inteiro. Não há aqui a divisão "semeia e nunca
# toca" do VeinMiner: nenhuma delas é editável em jogo -- `/autototem enabled` e
# `/autototem cooldowntime` escrevem o estado POR JOGADOR, num arquivo à parte, e o único
# comando que mexe neste config.yml é o `setdefaultcooldown` (autototem.admin). Então este
# arquivo é política do repo, e converge a cada provisionamento como o do CustomAnvil.
#
# `pvp-enabled: true` é uma DECISÃO, não o default herdado, e é a única das quatro que muda
# o resultado de uma partida: com ela ligada o totem também dispara em dano letal causado
# por outro jogador, o que inclui o minigame Combat/KitPvP, cujo vencedor é justamente o
# último sobrevivente. Quem quiser o inverso -- totem automático só contra queda, lava, mob
# e void -- troca por `false` aqui, e não em cada nó.
#
# `default-cooldown-ticks: 0` é o piso do servidor, não um teto: o plugin recusa um
# `/autototem cooldowntime` ABAIXO do padrão (cooldown-below-default), então 0 é o que deixa
# cada jogador escolher o próprio cooldown para cima. Subir este número tira essa escolha.
#
# `default-locale` só entra em cena para um cliente cujo idioma não tem .lang na pasta; os
# que têm resolvem sozinhos. Ver sync_autototem_lang para o pt-BR.
configure_autototem() {
    local config="$1" jar="$AUTOTOTEM_JAR"
    [[ -s "$jar" ]] || jar="${SX_SHARED_PLUGINS:-}/AutoTotem.jar"
    [[ -s "$jar" ]] || return 0
    mkdir -p "$(dirname "$config")"
    # Mesmo motivo do configure_customanvil: o plugin só escreve o config.yml no primeiro
    # enable, e yaml::set exige um arquivo que já exista -- sem esta semente as chaves só
    # entrariam em vigor no SEGUNDO restart depois da instalação. Extrair do próprio jar é o
    # que mantém os comentários do arquivo iguais aos da versão instalada.
    if [[ ! -f "$config" ]]; then
        need_cmd python3
        if python3 - "$jar" "$config" <<'PY_EXTRACT'
import shutil, sys, zipfile

with zipfile.ZipFile(sys.argv[1]) as jar, open(sys.argv[2], "wb") as out:
    with jar.open("config.yml") as src:
        shutil.copyfileobj(src, out)
PY_EXTRACT
        then
            log "AutoTotem: config.yml padrão extraído do jar -> $config"
        else
            rm -f "$config"
            log "AutoTotem: não consegui extrair o config.yml de $jar; o plugin escreve o dele"
            log "  no primeiro boot e as chaves entram no provisionamento seguinte"
            return 0
        fi
    fi
    yaml::set "$config" \
        default-enabled true \
        default-cooldown-ticks 0 \
        pvp-enabled true \
        default-locale en-US
    sync_autototem_lang "$(dirname "$config")/lang"
}

# sync_autototem_lang <plugins/AutoTotem/lang>
#
# Repo -> nó, e SEM a regra "só se for mais novo" do sync_schematics, porque os dois arquivos
# não são a mesma coisa. Um .schem pode ser reescrito em jogo por `//schem save` e aí o disco
# é a versão boa; um .lang não tem caminho de escrita nenhum dentro do servidor, então a
# versão do repo é a única que existe e sobrescrever é o comportamento correto -- é assim que
# uma correção de tradução chega aos quatro nós no provisionamento seguinte.
#
# Os .lang do PRÓPRIO plugin (en-US, zh-CN) não passam por aqui: ele os escreve sozinho no
# enable, via saveResource, e o glob abaixo só alcança o que está em assets/lang/autototem/.
sync_autototem_lang() {
    local dest="$1"
    [[ -d "${AUTOTOTEM_LANG_SRC:-}" ]] || return 0

    local copied=0 file
    shopt -s nullglob
    for file in "$AUTOTOTEM_LANG_SRC"/*.lang; do
        mkdir -p "$dest"
        cp "$file" "$dest/$(basename "$file")"
        copied=$((copied + 1))
    done
    shopt -u nullglob

    ((copied > 0)) && log "AutoTotem: $copied arquivo(s) de idioma sincronizado(s) -> $dest"
    return 0
}

# The two map-editing plugins. Optional on purpose: a failed download costs a warning, never the boot —
# nothing in Sexidium calls into either of them, they are here for the person building the maps.
ensure_world_editors() {
    mkdir -p "$PLUGINS_DIR"
    if [[ "$INSTALL_WORLDEDIT" == "1" ]]; then
        # FAWE ships WorldEdit itself, so installing both would be two plugins claiming the same commands.
        ensure_modrinth_plugin "fastasyncworldedit" "$FAWE_JAR" "FastAsyncWorldEdit"
    else
        log "INSTALL_WORLDEDIT=0: skipping FastAsyncWorldEdit"
    fi
    if [[ "$INSTALL_AXIOM" == "1" ]]; then
        ensure_modrinth_plugin "axiom-paper-plugin" "$AXIOM_JAR" "Axiom"
    else
        log "INSTALL_AXIOM=0: skipping Axiom"
    fi
    sync_schematics
}

# Repo -> server, never the reverse. A build saved in-game with `//schem save` lands under the gitignored
# test/paper/, so copy the ones worth keeping back to assets/schematics/ by hand; this direction only
# republishes what is already tracked.
#
# Newer-source-only: re-running the script must not clobber a schematic that was re-saved in-game under a
# name the repo also uses. FAWE creates this folder itself on first use, but that is after the first boot,
# and the point of the sync is that the builds are there BEFORE anyone types //schem load.
sync_schematics() {
    [[ "$INSTALL_WORLDEDIT" == "1" ]] || return 0
    [[ -d "$SCHEMATICS_SRC" ]] || return 0

    local copied=0 skipped=0 file dest
    shopt -s nullglob
    for file in "$SCHEMATICS_SRC"/*.schem "$SCHEMATICS_SRC"/*.schematic; do
        dest="$FAWE_SCHEMATICS_DIR/$(basename "$file")"
        if [[ -s "$dest" && ! "$file" -nt "$dest" ]]; then
            skipped=$((skipped + 1))
            continue
        fi
        mkdir -p "$FAWE_SCHEMATICS_DIR"
        cp "$file" "$dest"
        copied=$((copied + 1))
    done
    shopt -u nullglob

    if ((copied > 0)); then
        log "Synced $copied schematic(s) from $SCHEMATICS_SRC -> $FAWE_SCHEMATICS_DIR (//schem list)"
    elif ((skipped > 0)); then
        log "Schematics already current in $FAWE_SCHEMATICS_DIR ($skipped file(s))"
    fi
}

# Renames a hand-downloaded BetterHud jar to the name this script tracks.
#
# BetterHud was opt-in here for a while, so reaching for Modrinth directly is the obvious move — and it
# lands as `BetterHud-bukkit-<version>.jar`, while every existence check in this script is against
# `plugins/BetterHud.jar`. The failure is not "the script ignores it": the script sees no BetterHud,
# downloads its own copy, and Paper then finds TWO jars declaring the plugin name BetterHud and refuses to
# load either. Renaming is the whole fix, and it is safe because the file it would collide with is by
# definition not there.
#
# Only ever one file, and never over an existing BetterHud.jar — a directory with several is an operator
# mid-upgrade, and picking one for them is not this script's call.
adopt_manually_installed_betterhud() {
    local found=() candidate
    [[ ! -s "$BETTERHUD_JAR" ]] || return 0
    shopt -s nullglob
    for candidate in "$PLUGINS_DIR"/BetterHud*.jar; do
        # The glob matches the canonical name too, and it reaches here only when that file is EMPTY (a
        # truncated download). Renaming it onto itself is not an adoption; leave it for the downloader.
        [[ "$candidate" != "$BETTERHUD_JAR" ]] || continue
        found+=("$candidate")
    done
    shopt -u nullglob
    if [[ "${#found[@]}" -gt 1 ]]; then
        log "Several BetterHud jars in $PLUGINS_DIR; leaving them alone. Paper will refuse to load a"
        log "  duplicate plugin name — keep one and delete the rest."
        return 0
    fi
    [[ "${#found[@]}" -eq 1 ]] || return 0
    mv "${found[0]}" "$BETTERHUD_JAR"
    log "Adopted hand-installed $(basename "${found[0]}") as $(basename "$BETTERHUD_JAR") so this script"
    log "  stops treating BetterHud as missing (and stops downloading a second copy beside it)."
}

# Whether BetterHud's newest build ships a shader overlay matching $PAPER_VERSION's resource-pack format.
#
# BetterHud replaces the client's core TEXT shaders — that is how it positions glyphs anywhere on screen —
# and chooses which set to send from a hardcoded pack-format table in its PackOverlay enum. The newest
# published build (2.1.0-SNAPSHOT-447) ends at `betterhud_26_1`, declared for formats 84..99, and what it
# actually contains is 26.1's shaders: `rendertype_text.vsh/.fsh`, lightmap sampled unconditionally.
#
# 26.1.2 is format 84 and matches that exactly. 26.2 is format 88 but renamed the core text shaders to
# `text.*` and split them into IS_GUI / IS_SEE_THROUGH variants where no lightmap is bound — so BetterHud's
# copy is served, overrides the live shader, and corrupts every vanilla GUI screen (F62). The declared
# range says it is covered; the file contents say otherwise, which is why this is a version allowlist and
# not a range check against 84..99.
#
# Add a version here only after diffing the overlay against that version's vanilla shaders — the technique
# is in docs/reference/known-issues.md. Guessing from the declared range is exactly what produces F62.
betterhud_overlay_matches() {
    case "$PAPER_VERSION" in
        26.1 | 26.1.*) return 0 ;;
        *) return 1 ;;
    esac
}

# Stops an ALREADY-INSTALLED BetterHud from shipping its shader pack, and clears the layouts it is still
# holding for players from before the gate went up.
#
# Turning Sexidium's betterhud.enabled off stops SEXIDIUM using BetterHud; it does not stop BetterHud
# sending its own resource pack to every Java client, which is the thing that actually corrupts GUI
# rendering. `pack-type: none` is BetterHud's own documented way to build no pack at all (its config
# comment reads `#folder, zip, none`), so the client keeps vanilla shaders.
#
# That leaves BetterHud with no font on the client, and it does NOT leave it with nothing to draw — which
# is what the earlier version of this comment assumed. BetterHud persists each player's worn layouts to
# .users/<uuid>.yml and re-applies them on join, so anyone who played while betterhud.enabled was true
# keeps wearing sexidium_deathresets forever, now rendered as ~30 unknown-character boxes in a bare boss
# bar across the top of their screen. The plugin removes its own layouts on the disabled path now
# (BetterHudClaims#purgeOwned), so this is belt-and-braces for a server that has not been rebuilt yet —
# and it is done with the server stopped, which is the only time those files can be deleted at all
# (BetterHud rewrites them on shutdown).
#
# Only touched when INSTALL_BETTERHUD is not set: an operator who deliberately opted in keeps their pack.
neutralise_betterhud_pack_if_present() {
    local better_hud_dir="$PLUGINS_DIR/BetterHud"
    local config="$better_hud_dir/config.yml"
    # The pack is the POINT on a version whose shaders it matches — neutralising it there would leave the
    # overlay drawing with a font the client never receives, which is the unknown-character-box symptom.
    ! betterhud_overlay_matches || return 0
    [[ "${INSTALL_BETTERHUD:-0}" != "1" ]] || return 0
    [[ -f "$config" ]] || return 0
    clear_betterhud_stale_claims "$better_hud_dir"
    if grep -qE "^pack-type:[[:space:]]*none" "$config"; then return 0; fi
    log "Neutralising BetterHud's resource pack (pack-type: none): it replaces the client's core text"
    log "  shaders and has no overlay for Minecraft $PAPER_VERSION, which corrupts vanilla GUI text."
    log "  Set INSTALL_BETTERHUD=1 to keep BetterHud's pack as-is."
    sed -i -E "s|^pack-type:[[:space:]]*.*|pack-type: none|" "$config"
    sed -i -E "s|^enable-self-host:[[:space:]]*.*|enable-self-host: false|" "$config"
}

# Drops per-player BetterHud state that still lists a sexidium_* layout. Only those files are touched: a
# .users entry with the operator's own huds in it is theirs, and BetterHud recreates any file it needs.
clear_betterhud_stale_claims() {
    local users_dir="$1/.users"
    local file cleared=0
    [[ -d "$users_dir" ]] || return 0
    for file in "$users_dir"/*.yml; do
        [[ -f "$file" ]] || continue
        grep -q "sexidium_" "$file" || continue
        rm -f "$file"
        cleared=$((cleared + 1))
    done
    [[ "$cleared" -gt 0 ]] || return 0
    log "Cleared $cleared BetterHud player record(s) still wearing a Sexidium layout: with the pack"
    log "  neutralised those render as unknown-character boxes in a boss bar."
}

# SkinsRestorer ships ready for offline/LAN play, but make the skin source explicit so /skin resolves a
# username to a real Mojang skin even though the server runs online-mode=false. Only rewrites keys that
# already exist in the generated config, so a SkinsRestorer version bump never gets a stale key injected.
# configure_skinsrestorer_offline_skins [seed]
#
# `seed` is forwarded, and which callers pass it is the whole point. A NODE must seed: it is
# the first thing to touch its own plugins/SkinsRestorer/ and, unseeded, its first boot writes
# `type: FILE` while the proxy is already on MYSQL -- the disagreement checks.py:53 reports and
# the one that makes proxy mode share no skins. The shared-install tree must NOT: nothing boots
# a plugin there, so a file seeded there would be one nobody ever reads or completes.
# shellcheck disable=SC2120  # `seed` is passed from lib/paper.sh, which shellcheck does not follow
configure_skinsrestorer_offline_skins() {
    configure_skinsrestorer_config "$PLUGINS_DIR/SkinsRestorer/config.yml" "backend" "${1:-}"
}

# Applies the offline-skin keys AND the shared storage to one SkinsRestorer config.yml.
#
# Called for the backends' shared tree and, separately, for the proxy — with the same values, which is
# not tidiness but the plugin's own requirement: behind a proxy it runs in PROXY MODE, announces
# "you have to put the same config.yml on all servers and on the proxy", and refuses to share skins
# when they disagree.
#
# WHY THE DATABASE IS NOT OPTIONAL HERE. In proxy mode the skin lives in `database`, not in a file: the
# proxy is what builds the player's GameProfile (the `textures` property IS the skin) and re-sends it on
# every server switch, so a backend that stored the skin in its own FILE has it overwritten the moment
# the player is routed anywhere. Shipped `type: FILE` with `proxyMode.api: true`, the plugin logs
# "Proxy mode API is enabled, but database storage is not set up" and `/skin set` silently does nothing
# — which is exactly the report this fixes. Left on FILE when no database is configured, because a
# single-node LAN server has no proxy and no such problem.
# Removes the orphan keys an EARLIER provisioner left inside `database:`, and is a no-op on a healthy
# file. This is not hypothetical tidying: it is the repair for a config that four live nodes were
# already carrying, and without it every later provision re-blesses the corruption instead of fixing it.
#
# WHAT WENT WRONG. A previous version of the injection below used yaml::set/yamlkv.py, which assumes the
# 2-space indentation every Sexidium config uses while SkinsRestorer indents with 4. Rather than fail it
# APPENDED the keys at its own indentation, gluing a second, 2-space `type/host/port/database/username/
# password` run onto the end of the plugin's real 4-space block. YAML reads a shallower line as the end
# of the current mapping and the start of a new one, so SkinsRestorer died at startup with
#
#   ConfigMeException: YAML error ... expected <block end>, but found '<block mapping start>'
#
# and because that throw is inside SRPlugin.loadConfig() the plugin aborted BEFORE registering its
# commands -- `/skin` was registered by nobody, on the proxy and on all three backends at once.
#
# THE RULE. Inside the top-level `database:` block, the first non-comment child line defines the block's
# own indentation STRING. Every later key line must begin with that exact string: equal is a sibling,
# longer is a legitimately nested value and both are kept, while anything else is an orphan and is
# dropped. Comparing the string rather than its LENGTH is what makes this work on a file indented with
# tabs -- a length test scores one tab as 1, so a 2-space orphan under a tab-indented block is "deeper"
# and survives. Nothing well-formed is ever removed: a line that neither matches nor extends its block's
# indentation is exactly the shape YAML cannot parse.
skinsrestorer::heal_orphan_database_keys() {
    local config="$1" role="$2" dropped
    # The count comes back through a file rather than from `wc -l` on either side, because a config
    # whose last line has no newline makes wc undercount by one -- the repair would then run, change
    # the file, and report nothing.
    awk -v report="$config.dropped" '
        # Also matches `database : x` and `database: # comment`; the original anchor demanded a bare
        # `database:` and silently did nothing on either.
        /^database[[:space:]]*:[[:space:]]*($|#)/ { inblock = 1; base = ""; print; next }
        # A key at column zero closes the block. A comment does not: SkinsRestorer banners its
        # next section with #-lines that sit above that key.
        inblock && /^[^[:space:]#]/ { inblock = 0 }
        # Blanks and comments carry no indentation contract, so they neither set nor break the base.
        inblock && /^[[:space:]]*($|#)/ { print; next }
        inblock {
            match($0, /^[[:space:]]*/)
            indent = substr($0, 1, RLENGTH)
            if (base == "") { base = indent }
            else if (index(indent, base) != 1) { dropped++; next }
        }
        { print }
        END { printf "%d\n", dropped + 0 >report }
    ' "$config" >"$config.tmp"
    dropped="$(cat "$config.dropped")"
    rm -f "$config.dropped"
    skinsrestorer::replace "$config.tmp" "$config"
    [[ "$dropped" -gt 0 ]] || return 0
    log "Repaired SkinsRestorer ($role) config.yml: dropped $dropped orphan line(s) from the"
    log "  database block (an older provisioner appended them at the wrong indentation and the plugin"
    log "  refused to start, which is why /skin did nothing)"
}

# mv, but the destination keeps the permissions it had. Rewriting these files by
# tmp-file-and-rename is what makes the edit atomic; without this the mode is reset to
# whatever the provisioner's umask says on EVERY provision, quietly widening a file that
# holds a database password.
skinsrestorer::replace() {
    local tmp="$1" dest="$2"
    if [[ -f "$dest" ]]; then
        chmod --reference="$dest" "$tmp" 2>/dev/null || true
        chown --reference="$dest" "$tmp" 2>/dev/null || true
    fi
    mv "$tmp" "$dest"
}

# Writes just enough config.yml for the plugin's FIRST boot to already be correct: one line, the
# `database:` header, which the injection below then fills in.
#
# The two-pass trap this closes: configure_skinsrestorer_config can only patch a file the plugin has
# written, and the plugin only writes one once it has booted -- so provision #1 left this side on
# `type: FILE` while whichever side had already booted went to MYSQL, and that disagreement is exactly
# the state where SkinsRestorer logs "Proxy mode API is enabled, but database storage is not set up" and
# `/skin set` silently does nothing. It is also what checks.py:53 greps for. Every caller that can be
# first must therefore seed: the proxy AND each backend node, or the trap is merely inverted.
#
# A header and nothing else, deliberately. ConfigMe fills in every property the file omits and rewrites
# the document complete on first load, so a partial file is a supported input; writing values here as
# well would mean a second place that has to quote a password correctly, and one such place is already
# one more than this repo can afford.
#
# WHAT MAKES THE FOUR FILES IDENTICAL, since proxy mode requires it: not a copy of one onto the others
# -- there is nothing to copy from on a fresh network, and any "template" would be dead code. It is that
# all four get the same one-line seed, the same six values written by the same yamlkv call, and the same
# defaults filled in by the same jar; and that every later provision re-applies exactly those six.
skinsrestorer::seed_config() {
    local config="$1" role="$2"
    mkdir -p "$(dirname "$config")"
    printf 'database:\n' >"$config"
    log "Seeded SkinsRestorer ($role) with a database block; the plugin fills in the rest on its first"
    log "  boot. Without this the first provision left this side on FILE storage while the other side"
    log "  went to MYSQL, and proxy mode shares no skins at all when the two disagree."
}

# configure_skinsrestorer_config <config.yml> [role] [seed]
#
# A literal `seed` as the third argument opts this call into creating the file when the plugin has not
# generated one yet. Callers that can be the FIRST to touch a given data directory must pass it; the
# shared-install tree does not, because nothing ever boots a plugin there.
configure_skinsrestorer_config() {
    local config="$1"
    local role="${2:-backend}"
    local seed="${3:-}"
    local kind="MYSQL"
    [[ "${SEXIDIUM_DB_TYPE:-}" == "postgres" ]] && kind="POSTGRESQL"
    local shared=0
    [[ "${SEXIDIUM_DB_TYPE:-}" == "mysql" || "${SEXIDIUM_DB_TYPE:-}" == "postgres" ]] && shared=1
    # No config yet: the plugin has never booted, so it has not written one. Without a shared database
    # there is nothing worth seeding (a single-node LAN server has no proxy and no disagreement to
    # avoid), so that case still just says so out loud rather than failing silently.
    if [[ ! -f "$config" ]]; then
        if [[ "$seed" != "seed" || "$shared" -eq 0 ]]; then
            log "SkinsRestorer ($role) has not generated its config yet ($config); it is configured on the next provision"
            return 0
        fi
        sx_trace "seed skinsrestorer-config $role"
        skinsrestorer::seed_config "$config" "$role"
    fi
    skinsrestorer::heal_orphan_database_keys "$config" "$role"
    log "Configuring SkinsRestorer ($role) for offline-mode skin lookups"
    awk '
        /^[[:space:]]*NoSkinIfLoginCanceled:/ { sub(/:.*/, ": false"); print; next }
        /^[[:space:]]*disableOnJoinSkins:/    { sub(/:.*/, ": false"); print; next }
        /^[[:space:]]*defaultSkins-enabled:/  { sub(/:.*/, ": false"); print; next }
        { print }
    ' "$config" >"$config.tmp"
    skinsrestorer::replace "$config.tmp" "$config"

    # Shared storage, so proxy and backends read and write the same skins.
    [[ "$shared" -eq 1 ]] || return 0
    log "Pointing SkinsRestorer ($role) at the shared $kind database (proxy mode needs shared storage)"
    # yaml::set --step 4, e NÃO um awk à mão. Foi um awk, e ele carregava as duas falhas que o
    # arquivo de verdade não perdoa:
    #
    #   1. VALOR NÃO CITADO, INTERPOLADO POR sub(). `&` numa string de substituição do awk significa
    #      "o texto casado", e `awk -v` ainda processa escapes na atribuição. A senha vem do operador
    #      (docker/stack.sexidium.yml), então `a&b` virava `password: a: ''b`, `a: b #c` passava
    #      inteiro e uma barra invertida era comida antes de chegar ao sub(). YAML impossível de ler
    #      -- a MESMA queda do /skin, por outro caminho, e um que o reparo acima não sabe consertar
    #      porque não é falha de indentação. `scalar()` do yamlkv cita e escapa; usar o mesmo escritor
    #      que todos os outros config.yml deste repo é o conserto.
    #   2. SEM ÂNCORA DE PROFUNDIDADE. `^[[:space:]]+host:` casa `host:` em QUALQUER nível dentro de
    #      `database:`, então um `host:` legítimo aninhado sob `connectionOptions:` era reescrito com
    #      o host do banco. O reparo acima preserva esse aninhamento de propósito; sem a âncora, a
    #      passada seguinte o destruía. `--step 4` casa a profundidade exata e só ela.
    #
    # --step 4 porque é assim que o SkinsRestorer indenta. No padrão (2) o yamlkv não acha nada e
    # INSERE as chaves na profundidade dele: foi exatamente essa a origem do bloco órfão.
    yaml::set --step 4 "$config" \
        database.type "$kind" \
        database.host "${SEXIDIUM_DB_HOST:-127.0.0.1}" \
        database.port "${SEXIDIUM_DB_PORT:-3306}" \
        database.database "${SEXIDIUM_DB_NAME:-sexidium}" \
        database.username "${SEXIDIUM_DB_USER:-sexidium}" \
        database.password "${SEXIDIUM_DB_PASSWORD:-}"
}

# Two changes to BetterHud's own config, both of which it ships wrong for our purposes.
#
# 1. `default-hud: [test_hud]` — BetterHud's bundled DEMO hud (the health/hunger bars in its
#    default/huds/default-hud.yml), shown to every player automatically on join. Sexidium adds its own
#    hud per player when a Death Resets experience starts, so the demo is pure noise on top of the
#    vanilla HUD it duplicates. Emptied.
#
# 2. `enable-self-host: false` — BetterHud renders its HUDs as boss-bar text in a CUSTOM FONT that lives
#    in the resource pack it generates at startup. With no way to deliver that pack, the client has no
#    glyphs for those codepoints and draws the "unknown character" box for every one of them: a boss bar
#    full of garbage. Self-hosting turns the built pack into something the server can actually send on
#    join. This is the fix for "boss bar with unknown characters" — the pack is not optional polish, it
#    is what makes the text legible at all.
#
# Only rewrites keys the generated config already has, so a BetterHud version bump never gets a stale
# key injected. Bedrock is untouched: `disable-to-bedrock-player` stays true (Geyser cannot render this),
# and Sexidium falls back to the scoreboard sidebar for those players by itself.
#
# On `self-host-ip`, which is the subtle part. BetterHud binds its pack server with
# `InetAddress.getLocalHost()` and uses `self-host-ip` ONLY to build the URL it advertises to clients —
# the two are never checked against each other. Set it to the LAN IP on a machine whose hostname maps to
# 127.0.1.1 (the Debian/Ubuntu default) and every client is handed a URL pointing at an address nothing
# is listening on: the download fails silently and you are back to a boss bar of unknown characters, with
# the config looking correct. So we advertise whatever the JVM will actually bind — see java_local_host.
# configure_betterhud_if_present [<public-host>] [<port>]
#
# With no arguments this keeps the standalone behaviour: advertise whatever the JVM binds.
# With them -- the network case -- it advertises the PUBLIC address a player can actually
# reach and pins the listen port to match, because BetterHud builds its URL from the port it
# listens on. Inside a compose network the self-discovered address is a 172.x container IP
# that no client can reach, which is why the whole plugin was skipped on the network until
# there was a published port and a public host to name.
configure_betterhud_if_present() {
    local public_host="${1:-}" public_port="${2:-}"
    local config="$PLUGINS_DIR/BetterHud/config.yml"
    [[ -f "$config" ]] || return 0
    need_cmd python3
    local ip lan
    ip="$(java_local_host || true)"
    lan="$(host_ipv4 || true)"
    if [[ -n "$public_host" ]]; then
        ip="$public_host"
        log "Configuring BetterHud: pack advertised at http://$public_host:${public_port:-8163}"
    else
        log "Configuring BetterHud: no demo hud, no boss-bar merging, self-hosted pack on ${ip:-*}:8163"
    fi
    if [[ -n "$ip" && "$ip" == 127.* && -n "$lan" && "$ip" != "$lan" ]]; then
        log "  NOTE: '$(hostname)' resolves to $ip (loopback), so BetterHud's pack server is reachable"
        log "  only from this machine. A Java client running here is fine; one on another device on the"
        log "  LAN will fail to download the pack and see unknown characters in the HUD. To fix, point"
        log "  the hostname at the LAN IP:  sudo sed -i 's/^127.0.1.1\\t$(hostname)/$lan\\t$(hostname)/' /etc/hosts"
    fi
    IP="$ip" PORT="$public_port" python3 - "$config" <<'PY'
import os, re, sys

path = sys.argv[1]
ip = os.environ.get("IP", "")
port = os.environ.get("PORT", "")
lines = open(path, encoding="utf-8").read().splitlines()
out, skipping = [], False

for line in lines:
    # `default-hud:` is a block list, so drop its "- entry" continuation lines too.
    if skipping:
        if re.match(r"^\s*-\s", line):
            continue
        skipping = False
    if re.match(r"^default-hud:\s*$", line):
        out.append("default-hud: []")
        skipping = True
        continue
    if re.match(r"^default-hud:\s*\[.*\]\s*$", line):
        out.append("default-hud: []")
        continue
    if re.match(r"^enable-self-host:\s*", line):
        out.append("enable-self-host: true")
        continue
    # BetterHud draws every hud inside a boss bar, and its shader converts back to screen space by
    # subtracting a constant baked into the pack for ONE assumed bar line. With merging on it does not use
    # its own reserved bar at all — it rewrites whichever boss-bar packet is passing to carry the payload.
    # An Ender Dragon (or one of Sexidium's own countdown bars) therefore becomes the carrier, on a
    # different line and with a non-empty title, and every readout slides off its anchor. The plugin
    # asserts this too, on every boot; setting it here means the very first connect is already right.
    if re.match(r"^merge-boss-bar:\s*", line):
        out.append("merge-boss-bar: false")
        continue
    # '*' binds every interface; pinning the LAN IP is what makes the URL reachable from other machines.
    # Must match the JVM's getLocalHost(), which is what BetterHud actually binds to.
    if ip and re.match(r"^self-host-ip:\s*", line):
        out.append(f"self-host-ip: '{ip}'")
        continue
    # The listen port and the advertised port are the SAME number by construction: BetterHud
    # composes http://<self-host-ip>:<this port>, so publishing 26011->8163 would advertise
    # 8163 and the download would fail silently -- the unknown-character boss bar again.
    if port and re.match(r"^self-host-port:\s*", line):
        out.append(f"self-host-port: {port}")
        continue
    out.append(line)

open(path, "w", encoding="utf-8").write("\n".join(out) + "\n")
PY
}

configure_geyser_if_present() {
    [[ -f "$GEYSER_CONFIG" ]] || return 0
    local server_port
    server_port="$(prop_get "$SERVER_DIR/server.properties" server-port 25565)"

    log "Configuring Geyser: Bedrock UDP ${GEYSER_PORT:-19132} -> Java TCP $server_port"
    awk -v bedrock_port="${GEYSER_PORT:-19132}" -v java_port="$server_port" '
        BEGIN { section = "" }
        /^[^[:space:]#][^:]*:/ { section = $1; sub(":", "", section) }
        section == "bedrock" && /^[[:space:]]*address:/ { print "  address: 0.0.0.0"; next }
        section == "bedrock" && /^[[:space:]]*port:/    { print "  port: " bedrock_port; next }
        section == "java"    && /^[[:space:]]*auth-type:/ { print "  auth-type: offline"; next }
        section == "remote"  && /^[[:space:]]*address:/ { print "  address: 127.0.0.1"; next }
        section == "remote"  && /^[[:space:]]*port:/    { print "  port: " java_port; next }
        section == "remote"  && /^[[:space:]]*auth-type:/ { print "  auth-type: offline"; next }
        { print }
    ' "$GEYSER_CONFIG" >"$GEYSER_CONFIG.tmp"
    mv "$GEYSER_CONFIG.tmp" "$GEYSER_CONFIG"
}
