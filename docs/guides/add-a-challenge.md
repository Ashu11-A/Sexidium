# Base prompt: adding or modifying an experience Challenge

You are working on Sexidium's **experience challenges** — the composable survival twists (Double Drops,
Shared Life, Classic Skyblock, …) a player mixes into one persistent world. Read this whole file before
touching code; the reference for how the pieces behave is [experiences.md](../gameplay/experiences.md). Everything
below lives in the platform-agnostic core (`packages/core`) — a challenge **never** imports Bukkit or
NeoForge types; platform work goes through the SPI seams (see [Prompt.platform.md](add-a-platform-capability.md)).

> **Adapting a YouTuber format?** Most challenges here come from one. Do the research and the
> server-safe bounding first: [Prompt.youtube-challenge.md](research-a-youtube-challenge.md).

## Key files

| Concern | File(s) |
|---|---|
| Challenge implementations | `packages/core/src/main/java/com/sexidium/core/game/experience/challenges/<Name>Challenge.java` |
| Base class (hooks + helpers) | `…/game/experience/Challenge.java` |
| The catalog (single source of truth) | `…/game/experience/ChallengeCatalog.java` |
| Host + lifecycle | `…/game/experience/ExperienceGame.java`, `ExperiencePersistence.java`, `ExperienceService.java` |
| Composition pipelines | `…/game/experience/compose/` — `DropPipeline`, `DropContext`, `BlockBreakService`, `SweepLootSink`, `StackMergeService`, `DamagePipeline`, `HealthModel`, `MobRegistry`, `ExperienceStats`, `ChallengeRegistry` |
| Scoreboard HUD (right-hand panel) | `…/game/hud/HudContext.java`, `HudContributor.java`, `GameHud.java` |
| World types (map-generating challenges) | `…/game/experience/ExperienceWorldType.java`, `ExperienceSetup.java` |
| World-gen engine | `…/core/world/gen/` — `StructureBuilder`, `LootTable`, `TreeSpec`; `…/world/SafeSpawn.java`; Paper `VoidChunkGenerator` |
| Chest GUI (builder / edit grids) | `…/core/menu/ExperienceMenu.java` (+ `MenuSupport`, `MenuService`) |
| Menu icons | `…/core/menu/MenuArt.java` (`CHALLENGE_ICON_IDS`), `MenuArtIcons.java` (`CHALLENGE_ICON_MODELS`) |
| Config | `packages/core/src/main/resources/config.yml` → `experiences.modes.<id>:` |
| i18n | `…/core/i18n/MessageKey.java` + `packages/core/src/main/resources/lang/en.properties` / `pt.properties` (**both**, the server is bilingual) |
| Drift-guard tests | `packages/core/src/test/java/com/sexidium/core/game/experience/ChallengeCatalogTest.java`, `…/menu/MenuArtCoverageTest.java` |

## How to add a challenge

1. **Create** `challenges/<Name>Challenge.java` extending `Challenge`; constructor calls
   `super("<id>", "<Display Name>")`. Ids are lowercase, no separators.
2. **Register** it in `ChallengeCatalog`'s static block:
   `register("<id>", "<Display Name>", "<vanilla_icon_item>", "<one-line description>", <Name>Challenge::new)`.
   The description is what a Bedrock player sees (flat tap-grid, no hover tooltip) — make it one plain line.
3. **Bump `ChallengeCatalogTest`** — it asserts the exact catalog size and the id set; the build fails
   until you update it.
4. **Add the icon id** to `MenuArt.CHALLENGE_ICON_IDS` and a model mapping in
   `MenuArtIcons.CHALLENGE_ICON_MODELS` (plus optionally a greyscale `_disabled` twin for the
   unselected-tile state). `MenuArtCoverageTest` fails the build if the catalog and the icon table drift.
   The GUI tiles themselves appear automatically — the builder/edit grids render
   `ChallengeCatalog.selectable()`, so there is **no menu layout code to write**.
5. **Add a config block** `experiences.modes.<id>:` in `config.yml`. Read it with the inherited
   `cfg().getInt(configPath("my-key"), default)` — `configPath` prefixes `experiences.modes.<id>.`.
   Policy: **no artificial caps, fun-first** — cap only what would genuinely break the server, and keep
   those caps high (see the existing blocks for tone).
6. **Implement the mechanic** using the lifecycle below.
7. **Document** it: add a row to the challenge table in [experiences.md](../gameplay/experiences.md) and, if you added
   config keys, its config section.

## Lifecycle and the composition rules

Order per match: `attach(host)` → `register(ChallengeRegistry)` → `onStart(participants)` → events →
`onStop()`. `register` runs **before any** `onStart`, so publish capabilities there
(`registry.publish(MyService.class, impl)`) and fetch siblings' in `onStart` via `service(Class)` /
`sibling(Class)` — this makes challenge interop order-independent.

- **Never destroy or replace a block without asking the guard.** Every mode that edits blocks goes through
  `BlockBreakService`: `mayModify(world, position)` for one-at-a-time edits, `breakableTypes(set)` for bulk
  sweeps, `guard().preservedValues()` for whole-chunk rewrites, `guard().isProtected(type)` when picking a
  type to erase. Do **not** keep a private protected list — world integrity (the End portal frame, bedrock,
  admin blocks) is decided once in `BlockGuard` for every challenge. See
  [world integrity](../gameplay/experiences.md#world-integrity-blockguard).
- **An experience is three worlds, so never assume `world()` is the one you are acting on.** Positioned
  calls route themselves (a position carries its world name), but the world-scoped ones —
  `isChunkLoaded`, `loadChunk`, `convertChunk`, `minBuildHeight`/`maxBuildHeight` — answer for whichever
  adapter you called them on. Get the right one with `world().inWorld(worldName)`, and key any per-chunk
  or per-position state by **world as well as coordinates**: the Nether's chunk (5, 5) is not the
  Overworld's. Getting this wrong does not fail loudly — the mode just quietly stops working past a
  portal. See [all three dimensions](../gameplay/experiences.md#working-in-all-three-dimensions-worldadapterinworld).
- **Never handle loot/damage yourself if a pipeline exists.** Loot goes through `DropContributor`s
  (`registry.dropContributor(...)`, phases `GENERATE → TRANSFORM → FILTER → SINK` on the shared
  `DropContext`); damage through `DamageContributor`s; health through `HealthSource`s; block
  placement/destruction arbitration through `registry.blockVeto(...)`. This is what makes "multiplier ×
  break-all × randomizer" compose instead of fight. Only use the typed `on…` event hooks
  (`onBlockBreak`, `onPlayerMove`, `onMobDamage`, …) for mechanics no pipeline models.
- **Experiences are open-ended and never eject.** There is no elimination: a lethal condition is
  `softReset(player)` (heal + respawn in the dimension they are in) or `kill(player)` (a real death; the
  host redirects the respawn back into the right dimension). Do not teleport players out.
- **Timers/overlays** must go through the inherited `runTimer`/`runLater`/`track(...)` — they are
  attributed to the challenge's binding, so live add/remove of a challenge (experience editor, Chaos
  cycle) cancels them without ending the match.
- **Persistent shared state**: use the `stateInt`/`setStateInt`/`stateString`/`stateHas`… helpers. They
  read/write the experience's `state.yml` inside the world folder and survive restarts. Never keep
  must-survive state only in fields.
- **High-volume drops**: emit through the pipeline (which streams big payouts — `DropContext.spreadOver`)
  or through `SweepLootSink`/`StackMergeService` for bulk sweeps. Never `world.dropItem` thousands of
  entities in one tick.
- **Loot correctness**: block loot is the platform's real, tool-aware loot
  (`WorldAdapter.naturalDrops(pos, breaker)`); probabilistic tables are *sampled* by
  `DropContext.multiplySampled` — do not hard-code drop lists.

## Scoreboard (the player's right-hand panel)

There is exactly **one** per-player panel per match. Contribute lines with
`registry.hud(this::describeHud)`; the method receives a `HudContext`:

```java
private void describeHud(HudContext context) {
  context.line("<gold>My stat:</gold> <white>" + value + "</white>");   // MiniMessage fragment
  if (context.debug()) {                                                // gated debug section
    context.debugHeader(displayName());
    context.debugStat("internal thing", someCounter);
  }
}
```

`context.compact()` is true when the viewer chose the trimmed view — show only their own info then.
Never create your own boss bar / action bar for status; the unified panel replaced those.

## Map-generating challenges (world generation)

> **First, check you need a challenge at all.** If the world only has to *generate differently* — a
> vanilla preset such as Superflat, Large Biomes or Amplified — that is **not** a challenge. Add an
> `ExperienceWorldType` entry carrying a `WorldTerrain` instead (see
> [Prompt.worlds.md](work-on-worlds.md)); it needs no challenge class, no catalog row, no icon table entry
> and no config block. Write a challenge only when *your code* places the blocks.

If the challenge **builds the world itself** (SkyBlock-style):

1. Override `requiresVoidWorld()` → true (and `requiresVoidNether()` if the Nether must mirror). This is
   read at world-creation time from the catalog, before any instance exists — it must not depend on state.
2. Add an entry to `ExperienceWorldType.generatedMaps()` naming your challenge id. That is what makes map
   generators **mutually exclusive**: they are picked one-at-a-time in the "Choose World" screen (in the
   *Generated maps* row) and are excluded from the multi-select grid (`ChallengeCatalog.selectable()`).
   The entry is automatically `fixedAtCreation()`, so it can never be applied to an existing world.
   `ExperienceWorldTypeTest` guards this wiring.
3. Build geometry through the engine, never raw loops in the challenge: `StructureBuilder`
   (slab/stack/tree/chest), `LootTable.builder().guaranteed(...).rolls(min,max).pool(item,min,max,weight).build()`
   for chest contents, `TreeSpec` for trees. Keep the geometry in a **pure, host-free class**
   (`SkyblockIslands.java` is the model) so it is testable against a recording `WorldAdapter`
   (`SkyblockIslandsTest`).
4. **Load chunks before placing blocks** (`WorldAdapter.loadChunk`; see `SkyblockIslands.ensureChunks`) —
   a fresh void world silently drops `setBlock`s into unloaded chunks.
5. Guard first-build with a state key (`stateHas("built")`) so re-entry never rebuilds over player work;
   if you change an existing layout, write an **in-place repair** that only touches still-empty/original
   blocks (`SkyblockIslands.netherRepair` is the pattern).
6. Player placement: rely on `WorldAdapter.safeSpawnPosition()` / the host's entry-spawn resolution —
   never teleport to raw coordinates that may be void/underwater.

## Checklist before you finish

- [ ] `ChallengeCatalogTest` updated (count + ids + void flags if applicable)
- [ ] `MenuArt.CHALLENGE_ICON_IDS` + `MenuArtIcons.CHALLENGE_ICON_MODELS` updated
- [ ] `config.yml` block added; keys read via `configPath(...)`
- [ ] Pure logic extracted and unit-tested (see `LayerDeckTest`, `SphereSweepTest`, `SkyblockIslandsTest` for the pattern)
- [ ] HUD contributor registered; no private boss bars
- [ ] Every block edit validated through `BlockBreakService` (no private protected list)
- [ ] Verified in the **Nether and the End**, not just the Overworld: world-scoped calls go through
      `inWorld(...)`, and per-chunk/per-position state is keyed by world
- [ ] [experiences.md](../gameplay/experiences.md) table + config section updated **in the same change**
- [ ] `./gradlew clean build` green, and `scripts/remote.sh test gradle scripts` green on the
      deployment host ([deployment.md §8](../operations/deployment.md#8-running-tests-remotely))

---
*Keeping this current: this prompt tracks `ChallengeCatalog`, `Challenge`, the `compose/` package,
`ExperienceWorldType`, `world/gen/`, `ExperienceMenu`, `MenuArt(Icons)` and the `experiences.*` config
namespace. Update it in the same change that alters any of those workflows.*
