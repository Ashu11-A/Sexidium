# Lobby, Worlds & Social

Sexidium's pre-match social layer (groups, friends, quick-play matchmaking), its
disposable-world leasing, and its lobby furniture (protection, HUD, NPCs) are one
domain. The three formerly-separate subsystems — the old "party", the host
"match-lobby", and the matchmaking queue — were collapsed into a single core
package, `com.sexidium.core.lobby`, built around one `Lobby` (a leader-keyed
roster in exactly one of three states) owned by `LobbyManager`. Worlds live in
`com.sexidium.core.world`, centred on `AbstractWorldControl` (the platform-neutral
lease policy engine) plus the `WorldNaming` codec; lobby NPCs live in
`com.sexidium.core.npc`. All logic is platform-agnostic core — the Paper and
NeoForge adapters are thin command/event pass-throughs (see
[neoforge-paper parity](game-framework.md)) and supply only world/NPC/menu SPI
backends.

> The old `com.sexidium.core.social` package (`PartyManager`/`Party`), the
> separate `MatchLobbyManager`/`MatchmakingManager` classes, and the per-platform
> `PaperWorldLeaseService`/`NeoForgeWorldLeaseService` *naming* logic no longer
> exist on the unified path. Where this doc disagrees with older docs, the code
> wins.

Related: [game framework](game-framework.md) · [experiences](experiences.md) · [menus](menus.md) · [commands](commands.md)

---

## 1. The unified `Lobby` state machine

A `Lobby` (`Lobby.java`) is a leader-keyed group: its `id` **is** the creating
leader's UUID and never changes even when the host transfers — fixing the old
name-keyed lobby id that collided across players and went stale after a hand-off
(`Lobby.java:24-47`). Members are an ordered `LinkedHashSet` (leader auto-added);
`Lobby` is the sole implementation of the `Roster` seam.

A lobby is in exactly one `LobbyState` at a time (`LobbyState.java`):

| State | Meaning | Old equivalent |
| --- | --- | --- |
| `IDLE` | Just a social group; no mode chosen | a "party" |
| `QUEUED` | One quick-play ticket submitted for `modeId()` | matchmaking queue entry |
| `CONFIGURED` | Leader staged a host match (mode + team shape) | a "match-lobby" |

Transitions are `toIdle()`/`toQueued(mode)`/`toConfigured(mode)`
(`Lobby.java:140-154`). Collapsing "party XOR host-lobby XOR queue" into one field
is what removes the old three-managers-fighting-over-a-player bookkeeping.

**Visibility** is `LobbyVisibility {PUBLIC, FRIENDS_ONLY, INVITE_ONLY}`
(`LobbyVisibility.java`), replacing the old open/private boolean.
`canJoin(playerId, friends, invited)` grants the host and any invite-holder
always, then `PUBLIC`=anyone, `FRIENDS_ONLY`=a friend of the host (via the
`FriendGraph` seam), `INVITE_ONLY`=nobody (`Lobby.java:177-186`).

**Team config** is carried verbatim from the old match-lobby and is only
meaningful in `CONFIGURED`:

- `teamCount`: `0` = free-for-all, else clamped to `2..MAX_TEAMS`
  (`Lobby.java:205-208`).
- `teamSize`: players per team (`Lobby.java:195-198`).
- `selectedTeams`: per-player chosen team; `selectTeam` rejects a full team.
- `capacity()` = `teamCount * teamSize` (unbounded for FFA) (`Lobby.java:215-217`).
- `assignedTeamsForStart(roster)` honours player selections, then round-robin
  fills the unselected (`Lobby.java:285-313`).

`Lobby.MAX_TEAMS = 4` is the host-selectable cap (`Lobby.java:26`), even though
the `TeamColor` palette has 8 colours (`TeamColor.maxTeams() == 8`). The team
*allocator* may still use the full palette; the lobby *picker* is capped at 4.

---

## 2. `LobbyManager` — group, queue, host-match, lifecycle

`LobbyManager` is the single owner of the pre-match subsystem, merging the old
`PartyManager`, `MatchLobbyManager`, `MatchmakingManager` and
`MatchParticipationCoordinator`. `playerToLobby` is the one reverse index (each
player → at most one lobby); `queueByMode`/`countdowns`/`launchFailures` drive
matchmaking (`LobbyManager.java:42-50`). All mutation runs on the server main
thread; `tick()` runs from a 1 Hz timer (`SexidiumCore.start()` →
`lobbyManager.start()`, `SexidiumCore.java:98,105`). Exposed as `core.lobbies()`.

### Group ops (was `PartyManager`)

| Op | Method | Notes |
| --- | --- | --- |
| Invite | `invite(inviter, target)` | Leader-gated; lazily creates the inviter's lobby; rejects `SELF`/`TARGET_IN_PARTY`/`FULL`; records a time-boxed invite (`:169-190`). |
| Accept | `accept(target, lobbyId)` | `lobbyId` null → auto-pick the only pending invite, or `AMBIGUOUS` when several are pending (`:196-228`). |
| Decline | `declineInvite(playerId, lobbyId)` | Removes one pending invite. |
| Join friend | `joinFriendLobby(joiner, friendId)` | Direct join into a friend's lobby, subject to `canJoin`. |
| Leave | `leave(player)` | Host role transfers to `oldestMember()`; an empty lobby is removed (`:245-278`). |
| Kick / Disband | `kick(leaderId, targetId)` / `disband(leaderId)` | Leader-only (`:280-303`). |

### Join an existing lobby (was `MatchLobbyManager.join`)

`join(player, lobbyId)` rejects if the player is already in a match
(`ALREADY_IN_MATCH` via `games.matchOf`), enforces `canJoin` visibility +
capacity, and detaches a solo lobby first (`:307-339`). `joinByName(player,
hostName)` resolves a host display name to their lobby; `inviteToLobby(host,
playerId)` is host-gated (`:342-379`).

### Host-match config (was `MatchLobbyManager` setters)

`configure(leader, mode)` resolves the minigame via `ModeResolver` and moves the
lobby to `CONFIGURED`; `setVisibility`/`setMinPlayers`/`setTeamSize`/
`setTeamCount`/`chooseTeam` mutate the staged shape. `start(leader)` builds the
mode args and launches (`:384-506`):

- Teams on → `teams:<N>`, `size:<n>`, and one `assign:<uuid>:<idx>` per player.
- FFA → `["ffa"]`.
- Calls `games.startWithPlayers(modeId, roster, null, modeArgs)`, then returns the
  lobby to `IDLE` regardless of outcome so the same roster can **play again**.

### Quick-play queue (was `MatchmakingManager`)

`queue(leader, mode)` is **leader-gated** (`:511-538`): a non-leader gets
`NOT_LEADER` and must leave the group to queue solo. The queue is a per-mode
insertion-ordered `LinkedHashSet` (= priority). `tick()` runs
`pruneQueue` + `evaluate` per mode (`:574-579`). A countdown starts when the
online queued count ≥ the mode's `minPlayers` (`onQueueChanged`/`evaluate`,
`:617-670`). `launch(mode)` pulls a roster up to `maxPlayers()` **without
splitting a group** across the cap, and on a failed start retries up to
`MAX_LAUNCH_RETRIES = 6` before clearing the queue (`:672-733`).

### Player lifecycle

`onPlayerQuit(playerId)` is the unified disconnect cleanup: drop the player,
transfer host or remove the lobby, recompute the queue (`:584-612`). It is called
from `GameEventRouter.handleQuit` (`GameEventRouter.java:50-52`), alongside
`GameManager.handleQuit`.

### Config keys (see §7 for the important caveat)

| Setting | First key tried | Legacy fallback | Default |
| --- | --- | --- | --- |
| Max group size | `lobby.max-size` | `party.max-size` | 8 (min 2) (`:127-130`) |
| Invite TTL | `lobby.invite-expiry-seconds` | `party.invite-expiry-seconds` | 60 s (min 5 s) (`:889-892`) |
| Queue countdown | `lobby.queue.countdown-seconds` | `matchmaking.countdown-seconds` | 5 s (`:894-897`) |
| Queue cap | `lobby.queue.max-players` | `matchmaking.max-players` | 12 (min 2) (`:899-902`) |

---

## 3. Seam types

These small interfaces/value types keep `LobbyManager` unit-testable and decouple
it from the game/world/DB stack.

| Type | Role |
| --- | --- |
| `Roster` | The shared "group" interface (`leader`, ordered `members`, `onlineMembers` fan-out). `Lobby` is the only impl (`Roster.java`). |
| `FriendGraph` | Read seam over the persistent friend graph (`areFriends`, `friends`), so `Lobby.canJoin` and tests depend on an abstraction. Implemented by `FriendService`; **nullable when no DB** (`FriendGraph.java`). |
| `MatchLauncher` | The narrow slice of `GameManager` the queue needs (`descriptors`, `matchOf`, `startWithPlayers`). `GameManager` already satisfies it (`MatchLauncher.java`). |
| `ModeResolver` | Single minigame-id/alias resolver + `minPlayers` lookup over `CATEGORY_MINIGAMES` descriptors (`ModeResolver.java`). |
| `InviteBook` | Time-boxed invite store keyed `target → {lobbyId → expiryEpochMillis}`; a player may hold invites from several lobbies (the `AMBIGUOUS` path); `purgeExpired` on read (`InviteBook.java`). |
| `LobbyResult` | The single outcome enum merging the old `PartyManager.InviteResult`/`AcceptResult`, `MatchLobbyManager.Result` and `MatchmakingManager.JoinResult` (`LobbyResult.java`). |

---

## 4. Friends (SQLite-persisted) and the commands

`FriendService` (`FriendService.java`) is the **only persisted** social state —
the group/queue maps in `LobbyManager` are in-memory only and vanish on restart.
It is backed by SQLite tables `friends` (bidirectional rows) and
`friend_requests`, plus `idx_friend_requests_to` (`SchemaMigrator.java:99-134`).
Writes run on a single-thread daemon `Sexidium-Friends-DB`; `accept()` and all
reads are synchronous under `database.lock()`. There is **no request expiry**.
`FriendService` is `null` when no database is configured (`SexidiumCore.java:62`),
and friend commands then report `FRIEND_UNAVAILABLE`.

### `/friend` (unchanged in shape)

`/friend add|accept|remove|list|requests` (also `/sx friend …`), all requiring the target online
(`FriendCommands.handle`).

### `/lobby` (groups + host-lobby + queue, all unified)

Bare `/lobby` (also `/sx lobby`) opens the unified lobby menu (`menus().openLobby`).
Subcommands (`LobbyCommands.handle`):

| Subcommand | Routes to |
| --- | --- |
| `invite` / `accept` / `leave` / `kick` / `disband` / `list` | group ops (reuse the localized `party.*` message keys for display) |
| `join <host>` | `joinByName` |
| `mode <minigame>` | `configure` (→ `CONFIGURED`) |
| `teams <2-4\|ffa>` | `setTeamCount` |
| `size <n>` | `setTeamSize` (players per team) |
| `visibility <public\|friends\|invite>` | `setVisibility` |
| `start` | `start` (host-only launch, then play-again) |
| `queue <minigame>` / `queue leave` | `queue` / `dequeue` |

> **Removed:** standalone `party` and `queue` subcommands no longer exist — both fold into the
> unified `/lobby` global (`/lobby queue`, `/lobby invite|accept|…`). `PLAYER_SUBCOMMANDS` lists
> `lobby` and `friend` but no `party`/`queue` (`CoreCommandService.java:28`). The roster ops
> deliberately reuse the `party.*` i18n keys, so player-facing text still says "party".

### Match-join authorization (the old "open join" finding — now fixed)

`/sx join <mode>` builds `relatedPlayers` = online lobby/group members + DB
friends, then calls `GameManager.joinInProgress(player, mode, relatedPlayerIds)`
(`CoreCommandService.java:1140-1167`). The manager returns **`NOT_RELATED`** when
a match of the mode is running but contains none of the related players
(`GameManager.java:487-512`). A reconnect to the player's *own* persisted match
(e.g. after a restart) bypasses the gate; admins place arbitrary players via
`/sx start`.

---

## 5. Unified world control (`AbstractWorldControl` + `WorldNaming`)

`AbstractWorldControl implements WorldLeaseService` and owns **all** world policy
that used to be copy-pasted across the two platform lease services: the
managed/leased/preserved name registries, the warm temp pool, the
dispose-vs-unload-and-keep decision tree, and player evacuation to the lobby
(`AbstractWorldControl.java`). Consumers still call
`serverAdapter.worlds()` unchanged. A platform "backend" supplies only thin hooks
(`runOnWorldThread`, `backendAcquire`, `backendResolveLoaded`, `backendUnload`,
`backendLobby`, `backendRemoveStaleWorldgenDatapack`, `backendCleanupStaleTempWorlds`, disk
roots) — no naming or policy.

### `WorldNaming` — the single name/key/path codec

`WorldNaming` classifies and addresses every managed world (`WorldNaming.java`):

| World | Key | Canonical runtime name | On-disk (Paper, MC 26.1+) |
| --- | --- | --- | --- |
| Lobby | `minecraft:lobby` | `lobby` | `world/dimensions/minecraft/lobby` |
| Experience | `experiences:<nick>/<map>_<id>` | `experiences/<nick>/<map>_<id>` | `world/dimensions/experiences/<nick>/<map>_<id>` |
| Temp/game | `sexidium_temp:<short>` | `sexidium_temp/<short>` | `world/dimensions/sexidium_temp/<short>` |

`isLobby`/`isExperience`/`isTemp` accept any runtime form. Crucially,
`sameWorld()` **flattens separators** (`/ \ :` → `_`) before comparing, so a
canonical slash-path name and the platform's flattened live label (e.g. a keyed
Paper world from `sexidium_temp:<short>` reports `sexidium_temp_<short>`) compare
equal — the fix for players being ejected from their own match world the instant
they were teleported in (`WorldNaming.java:239-261`).

### Lease lifecycle

`LeasedWorld` is the shared `WorldLease` handle; its `close()` routes back to
`AbstractWorldControl.onLeaseClosed`, which applies kind-specific policy
(`AbstractWorldControl.java:380-399`):

| Entry point | Use |
| --- | --- |
| `acquireReady(profile)` | Poll a warm temp world of any `WorldProfile` from the pool (synchronous). The no-arg `acquireReady()` is the Overworld shortcut, for the many callers that only ever want one. |
| `acquireOrCreate(...)` | Ready world, else create a fresh temp world async (`:227-247`). |
| `acquireOrCreateClone(template, ...)` | Clone a template; degrade to a fresh world on failure (`:249-269`). |
| `acquireOrCreatePersistent(name, ...)` | Experience worlds (`:303-335`). |
| `reacquireByName` / `reacquirePersistent` | Re-attach after a restart (reconnect). |
| `discardByName` / `deletePersistent` | Drop an abandoned temp world / permanently delete an experience. |

`WorldKind` (`WorldKind.java`) drives disposal:

| Kind | On release |
| --- | --- |
| `LOBBY` | Never torn down by a lease close; preserved + saved. |
| `PERSISTENT` (experience) | Unloaded **and saved**; never auto-deleted. |
| `TEMP` / `CLONE` | Evacuated, unloaded, and deleted (`delete-on-release`). |

### Warm pool + runtime GC (core now populates both)

`start()` preserves the lobby, deletes any stale worldgen datapack, cleans stale
temp folders and stale external world registrations, then warms the pool.

**The pool is keyed by shape, not by consumer.** `WorldProfile` (dimension +
void/natural + vanilla preset) is what decides whether a warm world can serve a
request; `WorldPool` holds one queue per profile. Every consumer shares it —
minigames take a disposable Overworld through `acquireReady()`, experiences take
an Overworld **plus a Nether and an End** for their linked dimensions.

| Profile | Default warm | Serves |
| --- | --- | --- |
| `overworld` | 5 | minigames, experience overworlds, **Death Resets regenerations** |
| `nether` | 3 | every experience's linked Nether |
| `end` | 3 | every experience's linked End |
| `overworld-void` | 1 | the SkyBlock map types (island built into it) |
| `nether-void` | 1 | Classic Skyblock's Nether mirror |

The Overworld is warmed deepest (5, `WorldPool.DEFAULT_OVERWORLD_WARM_SIZE`) because it is what
everything consumes, and a Death Resets regeneration actually costs **three** warm worlds — an Overworld
*and* a Nether *and* an End — so with nether/end at 3 the fourth back-to-back reset generates a sibling
inline (harmless but a stall). Sizes are `worlds.temp.pool.<profile>`; the legacy
`worlds.temp.pool-size` still sets the Overworld target. Taking a world immediately starts generating its
replacement (lazy replenishment, one at a time, off the critical path).

**Two fixes live here, and both were why the pool looked ignored:**

1. Warm worlds were only ever used for minigames. An experience went straight to
   `acquireOrCreatePersistent` → `createWorld`, and then `ensureExperienceSiblings`
   generated a Nether and an End — three generations, on the server thread, while
   the player waited. `acquireOrCreatePersistent` now tries the pool first
   (`adoptForNewWorld`), and `createOrLoadSibling` does the same for each sibling.
2. The refill condition subtracted worlds already handed out
   (`ready + leased + pending >= pool-size`), so three concurrent matches emptied
   the pool **and stopped it refilling**. The target is now how many are kept
   *ready*; leases do not count against it (`WorldPool.shortfall`).

**Adoption** (`backendAdopt`) is what makes a start free: the warm world is
unloaded *with save*, its folder is **moved** (a rename on the same filesystem —
no chunk data is copied), and it is loaded again under the requested key. The
pooled world's **seed travels with it**, otherwise chunks generated later as the
player walks out of the pre-generated area would come from a different seed and
leave a visible terrain seam. A world already on disk is never adopted — an
experience being replayed must load its own saved world.

`collectGarbage(inUseWorldNames)` protects the lobby, in-use names, leased,
preserved, and every warm world (`WorldPool.all()`), then unloads/deletes every
other disposable temp world (loaded orphans + disk-only crash leftovers). Driven
by `worlds.temp.gc.{enabled, initial-delay-seconds=300, period-seconds=300}`.

### Where a new world starts (the "spawned in the ocean" fix)

A new world's spawn is **pinned**, not searched for: Minecraft's own spawn finder generates chunks on the
server thread and is half of what makes creating a world a multi-second freeze
(`creator.forcedSpawnPosition`). The cost of that shortcut was landing on whatever happens to be at
(0, 0) in a random seed — very often open ocean.

The fix is to choose the spawn from the **biome source** rather than the terrain
(`WorldAdapter.locateLandSpawn` → Paper's `World.locateNearestBiome`). That generates nothing, so it can
afford to look 6400 blocks out — far enough to actually leave an ocean, which a terrain probe never
could. `PaperWorldControl.pinLandSpawn` runs it on every newly generated Overworld; for a pooled world
that is **at boot, with nobody waiting**, so by the time a player is handed the world its spawn is already
on land. Config: `worlds.temp.land-spawn.{enabled, search-radius}`.

Two details that are easy to lose:

- **The spawn travels through adoption.** Our dimension folders carry no `level.dat`, so reloading a moved
  folder re-derives the spawn and would undo the work. `backendAdopt` reads `getSpawnLocation()` before
  unloading and restores it after — same reasoning as the seed.
- **Void worlds are exempt.** A SkyBlock's island is built at the pinned origin on purpose.

`SafeSpawn` is the per-player safety net underneath, and it had the same blind spot: an ocean column's
"highest block" is the **water surface**, so its fallback lift dropped the player into the sea. It now
recognises a liquid column and spends one coarse wide sweep (`SHORE_RADIUS`/`SHORE_STEP`) looking for a
shore before settling. It still always returns a position — callers teleport with it and cannot take null.

### Deleting a world must also unregister it

Multiverse autoloads what is on its books **before Sexidium is enabled**, so a
registration left behind for a folder we deleted becomes a
`WORLD_FOLDER_INVALID` error on every boot from then on, for ever. Deleting an
experience used to unregister only the overworld, and only when it happened to be
loaded — so siblings were *never* unregistered, and an unloaded experience left
its own entry behind too.

- `PaperWorldControl.deletePersistent` now calls `forgetRegistration` for the
  overworld **and** both siblings, unconditionally, trying each name form
  Multiverse could have filed the world under (`ns:key`, `ns_key`, `key`).
- `backendCleanupStaleRegistrations` (run at boot) reconciles the registry
  against disk: anything Multiverse lists under a namespace **we own** whose
  dimension folder is gone is dropped. This is the repair for entries that
  predate the fix — nothing else ever revisits them.
- `MultiverseBridge.forgetWorld` tries `removeWorld(String)` and then resolves
  the world object and tries every single-argument `removeWorld` overload,
  verifying with `isRegistered` after each; MV v5 does not reliably accept a bare
  name.

### Templates & the lobby bundle

- `WorldClone` copies a template save folder (skipping `session.lock`/`uid.dat`/
  `*.lock`) for `CLONE` worlds.
- `LobbyBundle` extracts an embedded `bundled/world.zip` into the lobby dimension
  folder + `bundled/settings.zip` into the data dir, gated by
  `worlds.lobby.create-if-missing`, with a zip-slip guard (`LobbyBundle.java`).
  A world is embedded at build time via `-PlobbyWorldZip`/`-PlobbyWorldDir`
  (config.yml:167-174); the bundled lobby world ships at
  `assets/worlds/lobbies/Medieval-BreadBuilds.zip` (the medieval BreadBuilds lobby —
  `scripts/init-paper.sh` passes that path). `AppleMC_Spawn.zip` sits beside it as the
  previous build; swap the `-PlobbyWorldZip` path to go back. (The old `Lobby-37.zip`
  Asia-theme lobby was buggy and has been deleted.) This supersedes the old "operators manually
  extract the lobby zip at the repo root" workflow.
- Source zips may nest the world under a wrapper folder (`My Map/level.dat`) or use
  MC 1.21.6+ dimension storage; `worldContentRoot` in the root `build.gradle.kts`
  normalises both — shared with `prepareMapBundle` — so `world.zip` always has
  `region/` at its root.
- `PaperLobbyBootstrap.stripWorldRootArtifacts` deletes `level.dat`/`level.dat_old`/
  `session.lock`/`uid.dat` after seeding (a dimension folder must not carry them). Because
  `level.dat` is the only place a downloaded map's spawn lives, `captureBundledSpawn` reads it
  FIRST (`LevelDataSpawn`, a minimal NBT reader handling both the MC 26.1+ `Data.spawn`
  compound and classic `SpawnX/Y/Z`) and persists it as spawn point 1 of the lobby sidecar.
  Skip that and the dimension has no spawn of its own, so vanilla's initial-spawn biome probe
  drops joiners hundreds of blocks from the build (BreadBuilds: spawn 8/47/8, players landed
  near -576/-464 in empty terrain). An existing sidecar is never overwritten, and a map with no
  readable spawn still needs `/sx lobby setspawn`. Re-seeding requires deleting the lobby dimension folder
  (`<container>/world/dimensions/minecraft/lobby`) — an existing lobby is never clobbered.

---

## 6. On-disk layout & platform differences

### Paper (migrated)

`PaperWorldControl extends AbstractWorldControl` is the Paper backend. It creates
worlds with native keyed `WorldCreator.ofKey(key)`, **copying the overworld's
generation settings** so a non-`minecraft`-namespace world gets real terrain
instead of void, then imports each into Multiverse-Core v5 for `/mv` tooling
(`PaperWorldControl.java:110-167`). The lobby world itself is seeded by
`PaperLobbyBootstrap` via `LobbyBundle` (gated by `create-if-missing`).
`applySettings` applies PvP/difficulty/border/gamerules through `WorldSettings`,
including `world.setPVP(settings.pvp())` (`PaperWorldControl.java:354-374`).

> `worlds.temp.pvp` now defaults to **true** (config.yml:96-99). Bukkit worlds
> default to `pvp=false`, which silently killed all PvP in fighting modes before
> this was applied at the world level.

`backendInstallWorldgen` only **deletes** a stale `datapacks/sexidium_worldgen`
folder — Sexidium writes no boot-time datapack (see §8) (`PaperWorldControl.java:282-290`).

### NeoForge (NOT migrated)

NeoForge still runs the legacy `NeoForgeWorldLeaseService` on the flat
`<serverHome>/worlds/...` layout. `NeoForgeLobbyBootstrap` creates the lobby via
the reflective `ServerLevel` pipeline, re-qualifying its name to `sexidium:lobby`
(`NeoForgeLobbyBootstrap.java:29-69`). Migrating it onto `AbstractWorldControl`
(restoring the `<nick>/` experience nesting and gaining PvP/difficulty + portal
parity) is the documented next step.

### Config keys

`worlds.lobby.{name, namespace=minecraft, create-if-missing, generator, game-mode,
difficulty, pvp, alias, as-default-spawn, spawn, lock-time, time, protection.*}`;
`worlds.temp.{namespace=sexidium_temp, subdir, pool-size, name-prefix, world-size,
pvp=true, warning-distance, damage-per-block, difficulty, cleanup-stale-on-start,
delete-on-release, auto-save, gc.*}`;
`worlds.experiences.{namespace, subdir, max-per-player, require-owner-online,
world-size=0, linked-dimensions}` (config.yml). The
legacy `temporary-worlds.*` block (which holds the only `enabled=true`) is read as
a fallback (`AbstractWorldControl.java:686-712`).

---

## 7. Config caveat: which keys are actually live

The new `lobby.*` group/queue keys in §2 and the `party.*` keys are **not present
in shipped `config.yml`** — only the `matchmaking:` block exists
(`countdown-seconds: 5`, `max-players: 12`, config.yml:969-973), and there is no
`party:` block at all. Practical consequence on a default install:

- Queue countdown / cap come from `matchmaking.*` (5 / 12).
- Max group size and invite TTL fall through to the **hardcoded defaults** (8, 60 s)
  because neither `lobby.*` nor `party.*` is defined.

Document `lobby.max-size`, `lobby.invite-expiry-seconds`,
`lobby.queue.countdown-seconds`, `lobby.queue.max-players` as **new/optional**
overrides a server owner may add; they take precedence when present.

The `lobby.*` keys that *do* ship are `lobby.hud.{enabled, refresh-ticks=40,
max-friend-lines=5}` and `lobby.npcs.{enabled, hologram-refresh-ticks=40,
hologram-font}` (config.yml:178-189) — these belong to the HUD and NPCs below, not
to the group/queue subsystem.

---

## 8. Lobby protection, nav items & HUD

`LobbyGuardPolicy` is the shared decision logic: `isProtected(player)` = in the
lobby world **and** not in a match; flags read
`worlds.lobby.protection.{block-break, block-place, damage, hunger, item-drop,
interactions, no-mob-spawn, void-redirect, void-level}`
(`LobbyGuardPolicy.java:34-102`).

`PaperLobbyGuard` enforces it natively and renders a **dynamic, inheritance-based
hotbar** driven by the platform-agnostic `HotbarController`
(`com.sexidium.core.world.hotbar`): **Menu** (compass, slot 0), **My Experiences**
(ender chest, slot 1), **Minigames** (diamond sword, slot 2), **Players** (head,
slot 4 — a click-to-teleport roster that works across lobby worlds), and a
conditional **Friend Requests** badge (slot 8, shown only when requests are
pending). The guard clears the inventory and re-renders the resolved items — each
PDC-tagged with its routing id — on join/world-enter/respawn/pack-load, and forwards
a click back to `core.hotbar().handleClick(...)`. Only the LOBBY scope carries items,
so entering a match strips them and a match kit can't return to the lobby. The full
model (shared `UiItem`, `HotbarItem` subclasses, the single Paper materializer, and
how to add an item) is documented in
[UI interaction system](ui-interaction-system.md). The hotbar + crafting lock are
**Paper-only**; NeoForge parity is outstanding.

`LobbyHud` is a platform-agnostic per-player scoreboard for lobby players: total
players, the player's ping, global points/level/rank-class (`RankAwardPort`), and
online friends (`FriendService`). Config `lobby.hud.{enabled, refresh-ticks=40,
max-friend-lines=5}` (`LobbyHud.java:29-53`). It also mirrors the player's
progression onto the vanilla XP bar each tick — the number is their level and the
green fill is progress toward the next level (`points % pointsPerLevel`); this is
cleared by `resetStatuses()` on match entry so it never leaks into a minigame.

---

## 9. Lobby NPCs (`com.sexidium.core.npc`)

`NpcManager` loads one YAML file per NPC under `<dataFolder>/fakeplayers/`, spawns
them via the platform `NpcAdapter`, and refreshes hologram placeholders on a timer
(config `lobby.npcs.{enabled, hologram-refresh-ticks=40, hologram-font}`)
(`NpcManager.java:38-101`).

A click on a **minigame-bound** NPC routes to `LobbyManager.queue(player, mode)`
(quick-play), taking priority over the NPC's click-command; a sneaking admin click
opens the editor (`NpcManager.java:144-189`). Holograms support `%players_total%`,
`%players_<modeId>%` (live match counts via `GameManager`), `%queue_<modeId>%`
(live `LobbyManager.queueSize`), and an animated `%phase%` gradient token
(`:253-311`). `NpcManager` depends on `LobbyManager` (not the removed
`PartyManager`); decor sync is injected as callbacks to stay decoupled from
`DecorManager`.

---

## 10. World generation (research subsection)

**Hard constraint:** Sexidium writes **no** boot-time worldgen datapack. A
`structure_set`/`dimension` JSON pack loads during registry freeze *before*
plugins enable, so any schema mismatch on a future MC version hard-fails boot and
the plugin cannot self-heal. `PaperWorldControl.backendInstallWorldgen` only
deletes a stale pack (`PaperWorldControl.java:282-290`).

**Borderless experiences (current default).** Experience worlds are now created
with **no world border** (`worlds.experiences.world-size: 0`, read by
`WorldConfig.experienceBorderSize`), so the vanilla seed-generated stronghold +
End portal is naturally reachable and no artificial portal is built. (The legacy
artificial reachable-End guarantee — `WorldGenSpec` / `ExperienceWorldGen` and the
`guaranteed-stronghold`/`portal-repair-at-runtime` config — has been **removed**;
borderless experiences make it unnecessary.) `PaperWorldControl.applySettings`
clears any border saved in an existing experience's `level.dat` on load (migration).

**Per-experience Nether + End (Paper).** Each experience overworld eagerly gets
its own linked siblings `experiences:<key>_nether` and `experiences:<key>_end`
(created on the world thread in `PaperWorldControl.ensureExperienceSiblings`,
copying the server Nether/End generation + the overworld seed, borderless). A
`PaperPortalListener` redirects nether/End portal travel inside an experience to
*that* experience's siblings (overworld↔nether coord ×/÷8; overworld→own End entry
platform at 100,50,0; End exit→overworld spawn); the lobby/temp/server worlds are
untouched. `deletePersistent` cascades to the sibling folders. Toggle with
`worlds.experiences.linked-dimensions` (default true). NeoForge keeps single-world
experiences (out of scope).

Surveyed alternatives for richer/custom maps (kept as design backdrop): runtime
in-world build (current), a custom `ChunkGenerator` via
`WorldCreator.generator(...)`, runtime structure placement, FAWE schematic paste,
slime worlds, Iris/Terra dimension engines, and Multiverse generator selection.
Proposed SPI seams (if richer maps are ever wanted): a
`WorldAdapter.pasteSchematic` primitive and a pluggable generator id — all runtime,
restart-free, no boot-brick risk.

---

## Keeping this current

The code is the source of truth; this doc is a derived view. Authoritative files:
`com.sexidium.core.lobby.*` (`Lobby`, `LobbyManager`, `Roster`, `FriendGraph`,
`FriendService`, `InviteBook`, `LobbyState`, `LobbyVisibility`, `LobbyResult`,
`ModeResolver`, `MatchLauncher`), `com.sexidium.core.world.*`
(`AbstractWorldControl`, `WorldNaming`, `WorldKind`, `LobbyGuardPolicy`,
`LobbyHud`, `LobbyBundle`, `WorldClone`),
`com.sexidium.core.npc.NpcManager`, `com.sexidium.core.world.hotbar.*`
(`HotbarController` + items — see [UI interaction system](ui-interaction-system.md)),
the Paper/NeoForge world+guard backends,
`CoreCommandService` (`handleLobby`/`handleFriend`/`handleJoin`),
`GameManager.joinInProgress`, and `config.yml`. **Update this doc in the same
change that touches them.** Edit triggers: a new class added to any of those
packages; a signature/behaviour change in the lobby state machine, lease
lifecycle, join gate, or lobby hotbar; or a config key added/removed (especially the
§7 `lobby.*`/`matchmaking.*`/`worlds.*` keys).
