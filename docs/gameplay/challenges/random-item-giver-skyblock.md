# Minecraft Game Mode: RANDOM ITEM GIVER Skyblock (One Block)

## Core Mechanics
You spawn in the **void on a single special block** — "Minecraft But You Only Get ONE BLOCK." There is no terrain anywhere; the entire run is built from that one block. **Breaking the block drops the block itself** and it immediately **regenerates**, so it is an infinite source you mine over and over. Items are **never handed to your inventory** — you earn everything by mining and from chests. The longer you grind, the further the source **progresses**: it starts as basic blocks (dirt, cobblestone, logs) and climbs through stone and ores to nether and end blocks ("advance to the next level"), so mining it yields steadily better materials. Every set number of breaks a **loot chest spawns right next to you**, drawn from a big weighted pool so that — between the blocks you mine and the chests you open — you obtain **everything needed to beat the game**: tools, food, buckets, flint & steel, obsidian, ender pearls, blaze rods, eyes of ender, up to netherite and the gear to kill the dragon.

Layered on top are two randomizers players lean into:
- **Random Mob** — random mobs (friendly or hostile) keep appearing next to you; hostiles can arrive **wearing random armour and wielding random weapons**, so "infinite mobs" is both a food/XP source and a real threat.
- **Randomized Block & Mob Drops** — every block you break and every mob you kill drops a *random* item, so nothing is predictable.

## Impact on Gameplay
- **The one block is everything:** food, wood, stone, ore, and eventually diamonds and netherite all come out of the same block — you never leave the island until you build out from it.
- **Progression is the pacing:** early breaks give scraps; as the level climbs the block yields better materials and the chests get richer, so "keep grinding" is the whole loop.
- **You must actually beat the game:** the pool is tuned so the essentials (buckets, obsidian, flint & steel, ender pearls, blaze rods, eyes of ender) all show up — the goal is a full run to the Ender Dragon.
- **Chaos from the randomizers:** a random creeper mid-grind, or a block that drops a diamond one swing and a stick the next, keeps every session different.

## Notable Strategies
- **Bank the source:** mine the one block continuously to bootstrap wood → tools → a base platform out over the void.
- **Chest runs:** watch the "next chest" counter and be ready — chests are the big loot spikes.
- **Tame the mobs:** funnel the random mobs into a pit so hostile ones are contained and passive ones become a farm.
- **Build outward:** use the random blocks to expand the island so you have room to fight, farm, and set up a portal.

## Endgame Adaptation
It is a full skyblock progression compressed onto one block: grind to iron and diamond gear, collect obsidian and flint & steel for a nether portal, gather blaze rods and ender pearls for eyes of ender, and finally build to the End and kill the dragon — every material sourced from the single block and the loot chests.

## Memorable Moments
- The first diamond popping out of a dirt-tier block.
- A creeper spawning right as a chest appears.
- A block that drops a totem of undying because the drops are randomized.

---

## Implementation Notes (sexidium)

Implemented as **three composable challenges** (they stack, and each also runs alone):

**`randomskyblock` — Random Skyblock** (`RandomSkyblockChallenge`). `requiresVoidWorld()` is true, so the experience generates as pure void via the existing world-gen path (`VoidChunkGenerator`); on first start it places the single special block at the player's feet using the `StructureBuilder` engine. Breaking that one block (matched by position):
- suppresses the vanilla drop and re-drops the block's **tool-aware natural loot** on top (`WorldAdapter.naturalDrops(pos, breaker)` → Paper `block.getDrops(heldTool, player)`): a pickaxe yields the **raw item** (raw iron, coal, diamond — not the ore block), Silk Touch yields the block, Fortune yields extra, and the wrong tool tier yields nothing. The drop is **stationary** (`WorldAdapter.dropItem(pos, stack, scatter=false)`) just above the block top so it lands cleanly and is never flung off the one-block platform into the void;
- regenerates the block as a random block from the **current level's palette** (`blocks-per-level` controls how fast levels climb: dirt/cobble → stone/ores → diamond/obsidian/nether → end). **Pickaxe gate:** until any participant is holding a pickaxe, the block only regenerates as **hand-mineable** blocks (dirt/sand/gravel/**logs**) — never bare stone/cobblestone/ore, which would drop nothing and soft-lock a fresh player. Logs stay available so a wooden pickaxe can always be crafted, which then unlocks the full palette;
- every `blocks-per-chest` breaks, a **filled loot chest appears at the exact progress-block spot** (facing the player). It is placed **one tick after the break** — placing it during the break event would let the vanilla break delete the fresh chest and drop the player into the void. The chest is **unbreakable** (the break at that spot is cancelled) and progression **pauses**: the block does not regenerate while the chest is there. A per-second `chestTick` watches `WorldAdapter.chestEmpty`; once emptied, the chest **auto-vanishes and the mineable block swaps back in**. Loot = several `LootTable` rolls from the **60-item pool** (`item-pool`, "id:min:max:weight").

**Chest fill fix.** `WorldAdapter.placeChest` fills the **live** `getBlockInventory()` and does **not** call `update()`. For a placed block state that live inventory is linked to the tile entity so the write persists immediately; calling `update()` (as before) wrote the empty captured snapshot back over it — which is why chests kept coming out empty.

**UI:** each player's broken-block count is shown **under their nameplate** (above their head) via the new `PlayerAdapter.setBelowName(label, value)` seam (Paper drives the vanilla `below_name` scoreboard slot); the **sidebar HUD** shows the level, total blocks broken, and **blocks until the next chest**.

**`randommob` — Random Mob** (`RandomMobChallenge`): every `interval-seconds`, spawns `mobs-per-spawn` random mobs from a 25-mob default list beside each player, and the sidebar HUD shows a live **"Next mob in m:ss"** countdown. Each spawned **hostile** has an `equip-chance` (default 0.5) of being given a random weapon (sword/axe/trident/bow) and a partial set of random-tier armour via `WorldAdapter.spawnMob(pos, type, count, equipChance)`; passive mobs are never armed.

**`randomdrops` — Randomized Drops** (`RandomDropsChallenge`): a `GENERATE`-phase drop contributor replaces each broken block's loot with a fresh random item, and `onEntityDeath` drops a random item at the kill — block AND mob drops randomized (distinct from `randomizer`, which is a fixed per-type remap). Pool defaults to the whole item registry.

**Keeping this current:** this file owns the Random-Item-Giver skyblock family; edit it here.
