<div align="center">

# Sexidium

<p>
<img alt="Stars" src="https://img.shields.io/github/stars/Ashu11-A/Sexidium?style=for-the-badge&color=302D41&labelColor=f9e2af">
<img alt="Last commit" src="https://img.shields.io/github/last-commit/Ashu11-A/Sexidium?style=for-the-badge&color=302D41&labelColor=b4befe">
<img alt="Repo size" src="https://img.shields.io/github/repo-size/Ashu11-A/Sexidium?style=for-the-badge&color=302D41&labelColor=89dceb">
</p>

<p>
<img alt="Minecraft 1.21+" src="https://img.shields.io/badge/Minecraft-1.21%2B-302D41?style=for-the-badge&labelColor=f5c2e7">
<img alt="Paper 26.1.x" src="https://img.shields.io/badge/Paper-26.1.x-302D41?style=for-the-badge&labelColor=fab387">
<img alt="Velocity 3.5.1" src="https://img.shields.io/badge/Velocity-3.5.1-302D41?style=for-the-badge&labelColor=89b4fa">
<img alt="Java 25" src="https://img.shields.io/badge/Java-25-302D41?style=for-the-badge&labelColor=cba6f7">
<img alt="Bun 1.3+" src="https://img.shields.io/badge/Bun-1.3%2B-302D41?style=for-the-badge&labelColor=f38ba8">
</p>

<br>

<p align="center">
<strong>One plugin. A whole minigame network.</strong>
<br>
<sub>
Sexidium is a <strong>platform-agnostic core</strong> with thin server adapters: all gameplay,
matchmaking, lobbies, worlds, ranks, auth and UI live in <code>packages/core</code>,
<br>
while <code>module-paper</code> and <code>module-velocity</code> only translate that logic into
the API each server actually speaks.
<br>
<br>
Ships <strong>5 minigames</strong>, <strong>16 chaos experiences</strong>, a shared database, and a
<strong>TypeScript Discord bot</strong> the plugin launches and supervises by itself.
</sub>
</p>

<p align="center">
<a href="https://github.com/Ashu11-A/Sexidium/stargazers"><img alt="Star Repo" src="https://img.shields.io/badge/Leave%20a%20Star%20🌟-302D41?style=for-the-badge&color=302D41&labelColor=f9e2af"></a>
<a href="docs/README.md"><img alt="Read the docs" src="https://img.shields.io/badge/Read%20the%20docs%20📚-302D41?style=for-the-badge&color=302D41&labelColor=89dceb"></a>
</p>

</div>

> [!WARNING]
> **This project is written and maintained entirely by generative LLMs.** Every module, script and
> document here — this README included — was structured, written and refactored by large language
> models under human direction; assume nothing was hand-written or reviewed line by line. Read it
> before you run it, and audit anything touching your database, server files or Discord bot token.

---

<!-- AI-AGENT-INSTRUCTION: This README is a SUMMARY, not the specification. Never expand it with
     per-mode configuration dumps, full command tables or schema listings — that content belongs in
     docs/. If a section here grows past its heading's scope, move the detail into docs/ and leave a
     link. Keep this file at 250 lines. -->

## Why "Sexidium"?

The project had working code for weeks and no name. Then this happened:

> **Ashu:** Dude, I need a name for this project — got any suggestions? @Yisly
>
> **Yisly:** When I don't know what name to pick, I just go with "sex"
>
> **Ashu:** Man, I think ore names are cool
>
> **Yisly:** Sexidium
>
> **Ashu:** Perfect, bro

Take the suffix off iridium, palladium or rhodium, bolt it onto Yisly's universal
naming strategy, and you get a word that sounds like something you'd mine at Y-level 12.
It stuck before anyone could argue, and renaming it now would break every package
under `com.sexidium`. So: **Sexidium**, a perfectly respectable fictional ore.

---

## What it is

A **multi-gamemode Minecraft plugin** that runs as a single Paper server or as a full network behind
a Velocity proxy. One admin command leases a disposable world, runs the game, scores it, cleans up.

| Layer | What lives there |
|-------|------------------|
| **Core** | Modes, commands, menus, world leasing, ranks, auth, HTTP bridge, localization |
| **Paper adapter** | Bukkit/Adventure implementations of the core's SPI |
| **Velocity adapter** | Proxy-side routing, network transfers, shared player state |
| **Bot** | TypeScript Discord bot — rank cards, leaderboards, account linking, events |

**Minigames** — `race`, `gather`, `tntwar`, `combat`, `fugitive`.
**Experiences** — 16 chaos modifiers layered onto survival: block randomizer, shared inventory, shared
life, mob duplication, TNT mobs, walking blocks, evolving mobs, cleave, chained players, and more.

Players link a Minecraft UUID to a Discord account, earn level points from every match, and the bot
renders their rank card. Persistence is SQLite for one server, MySQL/MariaDB for a network.

---

## Requirements

<!-- AI-AGENT-INSTRUCTION: These versions are load-bearing. Before editing any number here, verify
     it against build.gradle.kts, gradle.properties and docker/stack.sexidium.yml — the README must
     never be the only place a version is written down. -->

| | Version | Notes |
|---|---------|-------|
| **Java** | 25 | Toolchain target; matches modern Paper. |
| **Gradle** | 9.x | Use the bundled `./gradlew` wrapper — don't install it globally. |
| **Paper** | 26.1.x | Built and smoke-tested against `paper-26.1.2-67`. |
| **Velocity** | latest 3.x | Only for the multi-server deployment. |
| **Bun** | 1.3+ | Optional — the plugin downloads its own runtime unless told not to. |
| **Docker** | recent | Only for the database stack and remote provisioning. |

Managed easily with [SDKMAN!](https://sdkman.io/): `sdk install java 25-tem`, then `sdk env` in the repo.

Bun is **not** a hard dependency: with `bot.enabled` the plugin fetches the matching Bun build into
`<data>/runtime/bun` and runs `bun install` itself — set `bot.download-runtime: false` for a system `bun`.

---

## Dependencies

<!-- AI-AGENT-INSTRUCTION: every version here is pinned in build.gradle.kts, module-paper's plugin.yml
     `libraries:` block or scripts/lib/plugins.sh — change the pin first, then this table. -->

Nothing is vendored: platform APIs are `compileOnly`, JDBC drivers load at runtime, plugins come from Modrinth.

| Library | Version | Role |
|---|---|---|
| `io.papermc.paper:paper-api` | 26.1.2 | Paper adapter — provided by the server, `compileOnly`. |
| `com.velocitypowered:velocity-api` | 3.5.1 | Proxy adapter — `compileOnly`. |
| `org.xerial:sqlite-jdbc` | 3.53.1.0 | Single-server persistence; fetched by Paper's `libraries:` loader. |
| `com.mysql:mysql-connector-j` | 9.1.0 | Network persistence — the only driver the proxy jar ships. |
| `org.postgresql:postgresql` | 42.7.4 | Alternative network backend. |
| JUnit 5 · Mockito | 5.11.4 · 5.14.2 | Test suites. |

| Server plugin | Need | Purpose |
|---|---|---|
| **Multiverse-Core** `5.7.3` | required | Lobby and game world provisioning; pinned to a release build. |
| **FancyNpcs**, **FancyHolograms** | soft | Lobby NPCs, holograms and live nameplates. |
| **SkinsRestorer** | soft | `/skin`, and the player-head skins used by chest GUIs. |
| **BetterHud** | soft | Corner HUD readout; skipped on Minecraft versions its overlay misses. |
| **Geyser**, **Floodgate**, **Cumulus** | soft | Bedrock support — native Cumulus forms; the proxy owns the listener. |
| **FastAsyncWorldEdit**, **Axiom** | soft | Map building only — Sexidium never calls into either. |

---

## Building

```bash
./gradlew build
```

That compiles every module, runs the JUnit suites, and collects deployable jars:

```
build/libs/paper/Sexidium-Paper-1.0.0.jar        # drop into plugins/, restart
build/libs/velocity/Sexidium-Velocity-1.0.0.jar  # proxy only
build/libs/internal/                             # core artifacts — do NOT deploy these
```

Both jars bundle the bot's TypeScript source and manifests but never the Bun binary or `node_modules`,
which keeps the Paper jar around **0.6 MB**. A default `config.yml` is generated on first run.

In-game, `/sx modes` lists everything and `/sx start minigames race` starts one; every command sits under `/sexidium` (alias `/sx`), needs `sexidium.admin`, and only one game runs at a time.

```bash
./gradlew :packages:core:test        # core suites only
./gradlew build -x test              # skip tests
scripts/test/run.sh                  # local gate: shell lint + golden trace + JUnit + bot typecheck
scripts/remote.sh test               # the authoritative gate, on the deployment host
```

The local gate **skips and still passes** any stage whose tooling is missing, so `scripts/remote.sh test`
is the real answer. Parallel execution, build cache and configuration cache are on — see `gradle.properties`.

For the single-server debug stack, `docker compose up --build` brings up a database plus a Paper
container. For a full network, see [docs/operations/deployment.md](docs/operations/deployment.md).

---

## Project layout

<!-- AI-AGENT-INSTRUCTION: This tree is intentionally shallow — one line per directory that a new
     contributor must know about. Do not recurse into src/main/java or enumerate individual classes.
     If a new top-level directory appears, add exactly one line for it and delete nothing else. -->

```
sexidium/
├── build.gradle.kts              # root build: module wiring, jar collection, packaging
├── settings.gradle.kts           # :packages:core, :packages:module-paper, :module-velocity
├── gradle.properties             # parallel + build cache + configuration cache tuning
├── buildSrc/                     # SexidiumBuildUtil — shared build logic
├── packages/
│   ├── core/                     # ALL game logic, zero platform dependencies
│   │   └── src/main/resources/   # config.yml, localization, db/migrations/*.sql
│   ├── module-paper/             # Bukkit/Adventure SPI implementation
│   └── module-velocity/          # Velocity proxy plugin: transfers, shared state
├── bot/                          # TypeScript Discord bot (out-of-process child)
│   └── src/
│       ├── database/             # TypeORM entities + repositories
│       ├── discord/              # slash commands, events
│       ├── images/ ui/           # satori/React rank cards and banners
│       └── index.ts              # bootstrap entry
├── scripts/                      # provisioning, network control, test gate
│   ├── lib/                      # shell helpers: paper, velocity, java, plugins, yaml
│   ├── remote/                   # Python deploy pipeline (Portainer-driven)
│   └── test/                     # run.sh, fixtures, golden traces, fakes
├── docker/                       # node entrypoints, provisioner, stack definition
├── docs/                         # architecture, gameplay, guides, interface, operations
└── assets/                       # minigame, experience and UI icons
```

The canonical database schema lives in `packages/core/src/main/resources/db/migrations/`.
Java repositories and TypeScript TypeORM entities read the **same** tables — change one and
you must change the other in the same commit.

---

## Documentation

| Section | Start here |
|---------|------------|
| **Architecture** | [docs/architecture/overview.md](docs/architecture/overview.md) — core + adapters, startup order |
| **Gameplay** | [docs/gameplay/minigames.md](docs/gameplay/minigames.md), [experiences.md](docs/gameplay/experiences.md), [chaos.md](docs/gameplay/chaos.md) |
| **Guides** | [docs/guides/](docs/guides/) — add a minigame, a command, a menu, a platform capability |
| **Interface** | [docs/interface/commands.md](docs/interface/commands.md), [menus.md](docs/interface/menus.md) |
| **Operations** | [docs/operations/deployment.md](docs/operations/deployment.md), [networking-bot-ranks.md](docs/operations/networking-bot-ranks.md) |
| **Reference** | [docs/reference/tech-decisions.md](docs/reference/tech-decisions.md), [known-issues.md](docs/reference/known-issues.md) |

---

## Contributing

<!-- AI-AGENT-INSTRUCTION: COMMIT CONVENTION — every commit subject is `<emoji> <title>`, with one
     emoji matching the change (✨ feature · 🐛 fix · 📝 docs · ♻️ refactor · ✅ tests · 🔧 config ·
     🚀 deploy · 🔥 removal), then a blank line, then a DESCRIPTION body saying what changed and why.
     Never commit a bare subject line, never drop the emoji, never use `-m` without a body. -->
<!-- AI-AGENT-INSTRUCTION: When adding a feature, put the logic in packages/core and expose it
     through the SPI. Platform modules must stay thin — if you are writing game rules inside
     module-paper or module-velocity, you are in the wrong package. Always update the matching
     doc under docs/ in the same change, and run scripts/test/run.sh before proposing a diff. -->

1. Read [docs/architecture/overview.md](docs/architecture/overview.md) — the core/adapter split rules all.
2. Gameplay goes in `packages/core`; adapters only translate. [docs/guides/](docs/guides/) has one guide per extension point.
3. Run `scripts/test/run.sh` locally and `scripts/remote.sh test` before a release; ship docs with code.

<div align="center">
<sub>Built on Paper and Velocity · Java 25 · TypeScript on Bun · named by committee, badly.</sub>
</div>
