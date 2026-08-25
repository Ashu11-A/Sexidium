# Platform SPI & Adapters

The platform-agnostic engine in `com.sexidium.core` never imports a Bukkit, Adventure, or Minecraft/NeoForge class. It talks only to the **service-provider interface (SPI)** in `com.sexidium.core.platform` (interfaces + handles) and `com.sexidium.core.platform.model` (immutable value records/enums), with side-effect-free fallbacks in `com.sexidium.core.platform.noop`. Each adapter module — **Paper** (`com.sexidium.paper`, `packages/module-paper`) and **NeoForge** (`com.sexidium.neoforge`, `packages/module-neoforge`) — implements every interface and hands the engine one root object, a [`ServerAdapter`](../../packages/core/src/main/java/com/sexidium/core/platform/ServerAdapter.java). This doc is Part 1 the SPI contract, Part 2 the Paper internals, Part 3 the NeoForge internals + a parity table. Gameplay, matchmaking, social, persistence, and command logic all live in core (see [architecture](overview.md)); the adapters are thin translation layers.

---

## Part 1 — The SPI

### 1.1 Wiring

The adapter builds one `ServerAdapter` plus a `KitAdapter`/`RankAwardPort` and passes them into `SexidiumCore`, which constructs a single [`GameContext`](../../packages/core/src/main/java/com/sexidium/core/game/GameContext.java). A game mode reaches the platform only through that context: `gameContext.server()` → `ServerAdapter`, `gameContext.kits()` → `KitAdapter`, `gameContext.games()` → `GameManager` (`gameContext.ranks()` is the ranking port, not part of `platform/`).

Three mechanisms keep core platform-agnostic:

1. **Plain types only.** Interfaces expose JDK types (`UUID`, `Path`, `Optional`, …) and the `platform/model` records. World identity always crosses the boundary as a `String` name inside `WorldPosition`/`BlockPosition` — never a live world handle. Core resolves worlds by name via `WorldLeaseService` and `WorldAdapter.name()`.
2. **Optional capabilities are Java `default` methods.** A platform that cannot do something inherits a no-op/inert default rather than failing to compile. Convenient, but a correctness trap: a partially-implemented adapter lets gameplay calls vanish silently (see [§3.5 parity gaps](#35-parity-gaps-vs-paper)).
3. **`noop` fallbacks.** `platform/noop` provides inert (or real-but-headless) impls so the engine boots without any real platform.

### 1.2 `ServerAdapter` — the root aggregate

[`ServerAdapter.java`](../../packages/core/src/main/java/com/sexidium/core/platform/ServerAdapter.java). The single object every platform capability is reached through.

| Method | Returns | Notes |
| --- | --- | --- |
| `serverName()` / `platformType()` / `dataDirectory()` | `String` / `PlatformType` / `Path` | Brand, platform family, plugin/mod data dir. |
| `configuration()` | `ConfigurationAdapter` | Active config. |
| `logger()` / `resources()` | `LoggerAdapter` / `ResourceAdapter` | Log sink; bundled-asset reader. |
| `scheduler()` | `SchedulerAdapter` | Tick/async scheduling. |
| `ui()` / `messages()` | `UiAdapter` / `MessageAdapter` | Boss bars/HUD/popups; localized + raw chat. |
| `events()` / `commands()` | `EventDispatcherAdapter` / `CommandDispatcherAdapter` | Game register bridge (no-op); console dispatch. |
| `worlds()` | `WorldLeaseService` | Lobby + temp + persistent-experience world lifecycle. |
| `console()` | `CommandSource` | Console as a command source. |
| `onlinePlayers()` / `player(UUID)` / `playerExact(String)` | players | Online lookups. |

`ServerAdapter` carries **six** `default` getters that both adapters override (an empty/NOOP default keeps headless wiring valid), `ServerAdapter.java:50-81`:

| Default getter | Default | Paper backend | NeoForge backend |
| --- | --- | --- | --- |
| `itemTranslationKey(ItemKey)` | `""` (→ plain server-side name) | `Material.translationKey()` | reflective registry lookup |
| `inventorySerializer()` | `InventorySerializer.NOOP` | `PaperInventorySerializer` | `NeoForgeInventorySerializer` |
| `menus()` | `MenuAdapter.NOOP` | `PaperMenuAdapter` (InvUI-style chest GUI) | `NeoForgeMenuAdapter` (vanilla container) |
| `npcs()` | `NpcAdapter.NOOP` | `PaperNpcAdapter` (FancyNpcs/FancyHolograms) | `NeoForgeNpcAdapter` (native) |
| `decor()` | `DecorAdapter.NOOP` | `PaperDecorAdapter` (native display entities) | **inherits NOOP** (no decor backend) |
| `rankTags()` | `RankTagAdapter.NOOP` | `PaperRankTagAdapter` (scoreboard Team) | `NeoForgeRankTagAdapter` (`PlayerTeam`) |

> `inventorySerializer()`/`menus()`/`npcs()`/`decor()`/`rankTags()` are new sibling adapters added since the old docs were written; the catalog above supersedes them. Decor is additive Java flair (item/block displays are invisible on Bedrock, see [menu system & art](../interface/menus.md)), so NeoForge deliberately leaves it on the inert default.

### 1.3 Sibling service interfaces

- **`ConfigurationAdapter`** — typed config with defaults: `getBoolean/Int/Long/Double/String(path, default)`, `getStringList(path)`, `getMapList(path)` (arena spawns, team/kit lists), `keys(path)` (enumerate game ids/categories), `get/contains/set/reload/save`. Paths are dotted (`worlds.temp.pool-size`). `getMapList`/`keys` return empty (not null) on a missing/flat path.
- **`LoggerAdapter`** — `info/warning/severe`, plus `…(msg, Throwable)` overloads.
- **`ResourceAdapter`** — `openResource(String) -> Optional<InputStream>` for jar-bundled assets.
- **`SchedulerAdapter` + `ScheduledTask`** — global timing: `runNow`, `runLater(r, delayTicks)`, `runTimer(r, delay, period)` (20 ticks = 1 s), `runAsync(r)`. **Ticks are the engine's timing unit.** `ScheduledTask.cancel()` must really cancel; `AbstractGame.cleanup()` cancels every task it scheduled. **Region-scoped (Folia) variants** with default fallbacks to the global methods: `isRegionThreaded()`, `runForPlayer(player, task, retired)` / `runForPlayerLater(...)` (Folia EntityScheduler — runs on the region owning that player), `runAtRegion(world, blockX, blockZ, task)` (Folia RegionScheduler). Non-region-threaded platforms (regular Paper, NeoForge) inherit the global fallbacks unchanged.
- **`EventDispatcherAdapter`** — `registerGame`/`unregisterGame`. **Both adapters implement these as no-ops**: native events are bridged once by a single platform listener into `core.events().handle(...)`, which fans out to every active match's `game.handle(...)`. There is **no per-game event isolation at the platform layer**; a game self-filters by participant/world.
- **`CommandDispatcherAdapter`** — `dispatchFromConsole(String)` runs a line with console authority (HTTP `/command` bridge, kit-give).
- **`MessageAdapter`** — `send(...)` (engine prefix/format) vs `raw(...)`; both `LocalizedText` and MiniMessage overloads. Per-client localization comes from reading the recipient's `locale()` at render time.
- **`UiAdapter` + `BossBarHandle` + `HudPanelHandle`** — `createBossBar(text, progress, color, overlay)`; `showPopup(player, type, text)` *(default falls back to a title for WIN/ELIMINATION/OBJECTIVE, else action bar)*; `createPanel(title)` *(default → `HudPanelHandle.NOOP`)*. Handles are **per-match overlays** with per-viewer `show(player)`/`hide(player)` + `close()`. This is what lets `AbstractGame.releasePlayerUi()` hide one player's overlays while everyone else keeps theirs (critical on NeoForge, where player-level `clearBossBars()` is a no-op). `HudPanelHandle` adds `line(index, text)`, `removeLine(index)`, `refresh()` — call `refresh()` once after a batch of `line()` edits.

### 1.4 Entity / world interfaces

- **`PlayerAdapter` (extends `CommandSource`)** — the largest interface. Required reads: `uniqueId/online/dead/world/position/gameMode/health/maxHealth/foodLevel/inventory`. Required mutators: `teleport/setGameMode/setHealth/setFoodLevel/playSound/showTitle/sendActionBar/setCompassTarget/clearInventory/clearPotionEffects`. Optional `default` (inert if unimplemented): `experiencePoints/setExperiencePoints` (0/no-op), `setHealthScale/resetHealthScale`, `setVelocity`, `clearBossBars/clearTitle/resetCompass`, `duplicateLookedAtEntity` (→0; one crosshair target, Look Multiplies) and `duplicateNearbyEntities(radius, copiesPerEntity, maxSpawns, Set<DuplicableKind>)` + `supportsEntityDuplication` (→0/`false`; the untargeted bulk clone behind Jump Multiplies). The two duplication seams are deliberately separate rather than one call with a mode flag — they answer different questions, and a bulk clone must be a real NBT copy (aggro, taming, baby state, equipment, stack contents) where the crosshair one rebuilds from a type. Players are never duplicable: there is no `DuplicableKind` for them. The engine's reset contract is `resetStatuses()` (default), called on every match exit/join:

  ```java
  default void resetStatuses() {
    clearInventory(); clearPotionEffects(); resetHealthScale();
    setHealth(maxHealth()); setFoodLevel(20); setGameMode(GameModeType.SURVIVAL);
    clearBossBars(); clearTitle(); resetCompass();
  }
  ```

  Neither adapter overrides it; both implement every sub-method. Note: it **does not** reset experience points and **does not** snapshot/restore pre-match inventory/location — it wipes to a fixed lobby baseline (correct for a dedicated minigame server; data loss on a carry-inventory server).
- **`CommandSource`** — base of `PlayerAdapter` and `ServerAdapter.console()`: `name/locale/hasPermission/sendMiniMessage/sendPlainMessage`, `playerSource()` (`false`; `PlayerAdapter` → `true`). `locale()` drives per-client localization; console sources return a fixed English locale.
- **`WorldAdapter`** — per-world facade. `name()` is the **canonical world identifier** all core comparisons use; it must match the names stored in `WorldPosition`/`BlockPosition`. Reads/effects: `spawnPosition/players/dropItem/playSound/setBorder/resetBorder/loadChunk`. Optional `default` (air/empty/0 when unimplemented): `spawnTnt` (2-arg + 6-arg-with-velocity), `setBlock`, `blockTypeAt` (→`minecraft:air`), `nearbyMobs` (→empty), `countNearbyEntities(center, radius, Set<DuplicableKind>)` (→0; the live-entity ceiling behind Jump Multiplies — a count, not handles, because building a handle per entity is the cost it exists to avoid), `fullTimeTicks` (→0). Both shipping adapters override every default any game uses; the inert defaults apply only on the `noop`/headless path.
- **`WorldLeaseService` + `WorldLease`** — the lobby + a warm pool of disposable temp worlds + persistent player-owned experience worlds. See [§1.6](#16-worldleaseservice-the-control-seam) — its implementation strategy is the biggest change from the old docs.
- **`InventoryAdapter`** — `clear/contains/count`(default sums `storageContents()`)/`add/storageContents/setStorageContents/storageCapacity`(default 36)/`equipmentContents/setEquipmentContents` (slot-name keyed). Material + amount only; used for kit application, shared inventory, game-logic checks — **not** for save/restore.
- **`InventorySerializer`** — `serialize(PlayerAdapter) -> String`, `deserializeInto(PlayerAdapter, String)`. The **only** inventory snapshot/restore in the system (reconnect persistence). `InventorySerializer.NOOP` is an inert default. Fidelity differs by platform (Paper full NBT/components; NeoForge material+amount text).
- **`KitAdapter`** — `apply(player, kitName) -> boolean`, `exists`, `names`, `reload`. `AbstractGame.giveKit(...)` delegates here. `apply()` is **additive** (does not clear first). NOOP returns `false`/empty.
- **`MobHandle`** — wraps a `WorldAdapter.nearbyMobs(...)` result: `type/position/health/setHealth/setVelocity/equip/addEffect/setGlowing`. Plus `targetId()`/`clearTarget()`, used to make hostiles drop a player nobody is driving. An id rather
  than a handle because the only question asked is "is this aimed at *that* player"; resolving players
  is not a mob's job. **Paper subtlety:** de-aggro is `Mob#setTarget(null)`, never
  `EntityTargetEvent#setCancelled(true)` — cancelling means "refuse this *change* of target", which for
  a mob already chasing the player *preserves* the aggro.
- **`BedrockPlayers`** ([`BedrockPlayers.java`](../../packages/core/src/main/java/com/sexidium/core/platform/BedrockPlayers.java)) — a static utility, not an adapter interface: `isBedrockUuid(UUID)` returns true when the high 64 bits are zero (Floodgate's synthetic Bedrock UUID form). Dependency-free UX hint only (route command suggestions to a GUI affordance), never an authorization decision. See [Bedrock/mobile UX](../interface/menus.md).

### 1.5 Value model (`platform/model`)

Immutable records/enums; records validate/normalize in their canonical constructors.

| Type | Shape | Notes |
| --- | --- | --- |
| `WorldPosition` | `(String worldName, double x,y,z, float yaw,pitch)` | World identity is a **String**. Helpers `withWorldName`, `offset`. |
| `BlockPosition` | `(String worldName, int x,y,z)` | Integer block coords. |
| `ItemKey` | `(String namespace, value)` | Blank value throws; ns defaults `minecraft`, both lowercased. `qualifiedName()`=`ns:value`; `ItemKey.minecraft(value)`. |
| `ItemStackData` | `(ItemKey, int amount, Map<String,String> metadata)` | `amount`≥1; metadata copied to immutable map. |
| `SoundKey` | `(String value)` | Blank throws. |
| `TitleSpec` | `(String title, subtitle, long fadeIn,stay,fadeOut)` | Times are **milliseconds**. MiniMessage strings. |
| `WorldBorderSpec` | `(double centerX,centerZ,size, int warningDistance, double damagePerBlock)` | World border. |

Enums: `BossBarColor` (PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE), `BossBarOverlay` (PROGRESS/NOTCHED_6/10/12/20), `DamageCauseType` (ENTITY_ATTACK/PROJECTILE/EXPLOSION/FIRE/LAVA/FALL/VOID/MAGIC/STARVATION/CUSTOM/UNKNOWN), `GameModeType` (SURVIVAL/CREATIVE/ADVENTURE/SPECTATOR), `PlatformType` (BUKKIT/FABRIC/FORGE/NEOFORGE/HYBRID/UNKNOWN), `PopupType` (INFO/SUCCESS/WARNING/OBJECTIVE/COUNTDOWN/ELIMINATION/WIN), `DuplicableKind` (MOB/ITEM/PROJECTILE/TNT/BOSS — the vocabulary the two duplication seams share; BOSS is split from MOB so cloning a dragon stays an explicit opt-in, and there is no PLAYER member by design).

### 1.6 `WorldLeaseService` — the control seam

[`WorldLeaseService.java`](../../packages/core/src/main/java/com/sexidium/core/platform/WorldLeaseService.java) is the contract both world backends satisfy. Beyond the lobby + temp-world lifecycle it now also owns **persistent experience worlds** and a **runtime GC sweep**:

| Method | Contract |
| --- | --- |
| `enabled()` / `start()` / `shutdown()` | Toggle; startup (preserve lobby, clean stale, warm pool); teardown of non-preserved. |
| `acquireReady() -> Optional<WorldLease>` | Grab a pre-warmed lease (empty if none). |
| `acquireOrCreate(viewers, onReady, onFailure)` | Async create-then-callback. |
| `acquireOrCreateClone(template, viewers, onReady, onFailure)` *(default → `acquireOrCreate`)* | Copy a pre-built map folder into a fresh temp world (TNT-War arenas). |
| `acquireOrCreatePersistent(name, viewers, onReady, onFailure)` *(default fails)* | Load-or-create a player-owned experience world; lease `close()` unloads but **never deletes**. |
| `reacquireByName` / `reacquirePersistent` / `discardByName` / `deletePersistent` | Reconnect-after-restart; pending-match teardown; explicit owner delete (never automatic). |
| `preserve(Collection)` / `preserveSingle` *(default)* | Keep named worlds across shutdown. |
| `collectGarbage(inUseWorldNames) -> int` *(default 0)* | Background sweep: unload+delete every temp world not in-use, not pooled, not preserved. |
| `lobbyName/worldRoot/tempSubdir/lobbyFolder/experiencesSubdir(Name)/lobbySpawn` | Name + path + spawn resolution from config. |

`WorldLease` is `AutoCloseable`: `world()` returns the leased `WorldAdapter`; `close()` releases the world per its kind (`GameManager` calls it on match end).

> **The big change since the old docs.** `WorldLeaseService` is no longer implemented twice by hand. The Paper backend is now [`AbstractWorldControl`](../../packages/core/src/main/java/com/sexidium/core/world/AbstractWorldControl.java) (`AbstractWorldControl.java:36`), a unified core layer that owns **all** lifecycle policy — the managed/leased/preserved registries, the warm pool, the disposal decision tree, player evacuation, world classification, and the worldgen guarantees — and exposes a handful of abstract platform hooks (`runOnWorldThread`, `serverHome`, `backendAcquire`, `backendUnload`, `backendLobby`, …) for the irreducible raw operations (`AbstractWorldControl.java:59-105`). Naming/classification is centralized in [`WorldNaming`](../../packages/core/src/main/java/com/sexidium/core/world/WorldNaming.java), which addresses every managed world by `namespace:key-path` mapping to `world/dimensions/<namespace>/<key>` on MC 26.1+. The old per-platform `PaperWorldLeaseService`/`NeoForgeWorldLeaseService` naming divergences that caused the dispose-leak and double-namespace bugs are eliminated **on Paper**; NeoForge has **not** been migrated and still implements `WorldLeaseService` directly (see [§3.4](#34-world-generation--lease-lifecycle)). See [world control unification](../gameplay/lobby-worlds-and-social.md).

### 1.7 `noop` fallbacks (`platform/noop`)

Used by headless/test wiring (and as composable building blocks).

| Class | Behavior |
| --- | --- |
| `NoopUiAdapter` / `NoopBossBarHandle` | `createBossBar` → inert handle; inherits default `showPopup`/`createPanel`. |
| `NoopKitAdapter` | `apply`/`exists` → `false`; `names` → empty. |
| `NoopCommandDispatcherAdapter` / `NoopEventDispatcherAdapter` / `NoopScheduledTask` | All no-op (the event one matches the real adapters). |
| `NoopWorldLeaseService` | `enabled()` → `false`; acquire → empty; `acquireOrCreate` immediately runs `onFailure` (degrades to in-place); `lobbyName()` → `"lobby"`, `worldRoot()` → `Path.of("worlds")`. |
| `ClassLoaderResourceAdapter` / `PropertiesConfigurationAdapter` / `StdoutLoggerAdapter` | **Real** classpath loading / `.properties` config / stdout logging. |
| `DirectSchedulerAdapter` | `runNow`/`runLater` fire **synchronously immediately** (delay ignored); `runTimer` **never** fires; `runAsync` uses a real daemon pool. A known test-double timing limitation — production uses the real adapters. |

`acquireOrCreate` running `onFailure` immediately is the mechanism by which a world-disabled / headless deployment degrades cleanly: the start flow falls back to running the match in-place.

### 1.8 SPI → adapter implementation map

Both modules implement every interface. `events()`/`commands()` may be returned fresh per call (Paper does); other sub-adapters are cached fields.

| SPI | Paper | NeoForge |
| --- | --- | --- |
| `ServerAdapter` | `PaperServerAdapter` | `NeoForgeServerAdapter` |
| `ConfigurationAdapter` / `LoggerAdapter` / `ResourceAdapter` | `Paper…` | `NeoForge…` |
| `SchedulerAdapter` / `ScheduledTask` | `PaperSchedulerAdapter` / `PaperScheduledTask` | `NeoForge…` *(scheduler also `AutoCloseable`)* |
| `EventDispatcherAdapter` | `PaperEventDispatcherAdapter` *(no-op; events via `PaperEventBridge`)* | `NeoForgeEventDispatcherAdapter` *(no-op; events via `NeoForgeEventBridge`)* |
| `CommandDispatcherAdapter` / `MessageAdapter` | `Paper…` | `NeoForge…` |
| `UiAdapter` / `BossBarHandle` | `Paper…` | `NeoForge…` |
| `HudPanelHandle` | `PaperScoreboardPanelHandle` | `NeoForgeScoreboardPanelHandle` |
| `HudDriver` / `HudSurfaceHandle` | `BetterHudDriver` *(behind `BetterHudLink.available()`; else `NOOP`)*, stacked over core's `SidebarHudDriver` — `activeFor(player)` is the answer that decides a screen | `NOOP` *(core's sidebar renderer still draws every declared surface)* |
| `CommandSource` / `PlayerAdapter` | `PaperCommandSource` / `PaperPlayerAdapter` | `NeoForge…` |
| `WorldAdapter` | `PaperWorldAdapter` | `NeoForgeWorldAdapter` |
| `WorldLeaseService` | `PaperWorldControl extends AbstractWorldControl` | `NeoForgeWorldLeaseService` *(direct, not migrated)* |
| `WorldLease` | `LeasedWorld` (core) via `PaperWorldHandle` | `NeoForgeWorldLease` |
| `InventoryAdapter` / `InventorySerializer` / `KitAdapter` / `MobHandle` | `Paper…` | `NeoForge…` |
| `MenuAdapter` / `NpcAdapter` / `RankTagAdapter` | `Paper…` | `NeoForge…` |
| `DecorAdapter` | `PaperDecorAdapter` | *(NOOP — not implemented)* |

---


### `PlayerAdapter#idleMillis()`

Milliseconds since the client last did something the server had to be told about — vanilla's
last-action time, the clock behind `player-idle-timeout`. It is the one signal that means *nobody is in
control of this character*, covering lag, a frozen client, a dying connection and plain AFK at once.

`ping()` deliberately does **not** work for this: it is a rolling keep-alive average that **freezes** at
its last healthy value when a client goes silent rather than climbing, so a stalled player still reads
as a comfortable 40ms. Anything built on ping to detect a freeze detects nothing.

`-1` means "this platform cannot say", and every caller must read that as **not** downed. Treating it as
"infinitely idle" would make every player on a platform without the seam permanently unkillable — a far
worse failure than the one the seam exists to prevent.

Deliberately not exposed: `resetIdleDuration()`. Nothing has any business lying about whether somebody
is at the controls.

## Part 2 — Paper adapter (`module-paper`)

Module root `packages/module-paper/src/main/java/com/sexidium/paper/`. Target API: Paper `26.1.2` (`plugin.yml` `api-version: '26.1'`, compiled against `paper-api:26.1.2.build.74-stable`) — the oldest server the jar targets, so one artifact covers 26.1.2 and 26.2 alike, Folia-safe schedulers, Adventure/MiniMessage UI. Paper binds directly to the modern Paper/Adventure API at compile time.

### 2.1 Bootstrap (`PaperSexidiumPlugin`)

`extends JavaPlugin`. `onEnable()` constructs every adapter in dependency order (the order matters — `PaperServerAdapter` builds the world control, and the lobby must exist before core resolves `lobbySpawn()`): `saveDefaultConfig()` (the canonical `config.yml` lives in **core** `packages/core/src/main/resources/config.yml`) → `PaperConfigurationAdapter` → `provisionLobby()` (`PaperLobbyBootstrap`) → `PaperMessageAdapter` → `PaperServerAdapter` → `PaperKitAdapter` → `setupDatabase()` (`DatabaseSettings.resolve` → `database.type` sqlite/mysql/postgres, + `AuthService`; both `null` on failure → graceful degrade) → `new SexidiumCore(...)` (deps tuple `(serverAdapter, kitAdapter, gameRegistry, database, authService, authEnabledSupplier)`; registry from `PaperGameRegistryFactory.create()` → `CoreGameRegistryInitializer.create()`, **no** Paper-specific modes) → `messageAdapter.use(core.messages())` → `core.start()` → register `PaperEventBridge` (the one Bukkit `Listener`) → bind `/sexidium` (alias `sx`) to `PaperCommandBridge`.

`core.start()` reloads messages/kits, restores persisted matches, then runs `worlds().start()`, `apiServer.start()`, `botManager.start()`. **Teardown** `onDisable()` closes core (`gameManager.prepareShutdown()`, `worlds().shutdown()`, stop bot/API/repos) then the database. **Reload** `reloadSexidium()` reloads config + kit adapter + `core.reload()` — so `/sx admin reload` runs a *full plugin* reload on Paper (NeoForge passes only `core::reload`).

### 2.2 `PaperServerAdapter`

`implements ServerAdapter`. Stateless sub-adapters (`events`, `commands`, `console`, player wrappers) are constructed fresh; stateful ones are cached fields. `npcs()`/`decor()` are **lazily** created the first time requested (the optional FancyNpcs/FancyHolograms soft-depend classes are only referenced when an NPC backend is actually wanted). `platformType()` string-sniffs the brand: `mohist`/`arclight`/`magma` → `HYBRID`, else `BUKKIT`. `itemTranslationKey` resolves a `Material` by qualified then bare name and returns `Material.translationKey()` (or `""`), powering client-language `<lang:...>` item names.

### 2.3 Event bridging (`PaperEventBridge`)

The **only** Bukkit `Listener`. `PaperEventDispatcherAdapter.registerGame`/`unregisterGame` are deliberate no-ops. Every native event becomes a core `GameEvent` pushed through `core.events().handle(...)` → `GameEventRouter`, fanned to **every** active match. **MONITOR** for observe-only lifecycle events (join/quit/death/respawn/world-change/advancement/sneak); **HIGHEST + ignoreCancelled** for vetoable gameplay events (move/damage/block/interact/inventory), whose handlers write the core event's `cancelled()` flag back onto the Bukkit event. Notable mappings: `BlockBreakEvent` also writes `setDropItems(dropItems())` (native drop suppression for DoubleDrops/Randomizer); `EntityDamageEvent` only fires for a `Player` victim, `resolveAttacker` unwraps a direct player damager or a player-shot projectile, uses `getFinalDamage()`; `EntityDeathGameEvent` carries `EntityType.name()` (e.g. `ENDER_DRAGON` → `ender_dragon`, matching Fugitive's dragon check); the `AsyncPlayerPreLoginEvent` auth gate kicks rejected logins with the Adventure `disallow` overload. World names come from Bukkit `World.getName()` everywhere. Each handler builds a fresh, stateless `PaperPlayerAdapter`.

### 2.4 `PaperPlayerAdapter`

`extends PaperCommandSource implements PlayerAdapter`; overrides every optional default any mode uses. `locale()` = real client locale. `teleport` = `teleportAsync` (non-blocking, chunk-safe, **Folia-safe**). `setHealth` clamped `[0, maxHealth]`, `setFoodLevel` `[0,20]`. `setExperiencePoints` zeroes exp/level/total then `giveExp` (reliable round-trip). `setHealthScale`/`resetHealthScale` (`setHealthScaled`) power XpHealth heart display. `setVelocity` is **additive** (Fugitive dash, Chained tug). `clearBossBars` snapshots `activeBossBars()` before hiding (the live set mutates during iteration). Does not override `resetStatuses()` — the core default composition runs and fully restores a clean lobby baseline (not a snapshot of pre-match state).

### 2.5 World adapter & lease lifecycle

`PaperWorldControl extends AbstractWorldControl` (`PaperWorldControl.java:48`) is the Paper backend of the unified control layer. It supplies only platform hooks — `runOnWorldThread` (`GlobalRegionScheduler.run`), `serverHome` (`server.getWorldContainer()`), `backendAcquire`/`backendUnload`/`backendLobby`, stale-temp cleanup — while the core layer owns the pool, registries, naming and disposal policy. Worlds are created/loaded with native **keyed** `WorldCreator`s so they land at exactly `world/dimensions/<namespace>/<key>` on MC 26.1+ (`minecraft:lobby`, `experiences:<nick>/<map>_<id>`, `sexidium_temp:<short>`). [`MultiverseBridge`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/world/MultiverseBridge.java) (Multiverse-Core v5, reflective) imports each world for `/mv` tooling, but native keyed creation does the actual loading. `PaperLobbyBootstrap` provisions the lobby before `core.start()`; `PaperLobbyGuard`/`LobbyGuardPolicy` enforce lobby protection. `PaperWorldAdapter` overrides every optional capability games rely on (`spawnTnt`, `setBlock`, `blockTypeAt`, `nearbyMobs` via `Mob.class`, `fullTimeTicks`); `PaperConverters.toNative(WorldPosition)` returns `null` for an unloaded world, and teleport/compass callers null-guard.

### 2.6 Inventory & kits

| Class | Role | Fidelity |
| --- | --- | --- |
| `PaperInventoryAdapter` | game-logic item checks, kit/shared inventory | **Lossy** — material + amount only; NBT/enchants/durability dropped. Not for save/restore. |
| `PaperInventorySerializer` | reconnect snapshots | **Full fidelity** via `ItemStack.serializeItemsAsBytes`/`deserializeItemsFromBytes` + Base64. Deserialize swallows `Throwable` (silent no-op on a corrupt blob). |
| `PaperKitAdapter` | `kits.<name>` (material + amount); default = iron sword + 4 golden apples | `apply()` is **additive** (no clear first). |

### 2.7 UI & BetterHUD

`PaperUiAdapter` provides boss bars, a sidebar HUD panel, popups, and the platform **HUD driver**; per-player surface lifecycle is core-driven (`AbstractGame.releasePlayerUi`→`hide`, `restorePlayerUi`→`show`). **`PaperBossBarHandle`** wraps one Adventure `BossBar` shared by all viewers (last-rendered viewer's localized title wins for mixed-language audiences). **`PaperScoreboardPanelHandle`** is the sidebar: a **per-player** scoreboard (`MAX_LINES = 15`) whose lines are `Team` prefixes built from Adventure `Component`s, preserving per-client `<lang:>` item names; `show()` stashes the player's previous scoreboard and restores it on `hide`/`close`. **BetterHud** (a `softdepend`) backs `hudDriver()`; `showPopup` and `createPanel` are always native. Calls are **typed** against the `compileOnly` 2.0.0 API and split so a server without the plugin never loads a class it cannot link: `BetterHudLink` (the operator switch + `Class.forName` probe + the shader **capability probe**), `BetterHudApi` (the only file naming a `kr.toxicity.hud` type), `BetterHudClaims` (which surfaces each player should be wearing), `BetterHudRows` (resolves a row to a line rendered in the viewer's own language), `BetterHudSurfaceHandle`, `BetterHudReconciler` (listener + timer). Because BetterHud 2.0.0 has **no programmatic object creation**, `BetterHudAssetCompiler` compiles each declared `HudSurfaceSpec` to yml and `BetterHudAssetStore` writes it into a `sexidium/` subtree of BetterHud's own folders that Sexidium **owns outright** — regenerated every boot, stale files deleted, a SHA-256 manifest so `betterhud reload` fires only on a real change, and nothing outside that subtree ever touched. It also empties BetterHud's `default-hud: [test_hud]` demo list and renames its bundled demo hud/compass/entity-popup with a leading hyphen (BetterHud's own skip convention) — the compass sets `default: true` in its own yml and the entity health popup is `entity_attack`-trigger-driven, so neither can be stopped per player. All opt-out under `hud.betterhud.*`. Whether a surface is *drawing* is a **per-player** question (`HudSurfaceHandle#activeFor`), because BetterHud rides boss bars and Geyser does not pass them on — which is why core stacks the sidebar renderer behind it rather than choosing between them. Menu art and the resource-pack pipeline live in [menu system & art](../interface/menus.md) (`PaperMenuAdapter`, `PaperMenuArt`, `PaperResourcePackService`, `PaperSkullSkins`, `PaperFormRenderer` for Bedrock).

### 2.8 Scheduler & commands

`PaperSchedulerAdapter` is **Folia-safe**: `runNow`→`GlobalRegionScheduler.run`, `runLater`→`runDelayed` (delay floored ≥1), `runTimer`→`runAtFixedRate` (both floored ≥1), `runAsync`→`AsyncScheduler.runNow`. The region-scoped methods downcast to the native handle: `runForPlayer*`→`player.getScheduler()` (EntityScheduler), `runAtRegion`→`Bukkit.getRegionScheduler().run(world, chunkX, chunkZ, …)`; `isRegionThreaded()`→`FoliaSupport.isFolia()` (detected by the Folia-only `RegionizedServer` class). `PaperScheduledTask.cancel()` guards `null` + `isCancelled`. The plugin declares `folia-supported: true`; the adapters never touch the legacy `BukkitScheduler` (decor animation/join-hide, NPC skin re-apply, Bedrock form-click all dispatch through region/entity/async schedulers) and all entity teleports are `teleportAsync`. *Remaining caveat:* core game-loop ticks (`AbstractGame`, `ExperienceGame`, challenges, `LobbyManager`) still run on the global region; the region-scoped seam is the migration path for moving entity/block mutation onto its owning region. `/sexidium` (alias `sx`) is bound to `PaperCommandBridge` (executor + tab-completer); it wraps a `Player` as `PaperPlayerAdapter` (so `instanceof PlayerAdapter`) else as `PaperCommandSource`, then forwards the raw args to `CoreCommandService.execute/.suggest` — **all subcommand logic is in core**, so behavior matches NeoForge. `PaperCommandDispatcherAdapter` runs console commands via `Bukkit.dispatchCommand`. `PaperCommandSource.locale()` is hardcoded `ENGLISH` for non-players (by design).

### 2.9 Config / logging / resources / `plugin.yml`

`PaperConfigurationAdapter` → Bukkit `FileConfiguration` (unwraps `MemorySection`); `PaperLoggerAdapter` → j.u.l.; `PaperResourceAdapter` → `plugin.getResource`; `PaperConverters` centralizes `Location ↔ WorldPosition`, `GameMode`, `Material ↔ ItemKey`, boss-bar enums. `plugin.yml` declares only the `sexidium` command, `folia-supported: true`, the three JDBC drivers (SQLite + `mysql-connector-j` + `postgresql`) as Paper-loaded `libraries` (the active one is picked at runtime by `Class.forName`), **Multiverse-Core** as a hard `depend`, **BetterHud** as a `softdepend`, and permissions `sexidium.play` (default true) + `sexidium.admin` (default op).

**Database backends.** The persistence layer (`core` `lib/data`) is dialect-abstracted: `database.type` selects `sqlite` (default, embedded file `database.file`), `mysql`, or `postgres` (networked: `database.host/port/name/user/password/properties` — a *global* DB shared by a network of servers). `SqlDialect` emits portable DDL (TEXT keys→`VARCHAR(191)`, `AUTOINCREMENT`→identity, `REAL`→`DOUBLE`, partial/case-insensitive indexes degraded where unsupported) and upserts (`ON CONFLICT … excluded` on SQLite/Postgres, `ON DUPLICATE KEY … VALUES()` on MySQL); repositories write portable SQL (`LOWER(col)=LOWER(?)` instead of `COLLATE NOCASE`) and `SchemaMigrator` probes via JDBC `DatabaseMetaData` instead of `PRAGMA`/`sqlite_master`. The shared single-connection-under-`lock()` model is unchanged (it self-heals dropped networked connections). `DatabaseSettings.resolve(config, dataDir)` builds the `DatabaseConfig` for both adapters; NeoForge fat-jars the drivers (no library loader). The Discord bot stays backend-agnostic — it reaches data only over the HTTP bridge, never the DB.

> Core's auth gate also checks `sexidium.auth` (`CoreCommandService.java:125`), which is **not declared** in `plugin.yml` — it falls through to the `sexidium.play` default, so `/sx auth` still works but `sexidium.auth` is effectively dead. Declare it (default true) to make the OR meaningful.

---

## Part 3 — NeoForge adapter (`module-neoforge`)

> **Not currently in the build.** `settings.gradle.kts` includes only `:packages:core` and
> `:packages:module-paper`, and `packages/module-neoforge` is not in the tree. This part describes the
> intended design; it was not updated for MC 26.2 and none of it compiles today.

Module: `packages/module-neoforge`. Target: **NeoForge 26.1.2.71**, `modLoader="javafml"`, mod id `sexidium`. A **thin, reflection-only** implementation: it bootstraps core on the mod-loader lifecycle, bridges native events into core `GameEvent`s, and implements the SPI against the Minecraft runtime almost entirely through reflection. Only `@SubscribeEvent`, the NeoForge event-class types in `NeoForgeEventBridge`, and Brigadier are referenced directly.

### 3.1 Bootstrap (`NeoForgeSexidiumMod`)

`@Mod("sexidium")` registers the instance on `NeoForge.EVENT_BUS` in the constructor; real init is deferred to `ServerStartingEvent` (the live `MinecraftServer` must exist) and is idempotent via a `core != null` guard. Sequence: reflect `getServer()` → `new NeoForgeServerAdapter(server, config/sexidium)` → `NeoForgeKitAdapter` → `setupDatabase()` → `NeoForgeLobbyBootstrap(...).provision()` → `new SexidiumCore(...)` → `messageHandle().use(core.messages())` → `core.start()` → `worlds().start()` → register `NeoForgeEventBridge` + `NeoForgeCommandBridge`, then register commands against the live dispatcher. `dataDirectory` is hardcoded to relative `Path.of("config", "sexidium")`. Commands register **twice** (eagerly here + on `RegisterCommandsEvent` in the bridge); Brigadier tolerates it. **Shutdown** (`onServerStopping`): `core.close()` → `worlds().shutdown()` → `serverAdapter.close()` (stops the scheduler pool) → `database.close()`.

### 3.2 Event bridging (`NeoForgeEventBridge`) + synthetic events

One listener on `NeoForge.EVENT_BUS`; `registerGame`/`unregisterGame` are no-ops. NeoForge has **no native equivalents** for several Bukkit events, so the bridge **synthesizes** them by diffing per-player state every tick (`PlayerTickEvent.Post`), backed by three maps seeded on login and cleared on logout (`lastPositions`, `lastInventorySignatures`, `lastSneakingStates`).

| Native event | Core `GameEvent` | Cancel behavior / notes |
| --- | --- | --- |
| `PlayerNegotiationEvent` | *(auth gate)* | `core.authLogin().verify`; reject → `enqueueWork` disconnect. |
| `ServerTickEvent.Post` | *(scheduler tick)* | Calls `schedulerHandle().tick()` — **the only** driver of the cooperative scheduler. |
| `PlayerLoggedIn/Out` | `PlayerJoin/QuitGameEvent` | Seeds/clears the diff maps. |
| `PlayerTickEvent.Post` (pos diff) | `PlayerMoveGameEvent` | Synthetic. Cancel → teleport back (post-hoc rubber-band, not a true veto). |
| `PlayerTickEvent.Post` (storage-sig diff) | `InventoryChangeGameEvent` | Synthetic. **Cancel ignored**; hashes the `items` list only (armor/offhand undetected). |
| `PlayerTickEvent.Post` (shift/crouch diff) | `PlayerToggleSneakGameEvent` | Synthetic. |
| `LivingDamageEvent.Pre` | `PlayerDamageGameEvent` | `ServerPlayer` victim; damage = `getNewDamage()`; cancel → `setNewDamage(0)`. |
| `BreakBlockEvent` | `BlockBreakGameEvent` | Cancel → `setCanceled(true)`; drop suppression below. |
| `BlockEvent.EntityPlaceEvent` | `BlockPlaceGameEvent` | Cancel → `setCanceled(true)`. |
| `PlayerInteractEvent.{LeftClickBlock, RightClickBlock, RightClickItem}` | `PlayerInteractGameEvent` | LEFT/RIGHT click; PHYSICAL not mapped. |
| `LivingDeathEvent` | `EntityDeathGameEvent` | `entityType = NeoForgeConverters.entityTypeId(entity)` — see §3.5. |
| `PlayerRespawnEvent` | `PlayerRespawnGameEvent` | Core sends respawner to lobby. |
| `PlayerChangedDimensionEvent` | `PlayerChangedWorldGameEvent` | from/to = `ResourceKey.location()` → `namespace:path`. |
| `AdvancementEvent.AdvancementEarnEvent` | `PlayerAdvancementGameEvent` | id = `advancement.id()`. |

Block-break drop suppression: `BreakBlockEvent` has **no `setDropItems`**, so when a mode returns `dropItems() == false` the bridge cancels the vanilla break **and** calls `level.destroyBlock(pos, false)` so the block breaks without vanilla drops.

### 3.3 Reflection layer

The whole adapter avoids a compile-time dependency on Minecraft/NeoForge-mapped runtime classes — to survive mapping/version churn, degrade gracefully, and decouple the build. Two pieces:

- **[`NeoForgeReflector`](../../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/util/NeoForgeReflector.java)** — ad-hoc per-call dispatch by name: `invoke`/`invokeStatic`/`invokeRequired` (walks superclasses + public methods, auto-boxes primitives), `getField`/`setField`/`getStaticField`, `construct` (first param-count/assignability match), typed helpers (`string`/`integer`/`bool`/…), `namedMethods` (all overloads, used to probe `teleportTo` signatures). **`invoke()` returns `null` when a method is not found rather than throwing** — a missing/renamed method silently no-ops (same philosophy as the SPI's `default` no-ops, but the source of several parity gaps).
- **[`NeoForgeWorldGenBridge`](../../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/world/NeoForgeWorldGenBridge.java)** — pre-resolves every class/method/ctor/field **once at class-load** into `public static final` handles, records failures into `RESOLUTION_ERRORS`, and exposes `resolutionErrors()`/`logResolutionSummary(LoggerAdapter)` so a broken mapping is obvious in the log. Holds the world-building helpers (`createDimensionKey`, `createLevelStem`, `createNoiseChunkGenerator`, `createServerLevel`, `createStorageAccess`, `worldName`).

**`NeoForgeMiniMessage`** reproduces Paper's MiniMessage rendering without Adventure by parsing into a styled vanilla `Component`: named/hex colors, five decorations, `<reset>`, escapes, and `<lang:key>`/`<tr:key>` → `Component.translatable` (per-client item localization). Gradients/rainbow **degrade to the first solid color** (documented visual-only divergence); unknown tags push a no-op frame so closing tags still balance; if runtime style classes are unavailable, `toComponent` returns `null` and the caller falls back to a stripped plain literal. `parse(String)` is pure/unit-tested; rendering is reflective.

### 3.4 World generation & lease lifecycle

There is no third-party world plugin on NeoForge (Multiverse is Bukkit-only), so the adapter **builds `ServerLevel`s by hand** through the vanilla world-gen pipeline. `NeoForgeServerAdapter.createLevel(name, customFolder)` assembles overworld `DimensionType` + `NoiseGeneratorSettings.OVERWORLD` + `MultiNoiseBiomeSource` → `NoiseBasedChunkGenerator` → `LevelStem` → `ServerLevel` via the 10-arg ctor → `server.addLevel(level)`, with `tryCreateLevelFallback` probing `server.createLevel(ResourceKey)` then `(ResourceKey, DimensionType)`, heavily instrumented with `[WorldGen]` step logging + `logResolutionSummary`.

`NeoForgeWorldLeaseService` **implements `WorldLeaseService` directly** — it was **not** migrated to `AbstractWorldControl`. It maintains its own warm pool (`worlds.temp.pool-size`, default 3), `managed`/`leased`/`preserved` name sets, `cleanupStaleWorlds()`, and a chunk-preload progress UI (`preloadSpawn`, radius `preload-radius-chunks`=6, `chunks-per-tick`=4, per-viewer boss-bar). Its `dispose()` guards only on lobby/preserved names (no `startsWith(prefix())` regression). `NeoForgeLobbyBootstrap` provisions a persistent lobby (auto-save on) via the same `createLevel` pipeline. `NeoForgeWorldAdapter` overrides every optional `WorldAdapter` capability games use (`setBlock` flags `2|16`, `blockTypeAt`, `nearbyMobs` via `Mob.class`, `fullTimeTicks`, `spawnTnt` 2-/6-arg, `dropItem`, border, `loadChunk`, `setAutoSave`).

> **Naming caveat.** This service tracks the *full dimension location* (`NeoForgeWorldGenBridge.createDimensionKey` prepends `sexidium:`, so `NeoForgeWorldLeaseService.java:40` `LEVEL_NAMESPACE = "sexidium"` and a temp world reports `sexidium:worlds/temp/<id>`) while folders/prefix/lobby names are bare. The mismatch breaks lobby lookup (`"sexidium:lobby".equals("lobby")` → false), reconnect-after-restart (`reacquireByName` re-prepends `sexidium:` → invalid `sexidium:sexidium:...`), and disk folder delete. The in-memory unload/dispose path itself works (it operates on a live handle and guards only on lobby/preserved); it is the *name→folder/key* resolution that is broken. The Paper `WorldNaming` unification fixes exactly this class of bug — migrating NeoForge onto `AbstractWorldControl` is the outstanding follow-up.

### 3.5 Player adapter, UI handles, menus

`NeoForgePlayerAdapter extends NeoForgeCommandSource`. `teleport` does a multi-strategy reflective search over all `teleportTo` overloads, falling back to `moveTo` — **synchronous** (vs Paper's async). `setExperiencePoints` mirrors Paper's zero-then-give. `setCompassTarget`/`resetCompass` send a `ClientboundSetDefaultSpawnPositionPacket` so the held compass tracks an arbitrary per-player point (Fugitive hunter compass) without touching server spawn. `setVelocity` = additive `Entity#push`. `clearBossBars` is a **no-op by design** (vanilla has no per-player boss-bar registry; bars are hidden per-match via `Game#releasePlayerUi` → `BossBarHandle#hide` → `ServerBossEvent#removePlayer`). `setHealthScale`/`resetHealthScale` reflectively target **Bukkit-only** method names → `invoke` returns `null` → **silent no-op** (XpHealth heart-bar scaling is inert on NeoForge). `resetStatuses()` is not overridden.

UI handles: `NeoForgeBossBarHandle` (vanilla `ServerBossEvent`; title rendered **once** in the server default language). `NeoForgeScoreboardPanelHandle` is a **server-global, EN-only** sidebar objective with best-effort per-client hide — any online player (even a non-participant) sees the match HUD (documented divergence; minor info leak, not a crash). `NeoForgeUiAdapter` maps boss-bar enums to vanilla and routes WIN/ELIMINATION/OBJECTIVE → title else action bar (no BetterHUD equivalent).

Menus are rendered via a vanilla `ChestMenu` subclass generated **once per JVM** with ASM bytecode — because in this module's build the deobfuscated `net.minecraft.*` classes are not on the compile classpath (only the `net.neoforged.*` API is), so `ChestMenu` cannot be extended with statically compiled Java. The generated `SexidiumChestMenu` ([`NeoForgeMenuClassGenerator`](../../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/menu/NeoForgeMenuClassGenerator.java)) only forwards `clicked`/`quickMoveStack`/`stillValid` into static helpers, keeping the real logic in plain Java + reflection (`NeoForgeMenuClickHandler`). See [menu system & art](../interface/menus.md).

Inventory: `NeoForgeInventoryAdapter` reflects into `Inventory.items`/`armor`/`offhand` (material + amount). `NeoForgeInventorySerializer` encodes a Base64 blob of `storage=`/`armor=`/`offhand=` sections, each item `namespace:path:amount` — **lossy** (drops all data components) and **not cross-compatible** with Paper's byte format (relevant only for a shared DB). `storageCapacity` derives from the real backing-list size to match Paper.

### 3.6 Parity gaps vs Paper

| Area | Paper | NeoForge | Status |
| --- | --- | --- | --- |
| Runtime access | Bukkit/Adventure directly | reflection (`NeoForgeReflector` + `NeoForgeWorldGenBridge`) | Equivalent intent |
| Scheduler | Folia `GlobalRegionScheduler`/`AsyncScheduler` | cooperative; advanced only by `ServerTickEvent.Post` | Equivalent timing |
| Player move | native event, true pre-move cancel | synthetic per-tick diff + teleport-back | **Drift** (post-hoc, sub-tick miss) |
| Inventory change | `InventoryClick`/`Drag`, cancel honored | synthetic storage-sig diff | **Drift** (cancel ignored; armor/offhand undetected) |
| Interact PHYSICAL | mapped | not mapped | **Drift** (low impact) |
| Projectile attacker | unwraps `getShooter` | `getEntity()` only | **Drift** (edge cases) |
| Entity death type | `EntityType.name()` → `ender_dragon` | `NeoForgeConverters.entityTypeId` via `BuiltInRegistries.ENTITY_TYPE` → `ender_dragon` | **Fixed** — Fugitive DRAGON objective now fires |
| `setHealthScale`/`resetHealthScale` | native Bukkit API | reflective Bukkit-only names → silent no-op | **Drift** (XpHealth bar inert) |
| Teleport | `teleportAsync` (non-blocking) | synchronous `teleportTo`/`moveTo` | **Drift** (timing) |
| Block-break drop suppression | `setDropItems(false)` | cancel + `destroyBlock(pos,false)` | Equivalent (documented workaround) |
| World naming/lease | unified `AbstractWorldControl` + `WorldNaming` | own `WorldLeaseService`; full-vs-bare name mismatch | **Drift** (lobby lookup / reacquire / folder delete broken) — migration pending |
| Lobby provisioning | Multiverse-Core (reflective) + native keyed creation | own `ServerLevel` pipeline | Equivalent intent |
| Warm pool + chunk-preload UI | pooled (core layer) | pooled + progressive preload + boss-bar progress | NeoForge richer here |
| Scoreboard panel | per-player, per-language | server-global, EN-only | **Drift** (UX/info-leak) |
| Boss-bar title | re-rendered per viewer | rendered once, server language | **Drift** (mixed-language) |
| Inventory serialization | full-fidelity bytes | lossy `key:amount` text | **Drift** (data loss; not cross-compatible) |
| MiniMessage | Adventure (full gradients) | gradients → first solid color | **Drift** (visual only) |
| Decor (display entities) | `PaperDecorAdapter` | NOOP (not implemented) | **Gap** (additive flair; Bedrock-invisible anyway) |
| Jump input (`PlayerJumpGameEvent`) | `PlayerJumpEvent` at MONITOR + a `PlayerVelocityEvent` knockback veto | not raised | **Gap** — Jump Multiplies never triggers there |
| `PlayerAdapter#duplicateNearbyEntities` / `supportsEntityDuplication` | `Entity#copy(Location)` over a snapshot of the loaded-chunk walk; full NBT clone, TNT fuses jittered | `default` → `0` / `false` | **Gap** — the challenge is inert, and its HUD says so rather than reading as broken |
| `WorldAdapter#countNearbyEntities` | same loaded-chunk walk, spherical test, count only | `default` → `0` | **Gap** — the live-entity ceiling never engages |
| Commands / permissions | Bukkit named perms | Brigadier; `sexidium.admin`→op level 2, others→level 0 | Equivalent default |

A cross-platform open issue (both adapters, core-level): there is **no party/friend gate on match join** — `GameManager.joinInProgress` checks only online + not-already-in-match + a running match exists, so any `sexidium.play` holder can `/sx join <mode>` a stranger's match. This is an authorization gap in the command/game layer (see [lobby unification](../gameplay/lobby-worlds-and-social.md)), not the SPI.

---

## Keeping this current

Source of truth (the doc is a derived view): the SPI under `packages/core/src/main/java/com/sexidium/core/platform/` (+ `platform/model`, `platform/noop`) and the unified world layer `packages/core/src/main/java/com/sexidium/core/world/` (`AbstractWorldControl`, `WorldNaming`); the adapter trees `packages/module-paper/src/main/java/com/sexidium/paper/` and `packages/module-neoforge/src/main/java/com/sexidium/neoforge/`; and `module-paper/.../plugin.yml` + `neoforge.mods.toml`. Update this doc in the **same change** that touches those files. Triggers: a new SPI interface or `ServerAdapter` default getter; a new/removed adapter class in either module tree; a signature/behavior change in any contract or capability default; a parity gap opening or closing (especially the pending NeoForge `AbstractWorldControl` migration); a `worlds.*`/`auth.*`/permission config key added or removed.
