"""Configuração do CLI remoto: endereços, nomes e segredos.

Duas fontes, nesta ordem: o ambiente (exportado por scripts/remote.sh a partir de
scripts/remote.env) e, para o que descreve a TOPOLOGIA, a própria stack em execução.
A segunda parte é o ponto importante: a lista de nós, as portas da API e o nome do
container do banco são LIDOS do `Config.Env` dos containers, nunca escritos aqui.
A topologia já mudou uma vez (o container `netns` que era dono da rede foi removido,
o MariaDB legado vai virar um serviço do stack) e um CLI que hardcode nomes ou a base
de portas 8800 passa a mentir silenciosamente no dia da mudança.
"""

import json
import os
import secrets as pysecrets
import stat
import sys
import urllib.parse

from util import PreconditionError, parse_env_list

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))

# Protocolo do handshake usado no server list ping. É só o número que o cliente
# anuncia; o servidor responde o status independentemente de casar ou não, e o
# `status` compara o nome da versão devolvida com o que a stack deveria estar rodando.
MC_PROTOCOL = 773

DEFAULT_NODES = "lobby worker-1 worker-2 worker-3"

# Segredos do stack, com o tamanho em bytes de cada um. Gerados uma vez, guardados
# em scripts/remote.secrets.json (0600, gitignored) e injetados no stack como env.
SECRET_KEYS = (
    "db_password",
    "api_token",
    "forwarding_secret",
    "db_root_password",
    # URL do webhook de alertas. Entra AQUI, e não num arquivo de config qualquer, por
    # uma razão mecânica: Settings.secret_values() alimenta util.redact com tudo que
    # está nesta tupla, então a URL (que é uma credencial -- quem a tem posta no canal)
    # não pode vazar num corpo de erro do Portainer, que ecoa o env inteiro.
    "alert_webhook",
)
SECRET_BYTES = {
    "db_password": 16,
    "api_token": 24,
    "forwarding_secret": 32,
    "db_root_password": 16,
}


class Settings:
    """Tudo o que vem de fora do processo. Nenhum segredo é impresso a partir daqui."""

    def __init__(self, env=None):
        env = env or os.environ
        self.url = (env.get("SX_PORTAINER_URL") or "").rstrip("/")
        self.key = env.get("SX_PORTAINER_KEY") or ""
        self.endpoint = env.get("SX_PORTAINER_ENDPOINT") or "1"
        self.stack = env.get("SX_STACK_NAME") or "sexidium"
        self.prefix = env.get("SX_CONTAINER_PREFIX") or "sexidium-"
        self.image = env.get("SX_IMAGE") or "eclipse-temurin:25-jdk"
        self.volume = env.get("SX_DATA_VOLUME") or "sexidium-data"
        # O volume de BUILD: repo, caches de Gradle, artefatos baixados e relatórios
        # de teste. Separado da fonte desde que `sexidium-data` passou a conter só
        # `server/` -- o que se atualiza não é o que se acumula.
        self.build_volume = env.get("SX_BUILD_VOLUME") or "sexidium-build"
        self.compose = env.get("SX_COMPOSE_FILE") or os.path.join(
            REPO_ROOT, "docker", "stack.sexidium.yml"
        )
        self.secrets_file = env.get("SX_SECRETS_FILE") or os.path.join(
            REPO_ROOT, "scripts", "remote.secrets.json"
        )
        # O banco tem dois nomes possíveis durante a migração planejada (o container
        # legado `SexidiumDB` e o serviço `sexidiumdb` do stack). Candidatos, não
        # constante: o `status` e o `db-init` acham qualquer um dos dois.
        explicit = env.get("SX_DB_CONTAINER")
        self.db_candidates = (
            [explicit] if explicit else ["sexidiumdb", "SexidiumDB", "sexidium-database"]
        )
        # Host que os jogadores digitam. Por padrão é o mesmo host do Portainer --
        # verdade nesta instalação (um único servidor) e sobrescritível quando não for.
        self.mc_host = env.get("SX_MC_HOST") or (
            urllib.parse.urlsplit(self.url).hostname if self.url else ""
        )
        self.mc_port = int(env.get("SX_MC_PORT") or 25565)
        self.timeout = int(env.get("SX_TIMEOUT") or 900)

    def require(self):
        if not self.url:
            raise PreconditionError("SX_PORTAINER_URL não definida (veja scripts/remote.env.example)")
        if not self.key:
            raise PreconditionError("SX_PORTAINER_KEY não definida (veja scripts/remote.env.example)")

    # --- segredos do stack ----------------------------------------------------
    # Gerados uma vez e reusados para sempre. O forwarding secret em especial é o
    # que autentica os backends: trocá-lo sem reprovisionar derruba TODOS os joins
    # com "This server requires you to connect with Velocity".

    def secrets(self, create=True):
        path = self.secrets_file
        values = {}
        if os.path.exists(path):
            mode = stat.S_IMODE(os.stat(path).st_mode)
            if mode & 0o077:
                print(
                    f"aviso: {path} está {mode:04o}; use chmod 600 (contém a senha do banco)",
                    file=sys.stderr,
                )
            with open(path, encoding="utf-8") as handle:
                values = json.load(handle)
        elif not create:
            raise PreconditionError(f"{path} não existe (rode: scripts/remote.sh secrets)")
        # Só COMPLETA o que falta. Um valor existente nunca é regenerado aqui: o
        # forwarding secret já provisionado é o que os backends conferem no login,
        # e trocá-lo sem reprovisionar recusa todo join da rede.
        # Só as chaves com tamanho declarado são GERÁVEIS. As outras (a URL do webhook)
        # são fornecidas por quem opera ou simplesmente não existem -- inventar uma
        # daria uma string aleatória onde se espera uma URL, e o alerta falharia em
        # silêncio na primeira vez que fosse necessário.
        missing = [key for key in SECRET_KEYS if not values.get(key) and key in SECRET_BYTES]
        if missing and not create:
            raise PreconditionError(f"segredos ausentes em {path}: {', '.join(missing)}")
        for key in missing:
            values[key] = pysecrets.token_hex(SECRET_BYTES[key])
        if missing:
            self.write_secrets(values)
        return values

    def write_secrets(self, values):
        path = self.secrets_file
        # 0600 ANTES de escrever: criar com o umask e corrigir depois deixa uma
        # janela em que a senha do banco é legível por qualquer usuário da máquina.
        handle = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            json.dump(values, out, indent=2)
            out.write("\n")
        os.chmod(path, 0o600)

    def secret_values(self):
        """Valores a esconder de qualquer saída (util.redact)."""
        out = [self.key]
        if os.path.exists(self.secrets_file):
            out.extend(str(v) for v in self.secrets(create=False).values())
        return [v for v in out if v]


class Topology:
    """A forma da rede, lida da stack em execução (com defaults para um deploy zero)."""

    def __init__(self, nodes, env, containers):
        self.nodes = nodes
        self.env = env
        self.containers = containers  # nome -> dict do /containers/json

    @property
    def api_base(self):
        return int(self.env.get("SX_API_PORT_BASE") or 8800)

    @property
    def api_stride(self):
        return int(self.env.get("SX_API_PORT_STRIDE") or 10)

    @property
    def port_base(self):
        return int(self.env.get("SX_PORT_BASE") or 25566)

    @property
    def network_dir(self):
        """A raiz do estado por nó -- <network_dir>/<nó> é o CWD daquele servidor."""
        return self.env.get("NETWORK_DIR") or "/srv/nodes"

    @property
    def state_dir(self):
        """Onde mora o carimbo .provisioned.

        Lido do env pelo mesmo motivo que o resto: ele MUDOU de lugar. Enquanto o
        estado de cada nó vivia no volume compartilhado o carimbo podia morar em
        NETWORK_DIR, mas com um volume por nó aquele caminho deixou de ser visível
        entre containers, e o carimbo foi para a pasta-fonte. Um default hardcoded
        aqui faria o `status` reportar "carimbo ausente" numa rede saudável.
        """
        return self.env.get("SX_STATE_DIR") or self.env.get("SX_SHARED_INSTALL") or "/srv/sexidium/server"

    @property
    def shared_install(self):
        return self.env.get("SX_SHARED_INSTALL") or "/srv/sexidium/server"

    @property
    def proxy_port(self):
        return int(self.env.get("PROXY_PORT") or 25565)


def discover(client, settings):
    """Descobre a topologia sem supor nome de container nenhum além do prefixo.

    Ordem de preferência para o `Config.Env`: um nó rodando (é a verdade do que está
    no ar), depois o `init` (mesmo Exited, guarda o env do último provisionamento).
    Se não houver container algum -- deploy do zero -- caem os defaults.
    """
    listed = client.docker_json("GET", "/containers/json?all=1")
    containers = {}
    for entry in listed or []:
        for raw in entry.get("Names", []):
            containers[raw.lstrip("/")] = entry

    ordered = [n for n in containers if n.startswith(settings.prefix)]
    ordered.sort(key=lambda n: (0 if containers[n].get("State") == "running" else 1, n))

    env = {}
    for name in ordered:
        info = client.docker_json("GET", f"/containers/{name}/json")
        candidate = parse_env_list((info or {}).get("Config", {}).get("Env"))
        if candidate.get("SX_NODES"):
            env = candidate
            break

    nodes = (env.get("SX_NODES") or DEFAULT_NODES).split()
    return Topology(nodes, env, containers)
