#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# remote.sh -- operar a rede Sexidium no host do Portainer, por HTTP.
#
#   scripts/remote.sh up             zero → rede no ar
#   scripts/remote.sh update         sync + provision + restart (o do dia a dia)
#   scripts/remote.sh status         a validação da rede (13 checagens)
#   scripts/remote.sh test [suíte]   roda as suítes DENTRO do host
#   scripts/remote.sh logs <nó> -f   log de qualquer nó
#   scripts/remote.sh --help         a lista inteira
#
# Par simétrico de scripts/net.sh: aquele opera processos numa máquina local,
# este opera containers no Portainer. Mesmo vocabulário (up/update/restart/down/
# status/logs) de propósito.
#
# Este arquivo é só o carregador de ambiente. A lógica está em scripts/remote/,
# em Python de stdlib pura -- HTTP, tar e JSON em bash seriam um exercício de
# escrever mal o que a stdlib já faz certo.
#
# Documentação: docs/operations/deployment.md
# -----------------------------------------------------------------------------

SX_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SX_REMOTE_ENV="${SX_REMOTE_ENV:-$SX_SCRIPT_DIR/remote.env}"

remote::die() {
    printf 'remote.sh: %s\n' "$*" >&2
    exit 5
}

# Parser de KEY=VALUE. NÃO é `source`, e a diferença é de segurança, não de estilo:
# `source` num arquivo de credenciais executa qualquer `$(...)` que ele contenha,
# no shell que segura a API key do daemon Docker. Aqui uma linha só pode virar uma
# variável, e valor nenhum é interpretado.
remote::parse_env_file() {
    local file="$1" line key value
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line#"${line%%[![:space:]]*}"}" # trim à esquerda
        [[ -z "$line" || "$line" == '#'* ]] && continue
        [[ "$line" == *=* ]] || continue
        key="${line%%=*}"
        value="${line#*=}"
        [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
        # Aspas envolventes são convenção de arquivo .env, não parte do valor.
        if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then
            value="${value:1:${#value}-2}"
        fi
        printf '%s=%s\n' "$key" "$value"
    done <"$file"
}

# Precedência: AMBIENTE > arquivo. Permite
# `SX_PORTAINER_KEY=… scripts/remote.sh status` sem nenhum arquivo no disco (CI,
# ou uma chave de uso único que não se quer persistir).
remote::load_env() {
    local file="$1" pair key
    [[ -f "$file" ]] || return 0
    local mode
    mode="$(stat -c '%a' "$file" 2>/dev/null || echo 600)"
    [[ "$mode" == "600" || "$mode" == "400" ]] ||
        printf 'remote.sh: aviso: %s está %s; use chmod 600 (contém a API key)\n' "$file" "$mode" >&2
    while IFS= read -r pair; do
        key="${pair%%=*}"
        [[ -n "${!key:-}" ]] && continue
        export "${key?}=${pair#*=}"
    done < <(remote::parse_env_file "$file")
}

remote::main() {
    command -v python3 >/dev/null 2>&1 || remote::die "python3 não encontrado no PATH"
    remote::load_env "$SX_REMOTE_ENV"
    if [[ -z "${SX_PORTAINER_KEY:-}" ]]; then
        remote::die "SX_PORTAINER_KEY não definida.
  Crie $SX_REMOTE_ENV a partir de scripts/remote.env.example (chmod 600) ou
  exporte a variável. A chave sai de: Portainer → My account → Access tokens."
    fi
    # `exec`: o Python vira o processo, então Ctrl-C e o exit code chegam
    # inteiros a quem chamou (o exit code é a interface do CLI).
    exec python3 "$SX_SCRIPT_DIR/remote/cli.py" "$@"
}

# Guarda de sourcing: scripts/test/unit/remote.bats carrega este arquivo para
# testar o parser sem disparar o CLI.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    remote::main "$@"
fi
