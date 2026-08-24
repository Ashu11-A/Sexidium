"""Alertas por webhook do Discord. Quatro níveis, e SÓ em transição de estado.

"Só em transição" é a regra que separa alerta de ruído. Um `watch` de 15 s que
dispara a cada amostra manda 240 mensagens por hora dizendo a mesma coisa, e o efeito
prático de um canal assim é que ninguém lê o alerta que importava.

Transporte por urllib da stdlib, pelo mesmo motivo que o resto do CLI: este repo não
tem dependência de runtime em Python. A URL entra em SECRET_KEYS, então
Settings.secret_values() a entrega a util.redact automaticamente e ela não pode vazar
num corpo de erro do Portainer.

Deliberadamente NÃO a ponte RPC do bot: `BOT_HOST` é uma capability do papel PROXY, e
o módulo Velocity nunca constrói SexidiumCore -- o BridgeClient não sobe em lugar
nenhum desta topologia. Um webhook são 30 linhas e não depende de nada.
"""

import json
import urllib.error
import urllib.request

INFO, WARN, ERROR, CRITICAL = "info", "warn", "error", "critical"

# Cor da barra lateral do embed do Discord. Um alerta de rollback tem de ser
# distinguível de um "run iniciado" antes de a pessoa ler uma palavra.
COLORS = {INFO: 0x5865F2, WARN: 0xFEE75C, ERROR: 0xED4245, CRITICAL: 0x992D22}
PREFIX = {INFO: "•", WARN: "!", ERROR: "✗", CRITICAL: "‼"}


class Alerts:
    def __init__(self, settings, url=None, dry_run=False, echo=print):
        self.url = url or ""
        self.dry_run = dry_run
        self.echo = echo
        self.settings = settings
        self._seen = {}

    def fire(self, level, key, message, force=False):
        """Dispara quando o ESTADO daquela chave muda (ou com force=True).

        `key` identifica a condição, não a ocorrência: "capacity.exhausted",
        "check.db.nodes", "rollback.worker-3". Mesma chave com a mesma mensagem duas
        vezes seguidas = nada é enviado.
        """
        previous = self._seen.get(key)
        self._seen[key] = (level, message)
        if not force and previous == (level, message):
            return False
        self.echo(f"  {PREFIX.get(level, '•')} [{level}] {key}: {message}")
        if not self.url or self.dry_run:
            return False
        payload = {
            "embeds": [
                {
                    "title": f"[{level.upper()}] {key}",
                    "description": message[:3900],
                    "color": COLORS.get(level, 0x5865F2),
                }
            ]
        }
        request = urllib.request.Request(
            self.url, data=json.dumps(payload).encode(), method="POST"
        )
        request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=10):
                return True
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            # Um alerta que não sai NUNCA pode derrubar a operação que o gerou: o
            # webhook é observabilidade, e falhar um deploy porque o Discord está fora
            # do ar seria trocar um problema pequeno por um grande.
            self.echo(f"  (aviso: não consegui enviar o alerta: {exc})")
            return False

    def clear(self, key):
        """Esquece a chave, para que a próxima ocorrência volte a ser uma transição."""
        self._seen.pop(key, None)
