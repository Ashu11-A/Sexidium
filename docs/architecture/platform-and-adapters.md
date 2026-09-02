# Platform SPI & Adapters

The platform-agnostic engine in `com.sexidium.core` never imports a Bukkit, Adventure, or Minecraft class. It talks only to the **service-provider interface (SPI)** in `com.sexidium.core.platform` (interfaces + handles) and `com.sexidium.core.platform.model` (immutable value records/enums), with side-effect-free fallbacks in `com.sexidium.core.platform.noop`. Each adapter module — **Paper** (`com.sexidium.paper`, `packages/module-paper`, every game server) and **Velocity** (`com.sexidium.velocity`, `packages/module-velocity`, the network proxy) — implements its slice of the interfaces and hands the engine one root object: a [`ServerAdapter`](../../packages/core/src/main/java/com/sexidium/core/platform/ServerAdapter.java) on Paper, the narrowed `NodeRuntime` on Velocity. This doc is Part 1 the SPI contract, Part 2 the Paper internals, Part 3 the Velocity internals. Gameplay, matchmaking, social, persistence, and command logic all live in core (see [architecture](overview.md)); the adapters are thin translation layers.

---

## Part 1 — The SPI

### 1.1 Wiring

The adapter builds one `ServerAdapter` plus a `KitAdapter`/`RankAwardPort` and passes them into `SexidiumCore`, which constructs a single [`GameContext`](../../packages/core/src/main/java/com/sexidium/core/game/GameContext.java). A game mode reaches the platform only through that context: `gameContext.server()` → `ServerAdapter`, `gameContext.kits()` → `KitAdapter`, `gameContext.games()` → `GameManager` (`gameContext.ranks()` is the ranking port, not part of `platform/`).

Three mechanisms keep core platform-agnostic:

1. **Plain types only.** Interfaces expose JDK types (`UUID`, `Path`, `Optional`, …) and the `platform/model` records. World identity always crosses the boundary as a `String` name inside `WorldPosition`/`BlockPosition` — never a live world handle. Core resolves worlds by name via `WorldLeaseService` and `WorldAdapter.name()`.
2. **Optional capabilities are Java `default` methods.** A platform that cannot do something inherits a no-op/inert default rather than failing to compile. Convenient, but a correctness trap: a partially-implemented adapter lets gameplay calls vanish silently — which is why availability is *probed* at runtime (`versions()`/`capabilities()` below) rather than assumed from what an adapter compiles against.
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
| `versions()` *(default)* | `ServerVersionPort` | Minecraft version + resource-pack format of the RUNNING server (`platform/version/`). Paper: `ServerBuildInfo` → `Bukkit.getMinecraftVersion()` → `getBukkitVersion()`, pack format via `PackFormats`. Default: `UNKNOWN`/no opinion. |
| `capabilities()` *(default)* | `CapabilityRegistry` | What THIS server can serve right now, probed, with a reason per miss (`platform/capability/`). Feeds the boot log + `/sx admin capabilities`. Default: supports nothing. |

`ServerAdapter` carries these `default` getters that Paper overrides (an empty/NOOP default keeps headless wiring valid):

| Default getter | Default | Paper backend |
| --- | --- | --- |
| `itemTranslationKey(ItemKey)` | `""` (→ plain server-side name) | `Material.translationKey()` |
| `inventorySerializer()` | `InventorySerializer.NOOP` | `PaperInventorySerializer` (full-fidelity bytes) |
| `menus()` | `MenuAdapter.NOOP` | `PaperMenuAdapter` (chest GUI) |
| `npcs()` | `NpcAdapter.NOOP` | `PaperNpcBackend` → `PaperNpcAdapter` (FancyNpcs/FancyHolograms), the first backend on the general `Backend`/`BackendStack` seam |
| `decor()` | `DecorAdapter.NOOP` | `PaperDecorAdapter` (native display entities) |
| `rankTags()` | `RankTagAdapter.NOOP` | `PaperRankTagAdapter` (scoreboard Team) |
| `versions()` / `capabilities()` | UNKNOWN / EMPTY | `PaperServerVersionPort` / `PaperCapabilityRegistry.probe(...)` |

> Decor is additive Java flair (item/block displays are invisible on Bedrock, see [menu system & art](../interface/menus.md)). The `Backend<T>`/`BackendStack<T>` abstraction in `platform/backend/` is the generalised form of the HUD driver stack — platform implementation over a guaranteed floor, honest capabilities, selection instead of hand-rolled gates; migrate a seam to it when its gate grows a second consumer.

### 1.3 Sibling service interfaces

- **`ConfigurationAdapter`** — typed config with defaults: `getBoolean/Int/Long/Double/String(path, default)`, `getStringList(path)`, `getMapList(path)` (arena spawns, team/kit lists), `keys(path)` (enumerate game ids/categories), `get/contains/set/reload/save`. Paths are dotted (`worlds.temp.pool-size`). `getMapList`/`keys` return empty (not null) on a missing/flat path.
- **`LoggerAdapter`** — `info/warning/severe`, plus `…(msg, Throwable)` overloads.
- **`ResourceAdapter`** — `openResource(String) -> Optional<InputStream>` for jar-bundled assets.
- **`SchedulerAdapter` + `ScheduledTask`** — global timing: `runNow`, `runLater(r, delayTicks)`, `runTimer(r, delay, period)` (20 ticks = 1 s), `runAsync(r)`. **Ticks are the engine's timing unit.** `ScheduledTask.cancel()` must really cancel; `AbstractGame.cleanup()` cancels every task it scheduled. **Region-scoped (Folia) variants** with default fallbacks to the global methods: `isRegionThreaded()`, `runForPlayer(player, task, retired)` / `runForPlayerLater(...)` (Folia EntityScheduler — runs on the region owning that player), `runAtRegion(world, blockX, blockZ, task)` (Folia RegionScheduler). Non-region-threaded platforms inherit the global fallbacks unchanged.
- **`EventDispatcherAdapter`** — `registerGame`/`unregisterGame`. **Adapters implement these as no-ops**: native events are bridged once by a single platform listener into `core.events().handle(...)`, which fans out to every active match's `game.handle(...)`. There is **no per-game event isolation at the platform layer**; a game self-filters by participant/world.
- **`CommandDispatcherAdapter`** — `dispatchFromConsole(String)` runs a line with console authority (HTTP `/command` bridge, kit-give).
- **`MessageAdapter`** — `send(...)` (engine prefix/format) vs `raw(...)`; both `LocalizedText` and MiniMessage overloads. Per-client localization comes from reading the recipient's `locale()` at render time.
- **`UiAdapter` + `BossBarHandle` + `HudPanelHandle`** — `createBossBar(text, progress, color, overlay)`; `showPopup(player, type, text)` *(default falls back to a title for WIN/ELIMINATION/OBJECTIVE, else action bar)*; `createPanel(title)` *(default → `HudPanelHandle.NOOP`)*. Handles are **per-match overlays** with per-viewer `show(player)`/`hide(player)` + `close()`. This is what lets `AbstractGame.releasePlayerUi()` hide one player's overlays while everyone else keeps theirs — the reason per-player hide/show exists at all, since a blanket "clear every boss bar" would strip bars belonging to other matches. `HudPanelHandle` adds `line(index, text)`, `removeLine(index)`, `refresh()` — call `refresh()` once after a batch of `line()` edits.

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
- **`InventorySerializer`** — `serialize(PlayerAdapter) -> String`, `deserializeInto(PlayerAdapter, String)`. The **only** inventory snapshot/restore in the system (reconnect persistence). `InventorySerializer.NOOP` is an inert default; Paper's implementation is full-fidelity (`ItemStack` bytes).
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

> **The big change since the old docs.** `WorldLeaseService` is no longer implemented twice by hand. The Paper backend is now [`AbstractWorldControl`](../../packages/core/src/main/java/com/sexidium/core/world/AbstractWorldControl.java) (`AbstractWorldControl.java:36`), a unified core layer that owns **all** lifecycle policy — the managed/leased/preserved registries, the warm pool, the disposal decision tree, player evacuation, world classification, and the worldgen guarantees — and exposes a handful of abstract platform hooks (`runOnWorldThread`, `serverHome`, `backendAcquire`, `backendUnload`, `backendLobby`, …) for the irreducible raw operations (`AbstractWorldControl.java:59-105`). Naming/classification is centralized in [`WorldNaming`](../../packages/core/src/main/java/com/sexidium/core/world/WorldNaming.java), which addresses every managed world by `namespace:key-path` mapping to `world/dimensions/<namespace>/<key>` on MC 26.1+. This unification is what retired the per-platform world-service naming divergences behind the old dispose-leak and double-namespace bugs. See [world control unification](../gameplay/lobby-worlds-and-social.md).

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

Both modules implement their slice of the SPI. `events()`/`commands()` may be returned fresh per call (Paper does); other sub-adapters are cached fields. Paper implements `ServerAdapter` in full; Velocity implements `NodeRuntime` (the proxy subset) and never builds the gameplay half.

| SPI | Paper | Velocity |
| --- | --- | --- |
| Root aggregate | `PaperServerAdapter` (full `ServerAdapter`) | `VelocityNodeRuntime` (`NodeRuntime` only) |
| `ConfigurationAdapter` / `LoggerAdapter` / `ResourceAdapter` | `Paper…` | `YamlConfigurationAdapter` / `VelocityLoggerAdapter` / `ClassLoaderResourceAdapter` |
| `SchedulerAdapter` / `ScheduledTask` | `PaperSchedulerAdapter` / `PaperScheduledTask` (Folia-safe) | `VelocitySchedulerAdapter` |
| `EventDispatcherAdapter` | `PaperEventDispatcherAdapter` *(no-op; events via `PaperEventBridge`)* | not applicable — no games run on the proxy |
| `CommandDispatcherAdapter` / `MessageAdapter` | `Paper…` | console-dispatch (proxy commands) / `VelocityMessageAdapter` |
| `UiAdapter` / `BossBarHandle` / `HudPanelHandle` | `Paper…` / `PaperScoreboardPanelHandle` | not applicable |
| `HudDriver` / `HudSurfaceHandle` | `BetterHudDriver` *(behind `BetterHudLink.available()`; else `NOOP`)*, stacked over core's `SidebarHudDriver` — `activeFor(player)` is the answer that decides a screen | not applicable |
| `CommandSource` / `PlayerAdapter` | `PaperCommandSource` / `PaperPlayerAdapter` | `VelocityConsoleSource` / `VelocityPlayer` (as `NetworkPlayer`) |
| `WorldAdapter` / `WorldLeaseService` / `WorldLease` | `PaperWorldAdapter`; `PaperWorldControl extends AbstractWorldControl` | not applicable |
| `InventoryAdapter` / `InventorySerializer` / `KitAdapter` / `MobHandle` | `Paper…` | not applicable |
| `MenuAdapter` / `NpcAdapter` / `RankTagAdapter` / `DecorAdapter` | `Paper…` | not applicable |


### 1.9 The boundary is enforced by a test, not by a module

Core declares no platform dependency, so a stray `import org.bukkit.*` in core simply does not compile —
that half of the seam is free. The other half is not: nothing about the build stops an *adapter* from
reaching into more and more of core until the boundary has quietly dissolved. That is the failure a
separate `core-api` Gradle module is usually built to prevent, and it is the one part of the module that
is actually load-bearing.

So it is a test instead. `AbstractCoreApiSurfaceTest`
(`packages/core/src/testFixtures/java/com/sexidium/core/testing/`) scans a module's main sources for
every `com.sexidium.core` type it names and diffs the set against a checked-in list at
`src/test/resources/golden/core-api-surface.txt`. Both adapters extend it — `module-paper` names 146
core types, `module-velocity` 45 — so widening the surface is a reviewed one-line diff instead of an
invisible one.

Two details are what make it worth having rather than theatre:

- **It matches NAMES, not `import` lines.** A type used by its fully-qualified name needs no import; an
  import-only scan cannot see it, and anyone who wanted to skip the review only had to write the long
  name. Eight core types were already through that hole when it was closed.
- **Wildcard imports of core packages fail the test outright**, because one golden entry that licenses
  every type in a package is the same hole with a different shape.

To widen the surface deliberately, regenerate and commit the diff with your change:

```bash
./gradlew :packages:module-paper:test --tests '*ApiSurface*' -Dsexidium.updateGolden=true
```

The module's `test` task forwards that property into the test JVM and declares `src/main/java` as a task
input, so the check re-runs when sources change rather than sitting `UP-TO-DATE` behind an unchanged
classpath.

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

`core.start()` reloads messages/kits, restores persisted matches, then runs `worlds().start()`, `apiServer.start()`, `botManager.start()`. **Teardown** `onDisable()` closes core (`gameManager.prepareShutdown()`, `worlds().shutdown()`, stop bot/API/repos) then the database. **Reload** `reloadSexidium()` reloads config + kit adapter + `core.reload()` — so `/sx admin reload` runs a *full plugin* reload on Paper.

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

`PaperSchedulerAdapter` is **Folia-safe**: `runNow`→`GlobalRegionScheduler.run`, `runLater`→`runDelayed` (delay floored ≥1), `runTimer`→`runAtFixedRate` (both floored ≥1), `runAsync`→`AsyncScheduler.runNow`. The region-scoped methods downcast to the native handle: `runForPlayer*`→`player.getScheduler()` (EntityScheduler), `runAtRegion`→`Bukkit.getRegionScheduler().run(world, chunkX, chunkZ, …)`; `isRegionThreaded()`→`FoliaSupport.isFolia()` (detected by the Folia-only `RegionizedServer` class). `PaperScheduledTask.cancel()` guards `null` + `isCancelled`. The plugin declares `folia-supported: true`; the adapters never touch the legacy `BukkitScheduler` (decor animation/join-hide, NPC skin re-apply, Bedrock form-click all dispatch through region/entity/async schedulers) and all entity teleports are `teleportAsync`. *Remaining caveat:* core game-loop ticks (`AbstractGame`, `ExperienceGame`, challenges, `LobbyManager`) still run on the global region; the region-scoped seam is the migration path for moving entity/block mutation onto its owning region. `/sexidium` (alias `sx`) is bound to `PaperCommandBridge` (executor + tab-completer); it wraps a `Player` as `PaperPlayerAdapter` (so `instanceof PlayerAdapter`) else as `PaperCommandSource`, then forwards the raw args to `CoreCommandService.execute/.suggest` — **all subcommand logic is in core**. `PaperCommandDispatcherAdapter` runs console commands via `Bukkit.dispatchCommand`. `PaperCommandSource.locale()` is hardcoded `ENGLISH` for non-players (by design).

### 2.9 Config / logging / resources / `plugin.yml`

`PaperConfigurationAdapter` → Bukkit `FileConfiguration` (unwraps `MemorySection`); `PaperLoggerAdapter` → j.u.l.; `PaperResourceAdapter` → `plugin.getResource`; `PaperConverters` centralizes `Location ↔ WorldPosition`, `GameMode`, `Material ↔ ItemKey`, boss-bar enums. `plugin.yml` declares only the `sexidium` command, `folia-supported: true`, the three JDBC drivers (SQLite + `mysql-connector-j` + `postgresql`) as Paper-loaded `libraries` (the active one is picked at runtime by `Class.forName`), **Multiverse-Core** as a hard `depend`, **BetterHud** and **Vault** as `softdepend`s, and permissions `sexidium.play` (default true) + `sexidium.admin` (default op) + `sexidium.economy.pay` (default true) + `sexidium.economy.balance.others` (default op).

**Economy (Vault): Sexidium is the PROVIDER, not a consumer.** VaultUnlocked's own jar contains no economy at all — `Vault.java` is a services-manager lookup and nothing else — so a network that installs it and nothing else has no money. `PaperEconomyBridge` therefore *registers* Sexidium's balances into Bukkit's `ServicesManager`, as both `net.milkbowl.vault2.economy.Economy` and (because VaultUnlocked does not bridge vault2 back to vault1, while most shop plugins still ask for vault1 alone) the legacy `net.milkbowl.vault.economy.Economy`. Split the same way BetterHud is, and for the same reason: `VaultUnlockedLink` names no Vault type at all (the softdepend entry is `Vault`, because VaultUnlocked's `plugin.yml` declares that name), while `SexidiumVaultEconomy` / `SexidiumLegacyVaultEconomy` do and are only constructed behind its `Class.forName` + `LinkageError` probe. Every refusal path is a degradation: with no VaultUnlocked, no database, or `economy.vault.provider: false`, Sexidium's own `/pay`, `/balance` and sidebar balance keep working and only other plugins lose sight of the money. The bridge also logs any *existing* economy provider before registering, because two providers is the failure where half the server reads one set of balances and half reads the other with nothing in any log saying so. Balances live in `economy_accounts` as **cents in an integer column** (`SqlDialect` has no decimal token and SQLite has no real `DECIMAL`), and every mutation is a single conditional `UPDATE`. All opt-out under `economy.*`.

**Database backends.** The persistence layer (`core` `lib/data`) is dialect-abstracted: `database.type` selects `sqlite` (default, embedded file `database.file`), `mysql`, or `postgres` (networked: `database.host/port/name/user/password/properties` — a *global* DB shared by a network of servers). `SqlDialect` emits portable DDL (TEXT keys→`VARCHAR(191)`, `AUTOINCREMENT`→identity, `REAL`→`DOUBLE`, partial/case-insensitive indexes degraded where unsupported) and upserts (`ON CONFLICT … excluded` on SQLite/Postgres, `ON DUPLICATE KEY … VALUES()` on MySQL); repositories write portable SQL (`LOWER(col)=LOWER(?)` instead of `COLLATE NOCASE`) and `SchemaMigrator` probes via JDBC `DatabaseMetaData` instead of `PRAGMA`/`sqlite_master`. The shared single-connection-under-`lock()` model is unchanged (it self-heals dropped networked connections). `DatabaseSettings.resolve(config, dataDir)` builds the `DatabaseConfig` on both sides; the proxy shades the drivers into its jar (no library loader) and refuses SQLite with a dedicated error. The Discord bot stays backend-agnostic — it reaches data only over the HTTP bridge, never the DB.

> Core's auth gate also checks `sexidium.auth` (`CoreCommandService.java:125`), which is **not declared** in `plugin.yml` — it falls through to the `sexidium.play` default, so `/sx auth` still works but `sexidium.auth` is effectively dead. Declare it (default true) to make the OR meaningful.

---

## Part 3 — Velocity adapter (`module-velocity`)

Module root `packages/module-velocity/src/main/java/com/sexidium/velocity/`. Target API: `velocity-api:3.5.1` (class-file major 65 — Java 21), shaded jar (Velocity has no library loader, so the JDBC drivers are bundled). The proxy implements only the SPI's **proxy-side slice**, `NodeRuntime` — deliberately NOT `ServerAdapter`: implementing the full interface would mean providing `worlds()`/`ui()`/`events()`/`menus()`/`npcs()`/`decor()`, six subsystems a proxy has no way to honour, and `SexidiumCore.start()` would call `worlds().start()` on the no-op it was handed. The proxy never constructs a `SexidiumCore`; it consumes `core/network` directly (node registry, transfer tickets, world placements).

### 3.1 Bootstrap (`SexidiumVelocityPlugin`)

Constructor work is registration only (`@Subscribe` on the Velocity event manager); real init runs on `ProxyInitializeEvent`: `new VelocityNodeRuntime(proxy, logger, dataDirectory, plugin)` → log node id + capabilities (one loud warning when `network.node.role` is not `proxy`, i.e. ROUTER capability missing) → `connectDatabase()`.

`connectDatabase()` opens the SHARED network database and starts consuming player routes. Failure here is degraded-but-alive: players still connect and reach the lobby, placement handoffs just do not happen. A `database.type: sqlite` on the proxy gets an explicit error (a network shares ONE database; SQLite cannot be seen by two processes) rather than a generic "driver missing" that would send an operator hunting a jar that is absent on purpose. From the database it builds `DbTransferService` (transfer tickets, claim lease, circuit breaker) and `TransferConsumer`, polled every `network.route-poll-millis` (default 1000 ms, floor 250).

Shutdown (`ProxyShutdownEvent`): stop the registration heartbeat, close the database. The proxy holds no worlds to save.

### 3.2 What the runtime provides

| SPI | Velocity impl | Notes |
| --- | --- | --- |
| `NodeRuntime` (root) | `VelocityNodeRuntime implements NodeRuntime` | identity/config/logger/resources/scheduler/messages/console + `onlinePlayers()` as `NetworkPlayer`s |
| `ConfigurationAdapter` | `YamlConfigurationAdapter` | the proxy's own `config.yml` under its data dir |
| `LoggerAdapter` / `MessageAdapter` / `CommandSource` | `Velocity…Adapter` / `VelocityConsoleSource` | console language from `messages.console-language` |
| `SchedulerAdapter` | `VelocitySchedulerAdapter` | Velocity's scheduler drives heartbeats/polls; ticks are approximated, so nothing tick-critical lives here |
| `CommandDispatcherAdapter` | console dispatch | runs PROXY commands only; a backend-bound command must name its node over the message bus instead of silently running a same-named proxy command |

### 3.3 The jobs only the proxy can do

- **Backend directory** (`BackendDirectory`) — derives the backend list from LIVE `network_nodes` rows (heartbeat-driven) instead of a static `velocity.toml` `[servers]` block, so "adding a worker" is starting it up, nothing else. It registers only nodes that published an address and skips ROUTER-capable rows — registering the proxy itself as a backend would let the least-loaded fallback send players through their own front door in a loop. Nodes it did not register are never unregistered.
- **Login gate** (`PreLoginPlanner` + `VelocityAuthGate`) — the proxy half of the Discord-link auth flow, answering the pre-login event directly against the shared database because the login gate cannot tolerate an RPC hop to a possibly-down lobby.
- **Initial routing** (`PlayerChooseInitialServerEvent`) — a live experience world held by the connecting player short-circuits straight to its host worker (read-only, LOADED-with-live-lease placements only, never a DRAINING node); everything else lands in the lobby, which is always correct if sometimes an extra hop.
- **Kick redirect** (`KickedFromServerEvent`) — a player kicked from a backend goes back to the lobby instead of off the network, and any transfer ticket in flight for them is cancelled first (without which a rolling restart re-fires a stale ticket and bounces the player straight back into the server that just kicked them). This listener is why restarting a worker is routine instead of an outage.
- **Transfer consumer** (`TransferConsumer`) — claims and executes cross-node player-move tickets written by backends into `player_transfers`.

---

## Keeping this current

Source of truth (the doc is a derived view): the SPI under `packages/core/src/main/java/com/sexidium/core/platform/` (+ `platform/model`, `platform/noop`) and the unified world layer `packages/core/src/main/java/com/sexidium/core/world/` (`AbstractWorldControl`, `WorldNaming`); the adapter trees `packages/module-paper/src/main/java/com/sexidium/paper/` and `packages/module-velocity/src/main/java/com/sexidium/velocity/`; `module-paper/.../plugin.yml`; and the golden surface pair `packages/core/src/testFixtures/java/com/sexidium/core/testing/AbstractCoreApiSurfaceTest.java` + each module's `src/test/resources/golden/core-api-surface.txt` (§1.9). Update this doc in the **same change** that touches those files. Triggers: a new SPI interface or `ServerAdapter`/`NodeRuntime` member; a new/removed adapter class in either module tree; a signature/behavior change in any contract or capability default; a `Capability` constant added, or a probe answer changing; a golden core-api-surface entry added or removed; a `worlds.*`/`auth.*`/permission config key added or removed.
