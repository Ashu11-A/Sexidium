"""Estado DURÁVEL do pipeline: o container auxiliar, o lock e o diário.

Tudo vive no volume `sexidium-data`, nunca na máquina de quem operou. A razão é
concreta: um `update` interrompido no meio (Ctrl-C, notebook fechado, sessão SSH
caída) tem de poder ser RETOMADO -- por outra pessoa, de outra máquina, ou pelo
autoscaler rodando de um cron. Um diário no laptop de alguém não é estado de
operação, é um rascunho.

    /srv/sexidium/server/pipeline/
      lock                  run-id, host, pid, started-at, heartbeat-at, state
      runs/<run-id>.jsonl   diário append-only, um objeto JSON por linha
      runs/current          o id do run ativo
      retired.json          nós estacionados, disponíveis para reuso
      metrics.jsonl         amostras do watch/autoscaler

O auxiliar é UM container por execução, e existe porque um pin tem de poder ser
virado para um nó cujo container está PARADO ou em crash-loop -- que é exatamente o
caso do rollback. `docker exec` no próprio nó não serve para isso.
"""

import json
import time

from util import PreconditionError, RemoteError, journal_line, replay

HELPER = "sexidium-pipeline"
PIPELINE_DIR = "pipeline"
# Um lock cujo heartbeat parou há mais que isto é ESTANCADO: quem o segurava morreu.
# 5 min é folgado de propósito -- um estágio legítimo (BUILD com Gradle frio) leva
# minutos, e declarar estancado um run vivo é pior que esperar.
STALE_AFTER = 300
HEARTBEAT_EVERY = 30


def shell_quote(text):
    """Aspas simples POSIX. O diário passa por `sh -lc`, então isto é a fronteira."""
    return "'" + str(text).replace("'", "'\\''") + "'"


class Helper:
    """O container auxiliar do run, com os binds DERIVADOS da topologia.

    Gerado, nunca escrito à mão: a lista de volumes do `init` no compose é estática e
    já divergiu uma vez (um nó novo sem o mount correspondente faz o provisionador
    escrever num diretório dentro do próprio container). Aqui a lista sai de SX_NODES,
    então ela não tem como ficar para trás.

    Entra na rede `sexidium` também, para poder pingar um backend pelo nome e falar
    HTTP com a API de cada nó.
    """

    def __init__(self, client, settings, topology, extra_nodes=()):
        self.client = client
        self.settings = settings
        self.topology = topology
        self.extra_nodes = list(extra_nodes)
        self.name = HELPER

    def spec(self):
        binds = [
            f"{self.settings.volume}:/srv/sexidium",
            f"{self.settings.build_volume}:/srv/build",
        ]
        for node in ["proxy"] + list(self.topology.nodes) + self.extra_nodes:
            binds.append(f"{self.settings.prefix}{node}:/srv/nodes/{node}")
        return {
            "Image": self.settings.image,
            "Entrypoint": ["sleep", "infinity"],
            "HostConfig": {"Binds": binds, "AutoRemove": False},
            "NetworkingConfig": {"EndpointsConfig": {"sexidium": {}}},
        }

    def __enter__(self):
        self.client.create_and_start(self.name, self.spec())
        return self

    def __exit__(self, *_):
        # O `finally` não é decorativo: um auxiliar deixado para trás segura o nome e a
        # execução seguinte morre com "name already in use".
        self.client.remove(self.name)
        return False

    def sh(self, script, timeout=None):
        """BASH, não `sh`. A razão é o motivo de este container existir.

        Todo o caminho de controle do pipeline (drain, undrain, /node, selftest) fala
        HTTP por `exec 3<>/dev/tcp/host/porta`, e `/dev/tcp` é recurso do BASH: não é um
        arquivo, é uma sintaxe que o próprio bash intercepta. Sob o `/bin/sh` do Debian
        (dash) ele não existe, e a tentativa vira `cannot create /dev/tcp/...: Directory
        nonexistent` -- que `node_http` traduz para "sem resposta", que o pipeline lê
        como "o nó não drenou". Foi assim que um `pipeline deploy` esperou os 300 s
        inteiros por um dreno que nunca chegou a ser pedido.

        A imagem do auxiliar é `settings.image` (temurin, Debian), que tem bash.
        """
        return self.client.sh(self.name, script, timeout=timeout, shell="bash")

    def must_sh(self, script, what, timeout=None):
        code, out = self.sh(script, timeout=timeout)
        if code != 0:
            raise RemoteError(f"{what} falhou (exit {code}): {out.strip()[:400]}")
        return out

    def read(self, path):
        _, out = self.sh(f"cat {shell_quote(path)} 2>/dev/null || true")
        return out

    def write(self, path, text):
        """Escreve-e-renomeia, sempre. Um leitor vê o arquivo inteiro ou o anterior."""
        self.must_sh(
            "mkdir -p \"$(dirname %s)\" && printf '%%s' %s > %s.tmp && mv -f %s.tmp %s"
            % (shell_quote(path), shell_quote(text), shell_quote(path), shell_quote(path), shell_quote(path)),
            f"escrever {path}",
        )

    def append(self, path, line):
        self.must_sh(
            "mkdir -p \"$(dirname %s)\" && printf '%%s\\n' %s >> %s"
            % (shell_quote(path), shell_quote(line), shell_quote(path)),
            f"acrescentar a {path}",
        )


class Lock:
    """Uma execução por vez, com heartbeat.

    `set -C` + redirecionamento é O_EXCL: o create falha se o arquivo existe, e essa
    é a exclusão mútua inteira -- sem daemon, sem serviço de coordenação, e legível
    por qualquer um com `cat`.

    Cobre também `stack`, `provision`, `update`, `restart` e `down`, e isso não é
    excesso de zelo: sem o lock, um `remote.sh update` rodado à mão no meio de um deploy
    do autoscaler reprovisiona e reinicia nós que o pipeline acabou de drenar e pinar --
    e um `stack` à mão PUTa o compose com prune:true, que RECRIA esses containers.
    """

    # Quantas vezes ESTE processo já pegou o lock. Não é reentrância do arquivo -- é o
    # reconhecimento de que `update` e o autoscaler já o seguram quando chamam
    # `provision`/`stack`, e um segundo acquire vindo do mesmo run é o MESMO run, não uma
    # corrida. Sem isto, decorar `cmd_provision` faria o `update` falhar contra si mesmo.
    _depth = 0

    def __init__(self, helper, state_dir, run_id, actor="pipeline"):
        self.helper = helper
        self.path = f"{state_dir}/{PIPELINE_DIR}/lock"
        self.run_id = run_id
        self.actor = actor

    @classmethod
    def held_here(cls):
        return cls._depth > 0

    def contents(self, state="running"):
        return json.dumps(
            {
                "run": self.run_id,
                "actor": self.actor,
                "state": state,
                "started-at": int(time.time()),
                "heartbeat-at": int(time.time()),
            },
            sort_keys=True,
        )

    def read(self):
        text = self.helper.read(self.path).strip()
        if not text:
            return None
        try:
            return json.loads(text)
        except ValueError:
            return {"run": "?", "state": "unparseable", "heartbeat-at": 0}

    def acquire(self, force=False):
        held = self.read()
        if held and not force:
            age = int(time.time()) - int(held.get("heartbeat-at") or 0)
            if held.get("state") == "needs-human":
                raise PreconditionError(
                    f"o lock está em needs-human (run {held.get('run')}): um rollback falhou e "
                    "a rede foi deixada num estado que pede olho humano. Leia `pipeline status`, "
                    "resolva, e libere com `pipeline abort --force-unlock --yes`."
                )
            if age < STALE_AFTER:
                raise PreconditionError(
                    f"outro run está em curso (run {held.get('run')}, heartbeat há {age}s). "
                    "Use `pipeline status`; se ele morreu, `pipeline resume` o adota."
                )
            raise PreconditionError(
                f"lock ESTANCADO do run {held.get('run')} (heartbeat há {age}s). "
                "`pipeline resume` adota o run; `pipeline abort --yes` o desfaz."
            )
        # noclobber: quem perde a corrida falha no create, não sobrescreve.
        code, out = self.helper.sh(
            "mkdir -p \"$(dirname %s)\" && set -C && printf '%%s' %s > %s"
            % (shell_quote(self.path), shell_quote(self.contents()), shell_quote(self.path))
        )
        if code != 0 and not force:
            raise PreconditionError(f"não consegui pegar o lock: {out.strip()[:200]}")
        if code != 0:
            self.helper.write(self.path, self.contents())
        Lock._depth += 1
        return True

    def beat(self, state="running"):
        self.helper.write(self.path, self.contents(state))

    def mark(self, state):
        self.helper.write(self.path, self.contents(state))

    def release(self, force=False):
        """Solta o lock -- MENOS quando ele está em `needs-human`.

        `needs-human` é escrito por um rollback que FALHOU e existe para recusar todo run
        novo até alguém olhar. Só que quem o escreve levanta a exceção logo depois, e o
        `finally` do run chamava este release: o estado se apagava no caminho de saída de
        quem acabou de criá-lo. As três promessas do docstring lá em cima caíam juntas --
        o autoscaler pegava o lock no poll seguinte, a guarda de `resume` nunca podia
        disparar, e a instrução do próprio alerta (`abort --force-unlock`) morria em "não
        há lock para liberar", porque não havia mesmo.

        `force=True` é só do `abort`: é lá que o humano diz que olhou.
        """
        if not force:
            held = self.read()
            if held and held.get("state") == "needs-human":
                return False
        Lock._depth = max(0, Lock._depth - 1)
        self.helper.sh(f"rm -f {shell_quote(self.path)}")
        return True


class Journal:
    """Diário append-only de UM run, mais a regra de retomada.

    Uma linha por evento e nada de reescrita: uma linha final truncada é detectável
    (não é JSON válido) e portanto descartável. É a diferença entre "o run foi
    interrompido" e "o estado do run é desconhecido".
    """

    def __init__(self, helper, state_dir, run_id):
        self.helper = helper
        self.run_id = run_id
        self.dir = f"{state_dir}/{PIPELINE_DIR}/runs"
        self.path = f"{self.dir}/{run_id}.jsonl"

    def record(self, stage, event, node=None, sub=None, detail=None):
        line = journal_line(self.run_id, stage, event, node=node, sub=sub, detail=detail)
        self.helper.append(self.path, line)
        return line

    def text(self):
        return self.helper.read(self.path)

    def state(self):
        return replay(self.text())

    def set_current(self):
        self.helper.write(f"{self.dir}/current", self.run_id)

    @staticmethod
    def current(helper, state_dir):
        return helper.read(f"{state_dir}/{PIPELINE_DIR}/runs/current").strip() or None
