"""`status` -- a validação da rede. Uma checagem, um nome estável, PASS/FAIL/SKIP.

Escrito para sobreviver a mudanças de topologia. Nada aqui supõe o container `netns`
(que já foi removido), nem a base de portas 8800, nem o nome do container do banco:
tudo isso é lido do stack em execução. Uma checagem que não tem como ser respondida
naquela topologia devolve SKIP -- nunca FAIL -- para que `status` verde continue
significando "a rede está boa" em vez de "a rede é como era em agosto".
"""

import json
import re
import socket
import time

import config as configmod
from util import (
    api_port,
    handshake,
    human,
    listening_ports,
    parse_row,
    started_epoch,
    varint,
)

PASS, FAIL, SKIP = "PASS", "FAIL", "SKIP"

# Ruído conhecido no boot que NÃO é um problema. Versionado aqui de propósito: uma
# allowlist no código é revisável em diff; um `grep -v` na cabeça de quem opera não.
BOOT_NOISE = (
    # Multiverse reclamando do spawn de um mundo gerado -- o core reposiciona o
    # jogador por conta própria (SafeSpawn), então isto é barulho de plugin.
    re.compile(r"\[Multiverse-Core\] Safe spawn NOT found"),
    # Shutdown: o pool do FancyHolograms já foi fechado quando o disable roda.
    re.compile(r"Error occurred while disabling FancyHolograms"),
    re.compile(r"RejectedExecutionException: Task java\.util\.concurrent\.FutureTask"),
    # Cliente que caiu/fechou o jogo. Falha do outro lado do socket, não do servidor.
    re.compile(r"\[connected player\].*(read timed out|Connection reset by peer)"),
    re.compile(r"io\.netty\.channel\.unix\.Errors\$NativeIoException: (recvAddress|readAddress)"),
    # --- vistos ao vivo, todos benignos, todos pré-existentes ------------------
    # Entram AQUI e não numa segunda lista: uma allowlist só é auditável enquanto for
    # uma. E entram nomeados porque, sem isso, o `boot.clean` acusa cinco falsos a cada
    # boot e um check que sempre falha deixa de ser lido -- que é o mesmo que não ter.
    #
    # Multiverse tentando autoload de um mundo temporário que a limpeza de boot já
    # apagou. O mundo é descartável por definição (worldRoot()/temp).
    re.compile(r"\[Multiverse-Core\] Failed to autoload world sexidium_temp"),
    re.compile(r"WORLD_FOLDER_INVALID"),
    # FancyHolograms sem seção de hologramas num nó que não hospeda nenhum.
    re.compile(r"\[FancyHolograms\] No holograms section found in config"),
    # SkinsRestorer em modo proxy sem storage próprio: os skins vêm do MineSkin/Mojang
    # e a base é a do Sexidium, então este aviso descreve a configuração pretendida.
    re.compile(r"\[SkinsRestorer\].*(database storage is not set up|without API key)"),
    # BetterHud desabilitado de propósito enquanto o overlay de shader não cobre o pin
    # (F62). `betterhud.enabled` já governa isso do lado do Sexidium.
    re.compile(r"\[BetterHud\] Plugin disabled\."),
    # Avisos do próprio JDK sobre Unsafe/native access, emitidos por dependências.
    re.compile(r"WARNING: .*(sun\.misc\.Unsafe|restricted method|native access)"),
    # A adoção de um mundo do pool caindo de rename(2) para cópia. O rename falha com
    # EXDEV porque pool e experiences são MOUNTS diferentes do mesmo device, e a queda
    # para cópia é a rota projetada -- `stageFolder` loga isso em INFO e segue. Entra
    # aqui só porque a linha carrega o nome da exceção. A regex casa "copying instead"
    # de propósito: se a CÓPIA falhar, a mensagem é outra ("generating instead", em
    # WARNING) e continua aparecendo.
    re.compile(r"Could not rename .*; copying instead"),
    # FancyNpcs pedindo à Mojang o UUID de um nick que não é premium. A rede roda
    # offline-mode por decisão (o proxy é quem autentica), então esta busca vai dar 404
    # para sempre, para todo nick cracked -- e os skins vêm do SkinsRestorer, não daqui.
    re.compile(r"\[UUIDFetcher\].*Could not fetch UUID for name"),
    re.compile(r"de\.oliver\.fancynpcs\.api\.skins\.SkinLoadException"),
)

# DELIBERADAMENTE fora da allowlist, para quem for tentado a silenciar depois:
# `[server connection] <jogador> -> <nó>: read timed out` é o proxy vendo um BACKEND
# sumir por baixo dele. Foi o que denunciou os dois SIGSEGV de 16/08 (08:15 worker-2,
# 08:29 worker-1). O primo dele, `[connected player] ...: read timed out`, é o cliente
# caindo e esse sim está na lista acima. Confundir os dois troca o único sinal barato
# de "um nó morreu" por silêncio.


def _noise(line):
    return any(pattern.search(line) for pattern in BOOT_NOISE)


def _read_varint(sock):
    result = shift = 0
    while True:
        chunk = sock.recv(1)
        if not chunk:
            raise OSError("conexão fechada durante o handshake")
        byte = chunk[0]
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result
        shift += 7


def mc_ping(host, port, protocol, timeout=10):
    """Server list ping: exatamente o que o cliente faz antes de conectar."""
    with socket.create_connection((host, port), timeout) as sock:
        sock.sendall(handshake(host, port, protocol))
        sock.sendall(varint(1) + b"\x00")  # status request
        _read_varint(sock)  # tamanho total
        _read_varint(sock)  # packet id
        length = _read_varint(sock)
        data = b""
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                break
            data += chunk
        return json.loads(data.decode("utf-8"))


class Status:
    def __init__(self, client, settings):
        self.client = client
        self.settings = settings
        self.topology = configmod.discover(client, settings)
        self.rows = []

    def add(self, name, state, detail=""):
        self.rows.append((name, state, detail))
        return state

    def name_of(self, node):
        return f"{self.settings.prefix}{node}"

    def running(self, node):
        entry = self.topology.containers.get(self.name_of(node))
        return bool(entry) and entry.get("State") == "running"

    # --- 1 ---------------------------------------------------------------------
    def containers_running(self):
        missing, down = [], []
        for node in list(self.topology.nodes) + ["proxy"]:
            entry = self.topology.containers.get(self.name_of(node))
            if not entry:
                missing.append(node)
            elif entry.get("State") != "running":
                down.append(f"{node}={entry.get('State')}")
        init = self.topology.containers.get(self.name_of("init"))
        # `Exited (0)` é o estado CORRETO do init: ele provisiona e sai. Um init
        # *running* significa provisionamento em curso, não saúde.
        init_state = (init or {}).get("Status", "ausente")
        if missing or down:
            return self.add(
                "containers.running",
                FAIL,
                f"ausentes={missing or '-'} parados={down or '-'} | init: {init_state}",
            )
        return self.add(
            "containers.running",
            PASS,
            f"{len(self.topology.nodes)} nós + proxy up | init: {init_state}",
        )

    # --- 2 ---------------------------------------------------------------------
    def provision_stamp(self):
        """O carimbo descreve o ALVO, não o estado -- e isso mudou o que ele prova.

        Enquanto existia um jar só na árvore compartilhada, `plugin-sha256` era uma
        verdade sobre a rede INTEIRA e comparar o arquivo com ele pegava o caso
        silencioso ("um nó não reiniciou e segue no jar velho"). Com o store versionado
        não há mais esse arquivo: cada nó resolve o SEU build, e durante um rolling
        update eles são deliberadamente diferentes.

        Então este check voltou a fazer só o que ainda pode fazer honestamente -- o
        carimbo existe, não há lápide, e a lista de nós bate. A pergunta "quem está
        rodando o quê" passou para build.pins, que é onde ela agora tem resposta.
        """
        host = next((n for n in self.topology.nodes if self.running(n)), None)
        if not host:
            return self.add("provision.stamp", SKIP, "nenhum nó rodando para ler o carimbo")
        state = self.topology.state_dir
        code, out = self.client.sh(
            self.name_of(host),
            f"cat {state}/.provisioned 2>/dev/null; "
            f"if test -f {state}/.provision-failed; then echo 'TOMBSTONE'; fi",
        )
        if code != 0 or "provisioned-at" not in out:
            return self.add("provision.stamp", FAIL, f"carimbo {state}/.provisioned ausente")
        # O exec roda com TTY, então cada linha vem com CR -- que entraria no valor
        # e faria `nodes` nunca bater com SX_NODES.
        stamped = {}
        for line in out.splitlines():
            key, sep, value = line.strip().partition("=")
            if sep and key.isascii() and " " not in key:
                stamped[key] = value.strip()
        problems = []
        if "TOMBSTONE" in out:
            problems.append("lápide .provision-failed (o último init falhou)")
        if stamped.get("nodes", "").split() != list(self.topology.nodes):
            problems.append(f"nodes={stamped.get('nodes')!r} ≠ SX_NODES")
        if problems:
            return self.add("provision.stamp", FAIL, "; ".join(problems))
        staged = stamped.get("plugin-build", "?")
        return self.add(
            "provision.stamp", PASS, f"{stamped.get('provisioned-at', '?')} · alvo {staged}"
        )

    # --- 2b --------------------------------------------------------------------
    def build_pins(self):
        """Cada nó resolve um jar, e QUAL. É o check que a pergunta antiga virou.

        Duas coisas separadas, e a distinção importa:
          FAIL   um pin que não resolve. Um symlink pendurado faz o nó subir SEM o
                 plugin: container Running, `Done (` no log, e servindo nada.
          aviso  builds diferentes entre nós. É o estado NORMAL no meio de um rolling
                 update e um FAIL aqui transformaria a operação correta em alarme.
        """
        nodes = [n for n in self.topology.nodes if self.running(n)]
        if not nodes:
            return self.add("build.pins", SKIP, "nenhum nó rodando")
        seen, broken = {}, []
        for node in nodes:
            code, out = self.client.sh(
                self.name_of(node),
                f"sed -n 's/^build=//p' {self.topology.network_dir}/{node}/sexidium-build.pin "
                "2>/dev/null | head -1; "
                # `ls`, não `test -e`: o glob não é citado, e com DOIS jars casando o
                # `test` recebe três argumentos, morre em "unexpected operator" e o nó com
                # plugin A MAIS era reportado como "nó sem plugin" -- reprovando build.pins
                # e, por ele, o preflight de todo rolling update. `ls` aceita N e só falha
                # quando não casa nada, que é a pergunta real.
                f"ls {self.topology.network_dir}/{node}/pluginjars/Sexidium-Paper-*.jar "
                ">/dev/null 2>&1 || echo BROKEN",
            )
            text = out if code == 0 else ""
            if "BROKEN" in text:
                broken.append(node)
            build = next(
                (l.strip() for l in text.splitlines() if l.strip().startswith("b")), "<sem pin>"
            )
            seen.setdefault(build, []).append(node)
        if broken:
            return self.add(
                "build.pins", FAIL, f"pin que não resolve em: {', '.join(broken)} (nó sem plugin)"
            )
        if len(seen) == 1:
            return self.add("build.pins", PASS, f"os {len(nodes)} nós no build {next(iter(seen))}")
        detail = " · ".join(f"{b}: {','.join(ns)}" for b, ns in sorted(seen.items()))
        return self.add("build.pins", PASS, f"builds MISTOS (rolling update em curso?) — {detail}")

    # --- 3 e 4 -----------------------------------------------------------------
    def boot(self):
        done, dirty = [], []
        for node in list(self.topology.nodes) + ["proxy"]:
            if not self.running(node):
                continue
            # Só o log DESTE boot: um tail fixo atravessa reinícios e acusaria o
            # shutdown de ontem como erro de hoje.
            since = started_epoch(self.client.inspect(self.name_of(node)))
            text = self.client.logs(self.name_of(node), tail="2000", since=since)
            if "Done (" not in text:
                done.append(node)
            errors = [
                line
                for line in text.splitlines()
                if ("ERROR" in line or "Exception" in line) and not _noise(line)
            ]
            if errors:
                dirty.append(f"{node}:{len(errors)}")
        self.add(
            "boot.done",
            FAIL if done else PASS,
            f"sem 'Done (': {', '.join(done)}" if done else "todos os nós concluíram o boot",
        )
        self.add(
            "boot.clean",
            FAIL if dirty else PASS,
            f"erros no log: {', '.join(dirty)}" if dirty else "nenhum erro fora da allowlist",
        )

    # --- 5 ---------------------------------------------------------------------
    def ports_listening(self):
        bad = []
        for index, node in enumerate(self.topology.nodes):
            if not self.running(node):
                continue
            code, out = self.client.sh(
                self.name_of(node), "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"
            )
            ports = listening_ports(out)
            want = {
                self.topology.port_base + index,
                api_port(self.topology.nodes, node, self.topology.api_base, self.topology.api_stride),
            }
            absent = {p for p in want if p and p not in ports}
            if absent:
                bad.append(f"{node} sem {sorted(absent)}")
        if not bad:
            return self.add("ports.listening", PASS, "cada nó escuta a sua porta de jogo + API")
        return self.add("ports.listening", FAIL, "; ".join(bad))

    # --- 6 ---------------------------------------------------------------------
    def ports_published(self):
        """Alguém publica a 25565 -- quem, não importa (era o `netns`, hoje é o proxy)."""
        port = self.topology.proxy_port
        for name, entry in self.topology.containers.items():
            for mapping in entry.get("Ports") or []:
                if mapping.get("PublicPort") == port:
                    return self.add("ports.published", PASS, f"{port} publicada por {name}")
        return self.add("ports.published", FAIL, f"ninguém publica a {port}")

    # --- 7 e 8 -----------------------------------------------------------------
    def database(self):
        try:
            from ops import db_container

            name = db_container(self.client, self.settings)
        except Exception:  # noqa: BLE001 -- ausência do banco é SKIP, não crash
            self.add("db.schema", SKIP, "container do banco não encontrado")
            self.add("db.nodes", SKIP, "container do banco não encontrado")
            return
        info = self.client.inspect(name) or {}
        env = dict(
            entry.partition("=")[::2] for entry in info.get("Config", {}).get("Env", [])
        )
        root = env.get("MARIADB_ROOT_PASSWORD") or env.get("MYSQL_ROOT_PASSWORD")
        if not root:
            self.add("db.schema", SKIP, "senha de root indisponível")
            self.add("db.nodes", SKIP, "senha de root indisponível")
            return
        creds = [f"MYSQL_PWD={root}"]
        code, out = self.client.exec_run(
            name,
            [
                "mariadb", "-uroot", "-N", "-e",
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='sexidium';",
            ],
            env=creds,
        )
        tables = next((int(t) for t in re.findall(r"\d+", out)), 0) if code == 0 else 0
        self.add(
            "db.schema",
            PASS if tables > 0 else FAIL,
            f"{tables} tabelas em `sexidium`" if tables else "schema vazio (SchemaMigrator não rodou)",
        )
        # A idade do heartbeat é calculada PELO BANCO: `heartbeat_at` é epoch em
        # milissegundos (BIGINT, não DATETIME) e comparar com o relógio desta
        # máquina transformaria qualquer skew de horário num falso alarme.
        code, out = self.client.exec_run(
            name,
            [
                "mariadb", "-uroot", "sexidium", "-N", "-e",
                "SELECT node_id, state, UNIX_TIMESTAMP()*1000 - heartbeat_at FROM network_nodes;",
            ],
            env=creds,
        )
        if code != 0:
            return self.add("db.nodes", SKIP, "tabela network_nodes ausente nesta versão")
        stale, seen = [], 0
        for line in out.splitlines():
            fields = parse_row(line)
            if len(fields) < 3 or not fields[-1].lstrip("-").isdigit():
                continue
            seen += 1
            age = int(fields[-1]) // 1000
            if age > 30 or fields[1].upper() != "UP":
                stale.append(f"{fields[0]}={fields[1]}+{age}s")
        if seen < len(self.topology.nodes):
            return self.add("db.nodes", FAIL, f"{seen} de {len(self.topology.nodes)} nós registrados")
        return self.add(
            "db.nodes",
            FAIL if stale else PASS,
            f"heartbeat velho: {', '.join(stale)}" if stale else f"{seen} nós com heartbeat < 30s",
        )

    # --- 9 ---------------------------------------------------------------------
    def api_health(self):
        """Plugin VIVO, não JVM viva: a API só responde se o core subiu inteiro.

        Sem `curl` na imagem (temurin cru) -- daí o /dev/tcp do bash, que é o
        interpretador do entrypoint dos nós.
        """
        node = "lobby" if "lobby" in self.topology.nodes else self.topology.nodes[0]
        if not self.running(node):
            return self.add("api.health", SKIP, f"{node} não está rodando")
        port = api_port(self.topology.nodes, node, self.topology.api_base, self.topology.api_stride)
        code, out = self.client.exec_run(
            self.name_of(node),
            [
                "bash", "-c",
                f"exec 3<>/dev/tcp/127.0.0.1/{port} && "
                "printf 'GET /rank HTTP/1.0\\r\\n\\r\\n' >&3 && head -c 120 <&3",
            ],
        )
        if code == 0 and "HTTP/1." in out:
            return self.add("api.health", PASS, f"{node}:{port} responde /rank")
        return self.add("api.health", FAIL, f"{node}:{port} não respondeu HTTP")

    # --- 10 --------------------------------------------------------------------
    def mc_ping(self):
        host = self.settings.mc_host
        if not host:
            return self.add("mc.ping", SKIP, "SX_MC_HOST não resolvido")
        try:
            data = mc_ping(host, self.settings.mc_port, configmod.MC_PROTOCOL)
        except OSError as exc:
            return self.add("mc.ping", FAIL, f"{host}:{self.settings.mc_port} {exc}")
        version = (data.get("version") or {}).get("name", "?")
        players = (data.get("players") or {}).get("online", "?")
        return self.add("mc.ping", PASS, f"{version}, {players} online")

    # --- 11 --------------------------------------------------------------------
    def memory(self):
        tight = []
        for node in list(self.topology.nodes) + ["proxy"]:
            if not self.running(node):
                continue
            stats = self.client.docker_json(
                "GET", f"/containers/{self.name_of(node)}/stats?stream=false"
            )
            memory = (stats or {}).get("memory_stats") or {}
            used, limit = memory.get("usage"), memory.get("limit")
            if not used or not limit:
                continue
            if used / limit > 0.85:
                tight.append(f"{node} {human(used)}/{human(limit)}")
        return self.add(
            "memory.headroom",
            FAIL if tight else PASS,
            f"acima de 85%: {', '.join(tight)}" if tight else "todos abaixo de 85% do mem_limit",
        )

    # --- 13 --------------------------------------------------------------------
    def host_thp(self):
        """Transparent hugepages do HOST, lido de dentro de um nó.

        Sysfs não é namespaced: o que o container lê aqui é o valor do kernel da
        máquina, um só para todo mundo. Vale a pena checar porque `always` é a
        principal suspeita dos SIGSEGV que derrubaram os três nós dentro do GC, e
        porque o ajuste vive num container (`sexidium-host-tuning`) -- o umbrelOS
        roda raiz em overlay com slots A/B, então /etc não sobrevive a um update e
        não há unit systemd que segure isto.

        Checa o RESULTADO, não o mecanismo: se alguém ajustar o kernel por fora, ou
        trocar o container por uma unit de verdade num host que permita, isto
        continua correto. O que não pode passar despercebido é o valor voltar para
        `always` e os crashes voltarem junto, sem sinal nenhum.
        """
        for node in self.topology.nodes:
            if not self.running(node):
                continue
            code, out = self.client.sh(
                self.name_of(node), "cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null"
            )
            # O formato é `always [madvise] never`: o valor ativo vem entre colchetes.
            active = ""
            if "[" in out and "]" in out:
                active = out[out.index("[") + 1 : out.index("]")].strip()
            if not active:
                return self.add("host.thp", SKIP, "não consegui ler o sysfs do host")
            if active == "always":
                return self.add(
                    "host.thp",
                    FAIL,
                    "transparent_hugepage=always (suspeito nos SIGSEGV do GC);"
                    " sexidium-host-tuning está no ar?",
                )
            return self.add("host.thp", PASS, f"transparent_hugepage={active}")
        return self.add("host.thp", SKIP, "nenhum nó no ar para ler o sysfs")

    def run(self):
        self.rows = []
        self.containers_running()
        self.provision_stamp()
        self.build_pins()
        self.boot()
        self.ports_listening()
        self.ports_published()
        self.database()
        self.api_health()
        self.mc_ping()
        self.memory()
        self.host_thp()
        return self.rows


def cmd_status(client, settings, args):
    from util import as_json, summarize

    deadline = time.time() + max(args.wait, 0)
    while True:
        rows = Status(client, settings).run()
        failed = [r for r in rows if r[1] == FAIL]
        if not failed or time.time() >= deadline:
            break
        print(f"  aguardando convergir ({len(failed)} FAIL)…")
        time.sleep(10)
    print(as_json(rows) if args.json else summarize(rows))
    return 1 if failed else 0
