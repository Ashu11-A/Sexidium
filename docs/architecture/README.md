# Architecture

How the plugin is put together — the core ↔ adapter split, the seams between them, and the match
engine every game family is built on. **No game content lives here.** What a specific minigame,
challenge, or lobby feature *does* belongs in [`../gameplay/`](../gameplay/); what a player sees or
types belongs in [`../interface/`](../interface/).

## Read in this order

| # | Document | Covers |
|---|----------|--------|
| 1 | [overview.md](overview.md) | The big idea: platform-agnostic `packages/core` + thin adapters, the `SexidiumCore` construction graph, startup/shutdown ordering, command/event flow in both directions, and a subsystem inventory that points at every other page in these docs. |
| 2 | [platform-and-adapters.md](platform-and-adapters.md) | The SPI itself (`ServerAdapter`, `PlayerAdapter`, `WorldAdapter`, `SchedulerAdapter`, `UiAdapter`, event bridge, …), the immutable value model, default-method capability degradation and noop/headless impls — then both adapter implementations (Paper, NeoForge) with a parity-gaps table. |
| 3 | [game-framework.md](game-framework.md) | The match engine only: `GameManager`, `ActiveMatch`, `AbstractGame`/`BaseTimedGame`, `GameRegistry` + descriptors + factories, `GameState` transitions, countdowns, the `GameEvent` router, mid-match join, reconnect/pending sessions, and the start → run → end → teardown lifecycle. |

Start at `overview.md` even if you already know where you are going — it is the only page that names
every subsystem and says which document owns it.

## What is *not* here

- **Game content** — the 5 minigames, the 27 composable challenges, chaos mode, lobby/worlds/social:
  [`../gameplay/`](../gameplay/).
- **Player-facing surfaces** — commands, chest menus, hotbar, HUD and i18n:
  [`../interface/`](../interface/).
- **Running it** — hosts, nodes, world ownership across the network, the Discord bot process:
  [`../operations/`](../operations/).

## Changing it

Adding a new capability that core needs from the server (a new SPI seam, a capability flag, a
default-method fallback) is a workflow, not a description — follow
[`../guides/add-a-platform-capability.md`](../guides/add-a-platform-capability.md). Adding a new game
*mode* to the framework is [`../guides/add-a-minigame.md`](../guides/add-a-minigame.md).
