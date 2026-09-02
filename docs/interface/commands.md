# Sexidium Command Reference (`/sx`, `/sexidium`)

Every Sexidium command lives in one platform-agnostic class,
[`CoreCommandService`](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java).
The Paper bridge is a thin shim: it wraps a native command sender as a
`CommandSource` and forwards the raw `String[]` to `execute(source, args)` (dispatch) and
`suggest(source, args)` (tab completion), so every platform built on core serves the **same
subcommand set**; only the wiring, permission resolution, and argument transport differ (see
[Platform bridges](#platform-bridges)). The root label
`/sexidium` and its alias `/sx` are equivalent; this doc uses `/sx`.

> **Global commands.** The player-facing subcommands `menu`, `exit` (alias `leave`), `lobby` and
> `friend` are **also registered as top-level commands** — `/menu`, `/exit`/`/leave`, `/lobby …` and
> `/friend …` — forwarding to the same handlers as `/sx <x>`. Both spellings are equivalent; this doc
> leads with the global form for those four.
>
> **Note on `party`/`queue`.** The lobby-unification commit removed the old top-level `party` and
> `queue` subcommands and folded both into the unified lobby, now surfaced as the `/lobby` global.
> There is **no `party` subcommand**. Roster management is `/lobby invite|accept|leave|kick|disband`
> and the quick-play queue is `/lobby queue`.

---

## Dispatch flow

`execute(source, args)` ([CoreCommandService.java:75](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L75)):

- A `null` source falls back to `server.console()`; `null` args become an empty array.
- If args are **empty** or `args[0]` is `help`/`?`, `GameCommands.help()` runs **before the permission gate** — so bare `/sx` prints help to anyone.
- Otherwise `args[0]` is lowercased, gated by `hasRootPermission`, then routed via `switch` to the focused handler class.
- Unknown subcommands fall through to `help()`. `execute` **always returns `true`**, so the platform never prints its own usage.

`suggest(source, args)` ([:107](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L107))
mirrors dispatch and is permission-filtered; first-token suggestions follow `ROOT_ORDER`
([:31](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L31)) and only
include subcommands the source may run.

---

## Quick reference (VERIFIED)

`CoreCommandService.execute`
([CoreCommandService.java:75](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L75))
owns only the permission gate and root dispatch; the work is split across focused, package-private
handler classes (`GameCommands`, `ExperienceCommands`, `LobbyCommands`, `FriendCommands`,
`AdminCommands`, …) sharing a `CommandContext`.

### Player globals (also `/sx <x>`)

| Command | Args | Permission | Handler |
|---|---|---|---|
| `/menu` (`/sx menu`) | — | `sexidium.play` (player) | `GameCommands.handleMenu` |
| `/exit` / `/leave` (`/sx exit`\|`leave`) | — | `sexidium.play` | `GameCommands.handleExit` |
| `/lobby` (`/sx lobby`) | `[invite\|accept\|leave\|kick\|disband\|list\|join\|mode\|teams\|size\|visibility\|start\|queue] [args]` | `sexidium.play` | `LobbyCommands.handle` |
| `/friend` (`/sx friend`) | `<add\|accept\|remove\|list\|requests> [player]` | `sexidium.play` | `FriendCommands.handle` |
| `/pay` (`/sx pay`) | `<player> <amount>` | `sexidium.play` (root) **+** `sexidium.economy.pay` (inner) | `EconomyCommands.handlePay` |
| `/balance` (`/bal`, `/money`, `/sx balance`) | `[player]` | `sexidium.play`; `[player]` also needs `sexidium.economy.balance.others` | `EconomyCommands.handleBalance` |
| `/baltop` (`/sx baltop`) | `[page]` | `sexidium.play` | `EconomyCommands.handleBaltop` |

### Player `/sx`

| Subcommand | Args | Permission | Handler |
|---|---|---|---|
| `start` | `experience <challenge…>` (play) **or** `<minigames> <mode> [players…] [--players=a,b] [--flags]` (admin) | split¹ | `GameCommands.handleStart` |
| `experience` | `<list\|join\|requests\|accept\|deny\|public\|private\|rename\|delete> [args]` | `sexidium.play` | `ExperienceCommands.handle` |
| `join` | `<mode>` | `sexidium.play` | `GameCommands.handleJoin` |
| `top` | — | `sexidium.play` | `GameCommands.handleTop` |
| `rank` | `[player]` | `sexidium.play` | `GameCommands.handleRank` |
| `auth` | — | `sexidium.auth` **or** `sexidium.play` | `GameCommands.handleAuth` |
| `race` | `vote <yes\|no> \| switch <team> \| allow <on\|off>` | `sexidium.play` | `RaceCommands.handle` |

### Admin `/sx admin`

Every operator/build tool now lives under `/sx admin`, dispatched by `AdminCommands.handle`
([AdminCommands.java:52](../../packages/core/src/main/java/com/sexidium/core/command/AdminCommands.java#L52)):
each branch reslices the argument array so the focused handler receives exactly the shape it did as a
former top-level command. **All require `sexidium.admin`.**

| Subcommand | Args | Handler |
|---|---|---|
| `admin reload` | — | inline (reload callback + `COMMAND_RELOAD`) |
| `admin stop` | — | `GameCommands.handleStop` |
| `admin kit` | `[list]` \| `give <kit> [player]` \| `give <player> <kit>` | `GameCommands.handleKit` |
| `admin bot` | `[status\|start\|stop\|restart\|logs\|reload\|config]` | `BotCommands.handle` |
| `admin npc` | `create\|remove\|list\|here\|command\|name\|skin\|follow\|holo\|mode\|edit\|reload` | `NpcCommands.handle` |
| `admin net` | `[status\|nodes\|…]` | `NetworkCommands.handle` |
| `admin selftest` | — | inline `selfTest` — "is this node fit to serve?", the rollback trigger a rolling update reads |
| `admin broadcast` | `<seconds> <update\|restart\|shutdown>` | inline `broadcast` |
| `admin capabilities` | — | inline [`capabilities`](../../packages/core/src/main/java/com/sexidium/core/command/AdminCommands.java) — see [Capability readout](#capability-readout) |
| `admin eco` (`admin economy`) | `give\|take\|set <player> <amount>` \| `reset\|balance <player>` \| `top [n]` | [`EconomyCommands.handleAdmin`](../../packages/core/src/main/java/com/sexidium/core/command/EconomyCommands.java) |
| `admin backup` | `list [<experienceId>\|<player>] \| info <backupId> \| create <experienceId> \| restore <backupId> \| refresh <backupId> \| duplicate <experienceId> \| delete <backupId> \| pending` | [`BackupCommands.handle`](../../packages/core/src/main/java/com/sexidium/core/command/BackupCommands.java) |
| `admin map tntwar` | `list \| create <id> \| <id> corner <red\|blue> <1\|2> \| <id> spawn <red\|blue> \| <id> save` | `TntWarCommands.handle` |
| `admin map combat` | `list \| <id> <spawn\|clear>` (Combat-arena spawn capture → `sexidium-combat.yml`) | `CombatCommands.handle` |
| `admin map edit` | `[<mode> <mapId>] \| team <n> \| spawn \| save \| list \| worlds \| exit` (in-world battle-map editor; Creative + 5-tool hotbar: golden axe corners, iron pick delete, clock undo, lime dye confirm, red dye cancel; no-arg lists bundled worlds + configured maps) | [`MapEditorCommands`](../../packages/core/src/main/java/com/sexidium/core/command/MapEditorCommands.java) → [`MapEditorService`](../../packages/core/src/main/java/com/sexidium/core/world/map/editor/MapEditorService.java) |

`help` / `?` (or bare `/sx`) prints help to anyone via `GameCommands.help` — it runs **before** the
permission gate, so even though `help` sits in the admin bucket² it is reachable publicly.

¹ `start` is split-permission — see [Permission model](#permission-model).
² bare `/sx` prints help because the empty-args branch runs before the gate.

---

## Permission model

Buckets are static sets ([:28–30](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L28)),
resolved in `hasRootPermission` ([:138](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L138)).
Node constants: `ADMIN_PERMISSION=sexidium.admin`, `PLAY_PERMISSION=sexidium.play`,
`AUTH_PERMISSION=sexidium.auth`. Two money nodes are checked INSIDE the handler rather than at the
root, because the root gate sees only the first token: `sexidium.economy.pay` (plugin.yml default
`true`) and `sexidium.economy.balance.others` (default `op`). The root gate sees only the **first token**, so the whole admin tree
sits behind the single `admin` bucket entry.

| Bucket | Subcommands | Required node(s) |
|---|---|---|
| `PLAYER_SUBCOMMANDS` | `menu`, `exit`, `leave`, `join`, `experience`, `lobby`, `friend`, `pay`, `balance`, `bal`, `money`, `baltop`, `top`, `rank`, `race` | `sexidium.play` |
| `AUTH_SUBCOMMANDS` | `auth` | `sexidium.auth` **OR** `sexidium.play` |
| `ADMIN_SUBCOMMANDS` | `admin` (→ `reload`, `stop`, `kit`, `bot`, `npc`, `net`, `backup`, `eco`, `selftest`, `broadcast`, `capabilities`, `map tntwar\|combat\|edit`) | `sexidium.admin` |

`start` is special-cased ([:139](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L139)):
admitted at the root for `sexidium.play` **OR** `sexidium.admin`. The experience branch stays
player-accessible, while `GameCommands.handleStart` re-checks `sexidium.admin` for the category/mode
branch. Any token in no bucket returns `true` — only reachable by `help`/unknown, which are
short-circuited. Permission **resolution** differs per platform — see [Platform bridges](#platform-bridges).

---

## `/sx start` (participant model — CHANGED)

### `/sx start experience <challenge…> [--world=…] [--keep-inventory=…]`  (player)

Builds a composable, persistent survival "experience" running any combination of challenges at once
(`ExperienceCommands.handleStart` [:229](../../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L229)).

- Tokens after `experience`: known challenge ids are collected (deduped, request order); `--players=a,b` / `--players a,b` set a roster; unknown `--` flags are ignored. **Each unknown token is reported with a red "Unknown challenge" message** ([ExperienceCommands.java:255](../../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L255)) — not silently skipped.
- `--players` is honored **only for admins** ([ExperienceCommands.java:287](../../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L287)); a normal player seeds with their own roster.
- `--keep-inventory=<true|false>` sets whether deaths keep items + XP (default **true**, applied to every dimension of the experience). Toggleable later from the experience's manage GUI.
- `--world=<normal|nether|end|superflat|largebiomes|amplified>` picks the **map type** — the vanilla generation preset the world is built with and/or which dimension of it the player starts in. A world-generating challenge (`classicskyblock`, `randomskyblock`, `randomlayers`) sets the type implicitly; naming **two** of them is rejected, and combining one with a conflicting `--world` is rejected. See [world type](../gameplay/experiences.md#world-type-map-selection).
- **Persistent path:** when the owner is a player and `core.experiences().available()`, a registered `ExperienceManager.Experience` is created (enforcing `maxPerPlayer` → `EXPERIENCE_LIMIT_REACHED`). Otherwise a transient game via `core.games().start(ExperienceGame.MODE_ID, …)` in a leased world.

Challenge mechanics: [game framework](../architecture/game-framework.md) and the per-challenge reference in
[experiences.md](../gameplay/experiences.md).

### `/sx start <minigames> <mode> [players…] [--players=a,b] [--flags]`  (admin)

Launches a categorized match. Parsed by `GameCommands.parseStart`
([:109](../../packages/core/src/main/java/com/sexidium/core/command/GameCommands.java#L109)) with a
**category-first grammar**:

- `args[1]` is always the category slot (only `minigames` is registered for the admin path).
- `args[2]` is the mode id.
- `--players=a,b,c` (or `--players a,b,c`) sets the roster; any other `--token` is a **mode arg**; bare tokens are participant names.

`handleStart` validates: mode non-blank, **category mandatory + exists**, and the descriptor's category
matches the requested one.

**Participant resolution** (`CommandContext.participants` [:91](../../packages/core/src/main/java/com/sexidium/core/command/CommandContext.java#L91)) — **CHANGED**:

- With explicit names → each resolved via `playerExact`.
- With **no names and a player initiator** → the initiator **plus their online lobby group** (`core.lobbies().lobbyOf → onlineMembers`); never the whole server.
- With **no names and a console/non-player source** → falls back to **all online players** (`server.onlinePlayers()`).

### Registered modes ([`CoreGameRegistryInitializer`](../../packages/core/src/main/java/com/sexidium/core/game/CoreGameRegistryInitializer.java))

Only two categories exist: `minigames` and `experience`.

| Category | Mode id | Display name | minPlayers | Aliases |
|---|---|---|---|---|
| `minigames` | `race` | Race for Item | 1 | `raceforitem`, `item` |
| `minigames` | `gather` | Gather and Duel | 2 | `duel`, `gatherandduel`, `gatherduel` |
| `minigames` | `tntwar` | TNT War | 2 | `tnt`, `war` |
| `minigames` | `combat` | Combat Item Mode | 2 | `kit`, `kitpvp` |
| `minigames` | `fugitive` | Fugitive | 3 | `manhunt`, `thefugitive`, `fled` |
| `experience` | `experience` | Experience | 1 | `exp`, `experiences` |

---

## Challenge catalog (17 challenges)

Selectable challenge ids come from
[`ChallengeCatalog`](../../packages/core/src/main/java/com/sexidium/core/game/experience/ChallengeCatalog.java),
in display order:

`doubledrops`, `randomizer`, `sharedlife`, `sharedinventory`, `xphealth`,
`shrinkingachievements`, `breakonebreakall`, `blockdeleter`, `randomchunks`, `walkingblocks`,
`chained`, `cleave`, `growing`, `jumpenchants`, `mobduplication`.

Ids are normalized (trimmed, lowercased) and matched via `ChallengeCatalog.contains`/`normalize`,
deduped in request order.

> The old doc's ids `fleeingblocks`, `nogreen`, `jumpgravity`, `lavafloor`, `midastouch` are all
> obsolete and no longer exist as catalog entries.

---

## `/sx join` (relationship-gated — CHANGED)

`GameCommands.handleJoin` ([:279](../../packages/core/src/main/java/com/sexidium/core/command/GameCommands.java#L279)):

1. **Reconnect first:** if the player has no active match but a persisted session, `core.games().handleJoin(player)` rejoins it (no gate) and reports `COMMAND_JOIN_SUCCESS`.
2. **Gated path:** builds `relatedPlayers(player)` ([GameCommands.java:313](../../packages/core/src/main/java/com/sexidium/core/command/GameCommands.java#L313)) = online lobby/group members **+** persisted friends, then calls `core.games().joinInProgress(player, modeId, relatedPlayerIds)`.

A player may only join a match where a **friend or lobby member** is already playing — a stranger's
match returns `NOT_RELATED`. (This refutes the old doc's "[HIGH] No party/friend gate on /sx join".)

| `JoinResult` | Message |
|---|---|
| `JOINED` | `COMMAND_JOIN_SUCCESS` |
| `NOT_RUNNING` | `COMMAND_JOIN_NOT_RUNNING` |
| `NOT_RELATED` | `COMMAND_JOIN_NOT_ALLOWED` |
| `ALREADY_IN_MATCH` | `COMMAND_JOIN_ALREADY_IN_MATCH` |
| `PLAYER_OFFLINE` | `COMMAND_KIT_PLAYER_OFFLINE` (reused key) |

A missing/blank mode id (and no reconnect) → `COMMAND_JOIN_USAGE`.

---

## `/lobby` (the unified hub)

`LobbyCommands.handle` (reached as the `/lobby` global **or** `/sx lobby`). Bare `/lobby` opens
`core.menus().openLobby(player)`. Roster ops reuse the localized `PARTY_*` message keys (the group is
still spoken of as "your party"); most other lobby output is **hardcoded English MiniMessage**.
Outcomes come from `LobbyResult`
([LobbyResult.java:8](../../packages/core/src/main/java/com/sexidium/core/world/lobby/LobbyResult.java#L8)).

| Action | Effect | Result outcomes |
|---|---|---|
| `invite <player>` | Invite to the group. | `INVITE_SENT`, `SELF`, `NOT_LEADER`, `FULL`, `TARGET_IN_PARTY` |
| `accept [host]` | Accept a pending invite. | `JOINED`, `NO_INVITE`, `AMBIGUOUS`, `FULL`, `DISBANDED`, `ALREADY_IN_PARTY` |
| `leave` | Leave the group. | `LEFT`, else `NOT_IN_PARTY` message |
| `kick <player>` | Leader-only kick. | boolean |
| `disband` | Leader-only disband. | `NOT_LEADER` else disbanded |
| `mode <minigame>` | Host: pick the minigame. | `CONFIGURED`, `NOT_LEADER`, `NOT_MINIGAME`, `ALREADY_IN_MATCH` |
| `teams <2-MAX\|ffa>` | Host: set team **count** (`ffa` = 0). | `OK`, `NOT_HOST` |
| `size <players-per-team>` | Host: set team **size** (≥1). | `OK`, `NOT_HOST` |
| `visibility` / `privacy` `<public\|friends\|invite>` | Host: set visibility. | `OK`, `NOT_HOST` |
| `start` | Host: start now. | `STARTED`, `TOO_FEW`, `FULL`, `NOT_HOST`, `NOT_MINIGAME`, `FAILED` |
| `list` | List joinable open lobbies. | — (`joinableFor`) |
| `join <host>` | Join an open lobby by host name. | `JOINED`, `ALREADY_IN`, `ALREADY_IN_MATCH`, `NOT_FOUND`, `NOT_INVITED`, `FULL` |
| `queue <minigame>` / `queue leave\|cancel\|stop` | Quick-play queue / dequeue. | `QUEUED`, `ALREADY_QUEUED`, `NOT_LEADER`, `ALREADY_IN_MATCH`, `NOT_MINIGAME` / `DEQUEUED` |

> **`teams` vs `size`:** team **count** is `teams`, players-per-team is `size`. The old doc's single
> `size <number|ffa>` (and the `create` action) no longer exist — hosting is `/lobby mode <minigame>`.

`visibility` is parsed by `parseVisibility`:
`public`/`open` → `PUBLIC`; `friends`/`friends-only` → `FRIENDS_ONLY`; `invite`/`private` →
`INVITE_ONLY`. Team/lobby model: [game framework](../architecture/game-framework.md).

---

## `/sx experience`

`ExperienceCommands.handle` ([:44](../../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L44)),
reached from `CoreCommandService` through the `ExperienceCommands` field; **bare `/sx experience` opens
the GUI** (My Experiences), it does not print a list. Backed by `ExperienceService` /
`ExperienceManager`.

| Action | Effect | Outcomes |
|---|---|---|
| *(no action)* / `menu` | Opens **My Experiences**. | — |
| `edit [<id>]` | Opens the edit screen for `<id>`, or My Experiences without one. | — |
| `list` | List your experiences. | — |
| `join <id>` | Enter, or request to join. | `ENTERED`/`STARTED`, `REQUEST_SENT`, `ALREADY_IN_MATCH`, `NOT_FOUND`, `LIMIT_REACHED`, `HOST_OFFLINE`, `OFFLINE`/`FAILED` |
| `requests` | List pending join requests. | — |
| `accept <player>` / `deny <player>` | Approve / reject a request. | resolved via `server.playerExact` — **target must be online** |
| `public <id>` / `private <id>` | Toggle listing only (`setVisibility`). | owner-only |
| `hardcore <id> <on\|off>` | Turn HARDCORE on/off. One death ends the world for good. | owner-only; **refused once the world has been lost** |
| `rename <id> <name…>` | Rename (joins remaining args). | owner-only; **capped at 48 characters** — longer is refused before anything is written |
| `delete <id>` | **Permanently delete** the map. Reports the real outcome from the router's callback. | owner-only — the only thing left to do with a lost hardcore world; `DELETED` / `QUEUED` / `NOT_OWNER` / `REFUSED` |

**The 48-character rename cap is not cosmetic.** `ExperienceService.MAX_DISPLAY_NAME`
([:81](../../packages/core/src/main/java/com/sexidium/core/game/experience/ExperienceService.java#L81)) is
the one number, read by `ExperienceCommands` rather than duplicated
([:36](../../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L36)), because
the name does not stay a label: `WorldKey.of(displayName, id)` builds every later backup's `world_key`
out of it, and `display_name` and `world_key` are both `VARCHAR(191)`, the second with a unique index.
The name also **grows** afterwards without being asked again — a copy wears `" (backup)"`, the world a
restore displaces wears `" (before restore)"`, a duplicate wears `" (copy)"` — so 48 leaves room for
the longest of those plus the `_`, the id, and a second round of the same. Typing is not the only way a
long name arrives: `displayNameFor` enforces the same cap on the name an experience is *born* with,
because 12 of the catalog's 27 twists joined with `" + "` already make 199 characters, and that INSERT
used to fail with `Data too long` — the player got no world and nothing on screen. Over the cap, the
rename answers "keep it to 48 characters or fewer" and writes nothing.

**`delete` can answer `REFUSED` for two different reasons**, and only one of them is "somebody may be
inside". If the router finds **no node recorded as holding the world** (`result.unrouted()`), the lobby
refuses instead of reporting success: a backup or a duplicate builds its folders through
`WorldLeaseService.copyExperienceWorld`, which never writes a `world_placements` row, so "nothing
records where this world lives" is not "there is no folder anywhere" — dropping the rows there orphaned
hundreds of megabytes that nothing names and no sweep collects. The owner is told to **enter the world
once so it is placed, then delete it** (`EXPERIENCE_DELETE_UNPLACED`); an operator gets a `SEVERE` line.
The other refusal — a node has it and would not give it up — keeps the "somebody may still be inside,
try again in a moment" wording (`EXPERIENCE_DELETE_BUSY`). Both are the same `DeleteOutcome.REFUSED`,
because every `switch` over that enum is exhaustive with no `default`; the sentence that differs is
sent from `explainRefusal`, not chosen by the caller.

Tab completion (`ExperienceCommands.suggest`
[:187](../../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L187)):
`join` suggests own + public-others ids; `edit`/`public`/`private`/`hardcore`/`rename`/`delete` suggest own ids;
`hardcore` suggests `on`/`off` as its third argument; `accept`/`deny` suggest online player names. Full model: [game framework](../architecture/game-framework.md).

---

## Other subcommands

> The former `modes`, `status` and `list` subcommands were **removed** — that data (running matches,
> registered modes) now surfaces only through the GUI menu, not a chat command.

- **`admin stop`** — `COMMAND_STOP_NOT_RUNNING` when idle, else `stopActiveGame` with `STOP_BY_PLAYER`/`STOP_BY_CONSOLE` + `COMMAND_STOP_SUCCESS`.
- **`admin kit`** — `list` via `COMMAND_KIT_LIST`; `give` parsed by `parseKitGive`, which disambiguates kit-vs-player order via `kits().exists()`; applied **additively** via `kits().apply` (no inventory clear).
- **`admin reload`** — runs the reload callback then `COMMAND_RELOAD`. **The callback differs per platform** — see [Platform bridges](#platform-bridges).
- **`/exit`/`/leave`** (`/sx exit`\|`leave`) — `core.games().removePlayer(player, true)`; `false` → `COMMAND_EXIT_NOT_IN_GAME`.
- **`top`** — null `ranks()` → `COMMAND_RANKS_UNAVAILABLE`; else top 10 (rank, name, points, level, wins, kills).
- **`rank [player]`** — defaults to the command player (needs a player source then); `COMMAND_RANK_NONE` if no profile.
- **`auth`** — reads `auth.code-expiry-seconds` (600), `auth.code-length` (6), `auth.code-characters` (`23456789`); status `CREATED`/`ALREADY_LINKED`/`DISABLED`; `SQLException` → warning + `AUTH_UNAVAILABLE`.
- **`admin bot`** — default `status`; `reload` runs the reload callback then `bot.restart()`. **All bot output is hardcoded English MiniMessage**, not localized.
- **`/pay` / `/balance` / `/baltop`** (`/sx pay|balance|baltop`) — null `economy()` → `ECONOMY_UNAVAILABLE` (no database on this node); `economy.enabled: false` → `ECONOMY_DISABLED`. Balances live in `economy_accounts` as **cents in an integer column**, shared across the network. `/pay` is player-only; `/balance <player>` and every `/sx admin eco` verb work from console. An offline `/pay` target resolves by name against the account table when `economy.pay.allow-offline` is on. Sexidium is also the server's **Vault economy provider** — see [platform and adapters](../architecture/platform-and-adapters.md).
- **`/friend`** (`/sx friend`) — null `friends()` → `FRIEND_UNAVAILABLE`; SQLite-backed (`friends`/`friend_requests`). `add`/`accept`/`remove` resolve the counterpart via `onlinePlayerOrUsage` → `playerExact`, so the **other player must be online** even though accept/remove are pure DB ops.
- **`admin npc`** — full editor command set incl. `edit` (opens `core.menus().openNpcEditor`) and `mode <minigame|none>`; saves per-id YAML and respawns immediately; output uses `sendMiniMessage` directly (not localized). NPC model: [game framework](../architecture/game-framework.md).
- **`admin map tntwar`** — persists to `<world-root>/<world>/` via `TntWarMapStore`; maps configured in `minigames.tntwar.maps`; messages localized via `TNTWAR_MAP_*`. Stand in the map world to set corners/spawns; a map needs both bases + both spawns to be `ready`.
- **`admin map combat`** — captures Combat-arena player spawn points into `sexidium-combat.yml` in the map's world folder; maps configured in `minigames.combat.maps`; mirrors `admin map tntwar`.
- **`race`** — only works when the player's current match game is a `RaceGame`, else `RACE_USAGE`; `vote`/`switch`/`allow` forward to `RaceGame` methods.

### Capability readout

**`/sx admin capabilities`** — what this node can actually do, probed rather than assumed. It answers
"does it work on *that* Minecraft version?" with a grep instead of an eyeball, which is what makes a
cross-version test matrix cheap to run: point `SERVER_DIR`/`PAPER_VERSION` at each version in turn
(`scripts/init-paper.sh`) and grep one line per node.

One header line, then one line per capability the backend cannot serve, each with the reason:

```
SX-CAPABILITIES platform=BUKKIT minecraft=26.1.2 pack-format=84
BEDROCK_FORMS       no — neither Floodgate nor Geyser exposes a form API here; Bedrock players get the chest GUI like everyone else
SKIN_LOOKUP_OFFLINE no — SkinsRestorer is not installed; offline players resolve to their stored Mojang profile
```

**Missing entries are normal, not errors.** A plain Paper without BetterHud, SkinsRestorer and
FancyNpcs legitimately lacks half the list; every one of them has a working degraded path, which is
what the reason names.

Three things worth knowing before reading the output:

- **It is the boot-time snapshot, not a live probe.** The adapter resolves the registry once at enable,
  because these are facts about the running server. Installing a plugin at runtime does **not** change
  this output — restart the node. (The hot paths that care about late-enabling plugins, chiefly the HUD
  driver, keep their own per-call checks.)
- **The same data is printed at boot** as `SX-VERSION` + one `SX-CAPABILITY no=…` line per miss, so a
  pipeline can grep either. `SX-CAPABILIT` matches both prefixes.
- **`not probed by this backend`** as a reason is a bug in Sexidium, not a fact about the server: it
  means a `Capability` constant was added without its probe. The registry fails it closed and says so;
  `PaperCapabilityRegistryTest` is meant to catch it before it ever ships.

Capability vocabulary and how to add one: [add-a-platform-capability.md](../guides/add-a-platform-capability.md).

### `/sx admin backup`

The headless surface for experience backups, and the reason the feature is testable on the live
network at all: before it existed the **only** entry point was a tile in the lobby menu, so nothing
about backups could be exercised without a client standing in the lobby. Semantics — what a copy is,
what a restore does, why a refresh is a full re-copy — live in
[experiences § Backups](../gameplay/experiences.md#backups-a-backup-is-an-experience).

```
/sx admin backup list [<experienceId>|<player>]   copies, oldest first, with age + source
/sx admin backup info <backupId>                  the identity card, as console text
/sx admin backup create <experienceId>            routed; same cap and BUSY rules as the tile
/sx admin backup restore <backupId>               routed; the swap
/sx admin backup refresh <backupId>               routed; the FULL re-copy
/sx admin backup duplicate <experienceId>         routed; a new playable world
/sx admin backup delete <backupId>                routed; refuses a row that is not a copy
/sx admin backup pending                          the router's outstanding request ids
```

- **Console-safe.** Nothing here calls `ctx.requirePlayer`. A console has no UUID and the service
  methods are owner-scoped by design, so each verb resolves the **row's true owner** and passes that
  as the requester; the answer is reported to whoever typed it.
- **No new permission.** The whole `/sx admin` subtree is already behind `sexidium.admin` at the root
  gate, and no node-capability gate is needed — every verb routes itself to the node holding the
  folder, so typing it on the lobby is correct.
- **Operator-facing raw MiniMessage**, per the codebase rule that *player*-facing text must be a
  message key and operator output need not be. Everything read out of the database is `<`-escaped
  first, because a world name is a string a player chose.
- **`delete` refuses a row that is not a copy.** The same service call would happily delete a live
  world, and an operator reaching for "backup delete" is not reaching for that.
- **`list` with no argument** covers only the connected players: `ExperienceManager` has no "every
  row" query, so a bare list says so rather than printing a partial list that reads like a complete
  one. Name an experience id or a connected player for an exact answer.
- **`info` deliberately omits the day and death counts.** They would have to come from
  `experiences.challenge_state`, which on the live server lags the `state.yml` inside the folder and
  can be missing a challenge's counters entirely — a wrong number is worse than none on output whose
  whole job is telling two byte-identical worlds apart.
- **`BUSY` now also means "already being worked on".** `ExperienceBackupService` claims the source and
  backup ids for the length of the copy (`inFlight`,
  [:133](../../packages/core/src/main/java/com/sexidium/core/game/experience/ExperienceBackupService.java#L133)),
  so a second `create` / `restore` / `refresh` / `duplicate` typed on the same experience while the
  first is still copying is refused rather than allocating a second destination. The instruction is the
  same as the loaded-world refusal it shares the constant with: try again in a moment.

---

## Tab completion (VERIFIED)

`suggest` ([:107](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L107))
is permission-filtered; the first token follows `ROOT_ORDER`.

| Subcommand | Suggestions |
|---|---|
| (first token) | `ROOT_ORDER` filtered by permission |
| `start` | categories + `experience` → challenge ids (repeatable) / modes-in-category → online player names |
| `lobby` | the 13 actions; arg3 is context-sensitive (player names / open-lobby hosts / minigame ids / `ffa,2,3,4` / `1,2,3,4` / `public,friends,invite`) — `LobbyCommands.suggest` |
| `experience` | the 12 actions (`menu`/`edit`/`list`/`join`/`requests`/`accept`/`deny`/`public`/`private`/`hardcore`/`rename`/`delete`), then own/public ids or online names |
| `friend` | `add`/`accept`/`remove`/`list`/`requests` |
| `pay` | arg2 player names; arg3 `10`/`100`/`1000` |
| `balance` (`bal`, `money`) | arg2 player names — **only** with `sexidium.economy.balance.others`, otherwise empty |
| `admin eco` | arg3 `give`/`take`/`set`/`reset`/`balance`/`top`; arg4 player names (row counts under `top`); arg5 amounts under `give`/`take`/`set` |
| `join` | running mode ids |
| `rank` | online player names |
| `auth` | `code` (admins only) |
| `race` | dedicated suggest helper |
| `admin` | `reload`/`stop`/`kit`/`bot`/`npc`/`net`/`backup`/`selftest`/`broadcast`/`capabilities`/`map`; then `kit` → kit-or-player, `bot` → `status`/…/`config`, `npc` → NPC helper, `backup` → the 8 verbs then online player names, `map` → `tntwar`/`combat`/`edit` → each tool's own helper (`AdminCommands.suggest`) |

---

## Platform bridges

Both adapters forward 100% of dispatch and suggestion to the same `CoreCommandService`. The
differences are in wiring, permission resolution, and argument transport.

### Paper bridge

[`PaperCommandBridge`](../../packages/module-paper/src/main/java/com/sexidium/paper/command/PaperCommandBridge.java)
is the executor + tab-completer for the `sexidium` command (alias `sx` from
[`plugin.yml`](../../packages/module-paper/src/main/resources/plugin.yml)).

- **Source mapping** ([:34](../../packages/module-paper/src/main/java/com/sexidium/paper/command/PaperCommandBridge.java#L34)): a `Player` → `PaperPlayerAdapter`; anything else → `PaperCommandSource`.
- **Argument transport:** native `String[]` passed unmodified.
- **Permissions:** Bukkit `sender.hasPermission(node)`. `plugin.yml` declares **only** `sexidium.play` (default `true`) and `sexidium.admin` (default `op`); **`sexidium.auth` is not declared.**
- **Reload callback:** `plugin::reloadSexidium` — a **full plugin reload**.

### Velocity proxy

The proxy owns no `/sx` tree: it implements only core's proxy-side `NodeRuntime` slice, relays
network control messages, and dispatches PROXY console commands through Velocity's command manager.
A backend-bound command must name its node over the message bus instead of silently running a
same-named proxy command — see [platform-and-adapters.md](../architecture/platform-and-adapters.md),
Part 3.

> A NeoForge Brigadier bridge once existed in this slot (op-level permissions, greedy-string arg
> transport); it was dropped from the build together with the rest of that adapter.

### Bridge comparison

| Aspect | Paper | Velocity proxy |
|---|---|---|
| Root registration | `plugin.yml` command + alias (`sexidium`, `sx`) + global aliases `/menu`, `/exit`, `/lobby`, `/friend`, `/pay`, `/balance` (`/bal`, `/money`), `/baltop` | none — no gameplay commands on the proxy |
| Arg transport | native `String[]` | not applicable |
| Source (player) | `PaperPlayerAdapter` | `VelocityPlayer` (as `NetworkPlayer`) |
| Source (console) | `PaperCommandSource` | `VelocityConsoleSource` |
| Console dispatch helper | [`PaperCommandDispatcherAdapter`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/command/PaperCommandDispatcherAdapter.java) (`Bukkit.dispatchCommand`) | Velocity command manager (proxy commands only) |

The Paper console dispatch helper strips a leading `/`.

---

## Parity gaps & validation notes

- **[MEDIUM] `sexidium.auth` undeclared in Paper `plugin.yml`.** The `auth OR play` check passes only via `play`'s default `true`; net behavior is currently correct, but the node is dead on Paper. Declare it (default `true`) to make the OR meaningful.
- **[LOW] Hardcoded English output.** `bot` and most `/lobby` output are hardcoded English MiniMessage (only the `party.*` keys are localized).
- **[LOW] Online-only relationship ops.** `friend add|accept|remove` **and** `experience accept|deny` resolve the target via `playerExact`, so the other player must be online even though the underlying ops are UUID-keyed DB operations.
- **[INFO] `help` is admin-bucketed yet reachable publicly** via bare `/sx` (the empty-args branch runs before the gate).
- **[RESOLVED] Join/start relationship gating.** The old doc's "[HIGH] No party/friend gate on /sx join" and "all online players conscripted on /sx start" are now false: `join` is gated via `relatedPlayers` + `joinInProgress(NOT_RELATED)`, and a player initiator on `start` brings only their online lobby group (only a console/non-player source with no names still pulls the whole server).

---

## Keeping this current

Source of truth (the code; this doc is a derived view):
[`CoreCommandService.java`](../../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java)
(all dispatch, permission buckets, handlers, tab completion),
[`CoreGameRegistryInitializer.java`](../../packages/core/src/main/java/com/sexidium/core/game/CoreGameRegistryInitializer.java)
(modes/categories), [`ChallengeCatalog.java`](../../packages/core/src/main/java/com/sexidium/core/game/experience/ChallengeCatalog.java)
(challenge ids), [`LobbyResult.java`](../../packages/core/src/main/java/com/sexidium/core/world/lobby/LobbyResult.java),
the bridge (`PaperCommandBridge.java`), and Paper `plugin.yml`.
Update **this doc in the same change** that touches those files. Triggers: a subcommand added/removed
or moved between permission buckets (edit `ROOT_ORDER`/the bucket sets); a new game mode, category, or
challenge id; a changed `LobbyResult`/`JoinResult`/`EnterOutcome` mapping; a bridge wiring, permission,
or reload-scope change; or a new/removed config key read by a handler (`auth.*`, `minigames.tntwar.maps`).
