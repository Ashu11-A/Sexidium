# Base prompt: adding or modifying a Minigame

You are working on Sexidium's **competitive minigames** (Race, Gather & Duel, TNT War, Combat, Fugitive).
A minigame is a timed, match-scoped `Game` with a winner — unlike experiences, it eliminates, ends, and
tears its world down. Reference behaviour: [minigames.md](minigames.md) and
[game-framework.md](game-framework.md). Everything is platform-agnostic core code; platform capabilities
go through the SPI seams ([Prompt.platform.md](Prompt.platform.md)).

## Key files

| Concern | File(s) |
|---|---|
| Mode implementations | `packages/core/src/main/java/com/sexidium/core/game/modes/minigames/<Name>Game.java` (+ per-mode support packages `race/`, `tntwar/`) |
| Base classes | `…/game/AbstractGame.java` → `…/game/modes/BaseTimedGame.java` (start loop, duration, kit, reconnect) → `MinigameMode.java` (teams, mode args, `minigames.<id>` config prefix) → `BattleMode.java` (pre-built team maps) |
| Registration | `…/game/CoreGameRegistryInitializer.java` (`registerMinigames`), `GameRegistry.java`, `GameModeDescriptor.java` |
| Match engine | `…/game/GameManager.java`, `GameLauncher.java`, `MatchLifecycle.java`, `GameEventRouter.java`, `GameEvents.java` |
| Team battle maps | `…/core/world/map/` — `BattleMap`, `BattleMapStore`, `TeamZone`, `Cuboid`, `SpawnPoints`; in-world editor `…/world/map/editor/MapEditorService.java` (`/sx admin map edit`) |
| Bundled map worlds | `assets/worlds/**` → gradle `prepareMapBundle` → `…/core/world/MapBundle.java` (auto-extract; digest-stamped, so a re-exported map replaces the old copy and an unchanged one never clobbers) |
| Scoreboard HUD | `…/game/hud/GameHud.java`, `HudContributor.java`, `HudContext.java` |
| Chest GUI | `…/core/menu/MinigameMenu.java` (mode grid + per-mode detail + lobby browser) — driven by descriptors, no layout code per mode |
| Menu icons | `…/core/menu/MenuArt.java` (`MODE_ICON_IDS`), `MenuArtIcons.java` (`MODE_ICON_MODELS`) |
| Lobby / matchmaking | `…/core/world/lobby/` (`LobbyManager`, quick-play queue, `ModeResolver` min-players) |
| Config | `config.yml` → `minigames.<id>:` (per-mode) + shared `game.*` keys |
| Per-mode admin commands | `…/core/command/` (`TntWarCommands`, `CombatCommands`, `RaceCommands` are the pattern) |
| i18n | `…/core/i18n/MessageKey.java` + `lang/en.properties` / `pt.properties` (**both**) |

## How to add a minigame

1. **Create** `modes/minigames/<Name>Game.java`. Choose the base:
   - `MinigameMode` — timed round, optional teams (`teams` field, `formTeams()`), fresh generated temp world.
   - `BattleMode` — additionally runs on a **pre-built map**: it resolves a `ConfiguredMap` (override
     `chooseBattleMap()` for a custom rotation), supplies `worldTemplate()` so the launcher **clones** the
     template world, and loads the `BattleMap` regions/spawns (TNT War, Combat, Gather are the models).
2. **Register** in `CoreGameRegistryInitializer.registerMinigames`:
   ```java
   registry.register(new GameModeDescriptor(
       "<id>", CATEGORY_MINIGAMES, "<Display>", 2,          // minPlayers must be >= 2
       List.of("<alias>", …), "<vanilla_icon_item>",
       List.of("<gray>One-line description.</gray>")),
       (ctx, id, args) -> new <Name>Game(ctx, args));
   ```
   The descriptor is the single source for menus, holograms, lobby minimums and `/sx start` — there is no
   second place to declare the mode.
3. **Icon**: add the id to `MenuArt.MODE_ICON_IDS` + a mapping in `MenuArtIcons.MODE_ICON_MODELS`.
   `MenuArtCoverageTest` fails the build on drift. The Minigames grid, per-mode detail screen, quick-play
   and the lobby browser all pick the mode up automatically from the registry.
4. **Config**: add a `minigames.<id>:` block. `configPath("key")` resolves to `minigames.<id>.key`
   (`MinigameMode.configPrefix()`); `duration-seconds` and `kit` are inherited `BaseTimedGame` keys.
5. **Document**: [minigames.md](minigames.md) (phases, win detection, config) and
   [commands.md](commands.md) if you add admin subcommands.

## Match lifecycle rules

- `start(participants)` is provided by `BaseTimedGame` (per-player `startParticipant`, announce, duration
  timer). Override `startParticipant`/`onStart`-style hooks rather than replacing `start`.
- **Worlds**: no `worldTemplate()` → the launcher hands you a pooled/fresh disposable temp world;
  a `worldTemplate()` → a clone of that template. Never create worlds yourself.
- **Winning**: call `awardWin(player)` (or the team variant — see `RaceGame.awardTeamWin`) then
  `requestEnd()`. Points also flow from `awardParticipation`/`awardKill`. Timeouts: `endSoon(ticks)` /
  `durationSeconds()`.
- **Events** arrive translated via `handle(GameEvent)` / the router — pattern-match `GameEvents.*` types.
  Need a new signal? Extend `GameEvents` + `GameEventRouter` + the platform bridge
  (`packages/module-paper/...adapter/event/PaperEventBridge.java`) in one change.
- **Respawns**: if the mode manages its own (arena respawn instead of lobby release), override
  `handlesOwnRespawn()` and handle `PlayerRespawnGameEvent` — you may set
  `event.setRespawnPosition(...)` to redirect the platform placement (teleporting during the event does
  **not** stick).
- **Reconnect**: `BaseTimedGame` supports it (`reconnect.enabled`); think about what a rejoining player
  must be restored to.

## Scoreboard (right-hand panel)

Install the single per-player panel in your start path:
`installHud(text(MessageKey.MY_TITLE), refreshTicks, sharedSection, new MyHud())` where `MyHud implements
HudContributor` and writes lines into `HudContext` (`context.line(...)`, `context.compact()` for the
trimmed view, `context.debug…` for the gated debug block). Show it per player with `hud().show(player)`;
force a repaint with `hud().render()`. `RaceGame.RaceHud` is the reference. No standalone boss bars.

## Team map workflow (BattleMode only)

Maps are authored **in-world**: `/sx admin map edit <mode> <mapId>` opens `MapEditorService` (golden-axe
corners, spawn capture, particle wireframe) and saves through `BattleMapStore`. Bundled starter maps ship
from `assets/worlds/**` (gradle `prepareMapBundle` stages them; `MapBundle` extracts them, Paper-only, and
re-extracts one whose source zip changed — an unchanged bundle never overwrites an edited copy). A new
BattleMode needs: its store wiring, at least one
shipped or documented map, and an `/sx admin map <mode>` command branch (copy `CombatCommands`).

`/sx admin map edit` defines the *regions* (team zones, spawns) — it does not build the arena. For that,
`scripts/init-paper.sh` installs FastAsyncWorldEdit and Axiom on the test server and
`scripts/install-world-tools.sh` installs the client/desktop half; see the tooling table in
[Prompt.worlds.md](Prompt.worlds.md#editing-a-map-tooling).

## Checklist before you finish

- [ ] Descriptor registered; `minPlayers >= 2`; aliases don't collide (`GameRegistry`)
- [ ] `MenuArt.MODE_ICON_IDS` + `MODE_ICON_MODELS` updated (`MenuArtCoverageTest` green)
- [ ] `minigames.<id>:` config block added; keys via `configPath(...)`
- [ ] Win/kill/participation awards + `requestEnd()` wired; timeout path exists
- [ ] HUD contributor installed via `installHud`
- [ ] i18n keys in **both** `en.properties` and `pt.properties`
- [ ] [minigames.md](minigames.md) + [commands.md](commands.md) updated in the same change
- [ ] `./gradlew clean build` green, and `scripts/remote.sh test gradle scripts` green on the
      deployment host ([deployment.md §8](deployment.md#8-running-tests-remotely))

---
*Keeping this current: this prompt tracks `CoreGameRegistryInitializer`, the `modes/` base-class chain,
`world/map/`, `MinigameMenu`, `MenuArt(Icons)` and the `minigames.*` config namespace. Update it in the
same change that alters any of those workflows.*
