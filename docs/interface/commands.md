# Sexidium Command Reference (`/sx`, `/sexidium`)

Every Sexidium command lives in one platform-agnostic class,
[`CoreCommandService`](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java).
The Paper and NeoForge bridges are thin shims: they wrap a native command sender as a
`CommandSource` and forward the raw `String[]` to `execute(source, args)` (dispatch) and
`suggest(source, args)` (tab completion). Because both adapters call the same service with the same
argument vector, the subcommand set is **identical across platforms**; only the wiring, permission
resolution, and argument transport differ (see [Platform bridges](#platform-bridges)). The root label
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

`execute(source, args)` ([CoreCommandService.java:75](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L75)):

- A `null` source falls back to `server.console()`; `null` args become an empty array.
- If args are **empty** or `args[0]` is `help`/`?`, `GameCommands.help()` runs **before the permission gate** — so bare `/sx` prints help to anyone.
- Otherwise `args[0]` is lowercased, gated by `hasRootPermission`, then routed via `switch` to the focused handler class.
- Unknown subcommands fall through to `help()`. `execute` **always returns `true`**, so the platform never prints its own usage.

`suggest(source, args)` ([:107](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L107))
mirrors dispatch and is permission-filtered; first-token suggestions follow `ROOT_ORDER`
([:31](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L31)) and only
include subcommands the source may run.

---

## Quick reference (VERIFIED)

`CoreCommandService.execute`
([CoreCommandService.java:75](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L75))
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
([AdminCommands.java:44](../packages/core/src/main/java/com/sexidium/core/command/AdminCommands.java#L44)):
each branch reslices the argument array so the focused handler receives exactly the shape it did as a
former top-level command. **All require `sexidium.admin`.**

| Subcommand | Args | Handler |
|---|---|---|
| `admin reload` | — | inline (reload callback + `COMMAND_RELOAD`) |
| `admin stop` | — | `GameCommands.handleStop` |
| `admin kit` | `[list]` \| `give <kit> [player]` \| `give <player> <kit>` | `GameCommands.handleKit` |
| `admin bot` | `[status\|start\|stop\|restart\|logs\|reload\|config]` | `BotCommands.handle` |
| `admin npc` | `create\|remove\|list\|here\|command\|name\|skin\|follow\|holo\|mode\|edit\|reload` | `NpcCommands.handle` |
| `admin backup` | `list [<experienceId>\|<player>] \| info <backupId> \| create <experienceId> \| restore <backupId> \| refresh <backupId> \| duplicate <experienceId> \| delete <backupId> \| pending` | [`BackupCommands.handle`](../packages/core/src/main/java/com/sexidium/core/command/BackupCommands.java) |
| `admin map tntwar` | `list \| create <id> \| <id> corner <red\|blue> <1\|2> \| <id> spawn <red\|blue> \| <id> save` | `TntWarCommands.handle` |
| `admin map combat` | `list \| <id> <spawn\|clear>` (Combat-arena spawn capture → `sexidium-combat.yml`) | `CombatCommands.handle` |
| `admin map edit` | `[<mode> <mapId>] \| team <n> \| spawn \| save \| list \| worlds \| exit` (in-world battle-map editor; Creative + 5-tool hotbar: golden axe corners, iron pick delete, clock undo, lime dye confirm, red dye cancel; no-arg lists bundled worlds + configured maps) | [`MapEditorCommands`](../packages/core/src/main/java/com/sexidium/core/command/MapEditorCommands.java) → [`MapEditorService`](../packages/core/src/main/java/com/sexidium/core/world/map/editor/MapEditorService.java) |

`help` / `?` (or bare `/sx`) prints help to anyone via `GameCommands.help` — it runs **before** the
permission gate, so even though `help` sits in the admin bucket² it is reachable publicly.

¹ `start` is split-permission — see [Permission model](#permission-model).
² bare `/sx` prints help because the empty-args branch runs before the gate.

---

## Permission model

Buckets are static sets ([:28–30](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L28)),
resolved in `hasRootPermission` ([:138](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L138)).
Node constants: `ADMIN_PERMISSION=sexidium.admin`, `PLAY_PERMISSION=sexidium.play`,
`AUTH_PERMISSION=sexidium.auth`. The root gate sees only the **first token**, so the whole admin tree
sits behind the single `admin` bucket entry.

| Bucket | Subcommands | Required node(s) |
|---|---|---|
| `PLAYER_SUBCOMMANDS` | `menu`, `exit`, `leave`, `join`, `experience`, `lobby`, `friend`, `top`, `rank`, `race` | `sexidium.play` |
| `AUTH_SUBCOMMANDS` | `auth` | `sexidium.auth` **OR** `sexidium.play` |
| `ADMIN_SUBCOMMANDS` | `admin` (→ `reload`, `stop`, `kit`, `bot`, `npc`, `map tntwar\|combat\|edit`) | `sexidium.admin` |

`start` is special-cased ([:139](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L139)):
admitted at the root for `sexidium.play` **OR** `sexidium.admin`. The experience branch stays
player-accessible, while `GameCommands.handleStart` re-checks `sexidium.admin` for the category/mode
branch. Any token in no bucket returns `true` — only reachable by `help`/unknown, which are
short-circuited. Permission **resolution** differs per platform — see [Platform bridges](#platform-bridges).

---

## `/sx start` (participant model — CHANGED)

### `/sx start experience <challenge…> [--world=…] [--keep-inventory=…]`  (player)

Builds a composable, persistent survival "experience" running any combination of challenges at once
(`handleStartExperience` [:655](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L655)).

- Tokens after `experience`: known challenge ids are collected (deduped, request order); `--players=a,b` / `--players a,b` set a roster; unknown `--` flags are ignored. **Each unknown token is reported with a red "Unknown challenge" message** ([:675](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L675)) — not silently skipped.
- `--players` is honored **only for admins** ([:686](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L686)); a normal player seeds with their own roster.
- `--keep-inventory=<true|false>` sets whether deaths keep items + XP (default **true**, applied to every dimension of the experience). Toggleable later from the experience's manage GUI.
- `--world=<normal|nether|end|superflat|largebiomes|amplified>` picks the **map type** — the vanilla generation preset the world is built with and/or which dimension of it the player starts in. A world-generating challenge (`classicskyblock`, `randomskyblock`, `randomlayers`) sets the type implicitly; naming **two** of them is rejected, and combining one with a conflicting `--world` is rejected. See [world type](experiences.md#world-type-map-selection).
- **Persistent path:** when the owner is a player and `core.experiences().available()`, a registered `ExperienceManager.Experience` is created (enforcing `maxPerPlayer` → `EXPERIENCE_LIMIT_REACHED`). Otherwise a transient game via `core.games().start(ExperienceGame.MODE_ID, …)` in a leased world.

Challenge mechanics: [game framework](game-framework.md) and the per-challenge reference in
[experiences.md](experiences.md).

### `/sx start <minigames> <mode> [players…] [--players=a,b] [--flags]`  (admin)

Launches a categorized match. Parsed by `parseStart`
([:739](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L739)) with a
**category-first grammar**:

- `args[1]` is always the category slot (only `minigames` is registered for the admin path).
- `args[2]` is the mode id.
- `--players=a,b,c` (or `--players a,b,c`) sets the roster; any other `--token` is a **mode arg**; bare tokens are participant names.

`handleStart` validates: mode non-blank, **category mandatory + exists**, and the descriptor's category
matches the requested one.

**Participant resolution** (`participants` [:1634](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L1634)) — **CHANGED**:

- With explicit names → each resolved via `playerExact`.
- With **no names and a player initiator** → the initiator **plus their online lobby group** (`core.lobbies().lobbyOf → onlineMembers`); never the whole server.
- With **no names and a console/non-player source** → falls back to **all online players** (`server.onlinePlayers()`).

### Registered modes ([`CoreGameRegistryInitializer`](../packages/core/src/main/java/com/sexidium/core/game/CoreGameRegistryInitializer.java))

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
[`ChallengeCatalog`](../packages/core/src/main/java/com/sexidium/core/game/experience/ChallengeCatalog.java),
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

`handleJoin` ([:1118](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L1118)):

1. **Reconnect first:** if the player has no active match but a persisted session, `core.games().handleJoin(player)` rejoins it (no gate) and reports `COMMAND_JOIN_SUCCESS`.
2. **Gated path:** builds `relatedPlayers(player)` ([:1152](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L1152)) = online lobby/group members **+** persisted friends, then calls `core.games().joinInProgress(player, modeId, relatedPlayerIds)`.

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
([LobbyResult.java:8](../packages/core/src/main/java/com/sexidium/core/lobby/LobbyResult.java#L8)).

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
`INVITE_ONLY`. Team/lobby model: [game framework](game-framework.md).

---

## `/sx experience`

`ExperienceCommands.handle` ([:44](../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L44)),
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
([:81](../packages/core/src/main/java/com/sexidium/core/game/experience/ExperienceService.java#L81)) is
the one number, read by `ExperienceCommands` rather than duplicated
([:36](../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L36)), because
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
[:187](../packages/core/src/main/java/com/sexidium/core/command/ExperienceCommands.java#L187)):
`join` suggests own + public-others ids; `edit`/`public`/`private`/`hardcore`/`rename`/`delete` suggest own ids;
`hardcore` suggests `on`/`off` as its third argument; `accept`/`deny` suggest online player names. Full model: [game framework](game-framework.md).

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
- **`/friend`** (`/sx friend`) — null `friends()` → `FRIEND_UNAVAILABLE`; SQLite-backed (`friends`/`friend_requests`). `add`/`accept`/`remove` resolve the counterpart via `onlinePlayerOrUsage` → `playerExact`, so the **other player must be online** even though accept/remove are pure DB ops.
- **`admin npc`** — full editor command set incl. `edit` (opens `core.menus().openNpcEditor`) and `mode <minigame|none>`; saves per-id YAML and respawns immediately; output uses `sendMiniMessage` directly (not localized). NPC model: [game framework](game-framework.md).
- **`admin map tntwar`** — persists to `<world-root>/<world>/` via `TntWarMapStore`; maps configured in `minigames.tntwar.maps`; messages localized via `TNTWAR_MAP_*`. Stand in the map world to set corners/spawns; a map needs both bases + both spawns to be `ready`.
- **`admin map combat`** — captures Combat-arena player spawn points into `sexidium-combat.yml` in the map's world folder; maps configured in `minigames.combat.maps`; mirrors `admin map tntwar`.
- **`race`** — only works when the player's current match game is a `RaceGame`, else `RACE_USAGE`; `vote`/`switch`/`allow` forward to `RaceGame` methods.

### `/sx admin backup`

The headless surface for experience backups, and the reason the feature is testable on the live
network at all: before it existed the **only** entry point was a tile in the lobby menu, so nothing
about backups could be exercised without a client standing in the lobby. Semantics — what a copy is,
what a restore does, why a refresh is a full re-copy — live in
[experiences § Backups](experiences.md#backups-a-backup-is-an-experience).

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
  [:133](../packages/core/src/main/java/com/sexidium/core/game/experience/ExperienceBackupService.java#L133)),
  so a second `create` / `restore` / `refresh` / `duplicate` typed on the same experience while the
  first is still copying is refused rather than allocating a second destination. The instruction is the
  same as the loaded-world refusal it shares the constant with: try again in a moment.

---

## Tab completion (VERIFIED)

`suggest` ([:107](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java#L107))
is permission-filtered; the first token follows `ROOT_ORDER`.

| Subcommand | Suggestions |
|---|---|
| (first token) | `ROOT_ORDER` filtered by permission |
| `start` | categories + `experience` → challenge ids (repeatable) / modes-in-category → online player names |
| `lobby` | the 13 actions; arg3 is context-sensitive (player names / open-lobby hosts / minigame ids / `ffa,2,3,4` / `1,2,3,4` / `public,friends,invite`) — `LobbyCommands.suggest` |
| `experience` | the 12 actions (`menu`/`edit`/`list`/`join`/`requests`/`accept`/`deny`/`public`/`private`/`hardcore`/`rename`/`delete`), then own/public ids or online names |
| `friend` | `add`/`accept`/`remove`/`list`/`requests` |
| `join` | running mode ids |
| `rank` | online player names |
| `auth` | `code` (admins only) |
| `race` | dedicated suggest helper |
| `admin` | `reload`/`stop`/`kit`/`bot`/`npc`/`net`/`backup`/`selftest`/`broadcast`/`map`; then `kit` → kit-or-player, `bot` → `status`/…/`config`, `npc` → NPC helper, `backup` → the 8 verbs then online player names, `map` → `tntwar`/`combat`/`edit` → each tool's own helper (`AdminCommands.suggest`) |

---

## Platform bridges

Both adapters forward 100% of dispatch and suggestion to the same `CoreCommandService`. The
differences are in wiring, permission resolution, and argument transport.

### Paper bridge

[`PaperCommandBridge`](../packages/module-paper/src/main/java/com/sexidium/paper/command/PaperCommandBridge.java)
is the executor + tab-completer for the `sexidium` command (alias `sx` from
[`plugin.yml`](../packages/module-paper/src/main/resources/plugin.yml)).

- **Source mapping** ([:34](../packages/module-paper/src/main/java/com/sexidium/paper/command/PaperCommandBridge.java#L34)): a `Player` → `PaperPlayerAdapter`; anything else → `PaperCommandSource`.
- **Argument transport:** native `String[]` passed unmodified.
- **Permissions:** Bukkit `sender.hasPermission(node)`. `plugin.yml` declares **only** `sexidium.play` (default `true`) and `sexidium.admin` (default `op`); **`sexidium.auth` is not declared.**
- **Reload callback:** `plugin::reloadSexidium` — a **full plugin reload**.

### NeoForge bridge

[`NeoForgeCommandBridge`](../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/command/NeoForgeCommandBridge.java)
registers two Brigadier literal roots (`sexidium` and `sx`,
[:51–53](../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/command/NeoForgeCommandBridge.java#L51)),
each with a single greedy-string `args` argument. Registration is driven by
`onRegisterCommands(RegisterCommandsEvent)`; `registerCommands(dispatcher)` is also exposed for tests
(there is no eager startup register). A bare root executes with `new String[0]`.

- **Source mapping** (`sourceFrom` [:91](../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/command/NeoForgeCommandBridge.java#L91)): a `ServerPlayer` → `NeoForgePlayerAdapter`; otherwise `NeoForgeCommandSource`.
- **Argument transport:** greedy string split by `NeoForgeCommandArgs.splitArgs` (trim + split `\s+`; empty → no args → help) for dispatch; `splitArgsForSuggest` (split on `' '` with `-1` limit, preserving a trailing empty token) for completion; suggestions re-offset to the last space ([:71–79](../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/command/NeoForgeCommandBridge.java#L71)).
- **Permissions** ([`NeoForgeCommandSource.hasPermission`](../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/command/NeoForgeCommandSource.java#L45)): `sexidium.admin` → vanilla op **level 2**; everything else (`play`, `auth`) → **level 0 → always `true`**. Admin checks fail closed when the source cannot be resolved.
- **Reload callback:** `core::reload` — **core-only reload**.

### Bridge comparison

| Aspect | Paper | NeoForge |
|---|---|---|
| Root registration | `plugin.yml` command + alias | two Brigadier literal roots |
| Aliases | `sexidium`, `sx` | `sexidium`, `sx` |
| Arg transport | native `String[]` | greedy string → `splitArgs` / `splitArgsForSuggest` |
| Source (player) | `PaperPlayerAdapter` | `NeoForgePlayerAdapter` |
| Source (console) | `PaperCommandSource` | `NeoForgeCommandSource` |
| Permission resolution | Bukkit named permissions | vanilla op level (admin=2, else 0) |
| `sexidium.auth` declared | No (relies on `play` fallback) | n/a (level 0 = always true) |
| Reload scope | `plugin::reloadSexidium` (full) | `core::reload` (core-only) |
| Console dispatch helper | [`PaperCommandDispatcherAdapter`](../packages/module-paper/src/main/java/com/sexidium/paper/adapter/command/PaperCommandDispatcherAdapter.java) (`Bukkit.dispatchCommand`) | [`NeoForgeCommandDispatcherAdapter`](../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/command/NeoForgeCommandDispatcherAdapter.java) (reflective `performPrefixedCommand`, falls back to `dispatcher.execute`) |

Both console dispatch helpers strip a leading `/`.

---

## Parity gaps & validation notes

- **[MEDIUM] Reload scope differs.** Paper `/sx admin reload` and `/sx admin bot reload` run a full plugin reload (`plugin::reloadSexidium`); NeoForge runs `core::reload` only. Confirm the two are equivalent or align them.
- **[MEDIUM] `sexidium.auth` undeclared in Paper `plugin.yml`.** The `auth OR play` check passes only via `play`'s default `true`; net behavior is currently correct, but the node is dead on Paper. Declare it (default `true`) to make the OR meaningful.
- **[LOW] Permission configurability.** NeoForge is op-level only (`play`/`auth` always-on, `admin`=op2); no per-node grant/revoke as on Paper.
- **[LOW] Hardcoded English output.** `bot` and most `/lobby` output are hardcoded English MiniMessage (only the `party.*` keys are localized).
- **[LOW] Online-only relationship ops.** `friend add|accept|remove` **and** `experience accept|deny` resolve the target via `playerExact`, so the other player must be online even though the underlying ops are UUID-keyed DB operations.
- **[INFO] `help` is admin-bucketed yet reachable publicly** via bare `/sx` (the empty-args branch runs before the gate).
- **[RESOLVED] Join/start relationship gating.** The old doc's "[HIGH] No party/friend gate on /sx join" and "all online players conscripted on /sx start" are now false: `join` is gated via `relatedPlayers` + `joinInProgress(NOT_RELATED)`, and a player initiator on `start` brings only their online lobby group (only a console/non-player source with no names still pulls the whole server).

---

## Keeping this current

Source of truth (the code; this doc is a derived view):
[`CoreCommandService.java`](../packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java)
(all dispatch, permission buckets, handlers, tab completion),
[`CoreGameRegistryInitializer.java`](../packages/core/src/main/java/com/sexidium/core/game/CoreGameRegistryInitializer.java)
(modes/categories), [`ChallengeCatalog.java`](../packages/core/src/main/java/com/sexidium/core/game/experience/ChallengeCatalog.java)
(challenge ids), [`LobbyResult.java`](../packages/core/src/main/java/com/sexidium/core/lobby/LobbyResult.java),
the two bridges (`PaperCommandBridge.java`, `NeoForgeCommandBridge.java`), and Paper `plugin.yml`.
Update **this doc in the same change** that touches those files. Triggers: a subcommand added/removed
or moved between permission buckets (edit `ROOT_ORDER`/the bucket sets); a new game mode, category, or
challenge id; a changed `LobbyResult`/`JoinResult`/`EnterOutcome` mapping; a bridge wiring, permission,
or reload-scope change; or a new/removed config key read by a handler (`auth.*`, `minigames.tntwar.maps`).
