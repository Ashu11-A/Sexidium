# Minigames

The five round-based, competitive game modes registered under the `minigames` category:
**Race for Item**, **Gather and Duel**, **TNT War**, **Combat Item Mode**, and **Fugitive**. They
live in
[`packages/core/src/main/java/com/sexidium/core/game/modes/minigames/`](../../packages/core/src/main/java/com/sexidium/core/game/modes/minigames)
and all extend a thin shared base —
[`MinigameMode`](../../packages/core/src/main/java/com/sexidium/core/game/modes/MinigameMode.java) →
[`BaseTimedGame`](../../packages/core/src/main/java/com/sexidium/core/game/modes/BaseTimedGame.java) →
[`AbstractGame`](../../packages/core/src/main/java/com/sexidium/core/game/AbstractGame.java). They touch
only the platform SPI and immutable model records, so identical logic runs on Paper and NeoForge. For
the engine that launches/ticks/cleans up matches see [game framework](../architecture/game-framework.md); for the SPI
see [platform abstraction](../architecture/platform-and-adapters.md). The composable challenge modes are documented in
[experiences](experiences.md).

---

## Mode catalog

Registration (id, category, display name, minPlayers, aliases) lives in
[`CoreGameRegistryInitializer#registerMinigames`](../../packages/core/src/main/java/com/sexidium/core/game/CoreGameRegistryInitializer.java#L33-L53).
Launch with the category-first grammar `/sx start minigames <mode> [players/args…]`.

| Mode | id (display name) | aliases | minPlayers | Objective | Win condition | Reconnectable? |
| --- | --- | --- | --- | --- | --- | --- |
| Race | `race` (*Race for Item*) | `raceforitem`, `item` | 1 | First to clear all 3 random objectives (items + structures; never 2 hard) | First (player or team) to clear all 3; on timeout an overtime vote or the untied score leader | yes (own respawn; no mode-data persistence) |
| Gather | `gather` (*Gather and Duel*) | `duel`, `gatherandduel`, `gatherduel` | 2 | Gather phase → duel phase (FFA, 1v1 tournament, or team FFA) | Last survivor / tournament champion / last team standing; or highest health on timeout | no |
| TntWar | `tntwar` (*TNT War*) | `tnt`, `war` | 2 | Red vs Blue base-destruction war on a cloned/generated map | Destroy `win-destruction-percent` of the enemy base, or empty enemy lives; timeout → more-destroyed wins, else draw | yes (persists/restores mode data) |
| Combat | `combat` (*Combat Item Mode*) | `kit`, `kitpvp` | 2 | Last-man-standing PvP in an arena | Last survivor (FFA) / last team standing; or single highest-health player on timeout | no |
| Fugitive | `fugitive` (*Fugitive*) | `manhunt`, `thefugitive`, `fled` | 3 | One fugitive (head start + escape kit) vs. many hunters; hunters chase with a fugitive-tracking compass | Fugitive survives the `hunt-seconds` timer (30 min); hunters win by killing the fugitive after release | yes (own role-aware persistence) |

**Reconnect** is gated by the framework default
[`BaseTimedGame#isReconnectable`](../../packages/core/src/main/java/com/sexidium/core/game/modes/BaseTimedGame.java#L57-L60),
which returns `reconnect.enabled` (default `true`). TntWar and Race inherit that default; Fugitive
overrides it (`FugitiveGame.java:164-166`) so it can additionally pause the match when the fugitive
leaves. Combat and Gather are short-lived FFA matches with no mode-data persistence — a leaver is
simply removed.

---

## Shared base (`MinigameMode` → `BaseTimedGame`)

`MinigameMode` is intentionally tiny
([`MinigameMode.java`](../../packages/core/src/main/java/com/sexidium/core/game/modes/MinigameMode.java)):

- **Config namespace.** `configPrefix()` → `"minigames." + id()` (lines 40-42), so every
  `configPath("…")` resolves under `minigames.<id>.*` in `config.yml`.
- **Default start announcement.** `announceStarted()` emits `COMMAND_START_SUCCESS` (lines 45-49);
  every concrete mode overrides it with a themed message.
- **The team system** (see [next section](#shared-team-system)).

### Inherited lifecycle

`BaseTimedGame` supplies the timed-elimination skeleton:

| Method | Behaviour |
| --- | --- |
| `start(participants)` | `beginRunning()`, then `startParticipant()` per player, `announceStarted()`, and if `durationSeconds() > 0` schedules `endSoon(duration*20)` (a hard end). Each concrete mode overrides `start`, calls `super.start(...)` **first**, then teleports/sets up timers (`BaseTimedGame.java:20-30, 64-69`). |
| `startParticipant(p)` | `addParticipant` + `prepareSurvival` (heal, food 20, `SURVIVAL`) + `giveKit(configuredKit())` + `awardParticipation` (lines 64-69). |
| `onParticipantAdded(p)` | Mid-match join: `addParticipant` + `prepareSurvival` + `giveKit` only (lines 71-79). No mode overrides it — see [known issues](#known-issues). |
| `handleDamage(event)` | Default last-man-standing: if the victim is a participant and the hit is lethal, `eliminate(victim, attacker)` (lines 114-120). |
| `eliminate(victim, attacker)` | `removeParticipant` + `awardKill(attacker, if participant)` + `checkForWinner` (lines 122-131). Modes override to also `releaseAndReset(victim)` and announce. |
| `checkForWinner()` | Declares a win when `remainingOnlineParticipants().size() == 1 && players.size() <= 1` (lines 133-141). Only Combat (FFA) and Gather (FFA) route through it; Race, TntWar, and Fugitive use their own win paths. |

### Shared `game.*` tunables

`warmup-seconds`, `friendly-fire`, and `starter-kit` live once under the global `game:` section
(`config.yml:216-222`) and are read via `tunableInt/Boolean/String`, each of which prefers a per-mode
override and otherwise inherits the global (`BaseTimedGame.java:102-112`).

### Player-state model

No pre-match inventory, XP, or location is snapshotted. `prepareSurvival` plus per-mode
`clearInventory` wipe the player on start; `releaseAndReset` → `resetStatuses` returns them to a fixed
lobby baseline on eliminate/exit/stop. **Data loss** if a match is started in a survival world.

### Event delivery

Non-lifecycle `GameEvent`s are broadcast to **every** active match, so each mode self-filters with
`isParticipant(...)` on the first line of every event branch. All five do this — but it is a trap for
new modes that react to global events (`EntityDeath`, `BlockBreak`) without a participant guard.

---

## Shared team system

`MinigameMode` owns a **match-local** team system (`MinigameMode.java:19-232`), distinct from the
shared-life/auto-team mechanism in [experiences](experiences.md) — reference that doc only to
disambiguate; do not conflate the two.

### Enabling team play

`teamPlay()` is true when either source asks for ≥2 teams (`MinigameMode.java:55-61`):

| Source | Args / key | Meaning |
| --- | --- | --- |
| Team count | `teams:<n>` (or `ffa`) | Fixed number of coloured teams; `ffa` forces FFA (`teamCountFromArgs`, lines 67-85). |
| Team size | `size:<n>`, legacy `team:<n>` (or `ffa`) | Players per team (`teamSizeFromArgs`, lines 122-141). |
| Config | `minigames.<id>.players-per-team` | Default `0` = FFA (`playersPerTeamConfig`, lines 110-116). |
| Assignments | `assign:<uuid>:<index>` | Explicit per-player team from a host match-lobby (`teamAssignmentsFromArgs`, lines 87-108). |

> No minigame block in `config.yml` ships a `players-per-team` key — it defaults to `0` (FFA) unless
> set or overridden by a mode arg.

### Team allocation and palette

`formTeams()` picks `TeamAllocator.allocateAssigned` (fixed count + explicit assignments) when
`teams:<n>` is given, else `TeamAllocator.allocate` (balanced, count =
`clamp(ceil(players/size), 2, palette)`) — `MinigameMode.java:149-160`, `TeamAllocator.java:20-94`.
The palette is 8 colours — RED, BLUE, GREEN, YELLOW, AQUA, PINK, ORANGE, WHITE — each with a display
name, MiniMessage colour tag, and wool `ItemKey` for GUI icons, capping team count at 8
(`TeamColor.java:8-49`).

`usesTeamSidebar()` defaults to `true` and renders a shared right-side team panel via
`TeamDisplay.build`. Race and TntWar override it to `false` because they own their own scoreboard
(`MinigameMode.java:155-169`, `RaceGame.java:69-72`, `TntWarGame.java:96-100`).

### Per-mode integration

| Mode | Team mechanism |
| --- | --- |
| Combat | `formTeams` + `checkForTeamWinner` + `sameTeam` friendly-fire cancel. |
| Gather | `formTeams` + team friendly-fire in both phases + `finishDuelByTeamHealth` + last-team-standing (suppresses the 1v1 ladder). |
| Race | `formTeams` + per-team score groups + random team leaders + live switching (`/sx race`). |
| Fugitive | **Not** the `Teams` system — fixed fugitive/hunter roles instead. |
| TntWar | **Own** hard-coded Red/Blue string teams (`teamOf` map), independent of `Teams`. |

The shared types live in `com.sexidium.core.game.team` (`Team`, `Teams`, `TeamColor`,
`TeamAllocator`, `TeamDisplay`).

---

## Worlds

A match runs in a leased temporary world (or the players' current world if temp worlds are disabled).
Two world-acquisition mechanisms apply:

- **Procedural arena generation** — Combat and Gather (duel) build a flat walled platform via
  `ArenaGen.ringArena` when `arena.spawns` is empty **and** `arena.world` is blank **and**
  `arena.generated.enabled`; TntWar builds two symmetric bases via `ArenaGen.teamBases` when no map is
  ready and `generated.enabled`. See [worlds](lobby-worlds-and-social.md) for `ArenaGen`.
- **Map cloning** — Fugitive, Race, and TntWar may instead clone a hand-built map per match.
  Fugitive/Race read `minigames.<id>.maps` (`{id, world}`) via
  `MinigameMode#chooseConfiguredMapTemplate` (`MinigameMode.java:203-221`); TntWar's `worldTemplate()`
  rotates its own `maps` list. The chosen folder is cloned by `GameManager` into the match world.

---

## Battle maps & the in-world editor

The three **team** minigames (TntWar, Combat, Gather's duel arena) share an N-team map model and an
in-world editor/region debugger, so an admin can define and *see* each team's side without memorising
position commands.

- **`BattleMode`** (`game/modes/BattleMode`, extends `MinigameMode`) — shared base the three modes
  extend. Loads the chosen map's `BattleMap` on `start`, exposes `battleMap()`, `teamRegion(idx)` and
  `teleportTeamsToZones()` (each team round-robins over its own spawns). TntWar maps Red→team 0,
  Blue→team 1.
- **Model** (`com.sexidium.core.world.map`) — `Cuboid` (normalised min/max box, generalises the old
  `BaseRegion`; `BaseTracker` now counts destruction over a `Cuboid`), `TeamZone` (colour + region
  corners + spawns), `BattleMap` (ordered `Map<Integer,TeamZone>`, `isReady()` = ≥2 zones each with a
  region + ≥1 spawn). Persisted by `BattleMapStore` to `sexidium-battlemap.yml` in the map's world
  folder; `loadOrImportTntWar` does a one-time in-memory import of a legacy `sexidium-tntwar.yml`
  (red→0, blue→1) when no battlemap file exists yet.
- **Rendering** — `RegionRenderer.outline/marker` steps points along the 12 box edges and spawn
  columns, calling the new platform primitive `WorldAdapter.spawnDust(pos, rgb, size)` (Paper
  `Particle.DUST`, NeoForge reflective `DustParticleOptions`). Colours come from `TeamColor.rgb()`.
- **Editor** (`world/map/editor`, `MapEditorService`/`MapEditSession`) — `/sx admin map edit <mode> <mapId>`
  (admin-gated) **clones the real map** via `WorldLeaseService.acquireOrCreateClone` (the same path a
  match uses — so the admin loads the actual built arena, not a freshly generated world), switches the
  admin to **Creative** (set after the teleport + re-applied a tick later so a cross-world move can't
  stomp it; inventory stashed and restored on exit), and runs a ~10-tick loop rendering every zone in its
  colour. The clone is disposed on exit. **Save/Confirm persists both** the `sexidium-battlemap.yml`
  sidecar (team zones/spawns) **and the edited world blocks** back onto the TEMPLATE folder
  (`WorldLeaseService.saveTemplateWorld` → flush the clone + `WorldClone.copyChunkData` copies
  `region/entities/poi/data`, keeping the template's `level.dat`), so structures the admin builds in
  Creative become part of the base map every future match clones. A **seven-tool hotbar** drives editing:
  golden axe (left/right-click a block = corner 1/2 of the focused team, WorldEdit semantics), iron pick
  (strike a block to delete its box), clock (undo the last change — each edit is snapshotted), name tag
  (switch the focused team — right-click cycles, recoloured per team), ender pearl (set a spawn for the
  focused team at your exact position + facing), lime dye (confirm = save + exit), red dye (cancel = exit
  without saving). Slots 5–6 are left free so the admin can grab blocks and build (a normal held item is
  not break-suppressed). TNT War round-robins each team's members across that team's configured spawns
  (`spawnCursor`), so defining several spawns per team spreads players out instead of stacking them. While a tool is held the editor vetoes block breaking via
  `GameEventRouter` (`PlayerAdapter.heldItem`), so Creative stays non-destructive yet a normal held item
  still builds; tool display names ride `ItemStackData` `name` metadata (rendered by
  `PaperInventoryAdapter`). Sub-commands: `team <n>` (focus), `spawn` (add at your position), `save`,
  `list`/`worlds` (out of a session: a dynamic catalogue of the **bundled worlds**
  (`MapBundle.bundledWorldPaths`) + each mode's configured maps), `exit`. Interact and block-break events
  reach the editor through hooks in `GameEventRouter`; `exit`/quit cancels the render task and releases
  the world.

---

## Combat (`combat`)

[`CombatGame.java`](../../packages/core/src/main/java/com/sexidium/core/game/modes/minigames/CombatGame.java)
· minPlayers **2** · last-man-standing PvP.

- **Flow.** `super.start` → if `teamPlay()` `formTeams()` + `announceTeams()` → `teleportToArena()`
  (round-robin over `minigames.combat.arena.spawns`; if spawns empty + `arena.world` blank +
  `arena.generated.enabled`, `ArenaGen.ringArena` builds a flat walled platform with a spread spawn
  ring) → warmup `timerBar` (`warmup-seconds`, default 5, YELLOW) → `beginFight` flips `fighting=true`
  (only if RUNNING) → `duration-seconds` bar (default 300, RED) → `finishByHealth`
  (`CombatGame.java:28-46, 167-222`). `configuredKit()` defaults to `default` (lines 49-51).
- **Damage** (`handleDamage`, lines 62-75). While `!fighting`, all participant damage is cancelled;
  self-damage is ignored; in team play, same-team damage is cancelled (`sameTeam`); otherwise the base
  last-man-standing rule applies.
- **Win / lose.** Lethal damage → `eliminate` (`removeParticipant` + `releaseAndReset(victim)` to
  lobby + `COMBAT_ELIMINATED` + `ELIMINATION` popup + `awardKill`) → `checkForWinner`. FFA →
  `COMBAT_WIN` + `WIN` popup then `super.checkForWinner`; team play → `checkForTeamWinner` →
  `lastTeamStanding` → `awardTeamWin` (lines 78-140). **Timeout:** `finishByHealth` picks the single
  highest-health remaining player; an exact-health tie → `COMBAT_NO_WINNER` (lines 142-165).

**Config keys** (`minigames.combat.*`, `config.yml:420-436`): `kit` (`default`), `duration-seconds`
(300), `warmup-seconds` (inherited from `game:`), `arena.world` (`''`), `arena.spawns` (`[]`),
`arena.generated.{enabled(true), radius(14), floor-block(smooth_stone), wall-block(stone_bricks),
wall-height(3)}`, `players-per-team` (unset = FFA).

---

## Fugitive (`fugitive`)

[`FugitiveGame.java`](../../packages/core/src/main/java/com/sexidium/core/game/modes/minigames/FugitiveGame.java)
· minPlayers **3** · manhunt. One fugitive flees with a head start while the hunters are frozen as
spectators in a server-side cinematic, then released to chase with a tracking compass. Owns its own
respawns and pauses the match on a key-player leave.

- **Roles & objective.** One randomly chosen participant is the **fugitive**; everyone else is a
  **hunter**. There is a single win path: the fugitive **survives the hunt timer**. (The legacy
  `END`/`NETHER`/`DRAGON`/`SURVIVE` objective arg and `default-objective` key were removed; `start`
  ignores any launch args.)
- **World & entry spawn.** `worldTemplate()` = `chooseConfiguredMapTemplate()`: a freshly generated
  world by default; an operator may list `minigames.fugitive.maps` to clone a hand-built map.
  `handlesOwnRespawn()=true` so a death stays in the match world (never ejected to the lobby until the
  match ends). `entrySpawn` runs a deterministic outward ring scan (`landSpawn`/`ringPoints`/
  `isLandSurface`, FugitiveGame.java:78-145) for a dry-land surface near origin — avoiding the
  underwater fixed `(0,64,0)` temp-world spawn — and falls back to `safeSpawnPosition()`.
- **Flow.** `start` picks a random fugitive and records `fugitiveOrigin` — the **resolved match-world
  entry spawn** (`cachedStartSpawn`), *not* the fugitive's live `position()`, because the entry teleport
  is async (`teleportAsync`) and the live position at `start` is still the pre-teleport lobby spot; using
  it would later strand released hunters in the lobby. Then it equips the fugitive (`equipSoon`
  → `fugitive-kit`), `assignRoles()` (which shows the "Fugitive"/"Hunter" role title **once**), then
  `holdHuntersInCinematic()` puts every hunter into SPECTATOR aimed at the fugitive (gated on
  `cinematic.enabled`). `enterCinematic` sets a normal free-cam fly speed (`setFlySpeed` 0.1) and only
  (re-)applies SPECTATOR when the hunter isn't already in it, so the 2s `cinematicHint` safety net no
  longer resets the camera speed nor re-flashes the role title. If the online roster < 2 → `FUGITIVE_NO_HUNTERS`
  + end. Head-start `timerBar` (`head-start-seconds`, default **150** = 2:30, GREEN) → `releaseHunt` (or
  immediate release when 0). A 1s `trackFugitive` timer aims each hunter's compass at the fugitive's
  live position (the compass is the only tracker — there is no on-screen distance text); an optional 2s
  `cinematicHint` timer re-asserts the cinematic when `cinematic.enabled` (FugitiveGame.java:148-185).
- **Release.** `releaseHunt`: per hunter `prepareSurvival` + `clearPotionEffects`, optional
  `clearInventory` (`clear-hunter-inventory`, default true), teleport to start (`hunter-start`:
  `fugitive-origin` | `world-spawn`), then `equipSoon` → tracking compass (`give-tracking-compass`) +
  `hunter-kit` + a Spear Dash feather. Then the hunt `timerBar` (`hunt-seconds`, default **1800** =
  30 min, RED) whose expiry = `fugitiveSurvived` (FugitiveGame.java:375-401).
- **Kits.** Equipping is deferred two ticks after the entry/respawn teleport (`equipSoon`,
  FugitiveGame.java) so the kit write can't be dropped by the same-tick teleport/gamemode resync. The
  **fugitive** kit is iron sword (slot 0) + iron pickaxe (1) + 64 cobblestone (2) + 3 ender pearls (3) +
  iron helmet & leggings; the **hunter** kit is a golden sword (0) + golden pickaxe (1) + golden helmet
  & leggings. Both are defined under the top-level `kits:` block (an item's optional `slot` field pins it
  to an exact inventory index — honoured by `PaperKitAdapter`) and resolved by name through the
  `KitAdapter` (`giveKit`). On top of the kit the game places the hunter's tracking compass in slot 2 and
  a feather **Spear Dash** in slot 8 for everyone. The fugitive's inventory is wiped before kitting (the
  fugitive never passes through `releaseHunt`'s clear), so the kit always lands clean at its slots.
- **Win / lose** (guarded by a `decided` flag). `fugitiveSurvived` — the hunt timer expires, or `stop()`
  fires while undecided → awards the fugitive (`FUGITIVE_SURVIVED` / `FUGITIVE_WIN_TITLE`). `huntersWin`
  — a lethal hit on the fugitive **after** release, or an actual fugitive respawn while the hunt is on →
  awards every remaining hunter. Either path calls `requestEnd()`, which tears the match down →
  `stop()` → `releaseAndReset` per player (resets statuses, closes boss bars, returns to the lobby).
- **Damage & respawn** (`handleDamage`, FugitiveGame.java:215-246). Hunter-vs-hunter friendly fire is
  cancelled unless `friendly-fire` (default false); all lethal damage is cancelled. Lethal on the
  fugitive *before* release → `heal` (cannot die during the head start); *after* release → `huntersWin`.
  Lethal on a hunter → `heal` + `respawnHunter` (teleport to start + restore UI) + `awardKill` —
  **hunters respawn an unlimited number of times**. A non-combat fugitive death routes through
  `onFugitiveRespawn` (FugitiveGame.java:496-506): `huntersWin` if released, else heal + return to
  `fugitiveOrigin`.
- **Dash.** Using the **Spear Dash** feather propels the player forward (`dash-strength`) — via
  `PlayerAdapter.launch` (an absolute velocity set, so it works mid-air, not only off the ground, unlike
  the additive `setVelocity`). It triggers on either a **right-click (use)** or a **left-click / attack
  swing** with the feather (`usedDashItem` resolves the item from the interact event or, for the reliable
  arm-swing signal that carries none, the held item), so it fires reliably in the air. Restricted to the
  fugitive during the head start, available to both sides once released. After a dash the item shows the
  native item-cooldown sweep for `dash-cooldown-seconds` (via `PlayerAdapter.setItemCooldown`); a
  too-early click messages the remaining time (suppressed for the same-tick interact+swing duplicate).
- **Reconnect.** `isReconnectable()=true`. `onParticipantDisconnect` pauses (STANDBY) if the fugitive
  leaves; `onParticipantRejoin` resumes. `writeModeData`/`restoreModeData` persist `fugitiveId`,
  `huntReleased`, `decided`, deadlines, and origin (the objective is no longer persisted).

**Config keys** (`minigames.fugitive.*`, `config.yml:462-530`): `head-start-seconds` (150),
`hunt-seconds` (1800), `maps` (`[]`), `fugitive-kit` (`fugitive`), `hunter-kit` (`hunter`),
`clear-hunter-inventory` (true), `give-tracking-compass` (true), `hunter-start` (`fugitive-origin`),
`dash-cooldown-seconds` (8), `dash-strength` (2.5), `cinematic.enabled` (true) plus a `cinematic.*`
tuning block, `friendly-fire` (inherited). The `fugitive` and `hunter` kit item lists live under the
top-level `kits:` block (`config.yml:877-895`).

---

## Gather (`gather`)

[`GatherGame.java`](../../packages/core/src/main/java/com/sexidium/core/game/modes/minigames/GatherGame.java)
· minPlayers **2** · two phases: **GATHER** then **DUEL**.

- **Flow.** `super.start` → if `teamPlay()` `formTeams()` + `announceTeams()` → give starter-kit →
  `setupBorder()` centres a world border on world spawn (or configured center; size default 500) →
  `gather-seconds` bar (default 3600, GREEN) → `beginDuel` (lines 42-59).
- **GATHER damage** (`handleDamage`, lines 72-96). Teammates never damage each other (`sameTeam`);
  during gather, participant-vs-participant PvP is cancelled unless `pvp-during-gather` (default false).
- **DUEL.** `beginDuel` sets `dueling`, reads `duel.format`. `oneVsOne` is true **only** when
  `!teamPlay() && format==ONE_VS_ONE` — with teams the 1v1 ladder is replaced by a team FFA (everyone
  fights, last team standing) — lines 181-210. Optional heal (`duel.heal`, default true) re-prepares
  everyone. `ensureDuelArena` builds a generated duel platform when no duel arena is configured
  (lines 408-448).
  - **FFA duel:** teleport all to `duel.arena.spawns` (or generated ring) → warmup → fight →
    `duel.duration-seconds` (300) → `finishDuelByHealth` (single highest-health, tie → no winner) or
    `finishDuelByTeamHealth` (surviving team with most total health) — lines 283-350.
  - **ONE_VS_ONE:** queue everyone; `nextMatch()` pairs the first two (`activeDuel`), teleports them,
    warmup + fight; `finishMatchByHealth` eliminates the lower-health duelist on timeout (no tie
    handling — arbitrary first-max); winner re-queued; loop until queue ≤ 1, then `checkForWinner`
    (lines 212-281).
- **Win / lose during a duel.** Lethal damage → `eliminate` (`removeParticipant`, remove from
  `activeDuel`, `releaseAndReset` to lobby, `GATHER_ELIMINATED`, `awardKill`, re-queue attacker in
  1v1) then `nextMatch` (1v1) or `checkForWinner` (FFA). `checkForWinner` announces "last standing"
  (FFA) / "tournament champion" (1v1); team play → `checkForTeamWinner` + `awardTeamWin`
  (lines 99-170). `stop()` resets the world border before `super.stop` (lines 173-179).

**Config keys** (`minigames.gather.*`, `config.yml:300-336`): `gather-seconds` (3600),
`pvp-during-gather` (false), `starter-kit` (inherited), `border.{use-world-spawn(true), center-x(0),
center-z(0), size(500), warning-distance(15), damage-per-block(0.2)}`, `duel.{format(FFA|ONE_VS_ONE),
heal(true), warmup-seconds(inherited), duration-seconds(300), arena.{world(''), spawns([]),
generated.{enabled(true), radius(12), floor-block(smooth_stone), wall-block(stone_bricks),
wall-height(3)}}}`, `players-per-team` (unset = FFA).

---

## Race (`race`)

[`RaceGame.java`](../../packages/core/src/main/java/com/sexidium/core/game/modes/minigames/RaceGame.java)
· minPlayers **1** · scavenger race with item + structure objectives and overtime votes. Round
generation: [`race/RaceCatalog.java`](../../packages/core/src/main/java/com/sexidium/core/game/modes/minigames/race/RaceCatalog.java)
(tier pools + mix rule) and `race/RaceObjective.java` (item OR structure goal).

> **Race is not an elimination mode.** `handleDamage` is an empty override (lines 145-147), so a death
> never removes a player or ends the match; `handlesOwnRespawn()=true` (lines 151-154) and
> `respawnInArena` returns a dead racer to the arena spawn + `restorePlayerUi` so they keep racing
> until the timer (lines 156-163).

- **World.** `worldTemplate()` = `chooseConfiguredMapTemplate()`: generated by default; operator may
  list `minigames.race.maps` to clone a hand-built map (lines 62-65). `usesTeamSidebar()=false`
  (lines 69-72) — Race owns its objectives scoreboard.
- **Objective.** Be the first player/team to clear all **three** objectives. The tier mix is rolled by
  `RaceCatalog.rollDifficultyMix` from 6 combos, none with two hard targets (`RaceCatalog.java:43-57`).
  Easy defaults to every common ore drop (13 items: coal, raw/ingot iron, raw/ingot copper, raw/ingot
  gold, redstone, lapis_lazuli, quartz, diamond, emerald, amethyst_shard); medium/hard add tougher
  items and, with `structures.chance`, structure objectives (`RaceCatalog.java:21-37`).
- **Item collection.** `scanInventory` (from `InventoryChangeGameEvent`) credits when
  `inventory.count(itemKey) >= amount`; `markItem` (from `BlockBreakGameEvent`) matches a broken
  block's key against item objectives (lines 180-199). `markItem` matches **item** keys directly, so
  for ore→ingot targets (e.g. `diamond_ore` block vs `diamond` item) it does not fire — those rely on
  the inventory scan; an easy-pool item is only block-break-creditable if its block key equals its
  item key.
- **Structures.** `placeStructures` drops each structure objective at
  `structures.min-radius`..`max-radius` from spawn (defaults **200/900**) on the actual terrain
  surface (`highestSolidBlockY`, lines 226-237), builds a visible marker tower (`structures.marker-block`,
  default sea_lantern, height 12), and announces coordinates; a 1s `checkStructureZones` credits the
  first (team)member within `structures.zone-radius` (default 10), same-world only (lines 207-279).
- **No border.** `remove-world-border` (default true) resets the match world's border at start so
  players roam to far structures, the Nether/End, and can kill the Ender Dragon as a hard objective
  (lines 95-100).
- **Teams.** `formTeams` + `assignTeamLeaders` picks a **random** leader per team; scores/completions
  are keyed by score group (`team:N` or `player:<uuid>`); a teammate clearing an objective credits the
  whole team. In-match commands: `/sx race allow on|off` (leader toggles switch-into-team),
  `/sx race switch <team>` (live switch if target leader allows), `/sx race vote yes|no` (overtime) —
  lines 40-55, 372-379, 413-472.
- **Overtime vote.** `onTimeExpired` starts a vote unless disabled or `max-extensions` reached;
  players vote within `overtime.vote-seconds` (default 300); a quorum with yes-majority extends by
  `overtime.extension-seconds` (default 3600) and re-arms the timer, else `awardTimeout` awards the
  untied score leader (score > 0 and untied) — lines 322-409.

**Config keys** (`minigames.race.*`, `config.yml:233-294`): `maps` (`[]`),
`targets.{easy|medium|difficult}.{amount(1), points(1/3/5), items([])}`,
`structures.{enabled(true), chance(0.5), min-radius(200), max-radius(900), zone-radius(10),
marker-block(sea_lantern), marker-height(12), medium([]), difficult([])}`,
`display.{enabled(true), refresh-ticks(10)}`, `clear-inventory(true)`, `starter-kit` (inherited),
`remove-world-border(true)`, `duration-seconds(3600)`, `timeout-awards-leading-player(true)`,
`overtime.{enabled(true), vote-seconds(300), extension-seconds(3600), max-extensions(24)}`,
`teams.allow-switch-by-default(false)`.

---

## TntWar (`tntwar`)

[`TntWarGame.java`](../../packages/core/src/main/java/com/sexidium/core/game/modes/minigames/TntWarGame.java)
· minPlayers **2** · Red vs Blue base-destruction war on a cloned (or procedurally generated) map.
Supporting types in
[`tntwar/`](../../packages/core/src/main/java/com/sexidium/core/game/modes/minigames/tntwar): `TntWarConfig`,
`TntWarMap` + `TntWarMapStore`, `BaseRegion`, `BaseTracker`.

- **Teams.** TntWar uses its **own** hard-coded RED/BLUE string teams (`teamOf` map), **not** the
  shared `Teams` class; `usesTeamSidebar()=false` (lines 58-100). `assignTeams` honours `assign:` args
  from a lobby, else round-robins Red/Blue (lines 518-546).
- **Map.** `worldTemplate()` returns the chosen map's world folder (`config.chooseMap` rotates via a
  static `AtomicInteger` across matches) so `GameManager` clones it; if no ready map and
  `generated.enabled`, `generateMap()` builds two symmetric bases via `ArenaGen.teamBases` into the
  leased world (lines 88-105, 600-645).
- **Flow.** `super.start` → `loadMapDefinition` → `assignTeams` → `giveLoadoutAll` (clear inventory;
  slot 0 = `tnt-amount` TNT, slot 1 = flint_and_steel if `give-flint-and-steel`, slot 8 =
  `build-menu-item`) → `teleportTeams` → `createDestructionBar` → refresh timer (`refresh-ticks`) →
  optional `restockDispensers` timer (40t, when `lock-tnt-dispensers`) → warmup bar → `beginFight` →
  `match-seconds` bar → `onTimeUp` (lines 107-134, 548-563). Base trackers build lazily once arena +
  bases resolve (`ensureTrackers`, lines 238-257).
- **Win / lose** (`checkOutcome`, lines 273-296). A team loses when its lives hit 0 **or** its base
  destruction ≥ `win-destruction-percent` (default 75). If both fall on one tick, the team that
  wrecked more of the enemy base wins, else draw. `onTimeUp`: more-destroyed-enemy-base wins, else
  draw (lines 298-311).
- **Lives / respawn** (`onDeath`, lines 185-217). A lethal hit (`handleDamage`) or a void fall
  (`PlayerMove` `toPosition.y <= void-level`) decrements the victim's team lives, credits the killer
  (kills + killstreak + `awardWin` via `awardKill`), and respawns at team spawn while lives remain; at
  0 lives the player is removed + `releaseAndReset` to lobby for good.
- **Build palette + infinite items** (lines 336-429). Slot 8 chest opens a build GUI of every
  `build-palette` item (`openBuildMenu`); palette blocks are refunded on place when `infinite-blocks`
  (`handlePlace` + `refundLater`); SINGLE_STACK items are handed 1 at a time, others 64.
- **TNT + dispensers** (lines 336-412). Any `custom-tnt-ids` item auto-primes on place
  (`throwPrimedTnt`, throw-and-run); a placed dispenser self-stocks via `world.fillDispenserWithTnt`
  and, with `lock-tnt-dispensers`, its interact is cancelled; `restockDispensers` tops up tracked
  dispensers and drops destroyed ones.
- **HUD** (`renderHud`, lines 448-472). A per-player right panel shows Lives (Red/Blue), Bases
  destroyed (Red/Blue %), and Stats (Wins, Kills, Killstreak, Points) — Wins + Points are the global,
  Discord-aggregated figures via `gameContext.ranks().lookup(name)`. A boss bar shows both
  base-destruction percentages live (lines 474-492).
- **Reconnect.** `writeModeData`/`restoreModeData` persist `redLives`, `blueLives`, fighting,
  `mapId`/`mapWorld`, and the team roster; trackers + bars rebuild on restore (lines 670-717).
  Reconnect is active via the inherited `BaseTimedGame#isReconnectable` default (`reconnect.enabled`,
  default true).

**Config keys** (`minigames.tntwar.*`, `config.yml:345-415`): `match-seconds` (600), `void-level`
(0), `lives-per-team` (20), `win-destruction-percent` (75, clamped 1-100), `refresh-ticks` (20,
min 5), `max-scan-blocks` (40000), `tnt-amount` (64), `give-flint-and-steel` (true), `auto-prime`
(true), `prime-fuse-ticks` (40), `custom-tnt-ids` (`[minecraft:tnt]`), `build-menu-item`
(`minecraft:chest`), `infinite-blocks` (true), `dispenser-id` (`minecraft:dispenser`),
`lock-tnt-dispensers` (true), `build-palette` (26 defaults), `maps` (3 bundled maps, see below),
`generated.{enabled(true), separation(80), base-width(9), base-height(5), base-block(bricks),
platform-block(stone)}`, `warmup-seconds` + `friendly-fire` (inherited). Per-map base corners/spawns
live in each map's world folder, **not** `config.yml`: the legacy terse `/sx admin map tntwar` flow writes
`sexidium-tntwar.yml`, while the in-world editor (`/sx admin map edit tntwar <mapId>`, see
[Battle maps](#battle-maps--the-in-world-editor)) writes the shared `sexidium-battlemap.yml` and
imports an existing `sexidium-tntwar.yml` once.

**Bundled maps.** Three TNT War map worlds ship inside the jar (sources in `assets/worlds/tntwars/`,
listed in `minigames.tntwar.maps` as `tntwar/{tnt-wars,plains-tnt-wars-v2,summer-tntwars}`). The build
stages them (`prepareMapBundle` → `bundled/maps/<world>.zip` + a `manifest.txt` line
`<world-path> <sha256-of-source-zip>`, root `build.gradle.kts`) and on Paper startup `MapBundle` extracts
each into `<world-root>/tntwar/<id>`, gated by `worlds.map-bundle.extract-if-missing` (default true).

A map already on disk is **kept in sync with the bundle** (`worlds.map-bundle.refresh-when-changed`,
default true): the digest is stamped into the extracted folder as `.sexidium-map-bundle`, so a boot whose
manifest digest is unchanged touches nothing — in-game edits and `sexidium-tntwar.yml` survive — while a
map re-exported into `assets/worlds/tntwars/` replaces the old copy on the next start, moving it aside as
`<id>.replaced-<timestamp>` (newest kept, older pruned). A folder with no stamp is *adopted* — stamped and
left alone — so upgrading the jar never wipes maps seeded by an older build. They ship
without a `sexidium-tntwar.yml`, so define each map's Red/Blue bases + spawns via `/sx admin map tntwar` or
`/sx admin map edit tntwar <id>` before they play with proper sides. (NeoForge auto-extract is not yet wired.)

---

## Known issues

Verified against source; documented for awareness.

- **Mid-match joiners are not integrated into mode state.** `joinInProgress` calls only
  `BaseTimedGame.onParticipantAdded` (`addParticipant` + `prepareSurvival` + `giveKit`,
  `BaseTimedGame.java:71-79`); no mode overrides it. A joiner gets no TntWar team (`team()==""` can
  corrupt outcome detection), no Fugitive role, no team assignment in Combat/Gather/Race, and no arena
  placement.
- **Race block-break collection is inert for ore→ingot targets.** `markItem` matches item keys
  directly, and a broken ore block's key never equals its ingot item key — those rely solely on the
  inventory scan (`RaceGame.java:192-199`).
- **Health-tie edge cases.** `CombatGame.finishByHealth` and `GatherGame.finishDuelByHealth` declare
  an exact tie as no-winner, whereas `GatherGame.finishMatchByHealth` (1v1) takes the first max-health
  player without tie handling.
- **No pre-match state preserved.** `releaseAndReset` → `resetStatuses` returns players to a fixed
  lobby baseline — data loss if started in a survival world.

---

## Keeping this current

Authoritative sources (code is the source of truth; this doc is a derived view):
[`CoreGameRegistryInitializer`](../../packages/core/src/main/java/com/sexidium/core/game/CoreGameRegistryInitializer.java)
(ids/aliases/minPlayers/display names),
[`MinigameMode`](../../packages/core/src/main/java/com/sexidium/core/game/modes/MinigameMode.java) +
[`BaseTimedGame`](../../packages/core/src/main/java/com/sexidium/core/game/modes/BaseTimedGame.java)
(shared base + team system), the five `…/modes/minigames/*Game.java` files plus their `race/`,
`tntwar/`, and `team/` support packages, and the `minigames.*` / `game.*` blocks in
[`config.yml`](../../packages/core/src/main/resources/config.yml). Update **this doc in the same change**
that touches those files. Triggers: a new minigame class or support type added to the domain; any
change to a mode's id/alias/minPlayers/display name, phase flow, win detection, team integration, or
reconnect behaviour; or a `minigames.<id>.*` config key added/removed/renamed.
