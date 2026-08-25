# Sexidium Documentation

> Multi-platform Minecraft party/minigame plugin: a platform-agnostic **core** that runs
> unchanged on **Paper** and **NeoForge**, with a bundled **Discord bot**, a localhost
> **HTTP API**, persistent **ranks**, a unified **lobby** (party + queue + friends), managed
> **disposable match worlds**, player-built persistent **Experiences**, and competitive
> **minigames**.

This folder is the documentation index. Start here, then jump to a topic page. The **code is
the source of truth**; every page below is a derived view and carries a *"Keeping this current"*
footer naming the files it tracks. See [§4 Maintenance policy](#4-documentation-maintenance-policy).

---

## 1. Project overview

Sexidium is one plugin/mod built around a strict **core ↔ adapter** split. All gameplay,
command, social, persistence, and networking logic lives in a platform-agnostic module
(`packages/core`, package `com.sexidium.core`) that never references a Bukkit or
Minecraft/NeoForge class. Each server platform supplies a thin **adapter** module —
`packages/module-paper` (modern Paper/Adventure API) and `packages/module-neoforge`
(reflection-based, no compile-time Minecraft deps) — implementing the core's platform SPI
(`ServerAdapter`, `PlayerAdapter`, `WorldAdapter`, `SchedulerAdapter`, `UiAdapter`, event
bridge, and ~14 more). The core is handed exactly one `ServerAdapter` at startup and drives
everything through it, so the same engine produces identical behavior on both platforms.

Gameplay is organized as **matches**. An admin runs `/sx start minigames <mode> [players]`;
the core gathers a roster, leases a disposable temporary world, teleports participants in, and
runs a `Game` that reacts to translated `GameEvent`s. The **`minigames`** category ships 5
rounds (Race, Gather & Duel, TNT War, Combat, Fugitive/Manhunt). Separately, any player builds
their own **Experience** with `/sx start experience <challenge…>` — a persistent survival world
composing any of the 27 chaotic **challenges** (Double Drops, Shared Life, XP Health, …) that
interoperate through shared drop/damage/health pipelines. Social and progression features —
**friends** (SQLite), in-memory **party**, **queue**, and **invites** — are unified under a
single `LobbyManager`/`/lobby`. A **rank/points** system awards per participation, kill, and win.

Beyond the server, Sexidium supervises an out-of-process **TypeScript Discord bot** (`bot/`, run
via `bun`, downloaded per-OS at startup). The bot reaches the server over a localhost **HTTP API**
(`GET /rank`, `GET /player`, `GET /discord`, token-gated `POST /command` and `POST /auth/link`)
and gives ranks a Discord front end (`/rank`, `/leaderboard`, `/mc`, `/auth`, `/announce`,
`/event`, `/syncranks`).

> Sexidium is **server-side only** — vanilla clients connect with no companion mod; HUDs,
> scoreboards, menus, and overlays render server-side (an optional auto-served resource pack adds
> custom menu art). See the root [`README.md`](../README.md) for the user-facing feature tour.

---

## 2. Module map

| Path | What lives here |
|------|-----------------|
| [`packages/core`](../packages/core) | Platform-agnostic engine: game framework (`game/`), 5 minigames + 27 composable challenges (`game/modes/minigames/`, `game/experience/`), experiences/teams/persistence (`game/experience/`, `game/team/`, `game/persist/`), event model + router (`event/`), command service (`command/`), unified lobby/social (`lobby/`), worlds (`world/`), menus + scene art (`menu/`), UI/i18n/decor (`i18n/`, `decor/`), ranks/auth/data (`rank/`, `auth/`, `data/`), networking + bot manager (`net/`, `bot/`), platform SPI (`platform/`), bundled resources (`config.yml`, DB migrations). |
| [`packages/module-paper`](../packages/module-paper) | Paper adapter: `PaperSexidiumPlugin` bootstrap, `PaperEventBridge`, Adventure/Bukkit player/world/scheduler/UI/inventory/menu adapters, `PaperWorldControl` (unified world layer) + Multiverse lobby, the BetterHud HUD driver, native scoreboard panels, kits, `plugin.yml`. |
| `packages/module-neoforge` *(not in the tree)* | NeoForge adapter (MC 1.21.x): reflection-only port via `NeoForgeReflector`; `NeoForgeEventBridge` synthesizes move/inventory/sneak events from `PlayerTickEvent`; cooperative tick scheduler; hand-rolled vanilla world creation; ASM-generated chest-menu classes; global scoreboard/boss-bar UI. |
| [`bot/`](../bot) | TypeScript Discord bot (`bun` + discord.js + `@constatic/base`). Slash commands under `src/discord/`, auth/api helpers, `src/index.ts` entry. Launched and supervised by the Java `BotManager`; **not** a Gradle subproject (bundled as a resource into the adapter jars). |

The Gradle build (`settings.gradle.kts`) includes `:packages:core`, `:packages:module-paper`, and
`:packages:module-velocity` (the proxy plugin used by the networked deployment — see
[operations/deployment.md](operations/deployment.md)). **`:packages:module-neoforge` is not in the build**: the NeoForge
adapter described above is documented history, not something the build currently produces.

---

## 3. Documentation index

Six folders, one per family of domains. Each carries its own `README.md` with the reading order and
the "what is *not* here" boundary; the tables below are the flat view.

### [`architecture/`](architecture/) — how the plugin is put together

| Document | Covers |
|----------|--------|
| [architecture/overview.md](architecture/overview.md) | System map: the core ↔ adapter split, the `SexidiumCore` wiring graph, startup/shutdown lifecycle, command/event flow, and a subsystem inventory pointing at every page below. **Start here.** |
| [architecture/platform-and-adapters.md](architecture/platform-and-adapters.md) | The platform SPI (`ServerAdapter` & friends, value-model records/enums, default-method capability degradation, noop/headless impls) **and** both adapter implementations (Paper, NeoForge) with a parity-gaps table. |
| [architecture/game-framework.md](architecture/game-framework.md) | The match engine only: `GameManager`, `ActiveMatch`, `AbstractGame`/`BaseTimedGame`, `GameState`, registry/descriptors/factories, countdowns, event routing, mid-match join, and the start → run → end → teardown lifecycle. |

### [`gameplay/`](gameplay/) — what players play

| Document | Covers |
|----------|--------|
| [gameplay/minigames.md](gameplay/minigames.md) | The 5 competitive minigames (Race, Gather, TNT War, Combat, Fugitive): phases, teams/roles, win detection, min players, aliases, battle maps, config. Timed, with a winner. |
| [gameplay/experiences.md](gameplay/experiences.md) | Player-built persistent Experiences: the 27 challenges, how they **compose** via shared drop/damage/health pipelines + `ChallengeContext`, world-type selection, the auto team system, ownership/join model, and persistence. |
| [gameplay/chaos.md](gameplay/chaos.md) | The third family: one shared open world where **each player** gets an independent random twist set, reshuffled on a timer (previous effects reset first). Reuses the experience composition layer, scoped per player. |
| [gameplay/lobby-worlds-and-social.md](gameplay/lobby-worlds-and-social.md) | The pre-match layer everything arrives through: the unified `LobbyManager` state machine (party + queue + friends + invites), disposable temp-world leasing + warm pool + reconnect/cleanup, world naming/generation, on-disk layout, lobby protection/nav/HUD, and lobby NPCs. |
| [gameplay/challenges/](gameplay/challenges/) | Research notes on the 15 YouTube "Minecraft, but…" formats that seeded the catalog — one page per format with the rule, its edge cases, and (where written back) the shipped server-safe bound. Research input, not a code reference. |

### [`interface/`](interface/) — what players touch

| Document | Covers |
|----------|--------|
| [interface/commands.md](interface/commands.md) | The `/sx` (`/sexidium`) command tree and `/lobby`: every subcommand + args + permission bucket + tab completion, and the two adapter command bridges. |
| [interface/menus.md](interface/menus.md) | The declarative menu framework (`MenuCatalog`/`MenuTab`/`MenuView`/`MenuButton`), `MenuArt` as single source of truth, the native glyph/`item_model` **scene art** pipeline + auto-served resource pack, and per-platform menu adapters (incl. Bedrock Cumulus forms). |
| [interface/ui-interaction-system.md](interface/ui-interaction-system.md) | The one item model shared by chest menus **and** the dynamic lobby hotbar: the `UiItem` render spec, `MenuButton`/`HotbarItem`, the single Paper materializer, `HotbarController`/`HotbarProfile`/`HotbarScope`, cross-world item hygiene, and how to add an item. |
| [interface/ui-and-localization.md](interface/ui-and-localization.md) | Boss bars, native scoreboard HUD panels, the declarative HUD-driver system (BetterHud + sidebar fallback), popups, per-player UI release/restore, the decor (hologram) system, and `<lang:>` client-side localization + `MessageService`. |

> **Cross-play is a hard rule.** A UI technique is acceptable only if it has a defined answer for a
> Java client with the pack, a Java client without it, and a Bedrock client via Geyser/Floodgate —
> which never receives a Java pack. See [interface/README.md](interface/).

### [`operations/`](operations/) — running the network

| Document | Covers |
|----------|--------|
| [operations/deployment.md](operations/deployment.md) | The host and the node lifecycle: Portainer topology and adding a node, the shared Paper installation vs. each node's working directory (and what can never be shared), volume layout, credentials, deploy/update/restart, running the test suites remotely, the `status` checks, logs, rollback, and the `scripts/remote.sh` reference. |
| [operations/network-transfer.md](operations/network-transfer.md) | The world-ownership protocol between nodes: the `world_placements` state machine, the per-grant **fence** that makes an evicted holder find out, the heartbeat/lease/node-timeout timing invariant, the addressed idempotent transfer ticket + loop breaker, boot reconciliation, and `/sx admin net`. |
| [operations/networking-bot-ranks.md](operations/networking-bot-ranks.md) | Out-of-process services and the shared schema: the loopback HTTP API, the resource-pack server, the WebSocket RPC bridge, Discord bot process management, the token-gated auth-linking flow, ranks/points, and the `sexidium.db` schema + migrations. |

### [`reference/`](reference/) — re-verified logs

| Document | Covers |
|----------|--------|
| [reference/tech-decisions.md](reference/tech-decisions.md) | Decision log that only grows: UI/GUI techniques evaluated (forms, maps, display entities, `/dialog`, native screens) and Modrinth libraries/mechanics evaluated against the dual-adapter / no-fat-jar rule — each marked implemented / future / rejected, with the reason. |
| [reference/known-issues.md](reference/known-issues.md) | Severity-tagged, re-verified findings (open bugs, parity gaps, design notes) with line refs, plus what was fixed since the last review. Re-checked and pruned each pass, never appended to blindly. |

### [`guides/`](guides/) — how to change it

Task-oriented **how-to-change-it** documents, one per module — written to be loaded as base context
(by a contributor or an AI agent) before working on that module. The pages above describe how a system
*behaves*; a guide prescribes the *workflow*: which files to touch, in what order, which drift-guard
tests will fail, and which doc to update in the same change. Each ends in a checklist.

| Guide | Workflow it prescribes |
|-------|------------------------|
| [guides/add-a-challenge.md](guides/add-a-challenge.md) | Add/modify an experience challenge: catalog registration, composition pipelines, HUD contribution, world-generating (SkyBlock-style) challenges, chest-GUI/icon wiring, config + drift guards. |
| [guides/add-a-minigame.md](guides/add-a-minigame.md) | Add/modify a minigame: descriptor registration, base-class choice, battle maps + in-world editor, HUD, awards/win flow, menu icons, config. |
| [guides/add-a-menu-screen.md](guides/add-a-menu-screen.md) | Add/modify a chest-GUI screen: `MenuService` facade rules, the cross-play (Bedrock) constraints, custom art tables, per-player state hygiene. |
| [guides/add-a-command.md](guides/add-a-command.md) | Add/modify a command: dispatch buckets, the `/sx admin` arg-reslice pattern, tab completion, bilingual i18n. |
| [guides/add-a-platform-capability.md](guides/add-a-platform-capability.md) | Add a platform capability: the default-method seam pattern, capability flags, Paper/NeoForge parity, POJO-fake testing. |
| [guides/work-on-worlds.md](guides/work-on-worlds.md) | Work on managed worlds: leasing seams, naming, linked dimensions, void generation, safe spawn, the structure/loot generation engine, bundled worlds, map-editing tooling. |
| [guides/work-on-the-bot.md](guides/work-on-the-bot.md) | Work on the Discord bot / RPC bridge: the Zod contract as source of truth, slash-command registration, rendered cards, supervision rules. |
| [guides/research-a-youtube-challenge.md](guides/research-a-youtube-challenge.md) | Turn a YouTuber "Minecraft, but…" format into a challenge: harvesting titles/descriptions/transcripts with `yt-dlp`, mining them for the rule and its edge cases, and designing the server-safe bound (radius + per-tick budget + catch-up) before implementing. Front-end to `add-a-challenge.md`. |

These were previously `docs/Prompt.*.md`; [guides/README.md](guides/) carries the full old → new
name table.

---

## 4. Documentation maintenance policy

**These docs are part of the code.** A change that alters behavior is not done until the docs that
describe it are updated in the *same* change. Treat doc drift as a bug.

1. **One domain → one file; one family of domains → one folder.** Each page owns exactly one slice
   of logic, and each of the six folders owns one family of them. Add new material to the page that
   owns the domain; do **not** create a new doc per feature. A genuinely new domain gets one page
   inside whichever of the six folders it belongs to — `architecture/`, `gameplay/`, `interface/`,
   `operations/`, `reference/`, `guides/` — linked from that folder's `README.md` and from this
   index. If it fits none of them, say so explicitly in the change and justify a seventh folder;
   a stray top-level `docs/*.md` is not an option.
2. **Code wins.** When a doc disagrees with the code, the code is correct — fix the doc. Cite
   anchors as `path:line` or `Class#method` so claims stay checkable.
3. **Update-in-the-same-PR.** Each page's *"Keeping this current"* footer lists its authoritative
   source files and the triggers (new class in the domain, signature/behavior change, config key
   added/removed) that require an edit. When you touch those files, touch the page.
4. **Keep it lean.** Prefer editing a sentence over appending a section. Remove stale content
   instead of leaving it contradicted. Cross-link siblings rather than duplicating them.
5. **Re-verify on review.** [reference/known-issues.md](reference/known-issues.md) is re-checked against current code,
   not appended-to blindly — drop fixed findings, keep open ones with fresh line refs.

> Re-running the unification: this set was produced by reading every doc and verifying it
> against the source. To repeat after large drift, re-analyze each domain against its source files
> and rewrite the owning page — do not let per-feature docs accumulate.

---

## 5. Building & running

Gradle multi-module build (JDK 25; built/tested against Paper `26.1.2`). `settings.gradle.kts` has
`:packages:core`, `:packages:module-paper` and `:packages:module-velocity` — the NeoForge module
described in these docs is not currently part of the build.

```bash
./gradlew build     # compiles and runs the JUnit suites (check → test → jacocoTestReport)
```

Test reports land in `build/packages-<module>/reports/tests/test/`, **not** under
`packages/*/build/`. The authoritative gate runs on the deployment host, where no tool can be
missing: `scripts/remote.sh test` (see [operations/deployment.md §8](operations/deployment.md#8-running-tests-remotely)).
Beware that a bare `scripts/test/run.sh` *skips* a stage whose tool is absent and still exits 0 —
run it as `SX_TEST_STRICT=1 scripts/test/run.sh` to make a skip a failure.

Jars are collected under `build/libs/`:

- `build/libs/paper/` — Paper jars (`Sexidium-Paper-*.jar`).
- `build/libs/velocity/` — the proxy plugin, used only by the networked deployment.
- Plain jars carry the bot **source** (need `bun` on `PATH` if the bot is enabled); the
  `-x64` / `-arm64` jars bundle a matching Bun runtime. `node_modules` is never packaged — the
  plugin runs `bun install --no-save` on startup.

Drop the Paper jar into `plugins/` and restart. A default
`config.yml` is generated on first run. Run a minigame: `/sx start minigames race` (admin). Build an
Experience: `/sx start experience xphealth sharedlife` (any player). See the root
[`README.md`](../README.md) for the full config reference.

---

## 6. Mode reference (quick)

Mode/challenge IDs and aliases are normalized (lowercased, with `-`, `_`, and spaces stripped).
Source of truth: [`CoreGameRegistryInitializer.java`](../packages/core/src/main/java/com/sexidium/core/game/CoreGameRegistryInitializer.java)
(minigames) and [`ChallengeCatalog.java`](../packages/core/src/main/java/com/sexidium/core/game/experience/ChallengeCatalog.java)
(challenges).

### Minigames — `/sx start minigames <mode> [players]`

| Mode ID | Display name | Min players | Aliases |
|---------|--------------|:-----------:|---------|
| `race` | Race for Item | 1 | `raceforitem`, `item` |
| `gather` | Gather and Duel | 2 | `duel`, `gatherandduel`, `gatherduel` |
| `tntwar` | TNT War | 2 | `tnt`, `war` |
| `combat` | Combat Item Mode | 2 | `kit`, `kitpvp` |
| `fugitive` | Fugitive | 3 | `manhunt`, `thefugitive`, `fled` |

Full mechanics: [gameplay/minigames.md](gameplay/minigames.md).

### Experience challenges — `/sx start experience <challenge…>`

Composable, not standalone modes. Any combination runs together in one persistent survival world.
All 27, in `ChallengeCatalog` registration order. (Four ids — `evolvingmobs`, `tntmobs`, `lavafloor`,
`midastouch` — are **retired**: gone for good, and ignored rather than refused on old worlds.)

| Challenge ID | Display name | What it does |
|--------------|--------------|--------------|
| `doubledrops` | Double Drops | Blocks and mobs drop double loot. |
| `randomizer` | Randomizer | Every drop is shuffled into a random item. |
| `sharedlife` | Shared Life | Everyone shares a single health bar. |
| `sharedinventory` | Shared Inventory | All players share one live inventory. |
| `xphealth` | XP Health | Your XP level *is* your health. |
| `shrinkingachievements` | Shrinking Achievements | The world border keeps shrinking. |
| `breakonebreakall` | Break One Break All | Break one block, break all of its type. |
| `blockdeleter` | Block Deleter | Breaking a block deletes that type nearby. |
| `randomchunks` | Random Chunks | Chunks convert into random blocks. |
| `walkingblocks` | Walking Blocks | A trail of blocks builds where you walk. |
| `chained` | Chained Together | All players are leashed together. |
| `cleave` | Total Cleave | Each hit cleaves every nearby mob. |
| `growing` | Endless Growth | You grow larger the longer you live. |
| `jumpenchants` | Jump Enchants | Jumping randomly enchants your gear. |
| `mobduplication` | Mob Duplication | Hitting a mob can duplicate it. |
| `chunkbreak` | Chunk Break | Break one block, break its type in that chunk. |
| `lookmultiplies` | Look Multiplies | Everything you look at multiplies. |
| `jumpmultiplies` | Jump Multiplies | Every jump duplicates every entity around you. |
| `randomlayers` | Random Layers | New random layers are added every day. |
| `randomskyblock` | Random Skyblock | One void block gives random items as you break it. |
| `randommob` | Random Mob | Random mobs spawn beside you on a timer. |
| `randomdrops` | Randomized Drops | Blocks and mobs drop a random item each time. |
| `randomevents` | Random Events | A chaotic random event fires every so often. |
| `classicskyblock` | Classic Skyblock | The classic vanilla Skyblock island, with a Nether mirror. |
| `omnichunk` | Omni Chunk | Every block you place or break copies into every chunk. |
| `layereddimensions` | Layered Dimensions | One chunk of stacked layers in every dimension — dig down through all of them. |
| `deathresets` | Death Resets | Hardcore with no goals — when anyone dies, everyone resets and the world is regenerated. |

Full mechanics + composition rules: [gameplay/experiences.md](gameplay/experiences.md).

---

## 7. Glossary

| Term | Meaning |
|------|---------|
| **Mode** | A registered game type (normalized ID + aliases) in the **`minigames`** category, defined by a `GameModeDescriptor` (id, category, display name, min players, aliases) + a `GameFactory`. Registered in `CoreGameRegistryInitializer`. |
| **Challenge** | A composable rule selected into an **Experience** (`ChallengeCatalog`). Challenges interoperate through shared drop/damage/health pipelines rather than running in isolation. |
| **Experience** | A player-built persistent survival world composing any combination of challenges, started with `/sx start experience <challenge…>`. Owned, never auto-ended; the owner's lobby group joins, others request access. |
| **Match** | One live instance of a mode (`ActiveMatch`: match UUID, mode ID/args, the `Game`, its `WorldLease`, created-at). `GameManager` tracks matches and players, ticks timers, routes events, and tears down on end/empty. |
| **World lease** | A handle (`WorldLease`/`LeasedWorld`) to a disposable per-match temporary world from the world layer (`AbstractWorldControl`). Acquired on start, released (unloaded/deleted, or persisted for Experiences) on end. The lobby is always preserved. |
| **Adapter** | A platform-specific implementation of the core's SPI (e.g. `PaperServerAdapter`). The core depends only on these interfaces + value records, never on Bukkit/Minecraft types. |
| **Lobby (group)** | The unified social/matchmaking unit managed by `LobbyManager` — a leader + members with a state machine (idle → queued → configured), folding the former party, queue, friends-join, and invites under one model and `/lobby`. |
| **Friend** | A SQLite-persisted relationship (`FriendService`, package `com.sexidium.core.lobby`), established by request/accept; gates who can join a lobby/match. |

---

*This index is a navigation hub. For open bugs and parity gaps, see [reference/known-issues.md](reference/known-issues.md).
For the docs-stay-current contract, see [§4](#4-documentation-maintenance-policy).*
