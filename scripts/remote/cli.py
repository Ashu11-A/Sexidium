#!/usr/bin/env python3
"""Dispatch do CLI remoto. Entrada real: scripts/remote.sh (que carrega o ambiente).

Um binário, um cliente HTTP. "Rodar testes" e "validar a rede" são SUBCOMANDOS, não
ferramentas separadas -- todas falariam com a mesma API, com a mesma chave, sobre a
mesma stack.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import checks  # noqa: E402
import ops  # noqa: E402
import pipeline  # noqa: E402
import scale  # noqa: E402
import tests  # noqa: E402
from config import Settings  # noqa: E402
from portainer import Client  # noqa: E402
from util import RemoteError, UsageError  # noqa: E402

EPILOG = """\
exit codes: 0 ok · 1 falha operacional · 2 uso · 3 auth (401/403) · 4 Portainer
inalcançável · 5 pré-condição ausente. Documentação: docs/deployment.md
"""


def rolling_flags(child):
    """As duas flags que governam a FORMA da rolagem de `update`/`restart`.

    Sem nenhuma delas a escolha é automática e verificável: paralelo com a rede vazia,
    série com alguém online (ver ops.rolling_is_parallel). Elas existem para o operador
    que sabe algo que a contagem não diz.
    """
    group = child.add_mutually_exclusive_group()
    group.add_argument(
        "--parallel",
        action="store_true",
        help="sobe os backends juntos mesmo com jogadores online (todos caem de uma vez)",
    )
    group.add_argument(
        "--serial",
        action="store_true",
        help="um nó por vez, sempre (o comportamento antigo)",
    )


def build_parser():
    parser = argparse.ArgumentParser(
        prog="scripts/remote.sh",
        description="Opera a rede Sexidium no host do Portainer.",
        epilog=EPILOG,
    )
    parser.add_argument("-n", "--dry-run", action="store_true", help="só mostra as mutações")
    parser.add_argument("-y", "--yes", action="store_true", help="não pergunta nada")
    parser.add_argument("-v", "--verbose", action="store_true", help="ecoa cada requisição")
    parser.add_argument("--timeout", type=int, help="timeout HTTP em segundos (padrão 900)")
    parser.add_argument(
        "--boot-timeout",
        type=int,
        default=300,
        help="quanto esperar o 'Done (' de cada nó num rolling restart (0 desliga)",
    )
    sub = parser.add_subparsers(dest="command", metavar="<comando>")

    def add(name, help_text, fn):
        child = sub.add_parser(name, help=help_text)
        child.set_defaults(run=fn)
        return child

    add("up", "zero → rede no ar (db-init, sync, stack, provision, boot)", ops.cmd_up)
    update = add("update", "sync + provision + restart — o comando do dia a dia", ops.cmd_update)
    rolling_flags(update)

    sync = add("sync", "envia o repo local para o volume de build, sem tocar no estado dos nós", ops.cmd_sync)
    sync.add_argument("dirs", nargs="*", help="subdiretórios (padrão: o repo inteiro)")
    sync.add_argument(
        "--prune-repo",
        action="store_true",
        help="apaga /srv/build/repo antes de enviar (opt-in; nunca toca no estado dos nós)",
    )

    add("stack", "cria/atualiza o stack a partir de docker/stack.sexidium.yml", ops.cmd_stack)
    add("provision", "roda o container init e acompanha até o carimbo", ops.cmd_provision)

    restart = add("restart", "rolling worker-N → … → lobby → proxy", ops.cmd_restart)
    restart.add_argument("--only", metavar="NÓ", help="reinicia só este nó")
    rolling_flags(restart)

    add("down", "para a rede: proxy primeiro, depois lobby, depois workers", ops.cmd_down)

    status = add("status", "a validação da rede (13 checagens)", checks.cmd_status)
    status.add_argument("--json", action="store_true", help="saída para máquina")
    status.add_argument(
        "--wait", type=int, default=0, metavar="S", help="repete até convergir por até S segundos"
    )

    test = add("test", "roda as suítes dentro do host (scripts|gradle|bot)", tests.cmd_test)
    test.add_argument("suites", nargs="*", help="padrão: todas")
    test.add_argument("--no-sync", action="store_true", help="usa o repo que já está no volume")

    logs = add("logs", "logs de qualquer nó (proxy|lobby|worker-N|init|db)", ops.cmd_logs)
    logs.add_argument("node")
    logs.add_argument("-n", "--lines", type=int, default=200)
    logs.add_argument("-f", "--follow", action="store_true")

    execute = add("exec", "escape hatch: roda um comando dentro de um nó", ops.cmd_exec)
    execute.add_argument("node")
    execute.add_argument("cmd", nargs=argparse.REMAINDER)

    add("db-init", "database + usuário + grants no MariaDB (idempotente)", ops.cmd_db_init)

    secrets = add("secrets", "impressões digitais dos segredos do stack", ops.cmd_secrets)
    secrets.add_argument("--show", action="store_true", help="imprime os valores (exige --yes)")
    secrets.add_argument("--rotate", metavar="NOME", help="gera um valor novo para este segredo")

    # --- pipeline: a atualização rolante, retomável e reversível ---------------
    # `update` continua sendo a rota simples e continua desconectando quem está online.
    # Isto é a rota que drena cada nó antes de tocar nele, journala cada sub-etapa e
    # sabe voltar atrás -- e por isso tem flags que `update` não precisa ter.
    pipe = sub.add_parser("pipeline", help="atualização rolante: sem queda, retomável, reversível")
    pipe_sub = pipe.add_subparsers(dest="pipeline_command", metavar="<sub>")

    def add_pipe(name, help_text, fn):
        child = pipe_sub.add_parser(name, help=help_text)
        child.set_defaults(run=fn)
        return child

    deploy = add_pipe("deploy", "build → install → canário → resto → proxy", pipeline.cmd_deploy)
    deploy.add_argument("--canary", metavar="NÓ", help="qual nó vai primeiro (padrão: o último worker)")
    deploy.add_argument("--drain-timeout", type=int, default=300)
    deploy.add_argument(
        "--on-drain-timeout",
        choices=("abort", "force"),
        default="abort",
        help="abort (padrão) devolve o nó e não repina nada; force custa até 30s de estado por jogador",
    )
    deploy.add_argument("--soak", type=int, default=120, help="quanto observar antes de seguir")
    deploy.add_argument("--skip-tests", action="store_true", help="emergência: pula as suítes")
    deploy.add_argument(
        "--allow-unhealthy",
        metavar="NOMES",
        default="",
        help="checagens que já falham e podem ser assumidas (nomeadas, nunca 'todas')",
    )
    deploy.add_argument(
        "--allow-lobby-disconnect",
        action="store_true",
        help="assume a queda ao rolar o único lobby (NUNCA é o padrão)",
    )
    deploy.add_argument(
        "--maintenance-window",
        action="store_true",
        help="assume a queda da rede inteira ao reiniciar o único proxy",
    )
    # A perna do proxy é pulada quando o sha do JAR do plugin não muda -- e só o jar entra nesse
    # sha. Uma mudança de config do proxy (plugins/skinsrestorer/config.yml, velocity.toml) ou um
    # jar de terceiro novo ficam invisíveis para ele, e o Velocity não recarrega nada a quente:
    # "pulado" vira "não aplicado", em silêncio. Esta flag é como se pede a perna mesmo assim.
    deploy.add_argument(
        "--force-proxy",
        action="store_true",
        help="roda a perna do proxy mesmo com o jar igual (config/jar de terceiro; exige --maintenance-window)",
    )
    # A reversão (convergência ou abort) para nós que já foram DEVOLVIDOS à rede, então
    # ela drena antes de parar como a rolagem faz. Esta flag é para o nó que não responde
    # mais à API: sem ela o prazo de dreno inteiro é esperado por nada.
    deploy.add_argument(
        "--skip-drain",
        action="store_true",
        help="não drena antes de reverter (só para um nó inalcançável)",
    )

    resume = add_pipe("resume", "retoma o run gravado do ponto de interrupção", pipeline.cmd_resume)
    resume.add_argument("--run-id", dest="run_id", help="id do run (padrão: o gravado como atual)")
    for flag, default in (("--drain-timeout", 300), ("--soak", 120)):
        resume.add_argument(flag, type=int, default=default)
    resume.add_argument("--allow-lobby-disconnect", action="store_true")
    resume.add_argument("--maintenance-window", action="store_true")
    resume.add_argument("--force-proxy", action="store_true")
    resume.add_argument("--skip-drain", action="store_true")

    add_pipe("status", "lock, run atual e o pin de cada nó", pipeline.cmd_pipeline_status)
    add_pipe("builds", "o que existe no store de builds", pipeline.cmd_builds)

    abort = add_pipe("abort", "libera o lock, revertendo o que foi repinado", pipeline.cmd_abort)
    abort.add_argument("--run-id", dest="run_id")
    abort.add_argument(
        "--force-unlock",
        action="store_true",
        help="só libera o lock, sem reverter (para sair de needs-human)",
    )
    abort.add_argument(
        "--skip-drain",
        action="store_true",
        help="não drena antes de reverter (só para um nó inalcançável)",
    )

    pin = add_pipe("pin", "pina um nó em qualquer build retido e o reinicia", pipeline.cmd_pin)
    pin.add_argument("node")
    pin.add_argument("build")

    # A remoção FÍSICA de um nó é manual e continua sendo (as portas saem do índice em
    # SX_NODES). Isto é só a outra metade: o banco parar de acreditar num nó que não
    # existe mais. Sem ela a linha em `network_nodes` nunca sai -- nada dá DELETE nela,
    # ela só vira DOWN -- e o `status` fica FAIL para sempre, o que faz o `deploy`
    # recusar subir e ensina o operador a ignorar vermelho.
    forget = add_pipe("forget", "apaga do banco um nó já removido de verdade", pipeline.cmd_forget)
    forget.add_argument("node")

    # --- escala ----------------------------------------------------------------
    watch = add("watch", "laço de observação + avaliação do autoscaler", scale.cmd_watch)
    watch.add_argument("--interval", type=int, default=15)
    watch.add_argument("--once", action="store_true", help="uma amostra e sai")
    watch.add_argument("--observe-only", action="store_true", help="avalia e NÃO age")
    watch.add_argument("--min-workers", type=int, default=2)
    watch.add_argument("--max-workers", type=int, help="padrão: calculado da memória do host")
    watch.add_argument("--max-actions-per-hour", type=int, default=3)

    scale_cmd = add("scale", "uma ação de escala à mão (up|down)", scale.cmd_scale)
    scale_cmd.add_argument("direction", choices=("up", "down"))
    scale_cmd.add_argument("--min-workers", type=int, default=2)
    scale_cmd.add_argument("--max-workers", type=int)

    add("version", "preflight: versão do Docker + a chave funciona?", ops.cmd_version)
    return parser


def normalize(parser, args):
    """Defaults que só existem em alguns subcomandos, mas são lidos por funções comuns.

    `up`/`update` encadeiam sync, restart e stack; cada um lê flags do outro. Preencher
    aqui é mais barato que espalhar getattr(args, …, default) por três arquivos.
    """
    for name, value in (
        ("dirs", []),
        ("prune_repo", False),
        ("only", None),
        ("parallel", False),
        ("serial", False),
        ("json", False),
        ("wait", 0),
        ("suites", []),
        ("no_sync", False),
        ("show", False),
        ("rotate", None),
        ("cmd", []),
        ("lines", 200),
        ("follow", False),
        # Defaults do pipeline/escala lidos por funções compartilhadas. `up` e `update`
        # encadeiam sync/provision/restart e passam pelos mesmos caminhos, então é mais
        # barato preencher aqui que espalhar getattr(args, …, default) por cinco arquivos.
        ("canary", None),
        ("drain_timeout", 300),
        ("on_drain_timeout", "abort"),
        ("soak", 120),
        ("skip_tests", False),
        ("allow_unhealthy", ""),
        ("allow_lobby_disconnect", False),
        ("maintenance_window", False),
        ("force_proxy", False),
        ("run_id", None),
        ("force_unlock", False),
        ("skip_drain", False),
        ("interval", 15),
        ("once", False),
        ("observe_only", False),
        ("min_workers", 2),
        ("max_workers", None),
        ("max_actions_per_hour", 3),
    ):
        if not hasattr(args, name):
            setattr(args, name, value)
    if args.command is None:
        parser.print_help()
        raise UsageError("nenhum comando informado")
    # `pipeline` sem sub-comando: um grupo sem folha não tem `run`, e sem isto o CLI
    # morreria com AttributeError em vez de mostrar a ajuda do grupo.
    if not hasattr(args, "run") or not callable(getattr(args, "run", None)):
        parser.parse_args([args.command, "--help"])
        raise UsageError(f"{args.command}: falta o sub-comando")
    return args


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        args = normalize(parser, args)
        settings = Settings()
        settings.require()
        if args.timeout:
            settings.timeout = args.timeout
        client = Client(settings, verbose=args.verbose, dry_run=args.dry_run)
        # Preflight em TODO comando: sem ele uma chave expirada aparece como
        # "container não encontrado" no meio de um rolling restart, com metade da
        # rede parada. Aqui vira exit 3 antes de qualquer mutação.
        client.version()
        return args.run(client, settings, args) or 0
    except RemoteError as exc:
        print(f"erro: {exc}", file=sys.stderr)
        return exc.code
    except KeyboardInterrupt:
        print("\ninterrompido", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
