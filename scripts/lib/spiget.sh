# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/spiget.sh -- SpigotMC resource resolution and plugin installation, via spiget.
#
# O par de lib/modrinth.sh para o outro repositório de plugins. Existe porque o
# SpigotMC não tem API de download: a página de um recurso é servida atrás do
# Cloudflare e um `curl` nela devolve HTML, não jar. O spiget.org é o espelho
# somente-leitura que a comunidade mantém para exatamente isso.
#
# Sourced by scripts/init-*.sh via lib/core.sh's sx::require. Libraries never set
# shell options and never install traps: they inherit `set -Eeuo pipefail` from
# the entrypoint and register cleanup through sx::on_exit.
# -----------------------------------------------------------------------------

# The `if` form, NOT `[[ ... ]] && return 0`: the latter returns 1 when the guard
# is false, and as the last command of a sourced file that makes `source` return
# 1, which under `set -e` aborts the caller.
if [[ -n "${_SX_LIB_SPIGET:-}" ]]; then return 0; fi
_SX_LIB_SPIGET=1

# spiget::resource_state <resource-id>  ->  "<external:true|false> <version-name>"
#
# Uma consulta, dois fatos, e os dois decidem se vale sequer tentar o download.
#
# `external` é o que separa "o spiget serve o arquivo" de "o spiget só sabe o link":
# um recurso marcado external mora no site do autor, e /download responde um REDIRECT
# para lá -- que, seguido, traz a landing page em HTML. Sem esta checagem o HTML seria
# gravado com o nome do jar, e a falha só apareceria muito depois, no boot do Paper,
# como "invalid plugin.jar" num arquivo que nem zip é.
spiget::resource_state() {
    local id="$1"
    api_get "$SPIGET_API/resources/$id" 2>/dev/null |
        python3 -c '
import json, sys

try:
    data = json.load(sys.stdin)
except (ValueError, TypeError):
    raise SystemExit(0)
# O spiget devolve `external` ausente em recursos antigos; a ausência significa
# hospedado por ele, que é o caso que funciona -- daí o default False.
print("true" if data.get("external") else "false", data.get("name", "?"))
' 2>/dev/null
}

# ensure_spiget_plugin <resource-id> <dest> <label> [required|optional]
#
# Baixa um plugin do SpigotMC. Quarto argumento "required" aborta em falha; qualquer
# outra coisa trata como opcional (avisa e segue).
#
# DUAS DIFERENÇAS DE FUNDO para ensure_modrinth_plugin, e nenhuma é de estilo:
#
#   1. NÃO HÁ resolução por versão de Minecraft. O SpigotMC não modela isso -- o campo
#      `testedVersions` é texto que o AUTOR digita, não metadado de build -- então não
#      existe o degradê "esta versão -> qualquer versão" que o lib/modrinth.sh faz. O
#      que se baixa é sempre o arquivo mais recente do recurso, e conferir que ele
#      serve a versão pinada é trabalho de quem instala, na hora de instalar.
#
#   2. O RESULTADO É VERIFICADO como zip. Este é o modo de falha real deste caminho
#      (ver spiget::resource_state): o /download responde 200 com HTML quando não pode
#      servir o arquivo, e um 200 é indistinguível de sucesso para o download_to. Um
#      jar que não abre é apagado AQUI, onde a mensagem ainda aponta para a causa.
#
# Congela como o irmão do Modrinth: um `[[ -s "$dest" ]]` no topo, então a build que uma
# árvore recebe é decidida pelo dia em que ela foi provisionada. SX_REFRESH_PLUGINS=1 é
# o que reabre a pergunta.
ensure_spiget_plugin() {
    local id="$1" dest="$2" label="$3" requirement="${4:-optional}"

    if [[ -s "$dest" ]]; then
        log "$label already present: $dest"
        return 0
    fi

    local state external version
    state="$(spiget::resource_state "$id")"
    read -r external version <<<"$state" || true

    if [[ -z "${external:-}" ]]; then
        if [[ "$requirement" == "required" ]]; then
            die "Could not reach spiget for $label (resource $id)"
        fi
        log "Could not reach spiget for $label (resource $id); staying disabled"
        return 0
    fi
    if [[ "$external" == "true" ]]; then
        # Não é um erro transitório: é uma propriedade do recurso, e nenhuma tentativa
        # a mais muda. Quem quiser este plugin tem de baixá-lo à mão.
        if [[ "$requirement" == "required" ]]; then
            die "$label (spiget $id) is an EXTERNAL resource; spiget cannot serve its jar"
        fi
        log "$label (spiget $id) is hosted off-site; spiget cannot serve it. Install it by hand."
        return 0
    fi

    log "Downloading $label $version (SpigotMC resource $id) via spiget"
    if ! download_to "$SPIGET_API/resources/$id/download" "$dest"; then
        if [[ "$requirement" == "required" ]]; then
            die "Failed to download $label (hard dependency)"
        fi
        log "Failed to download $label; staying disabled"
        rm -f "$dest" 2>/dev/null || true
        return 0
    fi
    if ! spiget::looks_like_jar "$dest"; then
        rm -f "$dest"
        if [[ "$requirement" == "required" ]]; then
            die "$label downloaded from spiget is not a jar (Cloudflare page?); refusing to install it"
        fi
        log "$label downloaded from spiget is not a jar (Cloudflare page?); discarded"
    fi
}

# Um jar é um zip, e um zip começa com "PK\x03\x04". Checar a ASSINATURA em vez de
# rodar `unzip -t` porque a imagem de provisionamento não tem unzip garantido, e
# porque o que se quer distinguir aqui -- zip de página HTML -- os dois primeiros
# bytes já resolvem.
spiget::looks_like_jar() {
    local file="$1" magic
    # Um stub de dry-run nunca é o artefato real; checá-lo abortaria a simulação.
    sx_dry && return 0
    [[ -s "$file" ]] || return 1
    magic="$(head -c 2 "$file" 2>/dev/null || true)"
    [[ "$magic" == "PK" ]]
}
