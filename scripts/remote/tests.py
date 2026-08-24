"""`test` -- roda as suítes do repo DENTRO do host da stack, num container efêmero.

Por que no host e não aqui: é lá que o código roda, é lá que estão os 24 vCPU e o
cache do Gradle (~62 s e 484 MB por execução), e é o único lugar onde "passou" prova
alguma coisa sobre o que está no ar. O container é criado, seguido e destruído a cada
execução -- o `finally` importa: um efêmero órfão segura o nome e a próxima execução
morre com "name already in use".

O trabalho de dentro é do docker/test-entry.sh; este arquivo só cria, acompanha e
traduz o exit code.
"""

import time

from util import PreconditionError, RemoteError

HELPER = "sexidium-test"
SUITES = ("scripts", "gradle", "bot")


def cmd_test(client, settings, args):
    suites = list(args.suites or SUITES)
    unknown = [s for s in suites if s not in SUITES]
    if unknown:
        raise PreconditionError(f"suíte desconhecida: {', '.join(unknown)} (use: {' '.join(SUITES)})")

    if not args.no_sync:
        from ops import cmd_sync

        cmd_sync(client, settings, args)

    run_id = time.strftime("%Y%m%d-%H%M%S")
    spec = {
        "Image": settings.image,
        "Entrypoint": ["/bin/bash", "/srv/build/repo/docker/test-entry.sh"],
        "Env": [
            "SX_HOME=/srv/build",
            "ROOT_DIR=/srv/build/repo",
            # O teste não encosta na pasta-fonte nem no estado de nó nenhum: roda
            # inteiro dentro do volume de build, com os relatórios ao lado do repo.
            "NETWORK_DIR=/srv/build/test-run",
            f"SX_SUITES={' '.join(suites)}",
            # Um estágio pulado NÃO é um estágio aprovado: sem isto o run.sh sai 0
            # dizendo "5 stage(s) OK" num container onde shellcheck/shfmt/bats nem
            # existem. Ver scripts/test/run.sh.
            "SX_TEST_STRICT=1",
            # O GRADLE_USER_HOME do volume é o ganho real (dependências já baixadas);
            # o project-cache é separado do que o `init` usa -- dois daemons no mesmo
            # project-cache é a única parte que não tolera concorrência.
            "GRADLE_USER_HOME=/srv/build/gradle/home",
            "GRADLE_PROJECT_CACHE_DIR=/srv/build/gradle/test-cache",
            f"SX_TEST_RUN={run_id}",
        ],
        "Tty": True,  # saída já em texto puro no follow
        "HostConfig": {
            "Binds": [f"{settings.build_volume}:/srv/build"],
            "Memory": 6 * 1024**3,
            "NanoCpus": 12_000_000_000,
            "AutoRemove": False,
        },
    }

    print(f"test [{' '.join(suites)}] em {HELPER} (run {run_id})")
    if client.dry_run:
        print("  [dry-run] container de teste não criado")
        return 0

    client.create_and_start(HELPER, spec)
    try:
        client.follow(HELPER, since=0, out=lambda line: print(f"  {line}"))
        code = client.wait(HELPER, timeout=settings.timeout)
    finally:
        client.remove(HELPER)
    if code != 0:
        raise RemoteError(
            f"{code} suíte(s) falharam; relatórios em "
            f"/srv/build/test-run/test-reports/{run_id} (veja `exec init -- ls …`)"
        )
    print(f"  todas as suítes verdes; relatórios em /srv/build/test-run/test-reports/{run_id}")
    return 0
