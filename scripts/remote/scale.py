"""`scale` e `watch` -- o autoscaler e o laço de observação.

Duas regras governam tudo aqui, e as duas existem porque o erro barato e o erro caro
não são simétricos.

DWELL ASSIMÉTRICO. Subir pede 4 amostras (60 s) acima do limiar; descer pede 40
amostras (10 min) abaixo do outro. Um nó a mais custa memória; um nó a menos no meio
de um pico custa fila e lag. Os dois limiares são SEPARADOS (0.70 / 0.35), nunca um
limiar com delta -- é o que impede o oscilar em volta de um ponto.

O TETO É FÍSICO E É DITO EM VOZ ALTA. Este host tem 31,07 GiB, ~20 GiB já
comprometidos em mem_limit, e o `init` reserva 6 GiB enquanto provisiona. Quando não
há folga o autoscaler NÃO sobe nada: emite o alerta com a aritmética inteira e segura.
Um OOM do cgroup é SIGKILL -- sem shutdown hook, sem save-all -- e é o pior desfecho
disponível nesta máquina, pior que ficar sem capacidade.

O que este arquivo NÃO faz: mover mundo existente. Um mundo ocioso replanejado por
carga é churn, não balanceamento; o nó novo recebe os PRÓXIMOS mundos porque o
planejador de placement ordena por menos jogadores e menos mundos, então o vazio ganha.
"""

import json
import time

import config as configmod
from alerts import Alerts, INFO, WARN
from journal import Helper, Lock
from util import (
    PreconditionError,
    RemoteError,
    add_worker_to_compose,
    capacity_report,
    compose_guard,
    human,
    max_workers,
    player_counts,
    trace,
)

# GiB, e os números são medidos, não estimados: 24 CPU e 31,07 GiB de RAM, uso real
# ~8,9 GiB entre seis containers, CPU ~24% de UM core-equivalente. CPU sobra; memória
# é o que amarra.
GIB = 1024 ** 3
PROXY_LIMIT = 1 * GIB
LOBBY_LIMIT = 4 * GIB
INIT_RESERVE = 6 * GIB
# 3 GiB por worker autoescalado contra os 5 GiB dos plantados à mão: o working set
# medido é 2,0-2,4 GiB, então 3 é generoso -- e a 5 GiB cada, dois workers a mais
# simplesmente não cabem neste host.
AUTO_WORKER_LIMIT = 3 * GIB
AUTO_WORKER_HEAP = "-Xms1G -Xmx2G"

# Qual variável do stack guarda qual segredo. Só existe para CONFERIR o que o Portainer
# devolve contra o que está aqui -- o autoscaler nunca escreve nenhuma delas.
ENV_SECRETS = {
    "DB_PASSWORD": "db_password",
    "API_TOKEN": "api_token",
    "FORWARDING_SECRET": "forwarding_secret",
    "DB_ROOT_PASSWORD": "db_root_password",
}

UP_THRESHOLD = 0.70
DOWN_THRESHOLD = 0.35
UP_POLLS = 4
DOWN_POLLS = 40
UP_COOLDOWN = 600
DOWN_COOLDOWN = 1800


def host_memory(client):
    info = client.docker_json("GET", "/info") or {}
    return int(info.get("MemTotal") or 0)


def committed_memory(client, settings, topology):
    """Soma dos mem_limit dos containers que existem -- o que está COMPROMETIDO, que é
    o número que decide se cabe mais um, e não o que está EM USO."""
    total = 0
    for node in ["proxy"] + list(topology.nodes):
        info = client.inspect(f"{settings.prefix}{node}") or {}
        total += int((info.get("HostConfig") or {}).get("Memory") or 0)
    return total


class Sampler:
    """Uma amostra do estado da rede, e a decisão que ela suporta."""

    def __init__(self, client, settings):
        self.client = client
        self.settings = settings
        self.topology = configmod.discover(client, settings)
        from pipeline import Registry

        self.registry = Registry(client, settings)

    def sample(self):
        rows = self.registry.all_nodes()
        network, backends = player_counts(rows)
        workers = [n for n in self.topology.nodes if n.startswith("worker")]
        up_workers = [
            r for r in rows if (r.get("role") or "").lower() == "worker" and r.get("state") == "UP"
        ]
        capacity = 0
        for row in up_workers:
            try:
                capacity += int(row.get("max_players") or 0)
            except (TypeError, ValueError):
                pass
        if not capacity:
            # max_players é uma coluna que o JVM pode ainda não estar escrevendo. Sem
            # ela o autoscaler continua funcionando com uma capacidade assumida, em vez
            # de dividir por zero ou desligar.
            capacity = len(up_workers) * 40
        return {
            "ts": int(time.time()),
            "network_players": network,
            "backend_players": backends,
            "workers": len(workers),
            "workers_up": len(up_workers),
            "capacity": capacity,
            "utilisation": round(backends / capacity, 3) if capacity else 0.0,
            "nodes": rows,
        }


class Autoscaler:
    def __init__(self, client, settings, args, alerts):
        self.client = client
        self.settings = settings
        self.args = args
        self.alerts = alerts
        self.sampler = Sampler(client, settings)
        self.high = 0
        self.low = 0
        self.idle = {}
        self.last_action = 0
        self.actions_this_hour = []

    def ceiling(self, mem_total):
        configured = getattr(self.args, "max_workers", None)
        if configured:
            return int(configured)
        return max_workers(mem_total, PROXY_LIMIT, LOBBY_LIMIT, INIT_RESERVE, AUTO_WORKER_LIMIT)

    def evaluate(self, sample):
        """-> ("up"|"down"|None, motivo). Puro o bastante para ser lido de uma vez."""
        util = sample["utilisation"]
        self.high = self.high + 1 if util > UP_THRESHOLD else 0
        self.low = self.low + 1 if util < DOWN_THRESHOLD else 0

        for row in sample["nodes"]:
            if (row.get("role") or "").lower() != "worker":
                continue
            quiet = (row.get("players") in ("0", 0)) and (row.get("worlds") in ("0", 0))
            node = row.get("node_id")
            self.idle[node] = self.idle.get(node, 0) + 1 if quiet else 0

        since = time.time() - self.last_action
        recent = [t for t in self.actions_this_hour if time.time() - t < 3600]
        self.actions_this_hour = recent
        if len(recent) >= int(getattr(self.args, "max_actions_per_hour", 3) or 3):
            return None, "limite de ações por hora atingido; segurando"

        if self.high >= UP_POLLS and since >= UP_COOLDOWN:
            return "up", f"utilização {util} acima de {UP_THRESHOLD} por {self.high} amostras"
        min_workers = int(getattr(self.args, "min_workers", 2) or 2)
        if self.low >= DOWN_POLLS and since >= DOWN_COOLDOWN and sample["workers"] > min_workers:
            return "down", f"utilização {util} abaixo de {DOWN_THRESHOLD} por {self.low} amostras"
        return None, ""

    # --- subir ---------------------------------------------------------------

    def scale_up(self, helper, sample):
        topology = self.sampler.topology
        mem_total = host_memory(self.client)
        committed = committed_memory(self.client, self.settings, topology)
        ceiling = self.ceiling(mem_total)
        workers = sample["workers"]

        # A folga é medida contra o que está REALMENTE comprometido, mais o worker novo,
        # MAIS a reserva do `init` -- porque subir um nó roda o `init`, e ele pede 6 GiB
        # enquanto provisiona. A fórmula max_workers() responde "quantos caberiam se
        # todos fossem do tamanho autoescalado"; esta conta responde "cabe mais um AGORA,
        # com os tamanhos que os nós têm de fato". Os dois números podem discordar, e
        # quando discordam quem manda é este: os três workers plantados à mão têm
        # mem_limit 5g, não 3g, então a folga real é menor que a fórmula sugere.
        needed = committed + AUTO_WORKER_LIMIT + INIT_RESERVE
        if workers >= ceiling or needed > mem_total * 0.85:
            # NÃO sobe. A alternativa -- comprometer memória a mais e torcer -- termina
            # num OOM-kill do cgroup, que é SIGKILL: sem shutdown hook, sem save do
            # mundo, exit 137. Perder capacidade é recuperável; perder um save não é.
            self.alerts.fire(
                WARN,
                "capacity.exhausted",
                capacity_report(
                    mem_total, committed, PROXY_LIMIT, LOBBY_LIMIT, INIT_RESERVE, AUTO_WORKER_LIMIT
                ),
            )
            return False

        name = self.next_name(helper, topology)
        index = len(topology.nodes)
        print(f"  subindo {name} (índice {index}, mem_limit {human(AUTO_WORKER_LIMIT)})")
        trace(f"scale up {name} index={index}")
        if self.client.dry_run:
            return True

        # 1. o volume ANTES do deploy: `external: true` faz o compose recusar o stack
        #    inteiro quando o volume não existe.
        self.client.create_volume(f"{self.settings.prefix}{name}")

        # 2. cirurgia de texto sobre o compose IMPLANTADO, não sobre o do repo: é o
        #    implantado que o PUT vai substituir, e comparar contra o do repo esconderia
        #    exatamente a divergência que este guard existe para pegar.
        stack, deployed = self.deployed_stack()
        generated = add_worker_to_compose(
            deployed, name, index, mem_limit=f"{AUTO_WORKER_LIMIT // GIB}g", heap=AUTO_WORKER_HEAP
        )
        problems = compose_guard(deployed, generated, name)
        if problems:
            raise RemoteError(
                "a edição do compose sairia das mudanças permitidas e foi RECUSADA "
                "(um PUT com prune:true recriaria os serviços afetados): " + "; ".join(problems)
            )
        self.put_stack(stack, generated)

        # 3. provisiona o nó novo: sexidium-node.args, symlinks, config, pluginjars/
        #    com o build atual pinado. O proxy NÃO é reiniciado -- BackendDirectory
        #    encontra o nó pelo registro do banco em até 5 s.
        from ops import cmd_provision

        cmd_provision(self.client, self.settings, self.args)
        self.client.start(f"{self.settings.prefix}{name}")
        self.alerts.fire(INFO, f"scale.up.{name}", f"{name} entrou na rede")
        return True

    def next_name(self, helper, topology):
        """Reusa um nome ESTACIONADO antes de acrescentar um novo.

        A porta segue o ÍNDICE na lista e SX_NODES é append-only, então um nome reusado
        recupera o índice que sempre teve e portanto as portas que sempre teve -- e o
        volume com o estado que ficou. É a resposta inteira para "como remover sem
        renumerar": não removendo.
        """
        retired = self.retired(helper)
        if retired:
            name = retired[0]
            self.write_retired(helper, retired[1:])
            return name
        existing = [n for n in topology.nodes if n.startswith("worker-")]
        return f"worker-{len(existing) + 1}"

    def retired(self, helper):
        text = helper.read(f"{self.sampler.topology.state_dir}/pipeline/retired.json").strip()
        try:
            return list(json.loads(text)) if text else []
        except ValueError:
            return []

    def write_retired(self, helper, names):
        helper.write(
            f"{self.sampler.topology.state_dir}/pipeline/retired.json", json.dumps(sorted(set(names)))
        )

    def deployed_stack(self):
        """O stack como o Portainer o tem: o objeto (que carrega o `Env`) e o compose.

        Os dois vêm de LÁ, não daqui. Para o compose a razão já estava escrita -- é o
        implantado que o PUT substitui. Para o env a razão é a mesma e o custo é maior:
        veja `deployed_env`.
        """
        _, stacks = self.client.portainer_json("GET", "/stacks")
        stack = next((s for s in (stacks or []) if s.get("Name") == self.settings.stack), None)
        if not stack:
            raise PreconditionError(f"o stack {self.settings.stack} não existe")
        _, detail = self.client.portainer_json("GET", f"/stacks/{stack['Id']}")
        if isinstance(detail, dict) and detail.get("Env"):
            stack = detail
        _, content = self.client.portainer_json("GET", f"/stacks/{stack['Id']}/file")
        text = (content or {}).get("StackFileContent") if isinstance(content, dict) else None
        if not text:
            raise PreconditionError("não consegui ler o compose implantado do Portainer")
        return stack, text

    def deployed_env(self, stack):
        """O env do stack implantado, para ser reenviado LITERALMENTE.

        `ops.stack_env` não pode ser usado neste caminho, e a diferença é a que estraga
        uma rede inteira: ele chama `secrets(create=True)`, que GERA qualquer valor que
        falte no arquivo local. O autoscaler roda de um cron e pode rodar de um host sem
        `remote.secrets.json` (checkout novo, diretório de estado perdido) -- e aí um
        scale-up de rotina inventa um FORWARDING_SECRET novo e o PUTa com prune:true:
        todo container recriado, e o proxy deixando de casar com os backends. Todo join
        passa a ser recusado. O `compose_guard` não pega nada disso: ele valida o TEXTO
        do compose, não o env.

        Então o env vem de lá, verbatim -- e quando o que está aqui discorda do que está
        lá, RECUSA: reenviar o local seria a mesma rotação silenciosa por outra porta.
        """
        entries = [e for e in (stack.get("Env") or []) if isinstance(e, dict) and e.get("name")]
        if not entries:
            raise PreconditionError(
                "o stack implantado não devolveu `Env`; um PUT sem ele apagaria as "
                "variáveis do stack (senha do banco inclusa). Escale por `remote.sh stack`."
            )
        deployed = {e["name"]: e.get("value", "") for e in entries}
        local = self.settings.secrets(create=False)
        diverged = sorted(
            name
            for name, key in ENV_SECRETS.items()
            if local.get(key) and deployed.get(name) and local[key] != deployed[name]
        )
        if diverged:
            raise PreconditionError(
                "os segredos locais discordam do que o stack implantado tem em "
                + ", ".join(diverged)
                + ". Um PUT agora trocaria o valor que os nós já conferem no login. "
                "Reconcilie scripts/remote.secrets.json com o stack antes de escalar."
            )
        return entries

    def put_stack(self, stack, content):
        status, out = self.client.portainer_json(
            "PUT",
            f"/stacks/{stack['Id']}?endpointId={self.settings.endpoint}",
            {
                "stackFileContent": content,
                "env": self.deployed_env(stack),
                # prune:true é o default do projeto e o custo dele está contido pelo
                # guard acima: os únicos serviços cuja spec muda são o `init` (que está
                # Exited(0), recriá-lo é de graça) e o worker novo.
                "prune": True,
                "pullImage": False,
            },
        )
        if status not in (200, 201):
            raise RemoteError(f"deploy do stack -> HTTP {status}: {self.client.hide(str(out))[:400]}")

    # --- descer --------------------------------------------------------------

    def scale_down(self, helper, sample):
        """ESTACIONA, não remove. O container para, SX_NODES e o compose ficam intactos.

        Remover do meio renumeraria todos os nós seguintes -- porta de jogo, porta de
        API, porta de pack, mais o server.properties e o sexidium-node.args já gravados
        de cada um -- e cada nó passaria a atender na porta do vizinho. Por isso a
        remoção de verdade é MANUAL e só pela cauda: editar SX_NODES, aplicar o `stack`
        e apagar o volume daquele nó. Depois disso, `pipeline forget <nó>` tira o nó do
        banco -- a linha em `network_nodes` não expira sozinha e deixaria o `status` em
        FAIL para sempre.
        """
        topology = self.sampler.topology
        workers = [n for n in topology.nodes if n.startswith("worker-")]
        if not workers:
            return False
        candidate = workers[-1]  # a CAUDA, sempre: só ela pode sair sem renumerar nada
        if self.idle.get(candidate, 0) < 20:
            return False
        loaded = self.sampler.registry.loaded_placements(candidate)
        if loaded:
            print(f"  {candidate} ainda tem {loaded} mundo(s) LOADED; não estaciono")
            return False

        print(f"  estacionando {candidate}")
        trace(f"scale down {candidate}")
        if self.client.dry_run:
            return True
        from pipeline import node_command
        from util import api_port

        port = api_port(topology.nodes, candidate, topology.api_base, topology.api_stride)
        secrets = self.settings.secrets(create=False)
        node_command(helper, candidate, port, secrets.get("api_token", ""), "sx admin net drain scale-in")
        time.sleep(30)
        # stop?t=120 com stop_grace_period 120s: o Paper precisa desse tempo para
        # prepareShutdown e o save do mundo. Os mundos de experiência vivem na árvore
        # compartilhada, então estacionar um worker não perde dado de jogador nenhum --
        # só o overworld próprio e os mundos temporários de partida, ambos descartáveis.
        self.client.stop(f"{self.settings.prefix}{candidate}", seconds=120)
        self.write_retired(helper, self.retired(helper) + [candidate])
        self.alerts.fire(INFO, f"scale.down.{candidate}", f"{candidate} estacionado (container parado)")
        return True

    def act(self, helper, sample, decision):
        done = False
        if decision == "up":
            done = self.scale_up(helper, sample)
        elif decision == "down":
            done = self.scale_down(helper, sample)
        if done:
            self.last_action = time.time()
            self.actions_this_hour.append(self.last_action)
            self.high = self.low = 0
        return done


# --- subcomandos --------------------------------------------------------------


def cmd_watch(client, settings, args):
    """Laço sobre `status` + a avaliação do autoscaler. Uma linha por amostra.

    Não pega o lock, de propósito: observar tem de poder rodar ao lado de qualquer
    coisa. Uma AÇÃO de escala pega (a mesma do deploy), então escalar e deployar nunca
    se entrelaçam.
    """
    import checks

    topology = configmod.discover(client, settings)
    # create=False: escalar NÃO é provisionar. Gerar um segredo que falta aqui deixaria
    # o arquivo local em desacordo com o stack implantado, e é desse desacordo que sai o
    # PUT que rotaciona o FORWARDING_SECRET da rede inteira (veja `deployed_env`).
    secrets = settings.secrets(create=False)
    alerts = Alerts(settings, url=secrets.get("alert_webhook"), dry_run=client.dry_run)
    interval = int(getattr(args, "interval", 15) or 15)

    with Helper(client, settings, topology) as helper:
        scaler = Autoscaler(client, settings, args, alerts)
        mem_total = host_memory(client)
        ceiling = scaler.ceiling(mem_total)
        print(
            f"watch: intervalo {interval}s · teto {ceiling} workers "
            f"(host {human(mem_total)}, worker {human(AUTO_WORKER_LIMIT)})"
        )
        while True:
            sample = scaler.sampler.sample()
            rows = checks.Status(client, settings).run()
            failed = [r for r in rows if r[1] == "FAIL"]
            decision, reason = scaler.evaluate(sample)
            print(
                f"[{time.strftime('%H:%M:%S')}] jogadores={sample['network_players']} "
                f"backends={sample['backend_players']} U={sample['utilisation']} "
                f"workers={sample['workers_up']}/{sample['workers']} teto={ceiling} "
                f"falhas={len(failed)} {reason}"
            )
            helper.append(
                f"{topology.state_dir}/pipeline/metrics.jsonl",
                json.dumps({k: v for k, v in sample.items() if k != "nodes"}, sort_keys=True),
            )
            for name, state, detail in rows:
                # Transição, nunca amostra: um alerta por poll é como um canal de
                # alertas deixa de ser lido.
                if state == "FAIL":
                    alerts.fire("error", f"check.{name}", detail)
                else:
                    alerts.clear(f"check.{name}")
            if decision and not getattr(args, "observe_only", False):
                lock = Lock(helper, topology.state_dir, f"scale-{int(time.time())}", actor="autoscaler")
                lock.acquire()
                try:
                    scaler.act(helper, sample, decision)
                finally:
                    lock.release()
            if getattr(args, "once", False):
                return 0
            time.sleep(interval)


def cmd_scale(client, settings, args):
    """Uma ação de escala, à mão, com as mesmas travas do laço automático."""
    topology = configmod.discover(client, settings)
    # create=False: escalar NÃO é provisionar. Gerar um segredo que falta aqui deixaria
    # o arquivo local em desacordo com o stack implantado, e é desse desacordo que sai o
    # PUT que rotaciona o FORWARDING_SECRET da rede inteira (veja `deployed_env`).
    secrets = settings.secrets(create=False)
    alerts = Alerts(settings, url=secrets.get("alert_webhook"), dry_run=client.dry_run)
    with Helper(client, settings, topology) as helper:
        scaler = Autoscaler(client, settings, args, alerts)
        sample = scaler.sampler.sample()
        lock = Lock(helper, topology.state_dir, f"scale-{int(time.time())}", actor="scale")
        lock.acquire()
        try:
            if args.direction == "up":
                # Força o dwell: o operador JÁ decidiu, e as travas que continuam
                # valendo são as que protegem a máquina (folga de memória, teto), não as
                # que protegem contra oscilação.
                scaler.last_action = 0
                ok = scaler.scale_up(helper, sample)
            else:
                scaler.idle = {n: 99 for n in topology.nodes}
                ok = scaler.scale_down(helper, sample)
            return 0 if ok else 1
        finally:
            lock.release()
