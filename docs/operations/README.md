# Operations

Running the thing. Three documents that are easy to confuse — they sit at three different layers, and
the fastest way to pick one is to ask *what is being owned*.

| Document | The layer | Owns |
|----------|-----------|------|
| [deployment.md](deployment.md) | **The host and the node lifecycle** | Portainer topology, adding a node, the shared Paper installation vs. each node's working directory (and what can never be shared), volume layout, credentials, deploy/update/restart, running the test suites remotely, the `status` checks, logs, rollback. |
| [network-transfer.md](network-transfer.md) | **The world-ownership protocol between nodes** | Who may open a given experience world, and how a player reaches that node: the `world_placements` state machine, the per-grant **fence** that makes an evicted holder find out, the heartbeat/lease/node-timeout timing invariant, the addressed idempotent transfer ticket + loop breaker, boot reconciliation, `/sx admin net`. |
| [networking-bot-ranks.md](networking-bot-ranks.md) | **Out-of-process services and the shared schema** | The loopback `ApiServer`, the public `ResourcePackServer`, the WebSocket RPC bridge, the TypeScript Discord bot's process lifecycle, the token-gated auth-linking flow, ranks/points, and the `sexidium.db` schema + migrations. |

Put another way: `deployment.md` is *containers*, `network-transfer.md` is *region files and player
routing*, `networking-bot-ranks.md` is *ports, the bot, and the database*. A symptom like "the world
opened on two nodes" is the second; "the bot went away" is the third; "the node will not come up" is
the first.

## Entry point

One CLI drives the network. Start here before opening any of the three:

```bash
scripts/remote.sh up        # zero → network live
scripts/remote.sh update    # the day-to-day command: sync + provision + rolling restart
scripts/remote.sh status    # network validation
```

`scripts/remote.sh` is the remote twin of `scripts/net.sh`, which runs the same network as local
processes — same verbs, different substrate. Full reference: [deployment.md](deployment.md). The
`sexidium-deploy` skill (`.claude/skills/sexidium-deploy/`) wraps the same verbs for agent use.

## When it misbehaves

Check [`../reference/known-issues.md`](../reference/known-issues.md) before debugging. It is
re-verified against current code each pass and is severity-tagged, so a symptom you are chasing may
already be a logged open finding with line refs — including the "re-verify outside core" section that
covers scripts and deployment glue.
