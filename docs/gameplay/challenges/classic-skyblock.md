# Minecraft Game Mode: CLASSIC Skyblock

## Core Mechanics
The original SkyBlock: you spawn in an endless **void** on a tiny **floating L-shaped island**. The island is grass on top over a body of dirt (the classic "81 blocks of dirt"), with **one oak tree** and a **starter chest**. From almost nothing you must expand the island, farm, build a cobblestone generator, and eventually tech all the way to the Nether and the End — every block earned from the handful of resources you start with.

The **starter chest** holds only the iconic classic loadout:
- **1 block of ice** (melt it for water — the other half of a cobble generator),
- **1 lava bucket** (the heat source for the generator, and later a fuel/tool).

A **far-off, separate island** — built from **sandstone** (the "harder sand" that doesn't gravity-fall into the void and needs a pickaxe), with **no bridge**, so you must find your own way across (bridge over, ender-pearl, etc., like the Random-Skyblock map) — holds a **cactus** (on a supported sand block) and the **second chest** of extra supplies: seeds (melon, pumpkin, wheat, sugar cane, cocoa), mushrooms and saplings, plus **flint & steel and 10 obsidian** for the Nether portal.

**The Nether mirrors the Overworld.** When you build and light your portal, the linked Nether is *also a void world* containing the same L-shaped island — but built from **Nether blocks** (netherrack) with a red **"nether tree"** (crimson stem topped with a nether-wart-block canopy), a glowstone and a lava source for light, and a **Nether starter chest** (nether wart, soul sand, quartz, glowstone, gold, magma cream, crimson/warped fungi).

## Impact on Gameplay
- **Scarcity drives everything:** one tree, one bucket of lava, one block of ice — every early decision matters (don't waste the lava!).
- **The cobble generator is the key:** water + lava makes infinite cobblestone, your first path to tools, a furnace and expansion.
- **Fall = death:** it's a tiny island over the void; a knockback or a misstep is fatal.
- **The Nether is a second SkyBlock:** it, too, is a void island — you fight for footing on the netherrack the moment you arrive.

## Notable Strategies
- **Build the generator first:** ice → water on one side, lava on the other, mine the cobble that forms between them.
- **Farm the distant chest's seeds:** melons/pumpkins/sugar cane are renewable food and trade goods.
- **Save obsidian for the portal:** the starter 10 obsidian is exactly a portal frame — don't burn it on an enchanting table too early.
- **Bridge carefully** to the sand island; the cactus is a mob-free defence and a green-dye source.

## Endgame Adaptation
It's a full vanilla progression from a single island: cobble generator → tools → tree farm → food → Nether portal → the mirrored Nether island (fortress-less, so blaze rods come from trading or bring-your-own) → the End. The whole run is a study in turning almost nothing into everything.

---

## Implementation Notes (sexidium)

Implemented as a composable challenge, `id: classicskyblock`, display **"Classic Skyblock"** — `ClassicSkyblockChallenge`. It reuses the shared world-gen engine end to end.

**Void world (Overworld + Nether).** `requiresVoidWorld()` makes the experience generate as pure void (`VoidChunkGenerator`). New: `requiresVoidNether()` threads a `voidNether` flag through world creation (`ChallengeCatalog.anyRequiresVoidNether` → `GameLauncher` → `WorldLeaseService.acquireOrCreatePersistent(..., voidWorld, voidNether, ...)` → `WorldSettings.voidNether`) so **only this mode's linked Nether sibling** is generated void (with a `NETHER_WASTES` biome); the other SkyBlock modes keep a normal Nether (no regression). The End sibling is always normal.

**The island(s), tree, chests — via the engine.** On first start it loads the island's chunks (`ensureChunks`, so a freshly generated void world never drops blocks) then builds the **L-shaped island** (a 6×6 top with the +x/+z 3×3 corner removed = 27 grass cells over dirt) with `StructureBuilder`, plants the oak with `StructureBuilder.tree`, and fills both chests with `StructureBuilder.chest` (fixed classic contents, oriented `west` — using the fixed `placeChest` that fills the live block inventory so the chests are never empty). The main island holds only the tree and the ice/lava starter chest; the seeds/obsidian chest sits on a **separate island ~18 blocks away with no bridge**.

**Nether mirror + safe landing.** A timer watches for a player entering a Nether world (`WorldAdapter.isNether()`); the first time, it builds the same L island from netherrack with a crimson nether tree, glowstone/lava, and a Nether chest at the Nether spawn, and **teleports the arriving player onto the island** (once per visit, tracked in `netherSeated`) so a void arrival is never fatal.

**Engine validation.** The chest-generation (`LootTable`, `placeChest` fill/facing) and world-creation (`StructureBuilder` slab/stack/tree/chest, `VoidChunkGenerator`) engines are covered at **100%** by `StructureBuilderTest`/`LootTableTest`/`TreeSpecTest`; this mode exercises them directly.

**Keeping this current:** this file owns the Classic-Skyblock mode; edit it here.
