# Minecraft Game Mode: RANDOM LAYER One Chunk

## Core Mechanics
The world is a single chunk whose vertical column is built out of **random layers of blocks** — "this one chunk has over 300 random layers of blocks… even the nether and the end are random layers, and it can range from lava to diamond blocks all the way to TNT traps." Every horizontal slice of the chunk is one uniform, randomly chosen block or a **randomly themed challenge layer**. Players call the format "one chunk, random layers all the way down" (multiplayer runs are pitched as "three dudes, one chunk"). Survival is hardcore: you work the top surface for wood, food and animals, then mine downward through slice after unpredictable slice.

Two kinds of layers exist:
- **Block layers** — a flat slab of one material: cobblestone (great, lets you craft tools), ores and ore-blocks (netherite block, diamond block, emerald), terrain and decorative blocks (prismarine, sandstone, froglights, shroomlights), and dead-weight or dangerous ones (a whole layer of water, of lava, of string, of levers as a "troll", of ancient debris you can't yet mine).
- **Challenge / themed layers** — the layers that try to kill you: **TNT layers rigged as traps** (players shout "I saw a TNT layer" / "there's a whole layer of TNT"), **mob-spawner layers** (a slice full of spawners — sometimes peaceful cows/pigs/pufferfish, sometimes "way too many" zombies, skeletons, spiders, drowned, bees, or a sculk/warden layer), and hazard slabs of lava or water.

The stated end goal: the **very last (deepest) layer is the End portal frame** — reach it, beat the Ender Dragon, and claim "the only original block in this entire world", the dragon egg.

## Impact on Gameplay
- **Every layer is a gamble:** a slice can hand you free diamond blocks or drop you into a TNT trap or a spawner swarm. "I have no idea if it's even possible to survive on this insane one chunk."
- **Resource scarcity by slice:** you're rationed to whatever the current layer is made of — no cobblestone until you hit a cobblestone layer, no water/lava until those slices appear (so no cobblestone generator, no farms, until the world gives them to you).
- **Mob-spawner layers are the spike:** "there's way too many in there", skeletons and spiders and drowned pouring out of a single slice; breaking the spawners fast is the only way through.
- **TNT layers are lethal terrain:** one primed block chains the whole slice; players brace in a corner and pray.
- **Falling is death:** it's one chunk over the void — knock-off from mobs, TNT, or a "friend" is an instant hardcore loss.

## Notable Strategies
- **Fence the surface:** trapdoors and little pens keep the starting animals (and yourself) from wandering off the edge into the void.
- **Torch the slabs:** light up cleared layers so hostile mobs stop spawning while you work.
- **Pop spawners first:** on a hostile spawner layer, ignore the loot and sprint the spawners, letting the mobs kill each other in the crossfire.
- **Read before you dig:** peek at the next layer down (TNT? lava? spawner?) before breaking into it.
- **Bank the good layers:** strip-mine an ore-block or netherite layer completely before moving on — you may never see that material again.

## Endgame Adaptation
The run is a straight vertical campaign: survive the surface, tech up whenever a useful layer (cobblestone, iron, redstone, water, lava) turns up, and grind down through the hazard layers toward the bottom. The deepest layers turn Nether- and End-themed, and the final slice is the End-portal frame — beating the dragon there is the win condition, with the dragon egg as the single hand-placed prize in an otherwise fully-random world.

## Memorable Moments
- Getting "baited so hard" by an ancient-debris layer that a stone pickaxe can't touch.
- A birch sapling growing into a tree that shoves a sheep off the edge — "this birch tree is a murderer."
- Opening a spawner layer that turns out to be peaceful pufferfish, sheep, pigs and a mooshroom.
- A cake layer, a bee layer, a drowned-with-trident layer, and a sculk layer that nearly summons the Warden.
- A troll "layer of levers" doing absolutely nothing useful.

---

## Implementation Notes (sexidium)

Implemented as a composable experience challenge, `id: randomlayers`, display **"Random Layers"** — see `packages/core/src/main/java/com/sexidium/core/game/experience/challenges/LayeredWorldChallenge.java`. It reads its tuning from `experiences.modes.randomlayers`.

**A VOID world-generation engine (SkyBlock-style).** This mode is a SkyBlock: the experience world must generate with **no natural terrain at all — pure void**. Because a challenge attaches only *after* its world exists, the void requirement is declared up-front via `Challenge.requiresVoidWorld()` (true here) and read at world-creation time: `ChallengeCatalog.anyRequiresVoidWorld(ids)` decides it from the stored challenge ids, `GameLauncher` passes it into `WorldLeaseService.acquireOrCreatePersistent(..., voidWorld, ...)`, it rides through `WorldSettings.voidWorld`, and the Paper backend binds a `VoidChunkGenerator` (empty terrain + a single-biome provider so the dimension stays valid) instead of copying the overworld generator. The spawn is pinned to `(0,64,0)` so players land on the island the challenge builds. Unported platforms fall back to a normal world (NeoForge deferred).

**A reusable world-generation engine.** The structure building lives in `com.sexidium.core.world.gen`, a platform-agnostic engine future challenges (other SkyBlocks) can reuse, with **100% test coverage**:
- `StructureBuilder` — `slab`, `stack` (layered slabs), `tree`/`scatterTrees`, and `chest` (loads the chunk first, then fills), all driven through `WorldAdapter` so they run identically on any backend.
- `LootTable` — a deterministic, seedable chest-loot generator: guaranteed stacks + a weighted pool rolled a configurable number of times.
- `TreeSpec` — the shape of a procedural tree (trunk block/height, leaf block/radius).

**The starting structure + distant chest.** On the first run in the fresh void world (guarded by shared state so it never rebuilds), the challenge builds, into the void:
- the **SkyBlock starting island** centred on spawn — **grass on top** (players stand on it) over **two layers of stone** (`island-layers`, `island-size`) — with **2–3 starter trees** scattered on the grass (`min-trees`/`max-trees`, via `StructureBuilder.scatterTrees`);
- a **distant island** `chest-island-distance` blocks away whose ground is **three layers — sand over sandstone** (`chest-island-layers`), carrying a **chest** whose loot is rolled from the `LootTable` (guaranteed lava/water/ice + a weighted pool of seeds/saplings; `chest-guaranteed`, `chest-rolls-min/max`). The chest is placed via `WorldAdapter.placeChest` (Paper force-updates the block entity so the fill always lands, even in a freshly generated void chunk).

**The layers grow over time.** After the island, the one-chunk column *grows*: **every half Minecraft day (`day-length-ticks`, default 12000 ticks = 10 minutes real) a batch of `layers-per-day` (default 3) new random layers is added** just beneath the island, deepening the pit. The cycle boundary is read from the world clock (`WorldAdapter.fullTimeTicks() / day-length-ticks`); a bounded catch-up loop handles skipped cycles. Each layer is an `N×N` slab (`layer-size`) at the frontier Y, its kind rolled by the pure, unit-tested `LayerDeck`:
- **Block layer** — a random material from a broad palette (`ChallengePalettes.COMPREHENSIVE_BLOCKS`; override with `palette:`).
- **TNT-trap layer** (`tnt-layer-chance`) — TNT with `stone_pressure_plate`s on top: step in and it detonates.
- **Mob-spawn layer** (`mob-layer-chance`) — a floor with a burst of hostile mobs via the `WorldAdapter.spawnMob` seam (Paper implements; others no-op).

**The right-side info panel.** HUD lines on the shared per-player info panel (`registry.hud`): **Layers added** (running total), **Day** (days passed since the experience began), and **Next layers in `m:ss`** (time until the next day rollover, from the live world clock). Debug adds the per-day count, palette size, and challenge-layer odds.

**Shared & persisted.** Layer count, day index and frontier Y live in shared experience state, so the column keeps growing across disconnects/restarts and never regenerates what it placed (and the island is built exactly once). Block/mob world changes are intentionally not reverted (they belong to the shared world).

**Keeping this current:** the owning doc for the Random-Layers experience is this file; edit it here rather than adding a new page.
