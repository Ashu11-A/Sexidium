---
name: portainer
description: Controlar o Portainer via API REST — listar/iniciar/parar/reiniciar/remover containers, gerenciar stacks (deploy, update, start/stop), imagens, volumes, redes e ver logs. Use quando o usuário mencionar Portainer, containers Docker no servidor, stacks, ou quiser gerenciar o ambiente Docker remoto.
allowed-tools: Bash, Read
---

# Portainer Skill

Esta skill permite controlar um servidor Portainer (CE/BE) via API REST.

## Requisitos

Credenciais em `~/.claude/secrets/portainer.env` (carregadas automaticamente pelo script):

- `PORTAINER_URL` — URL base (ex.: `https://shared-portainer.marcosbrendon.com`)
- `PORTAINER_TOKEN` — Access Token (`Settings > My account > Access tokens`). Enviado como header `X-API-Key`. **Tem prioridade** sobre user/senha.
- `PORTAINER_USER` / `PORTAINER_PASS` — opcional; usados para gerar JWT via `/api/auth` **apenas** se `PORTAINER_TOKEN` estiver vazio.
- `PORTAINER_ENDPOINT` — opcional; Id do ambiente (endpoint) padrão. Se vazio, a skill usa o primeiro endpoint disponível.

Runtime: **Node.js** (usa `fetch` nativo, Node 18+).

## Como usar

```bash
node ~/.claude/skills/portainer/portainer.mjs <comando> [opções]
```

Opções globais:
- `--endpoint <id>` — sobrescreve o endpoint para o comando.
- `--json` — saída crua em JSON (útil pra processar).

## Comandos

### Status / ambiente
```bash
node ~/.claude/skills/portainer/portainer.mjs status            # versão + lista de endpoints
node ~/.claude/skills/portainer/portainer.mjs endpoints         # lista os ambientes (Id, nome, tipo, status)
```

### Containers
```bash
node ~/.claude/skills/portainer/portainer.mjs ps                # containers em execução
node ~/.claude/skills/portainer/portainer.mjs ps --all          # todos (inclui parados)
node ~/.claude/skills/portainer/portainer.mjs start   <id|nome>
node ~/.claude/skills/portainer/portainer.mjs stop    <id|nome>
node ~/.claude/skills/portainer/portainer.mjs restart <id|nome>
node ~/.claude/skills/portainer/portainer.mjs kill    <id|nome>
node ~/.claude/skills/portainer/portainer.mjs rm      <id|nome> [--force]
node ~/.claude/skills/portainer/portainer.mjs logs    <id|nome> [--tail 200]
node ~/.claude/skills/portainer/portainer.mjs inspect <id|nome>

# Executar comandos dentro de um container (Docker exec via proxy do Portainer)
node ~/.claude/skills/portainer/portainer.mjs exec <id|nome> -- npm run seed                       # argv literal após "--"
node ~/.claude/skills/portainer/portainer.mjs exec <id|nome> "ls -la /app && cat package.json"     # roda via sh -c
node ~/.claude/skills/portainer/portainer.mjs exec <id|nome> -- node -v --user node --workdir /app
```
O `<id|nome>` aceita o nome do container, o Id completo ou um prefixo do Id.

O comando `exec` é **síncrono e não-interativo**: cria o exec, roda até terminar, imprime stdout/stderr (desmultiplexados) e reporta o exit code. Use `--` para passar o comando como argv literal (sem shell), ou passe uma única string entre aspas para rodar via `sh -c`. Flags opcionais: `--user <usuário>` (ex.: `node`, `root`) e `--workdir <dir>`.

```bash
# Shell INTERATIVO (mesmo canal do Console da UI — via WebSocket)
node ~/.claude/skills/portainer/portainer.mjs shell <id|nome>                 # tenta bash, cai pra sh
node ~/.claude/skills/portainer/portainer.mjs shell <id|nome> --cmd "/bin/sh" --user root
```

O comando `shell` abre uma sessão **interativa de verdade** (TTY) ligando o stdin/stdout do seu terminal ao container, via o WebSocket `/api/websocket/exec` do Portainer. Saia com `exit` ou `Ctrl+D`.

**Requer JWT:** o WebSocket do Portainer **não aceita a API key** (`PORTAINER_TOKEN`) — só um JWT emitido por login. Portanto o `shell` só funciona se `PORTAINER_USER` e `PORTAINER_PASS` estiverem definidos no `portainer.env` (os demais comandos continuam usando a API key normalmente). É um recurso para uso humano no terminal — não é automatizável por um agente, que só consegue chamadas one-shot (use `exec` para isso).

### Stacks
```bash
node ~/.claude/skills/portainer/portainer.mjs stacks                    # lista stacks
node ~/.claude/skills/portainer/portainer.mjs stack-get   <id|nome>     # detalhes + arquivo compose
node ~/.claude/skills/portainer/portainer.mjs stack-start <id|nome>
node ~/.claude/skills/portainer/portainer.mjs stack-stop  <id|nome>
node ~/.claude/skills/portainer/portainer.mjs stack-update <id|nome> --file ./docker-compose.yml   # redeploy com novo compose
node ~/.claude/skills/portainer/portainer.mjs stack-rm    <id|nome>
```

### Imagens / volumes / redes
```bash
node ~/.claude/skills/portainer/portainer.mjs images
node ~/.claude/skills/portainer/portainer.mjs image-pull <imagem:tag>
node ~/.claude/skills/portainer/portainer.mjs volumes
node ~/.claude/skills/portainer/portainer.mjs networks
```

## Notas

- Operações destrutivas (`rm`, `stack-rm`, `kill`) só executam o que foi pedido; confirme com o usuário antes de remover algo em produção.
- Endpoints do tipo Docker local/agent funcionam via proxy `/api/endpoints/<id>/docker/...`. Stacks usam `/api/stacks`.
- Se o token expirar ou for inválido, a resposta será `401/403` — gere um novo Access Token no Portainer.
