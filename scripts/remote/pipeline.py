"""`pipeline` -- a atualização ROLANTE: stage, drena um nó, vira o pin, reinicia, verifica.

O que este arquivo acrescenta ao `update` que já existia não é velocidade, é a
possibilidade de PARAR NO MEIO sem estragar nada. `ops.cmd_update` reprovisiona e
reinicia os cinco nós em ordem; se o terceiro não voltar, os dois primeiros já estão
no código novo, os dois últimos no velho, e não há nem registro disso nem caminho de
volta. Aqui cada nó atravessa o mesmo ciclo -- anuncia, drena, pina, reinicia,
verifica, devolve -- e cada sub-etapa é journalada e re-entrante.

Três propriedades, e vale saber de qual cada linha de código está cuidando:

  RETOMÁVEL   o diário é append-only e cada sub-etapa é idempotente, então "retomar"
              é sempre "rode aquela sub-etapa de novo, do começo".
  REVERSÍVEL  o pin guarda `previous=`, e o rollback é a MESMA chamada de renomeação
              na outra direção. Não existe restaurar-de-backup.
  SEM QUEDA   nenhum jogador é desconectado: o nó é drenado ANTES de o pin virar, e o
              pipeline se RECUSA a rolar um lobby quando não há um segundo lobby vivo
              para receber quem está nele.

Os dois lugares onde "sem queda" tem exceção honesta são o lobby único e o proxy
único, e os dois são recusas explícitas com uma flag para o operador assumir, nunca um
aviso que o run atropela.
"""

import contextlib
import json
import re
import time

import config as configmod
from alerts import Alerts, CRITICAL, ERROR, INFO, WARN
from journal import Helper, HEARTBEAT_EVERY, Journal, Lock, STALE_AFTER, shell_quote
from util import (
    PreconditionError,
    player_counts,
    RemoteError,
    api_port,
    classify_rollback,
    game_port,
    listening_ports,
    restart_order,
    started_epoch,
    summarize,
    trace,
)

PASS, FAIL, SKIP = "PASS", "FAIL", "SKIP"

# As linhas de log que valem como PROVA, exatamente como o JVM as emite. `Done (`
# sozinho não serve e a razão está registrada: o Paper o imprime mesmo quando o plugin
# lançou durante o enable e foi desabilitado -- visto ao vivo com o BetterHud.
READY_LINE = "SX-READY"
DRAIN_LINE = "SX-DRAIN phase="
RECLAIM_LINE = "SX-RECLAIM"
SELFTEST_LINE = "SX-SELFTEST"
RECONCILER_LINE = "World placements are consistent with this node's disk"
API_LINE = "HTTP API listening on http://"


# --- HTTP para a API de um nó -------------------------------------------------
# Do container auxiliar, por /dev/tcp do bash: a imagem temurin não tem curl, e o
# auxiliar está na rede `sexidium`, então o nome do nó resolve por DNS. É isto que a
# flag -Dsexidium.api.bind=0.0.0.0 (escrita pelo provisionador) torna possível --
# sem ela a API só escuta em 127.0.0.1 dentro do namespace do próprio nó e o
# orquestrador não tem caminho de controle nenhum.


def node_http(helper, host, port, method, path, token, body=""):
    """-> (status:int|None, corpo:str). Nunca levanta: o chamador decide.

    A requisição é montada com CR/LF REAIS e passa como ARGUMENTO de `printf '%s'`,
    nunca como formato. Um `%` num comando (um motivo de broadcast, o nome de um build)
    seria interpretado pelo printf e sairia corrompido -- e o token viaja aqui dentro,
    então uma requisição corrompida é uma requisição sem autenticação.
    """
    request = (
        f"{method} {path} HTTP/1.0\r\n"
        f"Host: {host}\r\n"
        # X-Sexidium-Token, não Bearer: é o header que o ApiServer compara (em tempo
        # constante) e introduzir um segundo esquema seria inventar uma divergência.
        f"X-Sexidium-Token: {token}\r\n"
        # text/plain porque é o que o corpo É: /command recebe a linha de comando crua, e
        # os GETs não têm corpo nenhum. O `application/json` que ficava aqui descrevia o
        # envelope que o cliente mandava por engano -- e um header que mente sobre o corpo
        # é o que faz o próximo leitor (e o dublê de teste) acreditar no formato errado.
        f"Content-Type: text/plain; charset=utf-8\r\n"
        f"Content-Length: {len(body)}\r\n"
        "Connection: close\r\n\r\n"
    ) + body
    script = (
        f"exec 3<>/dev/tcp/{host}/{port} 2>/dev/null || exit 7; "
        f"printf '%s' {shell_quote(request)} >&3; cat <&3"
    )
    code, out = helper.sh(script)
    if code != 0 or not out:
        return None, out or ""
    first = out.splitlines()[0] if out.splitlines() else ""
    status = None
    if first.startswith("HTTP/1."):
        try:
            status = int(first.split()[1])
        except (IndexError, ValueError):
            status = None
    # O separador cabeçalho/corpo é procurado com TOLERÂNCIA A CR REPETIDO, e não com um
    # partition literal, porque o exec do Docker é criado com Tty=True: o TTY traduz cada
    # \n da saída em \r\n, então o \r\n que o servidor mandou chega aqui como \r\r\n. Um
    # `partition("\r\n\r\n")` não casa com isso e devolvia corpo VAZIO em toda resposta --
    # com status 200, o que é o pior formato possível de falhar: `node_json` virava None e
    # o verify pós-restart lia "o nó não sabe dizer em que build está".
    match = re.search(r"\r?\r\n\r?\r\n", out)
    payload = out[match.end():] if match else ""
    if not payload:
        _, _, payload = out.partition("\n\n")
    return status, payload.strip()


def node_json(helper, host, port, path, token):
    status, payload = node_http(helper, host, port, "GET", path, token)
    if status != 200:
        return None
    try:
        return json.loads(payload)
    except ValueError:
        return None


def node_command(helper, host, port, token, command):
    """POST /command. Dispara e não espera: a resposta é `{"ok":true}` e o resultado
    real vai para o console. Comandos são para AÇÕES; o estado se lê do banco.

    O corpo é o comando CRU, não um JSON. `ApiServer.handleCommand` lê o corpo inteiro
    como a linha de comando (`new String(readAll(...)).trim()`) -- não há parse de JSON
    do outro lado. Mandar `{"command": "..."}` fazia o servidor despachar essa chave e
    chaves como se fossem o comando; com a allowlist vazia isso nem era recusado, só
    virava um comando desconhecido e nada acontecia. Combinado com o `sh` sem /dev/tcp
    (ver Helper.sh), era o segundo motivo de o dreno nunca sair do lugar.
    """
    trace(f"command {host} {command}")
    return node_http(helper, host, port, "POST", "/command", token, body=command)


# --- consultas ao banco -------------------------------------------------------


class Registry:
    """`network_nodes` e `world_placements`, lidos pelo caminho que o `status` já usa.

    A idade do heartbeat é calculada PELO BANCO (UNIX_TIMESTAMP()*1000 - heartbeat_at):
    `heartbeat_at` é epoch em milissegundos e comparar com o relógio da máquina do
    operador transformaria qualquer skew de horário num falso alarme -- e aqui um falso
    alarme é um rollback.
    """

    def __init__(self, client, settings):
        self.client = client
        self.settings = settings
        self._name = None
        self._creds = None

    def _connect(self):
        if self._name:
            return True
        from ops import db_container

        try:
            self._name = db_container(self.client, self.settings)
        except PreconditionError:
            return False
        info = self.client.inspect(self._name) or {}
        env = dict(e.partition("=")[::2] for e in info.get("Config", {}).get("Env", []))
        root = env.get("MARIADB_ROOT_PASSWORD") or env.get("MYSQL_ROOT_PASSWORD")
        if not root:
            self._name = None
            return False
        self._creds = [f"MYSQL_PWD={root}"]
        return True

    def query(self, sql):
        """-> lista de listas de campos, ou None quando o banco é inalcançável."""
        if not self._connect():
            return None
        code, out = self.client.exec_run(
            self._name, ["mariadb", "-uroot", "sexidium", "-N", "-e", sql], env=self._creds
        )
        if code != 0:
            return None
        from util import parse_row

        return [row for row in (parse_row(line) for line in out.splitlines()) if row]

    def node(self, node_id):
        """Uma linha de network_nodes como dict, tolerante a colunas que não existem.

        As colunas de telemetria (tps/mspt/heap_used_mb/heap_max_mb) são novas e podem
        não existir ainda. Um SELECT que as nomeia falha inteiro numa base antiga, então
        a consulta cai para o subconjunto garantido -- degradar para SKIP, nunca FAIL.
        """
        full = (
            "SELECT node_id, role, state, players, worlds, plugin_version, max_players, "
            "tps, mspt, heap_used_mb, heap_max_mb, UNIX_TIMESTAMP()*1000 - heartbeat_at "
            f"FROM network_nodes WHERE node_id = '{node_id}';"
        )
        rows = self.query(full)
        keys = [
            "node_id", "role", "state", "players", "worlds", "plugin_version",
            "max_players", "tps", "mspt", "heap_used_mb", "heap_max_mb", "age",
        ]
        if rows is None:
            rows = self.query(
                "SELECT node_id, role, state, players, worlds, plugin_version, "
                "UNIX_TIMESTAMP()*1000 - heartbeat_at "
                f"FROM network_nodes WHERE node_id = '{node_id}';"
            )
            keys = ["node_id", "role", "state", "players", "worlds", "plugin_version", "age"]
        if not rows:
            return None
        return dict(zip(keys, rows[0]))

    def all_nodes(self):
        rows = self.query(
            "SELECT node_id, role, state, players, worlds, "
            "UNIX_TIMESTAMP()*1000 - heartbeat_at FROM network_nodes;"
        )
        keys = ["node_id", "role", "state", "players", "worlds", "age"]
        return [dict(zip(keys, row)) for row in (rows or []) if len(row) >= 6]

    def loaded_placements(self, node_id):
        rows = self.query(
            "SELECT COUNT(*) FROM world_placements "
            f"WHERE node_id = '{node_id}' AND state = 'LOADED';"
        )
        if not rows:
            return None
        try:
            return int(rows[0][0])
        except (IndexError, ValueError):
            return None

    def execute(self, sql):
        """Um comando que ESCREVE. -> True/False; None quando o banco é inalcançável.

        Separado de `query` de propósito: tudo o mais neste arquivo lê, e uma escrita
        no banco da rede merece aparecer no diff como uma chamada diferente.
        """
        if not self._connect():
            return None
        code, _ = self.client.exec_run(
            self._name, ["mariadb", "-uroot", "sexidium", "-N", "-e", sql], env=self._creds
        )
        return code == 0

    def count(self, table, column, value):
        """Quantas linhas daquela tabela nomeiam aquele nó; None se a tabela não existe.

        A tabela pode não existir: o schema cresce por migração e este comando roda
        contra bases de qualquer idade. Uma tabela ausente é ausência de reivindicação,
        não erro -- o que seria erro é o comando abortar no meio da limpeza por causa
        dela e deixar o nó meio esquecido.
        """
        rows = self.query(f"SELECT COUNT(*) FROM {table} WHERE {column} = '{value}';")
        if not rows:
            return None
        try:
            return int(rows[0][0])
        except (IndexError, ValueError):
            return None


# --- o pipeline ---------------------------------------------------------------


class Pipeline:
    def __init__(self, client, settings, args, helper, run_id):
        self.client = client
        self.settings = settings
        self.args = args
        self.helper = helper
        self.run_id = run_id
        self.topology = configmod.discover(client, settings)
        self.state_dir = self.topology.state_dir
        self.journal = Journal(helper, self.state_dir, run_id)
        self.lock = Lock(helper, self.state_dir, run_id)
        self.registry = Registry(client, settings)
        secrets = settings.secrets(create=False)
        self.token = secrets.get("api_token", "")
        self.alerts = Alerts(settings, url=secrets.get("alert_webhook"), dry_run=client.dry_run)
        self.resume = {"done": set(), "pending": None, "pins": {}}
        self.repinned = []  # (node, from-build) na ordem em que foram virados
        # None = ainda não sabemos; False = este build não tem o comando e usamos o
        # fallback. Guardado por run para o aviso sair UMA vez, não a cada nó.
        self.have_broadcast = None

    # --- utilidades -----------------------------------------------------------

    def name_of(self, node):
        return f"{self.settings.prefix}{node}"

    def node_dir(self, node):
        return f"/srv/nodes/{node}"

    def api_port_of(self, node):
        return api_port(
            self.topology.nodes, node, self.topology.api_base, self.topology.api_stride
        )

    def pin_of(self, node):
        text = self.helper.read(f"{self.node_dir(node)}/sexidium-build.pin")
        out = {}
        for line in text.splitlines():
            key, sep, value = line.strip().partition("=")
            if sep:
                out[key] = value
        return out

    def step(self, stage, sub=None, node=None):
        """Marca uma sub-etapa como concluída no diário, ou diz que já foi.

        A regra de retomada inteira mora aqui: uma chave com `ok` é pulada, uma chave
        com `begin` e nada depois é o ponto de retomada -- e como toda sub-etapa é
        idempotente, retomar é rodá-la de novo do começo.
        """
        key = (stage, node or "", sub or "")
        return key in self.resume["done"]

    def begin(self, stage, sub=None, node=None, detail=None):
        trace(f"stage {stage}{' ' + node if node else ''}{' ' + sub if sub else ''} begin")
        self.journal.record(stage, "begin", node=node, sub=sub, detail=detail)

    def ok(self, stage, sub=None, node=None, detail=None):
        self.journal.record(stage, "ok", node=node, sub=sub, detail=detail)

    def fail(self, stage, sub=None, node=None, detail=None):
        self.journal.record(stage, "fail", node=node, sub=sub, detail=detail)

    def say(self, text):
        print(f"  {text}")

    # --- estágio 0: PREFLIGHT -------------------------------------------------

    def preflight(self):
        """Nada foi mutado até aqui, então esta é a última porta barata.

        Recusar numa rede já doente não é preciosismo: um rolling update supõe que os
        nós que NÃO estão sendo tocados continuam servindo, e essa suposição é
        exatamente o que uma rede doente quebra.
        """
        import checks

        self.begin("preflight")
        problems = []

        compose = open(self.settings.compose, encoding="utf-8").read()
        # Rail 1. Subir o serviço `db` dormente roda um SEGUNDO mysqld sobre o datadir
        # que o container `sexidium-database`, feito à mão, já tem aberto. Isso é perda
        # de dados, não indisponibilidade.
        if 'profiles: ["db"]' not in compose:
            problems.append("o serviço `db` do compose perdeu o `profiles` (subiria um segundo mysqld)")

        rows = checks.Status(self.client, self.settings).run()
        failed = [r for r in rows if r[1] == FAIL]
        allowed = set((getattr(self.args, "allow_unhealthy", "") or "").split(","))
        blocking = [r for r in failed if r[0] not in allowed]
        if blocking:
            problems.append(
                "a rede já está com falhas: "
                + ", ".join(r[0] for r in blocking)
                + " (use --allow-unhealthy <nome>,… --yes para assumir uma delas)"
            )
        if problems:
            self.fail("preflight", detail={"problems": problems})
            print(summarize(rows))
            raise PreconditionError("; ".join(problems))
        self.ok("preflight")
        self.say(f"preflight OK ({len(rows)} checagens, 0 FAIL)")
        return rows

    # --- estágio 1 e 2: BUILD e INSTALL --------------------------------------

    def build(self):
        """Compila e estaciona no store. Não toca em nó nenhum."""
        from ops import cmd_sync
        from tests import cmd_test

        if self.step("build"):
            self.say("BUILD já concluído neste run; pulando")
            return self.latest_build()
        self.begin("build")
        cmd_sync(self.client, self.settings, self.args)
        if not getattr(self.args, "skip_tests", False):
            cmd_test(self.client, self.settings, self.args)
        self.run_init(mode="build")
        build_id = self.latest_build()
        if not build_id:
            self.fail("build")
            raise RemoteError("o build não deixou nenhuma entrada no store")
        self.ok("build", detail={"build": build_id})
        self.say(f"build {build_id} no store")
        return build_id

    def latest_build(self):
        return self.helper.read(f"{self.state_dir}/builds/LATEST").strip() or None

    def staged_jar_name(self, build_id):
        """O NOME do artefato daquele build, lido do MANIFESTO dele.

        O nome deixou de ser constante quando o artefato virou
        sexidium-paper-<mc>+<contador>.jar com contador POR versão: toda troca de piso
        muda o nome canônico, e um store que atravessou uma delas guarda builds sob
        NOMES diferentes. O manifesto (paper-jar-name=, escrito pelo store::stage) é o
        registro do nome real daquele build -- derivá-lo do fonte atual mentiria sobre
        builds antigos e apontaria o SEXIDIUM_JAR do install para caminho que não existe.
        """
        manifest = self.helper.read(f"{self.state_dir}/builds/{build_id}/manifest.txt") or ""
        for line in manifest.splitlines():
            if line.startswith("paper-jar-name="):
                return line.split("=", 1)[1].strip()
        # Builds anteriores à linha no manifesto só podem ter o nome da era velha.
        return "Sexidium-Paper-1.0.0.jar"

    def install(self, build_id):
        """Provisiona a rede inteira com o artefato já pronto, SEM mover pin nenhum.

        SX_ADOPT_BUILD=0 é o que separa estacionar de adotar. Sem ele o `init` pinaria
        os quatro nós de uma vez -- que é o comportamento certo para o `update` do dia a
        dia e o errado para um rolling update, onde o pin de cada nó vira entre a drenagem
        e o restart daquele nó e de mais nenhum.
        """
        if self.step("install"):
            self.say("INSTALL já concluído neste run; pulando")
            return
        self.begin("install", detail={"build": build_id})
        self.run_init(
            mode="full",
            env={
                "SX_SKIP_BUILD": "1",
                "SEXIDIUM_JAR": f"{self.state_dir}/builds/{build_id}/{self.staged_jar_name(build_id)}",
                "SX_ADOPT_BUILD": "0",
            },
        )
        self.ok("install", detail={"build": build_id})
        self.say("install OK (nenhum pin movido; todos os nós seguem no build anterior)")

    @contextlib.contextmanager
    def heartbeat(self):
        """Mantém o lock vivo enquanto uma chamada longa bloqueia a thread principal."""
        import threading

        stop = threading.Event()

        def beat():
            while not stop.wait(HEARTBEAT_EVERY):
                try:
                    self.lock.beat()
                except Exception:  # noqa: BLE001 -- um batimento perdido não derruba o run
                    return

        thread = threading.Thread(target=beat, daemon=True)
        thread.start()
        try:
            yield
        finally:
            stop.set()
            thread.join(timeout=5)

    def run_init(self, mode="full", env=None):
        """Roda o container `init` com um ambiente extra.

        O env do serviço vem do compose e não dá para mudá-lo sem redeployar o stack
        (o que recriaria containers), então as variáveis do run entram por um arquivo
        que o entrypoint carrega -- escrito pelo auxiliar, apagado depois.
        """
        name = f"{self.settings.prefix}init"
        if not self.client.inspect(name):
            raise PreconditionError(f"{name} não existe; rode `stack` antes")
        overrides = dict(env or {})
        overrides["SX_PROVISION_MODE"] = mode
        lines = "".join(f"export {k}={shell_quote(v)}\n" for k, v in overrides.items())
        self.helper.write(f"{self.state_dir}/pipeline/init-env.sh", lines)
        trace(f"init mode={mode} " + " ".join(f"{k}={v}" for k, v in sorted(overrides.items())))
        if self.client.dry_run:
            self.say(f"[dry-run] init ({mode}) não executado")
            return
        since = int(time.time())
        self.client.start(name)
        self.client.follow(name, since=since, out=lambda line: print(f"    {line}"))
        # O lock é batido em BACKGROUND aqui, e só aqui: esta é a única chamada que
        # bloqueia por mais tempo que STALE_AFTER. O provisionamento leva de 2 a 15 min
        # contra uma janela de 300s, então o lock do run ATIVO parecia morto no meio do
        # próprio trabalho -- e o segundo operador fazia o que a mensagem mandava
        # (`resume`), adotando um run vivo. HEARTBEAT_EVERY existia e nunca era usado.
        with self.heartbeat():
            code = self.client.wait(name, timeout=self.settings.timeout)
        self.helper.sh(f"rm -f {shell_quote(self.state_dir)}/pipeline/init-env.sh")
        if code != 0:
            raise RemoteError(f"init ({mode}) falhou (exit {code}); veja `logs init`")

    # --- por nó ---------------------------------------------------------------

    def roll_node(self, node, build_id):
        """Um nó, do anúncio à devolução. Cada sub-etapa journalada e re-entrante."""
        self.say(f"--- {node} ---")
        before = self.client.inspect(self.name_of(node)) or {}
        restarts_before = (before.get("RestartCount") or 0)

        self.announce(node)
        self.drain(node)
        old_build = self.pin(node, build_id)
        self.restart(node)
        evidence = self.verify(node, build_id, restarts_before)
        triggers = classify_rollback(evidence)
        if triggers:
            self.rollback(node, old_build, triggers)
            return False
        self.reclaim(node)
        return True

    def announce(self, node):
        """T-60/-30/-10 s. Nunca bloqueia a drenagem: um aviso que não sai é um aviso
        perdido, e adiar a drenagem por causa dele seria trocar isso por um deploy travado."""
        stage, sub = "roll", "announce"
        if self.step(stage, sub, node):
            return
        self.begin(stage, sub, node)
        row = self.registry.node(node) or {}
        players = int(row.get("players") or 0)
        if players <= 0:
            self.ok(stage, sub, node, detail={"players": 0})
            return
        port = self.api_port_of(node)
        for seconds in (60, 30, 10):
            # O pipeline manda SEGUNDOS e um motivo; o texto e a tradução são do JVM.
            # Formatar string voltada a jogador aqui seria a segunda cópia de uma coisa
            # que já é localizada do outro lado -- e a errada, porque esta ponta não sabe
            # em que idioma cada jogador está.
            self.broadcast(node, port, seconds, "update")
            if not self.client.dry_run:
                time.sleep(seconds - 30 if seconds > 30 else 10)
        self.ok(stage, sub, node, detail={"players": players})

    def broadcast(self, node, port, seconds, reason):
        """`sx admin broadcast`, com queda para `say` enquanto ele não existe.

        O fallback é inglês e sem estilo, e é anunciado como um WARN de propósito: um
        aviso feio é melhor que nenhum aviso, e um pipeline que só funcionasse depois de
        o JVM ter embarcado a metade dele não poderia ser usado hoje. Quando o comando
        existir esta função vira uma linha.
        """
        status, _ = node_command(
            self.helper, node, port, self.token, f"sx admin broadcast {seconds} {reason}"
        )
        if status == 200 and self.have_broadcast is not False:
            return
        if self.have_broadcast is None:
            self.have_broadcast = False
            self.alerts.fire(
                WARN,
                "broadcast.fallback",
                "`sx admin broadcast` ainda não existe neste build; avisando por "
                "`minecraft:say` (inglês, sem localização). Os avisos nunca bloqueiam a drenagem.",
            )
        node_command(
            self.helper, node, port, self.token,
            f"minecraft:say Server restarting in {seconds}s - you will be moved automatically",
        )

    def drain(self, node):
        """Drena e espera as QUATRO condições, todas lidas do banco.

        `players=0` sozinho não basta: um mundo ainda LOADED significa lease aberto, e
        reiniciar em cima disso é reiniciar com o dono de um mundo compartilhado dentro.
        """
        stage, sub = "roll", "drain"
        if self.step(stage, sub, node):
            return
        self.begin(stage, sub, node)
        port = self.api_port_of(node)
        node_command(self.helper, node, port, self.token, "sx admin net drain rolling-update")
        deadline = time.time() + getattr(self.args, "drain_timeout", 300)
        while time.time() < deadline:
            row = self.registry.node(node)
            placements = self.registry.loaded_placements(node)
            if row and row.get("state") == "DRAINING":
                players = int(row.get("players") or 0)
                worlds = int(row.get("worlds") or 0)
                if players == 0 and worlds == 0 and (placements or 0) == 0:
                    self.ok(stage, sub, node, detail={"players": 0, "worlds": 0})
                    self.say(f"{node} drenado")
                    return
            if self.client.dry_run:
                self.ok(stage, sub, node, detail={"dry-run": True})
                return
            time.sleep(5)

        on_timeout = getattr(self.args, "on_drain_timeout", "abort")
        if on_timeout != "force":
            self.fail(stage, sub, node, detail={"timeout": True})
            node_command(self.helper, node, port, self.token, "sx admin net undrain")
            raise RemoteError(
                f"{node} não drenou no prazo; nada foi repinado e o nó foi devolvido. "
                "Use --on-drain-timeout=force --yes para aceitar o custo (até 30 s de "
                "estado de experiência por jogador)."
            )
        self.alerts.fire(WARN, f"drain.forced.{node}", f"{node} não drenou; forçando")
        node_command(self.helper, node, port, self.token, "sx admin net drain rolling-update --force")
        if not self.client.dry_run:
            time.sleep(60)
        self.ok(stage, sub, node, detail={"forced": True})

    def redrain(self, node, reason):
        """Drena de novo um nó que JÁ foi devolvido à rede, antes de pará-lo.

        `drain()` não serve aqui e falharia em silêncio: ela é journalada por nó, a
        sub-etapa já está `ok` neste run, então ela voltaria na hora sem drenar nada.
        E o nó de uma convergência (ou de um abort) não é o nó da rolagem: ele foi
        RECLAMADO -- sem dreno, com jogador dentro. Parar um nó nesse estado é
        exatamente a desconexão que este pipeline existe para não causar, e ela viria
        pelo caminho tomado quando algo já deu errado, que é onde ela dói mais.

        `--skip-drain` é a saída para o nó INALCANÇÁVEL: se ele não responde à API, não
        há dreno a esperar e esperar o prazo inteiro só atrasa a reversão dos outros.
        """
        if getattr(self.args, "skip_drain", False):
            self.say(f"{node}: --skip-drain; parando sem drenar")
            return
        port = self.api_port_of(node)
        row = self.registry.node(node) or {}
        players = int(row.get("players") or 0)
        if players > 0:
            for seconds in (30, 10):
                self.broadcast(node, port, seconds, reason)
                if not self.client.dry_run:
                    time.sleep(20 if seconds > 10 else 10)
        self.begin("roll", "redrain", node, detail={"players": players})
        node_command(self.helper, node, port, self.token, f"sx admin net drain {reason}")
        if not self.client.dry_run and not self.wait_drained(node):
            # Prazo estourado: seguir mesmo assim é o certo AQUI, ao contrário do
            # `drain()` da rolagem. Lá abortar devolve o nó e não custa nada; aqui a rede
            # já está repartida em dois builds, e parar no meio a deixaria assim.
            self.alerts.fire(
                WARN,
                f"redrain.timeout.{node}",
                f"{node} não drenou no prazo antes da reversão; seguindo (pode haver queda)",
            )
        self.ok("roll", "redrain", node)

    def wait_drained(self, node):
        """As mesmas QUATRO condições do `drain()`, esperadas até `--drain-timeout`."""
        deadline = time.time() + getattr(self.args, "drain_timeout", 300)
        while time.time() < deadline:
            row = self.registry.node(node)
            if row and row.get("state") == "DRAINING":
                empty = int(row.get("players") or 0) == 0 and int(row.get("worlds") or 0) == 0
                if empty and (self.registry.loaded_placements(node) or 0) == 0:
                    return True
            time.sleep(5)
        return False

    def pin(self, node, build_id):
        """Vira o pin. Journalada com AMBOS os ids, e é isso que faz a retomada nunca
        precisar adivinhar: o diário diz de onde e para onde, então um PIN reexecutado
        compara e pula, e um rollback sabe o alvo mesmo com o arquivo de pin no meio de
        uma escrita."""
        stage, sub = "roll", "pin"
        pinfile = self.pin_of(node)
        current = pinfile.get("build", "")
        if self.step(stage, sub, node):
            # O fallback NÃO pode ser `current`: aqui o pin JÁ virou, então `current` é
            # exatamente o build de que um rollback estaria tentando escapar -- e a
            # reversão viraria um no-op silencioso na única hora em que ela importa
            # (diário com a linha truncada). `previous=` é o que o store grava para
            # responder a esta pergunta, e é para lá que se volta.
            return self.resume["pins"].get(node, {}).get("from") or pinfile.get("previous", "")
        self.begin(stage, sub, node, detail={"from": current, "to": build_id})
        if current != build_id:
            self.pin_via_helper(node, build_id)
        after = self.pin_of(node)
        if not self.client.dry_run and after.get("build") != build_id:
            self.fail(stage, sub, node, detail={"from": current, "to": build_id})
            raise RemoteError(f"o pin de {node} não virou para {build_id}")
        self.ok(stage, sub, node, detail={"from": current, "to": build_id})
        self.repinned.append((node, current))
        return current

    def pin_via_helper(self, node, build_id):
        """A renomeação, executada DENTRO do auxiliar, pela mesma função do provisionador.

        Pelo auxiliar e não pelo container do nó porque o pin tem de poder virar num nó
        PARADO ou em crash-loop -- que é precisamente o caso do rollback.
        """
        trace(f"pin {node} -> {build_id}")
        if self.client.dry_run:
            self.say(f"[dry-run] pin {node} -> {build_id}")
            return
        script = (
            "set -Eeuo pipefail; "
            "export ROOT_DIR=/srv/build/repo NETWORK_DIR=/srv/nodes "
            f"SX_SHARED_INSTALL={shell_quote(self.state_dir)} "
            f"SX_PIN_ACTOR={shell_quote('pipeline/' + self.run_id)}; "
            ". /srv/build/repo/scripts/lib/core.sh; sx::require sexidium store; store::defaults; "
            f"store::pin_node /srv/nodes/{node} {shell_quote(build_id)} --record-previous"
        )
        self.helper.must_sh(script, f"pinar {node} em {build_id}")

    def restart(self, node):
        stage, sub = "roll", "restart"
        if self.step(stage, sub, node):
            return
        self.begin(stage, sub, node)
        trace(f"restart {node}")
        if not self.client.dry_run:
            self.client.restart(self.name_of(node), seconds=120)
            from ops import wait_running

            if not wait_running(self.client, self.name_of(node), 60):
                self.fail(stage, sub, node)
                raise RemoteError(f"{node} não voltou a rodar")
        self.ok(stage, sub, node)

    # --- verificação ----------------------------------------------------------

    def verify(self, node, build_id, restarts_before):
        """Composto de boot + bateria, contra ESTE boot, com o nó ainda drenado.

        Drenado de propósito: se algo aqui reprovar, nenhum jogador esteve exposto ao
        build novo em momento nenhum.
        """
        stage, sub = "roll", "verify"
        if self.step(stage, sub, node):
            # A única sub-etapa que não tinha esta guarda, e a que mais custava sem ela.
            # Um verify re-executado numa retomada mede um nó que já foi DEVOLVIDO: ele
            # está UP, sem dreno e com jogadores dentro, então `battery.placements` reprova
            # por ver o estado CERTO, e o banner de prontidão sumiu da cauda de 3000 linhas
            # de um boot que foi há muito tempo. `classify_rollback` leria isso como build
            # ruim e o rollback PARARIA um nó saudável -- pelo caminho da recuperação.
            # O diário já tem o `ok` daquele verify; ele é a prova, e não se re-deriva.
            self.say(f"{node}: verify já concluído neste run; pulando (o diário é a prova)")
            return {"already_verified": True}
        self.begin(stage, sub, node)
        name = self.name_of(node)
        timeout = getattr(self.args, "boot_timeout", 300) or 300
        deadline = time.time() + timeout
        log = ""
        ready = False
        while time.time() < deadline:
            info = self.client.inspect(name) or {}
            since = started_epoch(info)
            log = self.client.logs(name, tail="3000", since=since)
            if READY_LINE in log or "Network node '" in log:
                ready = True
                break
            if self.client.dry_run:
                ready = True
                break
            time.sleep(5)

        # A espera acima dispara na PRIMEIRA linha de meio-de-enable ("Network node '"),
        # e a bateria abaixo lê o BANCO — onde o processo morto há segundos deixou a sua
        # última linha: plugin_version do build VELHO, heartbeat morrendo, API já sem
        # dono. Foi exatamente assim que um canário saudável levou rollback: as cinco
        # falhas da bateria eram idade, não defeito. Espera aqui até o nó NOVO publicar
        # a própria evidência — versão com o build pinado, heartbeat fresco e /health
        # respondendo — ou até o prazo; quem nunca convergiu é um boot ruim de verdade,
        # e a bateria o reprova como sempre.
        port = self.api_port_of(node)
        converge_deadline = time.time() + (getattr(self.args, "converge_timeout", None) or 180)
        converged = False
        while not self.client.dry_run and time.time() < converge_deadline:
            row = self.registry.node(node) or {}
            reported = row.get("plugin_version") or ""
            age = self._age(row)
            status, _ = node_http(self.helper, node, port, "GET", "/health", self.token)
            if build_id in reported and (age is None or age < 30) and status == 200:
                converged = True
                break
            time.sleep(5)

        info = self.client.inspect(name) or {}
        row = self.registry.node(node) or {}
        battery = self.battery(node, log, build_id)
        print(summarize(battery))
        evidence = {
            "boot_timed_out": not ready,
            "restart_count_before": restarts_before,
            "restart_count_now": info.get("RestartCount"),
            "log": log,
            "oom_killed": ((info.get("State") or {}).get("OOMKilled")),
            "exit_code": (info.get("State") or {}).get("ExitCode"),
            "plugin_version": row.get("plugin_version"),
            "pinned_build": build_id,
            "battery": battery,
            "running": bool((info.get("State") or {}).get("Running")),
            "heartbeat_age": self._age(row),
        }
        if self.client.dry_run:
            evidence = {"battery": battery}
        self.ok(stage, sub, node, detail={"ready": ready})
        return evidence

    @staticmethod
    def _age(row):
        try:
            return int(row.get("age")) // 1000
        except (TypeError, ValueError):
            return None

    def battery(self, node, log, build_id):
        """As asserções, contra um nó já de pé e ainda drenado.

        SKIP não é FAIL, e a distinção é estrutural: as asserções cuja metade em Java
        ainda não existe devolvem SKIP, e SKIP nunca dispara rollback. É o que permite
        embarcar o pipeline antes do JVM ter terminado a parte dele.
        """
        rows = []
        name = self.name_of(node)
        port = self.api_port_of(node)

        def add(check, state, detail=""):
            rows.append((check, state, detail))

        add(
            "battery.boot",
            PASS if (READY_LINE in log or "Network node '" in log) else FAIL,
            "linha de prontidão presente" if READY_LINE in log else "sem SX-READY neste boot",
        )
        add(
            "battery.api-line",
            PASS if API_LINE in log else SKIP,
            API_LINE if API_LINE in log else "a API não anunciou o bind neste log",
        )
        add(
            "battery.reconciler",
            PASS if RECONCILER_LINE in log else SKIP,
            "disco e banco concordam" if RECONCILER_LINE in log else "sem linha do reconciler",
        )
        # A asserção de MAIOR VALOR do contrato, e vale saber por quê:
        # ChallengeCatalog.create descartava ids desconhecidos em silêncio, e um nó com
        # catálogo velho GERA TERRENO NORMAL dentro do que deveria ser um SkyBlock vazio
        # -- sem erro, e destruindo o save. O selftest transforma isso num gatilho de
        # rollback ANTES de um jogador tocar no nó.
        #
        # Pelo ENDPOINT primeiro: /command é fire-and-forget e não devolve resultado, então
        # ler a linha de log é a segunda melhor prova (e a única enquanto o endpoint não
        # existia). SKIP -- alto e barulhento -- quando nenhum dos dois responde.
        selftest = node_json(self.helper, node, port, "/node/selftest", self.token)
        if isinstance(selftest, dict) and "ok" in selftest:
            add(
                "battery.selftest",
                PASS if selftest.get("ok") else FAIL,
                json.dumps({k: v for k, v in selftest.items() if k != "detail"})[:120]
                + (f" detail={selftest.get('detail')}" if selftest.get("detail") else ""),
            )
        elif SELFTEST_LINE in log:
            line = next(l for l in log.splitlines() if SELFTEST_LINE in l)
            add("battery.selftest", PASS if "ok=true" in line else FAIL, line.strip()[-120:])
        else:
            add("battery.selftest", SKIP, "nem GET /node/selftest nem SX-SELFTEST neste build")

        code, out = self.client.sh(name, "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null")
        ports = listening_ports(out) if code == 0 else set()
        want = {game_port(self.topology.nodes, node, self.topology.port_base), port}
        missing = {p for p in want if p and p not in ports}
        add(
            "battery.ports",
            SKIP if not ports else (PASS if not missing else FAIL),
            f"faltando {sorted(missing)}" if missing else f"escutando {sorted(want)}",
        )

        status, payload = node_http(self.helper, node, port, "GET", "/health", self.token)
        add(
            "battery.api",
            PASS if status == 200 else FAIL,
            f"/health -> {status}" if status else "sem resposta HTTP",
        )

        row = self.registry.node(node)
        if row is None:
            add("battery.registry", SKIP, "banco inalcançável")
        else:
            age = self._age(row)
            good = row.get("state") in ("UP", "DRAINING") and (age is None or age < 15)
            add(
                "battery.registry",
                PASS if good else FAIL,
                f"state={row.get('state')} heartbeat={age}s",
            )
            reported = row.get("plugin_version") or ""
            add(
                "battery.build",
                SKIP if not reported else (PASS if build_id in reported else FAIL),
                reported or "plugin_version vazio (build anterior a este trabalho)",
            )

        placements = self.registry.loaded_placements(node)
        add(
            "battery.placements",
            SKIP if placements is None else (PASS if placements == 0 else FAIL),
            "nenhum lease aberto enquanto drenado" if not placements else f"{placements} LOADED",
        )
        return rows

    # --- devolução e rollback -------------------------------------------------

    def reclaim(self, node):
        stage, sub = "roll", "reclaim"
        if self.step(stage, sub, node):
            return
        self.begin(stage, sub, node)
        node_command(self.helper, node, self.api_port_of(node), self.token, "sx admin net undrain")
        soak = getattr(self.args, "soak", 120)
        if not self.client.dry_run and soak:
            time.sleep(min(soak, 30))
            row = self.registry.node(node) or {}
            age = self._age(row)
            if age is not None and age > 30:
                self.fail(stage, sub, node, detail={"age": age})
                raise RemoteError(f"{node} perdeu o heartbeat no soak ({age}s)")
        self.ok(stage, sub, node)
        self.alerts.fire(INFO, f"repinned.{node}", f"{node} está no build novo e devolvido à rede")
        self.say(f"{node} de volta")

    def rollback(self, node, previous, triggers):
        """Repina para trás, reinicia, verifica de novo -- a MESMA renomeação.

        A ordem importa e a primeira linha é a que mais importa: PARAR o container antes
        de qualquer coisa. `restart: unless-stopped` com MaximumRetryCount 0 reinicia
        para sempre, e um container explicitamente parado é a única alavanca que
        interrompe um crash-loop. Repinar embaixo de um loop é apostar numa corrida.
        """
        ids = ", ".join(f"{tid} ({detail})" for tid, detail in triggers)
        self.alerts.fire(ERROR, f"rollback.{node}", f"{node}: {ids} -> voltando para {previous}")
        self.begin("rollback", node=node, detail={"triggers": [t for t, _ in triggers], "to": previous})
        trace(f"rollback {node} -> {previous}")
        if not previous:
            self.lock.mark("needs-human")
            self.fail("rollback", node=node, detail={"reason": "sem build anterior"})
            raise RemoteError(
                f"{node} reprovou ({ids}) e não tem `previous=` para onde voltar. "
                f"O container foi deixado como está; escolha um build com "
                f"`pipeline pin {node} <build-id> --yes`."
            )
        if self.client.dry_run:
            self.ok("rollback", node=node)
            return

        self.client.stop(self.name_of(node), seconds=120)
        self.pin_via_helper(node, previous)
        self.client.start(self.name_of(node))
        from ops import wait_running

        healthy = wait_running(self.client, self.name_of(node), 60)
        log = ""
        if healthy:
            deadline = time.time() + 180
            while time.time() < deadline:
                info = self.client.inspect(self.name_of(node)) or {}
                log = self.client.logs(self.name_of(node), tail="3000", since=started_epoch(info))
                if READY_LINE in log or "Network node '" in log:
                    break
                time.sleep(5)
        if not healthy or not (READY_LINE in log or "Network node '" in log):
            self.rollback_failed(node, previous)
            return
        node_command(self.helper, node, self.api_port_of(node), self.token, "sx admin net undrain")
        self.ok("rollback", node=node, detail={"to": previous})
        self.say(f"{node} de volta em {previous}")

    def rollback_failed(self, node, previous):
        """A falha é AMBIENTAL, não de código: o build anterior estava servindo minutos
        atrás. Então pare de mexer.

        Deixa o nó parado e drenado, marca o lock como needs-human (o que recusa todo
        run novo, autoscaler incluso) e deixa a rede N-1 SERVINDO. Uma rede degradada é
        uma rede; uma rede meio-rolada com um membro em crash-loop é uma rede quebrada.
        """
        self.client.stop(self.name_of(node), seconds=120)
        self.lock.mark("needs-human")
        self.fail("rollback", node=node, detail={"to": previous, "state": "ROLLBACK_FAILED"})
        self.alerts.fire(
            CRITICAL,
            f"rollback.failed.{node}",
            f"o rollback de {node} para {previous} não subiu. O container está PARADO e "
            f"drenado; os outros nós seguem servindo. Libere com "
            f"`pipeline abort --force-unlock --yes` depois de investigar.",
        )
        raise RemoteError(f"ROLLBACK_FAILED em {node}; a rede segue no ar sem ele")

    # --- ordem e as duas exceções honestas ------------------------------------

    def plan(self):
        """A ordem de rolagem, e as recusas que a antecedem.

        A ordem (workers de trás para frente, depois lobby, depois proxy) já era a
        certa e a razão continua valendo: enquanto o proxy vive, quem cai é devolvido ao
        lobby, então o proxy vai por último; e o lobby, sendo o destino do fallback,
        fica indisponível pelo menor tempo possível.
        """
        order = [n for n in restart_order(self.topology.nodes) if n != "proxy"]
        canary = getattr(self.args, "canary", None)
        if canary and canary in order:
            order = [canary] + [n for n in order if n != canary]

        lobbies = [n for n in self.topology.nodes if n == "lobby" or n.startswith("lobby")]
        if any(n in lobbies for n in order):
            live = self.live_lobbies()
            # A recusa vale pelo que ela mesma diz: rolar o único lobby É desconectar. Mas
            # isso só é verdade se houver ALGUÉM nele. Com o lobby vazio ninguém cai, e
            # exigir dois lobbies aí transformava a regra numa trava: `lobby` está sempre na
            # ordem de rolagem, o papel vem de um nome exato e o autoscaler só cria
            # `worker-N`, então a pré-condição era insatisfazível POR CONSTRUÇÃO -- todo run
            # abortava, ou pedia --allow-lobby-disconnect, que é exatamente a desconexão que
            # a regra existe para impedir. A regra protege jogadores, não o número de lobbies.
            occupied = self.lobby_players()
            if live < 2 and occupied > 0 and not getattr(self.args, "allow_lobby_disconnect", False):
                # A instrução é explícita: jogadores não podem ser desconectados. Com um
                # lobby só, `try = ["lobby"]` do Velocity não tem para onde devolver
                # ninguém, então rolá-lo É desconectar. Isto é uma RECUSA, não um aviso:
                # o caminho suportado para um lobby sem queda é subir um segundo lobby
                # primeiro (o autoscaler faz isso) e então rolar um de cada vez.
                raise PreconditionError(
                    f"há {live} lobby vivo e não-drenando; rolar o lobby desconectaria todo mundo "
                    "que estiver nele. Suba um segundo lobby (`scale up --role lobby`) e rode de "
                    "novo, ou assuma a queda com --allow-lobby-disconnect."
                )
        return order

    def live_lobbies(self):
        rows = self.registry.all_nodes()
        if not rows:
            return 0
        return sum(
            1
            for r in rows
            if (r.get("role") or "").lower() == "lobby" and r.get("state") == "UP"
        )

    def lobby_players(self):
        """Quantos jogadores estão AGORA num lobby vivo.

        Fail-safe de propósito: sem registro, ou com a coluna ilegível, devolve 1 -- "pode
        haver alguém". Errar para o lado de recusar custa um deploy adiado; errar para o
        outro custa exatamente a desconexão que a regra existe para impedir.
        """
        rows = self.registry.all_nodes()
        if not rows:
            return 1
        total = 0
        for r in rows:
            if (r.get("role") or "").lower() != "lobby" or r.get("state") not in ("UP", "DRAINING"):
                continue
            try:
                total += int(r.get("players") or 0)
            except (TypeError, ValueError):
                return 1
        return total

    def proxy_leg(self, build_id):
        """O proxy é a segunda exceção honesta, e é pulável na maioria das vezes.

        `module-velocity` muda muito menos que o core, então o caso comum é o sha do jar
        do proxy não ter mudado -- e aí não há nada a reiniciar e ninguém cai. Quando
        mudou, um proxy único não tem como ser atualizado sem derrubar a rede: o pipeline
        PARA e pede uma janela explícita em vez de desconectar todo mundo em silêncio.
        """
        current = self.pin_of("proxy").get("sha256", "")
        _, out = self.helper.sh(
            f"sha256sum {shell_quote(self.state_dir)}/builds/{build_id}/Sexidium-Velocity-1.0.0.jar "
            "2>/dev/null | cut -d' ' -f1"
        )
        wanted = out.strip().splitlines()[-1] if out.strip() else ""
        if not wanted:
            # "Não consegui ler o jar" NÃO é "o jar não mudou", e tratar um como o outro era
            # a pior das duas leituras: um build montado sem `Sexidium-Velocity-1.0.0.jar`
            # (forma que o store permite) fazia o pipeline anunciar "ninguém cai", journalar
            # o pulo como intencional e PROMOVER -- backends novos atrás de um plugin de
            # proxy velho, exatamente o skew que o provisionador diz não saber consertar.
            raise PreconditionError(
                f"não consegui ler o sha do jar do proxy no build {build_id} "
                f"({self.state_dir}/builds/{build_id}/Sexidium-Velocity-1.0.0.jar). "
                "Isso não é 'o proxy não mudou' -- é 'não sei se mudou'. Confira o build "
                "com `pipeline builds` antes de promover."
            )
        forced = bool(getattr(self.args, "force_proxy", False))
        if wanted == current and not forced:
            # O QUE ESTE SHA NÃO COBRE, dito em voz alta porque já custou um deploy. Ele é o do
            # `Sexidium-Velocity-1.0.0.jar` e só dele: uma mudança em `plugins/skinsrestorer/config.yml`,
            # em qualquer jar de terceiro do proxy ou no `velocity.toml` não mexe neste hash, e o
            # pipeline pulava a perna do proxy anunciando "ninguém cai" -- correto quanto a jogadores,
            # e errado quanto ao que o operador acabou de mandar para lá. O Velocity não recarrega
            # plugin a quente, então "pulado" ali significa "não aplicado" até alguém reiniciar. Com
            # --force-proxy a perna roda mesmo com o jar igual (ainda sob --maintenance-window, porque
            # o proxy é único e reiniciá-lo derruba a rede).
            self.say(
                "proxy: o jar do plugin não mudou; a perna do proxy é pulada (ninguém cai). "
                "Mudança só de config ou de jar de terceiro NÃO move este sha -- use --force-proxy."
            )
            self.journal.record("roll", "skip", node="proxy", sub="unchanged")
            return True
        if wanted == current:
            self.say("proxy: --force-proxy; a perna roda mesmo com o jar do plugin inalterado")
        if not getattr(self.args, "maintenance_window", False):
            # A razão tem de dizer a verdade: com --force-proxy o jar pode estar IGUAL, e uma
            # mensagem que afirma "o plugin MUDOU" manda o operador procurar um diff que não existe.
            # O que não muda é a consequência -- um proxy só, reiniciado, derruba a rede.
            why = (
                "--force-proxy foi pedido"
                if wanted == current
                else "o plugin do proxy MUDOU neste build"
            )
            raise PreconditionError(
                f"{why} e há um proxy só: reiniciá-lo desconecta a rede inteira por alguns "
                "segundos. Rode de novo com --maintenance-window para assumir a janela "
                "(o resto do rolling update já terminou)."
            )
        self.begin("roll", "proxy", node="proxy")
        for seconds in (300, 60, 30):
            for node in self.topology.nodes:
                node_command(
                    self.helper, node, self.api_port_of(node), self.token,
                    f"sx admin broadcast {seconds} restart",
                )
        self.pin_via_helper_proxy(build_id)
        if not self.client.dry_run:
            self.client.restart(self.name_of("proxy"), seconds=120)
        self.ok("roll", "proxy", node="proxy")
        return True

    def pin_via_helper_proxy(self, build_id):
        if self.client.dry_run:
            return
        script = (
            "set -Eeuo pipefail; export ROOT_DIR=/srv/build/repo NETWORK_DIR=/srv/nodes "
            f"SX_SHARED_INSTALL={shell_quote(self.state_dir)}; "
            ". /srv/build/repo/scripts/lib/core.sh; sx::require sexidium store; store::defaults; "
            f"store::pin_proxy /srv/nodes/proxy {shell_quote(build_id)}"
        )
        self.helper.must_sh(script, "pinar o proxy")

    # --- finalização ----------------------------------------------------------

    def finalise(self, build_id, rows_before):
        import checks

        self.begin("finalise")
        rows = checks.Status(self.client, self.settings).run()
        failed = [r for r in rows if r[1] == FAIL]
        print(summarize(rows))
        if failed:
            self.fail("finalise", detail={"failed": [r[0] for r in failed]})
            raise RemoteError("a rede não convergiu depois do rolling update; veja acima")
        self.helper.sh(f"touch {shell_quote(self.state_dir)}/builds/{build_id}/PROMOTED")
        # GC só aqui, nunca durante: um provisionamento que pudesse apagar um alvo de
        # rollback faria de "o deploy falhou" e "não dá para voltar" o mesmo evento.
        self.helper.sh(
            "set -Eeuo pipefail; export ROOT_DIR=/srv/build/repo NETWORK_DIR=/srv/nodes "
            f"SX_SHARED_INSTALL={shell_quote(self.state_dir)}; "
            ". /srv/build/repo/scripts/lib/core.sh; sx::require sexidium store; "
            "store::defaults; store::gc"
        )
        self.ok("finalise", detail={"build": build_id})
        self.alerts.fire(INFO, "run.finished", f"run {self.run_id}: build {build_id} promovido")


# --- subcomandos --------------------------------------------------------------


def run_id_now():
    return time.strftime("%Y%m%d-%H%M%S")


def cmd_deploy(client, settings, args):
    """O rolling update inteiro: preflight -> build -> install -> canário -> resto -> proxy."""
    topology = configmod.discover(client, settings)
    with Helper(client, settings, topology) as helper:
        run = getattr(args, "run_id", None) or run_id_now()
        pipe = Pipeline(client, settings, args, helper, run)
        pipe.lock.acquire()
        pipe.journal.set_current()
        pipe.alerts.fire(INFO, "run.started", f"run {run} começou")
        try:
            rows = pipe.preflight()
            build_id = pipe.build()
            pipe.install(build_id)
            for node in pipe.plan():
                pipe.lock.beat()
                if not pipe.roll_node(node, build_id):
                    # O nó voltou para o build anterior. Convergir a rede: os que já
                    # foram repinados voltam também, em ordem inversa, para que ela fique
                    # num build só. Como todos começaram iguais, "quanto voltar" não tem
                    # ambiguidade -- um passo.
                    converge(pipe, node)
                    raise RemoteError(f"{node} reprovou a verificação; o run foi revertido")
            pipe.proxy_leg(build_id)
            pipe.finalise(build_id, rows)
            print(f"pipeline: build {build_id} no ar em toda a rede")
            return 0
        finally:
            pipe.lock.release()


def converge(pipe, failed=None):
    """Devolve ao build anterior todo nó que ESTE run já virou, em ordem inversa.

    A fonte é o DIÁRIO, não a lista em memória. Num run retomado o `pin` de cada nó já
    concluído é curto-circuitado e nunca entra em `repinned`, então convergir por
    memória não convergia nada: os nós de antes da queda ficavam no build novo, sem
    rollback e sem ninguém dizendo. `cmd_abort` já lia daqui -- as duas rotas de
    desfazer passam a ler a mesma coisa.

    `failed` já voltou pelo rollback que disparou esta convergência; repetir seria
    parar de novo um nó que acabou de subir.
    """
    for node, moves in reversed(list(pipe.journal.state()["pins"].items())):
        if node == failed:
            continue
        origin = moves.get("from")
        if not origin:
            pipe.say(f"{node}: sem `from` no diário; deixado como está")
            continue
        pipe.say(f"convergindo {node} de volta para {origin}")
        # Este nó foi DEVOLVIDO à rede: tem jogador dentro. Drena antes de parar.
        pipe.redrain(node, "rollback")
        pipe.rollback(node, origin, [("R0", "convergindo o run revertido")])


def adopt_lock(pipe):
    """Pega o lock para uma RETOMADA: adota um estancado, recusa os outros dois casos.

    `acquire(force=True)` era um atalho e passava por cima das duas guardas inteiras,
    que vivem as duas dentro do `if held and not force` do `Lock.acquire`:

      needs-human  é o estado que existe PARA parar a automação depois de um rollback
                   que falhou. Uma retomada que o atropela puxa mais um nó de uma rede
                   já N-1 -- e ninguém pediu isso, o run só foi retomado.
      run vivo     dois pipelines rolando os mesmos nós, cada um lendo o pin
                   meio-aplicado do outro. Retomar não é motivo para concorrer.

    Só o lock ESTANCADO é adotável, e é exatamente para ele que `resume` existe.
    """
    held = pipe.lock.read()
    stale = False
    if held:
        if held.get("state") == "needs-human":
            raise PreconditionError(
                f"o lock está em needs-human (run {held.get('run')}): um rollback falhou e a "
                "rede foi deixada num estado que pede olho humano. `resume` NÃO passa por "
                "cima disso. Leia `pipeline status`, resolva, e libere com "
                "`pipeline abort --force-unlock --yes`."
            )
        age = int(time.time()) - int(held.get("heartbeat-at") or 0)
        stale = age >= STALE_AFTER
        if not stale:
            raise PreconditionError(
                f"outro run está em curso (run {held.get('run')}, heartbeat há {age}s); "
                "retomar agora poria dois pipelines rolando os mesmos nós. Espere ele "
                "terminar, ou confira em `pipeline status`."
            )
    pipe.lock.acquire(force=stale)


def resume_build(pipe):
    """O build que ESTE run estava rolando, lido do diário -- não o LATEST de agora.

    `latest_build()` sozinho era uma troca silenciosa de alvo: um `pipeline deploy` que
    aterrisse entre a queda e a retomada move o LATEST, e a retomada instalaria e pinaria
    um build diferente do que a outra metade da rede já está rodando, com o diário
    afirmando o primeiro. A rede acabaria repartida em dois builds e o registro seria
    mentira -- que é pior que o run simplesmente parar.

    O LATEST só vale quando o diário ainda não tem pin nenhum (nada foi virado, então
    não há alvo a preservar). Quando os dois discordam, RECUSA: escolher por conta
    própria entre "o que o run prometeu" e "o que alguém deployou depois" não é uma
    decisão de automação.
    """
    targets = {m["to"] for m in pipe.resume["pins"].values() if m.get("to")}
    latest = pipe.latest_build()
    if len(targets) > 1:
        raise PreconditionError(
            "o diário deste run aponta para mais de um build ("
            + ", ".join(sorted(targets))
            + "); isso não é retomável. Use `pipeline abort --yes` e rode um deploy novo."
        )
    build_id = next(iter(targets), None) or latest
    if not build_id:
        raise PreconditionError("o store não tem build; rode `pipeline deploy`")
    if targets and latest and build_id != latest:
        raise PreconditionError(
            f"este run estava rolando {build_id} e o store agora aponta {latest}: alguém "
            "deployou entre a queda e esta retomada. Retomar espalharia a rede por dois "
            f"builds. Use `pipeline abort --yes` para desfazer este run, ou `pipeline pin "
            f"<nó> {build_id} --yes` nó a nó."
        )
    return build_id


def cmd_resume(client, settings, args):
    """Adota o run gravado e continua da primeira sub-etapa iniciada e não terminada."""
    topology = configmod.discover(client, settings)
    with Helper(client, settings, topology) as helper:
        state_dir = topology.state_dir
        run = getattr(args, "run_id", None) or Journal.current(helper, state_dir)
        if not run:
            raise PreconditionError("não há run gravado para retomar")
        pipe = Pipeline(client, settings, args, helper, run)
        pipe.resume = pipe.journal.state()
        adopt_lock(pipe)
        pending = pipe.resume["pending"]
        print(f"retomando o run {run} ({len(pipe.resume['done'])} sub-etapas concluídas)")
        if pipe.resume["bad"]:
            print(f"  ({pipe.resume['bad']} linha(s) truncada(s) descartada(s) -- run interrompido)")
        if pending:
            print(f"  ponto de retomada: {'/'.join(x for x in pending if x)}")
        try:
            build_id = resume_build(pipe)
            pipe.install(build_id)
            for node in pipe.plan():
                pipe.lock.beat()
                if not pipe.roll_node(node, build_id):
                    converge(pipe, node)
                    raise RemoteError(f"{node} reprovou a verificação; o run foi revertido")
            pipe.proxy_leg(build_id)
            pipe.finalise(build_id, [])
            return 0
        finally:
            pipe.lock.release()


def cmd_pipeline_status(client, settings, args):
    """O lock, o run atual e a cauda do diário -- mais o pin de cada nó.

    A tabela de pins é a resposta para a pergunta que não existia antes deste trabalho:
    "quem está rodando o quê agora". Durante um rolling update ela é deliberadamente
    heterogênea, e poder LER isso é metade do valor.
    """
    topology = configmod.discover(client, settings)
    with Helper(client, settings, topology) as helper:
        state_dir = topology.state_dir
        lock = Lock(helper, state_dir, "-").read()
        run = Journal.current(helper, state_dir)
        latest = helper.read(f"{state_dir}/builds/LATEST").strip()
        print(f"store LATEST: {latest or '<nenhum>'}")
        if lock:
            age = int(time.time()) - int(lock.get("heartbeat-at") or 0)
            flag = " ESTANCADO" if age > 300 else ""
            print(f"lock: run={lock.get('run')} state={lock.get('state')} heartbeat={age}s{flag}")
        else:
            print("lock: livre")
        rows = []
        for node in ["proxy"] + list(topology.nodes):
            pin = {}
            for line in helper.read(f"/srv/nodes/{node}/sexidium-build.pin").splitlines():
                key, sep, value = line.strip().partition("=")
                if sep:
                    pin[key] = value
            state = "=" if pin.get("build") == latest else "ATRASADO"
            rows.append((node, state, f"{pin.get('build', '<sem pin>')} (antes: {pin.get('previous', '-')})"))
        print(summarize(rows))
        if run:
            journal = Journal(helper, state_dir, run)
            tail = journal.text().splitlines()[-12:]
            print(f"\nrun {run}, últimos {len(tail)} eventos:")
            for line in tail:
                print(f"  {line}")
        return 0


def cmd_abort(client, settings, args):
    """Libera o lock, revertendo antes tudo o que o diário mostra como repinado."""
    from ops import confirm

    topology = configmod.discover(client, settings)
    with Helper(client, settings, topology) as helper:
        state_dir = topology.state_dir
        run = getattr(args, "run_id", None) or Journal.current(helper, state_dir)
        if not run and getattr(args, "force_unlock", False):
            # O lock NÃO é só do pipeline: `stack`, `provision`, `restart`, `update` e o
            # autoscaler também o pegam, com um id `manual-<ts>` e sem diário nenhum. Quando
            # um DESSES morre segurando o lock não existe run para adotar -- e exigir um aqui
            # fazia a única saída documentada recusar justamente o caso em que ela é
            # necessária, deixando a rede inteira trancada: `provision` e `restart` recusavam
            # pelo lock estancado, e `abort --force-unlock` recusava por "não há run".
            held = Lock(helper, state_dir, "-").read()
            if not held:
                raise PreconditionError("não há lock para liberar")
            stale = int(time.time()) - int(held.get("heartbeat-at") or 0)
            confirm(args, f"liberar o lock do run {held.get('run')} "
                          f"(estado {held.get('state')}, heartbeat há {stale}s) SEM reverter nada")
            Lock(helper, state_dir, held.get("run") or "-").release(force=True)
            print(f"lock liberado (run {held.get('run')}); nenhum pin foi tocado")
            return 0
        if not run:
            raise PreconditionError("não há run para abortar")
        pipe = Pipeline(client, settings, args, helper, run)
        pipe.resume = pipe.journal.state()
        pins = pipe.resume["pins"]
        if getattr(args, "force_unlock", False):
            confirm(args, f"liberar o lock do run {run} SEM reverter nada")
            pipe.lock.release(force=True)
            print("lock liberado; nenhum pin foi tocado")
            return 0
        confirm(args, f"abortar o run {run} e reverter {len(pins)} nó(s) repinado(s)")
        for node, moves in reversed(list(pins.items())):
            origin = moves.get("from")
            if not origin:
                print(f"  {node}: sem `from` no diário; deixado como está")
                continue
            print(f"  {node}: voltando para {origin}")
            # Estes nós foram DEVOLVIDOS à rede pelo run que morreu: estão servindo. Um
            # restart aqui derrubaria quem está neles -- pelo comando que existe para
            # consertar as coisas. Drena primeiro, sempre; `--skip-drain` para o nó que
            # não responde mais.
            pipe.redrain(node, "abort")
            if not client.dry_run:
                pipe.pin_via_helper(node, origin)
                client.restart(pipe.name_of(node), seconds=120)
        pipe.lock.release(force=True)
        return 0


def cmd_pin(client, settings, args):
    """A saída manual: pina um nó em qualquer build retido e o reinicia.

    Existe para o caso que a automação não cobre de propósito -- voltar MAIS DE UM
    passo. O rollback automático vai um passo, porque todos os nós começaram o run
    iguais e um passo é uma resposta sem ambiguidade; escolher um build arbitrário é uma
    decisão humana.
    """
    from ops import confirm

    topology = configmod.discover(client, settings)
    with Helper(client, settings, topology) as helper:
        pipe = Pipeline(client, settings, args, helper, run_id_now())
        build_id = args.build
        if not helper.read(f"{topology.state_dir}/builds/{build_id}/manifest.txt").strip():
            raise PreconditionError(f"o build {build_id} não está no store")
        confirm(args, f"pinar {args.node} em {build_id} e reiniciar")
        pipe.pin_via_helper(args.node, build_id)
        if not client.dry_run:
            client.restart(pipe.name_of(args.node), seconds=120)
        print(f"{args.node} agora em {build_id}")
        return 0


def cmd_builds(client, settings, args):
    """O store: o que existe, o que está promovido, quem referencia o quê."""
    topology = configmod.discover(client, settings)
    with Helper(client, settings, topology) as helper:
        state = topology.state_dir
        _, out = helper.sh(
            f"for d in {state}/builds/b*; do [ -d \"$d\" ] || continue; "
            "printf '%s|%s|%s\\n' \"$(basename \"$d\")\" "
            "\"$(sed -n 's/^built-at=//p' \"$d/manifest.txt\" 2>/dev/null | head -1)\" "
            "\"$([ -f \"$d/PROMOTED\" ] && echo promoted || echo -)\"; done"
        )
        rows = []
        for line in out.splitlines():
            parts = line.strip().split("|")
            if len(parts) == 3 and parts[0].startswith("b"):
                rows.append((parts[0], parts[2], parts[1]))
        print(summarize(rows) if rows else "o store está vazio")
        return 0


# As tabelas em que um nó REIVINDICA algo -- (tabela, coluna). Só estas são apagadas:
# são estado vivo, e um nó que não existe mais não pode continuar reivindicando nada.
# `matches` NÃO está aqui de propósito: é histórico, e apagar histórico não é "esquecer
# um nó", é perder dado. O comando conta essas linhas e diz o que deixou para trás.
FORGET_CLAIMS = (
    ("network_nodes", "node_id"),
    ("node_drains", "node_id"),
    ("network_leases", "holder"),
    ("world_placements", "node_id"),
)
FORGET_KEPT = (
    ("matches", "node_id"),
    ("network_messages", "target_node"),
)


def cmd_forget(client, settings, args):
    """Apaga do BANCO um nó que já foi removido de verdade.

    A remoção FÍSICA de um nó continua manual, e por um motivo que não vai mudar: as
    portas saem do ÍNDICE em SX_NODES (base + índice), então tirar um nó do meio faz
    cada nó seguinte atender na porta do vizinho. `scale down` estaciona a cauda por
    isso. O que faltava era a outra metade -- o banco continua acreditando no nó, a
    linha em `network_nodes` nunca expira (nada dá DELETE nela; ela só vira DOWN), e o
    `status` fica em FAIL para sempre. Um FAIL permanente é pior do que nenhum: é o
    que faz o `pipeline deploy` recusar a subir, e é o que treina o operador a ignorar
    o vermelho.

    As três travas existem porque um DELETE no nó errado é grave: o próprio
    DrainCoordinator documenta que um `DELETE FROM network_nodes` de um nó VIVO faz o
    drain dele ser varrido e a lease ser entregue a outro por baixo dos pés.
    """
    from ops import confirm

    topology = configmod.discover(client, settings)
    node = args.node

    if node in topology.nodes or node == "proxy":
        raise PreconditionError(
            f"'{node}' ainda está na frota (SX_NODES={' '.join(topology.nodes)}):"
            " esqueça só o que já foi removido de verdade"
        )
    entry = topology.containers.get(f"{settings.prefix}{node}")
    if entry and entry.get("State") == "running":
        raise PreconditionError(f"o container {settings.prefix}{node} está NO AR; pare-o antes")

    # Em dry-run o `exec_run` devolve (0, "") sem chamar nada: toda LEITURA vira "sem
    # linhas", que é indistinguível de "o nó não está lá". Decidir a partir disso faria
    # o `-n` anunciar "nada a fazer" justamente para um nó que tem linha -- a única
    # resposta que o operador não pode receber de um dry-run. Então aqui ele não decide:
    # diz o que faria e sai.
    if client.dry_run:
        print(f"[dry-run] leituras do banco não são executadas; não dá para conferir '{node}' daqui")
        for table, column in FORGET_CLAIMS:
            print(f"  apagaria de {table} onde {column} = '{node}'")
        return 0

    registry = Registry(client, settings)
    row = registry.node(node)
    if row is None and registry.query("SELECT 1;") is None:
        raise PreconditionError("banco inalcançável")
    if row is None:
        print(f"{node} já não existe no banco; nada a fazer")
        return 0
    age = int(row.get("age") or 0) // 1000
    if row.get("state", "").upper() == "UP" and age < 60:
        raise PreconditionError(
            f"'{node}' bateu heartbeat há {age}s -- isso é um nó VIVO, não um removido"
        )

    counts = [(t, c, registry.count(t, c, node)) for t, c in FORGET_CLAIMS]
    kept = [(t, c, registry.count(t, c, node)) for t, c in FORGET_KEPT]
    print(f"{node}: state={row.get('state')} último heartbeat há {age}s")
    for table, _, total in counts:
        print(f"  apagar   {table}: {'tabela ausente' if total is None else total} linha(s)")
    for table, _, total in kept:
        if total:
            print(f"  manter   {table}: {total} linha(s) (histórico, fica)")

    if all(not total for _, _, total in counts):
        print("nada a apagar")
        return 0
    confirm(args, f"esquecer o nó '{node}' no banco")
    if client.dry_run:
        return 0

    for table, column, total in counts:
        if not total:
            continue
        if registry.execute(f"DELETE FROM {table} WHERE {column} = '{node}';"):
            print(f"  {table}: {total} linha(s) apagada(s)")
        else:
            raise RemoteError(f"falha ao apagar de {table}")
    print(f"{node} esquecido")
    return 0
