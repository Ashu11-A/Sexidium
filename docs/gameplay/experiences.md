# Experiences

A player-built **Experience** is a single, persistent, survival-only world that runs **any combination** of challenge twists at once (e.g. XP-Health + Shared-Inventory + Shared-Life together). It is open-ended — no win/lose, never auto-ends, death never ejects — and is owned by its creator, who alone can delete it. The host (`ExperienceGame`) composes the selected twists (`Challenge` objects) over a set of ordered pipelines and a typed capability registry so independent challenges interoperate instead of clobbering each other, persists everything to flat `.yml` files inside the world folder, and renders one unified scoreboard HUD. There is **no standalone per-twist game mode** — every twist only ever runs inside an Experience.

Everything here lives in the platform-agnostic **core** (`packages/core`) under `com.sexidium.core.game.experience` (host + manager + service + store), `…experience.challenges` (the twists), `…experience.compose` (the interop layer) and `com.sexidium.core.game.team` (the minigame team system). It talks only to the core SPI (`PlayerAdapter` / `WorldAdapter` / `MobHandle` / `InventoryAdapter` / `UiAdapter`), never to Bukkit or Minecraft directly.

Related docs (linked, not duplicated): the [game framework](../architecture/game-framework.md) (`Game`/`GameManager`/event router), the [menu system](../interface/menus.md) for the builder/manage/browse chest GUIs, [lobby, worlds & social](lobby-worlds-and-social.md) for lobby nav items, host match-lobbies (`/lobby`), lobby NPCs (`/sx admin npc`), world leasing and the persistent-world layout.

---

## Overview: what an Experience is

- **One world, many twists.** Started via `/sx start experience <challenge…>` (e.g. `xphealth sharedlife sharedinventory`) or the lobby experience-builder GUI. `ChallengeCatalog.create(ids)` resolves the request into challenge instances in order, deduped, skipping unknown ids.
- **Open-ended.** No win/lose, never auto-ends, death never ejects. `ExperienceGame.handlesOwnRespawn() == true`, so `GameManager` re-spawns a dead player **in the experience world** (in survival) instead of releasing them to the lobby; a lethal twist condition becomes `softReset(player)` (heal + respawn in place), not an elimination. The only exit is `/leave`/`/exit`. `allowsWorldChange` keeps participants in until they leave.
- **`ExperienceGame extends AbstractGame implements ChallengeContext`** (`MODE_ID = "experience"`, `ExperienceGame.java:56`). It does **not** extend `BaseTimedGame` and has **no** duration / elimination / winner logic — challenges are composed objects, not subclasses. The per-tick driver (`composeTick`) runs `mobRegistry.tick` + `healthModel.writeAll` + HUD-toggle polling.
- **HUD toggle** (`ExperienceGame.java:499`): PC double-tap sneak (two presses within 350ms); mobile look straight up (pitch ≤ −75° sustained ~1s, polled each tick). Per-player, stored in `ExperienceHud.hidden`.

---

## Challenge catalog (the actual set — 27)

`ChallengeCatalog` (`ChallengeCatalog.java:49`) is the single source of truth: `id → displayName → icon → one-line description → factory`. `ChallengeCatalogTest` (`ChallengeCatalogTest.java:16`) asserts **exactly 27** entries and asserts the removed ids are absent. The description exists for the Bedrock chest GUI (a flat tap-grid with no hover tooltip). Ids are normalized (lowercased/trimmed) so `xphealth` == `XpHealth`.

| Id | Display | Icon | Twist |
|---|---|---|---|
| `doubledrops` | Double Drops | `emerald` | Blocks and mobs drop double loot (`TRANSFORM`-phase drop multiplier) — the block's **real tool-aware vanilla loot**, with probabilistic tables sampled across the multiplier, **poured out over time** at high multipliers. See [Real vanilla loot](#real-vanilla-loot-tools-and-probabilities) and [Streamed payouts](#streamed-payouts). |
| `randomizer` | Randomizer | `crafting_table` | Every block's drop is remapped to a random item drawn from the **whole item registry** (`ServerAdapter.allItems()`), freshly shuffled per session; loot drops into the world. Rotation OFF by default. |
| `sharedlife` | Shared Life | `red_dye` | All players draw from one shared health bar. |
| `sharedinventory` | Shared Inventory | `chest` | All players share one live storage inventory (authoritative slot model + batched changed-slot sync — no full-copy storms). |
| `xphealth` | XP Health | `experience_bottle` | Your XP level **is** your health. |
| `shrinkingachievements` | Shrinking Achievements | `barrier` | The world border shrinks on each advancement. |
| `breakonebreakall` | Break One Break All | `diamond_pickaxe` | Breaking a block flags that **type** as broken everywhere; a budgeted render-distance sweep removes it around every player. |
| `chunkbreak` | Chunk Break | `iron_pickaxe` | Breaking a block removes every other block of that type **in that one chunk** (one-shot, chunk-scoped; no persistent set). |
| `blockdeleter` | Block Deleter | `coal_block` | Breaking a block deletes that whole type nearby (never repeats). |
| `randomchunks` | Random Chunks | `grass_block` | Each entered chunk converts to one random block. |
| `walkingblocks` | Walking Blocks | `magma_block` | The solid block directly under your feet transforms into a random palette block as you walk — air/liquid is never filled, and each position is changed only **once** (permanent). |
| `chained` | Chained Together | `chain` | Players are leashed to a shared centroid + a shared death link. |
| `cleave` | Total Cleave | `netherite_sword` | Each swing cleaves every nearby mob; tools wear fast. |
| `growing` | Endless Growth | `slime_block` | Players grow over time — bigger is stronger + tankier, no cap. |
| `jumpenchants` | Jump Enchants | `enchanted_book` | Every jump enchants a random inventory item. |
| `mobduplication` | Mob Duplication | `slime_ball` | A mob hit by a player can duplicate. |
| `lookmultiplies` | Look Multiplies | `ender_eye` | Everything in your crosshair multiplies on a repeating look-tick (mobs, dropped items, arrows). A shared, persisted **multiplier** climbs as the party clears an item milestone ladder — shown on the XP bar above the hotbar, next item on the info panel. Mob spawns bounded by `max-copies-per-tick`. |
| `jumpmultiplies` | Jump Multiplies | `rabbit_foot` | **Every jump duplicates every entity near you** — indiscriminately: mobs, dropped items, projectiles in flight, primed TNT and tamed pets, all at once (bosses are opt-in). Placed blocks never duplicate (a TNT *block* is inert; lit TNT is not). Copies are full NBT clones, so aggro, taming, baby state, equipment and stack contents all carry over. The trigger is the real jump **input** (crits and jumping out of water count; being knocked upward does not), never a Y-delta. Bounded by `max-clones-per-jump` / `max-per-tick` / `max-live-entities`; at the live ceiling it refuses and shows "saturated". See [Jump Multiplies](#jump-multiplies-jumpmultiplies). |
| `randomskyblock` | Random Skyblock | `grass_block` | Random-Item-Giver one-block skyblock: **VOID world** + one special block at spawn; breaking it gives a weighted random item (60-item pool), regenerates the block a level richer, and spawns a loot chest every `blocks-per-chest`. Broken count under the nameplate (`PlayerAdapter.setBelowName`); sidebar shows level + next-chest. |
| `randommob` | Random Mob | `zombie_spawn_egg` | A random mob (25-type default) spawns beside each player on a timer (`WorldAdapter.spawnMob`). |
| `randomdrops` | Randomized Drops | `dropper` | Every block break (GENERATE drop contributor) and mob kill (`onEntityDeath`) drops a fresh random item — not a fixed per-type remap. |
| `randomevents` | Random Events | `firework_rocket` | Drives the standalone `com.sexidium.core.game.experience.events` engine (64 built-in events, balanced 50/25/25 neg/neutral/pos) on a random interval; sidebar "next event in" countdown. New `WorldAdapter.strikeLightning` seam. |
| `classicskyblock` | Classic Skyblock | `grass_block` | The vanilla SkyBlock: **VOID world** + L-shaped grass/dirt island (81 dirt), one oak tree, starter chest (ice + lava bucket), a distant sandstone island (cactus + seeds/portal-blocks chest) — all via `StructureBuilder`/`placeChest`. Its linked **Nether is void too** (`requiresVoidNether()` → `voidNether` threaded to `VoidChunkGenerator`) and is a true **mirror**: same L footprint, a **walled lava basin**, a **ready-lit portal on a pad beside the island**, and a distant blackstone island whose chest rolls a `LootTable` of Nether essentials. See [Classic Skyblock geometry](#classic-skyblock-geometry-skyblockislands). |
| `randomlayers` | Random Layers | `dirt` | SkyBlock-style **VOID world** (`Challenge.requiresVoidWorld()` → `acquireOrCreatePersistent(voidWorld)` → Paper `VoidChunkGenerator`): the challenge builds a starting island (1 dirt + 2 stone) and a distant chest island of SkyBlock items (`WorldAdapter.placeChest`), then the one-chunk column grows every Minecraft day by `layers-per-day` (3) random layers (`LayerDeck` → block slab / TNT+pressure-plate trap / mob-spawn slice via `WorldAdapter.spawnMob`). Its linked **Nether is void too** (`requiresVoidNether()`) and grows a **column of its own** on the same clock, from a Nether palette and Nether mobs, with its own starting platform and its own frontier — before this the Nether was ordinary terrain and the mode simply stopped existing past a portal. Info panel shows the layer count for the dimension the viewer is standing in, the current day, and time to the next layers. |

> **Four of these are not free-choice twists.** `classicskyblock`, `randomskyblock`, `randomlayers` and `layereddimensions` each **generate the world**, so they are chosen — one at a time — as a **map type** instead of being ticked in the challenge grid. See [World type (map selection)](#world-type-map-selection) below.

| `omnichunk` | Omni Chunk | `chiseled_stone_bricks` | Everything you do to a block happens in the same slot of every chunk. Backed by a **commit log** (`ChunkLedger`): each place / break / item-use is a commit against a chunk-local slot, kept in order and squashed when the slot is overwritten. Commits are applied **naturally** (`WorldAdapter.setBlockNatural`, physics on) so golems spawn, fluids flow and lit TNT really explodes; a newly seen chunk **replays the whole log in order**. Scope follows each player's **render distance**. See [Omni Chunk's commit engine](#omni-chunks-commit-engine). |
| `layereddimensions` | Layered Dimensions | `deepslate` | One 16×16 column of stacked layers in **every** dimension, sharing one layout and one seed. See [Layered Dimensions](#layered-dimensions-layereddimensions). |
| `deathresets` | Death Resets | `totem_of_undying` | Hardcore with no goals: any death wipes everyone and **regenerates the world**. Forces hardcore on. See [Death Resets](#death-resets-deathresets). |

**Removed** (asserted absent in the test): `lavafloor`, `midastouch`, `fleeingblocks`, `nogreen`, `jumpgravity`. The YouTuber-style twists (`breakonebreakall` … `randomlayers`) are now **first-class catalog entries**, not an addendum.

Each challenge class lives in `…game/experience/challenges/` as `*Challenge.java` (the old `game/modes/experiences/*Game.java` path is dead). Each reads config from `experiences.modes.<id>.*`.

### "No artificial limits" design defaults

Several twists deliberately uncap for fun: `jumpenchants` defaults to the **entire** enchant set (`enchants: []`, `max-level: 30`); `blockdeleter`/`randomchunks`/`walkingblocks` default to a comprehensive ~85-block palette (`ChallengePalettes.COMPREHENSIVE_BLOCKS`); `growing` has no size cap and an uncapped Strength amplifier (Resistance stays capped so there are still stakes); `doubledrops` keeps `max-drops-per-break: 65536` purely as an overflow guard; `jumpmultiplies` lets the exponential blow-up run and caps only what would genuinely break the server (`max-clones-per-jump: 1024`, `max-per-tick: 256`, `max-live-entities: 4000`), refusing to clone — and saying so on the panel — once an area is saturated.

---

## World type (map selection)

`ExperienceWorldType` (`game/experience/ExperienceWorldType.java`) is the single source of truth for **which world an experience runs and where the player wakes up in it**. Exactly one type per experience, which is what makes the map-generating twists mutually exclusive — before this they were ordinary grid entries, so two of them could be ticked and would then build two maps into the same void world.

| Group | Type | Id | Dimension | Terrain | Challenge | What it is |
|---|---|---|---|---|---|---|
| Start dimension | Normal World | `normal` | Overworld | normal | — | Standard terrain (the default). |
| Start dimension | Nether | `nether` | Nether | normal | — | Standard terrain; the player starts in **this experience's own Nether**. |
| Start dimension | The End | `end` | End | normal | — | Standard terrain; the player starts in **this experience's own End**. |
| World generation | Superflat | `superflat` | Overworld | `SUPERFLAT` | — | Vanilla flat preset — flat layers to the horizon. |
| World generation | Large Biomes | `largebiomes` | Overworld | `LARGE_BIOMES` | — | Vanilla preset — normal terrain, enormous biomes. |
| World generation | Amplified | `amplified` | Overworld | `AMPLIFIED` | — | Vanilla preset — extreme mountains and canyons. |
| Generated map | Classic Skyblock | `classicskyblock` | Overworld | normal | `classicskyblock` | Void world, built by the challenge. |
| Generated map | Random Skyblock | `randomskyblock` | Overworld | normal | `randomskyblock` | Void world, built by the challenge. |
| Generated map | Random Layers | `randomlayers` | Overworld | normal | `randomlayers` | Void world, built by the challenge. |

- **Start dimensions cost nothing extra**: every experience already gets linked `_nether`/`_end` siblings (`PaperWorldControl.ensureExperienceSiblings`). The type only decides which one the *first* entry teleports into; a returning player always resumes at their saved spot.
- **Terrain presets are vanilla world generation, not challenges.** `SUPERFLAT`/`LARGE_BIOMES`/`AMPLIFIED` carry a `WorldTerrain` that rides `WorldGeneration` → `WorldSettings.terrain` → `WorldCreator.type(...)` on Paper (`PaperWorldControl.nativeWorldType`). They add **no** challenge, so they never touch the challenge grid, and they leave the linked Nether/End on normal generation exactly as vanilla does. A VOID world ignores the preset — there is no natural terrain to shape.
- **`fixedAtCreation()`** is the rule that decides what an existing experience may change: any type that dictates generation (a preset **or** a generated map) is baked into the terrain the moment the world exists, so it can be neither switched to nor away from later. Only the three plain start dimensions stay re-pointable (`ExperienceService.updateWorldType`).
- **Generated maps own their terrain.** Picking one adds its challenge automatically, and `ExperienceService.challengesFor(requested, type)` strips every *other* map challenge from the request. That one helper is the gate every entry point goes through (builder, live edit, `/sx start experience`), so a two-generator experience is unrepresentable.
- **Storage**: the `experiences.world_type` column (added by `SchemaMigrator`; older rows read null). `Experience.type()` resolves it through `ExperienceWorldType.resolve(challenges, worldType)` — a running map challenge outranks the stored value, so experiences created before the selector existed still report the right type.
- **Transport into a match**: `ExperienceSetup.toModeArgs(challengeIds)` appends a `world:<id>` token to the mode args (alongside `keepinv:<bool>` — see [Keep Inventory](#keep-inventory-per-experience-all-dimensions)). `ExperienceGame` strips the option tokens back out (`ExperienceSetup.stripArgs`) and keeps the values; because mode args are part of the persisted match state, the chosen start dimension survives a restart.
- **Editable after creation?** Only the start dimension, and only on a non-generated map (`ExperienceService.updateWorldType`) — the terrain of a generated map already exists. It applies the next time the experience opens.
- **GUI**: the builder and the manage/edit screens carry a **World** tile that opens the single-choice chooser (`ExperienceMenu.openExperienceWorldType`, `MenuService.openExperienceWorldType`). That screen is one labelled row per group — *Start dimension* / *World generation* / *Generated maps* — with the caption tiles rendered as `MenuButton.label`s so they survive the Bedrock form projection as body text. The challenge grid itself renders `ChallengeCatalog.selectable()` (everything except the map challenges).
- **CLI**: `/sx start experience <challenge…> [--world=normal|nether|end|superflat|largebiomes|amplified]`. Naming a map challenge sets the type implicitly; naming two is rejected.

### Omni Chunk's commit engine

The naive version of this mode — copy the final block into every chunk — is wrong for everything that
makes Minecraft interesting. An iron golem exists because four iron blocks were already there **when the
carved pumpkin landed**; TNT explodes because it was lit **after** it was placed; water flows because it
was poured into a shape that already existed. **Order is the mechanic**, so the engine stores order.

- **`ChunkLedger` is a commit log.** Every place, break and recorded item-use is a `Commit` against a
  chunk-local slot (`localX, y, localZ`), appended in sequence. One log describes every chunk at once.
- **Squashing.** A `PLACE` or `BREAK` drops that slot's earlier commits — the history that led to a block
  nobody can see any more is dead weight, and replaying it would be wrong as well as slow. What survives
  is the shortest history that still reproduces the world. A `USE` does **not** squash: it is an event
  layered on the block it acts on, so "place TNT" + "light TNT" both survive, in that order.
- **Genuine application.** Commits are applied through `WorldAdapter.setBlockNatural` — physics and block
  updates **on** — so the game itself decides what happens next: golems spawn, sand falls, redstone fires,
  fluids flow. `WorldAdapter.useItemOn` re-runs an item's real effect (flint & steel on TNT spawns a real
  primed entity that really explodes; bone meal really grows the plant). `natural-placement: false`
  restores the cheap, inert copy.
- **Replay into new chunks.** A chunk nobody has seen is queued for a full, in-order replay of the log the
  moment a player comes within render distance — the same events, in the same sequence, unfolding again.
  Because the pumpkin commit is still last, the golem spawns there too.
- **Per-chunk versions.** Every chunk records **which commit it has been brought up to**. On a visit, a
  chunk already at the head is skipped; one that is behind is **fast-forwarded with exactly the commits it
  missed**; one so far behind that those commits have been trimmed off replays everything that survives (a
  re-clone rather than a pull). This is what stops a chunk being written off as "already done" while the
  world moved on. A squashed slot still reaches a stale chunk, because the commit that *replaced* it
  carries a higher sequence id and is therefore in the delta. Versions are persisted, and guarded by the
  ledger's **head hash**: if the stored hash does not match the log that loaded, every chunk is
  re-verified rather than trusted.
- **Scope follows the player's eyes**, via the shared [`PlayerRadius`](#playerradius-one-reach-for-every-player-effect)
  rules. Work drains against `blocks-per-tick`, nearest chunk first, and only already-loaded chunks are
  touched (`WorldAdapter.isChunkLoaded`).
- **Replication is continuous, not one-shot.** A `sync-period-ticks` sweep re-checks every participant's
  surroundings against the log, so chunks that stream in as players travel are picked up and a chunk that
  was not resident when its turn came gets another one. Two rules make this correct: a chunk's version is
  recorded **only once its commits have actually been applied** (recording it at queue time wrote off
  chunks that were not loaded yet, which is exactly why replication used to stop at a distance), and a
  chunk with work already queued is not queued twice.
- **Persistence.** The log is encoded into the experience's own `state.yml` inside its world folder, so
  the history travels with the world and needs no database. `ChunkLedger`/`ChunkStamp` are pure and
  host-free; `ChunkLedgerTest` and `ChunkStampTest` cover the semantics without a server.

### `PlayerRadius`: one reach for every player-following effect

Three systems used to derive "how far around a player does this reach" from
`PlayerAdapter.viewDistanceChunks()`, each with its own formula, so they disagreed about where a player's
world ends. They now all resolve a `PlayerRadius.Area` (`core/world/PlayerRadius.java`):

| Consumer | Uses |
|---|---|
| Omni Chunk replication + replay | `PlayerRadius.around(player, min, max, overscan)` → chunk offsets |
| Break-One-Break-All sweep | `PlayerRadius.blockRadius(player, …)`, then its own `max-radius-blocks` cap |
| Ground-item re-validation (`SexidiumCore`) | `PlayerRadius.blockRadius(player, …)`, capped by `revalidate.max-radius` |

The rules, in one place:

- **The player's own render distance** is the basis — Paper reports the *client's* real setting, so a
  player on a low video setting genuinely costs less.
- **Overscan** (10% by default) reaches slightly past the visible edge, so the chunk a player is about to
  walk into is already correct rather than resolving in front of them.
- **A circle, not a square.** A boundary chunk is included only when **more than 50% of its area** falls
  inside the circle — measured by sub-sampling the chunk on an 8×8 grid, which is exact enough for a >50%
  decision and far easier to reason about than analytic circle/square intersection. A chunk sitting
  exactly on the circle is a tie, and ties are excluded. The player's own chunk is always included.
- **Nearest first**, so budgeted work resolves what the player is looking at before the fringe.

Pure, deterministic, and cached per radius, so the offset table for a given render distance is computed
once for the whole server. `PlayerRadiusTest` covers the basis, the clamp, the boundary rule, the
circle-not-square property and cache stability.

### Classic Skyblock geometry (`SkyblockIslands`)

All the Classic-SkyBlock shapes and loot live in `challenges/SkyblockIslands.java` (pure, no host needed —
`SkyblockIslandsTest` drives it against a recording `WorldAdapter`); the challenge class owns only the
lifecycle. The Overworld and Nether are genuine mirrors: the same `lIsland` L footprint, four layers deep,
and a second island at the same `DISTANT_OFFSET_X` (18).

The Nether side additionally has:

- **A ready-lit portal on its own pad** (`netherPortal`, at `PORTAL_OFFSET_X = -7`, a 4×5 frame along X so
  the default `nether_portal` axis is correct). It is built **eagerly at `onStart`** — resolved through the
  `WorldAdapter.dimension(NETHER)` seam — *before* anyone travels. That ordering is the fix for portals
  appearing in mid-air over the island: with a portal already present, vanilla's search **links** to it
  instead of carving a fresh one at the arrival altitude. The pad bridges east to the island edge, so the
  arrival is walkable. If the POI search ever misses it, the void-arrival rescue below still applies.
- **A walled lava basin** (`lavaBasin`) instead of a bare source. The old build put lava on a surface cell
  next to the L's missing corner, so it poured into the void; the basin now places its floor and all four
  walls explicitly rather than inheriting them from the island shape.
- **A distant supply island** (`netherDistantIsland`) mirroring the Overworld's: 3×3 blackstone, a planted
  soul-sand wart farm, and a chest filled from `netherDistantLoot()` — the brewing/nylium/gold/portal
  essentials **guaranteed** (a void Nether has no fortresses or forests, so a bad roll would end the run)
  plus a weighted pool of Nether blocks and rarities.
- **Void-arrival rescue**: `seatInNether` no longer teleports on every entry — it only fires when there is
  genuinely no ground within 8 blocks below the player, so a normal portal arrival is left where it landed.

**Existing worlds** are repaired in place, not rebuilt: a separate `netherextras` state marker triggers
`SkyblockIslands.netherRepair`, which seals the legacy lava source (only if it is still lava) and adds the
portal and distant island **only where the world is still empty**, so nothing a player built is overwritten.

### Death & respawn dimension

An experience never ejects on death, and it never changes dimension either:

1. **Die in the Nether → respawn in the Nether.** `ExperiencePersistence` samples each living
   participant's dimension (`recordDimension`, on a 10-tick timer *and* on every damage event, since a
   death is always preceded by one). By the time the respawn fires the platform has already moved the
   player, so the dimension has to have been recorded while they were alive.
2. **No sample → the experience's chosen START dimension.** An End experience never dumps its players
   into an Overworld they did not pick.
3. **A bed or respawn anchor in the right dimension wins.** `PlayerRespawnGameEvent` now carries
   `vanillaPosition()` (where the platform intended to put them); when that is already this experience's
   world for the wanted dimension, `respawnPosition` returns null and nothing is overridden.

The redirect is applied **through the event** (`setRespawnPosition` → Paper's `setRespawnLocation`, with
the bridge moved from `MONITOR` to `HIGH` so it still takes effect) rather than by teleporting during the
respawn — a post-hoc teleport is overwritten by the platform's own placement, which is exactly why deaths
used to land back in the Overworld. A next-tick teleport remains as a backstop for platforms that cannot
honour the redirect. Challenge soft-resets (`softRespawn`) pass a null vanilla position and therefore
always resolve to the dimension spawn. `ExperienceRespawnTest` covers all six cases; Chaos worlds use the
identical rule.

### Working in all three dimensions (`WorldAdapter.inWorld`)

An experience is **three worlds** — its Overworld and its linked `_nether`/`_end` siblings — but a
challenge holds a single `world()` adapter. Most of `WorldAdapter` takes a *position*, and a position
carries its world name, so the Paper adapter already routes those to the right world (`worldFor`): a drop,
a block edit, a mob scan or an effect in the Nether acts *there*.

The exceptions are the handful of calls that describe a **world** rather than a point — `isChunkLoaded`,
`loadChunk`, `convertChunk`, `minBuildHeight`/`maxBuildHeight`. Those silently answered for the Overworld,
so any mode that used them was wrong the moment a player stepped through a portal.

`WorldAdapter.inWorld(worldName)` is the seam that fixes it: it returns the adapter for the named world
(itself when the name is blank or unresolvable, so a platform without the capability is unchanged). What
was broken and is now fixed:

| Mode | Was | Now |
| --- | --- | --- |
| **Omni Chunk** | Nether chunks were tested for residency against the **Overworld**, so their jobs were dropped as "not loaded" for ever — the mode looked dead past a portal. One shared history meant an Overworld edit also fired in the Nether and the End. | A `DimensionLog` **per world** (its own ledger, chunk versions and queued set); `experienceWorld.inWorld(job.worldName)`; changes outside the target world's build range are skipped rather than attempted (an Overworld y=200 has nowhere to land in the Nether's 0…128). See below. |
| **Chunk Break** | Swept every chunk over the Overworld's −64…320, so a Nether chunk walked 2.5× more slots than it has, nearly all out of bounds. | Per-job `inWorld(...)`, so the span is the sweeping world's own height. |
| **Random Chunks** | The "already rolled" lock was `chunk.<x>.<z>`, so every chunk in a sibling arrived pre-locked and never converted. The same-chunk short-circuit ignored the world, so a portal arrival at matching coordinates was skipped. | Key is `chunk.<world>.<x>.<z>`; the short-circuit compares worlds. |

Everything else already used `playerAdapter.world()` (the player's *actual* current world) or positioned
calls, and was verified correct in all three dimensions.

**Rule for new challenges:** if you call a world-scoped method, get the adapter from `inWorld(...)` with
the world name you are acting on, and key any per-chunk/per-position state by world as well.

#### Omni Chunk: one history per dimension

Omni Chunk keeps a separate `DimensionLog` per world — its own `ChunkLedger`, its own chunk-version map,
its own queued set. Nothing crosses between them, so a plank laid in the Overworld repeats through the
Overworld's chunks and leaves the Nether and the End exactly as they were.

This is not cosmetic. With one shared history, mining out a Nether corridor also carved the same holes
through everything you had built at home, and a dimension entered for the first time arrived pre-filled
with edits that were never made in it. Independence is now **structural** — there is no cross-dimension
state left to forget to key by world, because every piece of it hangs off `DimensionLog`
(`DimensionLogTest` guards it).

Consequences worth knowing:

- `max-changes` is a **per-dimension** budget, so a busy Overworld cannot squeeze out the Nether's history.
- Each dimension persists under its own state key (`log.<slug>`, `loghash.<slug>`, `chunkversions.<slug>`,
  indexed by `worlds`), with its own hash — so restoring or hand-editing one dimension never invalidates
  another's chunk versions. World names are slugged, since state keys are config paths.
- The scoreboard count is the **viewer's own** dimension, not a total.
- A history written before this change is adopted into the Overworld (the only dimension that was ever
  correctly replicated) and the siblings start clean.

#### Lighting a portal in every chunk

Replaying a flint-and-steel as a real use meant placing a fire block and hoping vanilla noticed the frame.
Fire is a live block with its own behaviour — it spreads, it burns out, and where a copied frame differs
from the original it lands somewhere unintended — so lighting one portal did not reliably light the rest
and could leave the copies broken.

`PortalFrame` (`world/gen/`, pure + unit-tested) finds the obsidian frame's cavity by **shape**, and
`PaperWorldAdapter.ignite` fills it with portal blocks directly. That is deterministic, has no side
effects, and is naturally **idempotent**: an already-lit frame is recognised and left alone, which matters
because a chunk can replay its history more than once. Fire remains the fallback for anything that is not
a portal frame; TNT still becomes a real primed entity.

### Layered Dimensions (`layereddimensions`)

A **map type**, so it is mutually exclusive with the SkyBlocks. Every dimension is one 16×16 column of
stacked slabs sharing a single layout, built once and deterministically from the experience's own seed.

| Dimension | What it is |
|---|---|
| Overworld | `layers` × `layer-height` of rock, dirt, ores — the column you spawn on top of |
| Nether | the **same column in Nether materials**, from the same seed, so the two read as one world seen twice |
| End | deliberately an **ordinary End** — after two dimensions of digging, the reward is somewhere normal |

Layers are not just material: a `hazard-chance` share of them carry monster spawners set into the slab,
liquid pools, TNT under pressure plates, magma floors, or cobwebs that stall your descent right where the
spawners are. **Layer 1 is always plain** — you have to be able to stand somewhere when you arrive. The
deepest Nether layer is an already-active **End portal**, so digging all the way down is what opens the
way onward; reaching the Nether at all stays conventional (build an obsidian frame, light it).

Death always returns you to the **Overworld** column, whichever dimension killed you — via the new
`Challenge.respawnPosition(player)` hook, which overrides the usual "respawn where you died" rule. The
first challenge with an opinion wins, so two cannot fight over one respawn.

**The height caveat, stated plainly:** the dimensions are not the same size. The Overworld is 384 blocks
tall, so 100 layers of 3 fit easily. The **Nether is only 128**, so ~40 layers of 3 is all that
physically fits. `LayeredColumn.Spec` clamps to what the dimension has room for rather than asking the
platform to build outside its range (an error, not a no-op), reports `isClamped()`, and shows the real
count on the debug HUD. Lower `layer-height` to get the Nether closer to the full count — 2 gives ~63,
1 gives ~127. `LayeredColumnTest` locks this arithmetic down.

`LayeredColumn` (`world/gen/`) is pure and unit-tested: the plan — how many layers fit, where each starts,
which are hazards, which is the floor — is computed with no world attached, and the challenge just walks
it. `WorldAdapter.placeSpawner(pos, entityType)` is the new seam; a spawner placed by `setBlock` alone is
inert, so a "dangerous" layer would have been decorative.

### Jump Multiplies (`jumpmultiplies`)

A free-choice twist with one rule: **every jump duplicates every entity around the jumper, once.** It is
deliberately untargeted — unlike [`lookmultiplies`](#challenge-catalog-the-actual-set--27), which copies
whatever is in the crosshair, this copies *everything* in range in the same instant, so a jump taken beside a
herd, a dropped stack and a creeper doubles all three. Repeated jumps compound, which is the entire point:
the count is exponential in jumps, and the mode's real antagonist is entity population rather than death.

**The trigger is the jump input, not upward motion — and that has to be a real seam.** `jumpenchants` detects
jumps from a movement sample (`jump-detection-y-delta`), which is acceptable where a false positive costs one
enchantment roll. Here it would be wrong: mob knockback, an explosion punt and a piston all produce the same
upward delta, and "I was launched, I did not jump" is the rule players are being asked to play around. The
challenge therefore listens to a dedicated core event, `GameEvents.PlayerJumpGameEvent`, with
`jump-cooldown-ms` as a debounce only, so one physical jump can never fire twice.

Paper's `PlayerJumpEvent` alone is **not quite** that signal, and the bridge says so out loud: Paper raises it
from the movement-packet handler on the predicate "was on the ground, is now airborne, Y went up", which
cannot tell a keypress from a creeper punt. `PaperEventBridge` therefore also watches `PlayerVelocityEvent`
and records the last time the *server* pushed each player upwards — a player's own jump never passes through
there — and vetoes any jump inside ~250 ms of such a launch. The jump listener is `MONITOR` and the event is
**never cancelled**: Paper rubber-bands a refused jump back to `getFrom()`, which reads as lag, not as a rule,
which is also why `PlayerJumpGameEvent` is non-cancellable in core. A jump taken while a Death-Resets world
reset is running is suppressed by `ExperienceGame`, so a doomed world never pays for hundreds of spawns.

| Situation | Duplicates? |
|---|---|
| Normal jump | **yes** |
| Jump-attacking — a **critical hit** | **yes**; the crit *is* a jump, so fights become mass-spawns |
| Jumping out of water onto land | **yes** |
| Swimming / rising in water | no |
| Knocked upward by a mob, an explosion or a piston | no |
| Falling, elytra, or a mount that jumps | no |

**Entity layer only.** Mobs (passive and hostile, babies included), dropped item entities, in-flight
projectiles, primed TNT and tamed pets are eligible, and bosses are an explicit opt-in; **placed blocks are
never touched**, so a TNT *block* on the ground is inert while the same TNT once lit duplicates like anything
else. Players are never copied — there is no `DuplicableKind` for them, so it is not a switch anyone can flip.
Item entities copy **per entity, not per item**: a stack of 64 is one entity and yields one more entity
carrying the same stack, which is what makes "drop the stack out as individual items first, *then* jump" the
mode's signature exploit rather than a bug.

**Clones are real copies, not same-type respawns.** The sweep goes through a dedicated platform seam,
`PlayerAdapter#duplicateNearbyEntities(radius, copiesPerEntity, maxSpawns, kinds)`, and Paper implements it
with `Entity#copy(Location)` — a full NBT-level clone with a fresh UUID. That is what delivers aggro, taming
and its owner, baby state, equipment, custom names, item-stack NBT, a projectile's shooter and a TNT fuse in
one call, for every entity kind. The existing `spawnMob`/`spawnItemEntity`/`duplicateLookedAtEntity` seams all
rebuild from a type string or an `ItemKey` and lose every one of those, which is why this needed its own seam
rather than a flag on an old one. Two details on top of the clone: the attack target is re-asserted explicitly
(`copy()` is not documented to carry it), and primed-TNT fuses are **jittered by a tick per copy**, because N
copies at one point with one fuse is a single N-fold blast rather than a chain. The same fix was applied to
`MobHandle#duplicate()`, so `mobduplication` is faithful too.

**Bounding follows the [no-artificial-limits policy](#no-artificial-limits-design-defaults):** the blow-up is
the entertainment, so the caps sit where a server actually breaks, not where the fun starts — the same
reasoning as `doubledrops.max-drops-per-break: 65536`. Three of them cascade, all in the host-free,
unit-tested `JumpMultiplierRule`: the per-jump clone budget, then `MobRegistry`'s shared per-tick spawn budget
(so one jump beside a farm spreads over several ticks instead of one — and because Mob Duplication and Cleave
drain the same budget, the challenge sets the **minimum** of the existing value and its own rather than
clobbering a tighter cap a sibling asked for), then the live-entity ceiling measured by
`WorldAdapter#countNearbyEntities` over exactly the area the sweep would touch.

At the ceiling the mode **refuses to clone and shows "saturated" on the panel**, so a packed area reads as the
mode working rather than as the mode being broken. There is deliberately **no oldest-clone culling**: nothing
in the repo tracks clone identity, and building it would mean tagging every copy with something that survives
a chunk unload. Duplicated item entities are still consolidated by the server-wide
[`StackMergeService`](#stack-merge-two-stage-animated-bundling) exactly like any other item flood, which is
what keeps item duplication cheap.

The sweep runs **inline in the jump's own call stack**, never deferred onto `runTimer`/`runLater`: those map
to Folia's *global* region scheduler, while the jump listener already runs on the player's own region thread —
the one that owns the entities being cloned.

`experiences.modes.jumpmultiplies.*`:

| Key | Default | Meaning |
|---|---|---|
| `radius` | `12.0` | blocks around the jumper scanned for eligible entities (clamped down to what the player can see) |
| `copies-per-entity` | `1` | copies made of **each** eligible entity (1 = everything doubles) |
| `max-clones-per-jump` | `1024` | one catastrophic jump cannot spawn unbounded entities in a tick |
| `max-per-tick` | `256` | shared `MobRegistry` spawn budget (0 = uncapped); the tightest composed value wins |
| `max-live-entities` | `4000` | live-entity ceiling over the swept area (0 = uncapped); at it, the mode refuses |
| `jump-cooldown-ms` | `250` | debounce so one physical jump fires once — **never** the detector |
| `multiply-mobs` | `true` | passive + hostile, babies included |
| `multiply-items` | `true` | dropped item entities |
| `multiply-projectiles` | `true` | arrows, thrown pearls, anything in flight |
| `multiply-tnt` | `true` | **primed** TNT entities (never TNT blocks) |
| `multiply-bosses` | `false` | opt-in; the Ender Dragon and the Wither are the headline risk |
| `play-sound` | `true` | feedback on a duplicating jump |

There is **no** `jump-detection-y-delta` key (that approach is what the dedicated event exists to replace) and
**no** `cull-oldest` key.

**HUD**: on the shared per-player info panel — entities duplicated so far with the jump count, and the live
population against the ceiling, plus a "saturated" line when the ceiling is refusing. A platform without the
seam says so instead, so "nothing was nearby" and "this platform cannot clone" are distinguishable (both spawn
0). Debug adds the radius, the per-entity/per-jump/per-tick budgets, the cooldown, the eligible kinds, the
tracked jumpers and the last jump's actual clone count. Every player-facing line is a `MessageKey`
(`experience.jumpmultiplies.hud.*`) with **en + pt** entries, so no English string lives in the challenge class.

### Death Resets (`deathresets`)

A free-choice twist, not a map type — it composes with whatever terrain the experience already has. One
rule: **when any player dies, that world is over and a new one takes its place.**

Nobody is ever sent to the lobby, and **nothing is ever suppressed**. The replacement world is built
**alongside** the one it replaces — under the next generation name (`…_ab12cd34` → `…_ab12cd34_r1`) —
while everyone keeps playing normally in the world they lost. Only once they have been moved across, and
verifiably left, is the old world deleted. What happens, in order, is owned by `ExperienceWorldReset`:

1. **A five-second countdown begins** — see [the countdown](#the-countdown) below — and the dead are taken
   off their death screens (`PlayerAdapter#forceRespawn()` — a hardcore death screen has no Respawn
   button). Players carry on normally for those seconds: nothing they do is cancelled, because the old
   world is not deleted until they have all left it, so nothing done in it can matter.
2. **In the same tick, the replacement is acquired** under its new name via
   `WorldLeaseService#acquireOrCreatePersistent`, served from the warm pool so it is normally a folder
   rename rather than terrain generation. `Challenge#onWorldReset` rebuilds into it *before* anyone is
   teleported, so a composed map challenge has somewhere to build and nobody spends a tick falling
   through void.
3. **At the end of the countdown, the swap** — in one tick, in a fixed order: the experience's registry
   row is renamed to the new world (`ExperienceManager#updateWorldName`), the match is re-pointed at the
   new lease, persistence re-resolves against the new folder, and only then is everyone stripped back to
   nothing (inventory, health, hunger, effects, XP) and moved across. On arrival each player gets a brief
   invulnerability (`reset-grace-ticks`): wiped to nothing in a fresh HARD world, they would otherwise be
   killed before they could act — and that death would trigger another reset, looping the run.
4. **The old world is deleted last**, on a one-per-second timer that waits until it is actually empty.
   A teleport is asynchronous, so at the moment of the swap every player is still standing in the world
   they were just moved out of — deleting it then is the bug the alongside-design exists to prevent.

> **There is deliberately no "freeze".** An earlier version cancelled every gameplay event for the length
> of the countdown, on the theory that a world about to be deleted should be inert. Every bug this feature
> has had traced back to that suppression failing to lift — a world you could stand in but not mine, hit
> or build in. Since the old world outlives the swap and is only removed once empty, the suppression
> protected nothing and was removed outright.

Three details are load-bearing and easy to break:

- **The world name changes on every reset.** That is what makes `EntryPolicy#prepareArrival` re-send the
  hardcore login packet (it only fires on a world *name* change), so the hardened hearts survive — and it
  is why both worlds can be alive at once, which in turn is why nobody is ever sent to the lobby and why
  the old world is deleted only after everyone is verifiably out of it. (The earlier version faked the
  name change by routing everyone through the lobby; an async teleport meant the world could not be
  unloaded while occupied, and deleting it anyway left a "ghost" the acquire path handed back as fresh.
  The alongside-design makes the failure mode "nothing happened" instead of "your run is gone".)
- **State is carried by allowlist, not wholesale.** `state.yml` lives *inside* the world folder, so the
  old one's state goes with it; only the keys the caller names survive, written to the new folder and to
  the legacy `challenge_state` column as a crash net for a mid-swap server death. Carrying everything
  would break composition — Classic Skyblock guards its build with an "already built" flag, and carrying
  that into a fresh void world leaves everyone standing in empty space.

  An allowlist entry is either an **exact key** or, with a trailing `*`, a **namespace prefix**
  (`StateCarry`). Death Resets names three: its reset count, its day baseline, and
  `ExperienceStats.RUN_KEY_PREFIX + "*"` — the run-lifetime statistics below, whose keys are per-UUID and
  therefore cannot be named exactly. The prefix is still an allowlist: it names a namespace whose whole
  point is that it describes the **run** rather than the world, so everything in it survives for the same
  reason. `StateCarryTest` holds the rule, including that a bare `*` is refused.
- **The reset count is bumped before the swap and rolled back if it fails.** A regeneration that cannot
  complete leaves everybody in the world they were already in and takes the counter back, so it never
  claims a world that was never burned through.

**A new world always opens at dawn.** The replacement comes from the warm pool, which has been ticking
since the server booted, so its hour is arbitrary — about half the time everyone who just lost a world
would respawn into the dark with the mobs already out, which on a mode where the next death costs the
world too reads as a punishment for the reset. `onWorldReset` winds the clock to morning
(`WorldAdapter#setTimeOfDay`, tick 1000) and leaves the day/night cycle running; it is a one-off nudge,
not `WorldSettings#lockTime`. The same applies to the very first world of a run — never to one being
*resumed*, where the hour those players left it at is theirs.

#### The countdown

Those five seconds are shown as **one** thing — a big red number in the middle of the screen — drawn
from one declaration (`ExperienceWorldReset.countdownSpec()`, registered in `HudSurfaceCatalog`) on
whichever surface a given player can actually see:

| Surface | Who sees it |
|---|---|
| **A number in the middle of the screen**, ~3× chat size at rest | Java players, where BetterHud is installed, opted in and capable |
| **A vanilla title**, same number, no fade in or out | everyone else — Bedrock, and any server without the overlay plugin |

Exactly one of the two per player, and never both: the fallback is gated on the overlay's own
`activeFor`.

> **The gate used to be false for every popup, which is how two numbers got on screen at once.**
> `activeFor` asked BetterHud what the player was *wearing*, and a popup is fired rather than worn —
> `HudPlayer#getPopups()` is derived from a map only `HudObject#add` writes to, and `Popup#show`
> writes somewhere else. So the overlay half answered "not reaching this player" about a popup it had
> just fired, the title fired on top of it, and the same declaration drew a white number (the layout's
> hardcoded colour) with a red one through it. Both halves are fixed: the popup gate is answered from
> the fired-popup ledger (`SurfaceClaims.showingPopup`), and the number's colour is declared as
> `HudColor.RED` on the row so the overlay agrees with the `<red><bold>` the title reads from the lang
> file. See **A fired popup is invisible…** and **Row colour** in
> [`ui-and-localization.md`](../interface/ui-and-localization.md).

Each new number **arrives at 5× and eases back to 3× over 420 ms**. That is not decoration: a countdown
whose digits merely replace each other at a fixed size gives a glancing player nothing to distinguish
"still counting" from "stuck", and someone who has just been killed is doing a great deal of glancing.
The mechanism is `HudElement.PulseRow`, and it is triggered by the value *changing* rather than by a
call — see **Animated rows** in [`ui-and-localization.md`](../interface/ui-and-localization.md) for why that
distinction is the whole design, and for how it is compiled given BetterHud has no scale equation.

**There is no boss bar any more.** There used to be one (`experience.reset.countdown`, "NEW WORLD in
5s"), on the reasoning that it was the surface reaching everybody — but the centre already reaches
everybody, because the title fallback covers precisely the players the overlay does not. All the bar
added was the same five seconds counted a second time, one line lower and a tick out of step, which is
what the duplicate-countdown report was. `ExperienceGame.startResetCountdown` now uses
`AbstractGame.timerHidden`: the clock still ticks, nothing is drawn but the number. (A `Countdown` with
a null title key is bar-less.)

Two things follow from re-showing the surface every second, which is what keeps the vanilla title
alive: pushing the value is enough for the overlay, which animates off the change, while the title has
no such memory and must be re-sent — and a BetterHud **popup** re-fired while it is still on screen
draws a *second copy beside the first*, so `BetterHudSurfaceHandle.show` skips the re-claim for a popup
the player is already wearing (it still publishes the new value). Zero is pushed explicitly when the countdown
completes, because `Countdown` stops *at* zero rather than announcing it, and a number frozen at 1 during
a slow world acquire reads as a hang where a held zero reads as waiting.

#### What a run remembers

An experience is long-lived and its world sits on disk between visits, so wall-clock age says nothing
about it. What is kept instead, in `state.yml` under `stats.run.*` (`ExperienceStats`) and therefore
carried across every regeneration:

| Statistic | Where it comes from |
|---|---|
| **Played time** — total seconds the experience has been *occupied* | `OccupancyLedger`, ticked once a real second by `ExperienceGame#accrueOccupancy`; **nothing accrues while the experience is empty** |
| **Per-player time** — each player's own seconds inside | the same ledger; enumerate with `ExperienceStats#runSecondsByPlayer()` |
| **Per-player deaths**, and the run total | `ExperienceStats#recordRunDeath`, called from the `PlayerDeathGameEvent` branch of `ExperienceGame#handle` |
| **Resets** — worlds this run has been through | `deathresets.resets`, bumped before the swap and rolled back if it fails |
| **Days** — in-game days survived in the *current* world | computed live from the world clock; mirrored into `deathresets.days` so it is readable with the world unloaded |

The total is **wall clock while occupied**, not the sum of the per-player figures: three players together
for ten minutes is ten minutes of run and thirty of player time, and neither can be derived from the
other. Accrual is buffered in memory and committed every `experiences.common.stats-commit-ticks` (5s) —
partly to keep the file write off the per-second path, and partly because during a regeneration the state
being written to belongs to a folder on its way out, so the seconds wait until the swap has installed the
new one. `ExperienceGame#stop` drains the buffer before its final flush, so a graceful stop loses nothing.

> **Deaths are counted at the death, not at the respawn.** The respawn is deferred by a tick on purpose
> (see step 1), and a warm-pool world can be handed over in the *same* tick as the reset — so the
> regeneration may already have snapshotted what it carries before the respawn fires. A death counted
> there is the death the next world never hears about. `DeathResetsWiringTest` pins the ordering.

**The HUD** shows three numbers, in white: `Played` (how long the run has been played), `Days` (in-game
days survived in the *current* world) and `Resets` (worlds this run has been through). Days is counted
from a baseline recorded when the world became current, not from the world clock directly — a
pool-adopted world has been ticking since server boot, so a raw `fullTime / 24000` would open the counter
on "day 37". All three survive a restart; `Days` alone returns to zero on a reset, because it is the age
of the world rather than of the run. Beneath them, separated by a spacer, sits the boss checklist.

#### The boss checklist

Under the counters, the four bosses a world is asked to get through, in the order the mode asks for
them — **Elder Guardian → Warden → Wither → Ender Dragon** (`BossLadder`). Each is an unticked box until
it dies, then a ticked, struck-through one:

```
Played: 1h 12m          ← white, the run
Days: 3
Resets: 4

☑ E̶l̶d̶e̶r̶ ̶G̶u̶a̶r̶d̶i̶a̶n̶     ← green tick, dimmed struck label
☑ W̶a̶r̶d̶e̶n̶
☐ Wither                ← white, still to do
☐ Ender Dragon
```

**There is no tally row.** Four lines that already show which rungs are ticked do not also need a
number saying how many — on a corner readout that is a fifth line of reading for a fact the reader can
see. The count lives where it is worth words: the announcement when a boss falls, and
`/sx experience boss list`.

Five things about it are decisions rather than defaults:

- **A kill counts whichever order it happens in.** The ladder is the suggested route — easiest first —
  not a gate. Refusing to tick a boss somebody actually beat would be punishing them for succeeding.
- **The list is scoped to the WORLD, not the run.** It is not in the reset's carry allowlist, so a death
  takes it back to nothing along with everything else. That is the point: the monument, the Deep Dark and
  the End those bosses lived in stop existing with the world, so a list that carried over would credit a
  run for bosses that are gone. It also gives the mode its shape — reaching the Ender Dragon and then
  dying costs all four, which is the same bargain the world is already on.
- **Colour is per span on both surfaces; the strike-through is real only on the sidebar.** A ticked rung
  is `<green>☑</green> <gray><strikethrough>Warden</strikethrough></gray>` — a green tick against a
  dimmed, struck label. The colours reach BetterHud because the generated layout sets
  `use-legacy-format` and the publisher serializes to ampersand codes (§ *Row colour* in
  [`ui-and-localization.md`](../interface/ui-and-localization.md)); the line arrives there as
  `&a☑&r &7&mWarden`. The `&m` is parsed and thrown away — nothing in BetterHud references Adventure's
  `TextDecoration`, its renderer reads `color()` and nothing else — so the corner shows a green tick
  and a dimmed name, and the sidebar shows the same with the line actually struck through. That is why
  the state also rides in a glyph: `☐` against `☑`, both 8px wide in unifont, so a rung ticking off
  never shifts the column.
- **Nothing on this surface animates.** A `PulseRow` on the tally would pop nicely, but an animated
  surface is republished *every tick* for as long as it is worn (`HudSurfaceSpec#animated`, and the
  driver's second pass), and this one is worn by every player for a whole match to animate an event that
  happens at most four times per world. The chat announcement carries the moment instead.

##### Where the checklist is stored

In the experience's **shared state** — `state.yml`, inside the world folder, mirrored to the
`challenge_state` column as a crash net (§ [What a run remembers](#what-a-run-remembers)). Not a table
of its own, and not memory: it survives a disconnect and a restart, and dies with its world, which is
the scoping the checklist wants. Three keys per rung, under `deathresets.`:

| Key | Holds |
|---|---|
| `boss.<id>` | that it fell |
| `boss.<id>.at` | the wall-clock instant, epoch millis — the exact date it died |
| `boss.<id>.played` | the run's **played** seconds at that moment |

The last two are not derivable from each other. Played time only accrues while somebody is inside
(`OccupancyLedger`), so for a world that sits on disk between visits the wall clock between two kills
and the play time between them are different numbers — usually very different. A rung ticked by hand
records the same three facts as a real kill, so nothing downstream has to know which it was; un-ticking
clears all three, because a kill time behind a cleared flag is a record of something the list says never
happened.

##### Controlling it live

```
/sx experience boss list              # the ladder, with kill date and play time at the kill
/sx experience boss done <boss>       # tick a rung off by hand
/sx experience boss todo <boss>       # put it back on the list
/sx experience boss hide | show       # hide the checklist, keeping the run counters
```

Scoped to the match the caller is **standing in**, not to an experience id they could name from
anywhere. Every one of these changes something drawn on the screens of the people in that world, and
`ExperienceCommandRouter` exists because acting on a world that lives on another node is its own problem
with its own failure modes. Standing in it also means the challenge instance is right there.

`hide` blanks the checklist rows without touching the counters, and "blank" is a distinct state from
"unset" throughout the HUD stack (`HudValues#blank`): the overlay draws an invisible slot, the sidebar
drops the row rather than leave a hole mid-panel, and un-hiding restores the last value instead of
flashing the unset dash. It is persisted, so a player who turned it off does not get it back on the next
boot.

`EntityDeathGameEvent` is the signal, taken in `Challenge#onEntityDeath`. Two guards matter. The match is
**exact** — `WITHER_SKELETON` is not the Wither and `ENDERMAN` is not the Ender Dragon — and it is scoped
to **this** world, because `GameEventRouter` hands every entity death to every running match, so two
Death Resets worlds on one node would otherwise tick each other's lists. Scoping goes through
`WorldKey.fromRuntime`, which strips the `_nether` / `_end` suffixes, so a dragon dying in `…_r3_end`
belongs to the run whose Overworld is `…_r3` — and keeps the generation, so a kill in a world this run
has already thrown away does not count.

**The tab player list** carries each player's death count for the run, as a
`DisplaySlot.PLAYER_LIST` scoreboard objective (`TabListHandle` → `PaperTabListCounter`). It is the one
surface where a player reads the same statistic for *everybody* — which in a mode where one person's
death costs the whole table its world is shared information. Two things about it are deliberate: it is a
**vanilla** surface, so unlike the corner overlay it reaches Bedrock players and servers without
BetterHud, and it is therefore pushed *outside* the overlay's drawing check; and the objective is
installed on **whichever board each viewer is currently looking at**, not on the main board, because a
player being shown a sidebar is on a private `getNewScoreboard()` and would otherwise never see it.
A player who leaves stops seeing the column but keeps their number in it for everyone still inside.

> **The baseline is read *after* the clock is wound**, in both places that seat it. `setTimeOfDay` only
> ever moves forward, by up to a full day; a baseline captured first would be that far behind and the
> run's first day would be short by however dark the world happened to arrive. `DeathResetsMorningTest`
> is what holds that ordering.

**This mode owns the screen — for the players it can reach.** Unlike every other challenge it prefers to
render those three numbers in BetterHud's **top-left corner** overlay and suppress the scoreboard sidebar.

> **The corner works at the pinned 26.1.2** — that pin exists largely for this readout (F62 in
> [known issues](../reference/known-issues.md)), and `hud.betterhud.enabled` ships `true` for it. On **26.2**
> BetterHud's shader overlay does not match the client, so the readout renders on the sidebar for
> everybody: the provisioner writes `hud.betterhud.enabled: false` on a pin it does not cover, and on any
> path it does not reach, `BetterHudLink`'s capability probe reaches the same outcome by refusing to
> report capabilities.
> The two-part suppression below is what makes that a non-event: with no overlay drawing,
> `hudSurfaceActive(player)` is false for every player and nobody's sidebar is taken away.
The counters are the entire interface here, and the sidebar's usual header — active-challenge list,
deaths, blocks broken — beside them would be two surfaces saying different things.

That suppression is a **two-part** decision, and both parts matter:

| Hook | Meaning |
|---|---|
| `Challenge#ownsHud()` | the claim — "my own surface should replace the sidebar" |
| `Challenge#hudSurfaceActive(player)` | whether the claim was honoured **for that player** — the corner is genuinely drawing for them *right now* |

`ExperienceGame#refreshActiveNames` hands `GameHud#suppressPanel` a **predicate**, not a flag, and a
player's panel is dropped only where both agree. Minecraft has no vanilla top-left text surface, so that
corner is BetterHud, and BetterHud may be absent, may be off, may be incapable on this Minecraft
version, or may be disabled for *this* viewer — it rides boss bars and turns itself off for
Floodgate/Bedrock players.

**There is no second copy of the readout any more.** The challenge declares its three rows once as a
`HudSurfaceSpec`; the driver stack renders that declaration in the corner for whoever it reaches and on
the sidebar for everyone else, in each viewer's own language. That replaced a hand-written
`describeHud` duplicate and, with it, the old limitation that the corner's labels were hardcoded
English in an operator-owned yml `MessageService` could not reach. `hudSurfaceActive(player)` is now
answered from the live surface rather than tracked by the challenge, so the edge-report call it used to
make on every transition is gone too.

> **Per player, not per match.** One Java client seeing the overlay used to take the sidebar away from
> the Bedrock client standing beside them, who then had no readout at all. `HudSurfaceHandle#activeFor`
> and `Game#drawsSidebar(player)` are what keep those two answers separate.

> **BetterHud is optional, not required — but if it is installed, Sexidium sets it up.** On enable the
> plugin generates the yml for every declared surface into a `sexidium/` subtree of
> `plugins/BetterHud/{texts,layouts,huds,popups}` that it owns outright (regenerated each boot, stale
> files removed, nothing outside it touched), empties BetterHud's `default-hud` list so its bundled demo
> hud stops riding along, and reloads it only if something changed. It then keeps each player wearing
> *only* the surfaces a consumer asked for — nothing in the lobby, nothing in a minigame. All opt-out
> under `hud.betterhud.*` in `config.yml`; see [ui-and-localization.md](../interface/ui-and-localization.md) §4.4.

The overlay is driven by the challenge's own timer (`experiences.common.hud-refresh-ticks`) rather than
by the HUD render pass, because when the panel *is* suppressed that pass paints nothing.

Selecting this challenge **forces hardcore on** and locks the toggle for as long as it is selected —
`setHardcore(false)` is refused in the service, not just hidden in the GUI.

#### A death nobody was present for does not count

On 22/08/2026 a network outage destroyed a fifty-five-in-game-day run. The timeline is the design brief:

| Time (UTC) | Event |
|---|---|
| ~03:24:05 | three clients stop sending packets (the start of Velocity's 30s `read-timeout` window) |
| 03:24:32 | Evelyn, frozen and unable to react, is killed by a Guardian → `[deathresets] reset started` |
| 03:24:35 | all three are dropped (`read timed out` at the proxy) |
| 03:24:40 | the swap completes **with nobody online**, announcing "everyone starts again from nothing" |

The death landed 27 seconds *inside* the freeze but 3 seconds *before* the disconnect. A guard hung off
`PlayerQuitEvent` would have been too late — and in vanilla the player's entity is removed at
disconnect anyway, so "no damage after you drop" protects nothing. The damage happens while the client
is frozen but still connected.

So the trigger is **silence, not disconnection**. `PlayerAdapter#idleMillis()` reports vanilla's
last-action time (the clock behind `player-idle-timeout`), which keeps climbing for exactly as long as
nothing is heard from a player. Past `downed-idle-seconds` they are **downed** — nobody is driving that
character — and three things follow:

1. **They cannot be hurt.** The damage event is cancelled outright. Deliberately *not*
   `setInvulnerable(true)`: that flag is persistent NBT with no expiry, safe only when paired with a
   revoke scheduled at grant time, and this state has no known duration to schedule against because it
   ends when the player acts. Derived state fails safe; a written flag fails open.
2. **Hostiles lose interest.** New aggro is refused at the platform's target event, and mobs that were
   already chasing them are swept with `downed-deaggro-radius`. This is polish, not the safety net — it
   cannot stop a primed creeper or an arrow already in the air; (1) catches those.
3. **A death while downed does not count.** The host returns before the death is recorded, before the
   hardcore branch, and before challenges are dispatched — so no counter moves and no reset is asked
   for. It logs at `WARNING`, because it should be unreachable: (1) stops the lethal hit.

Protection lifts on the first input, plus `downed-recovery-tail-ticks` so somebody rescued while
standing in lava gets a moment to move instead of taking the backlog at once.

Because the signal is "no input", this also covers a player who simply walked away. That conflation is
intended: in a mode where one death costs everyone the world, an AFK player being eaten is the same
problem as a frozen one. It does mean standing perfectly still makes you unkillable — and unable to do
anything — until you move. Set `downed-protection: false` to restore the old behaviour, including the
outcome above.

Two further guards close the same incident from the other end:

- **A swap with nobody online is abandoned**, not completed. Checked at the request *and* again at the
  swap, because everyone can drop during the countdown — which is what happened. The old world is
  untouched until the swap succeeds, so abandoning costs nothing, and the mode takes its reset counter
  back on the `false` answer.
- **The offline carry wipes too.** `carryPlayerSnapshots` used to bring every logged-out player's
  inventory across intact while the wipe loop only reached players who were *online*, so "was offline
  when somebody else died" quietly became a way to keep your gear through a reset. It now asks the mode:
  a `RESET_WORLD` outcome strips contents to exactly what `resetStatuses()` leaves an online player
  with. An unreadable snapshot is left behind rather than copied across, which was the same hole by a
  quieter route.

### Hardcore (per experience)

An experience option alongside the map type and keep-inventory, off unless deliberately turned on —
toggled in the builder, on the manage screen, or with `/sx experience hardcore <id> <on|off>`.

- **A death nobody was present for never reaches the hardcore branch.** The downed guard described
  above sits in the host, before `handleHardcoreDeath`, so a `LOSE_WORLD` experience is covered by the
  same lines that cover Death Resets — neither `HardcoreRule` nor any challenge knows it exists. The
  explicit non-goal: a disconnect that arrives *after* a death does **not** undo it, or quitting would
  be a way out of any death. The lead-in is covered by silence, which is why silence is the trigger.
- **Hardcore has two halves, in two different places.** The *world* half — difficulty pinned to hard, a
  death that is final — is `World#setHardcore`, applied to the Overworld **and** its Nether and End, or
  walking through a portal would quietly drop the stakes. The *client* half — the hardened heart texture
  — is not that flag and never was: a client is told whether it is in a hardcore world exactly once, in
  its login packet, built from the world it logged in to, and believes it until it is sent another. That
  single fact is the whole of the two reported bugs (a hardcore experience entered from the lobby drew
  ordinary hearts; hardcore hearts followed a player back *into* the lobby), and no amount of setting the
  world flag fixes either, because neither is about the world.
- **So the client is re-told, and only when it can be.** `PlayerAdapter#setHardcoreView(boolean)` re-sends
  the login packet with the right flag (`PaperHardcoreView`, reflective and fail-safe: a server that does
  not expose what it needs logs once and gives up, leaving difficulty and death handling untouched).
  Re-telling a client costs it the world it has loaded, so it is only ever said immediately before a
  teleport that changes world — `EntryPolicy#prepareArrival` enforces exactly that guard, and the world
  change itself re-sends the level, chunks, position, abilities, health, XP, inventory and effects, which
  is the resynchronisation this needs and gets for free. The counterpart,
  `EntryPolicy.leaveHardcoreWorld`, runs on every release to the lobby: the lobby is never hardcore.
  A hardcore toggle flipped on players who are *already inside* still cannot repaint their hearts before
  they next enter — `setHardcoreLive` tells them so rather than leaving ordinary hearts to read as
  ordinary stakes.
- **The world flag must exist before anyone is teleported in**, so hardcore rides
  `WorldGeneration`/`WorldSettings` into `WorldCreator.hardcore(…)` — the world is *born* hardcore — is
  re-asserted by `applySettings` on every acquire (a world loaded from disk must never come back
  ordinary), and is re-applied in `ExperienceGame.entrySpawn`, the one hook that runs before the entry
  teleport on **every** path in.
- **The world ends at the DEATH; nobody is moved anywhere.** A genuine hardcore client's death screen
  offers to spectate rather than to respawn, so a mode watching for `PlayerRespawnEvent` would never
  learn the player died — hence `PlayerDeathGameEvent`, bridged from Bukkit at `MONITOR`, marks the world
  lost the moment it happens (in memory *and* in the registry, in that order, so a database that refuses
  the write cannot hand a lost run back). From that moment the entry policy answers SPECTATOR, so the
  player who died comes back from their own death screen as a **spectator of the world they lost, in the
  dimension they died in** — still in the match, never bounced through the lobby. Living bystanders are
  flipped to spectator on the spot. Leaving is what `/leave` is for; deleting the world is the owner's
  decision to make whenever they are ready.
- **What a death costs is a choice, not a constant.** The bookkeeping lives in `HardcoreRule`
  (`core/game/hardcore`), which any `Game` can own: the world flag, the cached one-way "lost" fact, and a
  `HardcoreDeathOutcome` of `LOSE_WORLD` (the default, everything below) or `RESET_WORLD`
  ([Death Resets](#death-resets-deathresets)). A `RESET_WORLD` experience never writes `dead` — doing so
  would lock its owner out of a run that is still going. A challenge declares the choice with
  `Challenge#hardcoreDeathOutcome()`, and can force hardcore on entirely with `Challenge#requiresHardcore()`.
- **The world is lost, not deleted.** It stays on the owner's list so they can walk back through what
  killed them, but any re-entry is **SPECTATOR** — declared through the
  [entry-policy API](../architecture/game-framework.md#entry-policy-how-a-mode-controls-arrival) rather than set by hand,
  and *kept* that way by a once-a-second watchdog rather than only checked at the door. Entry-time
  enforcement alone was not enough: something always wrote a game mode afterwards (a respawn, a restored
  snapshot, a plugin, an operator), which is exactly how a player could still be in a dead world in
  survival. `EntryPolicy#enforce` writes only when the mode is actually wrong, so the guard is free while
  it is being obeyed.
- **Its settings are frozen, in the service and not just the GUI.** `setVisibility`, `updateChallenges`,
  `updateChallengesLive`, `updateWorldType`, `setKeepInventory` and `setHardcore` all refuse on a lost
  world. A hidden button is a suggestion — a command, a stale open menu or another client still reaches
  the service. Renaming and deleting stay open, because those are how an owner puts a run to rest.
- **A lost world reads as lost.** A **skeleton skull** means hardcore everywhere it appears — the tile in
  *My Experiences*, the builder toggle, the manage-screen toggle — and nothing else in these menus is
  allowed to use one (keep-inventory-off wears a hopper now). Once a world is lost every line describing
  it turns red: its list tile, and a red information tile at the head of the manage screen carrying the
  world type and the twist list, because after a death that is the state the owner needs first. The
  visibility, challenge-set, keep-inventory and hardcore tiles are shown **locked** rather than hidden —
  the settings are part of the record of the run — and the challenge editor and world chooser refuse to
  open at all, since a stale screen left open at the moment of death could still reach them. Only
  *Spectate*, *Rename* and *Delete* still do anything.
- **`dead` is one-way.** There is no revive counterpart, and `setHardcore` refuses to turn hardcore *off*
  on a lost world. Hardcore that could be undone after the fact would mean nothing.

Persisted as two columns (`hardcore`, `dead`) plus a `hardcore:<bool>` mode-arg token, so both survive a
restart with the rest of the match state. Older rows read NULL for both: a normal, living experience.

### Keep Inventory (per experience, all dimensions)

`ExperienceSetup` (`game/experience/ExperienceSetup.java`) holds the per-experience options that are **not**
challenges — the map type and the keep-inventory rule — and owns the mode-arg tokens that carry them into a
running match (`world:<id>`, `keepinv:<bool>`), so both survive a restart. Keeping both tokens in one class
means `stripArgs` can never fall out of sync with the encoders.

- **Default ON**, which is what every experience did before the toggle existed. A DB row written before the
  `keep_inventory` column existed reads NULL → ON, and mode args with no token read ON.
- **Toggle in the GUI**: a tile in the experience builder (before creation) and on the manage screen (live).
  The live path (`ExperienceService.setKeepInventory`) persists the flag and calls
  `ExperienceGame.setKeepInventoryLive`, so it takes effect on the very next death rather than the next restart.
- **CLI**: `/sx start experience <challenge…> [--keep-inventory=true|false]`.
- **All dimensions.** `WorldAdapter.setKeepInventoryEverywhere(boolean)` applies the rule to a world *and*
  every linked dimension of it, so walking from the Overworld into the experience's own Nether or End never
  silently drops the rule. `ExperienceGame.applyKeepInventory` calls it from `initChallenges()`, which both
  `start()` and `restore()` run — the world layer sets its own default when a world is (re)acquired, so the
  owner's choice has to be re-applied on every start, and it always wins over that default.

### Safe starting spawn (`world/SafeSpawn.java`)

Every "drop the player into the world" path resolves through `WorldAdapter.safeSpawnPosition()` / `safePositionNear(pos)`, which delegate to `SafeSpawn`. It guarantees the player stands **on top of a block** — never inside terrain, never submerged, never in mid-air:

1. Scan the spawn column downwards (in the Nether, starting *below* the bedrock roof so nobody lands on it), walking through plants/snow/air.
2. The first solid block with **two free blocks above it** is the standing spot (`y + 1`).
3. A column whose scan passes **through a liquid** first is rejected outright — that is the "spawned under water" case — and the search steps outward ring by ring (6-block steps, up to 24 blocks) to the **nearest** column that works.
4. Backends that report a surface height but not blocks (and the all-void case) fall back to one block above the reported surface, never below a deliberately raised spawn Y.

`SafeSpawnTest` covers the ocean, lava, plant-canopy, Nether-roof and void cases.

---

## Challenge base + host surface

A `Challenge` (`Challenge.java`) is one twist with this lifecycle: `attach(host)` → `register(ChallengeRegistry)` → `onStart`; plus `onStop`, `onEvent(GameEvent)`, `onPlayerJoin/Leave`. The default `register()` contributes nothing (`Challenge.java:75`), so a legacy `onEvent`-only twist still works. `priority()` defaults to 0; **life** twists sequence via their **contributor** `order()` instead. A lethal condition calls `softReset(player)` (`Challenge.java:254`) rather than `eliminate`/`requestEnd`.

`ExperienceHost` is the base surface (participants, `online`, `isParticipant`, `runTimer`/`runLater`, `track`, `world`, shared persistent `state`, `softRespawn`). **`ChallengeContext`** (`ChallengeContext.java:15`) extends it with:

- **sibling discovery** — `challenges()`, `challenge(id)`, `challenge(Class)`.
- **typed capability registry** — `publish(Class, impl)` / `service(Class)`, so a twist can call another's live Java object instead of smuggling strings through `ExperienceState`.
- **shared pipelines** — `drops()`, `blocks()`, `damage()`, `health()`, `mobs()`, `stats()`.

---

## Composition layer (`compose/`) — how challenges interoperate

The `register()` pass runs **once** at start (`initChallenges`, `ExperienceGame.java:139`): **attach all → register all** (declare pipeline contributions + publish capabilities) **→ onStart all**. So a challenge's `onStart` can already fetch a sibling's published service regardless of registration order. The same path runs from `restore()` after a server restart, so a rehydrated experience is fully wired and events never hit a null host (`challengesReady` gates dispatch).

`ExperienceGame.handle` (`ExperienceGame.java:465`) owns the hot path: it counts manual block breaks for the HUD, runs the drop pipeline (suppressing vanilla once if a contributor claimed the loot), runs the damage pipeline (cancelling the native hit once when absorbed, then writing merged health), polls the sneak HUD-toggle, and only **then** forwards the raw event to each `challenge.onEvent`. Twists contribute to pipelines via `register()` rather than cancelling events independently.

### Drop pipeline

`DropPipeline` + `BlockBreakService` funnel **every** loot path — manual break, sweep, chunk conversion, explosion, entity death — through one mutable `DropContext`. Contributors run in phase order, then `order()` within a phase: `GENERATE → TRANSFORM → FILTER → SINK`. Because all share one list, `DoubleDrops` (a `TRANSFORM` multiply) scales whatever `Randomizer`/sweeps (`GENERATE`) produced — the headline "multiplier × break-all" interop. `BlockChangeVeto`s (`BlockDeleter` "deleted forever", `BreakOneBreakAll` "broken everywhere") arbitrate placement vs destruction so placers and sweepers stop fighting. `BLOCK_BREAK` drops start **empty** (vanilla fallback unless a contributor marks the context dirty / suppresses vanilla); `SWEEP`/`EXPLOSION`/`ENTITY_DEATH` paths must always emit since there is no vanilla fallback.

#### World integrity: `BlockGuard`

Challenges are deliberately destructive, and each one used to carry its own idea of what was off-limits —
or none at all. A block one mode protected, another would happily delete. The visible symptom was an **End
portal frame destroyed by Omni Chunk's replication**, which can make a world unbeatable.

The rule now lives in **one** place. `BlockGuard` (`compose/BlockGuard.java`) holds the protected list, and
`BlockBreakService` — already the single funnel for block changes — consults it **before** any challenge
veto, so a veto cannot re-open a protected block:

| Editing shape | What the mode calls |
|---|---|
| One block at a time (Omni Chunk copies, Walking Blocks trails) | `blocks().mayModify(world, position)` |
| Bulk sweep by type (Break-One-Break-All, Chunk Break) | `blocks().breakableTypes(typeValues)` — filters the set |
| Whole-chunk rewrite (Random Chunks) | `guard().with(ownBlacklist).preservedValues()` → `convertChunk`'s preserved set |
| Picking a victim type (Block Deleter) | `blocks().guard().isProtected(type)` |

Protected by default, in two groups:

1. **Blocks whose loss breaks a world rather than changing it** — `bedrock`, `barrier`, `light`,
   `structure_void`, the way out (`end_portal`, **`end_portal_frame`**, `end_gateway`,
   `reinforced_deepslate`) and the admin/structure blocks.
2. **Containers** — `chest`, `trapped_chest`, `ender_chest`, `barrel`, `shulker_box`. Destroying terrain
   is the mode working; destroying a chest takes the player's **things** with it, which is a different
   kind of loss. A sweep now flows around your storage instead of emptying it into the void.

Everything else stays destructible, per the fun-first policy: burying or erasing something you needed is
the mode working. Note this governs **challenge-driven** edits only — a player can still break their own
chest by hand, as they must be able to.

Colour and material variants are matched by family suffix (`_shulker_box`, `_chest`) rather than listed
one by one, so a new variant is covered automatically — and only while its family is protected, so the two
can never disagree. `preservedValues()` expands them explicitly, because a whole-chunk rewrite can only
compare exact ids.

Configured once at `experiences.common.protected-blocks` and installed on the funnel by
`ExperienceGame`/`ChaosGame` before any challenge registers, so it covers **every** mode and every
combination of them. Setting the list replaces the defaults; a challenge with its own extras (Block
Deleter) *adds* to the guard rather than replacing it, so a server can shield more but never less.
`BlockGuardTest` covers the End-portal case, the veto-outranking rule, set filtering and the config forms.

#### Real vanilla loot: tools and probabilities

The loot a break contributes is the block's **real vanilla loot**, resolved from the platform's own loot
function — never a hard-coded table and never "one of the block itself".

- **A manual break respects the held tool.** `BlockBreakService.onManualBreak` seeds the context from
  `WorldAdapter.naturalDrops(position, breaker)`, which rolls `Block#getDrops(mainHand, player)`: a wooden
  pickaxe on iron ore yields **nothing**, Silk Touch yields the block, Fortune yields more, and a bare hand
  on leaves rolls the leaf table (sapling/stick/apple) and **never the leaf block**.
- **A sweep ignores the held tool** (`breakIfTypeNatural` → an unenchanted netherite pickaxe). Break-One-
  Break-All and Chunk Break would be miserable if breaking stone by hand meant the whole cluster dropped
  nothing, so the sweep assumes a competent tool — but *not* Silk Touch or Fortune, so leaves in a sweep
  still yield saplings and sticks rather than leaf blocks.
- **No block-item fallback.** `dropsOf` used to fall back to "one of the block" whenever a loot roll came
  up empty, which is exactly what made leaves drop leaf blocks. An empty result now means the break
  legitimately dropped nothing (glass, ice, an unlucky leaf roll). Platforms that cannot compute loot at
  all say so via `WorldAdapter.resolvesBlockLoot()` (false by default) and keep the old block-item seed,
  so "cannot tell" is never mistaken for "drops nothing".
- **Probabilistic blocks are sampled, not scaled.** Multiplying a *single* roll by 65,536 would turn one
  lucky apple into 65,536 apples, or one unlucky roll into nothing. Instead `DropContext.multiplySampled`
  **re-rolls the real loot table** (up to 512 times, never more than the factor, stopping early once
  enough items have been observed), measures each item's average yield per break and projects it onto the
  full factor, settling the fraction with a coin flip so the expected total is exact. 65,536 leaf breaks
  therefore pay out ~3,277 saplings, ~1,311 sticks and ~328 apples — and the rare drop still shows up,
  because the sampling budget is spent precisely on pinning down rare events.
- Sampling only applies when the loot is **the block's own roll and nothing else's** (`naturalOnly`): a
  Randomizer remap or a sweep's accumulated bucket (already one roll per block removed) is scaled linearly,
  as before.

#### Streamed payouts

A contributor may call `DropContext.spreadOver(ticks)` to ask the sink to **pour** its loot out from the
break position over that many ticks instead of spawning it all in one. `DropPipeline.emit` splits the loot
into ground stacks (`max-item-stack-size`), then a self-cancelling 1-tick timer drops an even share each
tick. A host with no scheduler falls back to the instant emission, so loot is never swallowed.

`DoubleDrops` sets the duration from a **rule of three against the configured cap**
(`DoubleDropsChallenge.streamTicks`): `max-drops-per-break` pours for `stream-seconds` (default 10s) and
every smaller multiplier takes its proportional share — with the default cap, 65536 → 10s, so 512 →
0.078s (rounds to 2 ticks, still instant to a player). Anchoring on the *configured* cap rather than a
constant keeps the rule correct when a server lowers the cap: its own maximum is always the 10-second
payout. `stream-seconds: 0` restores the old single-tick behaviour. At the cap this is the difference
between ~1024 item entities in one tick — which froze the server — and ~5 per tick for ten seconds.

#### Stack merge: two-stage animated bundling

`StackMergeService` (core, driven by `SexidiumCore`) consolidates ground item entities into as few stacks
as possible (each up to `MAX_AMOUNT` = 65,536) so an item flood cannot bury the server in entities. It
used to move the amounts and delete the donors in one go, which read as items **blinking out of
existence**. Now every stack **flies to the pile it joins** and merges on arrival, in two stages:

| Stage | What moves | Distance | Why |
|---|---|---|---|
| **1 — cluster** | every stack in a `cluster-cell`-sized cell → that cell's fullest stack | short hops | collapses the entity count fast, so stage 2 has little left to move |
| **2 — consolidate** | the surviving cell heads → the single fullest stack in the chunk | long hops | only a handful of entities are ever in flight over distance |

Staging is the whole point: a mode like Double Drops can put an absurd number of entities on the ground per
second, and animating all of them individually would cost more than the merge saves. Stage 1 animates many
*cheap* hops and immediately reduces the population; stage 2 animates *few* entities over the visible
distance.

Mechanics:

- **One pump, two jobs.** `StackMergeService.pump()` runs **every tick**: it advances anything in flight and
  counts down to the next planning pass. With no active chunk it is two map checks, so a calm server pays
  nothing. `SexidiumCore` keeps a second, slow timer for *discovery* only (`activateFloodedChunks`, which
  scans every player's surroundings and is the expensive part) — a new pile elsewhere can afford to be
  noticed a couple of seconds late.
- **Dynamic pass interval** (`MergePacing`). The old fixed 40 ticks was wrong in both directions: wasteful
  when idle, far too slow when a mode is dumping thousands of items a second. The interval now *starts* at
  the `period-ticks` baseline and shortens in proportion to the measured pressure, floored at
  `pacing.min-period-ticks`: 64 mergeable items → 40 ticks, 128 → 20, 256 → 10, 1280 → 2. Once the pile is
  consolidated the pressure falls and it relaxes straight back to the baseline.
- **Constant re-validation near players.** Every `period-ticks`, `StackMergeService.validateNear` inspects
  the ground around each online player (`revalidate.radius`) and wakes the merger wherever there is work.
  Inside a **match or experience world** — where bundling is the point — the bar is simply *is there
  anything to merge* (`revalidate.min-mergeable`, 2 stacks). Elsewhere a **brand-new** area still has to
  cross `max-ground-items`, so ordinary survival play is untouched, and an area that was recently
  consolidated stays **watched** for `revalidate.watch-seconds` and re-wakes at the low bar too.
  **Item type never enters into it**: types are read from the platform registry
  (`material.name()`), so every block and item groups with its own kind. Volume decides only how *urgently*
  a pile is consolidated (see the pacing above), never *whether* it is — which is what used to make a
  shower of apples bundle while a handful of logs sat there. That asymmetry is what fixes "merging just stopped": a
  leftover pile below the flood bar, or a chunk retired by a transient scan failure, used to be forgotten
  permanently.
- **Stuck recovery.** A pile that has items *in flight* and has not shrunk for `revalidate.stalled-passes`
  consecutive passes is stuck (a wedged entity, a receiver that keeps moving, a platform that accepted a
  velocity it never applied). The merger then **cancels those flight records and merges the pile
  instantly** on the same pass, so bundling always finishes even when the animated path cannot. A pile
  merely *waiting* on the shared animation budget has nothing in flight there, so it is never mistaken for
  stuck. A transient scan failure now reads as "no measurement", never as "settled".
- **Counters** (`StackMergeService.diagnostics()`): active/watched areas, items in flight, pressure,
  entities scanned, stacks merged, pulls started/completed/timed-out, stuck resets and re-validations —
  so a non-merging hotspot is *visible* rather than guessed. A stuck reset is logged with its coordinates
  and counts.
- **Chunk retirement asks "is there still work?", not "did anything move?"** An active chunk is released
  after a few passes that find nothing to do. With the animated merge, a pass that *plans* nothing —
  because the concurrency budget is full, or because every candidate is already in flight — looks exactly
  like a settled chunk under a "did anything move" rule, so chunks were retired while items were still
  lying on the ground and were then never grouped again. The predicate is now the mergeable count from the
  scan, and stacks already at `MAX_AMOUNT` do not count (two full stacks are not work), so a chunk is
  neither retired early nor kept alive for ever. `StackMergeScenarioTest` covers both directions.
- **Pressure that cannot cry wolf.** Pressure is not a global entity count and not a player-radius count —
  either would fire on items the merger has no business touching. It comes from the merge scan *itself*
  (same centre, same radius the merger works in) and counts only items that have **at least one same-type
  partner in that radius**, i.e. items the merger can actually do something about. 300 one-off types in one
  spot measure **zero** pressure; a pile two chunks away is a different chunk's pass, not this one's
  emergency. `emergency()` (pressure ≥ `pacing.emergency-items`) exists for logs/HUD; the ramp itself is
  continuous.
- **One budget** (`animate.max-entities`, default 192) caps entities in flight across both stages — the cost
  knob. Below the flood ceiling the overflow simply **waits** for a later pass, so nothing pops; at or above
  `animate.instant-above-items` the overflow merges instantly, because there the entity count itself is the
  emergency.
- **A head still gathering its cell is not sent to stage 2** (`inbound` count), so a cluster never chases a
  target that left mid-gather. If a head *is* absorbed while stacks are chasing it, `retarget` re-points them
  at the pile that absorbed it instead of stranding them.
- **Arrival** absorbs up to `MAX_AMOUNT`; a receiver that filled up in the meantime takes what it can and the
  remainder stays on the ground as its own stack, to be re-planned later. Nothing is ever lost.
- **Degradation**: `ItemEntityHandle.setVelocity` returns false on a platform that cannot move item entities
  (the default), and the service merges on the spot instead of waiting for an arrival that never comes.
  `animate.enabled: false` restores the original instant merge.

### Damage + health pipeline

`DamagePipeline` runs one `DamageContext` per hit through ordered `DamageContributor`s (`amount` reducible; `absorb()`; `consume()`; `markFatalHandled()`). `SharedLife` and `XpHealth` are **independent** systems: each reads the same hit and deducts in parallel — the pool drains **and** the victim's XP burns from one hit; neither `consume()`s, both `absorb()` the native hit. Only **death** is exclusive: exactly one contributor may `markFatalHandled()`, so two life twists never both reset. Contributor order:

| Order | Contributor | Role |
|---|---|---|
| 20 | `SharedLife.Drain` (`SharedLifeChallenge.java:176`) | drain the shared pool, absorb |
| 30 | `XpHealth.XpDrain` (`XpHealthChallenge.java:102`) | burn XP, absorb |
| 40 | `Chained.DeathLink` (`ChainDeathLink.java:17`) | death link; defers when already `absorbed()` or `fatalHandled()` |

`HealthModel` (`HealthModel.java`) merges per-player `HealthSource`s and writes `setHealth`/`setHealthScale` **once** per tick (highest-priority source providing a value/scale wins) — no flicker from competing timers.

#### SharedLife ↔ XpHealth coordination

`XpHealth` publishes an `XpHealthModel` capability (snapshotted in `register`); `SharedLife` publishes a `SharedHealthPool`. With **both** active, `SharedLife` denominates its pool in **XP** (max = startingXp, grows to `MAX_XP_POOL = 1,000,000`) and mirrors it onto every player's XP bar as one shared life bar; `XpHealth` **stands down entirely** — its `pooled()` short-circuits drain/seed/hearts/HUD (`XpHealthChallenge.java:60`) and defers the heart scale to `SharedLife`. Run **solo**, each falls back to its classic per-player mechanic.

### Mobs, stats & HUD

- **`MobRegistry`** (`MobRegistry.java`) coordinates the mob twists (`Cleave`, `MobDuplication`): a per-tick `nearbyMobs` scan cache, a per-tick spawn budget (default **16/tick**) that caps `MobDuplication`'s snowball, and a `cleaveActive` guard so `Cleave`'s splash damage cannot recursively duplicate the horde.
- **`ExperienceStats`** (`ExperienceStats.java`) — persistent counters under the `stats.` namespace (total + per-player deaths recorded once in `softRespawn`, blocks broken per manual break, plus generic `add/get`), so cross-cutting HUD figures are tracked once.
- **`ExperienceHud`** (`ExperienceHud.java`) — **one** per-player scoreboard panel via the same `UiAdapter.createPanel` library the lobby HUD uses. The host prepends a shared section (active challenge **NAMES** + Deaths + Blocks broken — names, not just a count, so a mobile player can see which twists are live); each challenge appends a `HudContributor`. Repaints on `hud-refresh-ticks` (default 20, min 5). The old design's boss bars / action-bar rows / `StatusPanel` are gone.

---

## Persistence (file-based, world-local)

`ExperienceStateStore` (`ExperienceStateStore.java`) writes flat `.yml` (the same self-contained format core uses for NPCs — no YAML library) **inside** the never-deleted world folder:

```
<experiencesSubdir>/<map>_<id>/sexidium/state.yml           # shared challenge state + stats
<experiencesSubdir>/<map>_<id>/sexidium/players/<uuid>.yml  # one player's snapshot
<experiencesSubdir>/<map>_<id>/sexidium/holder.json         # which node holds the lease (advisory)
```

There is **no `<nick>/` level**. The registry used to record one and the world layer silently dropped it, so an experience's `world_name` changed shape halfway through its life and an exact lookup never matched. `WorldKey` is now the single spelling everywhere — `<map>_<id>`, plus `_r<n>` once the world has been regenerated.

State therefore shares the world's lifecycle (travels with a copy, deleted with the world). `relativeKey` (`ExperienceStateStore.java:143`) strips up to and including the subdir segment, so the store resolves the same folder whether handed the stable key or the runtime slash-path world name.

- **Persistent only.** Only PERSISTENT experiences (a folder under `experiencesSubdir`) persist; transient temp-world experiences keep state in memory — `saveSharedState`/`savePlayerSnapshot` no-op when `!persistent(worldName)` (`:55`, `:90`).
- **Live coordination object.** `ExperienceState` (Props-backed) is the live in-memory object behind each twist's typed `state*` helpers. `setX → onChange → ExperienceGame.scheduleStateSave` debounces (40-tick) to one `flushState` write; `stop()` does a final **synchronous** flush before cleanup (`ExperienceGame.java:529`).
- **Legacy DB is migration-read-only.** The old `experiences.challenge_state` column and `experience_players` table are read **once** to seed the `.yml`, then all writes go to file; the old DB write methods were removed (`ExperienceManager.java:121`). `ExperienceManager.challengeState`/`loadPlayerState` remain only as that one-time migration source.
- **Per-player record.** `PlayerSnapshot` (`game/persist/`) holds uuid, name, world, xyz, yaw/pitch, gamemode, health, food, `inventoryPayload`, Props data. `captureLive` serializes the inventory via `InventorySerializer`; `applyTo` restores inventory + state.
- **Entry restore.** The host overrides `entrySpawn` to return the saved position, so `GameManager` teleports there **once** (no spawn-then-re-teleport). `resolveEntryPosition` reuses a saved position **only** when it was captured in **this** world (short-name compare) else `world.safeSpawnPosition()` — guarding the old wall/water teleport bug. `enterClean` clears the inventory (config `clear-inventory`, default true) **before** restoring; `capturePlayerState` refuses to persist a foreign-world position. Autosave every `autosave-ticks` (default 600 = 30s) plus on disconnect/leave.

---

## Ownership, registry & join model

`ExperienceManager` (`ExperienceManager.java`) is the SQLite `experiences` registry (columns `id, owner_uuid, owner_name, world_name, display_name, challenges` CSV, `is_public, created_at, updated_at, challenge_state, mode, world_type, keep_inventory`, and the nullable `backup_of` — see [Backups](#backups-a-backup-is-an-experience)). All access is synchronous under `Database.lock()`; with no database it no-ops (and `ExperienceService` falls back to a transient leased world). Experiences are **never auto-deleted** — only owner delete or ban. The world key is `<owner-nick>/<map-name>_<id>`.

`ExperienceService` (`ExperienceService.java`) centralises entry, the join-request inbox and owner controls:

- **Per-player cap** `worlds.experiences.max-per-player` (default 10, min 1) is enforced in `createAndStart` via `countByOwner` (`ExperienceService.java:78`).
- **Direct entry** (`canEnterDirectly`, `ExperienceService.java:137`) for: the **owner**, OR a **public** experience (`isPublic` means open to everyone — not merely "listed"), OR a **friend** of the owner (`FriendService.areFriends`), OR a **member of the owner's Lobby** (`lobbies.lobbyOf(owner).isMember` — the party path; the standalone `party` command no longer exists, party is merged into the unified Lobby, now the `/lobby` global). Everyone else files a join request the owner accepts/denies.
- `enter` resumes a live match or starts the persistent world; `createAndStart` registers + starts a new one.
- Optional `worlds.experiences.require-owner-online` (default false) gates **non-owner** entry to host-online; owners always enter their own worlds.
- `updateChallenges`/`delete` end any live match first; `delete` also calls `worlds().deletePersistent` and clears pending requests.
- **A shared map shows its owner's latest state.** The registry row is the source of truth and every
  screen is a fresh query, but a *running* world composed its challenges once at start and an *open*
  menu was drawn once at open. So every owner action (`setVisibility`, `rename`, `updateChallenges`,
  `updateChallengesLive`, `updateWorldType`, `setKeepInventory`, `setHardcore`, and a completed
  delete) announces itself on `NetworkBus.Topics.EXPERIENCE_UPDATED`, and each node re-reads the row:
  `reconcileLive` makes the live world agree with it (one `updated_at` read, skipped for a Chaos world
  — it has no stored set — and for a lost hardcore world, which is frozen), and
  `MenuService.refreshExperienceScreens()` redraws the experience screens players here still have
  open. `reconcileLive` also runs on every entry, so a visitor joining a world that has been up for
  hours plays the set the owner has now, not the one it started with. See
  [network-transfer.md §5c](../operations/network-transfer.md).
- **Restart resume** reuses the reconnect machinery (`isReconnectable() == true`): a reconnectable match is persisted on shutdown and its world preserved; `ExperienceGame.restore` re-wires challenges via `initChallenges` so events never hit a null host, and the player is restored to their exact saved position.

### Backups (a backup IS an experience)

**Take a backup now**, on the world's own **Backups** screen (reached from slot 15 of the manage
screen, immediately left of Rename), produces an exact,
independent copy of a world: the terrain of **all three dimensions**, every per-player save
(inventory, XP, health, food, effects, position, gamemode) and every challenge counter (Death Resets'
reset count and day baseline, in-game days, per-player deaths and played seconds, timestamps).

**The copy is a real experience** — its own registry row, its own `world_key`, its own folders — that
remembers where it came from in a nullable `backup_of` column (`Experience.backupOf()` /
`Experience.isBackup()`). That is the whole design: *restoring is entering the backup*. There is no
restore engine, and nothing ever has to put bytes back underneath a running world.

- **Where it runs.** `ExperienceCommandRouter.Op.BACKUP`, with `touchesTheFolder() == true`, so it is
  routed exactly like `DELETE`: to the node recorded in `world_placements.node_id`, lease or no lease.
  The owner clicks from the **lobby**, which never holds an experience world — resolving the target
  off the live lease would have the lobby copy a folder it does not have. The request is written to
  `experience_commands` before it is announced, so a worker that is restarting still runs it on its
  next drain.
- **A copy may be taken while people are playing** (`worlds.experiences.allow-live-copy`, default
  `true`). This is a deliberate reversal of the original rule, and the tradeoff is real: an experience
  world is a dimension of the node's own level, so nothing on disk stops a second reader, and an
  in-place `save()` returns *before* the async chunk writes land — there is no true flush short of
  unload-with-save. Being unable to back up the world you are actually playing was the wrong answer to
  that, so the copy is **verified instead of refused**, in this order:
  1. `WorldLeaseService.saveExperienceNow(key)` flushes the Overworld **and both linked dimensions**
     where they stand (`AbstractWorldControl.saveExperienceNow` → `backendSaveWorld` →
     `PaperWorldControl`'s `World.save()`). It narrows the window; it does not close it, and its
     javadoc says so.
  2. Every folder is copied inside `WorldClone.copyWorldFolderChecked`'s before/after inventory
     bracket — relative path → size + **nanosecond** mtime, which is what lets a same-length rewrite
     of a one-byte counter be seen at all.
  3. A source that moved is copied again, up to `AbstractWorldControl.COPY_ATTEMPTS` (3) times **per
     dimension folder**, and then refused. That retry is the *only* one in the path: a second loop
     around the verb would multiply into nine tree copies and would re-run the dimensions that
     already copied cleanly.

  So the failure this opens is `FAILED` ("your backup did not happen"), never a copy that is quietly
  not the world it claims to be — a torn folder is never published and never given a row. A world
  under heavy play may genuinely never settle; "try again when it is quieter" is a true instruction,
  and it is what the message now says. Set `allow-live-copy: false` to put the old `Outcome.BUSY`
  refusal back for `backup` / `refresh` / `duplicate`.
- **What is still refused while loaded.** The line is *whether the operation can be checked
  afterwards*. A copy can, so it runs. These cannot, so they still answer `BUSY`, on this node **and**
  across the fleet via `PlacementGate.heldElsewhere`, whatever `allow-live-copy` is set to:
  - **`restore`, on both worlds.** The swap rewrites the `world_key` a running match's
    `ExperiencePersistence` is bound to, and only `ExperienceWorldReset` — which owns the match, the
    countdown and the teleports — can move a live match between worlds. There is nothing here a
    verification pass could catch after the fact; there is only a live match to break.
  - **the DELETE at the end of a `refresh`.** Reading a live world is fine; removing a folder someone
    is standing in is data destruction with no undo. The old copy is checked at the top of the verb
    *and again immediately before `deletePersistent`* — locally and across the fleet — because a proof
    taken before a multi-minute copy is a proof about a different moment. If it was re-opened, the
    delete is skipped, a `SEVERE` line names the orphan folder, and the verb still reports
    `REFRESHED`: the row already names the new copy, which is the whole verb.

    Two more questions were added around that delete, and both are about the **registry**, not about
    who has the world open:
    - **The re-point is conditional.** `ExperienceManager.repointBackup(backupId, expectedWorldKey, …)`
      (`ExperienceManager.java:739`) updates only `WHERE … world_key = <the folder this copy was taken
      to replace>`. A `restore` of this same backup can commit during the minutes the copy runs and
      move both rows onto each other's folders; an unconditional re-point would hand this row the new
      copy and leave the folder the SOURCE is now running named by nobody — which the delete below
      would then remove. Zero rows updated *is* that case: the new folder is deleted, the row still
      names what it named, and the verb ends `FAILED` having changed nothing. The backup's
      `experience_players` pointers move inside the same transaction, so a member who walked into the
      copy and disconnected hard is not handed a folder that is about to be deleted.
    - **A folder some row still names is never deleted.** `experiences.namedBy(oldKey)`
      (`ExperienceManager.java:1170`, `ExperienceBackupService.java:568`) asks the registry "is this
      folder still nobody's?" — the one question `experienceWorldLoaded` / `matchRunning` /
      `heldElsewhere` cannot pose, since a restored world whose match has not started yet is open to
      nobody. The answer is a `Naming` record (`ExperienceManager.java:1189`) carrying `known` as well
      as the row, because `byWorld` funnels a `SQLException` into a warning and an empty list: "nobody
      names it" and "the registry could not be reached" would otherwise arrive as the same null, and
      this is the one place that difference destroys a living experience's terrain. Named **or**
      unanswerable ⇒ skip the delete, log `SEVERE`, still report `REFRESHED`.
- **One verb per experience at a time.** `ExperienceBackupService` keeps an `inFlight` id set
  (`ExperienceBackupService.java:133`) claimed all-or-nothing before the copy starts (`claim`, `:652`)
  and released on **every** exit — the failures, the continuation, and a continuation that *threw*
  (`releasing` / `answering`). A second `backup`, `restore`, `refresh` or `duplicate` on ids already
  held is answered `BUSY`: the same word the owner already knows from a world somebody has open, and
  the same instruction — try again in a moment. Every gate in this engine is a check-then-act with a
  multi-hundred-megabyte folder copy in the middle, and nothing used to mark a row as being worked on
  for that long: two refreshes of one backup both passed every gate, both completed, and the loser's
  folder ended up named by no row at all — hundreds of megabytes nothing lists and no sweep collects
  (`cleanupStaleBackupStaging` only touches `.incoming-*` staging). The claim key covers the **source**
  id as well as the backup's, because a refresh and a restore of the same copy touch the same pair of
  rows from opposite ends. The claim is released *before* the owner is told `BUSY`, so clicking again
  immediately — which is exactly what the message asks for — is not refused by the claim that just
  answered.
- **Entering a copy changes it.** Minecraft writes to any world it loads. On the live network a
  backup's `game_rules.dat` and its spawn region were rewritten minutes after the copy was taken,
  purely because the owner opened it to look at it. Nothing was lost — the terrain and the saves are
  all there — but *"byte-identical to the instant it was captured"* stops being true the moment
  somebody walks in. To inspect a copy without contaminating it, **`duplicate` it and enter the
  duplicate**.
- **Per-experience cap** `worlds.experiences.max-backups-per-experience` (default 3, `0` disables).
  Checked in `ExperienceService.backup` *before* routing (so a full experience never writes a request
  row per click) and again authoritatively in the engine. Hitting the cap answers
  `Outcome.LIMIT_REACHED` and **never auto-deletes** an older backup. A backup is never copied itself,
  which the same gate refuses. Backups do **not** count against `max-per-player` (`countByOwner`
  ignores them).
- **Outcomes** are one shared enum for every verb —
  `CREATED / RESTORED / REFRESHED / DUPLICATED / QUEUED / BUSY / LIMIT_REACHED / NOT_OWNER / GONE /
  NO_SPACE / FAILED` (`ExperienceBackup.Outcome`) — each with its own `experience.<verb>.*` message in
  both catalogs. One enum on purpose: every `switch` over it in `ExperienceMenu` and `BackupCommands`
  is exhaustive with **no `default`**, so adding a twelfth constant stops the build until every screen
  and every console line has decided what to say about it.
  `QUEUED` covers both "the holder has not answered yet" and "the copy is under way here": in neither
  case is there a finished world to promise. A *finished* copy announces itself on
  `EXPERIENCE_UPDATED`, which is what makes the new row appear in a "My Experiences" list the owner
  already has open on another node.
- **Deliberately NOT copied.** Vanilla `playerdata/`, `advancements/` and `stats/` are **server-global**
  — they do not belong to an experience world at all, and copying them would rewrite a player's whole
  profile. The `experience_players` rows are pointers ("this player was last in this world"), not
  state, so they stay with the source; the backup's per-player state lives in its own copied rows. The
  invariant those pointers keep is that **a member row names the world its own `experience_id` names**,
  which is why both `swapWithBackup` (restore) and `repointBackup` (refresh) move the affected side's
  pointers *inside the same transaction* as the row itself. Without that, a refresh left every
  `experience_players` row of the copy naming the OLD folder — the one the very next step deletes — so
  a member who had walked into the copy and disconnected hard came back to `rememberedWorldOf` handing
  them a world that no longer existed.
- **A copy's display name is only *sometimes* different from its source's.** A copy taken now is named
  `"<source> (backup)"` — `ExperienceBackupService.backup` passes a null display name to
  `ExperienceManager.backupDisplayName`, which appends the suffix. It did **not** used to: the engine
  passed the source's name verbatim, so the suffix never ran, and the live database still holds rows
  from that era — two "Death Resets", same owner, same twists, no way to tell them apart by name.
  Those rows are not renamed retroactively. So the design rule stands regardless of the suffix:
  **the screens are the only place a player can reliably tell a copy from its world**, which is why
  every tile that draws a copy says what it is a copy *of* and when it was taken, and why the console
  output leads with the id. Two
  facts are deliberately **not** shown anywhere: the in-game day count and the death count. They would
  have to be decoded from `experiences.challenge_state`, and on the live server that column is *stale*
  — it lags the `state.yml` inside the folder by hours and can be missing a challenge's counters
  altogether. A wrong number is worse than no number on the one screen whose job is telling two
  byte-identical worlds apart. A member list is out for the same kind of reason:
  `experience_players` is not copied, so every copy would read as empty.
- **In the list.** "My Experiences" orders sources first with each backup directly beneath its source,
  drawn as a `bundle` in aqua with the source name and the time it was taken. The body is 18 tiles
  (slots 9–26) for up to 10 worlds + 30 copies, so `ExperienceMenu.tilesFor(owned, 18)` decides who
  gets one: **a copy is never allowed to displace a world**, because that tile is the only route to
  entering, renaming or deleting it. Copies are dropped from whichever source still has the most of
  them (`fattestGroup`, so one much-copied world cannot spend the tiles the other sources' copies
  would have used), and within a group **the oldest goes first** — the DB hands them over oldest-first,
  so the ones kept are the ones most recently taken (`ExperienceMenu.java:484`). That end is not
  arbitrary: it is the same end the **Backups** screen keeps when it runs out of row slots
  (`newestBackups`, `ExperienceMenu.java:612`). The two screens have to agree on which copy matters,
  and they used to disagree — this list dropped the *newest* first, so the copy an owner had just
  taken, the one both "backup done" messages promise will be here, was guaranteed a slot on the
  Backups screen and was the very first row thrown away on this one. A copy whose source has been
  deleted is dropped last, since no other screen lists it.
- **On the source's manage screen.** Slot 15 is a **doorway**, not the copy list: a `bundle` reading
  `Kept: n/max` that opens the world's own **Backups** screen. The copies used to be drawn inline at
  slots 19–21 and 23–26; seven of them left that screen with nothing free and still could not show
  what a copy *is*. The doorway is drawn whenever `room > 0 || taken > 0`, so turning
  `max-backups-per-experience` down to `0` switches off *taking* new copies without hiding the ones an
  owner already has — nothing else lists them all. It stays hidden on a world that IS a copy and on a
  **lost** hardcore world, whose screen says in as many words that spectating, renaming and deleting
  are all that is left.

#### The verb set

Six verbs ship. Each one is a tile on the copy's own screen and a
[`/sx admin backup`](../interface/commands.md#sx-admin-backup) verb, and each destructive one arms with a **second
tap** through `MenuSupport.confirmButton` (`MenuSupport.java:406`). **Two taps, always — there is no
shortcut past it.** A shift-click used to confirm outright, and in a chest that is the reflex gesture
for "move this item", so it fired Restore, Refresh and Delete off a tap the owner never meant as one;
it is gone. What counts as the second tap is `MenuSupport.isTap` (`MenuSupport.java:394`) and it is
**only `LEFT` and `RIGHT`** — the gesture the armed tile's own text promises. Everything else a chest
delivers as an `InventoryClickEvent` (a number key, `Q`, `Ctrl+Q`, `F`, a creative click, a
double-click) re-arms instead of confirming, costing the owner nothing but another tap. A
**double-click is deliberately not two taps**: Bukkit sends it as a `LEFT` event followed by a
`DOUBLE_CLICK` one, so while `PaperMenuAdapter.mapClickType` folded `DOUBLE_CLICK` into `LEFT` one
flick of the mouse *was* both halves of the gesture; it now falls through to `OTHER` with the number
keys, the drops and the swaps. LEFT is also what makes this the one gesture a Bedrock/mobile player can
perform, since Geyser delivers every tap as a plain `LEFT` click. The four confirm tokens —
`restore:`, `refresh:`, `duplicate:`, `deletebackup:` — are all distinct from the manage screen's
`delete:`, because there is exactly **one** `pendingConfirm` slot per player and a shared token would
let arming one verb disarm another across screens.

**Every routed verb closes the menu the moment it fires**, before the *working* line is sent
(`serverAdapter.menus().close(clicker)`: `ExperienceMenu.java:739` take-a-backup, `:874` Delete this
copy, `:950` Restore, `:1019` Refresh, `:1077` Duplicate, `:1309` Delete the source). A routed verb stays in flight until its ACK times
out, and every tile on a screen that is still open is still clickable — two more taps queued a second
copy on top of the one already being taken. **No outcome callback reopens a screen either.** The
answer can be minutes behind the click (the armed lore says in as many words that the owner can keep
playing meanwhile), so it arrives as localized chat and nothing else: a chest — a Cumulus popup, on
Bedrock — springing open over whatever they walked off to do is the one thing this must not do. The
new row is on the Backups screen when they next open it.

| Verb | What it does | What it costs |
| --- | --- | --- |
| **Enter** | Opens the copy as the world it is. | Nothing; the live world is untouched. |
| **Restore** | The copy becomes the live world again. | No bytes move — see below. |
| **Refresh** | Re-takes this copy from its source. | A **full re-copy** every time. |
| **Duplicate** | A new *playable* world made from the copy. | One of the owner's `max-per-player` world slots, permanently. |
| **Rename** | The display name is the label. | Nothing. |
| **Delete** | Removes the copy. | Nothing; the source is not touched. |

**Restore keeps your current world.** It is a *swap*, not an overwrite: the source row starts naming
the copy's folder and the copy row starts naming the folder the source was just using, renamed
`<name> (before restore)` and forced private. Nothing is deleted and nothing is copied, because the
world being replaced already *is* a perfect backup of itself, byte for byte. The count of copies is
unchanged — the restored one stops being a copy and the replaced one becomes one.

Three things a player has to be told before they confirm a restore, and the armed tile lore states
them in this order:

1. **Your current world is kept as a copy.** Nothing is thrown away.
2. **Anyone who joined after this copy was taken starts with nothing.** They have no per-player
   snapshot inside it, so they arrive at the safe spawn empty-handed. That is correct restore
   semantics, not a bug.
3. **Nobody may be inside either world.** Both are refused `BUSY` while loaded or holding a live
   match, and the copy is refused too when another node holds it. Unlike taking a copy, this refusal
   has no `allow-live-copy` escape and never will: a copy can be verified after the fact, a swap
   cannot.

**Restore rolls the run statistics back too.** `stats.run.*` lives in the `state.yml` *inside* the
restored folder, so a restore puts the played time, the death count and the day baseline back to what
they were when the copy was taken. That is deliberate: a run's statistics are part of the run.

**Refresh reads a live source but never deletes a live copy.** The source half follows
`allow-live-copy` like any other copy; the old folder it replaces is gated strictly at both ends. See
the two bullets above — this asymmetry is the single most expensive thing in this feature to get
wrong, which is why `ExperienceLiveCopyTest` spends a test on each end of it.

**Refresh is a full re-copy, not an incremental one**, and the tile lore says so. The cheap
alternatives were all rejected: an mtime+size skip produces a silently wrong copy when a same-length
rewrite lands inside one timestamp tick; a sha256 manifest reads every byte, so it trades the writes
for the reads twice; and hard links are *actively unsafe*, because Minecraft rewrites `.mca` sectors
**in place** through a random-access handle — a hard-linked region file shared with a live world is
mutated by ordinary gameplay, and a copy that changes while you play is not a copy. Reflink/CoW is a
possible future fast path, gated on the server's filesystem, and is not in this build.

**A copy of a deleted source is promoted, not orphaned**: deleting a world sets `backup_of = NULL` on
its copies. They become worlds in their own right and from then on count against `max-per-player`,
which can push an owner over the cap. That is a soft over-cap — the cap is only checked on create.

**The Delete tile says how many will be promoted, before the first tap.** This is the exact opposite of
what the rest of the feature promises ("Nothing is ever deleted for you", "the world it was copied from
is not touched"), and no other screen states it — so an owner deleting a world to make room can come
back to a list *further over* the cap than before. The count is read once for both tiles that need it
(slot 15's `Kept: n/max` doorway and this lore), because asking the registry twice is how the two end
up disagreeing, and it is read for a **lost** world too even though its doorway is hidden: its copies
outlive it, Delete is one of the three verbs its screen says are all that is left, and on Bedrock that
line is the *only* lore line the tile shows. It is therefore lore line **0** on both the idle and the
armed face — `PaperFormRenderer` gives a Bedrock player the button's name plus line 0 and nothing
after it, and "this tile deletes the world" is already what the tile is *called*.

### Commands & GUIs

```
/sx start experience <challenge…> [--world=normal|nether|end] [--keep-inventory=true|false]
                                      build & start a new experience
/sx experience list                   your experiences
/sx experience join <id>              enter (or request to enter)
/sx experience requests               pending join requests
/sx experience accept|deny <player>   approve / reject a request
/sx experience public|private <id>    toggle visibility
/sx experience rename <id> <name>
/sx experience delete <id>            permanently deletes the map (owner only)
```

Backups have an operator surface of their own — see
[`/sx admin backup`](../interface/commands.md#sx-admin-backup) for `list | info | create | restore | refresh |
duplicate | delete | pending`. It is console-safe (it never requires a player) and resolves the row's
true owner as the requester, which is what makes the whole feature testable on the live network
without a client standing in the lobby. There is still no *player* `/sx experience backup` command:
the tiles are the player's entry point.

GUIs (built on the [menu system](../interface/menus.md)): **Experiences** (builder / challenge picker + the **World** and **Keep Inventory** tiles), **Choose World** (the single-choice map-type screen), **My Experiences** (manage; slot 15 is the **Backups** doorway, **Delete** confirms with a second tap), **Backups** (every copy of one world + "Take a backup now"), **Backup** (one copy: Enter / Restore / Refresh / Duplicate / Rename / Delete), **Browse Worlds** (public).

---

## Auto team system (`game/team/`)

The team system belongs to the **minigame** side (not Experiences) but is in scope here.

- **`TeamColor`** (`TeamColor.java:9`) is the 8-colour palette `RED, BLUE, GREEN, YELLOW, AQUA, PINK, ORANGE, WHITE`, each with a display name, a MiniMessage tag and a wool `ItemKey`. The palette size caps the maximum team count (`maxTeams()`).
- **`Team` / `Teams`** provide `teamOf` / `teammates` / `sameTeam` / `teamsWithAnyOf` / `remove`.
- **`TeamAllocator`** has three entry points (`TeamAllocator.java`):
  - `allocate(players, playersPerTeam)` (`:82`) — auto-sizes `count = clamp(ceil(players / size), 2, palette)`, round-robin balanced (sizes differ by ≤ 1).
  - `allocateFixed(players, teamCount)` (`:31`) — exactly the host-chosen count.
  - `allocateAssigned(players, teamCount, assignments)` (`:46`) — honours explicit per-player team-index assignments, then balance-fills the rest.
- **`TeamHudContributor`** (`TeamHudContributor.java:24`) builds a per-viewer right-side scoreboard panel: your team (coloured) + allies + rivals with sizes.

`MinigameMode` (`MinigameMode.java`) wires `teamPlay`/`playersPerTeam`/`formTeams`/`lastTeamStanding`/`awardTeamWin`/`sameTeam`. The per-match size comes from the mode arg `size:<n>` (legacy `team:<n>`, or `ffa`) (`MinigameMode.java:119`), else config `minigames.<id>.players-per-team` (default 0 = FFA). `formTeams` (`:149`) uses `allocateAssigned` when a count/assignments arg is present, else `allocate`, and shows `TeamDisplay` unless `usesTeamSidebar()` is overridden false (Race / TNT War fold team info into their own board).

---

## Config namespace

Defaults live in `config.yml` (`config.yml:521`) under `experiences:`.

`experiences.common.*`:

| Key | Default | Meaning |
|---|---|---|
| `prepare-players` | `true` | heal + survival at start |
| `clear-inventory` | `true` | clear lobby inventory on entry (then restore saved) |
| `starter-kit` | `''` | kit handed out on start |
| `teleport-to-world-spawn` | `false` | move to world spawn on start |
| `duration-seconds` | `0` | 0 = run until `/leave`/stop |
| `announce-descriptions` | `true` | announce twist descriptions |
| `release-players-on-stop` | `true` | release + reset on stop |
| `hud-refresh-ticks` | `20` | unified HUD repaint cadence (min 5) |
| `autosave-ticks` | `600` | per-player snapshot autosave (30s) |

`experiences.modes.<id>.*` holds one section per registered challenge (the keys match the 27 catalog ids exactly — the dead `fleeingblocks`/`nogreen`/`jumpgravity` blocks have been removed from `config.yml`, so there are no orphaned keys). Notable per-mode defaults: `doubledrops.max-drops-per-break 65536` / `stream-seconds 10`; `xphealth.starting-xp-points 30` / `xp-per-damage-point 4.0` / `display-health-scale 2.0`; `breakonebreakall.blocks-per-tick 256` / `loot-mode chunk-stacks`; `sharedlife.max-health 20.0` / `healing-multiplier 1.0`; `chained.death-link false`; `jumpmultiplies.radius 12.0` / `max-clones-per-jump 1024` / `max-per-tick 256` / `max-live-entities 4000`.

World-level keys live under `worlds.experiences.*` (`max-per-player 10`, `max-backups-per-experience 3`, `allow-live-copy true`, `require-owner-online false`, `namespace`/`subdir`) — see [lobby, worlds & social](lobby-worlds-and-social.md) for world leasing and the persistent-world layout.

---

## Keeping this current

The **code is the source of truth**; this doc is a derived view. Authoritative files: `game/experience/ChallengeCatalog.java` + `game/experience/challenges/*Challenge.java` (the catalog), `game/experience/ExperienceGame.java` (host/handle/lifecycle), `game/experience/compose/*` (pipelines, `HealthModel`, `MobRegistry`, `ExperienceHud`, `ExperienceStats`), `ExperienceStateStore.java` + `ExperienceManager.java` + `ExperienceService.java` (persistence/registry/join), `ExperienceBackup.java` + `ExperienceBackupService.java` + `ExperienceCommandRouter.java` (backups and where owner actions run), `WorldClone.copyWorldFolderChecked` + `AbstractWorldControl.saveExperienceNow`/`copyExperienceFolders` (the flush, the verification bracket and the bounded retry a live copy rests on), `game/team/*` (teams), `ChallengeCatalogTest.java` (catalog assertion), and the `experiences:` / `worlds.experiences:` blocks of `config.yml`.

Update this doc **in the same change** that touches those files. Triggers: a challenge added/removed (update the catalog table **and** the `ChallengeCatalogTest` count), a new pipeline/contributor or a changed contributor `order()`, a host `handle`/lifecycle behavior change, a `TeamAllocator`/`TeamColor` change, a persistence-path or migration change, or any `experiences.*` / `worlds.experiences.*` config key added or removed.
