"""Fakes that let the bats suite drive the REAL pipeline state machine.

The pure half of scripts/remote/ is already covered by asserting on functions. The
stateful half -- resume, converge, rollback, the lock -- is the half whose bugs cost a
live node, and none of it is reachable from a pure-function test: every one of those
paths is defined by what it DOES to a container and to a pin file.

So the three things the pipeline talks to are faked, and only those three:

  FakeClient    the Portainer/Docker client. Records every mutation in `events`.
  FakeHelper    the helper container, as an in-memory filesystem. It also EXECUTES the
                two scripts whose effect the tests assert on -- `store::pin_node`
                (so the pin file really moves) and the /dev/tcp HTTP request (so a
                `sx admin net drain` really reaches a node) -- into the same `events`
                list, which is what makes "the drain happened BEFORE the stop" an
                assertion rather than a hope.
  FakeRegistry  network_nodes / world_placements as plain dicts.

`events` being one shared list across the client and the helper is the whole point:
ordering between a console command and a container stop is exactly the property R8
turns on, and two separate logs cannot express it.
"""

import json
import os
import re
import sys

_TEST_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_REMOTE = os.path.join(os.path.dirname(_TEST_DIR), "remote")
if _REMOTE not in sys.path:
    sys.path.insert(0, _REMOTE)

import config as configmod  # noqa: E402
import journal as journalmod  # noqa: E402
import pipeline as pipelinemod  # noqa: E402

NODES = ["lobby", "worker-1", "worker-2"]
STATE_DIR = "/srv/sexidium/server"
READY_LOG = (
    "[12:00:01 INFO]: [Sexidium] Enabling Sexidium v1.0.0\n"
    "[12:00:08 INFO]: [Sexidium] SX-READY node=x protocol=1 build=1.0.0+b0042-new\n"
    "[12:00:08 INFO]: [Sexidium] HTTP API listening on http://0.0.0.0:8810\n"
    "[12:00:09 INFO]: Done (21.5s)!\n"
)


class Clock:
    """`time`, minus the sleeping. Otherwise a drain poll costs real minutes."""

    def __init__(self):
        self.now = 1_000_000.0

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds

    def strftime(self, fmt, *rest):
        return "00:00:00"


class Args:
    def __init__(self, **overrides):
        self.yes = True
        self.dry_run = False
        self.verbose = False
        self.boot_timeout = 30
        self.drain_timeout = 60
        self.on_drain_timeout = "abort"
        self.soak = 0
        self.canary = None
        self.skip_tests = True
        self.allow_unhealthy = ""
        self.allow_lobby_disconnect = True
        self.maintenance_window = False
        self.force_proxy = False
        self.skip_drain = False
        self.force_unlock = False
        self.run_id = None
        self.__dict__.update(overrides)


class FakeRegistry:
    """network_nodes, as dicts. `drain()` is what the fake helper calls back into."""

    def __init__(self, nodes=NODES, players=0, placements=0):
        self.rows = {
            node: {
                "node_id": node,
                "role": "lobby" if node.startswith("lobby") else "worker",
                "state": "UP",
                "players": str(players),
                "worlds": "0",
                "plugin_version": "1.0.0+b0042-new",
                "age": "2000",
            }
            for node in nodes
        }
        self.placements = placements
        self.drained = []

    def node(self, node_id):
        row = self.rows.get(node_id)
        return dict(row) if row else None

    def all_nodes(self):
        return [dict(r) for r in self.rows.values()]

    def loaded_placements(self, node_id):
        return self.placements

    def drain(self, node_id):
        self.drained.append(node_id)
        row = self.rows.get(node_id)
        if row:
            row["state"] = "DRAINING"
            row["players"] = "0"
            row["worlds"] = "0"

    def undrain(self, node_id):
        row = self.rows.get(node_id)
        if row:
            row["state"] = "UP"


class FakeHelper:
    """The helper container: a dict of files, plus the two scripts that must be real."""

    PIN = re.compile(r"store::pin_node /srv/nodes/(\S+) '([^']+)'")
    TCP = re.compile(r"/dev/tcp/([^/]+)/(\d+)")
    # O comando é o CORPO da requisição, exatamente como ApiServer.handleCommand o lê
    # (`new String(readAll(getRequestBody())).trim()`). Este fake já teve uma regex de
    # `{"command": "..."}` aqui, copiada do que o CLIENTE mandava -- e o cliente estava
    # errado. Um dublê que espelha o engano do chamador em vez do contrato do servidor
    # deixa a suíte inteira verde sobre um caminho que não funciona em lugar nenhum:
    # foi assim que o drain do pipeline passou nos testes e nunca drenou nada.
    REQUEST_BODY = re.compile(r"POST (/\S+) HTTP/1\.0\r\n.*?\r\n\r\n(.*?)' >&3", re.S)
    # `set -C` + redirection IS the mutual exclusion -- O_EXCL, no daemon. Faking it as
    # "always succeeds" would make every lock test green by accident, so it is emulated
    # here with the one semantic that matters: the create FAILS if the file exists.
    NOCLOBBER = re.compile(r"set -C && printf '%s' '(.*)' > '(.*)'\Z", re.S)
    UNLINK = re.compile(r"\Arm -f '(.*)'\Z", re.S)

    def __init__(self, events, registry=None):
        self.files = {}
        self.events = events
        self.registry = registry
        self.scripts = []

    # --- the real Helper's surface -------------------------------------------

    def __enter__(self):
        return self

    def __exit__(self, *rest):
        return False

    def sh(self, script, timeout=None):
        self.scripts.append(script)
        self._pin(script)
        create = self.NOCLOBBER.search(script)
        if create:
            path = create.group(2)
            if path in self.files:
                return 1, f"cannot overwrite existing file: {path}"
            self.files[path] = create.group(1)
            return 0, ""
        unlink = self.UNLINK.search(script)
        if unlink:
            self.files.pop(unlink.group(1), None)
            return 0, ""
        host = self.TCP.search(script)
        if not host:
            return 0, ""
        request = self.REQUEST_BODY.search(script)
        if request and request.group(1) == "/command":
            self._command(host.group(1), request.group(2).strip())
        # A 200 with a body every caller can parse: /health, /node/selftest and
        # /command all read as healthy, so a test only has to arrange the failure it
        # is actually about.
        return 0, 'HTTP/1.0 200 OK\r\n\r\n{"ok":true}'

    def must_sh(self, script, what, timeout=None):
        code, out = self.sh(script)
        return out

    def read(self, path):
        return self.files.get(path, "")

    def write(self, path, text):
        self.files[path] = text

    def append(self, path, line):
        self.files[path] = self.files.get(path, "") + line + "\n"

    # --- the two effects the tests assert on ---------------------------------

    def _pin(self, script):
        match = self.PIN.search(script)
        if not match:
            return
        node, build = match.group(1), match.group(2)
        path = f"/srv/nodes/{node}/sexidium-build.pin"
        previous = pin_field(self.files.get(path, ""), "build")
        self.files[path] = f"build={build}\nprevious={previous}\n"
        self.events.append(("pin", node, build))

    def _command(self, node, command):
        self.events.append(("command", node, command))
        if self.registry is None:
            return
        if command.startswith("sx admin net drain"):
            self.registry.drain(node)
        elif command.startswith("sx admin net undrain"):
            self.registry.undrain(node)


class FakeClient:
    def __init__(self, events, nodes=NODES):
        self.dry_run = False
        self.nodes = list(nodes)
        self.events = events
        self.log = READY_LOG
        self.restart_count = 0
        self.running = True
        self.stacks = []
        self.puts = []

    # --- discovery ------------------------------------------------------------

    def docker_json(self, method, path, body=None, timeout=None):
        if path.startswith("/containers/json"):
            return [
                {"Names": [f"/sexidium-{n}"], "State": "running"}
                for n in ["proxy"] + self.nodes
            ]
        if path.endswith("/json"):
            return {
                "Config": {
                    "Env": [
                        "SX_NODES=" + " ".join(self.nodes),
                        f"SX_STATE_DIR={STATE_DIR}",
                        "SX_API_PORT_BASE=8800",
                        "SX_API_PORT_STRIDE=10",
                        "SX_PORT_BASE=25566",
                    ]
                }
            }
        if path == "/info":
            return {"MemTotal": 31 * 1024 ** 3}
        return None

    # --- containers -----------------------------------------------------------

    def inspect(self, name):
        return {
            "State": {
                "Running": self.running,
                "StartedAt": "2026-08-13T00:00:00Z",
                "OOMKilled": False,
                "ExitCode": 0,
            },
            "RestartCount": self.restart_count,
            "Config": {"Env": []},
            "HostConfig": {"Memory": 0},
        }

    def logs(self, name, tail="200", since=0):
        return self.log

    def sh(self, name, script, env=None, timeout=None):
        return 0, ""

    def exec_run(self, name, cmd, env=None, timeout=None):
        return 0, ""

    def restart(self, name, seconds=120):
        self.events.append(("restart", name))

    def stop(self, name, seconds=120):
        self.events.append(("stop", name))

    def start(self, name):
        self.events.append(("start", name))

    def create_and_start(self, name, spec):
        self.events.append(("create", name))

    def remove(self, name, force=True):
        self.events.append(("remove", name))

    def create_volume(self, name):
        self.events.append(("volume", name))
        return name

    def follow(self, name, since=0, out=print):
        return None

    def wait(self, name, timeout=None):
        return 0

    def version(self):
        return {"Version": "28.0.0", "ApiVersion": "1.48"}

    def hide(self, text):
        return text

    # --- Portainer ------------------------------------------------------------

    def portainer_json(self, method, path, body=None, timeout=None):
        if method == "GET" and path == "/stacks":
            return 200, self.stacks
        if method == "GET" and path.endswith("/file"):
            return 200, {"StackFileContent": self.stacks[0].get("_file", "")}
        if method == "GET" and path.startswith("/stacks/"):
            return 200, self.stacks[0]
        if method == "PUT":
            self.puts.append((path, body))
            return 200, {}
        return 200, None


class RecordingSettings(configmod.Settings):
    """Settings that remember every `secrets()` call, so a test can assert on
    `create=True` never happening on the autoscale path."""

    def __init__(self, env=None):
        super().__init__(env=env)
        self.secret_calls = []

    def secrets(self, create=True):
        self.secret_calls.append(create)
        return super().secrets(create=create)


def pin_field(text, key):
    for line in text.splitlines():
        name, sep, value = line.strip().partition("=")
        if sep and name == key:
            return value
    return ""


def settings_for(tmp, env=None):
    """Real Settings over a real (temporary) secrets file: the CLI reads it for the API
    token and for redaction, and faking that would fake the thing H3 is about."""
    path = os.path.join(tmp, "secrets.json")
    with open(path, "w", encoding="utf-8") as out:
        json.dump(
            {
                "db_password": "dbpw",
                "api_token": "tok",
                "forwarding_secret": "fwd",
                "db_root_password": "rootpw",
                "alert_webhook": "",
            },
            out,
        )
    values = {
        "SX_PORTAINER_URL": "http://portainer.invalid",
        "SX_PORTAINER_KEY": "key",
        "SX_SECRETS_FILE": path,
    }
    values.update(env or {})
    return RecordingSettings(env=values)


def build(tmp, journal_lines=(), pins=None, run="r1", nodes=NODES, **argkw):
    """-> (pipe, client, helper, registry, events). `pipe` is the real Pipeline.

    `journal_lines` seeds the run's journal exactly as an interrupted run would have
    left it; `pins` seeds each node's on-disk pin file.
    """
    pipelinemod.time = Clock()
    journalmod.Lock._depth = 0
    events = []
    registry = FakeRegistry(nodes=nodes)
    client = FakeClient(events, nodes=nodes)
    helper = FakeHelper(events, registry=registry)
    for node, (build_id, previous) in (pins or {}).items():
        helper.files[f"/srv/nodes/{node}/sexidium-build.pin"] = (
            f"build={build_id}\nprevious={previous}\n"
        )
    settings = settings_for(tmp)
    args = Args(**argkw)
    pipe = pipelinemod.Pipeline(client, settings, args, helper, run)
    pipe.registry = registry
    pipe.alerts.echo = lambda *rest: None
    if journal_lines:
        helper.files[f"{STATE_DIR}/pipeline/runs/{run}.jsonl"] = (
            "\n".join(journal_lines) + "\n"
        )
        pipe.resume = pipe.journal.state()
    return pipe, client, helper, registry, events


def patch_helpers(helper, registry=None):
    """Every module that reaches for a helper container gets this one instead.

    Three call sites and three import styles: `ops.exclusive` imports Helper lazily
    inside the wrapper (so `journal.Helper` is what it sees), while `pipeline` and
    `scale` bound it at import time. Patching one of the three would silently leave a
    command talking to a real Portainer.
    """
    import scale as scalemod

    journalmod.Helper = lambda *rest, **kw: helper
    pipelinemod.Helper = lambda *rest, **kw: helper
    scalemod.Helper = lambda *rest, **kw: helper
    if registry is not None:
        real = pipelinemod.Pipeline

        def make(*rest, **kw):
            pipe = real(*rest, **kw)
            pipe.registry = registry
            pipe.alerts.echo = lambda *ignored: None
            return pipe

        pipelinemod.Pipeline = make


def rolled(run, node, build_id, previous, through="reclaim"):
    """The journal lines a node that got all the way to `through` would have left."""
    from util import journal_line

    order = ["announce", "drain", "pin", "restart", "verify", "reclaim"]
    lines = []
    for sub in order[: order.index(through) + 1]:
        detail = {"from": previous, "to": build_id} if sub == "pin" else None
        lines.append(journal_line(run, "roll", "begin", node=node, sub=sub, detail=detail))
        lines.append(journal_line(run, "roll", "ok", node=node, sub=sub, detail=detail))
    return lines
