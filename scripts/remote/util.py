"""Funções PURAS do CLI remoto: nada aqui abre socket, arquivo ou processo.

É de propósito. Tudo o que dá para testar sem uma stack de pé mora neste arquivo
(scripts/test/unit/remote.bats o exercita); o resto dos módulos só orquestra I/O.
"""

import hashlib
import json
import os
import re

# --- erros --------------------------------------------------------------------
# Um código de saída por CLASSE de problema, não por mensagem: quem chama o CLI de
# um cron/script precisa distinguir "a rede está ruim" (4) de "a chave expirou" (3)
# de "faltou uma pré-condição" (5) sem casar strings de log.


class RemoteError(Exception):
    """Falha esperada: o CLI imprime a mensagem e sai com self.code (sem traceback)."""

    code = 1

    def __init__(self, message, code=None):
        super().__init__(message)
        if code is not None:
            self.code = code


class UsageError(RemoteError):
    code = 2


class AuthError(RemoteError):
    code = 3


class UnreachableError(RemoteError):
    code = 4


class PreconditionError(RemoteError):
    code = 5


# --- stream do Docker ---------------------------------------------------------

_STREAM_PREFIXES = (0, 1, 2)


def demux(payload):
    """Converte o corpo de /logs (ou /exec/start) sem TTY em texto.

    Sem TTY o Docker multiplexa stdout/stderr em frames de 8 bytes de cabeçalho
    (byte 0 = fluxo, bytes 4-7 = tamanho big-endian). Com TTY o corpo já é texto
    puro. O CLI recebe os dois casos (o `logs` de um container que não pediu TTY
    vem multiplexado), então a detecção é pelo primeiro byte + consistência dos
    tamanhos: um texto que por acaso comece com \\x01 mas não se decomponha em
    frames coerentes cai no caminho literal em vez de virar lixo.
    """
    if not payload:
        return ""
    if payload[0] not in _STREAM_PREFIXES or len(payload) < 8:
        return payload.decode("utf-8", "replace")
    chunks = []
    index = 0
    while index + 8 <= len(payload):
        if payload[index] not in _STREAM_PREFIXES:
            return payload.decode("utf-8", "replace")
        length = int.from_bytes(payload[index + 4 : index + 8], "big")
        chunks.append(payload[index + 8 : index + 8 + length])
        index += 8 + length
    if index != len(payload):  # sobrou byte: não era um stream multiplexado
        return payload.decode("utf-8", "replace")
    return b"".join(chunks).decode("utf-8", "replace")


# --- seleção de arquivos do sync ----------------------------------------------
# O que NUNCA sobe para o volume. Saídas de build e dependências baixáveis são
# reconstruídas lá dentro (e `build/` local é de outra arquitetura de cache);
# `run/` é DADO do servidor -- mundos, config editada à mão -- e subir o `run/`
# local por cima do remoto apagaria o mundo dos jogadores. `backup-<host>/` é o
# dump bruto extraído do próprio host (3,5 GB, com segredos em texto claro):
# devolvê-lo ao volume de build só queima disco e reexpõe o que já vazou.

TOP_SKIP = {".git", "build", "run", "test", "context", "tools", "UI", ".gradle", ".idea"}
PART_SKIP = {"node_modules", ".gradle", "build", "__pycache__", ".pytest_cache"}


def excluded(relpath):
    """True quando o caminho (relativo à raiz do repo, com '/') não deve subir."""
    parts = [p for p in relpath.split("/") if p and p != "."]
    if not parts:
        return False
    if parts[0] in TOP_SKIP:
        return True
    for part in parts:
        if part in PART_SKIP:
            return True
        # Virtualenvs do toolchain de arte: .venv-rembg sozinho tem 873 MB.
        if part.startswith(".venv"):
            return True
    if parts[0].startswith("backup-"):
        return True
    return False


# --- segredos -----------------------------------------------------------------


def fingerprint(value):
    """Identidade estável de um segredo sem revelá-lo (para diffs e conferência)."""
    return "sha256:" + hashlib.sha256(value.encode()).hexdigest()[:12]


def redact(text, secrets):
    """Substitui valores sensíveis antes de qualquer impressão.

    O corpo de erro do Portainer ecoa o que foi enviado -- um deploy de stack que
    falha devolve o env inteiro, senha do banco inclusa. Todo texto que o CLI
    imprime passa por aqui.
    """
    if not text:
        return text
    out = text
    for secret in secrets:
        if secret and len(secret) >= 8:
            out = out.replace(secret, "«redacted»")
    return out


# --- protocolo Minecraft (server list ping) -----------------------------------


def varint(value):
    """VarInt do protocolo Minecraft: 7 bits por byte, MSB = "tem mais"."""
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def handshake(host, port, protocol):
    """Pacote de handshake (next state = 1, status) já com o prefixo de tamanho."""
    body = (
        b"\x00"
        + varint(protocol)
        + varint(len(host.encode()))
        + host.encode()
        + port.to_bytes(2, "big")
        + varint(1)
    )
    return varint(len(body)) + body


# --- topologia ----------------------------------------------------------------
# Derivada, nunca escrita à mão: a lista de nós e as portas saem do ambiente do
# stack em execução. Hardcodar "sexidium-netns-1" ou a base 8800 quebraria o CLI
# no dia em que a stack mudar de forma -- e ela já mudou uma vez.


def restart_order(nodes):
    """Ordem de reinício: último backend primeiro, proxy por último.

    O proxy é reiniciado por último porque, enquanto ele está vivo, quem cai é
    devolvido ao lobby; derrubá-lo primeiro desconecta a rede inteira de uma vez.
    Os backends vão de trás para a frente para o lobby (destino do fallback)
    ficar indisponível o mínimo possível.
    """
    return list(reversed(list(nodes))) + ["proxy"]


def shutdown_order(nodes):
    """Ordem de parada: proxy primeiro, para ninguém ficar preso num nó salvando mundo."""
    return ["proxy"] + list(nodes)


def boot_order(nodes):
    """Ordem de subida: backends antes do proxy (senão o join dá 'Unable to connect')."""
    return list(nodes) + ["proxy"]


def api_port(nodes, node, base, stride):
    """Porta da API HTTP daquele nó -- pelo ÍNDICE na lista, como o provisionador faz.

    Devolve None para um nó fora da lista (o proxy, por exemplo, que usa 8787).
    """
    nodes = list(nodes)
    if node not in nodes:
        return None
    return int(base) + nodes.index(node) * int(stride)


def listening_ports(text):
    """Portas em LISTEN a partir do conteúdo de /proc/net/tcp{,6}.

    Lido de DENTRO de cada container em vez de perguntar ao Docker: é a única
    formulação que continua correta com um namespace de rede por container (cada
    um vê só o que ele mesmo abriu) e com o namespace único que a stack usava
    antes (todos veem a mesma tabela, um superconjunto). Estado 0A = TCP_LISTEN;
    o endereço local vem como HEX:HEX.
    """
    ports = set()
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 4 or ":" not in fields[1]:
            continue
        if fields[3].upper() != "0A":
            continue
        try:
            ports.add(int(fields[1].rsplit(":", 1)[1], 16))
        except ValueError:
            continue
    return ports


def started_epoch(info):
    """`State.StartedAt` do inspect -> epoch em segundos (0 se indisponível).

    Serve para pedir ao Docker só o log DESTE boot. Sem isso, um tail fixo de N
    linhas atravessa reinícios e o `boot.clean` acusa o erro de um shutdown de
    ontem como se fosse de agora.
    """
    import datetime

    raw = ((info or {}).get("State") or {}).get("StartedAt") or ""
    if not raw or raw.startswith("0001"):
        return 0
    text = raw.replace("Z", "+00:00")
    # O Docker devolve nanossegundos; datetime aceita no máximo microssegundos.
    if "." in text:
        head, _, tail = text.partition(".")
        index = 0
        while index < len(tail) and tail[index].isdigit():
            index += 1
        text = f"{head}.{tail[:index][:6]}{tail[index:]}"
    try:
        return int(datetime.datetime.fromisoformat(text).timestamp())
    except ValueError:
        return 0


def parse_row(line):
    """Uma linha de saída do cliente mariadb (com ou sem as bordas de tabela) -> campos."""
    text = line.strip().strip("\r")
    if not text or set(text) <= set("+-| "):
        return []
    if "|" in text:
        return [cell.strip() for cell in text.strip("|").split("|")]
    return text.split("\t") if "\t" in text else text.split()


def parse_env_list(entries):
    """Config.Env do Docker (["K=V", …]) -> dict. Valores com '=' sobrevivem."""
    out = {}
    for entry in entries or []:
        key, sep, value = entry.partition("=")
        if sep:
            out[key] = value
    return out


def human(size):
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{size:.1f} GB"


def summarize(rows):
    """Tabela PASS/FAIL/SKIP em texto fixo, para o olho e para o grep."""
    width = max((len(r[0]) for r in rows), default=0)
    return "\n".join(f"  {name:<{width}}  {state:<4}  {detail}" for name, state, detail in rows)


def as_json(rows):
    return json.dumps(
        {"checks": [{"name": n, "state": s, "detail": d} for n, s, d in rows]},
        indent=2,
        ensure_ascii=False,
    )


# --- trace --------------------------------------------------------------------
# Same line format and same environment variable as the shell libraries
# (lib/core.sh: sx_trace), so scripts/test/run.sh's diff-and-restore machinery works
# on a pipeline run exactly as it does on a provision run. The pipeline is the piece
# whose ORDER matters most -- drain before pin, pin before restart, restart before
# verify -- and an ordering regression there is invisible until it costs a world save.


def trace(op):
    path = os.environ.get("SX_TRACE")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(op + "\n")


# --- port arithmetic ----------------------------------------------------------
# Pure reimplementations of velocity::node_port / node_api_base / node_pack_port
# (scripts/lib/velocity.sh). Two implementations of one rule is a liability, so
# scripts/test/unit/pipeline.bats invokes the SHELL functions and compares -- the two
# cannot drift without a red test. They are reimplemented rather than shelled out to
# because the CLI must be able to answer "what port will worker-4 get" before that
# node, or its container, exists.


def game_port(nodes, node, base):
    nodes = list(nodes)
    if node not in nodes:
        return None
    return int(base) + nodes.index(node)


def pack_port(nodes, node, base):
    nodes = list(nodes)
    if node not in nodes:
        return None
    return int(base) + nodes.index(node)


# --- journal ------------------------------------------------------------------
# Append-only JSON Lines, one object per event. Append-only is what makes a torn
# final line DETECTABLE (it is not valid JSON) and therefore discardable, which is the
# difference between "the run was interrupted" and "the run's state is unknown".

JOURNAL_EVENTS = ("begin", "ok", "fail", "skip")


def journal_line(run, stage, event, node=None, sub=None, detail=None):
    """One journal record, as the exact text that gets appended."""
    record = {"run": run, "stage": stage, "event": event}
    if node:
        record["node"] = node
    if sub:
        record["sub"] = sub
    if detail:
        record["detail"] = detail
    # Sorted keys and no spaces: the line is quoted into a shell command to append it,
    # and a stable serialisation keeps the pipeline golden trace comparable.
    return json.dumps(record, sort_keys=True, separators=(",", ":"))


def journal_key(record):
    return (record.get("stage", ""), record.get("node", ""), record.get("sub", ""))


def replay(text):
    """Journal text -> {done, pending, pins, run, bad}.

    `pending` is the resume point: the first (stage, node, sub) that began and never
    reported ok/fail. Every sub-stage is written to be idempotent and re-entrant, so
    resuming always means "run that sub-stage again from the top" -- which is why this
    only has to find WHERE, never HOW FAR IN.

    `pins` carries both build ids per node, from the PIN records. That is what makes a
    resume never have to guess: the journal says what the pin was and what it was
    being moved to, so a re-entered PIN can compare and skip, and a rollback knows its
    target even if the pin file is mid-write.
    """
    done, order, state, pins = set(), [], {}, {}
    run, bad = None, 0
    for line in (text or "").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except ValueError:
            # A torn final line is the NORMAL shape of an interrupted run, not a
            # corruption to escalate. Count it and move on.
            bad += 1
            continue
        if not isinstance(record, dict):
            bad += 1
            continue
        run = record.get("run") or run
        key = journal_key(record)
        if key not in state:
            order.append(key)
        state[key] = record.get("event")
        if state[key] in ("ok", "skip"):
            done.add(key)
        detail = record.get("detail") or {}
        if record.get("sub") == "pin" and record.get("node"):
            pins.setdefault(record["node"], {}).update(
                {k: v for k, v in detail.items() if k in ("from", "to")}
            )
    pending = next((k for k in order if state.get(k) == "begin"), None)
    return {"run": run, "done": done, "pending": pending, "pins": pins, "bad": bad}


# --- rollback triggers --------------------------------------------------------
# The single most important piece of judgement in the pipeline, and the one with the
# worst failure mode in the wrong direction: a FALSE rollback in production is worse
# than a slow one. Every trigger below is scoped to the node the run just repinned and
# to that node's own verify/battery/soak window; nothing observed on another node, or
# before that node's restart, can reach this function at all.

TRIGGERS = {
    "R1": "boot verification timed out",
    "R2": "crash loop (RestartCount climbed)",
    "R3": "the plugin threw during enable",
    "R4": "the node did not take the pinned build",
    "R5": "a battery assertion failed",
    "R6": "memory death (OOMKilled / exit 137 / OutOfMemoryError)",
    "R7": "heartbeat lost during soak",
}

# `Done (` is NOT sufficient on its own and the reason is on the record: Paper prints
# it even when a plugin threw during enable and was disabled -- observed live with
# BetterHud. The gate is the plugin saying it is ready, not the server saying it booted.
READY_MARKERS = ("SX-READY", "Network node '")
ENABLE_MARKER = "Enabling Sexidium"


def classify_rollback(evidence):
    """-> [(trigger-id, human detail), …]. Empty means: do not roll back.

    `evidence` is a plain dict so this is testable against synthetic fixtures for every
    trigger AND for every allowlisted noise line -- see scripts/test/unit/pipeline.bats,
    where the noise cases assert an EMPTY result.
    """
    # The sentinel a re-entered verify returns. Its evidence is not missing, it is
    # UNAVAILABLE-BY-DESIGN: the node passed in an earlier attempt of this same run and
    # has since been handed back to the network, so every field this function reads now
    # describes a serving node rather than a fresh boot. Re-deriving would read the
    # correct state as a defect. The journal's `ok` is the proof.
    if evidence.get("already_verified"):
        return []

    fired = []
    log = evidence.get("log") or ""

    if evidence.get("boot_timed_out"):
        fired.append(("R1", "no ready marker within the boot timeout"))

    before = evidence.get("restart_count_before")
    now = evidence.get("restart_count_now")
    if before is not None and now is not None and now > before:
        fired.append(("R2", f"RestartCount {before} -> {now}"))

    # Enable started and never finished. Per-node-identical code, so this is the shape
    # a bad build takes: the JVM is up, `Done (` is printed, and the plugin is dead.
    if ENABLE_MARKER in log and not any(marker in log for marker in READY_MARKERS):
        fired.append(("R3", "'Enabling Sexidium' with no readiness line after it"))

    pinned = evidence.get("pinned_build") or ""
    reported = evidence.get("plugin_version") or ""
    # Only when the node actually REPORTED a version. An empty column is a build that
    # predates this work, and "the JVM did not tell us" must degrade to SKIP -- never to
    # a rollback, or every deploy of a mixed fleet would roll itself back.
    if pinned and reported and pinned not in reported:
        fired.append(("R4", f"plugin_version {reported!r} does not carry pin {pinned!r}"))

    for name, state, detail in evidence.get("battery") or []:
        # SKIP is not FAIL. An assertion whose JVM-side half has not shipped yet must
        # never be able to trigger a rollback.
        if state == "FAIL":
            fired.append(("R5", f"{name}: {detail}"))

    # `java.lang.OutOfMemoryError`, QUALIFICADO, e nunca o sufixo solto.
    #
    # A JVM sempre nomeia o erro por inteiro -- `java.lang.OutOfMemoryError: Java heap
    # space` numa stack trace, e `Terminating due to java.lang.OutOfMemoryError: …` no
    # caminho do -XX:+ExitOnOutOfMemoryError. Já o SUFIXO aparece em texto que não é erro
    # nenhum: docker/node-entry.sh ecoa a linha de comando da JVM ao subir cada nó, e essa
    # linha contém a própria flag `-XX:+ExitOnOutOfMemoryError`. Casar o sufixo fazia o R6
    # disparar em TODO nó de TODA rolagem, sobre um nó saudável que tinha acabado de
    # passar a bateria inteira -- ou seja, o pipeline revertia sempre e não havia como um
    # deploy sem queda jamais concluir.
    if (
        evidence.get("oom_killed")
        or evidence.get("exit_code") == 137
        or "java.lang.OutOfMemoryError" in log
    ):
        fired.append(("R6", "container or heap ran out of memory"))

    age = evidence.get("heartbeat_age")
    # Only while the container is RUNNING: a stopped container has a stale heartbeat by
    # definition, and it is R2/R6's business, not this one's.
    if evidence.get("running") and age is not None and age > 30:
        fired.append(("R7", f"heartbeat {age}s old (node-timeout is 30s)"))

    return fired


def player_counts(nodes):
    """(rede, backends) a partir das linhas de network_nodes.

    NÃO some tudo. A linha do proxy conta a rede INTEIRA e cada backend conta os seus
    de novo, então um SUM ingênuo dá ~2x a realidade -- e um autoscaler alimentado com
    o dobro dos jogadores sobe nós que ninguém precisa até bater no teto de memória.
    """
    network = 0
    backends = 0
    for row in nodes:
        try:
            players = int(row.get("players") or 0)
        except ValueError:
            continue
        if (row.get("role") or "").lower() == "proxy":
            network = max(network, players)
        else:
            backends += players
    return network, backends


# --- capacity -----------------------------------------------------------------


def max_workers(mem_total, proxy_limit, lobby_limit, init_reserve, worker_limit, headroom=0.85):
    """How many workers this host can carry, in bytes throughout.

    Memory is the binding constraint here and CPU is free (24 vCPU, ~24% of one
    core-equivalent across six containers). The arithmetic is a function rather than a
    constant so `watch` can PRINT it: a ceiling nobody can see is a ceiling somebody
    will be surprised by.

    Never rounds up. Over-committing buys one more worker and risks a host-level OOM
    kill, which is SIGKILL -- no shutdown hook, no world save.
    """
    if not mem_total or not worker_limit:
        return 0
    usable = mem_total * headroom - proxy_limit - lobby_limit - init_reserve
    return max(0, int(usable // worker_limit))


def capacity_report(mem_total, committed, proxy_limit, lobby_limit, init_reserve, worker_limit):
    """The arithmetic, as text, for the `capacity.exhausted` alert.

    The alert states the three options a human actually has, because "cannot scale" on
    its own is a dead end and the reader is usually the person who can pick one.
    """
    ceiling = max_workers(mem_total, proxy_limit, lobby_limit, init_reserve, worker_limit)
    needed = committed + worker_limit + init_reserve
    usable = mem_total * 0.85
    return (
        f"MemTotal={human(mem_total)} usable@85%={human(usable)} · "
        f"committed now={human(committed)} + new worker {human(worker_limit)} + "
        f"init reserve {human(init_reserve)} = {human(needed)} "
        f"{'>' if needed > usable else '<='} {human(usable)}. "
        f"Uniform-size ceiling would be {ceiling} workers, but the deployed workers are "
        "larger than the autoscaled size, so this figure is what governs. "
        "Raise SX_MAX_WORKERS, lower an existing worker's mem_limit, or add a host."
    )


# --- compose surgery ----------------------------------------------------------
# Text, never a YAML round trip. docker/stack.sexidium.yml is 448 lines of
# load-bearing comments and anchors, and yaml.dump would destroy every one of them --
# the comments are where the reasons live, and the reasons are the file's real content.

WORKER_BLOCK_RE = re.compile(r"(?ms)^  (worker-\d+):\n(.*?)(?=^  [a-z#]|\Z)")


def compose_workers(text):
    return [m.group(1) for m in WORKER_BLOCK_RE.finditer(text)]


def add_worker_to_compose(text, name, index, mem_limit="3g", heap="-Xms1G -Xmx2G"):
    """Append one worker service. Strictly additive; four hunks, no more.

    The four:
      1. SX_NODES gains a name (APPEND ONLY -- the port arithmetic is positional in
         three places plus each node's persisted server.properties, so inserting or
         removing changes the identity of every later node with no rollback story).
      2. a new service block, cloned from the last worker.
      3. a top-level `volumes:` entry, external: true.
      4. the `init` service's mount list gains the volume.

    What it deliberately does NOT touch is `proxy.depends_on`. That is the single most
    important line in this function: depends_on only orders startup and the proxy does
    not need the worker (BackendDirectory picks it up from the DB registry within one
    5 s refresh), but editing it changes the proxy's config hash -- and a stack update
    runs with prune:true, so the proxy would be RECREATED and every player on the
    network disconnected. Omitting it is what makes scale-out zero-disconnect.
    """
    if f"SX_NODE: {name}\n" in text or f'container_name: sexidium-{name}\n' in text:
        raise PreconditionError(f"{name} is already in the compose file")
    workers = compose_workers(text)
    if not workers:
        raise PreconditionError("no worker-N service block to clone")
    last = workers[-1]

    # 1. SX_NODES
    text, n = re.subn(
        r'(?m)^(  SX_NODES: )"(.*)"$', lambda m: f'{m.group(1)}"{m.group(2)} {name}"', text, count=1
    )
    if not n:
        raise PreconditionError("could not find SX_NODES in the compose x-env block")

    # 2. the service block
    block = next(m for m in WORKER_BLOCK_RE.finditer(text) if m.group(1) == last)
    body = block.group(0)
    clone = body.replace(f"  {last}:", f"  {name}:", 1)
    clone = clone.replace(f"container_name: sexidium-{last}", f"container_name: sexidium-{name}")
    clone = clone.replace(f"SX_NODE: {last}", f"SX_NODE: {name}")
    clone = clone.replace(f"sexidium-{last}:/srv/nodes/{last}", f"sexidium-{name}:/srv/nodes/{name}")
    old_pack = pack_port(compose_nodes(text), last, compose_pack_base(text))
    new_pack = pack_port(compose_nodes(text), name, compose_pack_base(text))
    if old_pack and new_pack:
        clone = clone.replace(
            f'"127.0.0.1:{old_pack}:{old_pack}/tcp"', f'"127.0.0.1:{new_pack}:{new_pack}/tcp"'
        )
    # An autoscaled worker is sized to its MEASURED working set (2.0-2.4 GiB), not to
    # the 5g the hand-placed ones carry: at 5g each, two more workers do not fit on this
    # host at all. Per-service env, so the hand-placed ones are untouched.
    clone = re.sub(r"(?m)^    mem_limit: .*$", f"    mem_limit: {mem_limit}", clone)
    clone = clone.replace(
        f"      SX_NODE: {name}\n", f"      SX_NODE: {name}\n      SX_WORKER_MEMORY: \"{heap}\"\n"
    )
    text = text.replace(body, body + clone, 1)

    # 3. top-level volume
    text, n = re.subn(
        r"(?m)^(  sexidium-%s:\n    external: true\n)" % re.escape(last),
        lambda m: m.group(1) + f"  sexidium-{name}:\n    external: true\n",
        text,
        count=1,
    )
    if not n:
        raise PreconditionError(f"could not find the sexidium-{last} volume declaration")

    # 4. init's mount list. Constraint 6, and the failure it prevents is nasty: without
    # it provision.sh creates /srv/nodes/<name> inside the init container's OWN
    # filesystem, the real worker finds no sexidium-node.args, and it dies on a
    # precondition that names the wrong cause.
    text, n = re.subn(
        r"(?m)^(      - sexidium-%s:/srv/nodes/%s\n)(?=\s*restart: \"no\")"
        % (re.escape(last), re.escape(last)),
        lambda m: m.group(1) + f"      - sexidium-{name}:/srv/nodes/{name}\n",
        text,
        count=1,
    )
    if not n:
        raise PreconditionError("could not find the init service's node mount list")
    return text


def compose_nodes(text):
    match = re.search(r'(?m)^  SX_NODES: "(.*)"$', text)
    return match.group(1).split() if match else []


def compose_pack_base(text):
    match = re.search(r'(?m)^  SX_PACK_PORT_BASE: "(\d+)"$', text)
    return int(match.group(1)) if match else 26011


def compose_guard(before, after, name):
    """Every changed HUNK must fall inside one of the four allowlisted regions.

    This is the rail that actually enforces "never prune a live service". The generator
    being append-only is the intent; this is the check, and it is run against the
    DEPLOYED stack file (GET /api/stacks/<id>) immediately before the PUT -- so a stack
    that has drifted from the repo cannot be quietly overwritten by a scale-out, and an
    edit that reached a service it had no business touching is refused rather than
    deployed.

    Hunk-level rather than line-level on purpose: the new service block legitimately
    contains lines that appear nowhere else (its own mem_limit, its own heap), and a
    set-difference over lines would either reject those or have to allow any line
    containing the node name -- which is most of an arbitrary edit.
    """
    import difflib

    old_lines = before.splitlines()
    new_lines = after.splitlines()
    problems = []

    # The regions a scale-out is allowed to write, as (start, end) indices into `after`.
    allowed = []
    for index, line in enumerate(new_lines):
        if line.startswith("  SX_NODES:"):
            allowed.append((index, index + 1))
        if line.strip() == f"- sexidium-{name}:/srv/nodes/{name}":
            allowed.append((index, index + 1))
        if line == f"  sexidium-{name}:":
            allowed.append((index, index + 2))  # the declaration plus `external: true`
    block = re.search(r"(?m)^  %s:$" % re.escape(name), after)
    if block:
        start = after[: block.start()].count("\n")
        # Walk back over the blank line that separates service blocks: difflib anchors
        # the insertion there, not at the `  worker-N:` header, so a region that began
        # at the header would report its own hunk as out of bounds.
        while start > 0 and not new_lines[start - 1].strip():
            start -= 1
        end = after[: block.start()].count("\n")
        while end + 1 < len(new_lines) and (
            new_lines[end + 1].startswith("    ")
            or new_lines[end + 1].startswith("      ")
            or not new_lines[end + 1].strip()
        ):
            end += 1
        allowed.append((start, end + 1))

    def inside(lo, hi):
        return any(lo >= a and hi <= b for a, b in allowed)

    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, old_lines, new_lines).get_opcodes():
        if tag == "equal":
            continue
        if tag == "delete" or not inside(j1, j2):
            changed = (old_lines[i1:i2] or new_lines[j1:j2])[:2]
            problems.append(f"{tag} outside the allowed hunks: {' / '.join(c.strip() for c in changed)}")

    # Two invariants that are not about hunks at all, checked here because this is the
    # last gate before a PUT and both are catastrophic:
    if 'profiles: ["db"]' not in after:
        # Starting the dormant db service runs a SECOND mysqld on the datadir the
        # hand-run sexidium-database container already owns.
        problems.append("the db service lost its `profiles` guard")
    if before.count("depends_on:") != after.count("depends_on:"):
        problems.append("depends_on was edited -- prune:true would recreate the proxy")
    return problems
