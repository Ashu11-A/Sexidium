"""As operações que MUDAM a stack: sync, stack, provision, restart, up, down, db-init.

Regra de ouro deste arquivo: nada aqui apaga dado de servidor. O `sync` sobrescreve
código dentro de /srv/build/repo e não encosta no estado dos nós (mundos, config editada à
mão, logs) nem nos caches do Gradle -- é por isso que atualizar a rede não custa um
mundo novo. A única exceção é opt-in e confirmada: `sync --prune-repo`.
"""

import io
import os
import tarfile
import time

from util import (
    PreconditionError,
    RemoteError,
    boot_order,
    excluded,
    human,
    restart_order,
    shutdown_order,
)
import config as configmod

SYNC_HELPER = "sexidium-sync"
SECRET_KEYS = configmod.SECRET_KEYS


def container(settings, node):
    """Nome do container de um nó. `init` e os nós seguem o mesmo prefixo do stack."""
    return f"{settings.prefix}{node}"


def db_container(client, settings):
    for name in settings.db_candidates:
        if name and client.inspect(name):
            return name
    raise PreconditionError(
        "nenhum container de banco encontrado (procurei: %s); defina SX_DB_CONTAINER"
        % ", ".join(filter(None, settings.db_candidates))
    )


def confirm(args, what):
    """Confirmação interativa. Sem TTY a operação FALHA em vez de assumir 'sim'."""
    if args.yes or args.dry_run:
        return
    try:
        answer = input(f"{what} [digite 'sim' para confirmar]: ")
    except EOFError:
        raise PreconditionError(f"{what}: sem terminal para confirmar (use --yes)") from None
    if answer.strip().lower() not in ("sim", "s", "yes", "y"):
        raise RemoteError("cancelado")


# --- sync ---------------------------------------------------------------------


def build_tar(root, names):
    """Tar em memória do repo (ou de alguns diretórios dele), sem o que não deve subir."""
    buffer = io.BytesIO()

    def keep(info):
        # info.name começa com o arcname; o filtro raciocina sobre o caminho
        # relativo à raiz do repo, que é o que util.excluded() conhece.
        rel = info.name.split("/", 1)[1] if "/" in info.name else ""
        return None if rel and excluded(rel) else info

    with tarfile.open(fileobj=buffer, mode="w") as tar:
        if names:
            for name in names:
                path = os.path.join(root, name)
                if not os.path.exists(path):
                    raise PreconditionError(f"{name} não existe no repo local")
                tar.add(path, arcname=name, filter=lambda i: (None if excluded(i.name) else i))
        else:
            tar.add(root, arcname="repo", filter=keep)
    return buffer.getvalue()


def with_helper(client, settings, body):
    """Roda `body(nome)` com um container auxiliar montando o volume de dados.

    O `finally` não é decorativo: um auxiliar deixado para trás segura o nome e o
    volume, e a execução seguinte morre com "name already in use".
    """
    spec = {
        "Image": settings.image,
        "Entrypoint": ["sleep", "infinity"],
        # Os dois volumes que o sync e o db-init tocam. O repo vive no de BUILD desde
        # que a pasta-fonte passou a conter só `server/`; a data continua montada
        # porque os helpers também inspecionam a fonte.
        "HostConfig": {
            "Binds": [
                f"{settings.volume}:/srv/sexidium",
                f"{settings.build_volume}:/srv/build",
            ],
            "AutoRemove": False,
        },
    }
    client.create_and_start(SYNC_HELPER, spec)
    try:
        return body(SYNC_HELPER)
    finally:
        client.remove(SYNC_HELPER)


def cmd_sync(client, settings, args):
    names = list(args.dirs or [])
    if args.prune_repo:
        confirm(args, "apagar /srv/build/repo no volume antes de enviar")
    blob = build_tar(configmod.REPO_ROOT, names)
    dest = "/srv/build/repo" if names else "/srv/build"
    print(f"sync {', '.join(names) or 'repo inteiro'} ({human(len(blob))}) → {dest}")
    if client.dry_run:
        print("  [dry-run] nada enviado")
        return 0

    def body(helper):
        if args.prune_repo:
            client.sh(helper, "rm -rf /srv/build/repo")
        started = time.time()
        client.put_archive(helper, dest, blob)
        # O bit de execução não sobrevive a toda combinação de tar/umask, e um
        # node-entry.sh sem +x faz o container morrer em "permission denied"
        # depois do deploy, longe daqui.
        client.sh(
            helper,
            "chmod +x /srv/build/repo/gradlew /srv/build/repo/scripts/*.sh "
            "/srv/build/repo/docker/*.sh 2>/dev/null; true",
        )
        print(f"  enviado em {time.time() - started:.0f}s")
        return 0

    return with_helper(client, settings, body)


# --- exclusão mútua -----------------------------------------------------------
# Antes dos comandos porque é um DECORADOR: o Python o avalia na hora de definir a
# função, então ele tem de existir antes do primeiro `@exclusive`.


def exclusive(fn):
    """Uma execução MUTANTE por vez, no mesmo lock que o pipeline usa.

    Sem isto, um `remote.sh update` rodado à mão no meio de um deploy rolante (ou do
    autoscaler) reprovisiona e reinicia nós que o pipeline acabou de drenar e pinar --
    e o pipeline então verifica um boot que não é o que ele causou. O lock é o único
    lugar onde os dois se encontram, então ele tem de valer para os dois.

    Degrada com AVISO, nunca com falha, quando o auxiliar não pode ser criado: estes
    comandos existiam antes do pipeline e têm de continuar funcionando numa rede onde
    ele nunca rodou.

    E não pega o lock DUAS vezes: `update` chama `provision`, o autoscaler chama
    `provision` já com o lock na mão, e `up` encadeia `stack` + `provision`. Um segundo
    acquire vindo do mesmo processo é o mesmo run -- e criar um segundo auxiliar aqui
    também roubaria o nome do container que o run de fora ainda está usando.
    """

    def wrapped(client, settings, args):
        import time as _time

        from journal import Helper, Lock

        if client.dry_run or Lock.held_here():
            return fn(client, settings, args)
        topology = configmod.discover(client, settings)
        helper = Helper(client, settings, topology)
        try:
            helper.__enter__()
        except Exception:  # noqa: BLE001
            # A CRIAÇÃO é o que falha, não o construtor -- e ela falha de verdade num
            # `up` do zero, onde o `stack` roda antes de os volumes por nó existirem e o
            # auxiliar não tem o que montar. Degradar aqui é o que mantém `up` possível.
            print("  aviso: sem auxiliar para o lock; seguindo sem exclusão mútua")
            return fn(client, settings, args)
        try:
            lock = Lock(helper, topology.state_dir, f"manual-{int(_time.time())}", actor=fn.__name__)
            lock.acquire()
            try:
                return fn(client, settings, args)
            finally:
                lock.release()
        finally:
            helper.__exit__()

    wrapped.__name__ = fn.__name__
    wrapped.__doc__ = fn.__doc__
    return wrapped


# --- stack --------------------------------------------------------------------


def stack_env(settings):
    """Env do stack a partir do arquivo de segredos, criando só o que faltar.

    Settings.secrets() completa o que falta e NUNCA regenera um valor existente: o
    forwarding secret já provisionado é o que os 4 backends conferem no login.
    """
    values = settings.secrets(create=True)
    return [
        {"name": "DB_PASSWORD", "value": values["db_password"]},
        {"name": "API_TOKEN", "value": values["api_token"]},
        {"name": "FORWARDING_SECRET", "value": values["forwarding_secret"]},
        {"name": "DB_ROOT_PASSWORD", "value": values["db_root_password"]},
    ]


@exclusive
def cmd_stack(client, settings, args):
    if not os.path.exists(settings.compose):
        raise PreconditionError(f"{settings.compose} não existe")
    with open(settings.compose, encoding="utf-8") as handle:
        content = handle.read()
    env = stack_env(settings)

    _, stacks = client.portainer_json("GET", "/stacks")
    existing = next((s for s in (stacks or []) if s.get("Name") == settings.stack), None)
    if existing:
        print(f"atualizando stack {settings.stack} (id={existing['Id']})")
        status, out = client.portainer_json(
            "PUT",
            f"/stacks/{existing['Id']}?endpointId={settings.endpoint}",
            {"stackFileContent": content, "env": env, "prune": True, "pullImage": False},
        )
    else:
        print(f"criando stack {settings.stack}")
        status, out = client.portainer_json(
            "POST",
            f"/stacks/create/standalone/string?endpointId={settings.endpoint}",
            {"name": settings.stack, "stackFileContent": content, "env": env},
        )
    if client.dry_run:
        return 0
    if status not in (200, 201):
        raise RemoteError(f"deploy do stack → HTTP {status}: {client.hide(str(out))[:600]}")
    print("  stack aplicado")
    return 0


# --- provisionamento ----------------------------------------------------------


@exclusive
def cmd_provision(client, settings, args):
    """Roda o container `init` (docker/provision.sh) e acompanha até o carimbo.

    `Exited (0)` é o estado CORRETO do init entre deploys -- ele provisiona e sai.
    """
    name = container(settings, "init")
    if not client.inspect(name):
        raise PreconditionError(f"{name} não existe; rode `stack` antes")
    print(f"provisionando ({name}) — 2 a 15 min no primeiro run")
    if client.dry_run:
        print("  [dry-run] init não iniciado")
        return 0
    since = int(time.time())
    client.start(name)
    client.follow(name, since=since, out=lambda line: print(f"  {line}"))
    code = client.wait(name, timeout=settings.timeout)
    if code != 0:
        raise RemoteError(f"provisionamento falhou (exit {code}); veja `logs init`")
    print("  provisionamento OK")
    return 0


# --- ciclo de vida ------------------------------------------------------------


def wait_running(client, name, seconds=60):
    for _ in range(seconds):
        info = client.inspect(name)
        if info and info["State"].get("Running"):
            return True
        time.sleep(1)
    return False


def wait_boot(client, name, seconds, since):
    """Espera o `Done (` daquele nó. Devolve False no timeout (o chamador aborta)."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        info = client.inspect(name)
        if info and not info["State"].get("Running"):
            return False
        # `since` NAO e decorativo: sem ele este log ainda contem o `Done (` do boot
        # ANTERIOR (reiniciar um container nao limpa o log), a primeira iteracao casa na
        # hora e o update declara sucesso antes de o no ter subido. Medido: nos subiram
        # 02:16:20 e chegaram no `Done (` 02:16:44-46, mas o update retornou 02:16:22.
        # O chamador captura `since` ANTES do restart, entao filtrar por ele so deixa
        # passar o banner do boot novo -- e um no que nao sobe volta a estourar o timeout
        # em vez de passar batido.
        text = client.logs(name, tail="400", since=since)
        if "Done (" in text:
            return True
        time.sleep(5)
    return False


def roll_one(client, settings, node, args, action):
    """Um nó, do restart ao `Done (`. Levanta RemoteError se ele não voltar."""
    name = container(settings, node)
    since = int(time.time())
    if action == "stop":
        client.stop(name)
        return
    client.restart(name) if action == "restart" else client.start(name)
    if not wait_running(client, name, 60):
        raise RemoteError(f"{node} não voltou a rodar; sequência interrompida")
    if args.boot_timeout > 0 and not wait_boot(client, name, args.boot_timeout, since):
        # Falha rápido de propósito: seguir para o próximo nó com este ainda
        # fora deixa a rede meio de pé, que é pior que parar e avisar.
        raise RemoteError(
            f"{node} não terminou de subir em {args.boot_timeout}s; "
            f"sequência interrompida (veja `logs {node}`)"
        )


def roll(client, settings, nodes, args, action, parallel=False):
    """A rolagem. Serial por padrão; `parallel` junta só a fase dos BACKENDS.

    O tempo de parede aqui é dominado por esperar o `Done (` de cada nó -- 13 a 20 s de
    Paper, um depois do outro. Os backends não dependem um do outro (volume, porta de
    jogo, porta de API e CWD próprios), então esperá-los em série é escolher somar o que
    dava para sobrepor. O PROXY continua sempre por último e sozinho: ele é o que
    conhece os backends, e subir antes deles é o `Unable to connect` que o boot_order
    existe para evitar.

    `stop` nunca paralela: a ordem de desligamento (proxy primeiro) é o que evita
    derrubar backends debaixo de jogadores ainda conectados.
    """
    topology = configmod.discover(client, settings)
    present, absent = [], []
    for node in nodes:
        (present if container(settings, node) in topology.containers else absent).append(node)
    for node in absent:
        print(f"  {node:<9} SKIP (container ausente)")

    backends = [n for n in present if n != "proxy"]
    if action == "stop" or not parallel or len(backends) < 2:
        for node in present:
            print(f"  {node:<9} {action}…")
            if not client.dry_run:
                roll_one(client, settings, node, args, action)
        return 0

    print(f"  {'/'.join(backends)} {action}… (em paralelo)")
    if not client.dry_run:
        import concurrent.futures

        with concurrent.futures.ThreadPoolExecutor(max_workers=len(backends)) as pool:
            futures = {
                pool.submit(roll_one, client, settings, node, args, action): node
                for node in backends
            }
            # Todas as falhas, não só a primeira: com os nós subindo juntos, relatar uma
            # e engolir as outras esconde metade do que quebrou justo quando o operador
            # precisa decidir se reverte.
            broken = []
            for future in concurrent.futures.as_completed(futures):
                try:
                    future.result()
                except RemoteError as failure:
                    broken.append(str(failure))
            if broken:
                raise RemoteError("; ".join(broken))
    for node in present:
        if node == "proxy":
            print(f"  {node:<9} {action}…")
            if not client.dry_run:
                roll_one(client, settings, node, args, action)
    return 0


def rolling_is_parallel(client, settings, args):
    """Os backends podem subir juntos? Só com a rede VAZIA.

    `update`/`restart` já desconectam todo mundo -- essa é a diferença declarada deles
    para o `pipeline deploy`. Mas COM jogadores online a queda escalonada ainda vale
    alguma coisa: enquanto um nó reinicia os outros seguem servindo, e quem estava neles
    não cai. Paralelizar aí trocaria "alguns caem por vez" por "todos caem juntos" para
    ganhar meio minuto -- barganha ruim. Com ninguém online não há nada a escalonar, e
    aí o serial é só espera.

    `--serial` força o comportamento antigo; `--parallel` assume a queda simultânea.
    Na dúvida (banco inalcançável, contagem desconhecida) o padrão é SERIAL: um número
    que não se conhece não autoriza a rota mais agressiva.
    """
    if getattr(args, "serial", False):
        return False
    if getattr(args, "parallel", False):
        return True
    from pipeline import Registry

    rows = Registry(client, settings).all_nodes()
    if not rows:
        print("  (contagem de jogadores indisponível; rolando em série)")
        return False
    online = sum(int(row.get("players") or 0) for row in rows if row.get("role") != "proxy")
    if online:
        print(f"  ({online} jogador(es) online; rolando em série para escalonar a queda)")
        return False
    return True


@exclusive
def cmd_restart(client, settings, args):
    topology = configmod.discover(client, settings)
    nodes = [args.only] if args.only else restart_order(topology.nodes)
    print("reiniciando: " + " → ".join(nodes))
    return roll(client, settings, nodes, args, "restart", rolling_is_parallel(client, settings, args))


@exclusive
def cmd_down(client, settings, args):
    topology = configmod.discover(client, settings)
    confirm(args, "parar a rede inteira (jogadores serão desconectados)")
    nodes = shutdown_order(topology.nodes)
    print("parando: " + " → ".join(nodes))
    return roll(client, settings, nodes, args, "stop")


def cmd_up(client, settings, args):
    """Zero → rede no ar. Cada etapa é idempotente, então repetir `up` é seguro."""
    print(f"docker {client.version().get('Version')} — API key OK")
    cmd_db_init(client, settings, args)
    cmd_sync(client, settings, args)
    cmd_stack(client, settings, args)
    cmd_provision(client, settings, args)
    topology = configmod.discover(client, settings)
    nodes = boot_order(topology.nodes)
    print("subindo: " + " → ".join(nodes))
    return roll(client, settings, nodes, args, "start")


@exclusive
def cmd_update(client, settings, args):
    """O comando do dia a dia: código novo, reprovisiona, rola os nós.

    Continua sendo a rota simples e continua desconectando quem estiver online -- ele
    reinicia os cinco nós sem drenar. Para uma atualização SEM QUEDA use
    `pipeline deploy`, que drena cada nó antes de tocar nele e sabe voltar atrás.
    """
    cmd_sync(client, settings, args)
    cmd_provision(client, settings, args)
    # cmd_restart já é @exclusive; chamá-lo por dentro pegaria o lock duas vezes, então
    # a rolagem é feita aqui com o lock que este comando já segura.
    topology = configmod.discover(client, settings)
    nodes = [args.only] if args.only else restart_order(topology.nodes)
    print("reiniciando: " + " → ".join(nodes))
    return roll(client, settings, nodes, args, "restart", rolling_is_parallel(client, settings, args))


# --- banco --------------------------------------------------------------------


def cmd_db_init(client, settings, args):
    name = db_container(client, settings)
    values = settings.secrets(create=True)
    info = client.inspect(name)
    env = {}
    for entry in info["Config"]["Env"]:
        key, _, value = entry.partition("=")
        env[key] = value
    root = env.get("MARIADB_ROOT_PASSWORD") or env.get("MYSQL_ROOT_PASSWORD")
    if not root:
        raise PreconditionError(f"senha de root não encontrada no env de {name}")
    sql = (
        "CREATE DATABASE IF NOT EXISTS sexidium "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
        "CREATE USER IF NOT EXISTS 'sexidium'@'%%' IDENTIFIED BY '%(pw)s';"
        "ALTER USER 'sexidium'@'%%' IDENTIFIED BY '%(pw)s';"
        "GRANT ALL PRIVILEGES ON sexidium.* TO 'sexidium'@'%%';"
        "FLUSH PRIVILEGES;"
    ) % {"pw": values["db_password"]}
    print(f"db-init em {name}")
    if client.dry_run:
        print("  [dry-run] SQL não executado")
        return 0
    # A senha de root vai por MYSQL_PWD (env do exec), nunca em argv: `ps` dentro
    # do container mostraria a linha de comando inteira.
    code, out = client.exec_run(name, ["mariadb", "-uroot", "-e", sql], env=[f"MYSQL_PWD={root}"])
    if code != 0:
        raise RemoteError(f"db-init falhou (exit {code}): {client.hide(out)[:400]}")
    print("  database + usuário `sexidium` prontos")
    return 0


# --- observação ---------------------------------------------------------------


def cmd_logs(client, settings, args):
    name = db_container(client, settings) if args.node == "db" else container(settings, args.node)
    if not client.inspect(name):
        raise PreconditionError(f"{name} não existe")
    if args.follow:
        client.follow(name, since=int(time.time()), out=print)
        return 0
    print(client.logs(name, tail=str(args.lines)))
    return 0


def cmd_exec(client, settings, args):
    name = db_container(client, settings) if args.node == "db" else container(settings, args.node)
    if not args.cmd:
        raise PreconditionError("nada para executar (use: exec <nó> -- <cmd…>)")
    if client.dry_run:
        print(f"  [dry-run] exec {name}: {' '.join(args.cmd)}")
        return 0
    code, out = client.exec_run(name, list(args.cmd))
    print(out.rstrip("\n"))
    return 0 if code == 0 else 1


def cmd_version(client, settings, args):
    data = client.version()
    print(f"portainer: {settings.url} (endpoint {settings.endpoint}) — API key OK")
    print(f"docker:    {data.get('Version')} (API {data.get('ApiVersion')})")
    topology = configmod.discover(client, settings)
    print(f"nós:       {' '.join(topology.nodes)}")
    return 0


def cmd_secrets(client, settings, args):
    """Lista os segredos por IMPRESSÃO DIGITAL. Valores só com --show --yes."""
    import secrets as pysecrets

    values = settings.secrets(create=True)
    if args.rotate:
        key = args.rotate.replace("-", "_")
        if key not in SECRET_KEYS:
            raise PreconditionError(f"segredo desconhecido: {args.rotate}")
        if key == "forwarding_secret":
            print(
                "ATENÇÃO: trocar o forwarding secret invalida TODOS os backends até "
                "reprovisionar (`update`). Enquanto isso todo join é recusado."
            )
        confirm(args, f"rotacionar {args.rotate}")
        if not client.dry_run:
            values[key] = pysecrets.token_hex(32)
            settings.write_secrets(values)
            print(f"{args.rotate} rotacionado; rode `update` agora")
        return 0
    from util import fingerprint

    print(f"{settings.secrets_file} (nunca commitado)")
    for key in SECRET_KEYS:
        value = values.get(key, "")
        if args.show and args.yes:
            print(f"  {key:<18} {value}")
        else:
            print(f"  {key:<18} {fingerprint(value)}  ({len(value)} chars)")
    if args.show and not args.yes:
        print("  (--show exige --yes: os valores vão para o terminal e para o scrollback)")
    return 0
