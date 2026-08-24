package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.gen.LootTable;
import com.sexidium.core.world.gen.StructureBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random Skyblock (one-block skyblock). The experience generates as an empty VOID world (reusing the
 * world-gen engine) with a single special block at spawn. Breaking that one block drops the block itself
 * (you gain materials only by mining it), then it regenerates as a progressively better block, so the
 * deeper you grind the richer the source becomes (basic blocks first, then ores, nether and end). The
 * ONLY other way to obtain items is the loot chest that spawns beside you every {@code blocks-per-chest}
 * breaks (a big weighted pool with the tools/buckets/seeds/gems a run needs). Nothing is ever injected
 * into the inventory — everything is earned by mining or collected from chests.
 *
 * <p>UI: each player's broken-block count is shown under their nameplate (above their head); the sidebar
 * HUD shows the level, the total broken, and how many more blocks until the next chest.</p>
 */
public final class RandomSkyblockChallenge extends Challenge {
  private static final String KEY_BREAKS = "breaks";
  private static final String KEY_PLACED = "placed";
  private static final String KEY_CHESTS = "chests";
  private static final String KEY_CHEST_ACTIVE = "chestactive";
  /** Air kept above the source block — tall enough to clear anything a build can leave overhead. */
  private static final int SPAWN_HEAD_ROOM = StructureBuilder.STANDING_CLEARANCE;

  // ----- default 60-item weighted pool, spanning the full progression (higher weight = more common) -----
  private static final List<LootRow> DEFAULT_POOL = List.of(
      row("oak_log", 1, 4, 30), row("oak_planks", 1, 6, 24), row("cobblestone", 2, 8, 30),
      row("dirt", 2, 6, 20), row("stone", 1, 5, 18), row("sand", 1, 4, 16), row("gravel", 1, 4, 14),
      row("coal", 1, 4, 20), row("stick", 2, 6, 18), row("apple", 1, 3, 16), row("bread", 1, 2, 14),
      row("wheat_seeds", 1, 3, 14), row("oak_sapling", 1, 2, 14), row("torch", 2, 6, 14),
      row("iron_ore", 1, 3, 14), row("raw_iron", 1, 3, 14), row("iron_ingot", 1, 2, 12),
      row("copper_ingot", 1, 3, 12), row("flint", 1, 2, 12), row("string", 1, 3, 12),
      row("bone", 1, 3, 12), row("egg", 1, 2, 10), row("leather", 1, 2, 10), row("feather", 1, 3, 10),
      row("cooked_beef", 1, 2, 12), row("cooked_chicken", 1, 2, 12), row("carrot", 1, 3, 10),
      row("potato", 1, 3, 10), row("sugar_cane", 1, 3, 10), row("cactus", 1, 2, 8),
      row("water_bucket", 1, 1, 8), row("lava_bucket", 1, 1, 6), row("bucket", 1, 1, 8),
      row("redstone", 1, 4, 10), row("coal_block", 1, 1, 6), row("crafting_table", 1, 1, 10),
      row("furnace", 1, 1, 10), row("chest", 1, 1, 8), row("iron_pickaxe", 1, 1, 8),
      row("iron_axe", 1, 1, 7), row("iron_sword", 1, 1, 7), row("iron_shovel", 1, 1, 6),
      row("shield", 1, 1, 6), row("bow", 1, 1, 6), row("arrow", 4, 12, 8), row("fishing_rod", 1, 1, 6),
      row("shears", 1, 1, 6), row("flint_and_steel", 1, 1, 7), row("gold_ingot", 1, 2, 8),
      row("diamond", 1, 1, 5), row("diamond_pickaxe", 1, 1, 3), row("diamond_sword", 1, 1, 3),
      row("obsidian", 1, 4, 6), row("ender_pearl", 1, 2, 6), row("blaze_rod", 1, 2, 5),
      row("blaze_powder", 1, 2, 5), row("ender_eye", 1, 1, 5), row("nether_wart", 1, 3, 5),
      row("glowstone", 1, 2, 6), row("gunpowder", 1, 3, 6), row("slime_ball", 1, 3, 5),
      row("emerald", 1, 2, 4), row("experience_bottle", 1, 3, 6), row("golden_apple", 1, 1, 3),
      row("netherite_scrap", 1, 1, 2), row("diamond_block", 1, 1, 2), row("totem_of_undying", 1, 1, 1),
      row("enchanted_golden_apple", 1, 1, 1), row("elytra", 1, 1, 1));

  // Blocks that drop when mined BY HAND (no pickaxe needed). Everything else in the palettes (stone,
  // cobblestone, ores, netherrack, obsidian …) requires a pickaxe, so it is withheld until the party has
  // one — otherwise a fresh player is stuck on a block they cannot usefully mine. Logs stay available so a
  // wooden pickaxe can always be crafted to unlock the rest.
  private static final Set<String> HAND_MINEABLE = Set.of(
      "dirt", "coarse_dirt", "grass_block", "podzol", "rooted_dirt", "mud", "sand", "red_sand", "gravel",
      "clay", "oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log", "dark_oak_log",
      "mangrove_log", "cherry_log", "oak_planks", "oak_leaves", "hay_block", "pumpkin", "melon",
      "snow_block", "glowstone", "sea_lantern", "glass", "white_wool", "sponge", "cactus", "soul_sand",
      "soul_soil", "crimson_stem", "warped_stem");

  // Regenerated source-block palettes per level (index = level), basic → end-game.
  private static final List<List<ItemKey>> LEVEL_BLOCKS = List.of(
      blocks("dirt", "cobblestone", "oak_log", "sand", "gravel"),
      blocks("stone", "coal_ore", "copper_ore", "andesite", "granite", "diorite"),
      blocks("iron_ore", "gold_ore", "redstone_ore", "lapis_ore", "stone"),
      blocks("diamond_ore", "emerald_ore", "obsidian", "netherrack", "nether_quartz_ore"),
      blocks("end_stone", "purpur_block", "ancient_debris", "obsidian", "glowstone"));

  private LootTable chestLoot;
  private int blocksPerLevel;
  private int blocksPerChest;
  private boolean announceChests;

  // Per-player broken count for the below-name display (transient — the nameplate is a live view).
  private final Map<UUID, Integer> perPlayerBreaks = new HashMap<>();

  public RandomSkyblockChallenge() {
    super("randomskyblock", "Random Skyblock");
  }

  @Override
  public boolean requiresVoidWorld() {
    return true;
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    blocksPerLevel = Math.max(1, cfg().getInt(configPath("blocks-per-level"), 30));
    blocksPerChest = Math.max(1, cfg().getInt(configPath("blocks-per-chest"), 20));
    announceChests = cfg().getBoolean(configPath("announce-chests"), true);
    List<LootRow> pool = resolvePool();
    chestLoot = buildLoot(pool, cfg().getInt(configPath("chest-rolls-min"), 5),
        cfg().getInt(configPath("chest-rolls-max"), 9), true);                    // a filled chest

    WorldAdapter world = world();
    if (world != null && world.spawnPosition() != null && !stateHas(KEY_PLACED)) {
      // Place the single special block at the player's feet, into the void.
      placeSourceBlock(world, world.spawnPosition(), LEVEL_BLOCKS.get(0).get(0));
      setStateInt(KEY_PLACED, 1);
    }
    for (PlayerAdapter player : online()) {
      player.setBelowName("Blocks", perPlayerBreaks.getOrDefault(player.uniqueId(), 0));
    }
    // Watches an active reward chest so it auto-vanishes (and the block swaps back) once emptied.
    runTimer(this::chestTick, 20L, 20L);
  }

  /**
   * A regeneration handed the run a brand-new VOID world, so the one block the whole mode stands on has
   * to be placed into it again.
   *
   * <p>There is no terrain here at all — the entire map is a single block — so a world that does not get
   * one is not merely a worse start, it is a drop into the void, and the death that follows triggers
   * another reset. Composing with Death Resets is the only way this method is ever reached.</p>
   *
   * <p>The level-ONE block, not the one the run had reached. Progress goes back to zero with the world,
   * because the reset carries only what the mode's allowlist names and the break count is not in it —
   * and that is the right answer rather than an accident of the allowlist: Death Resets strips every
   * player of everything they were carrying, so a mining level that quietly survived would be the one
   * thing the death did not cost. The per-player nameplate counters are cleared for the same reason; a
   * block placed at a level the counters no longer agree with would swap tier on the very next break.</p>
   */
  @Override
  public void onWorldReset(WorldAdapter world) {
    perPlayerBreaks.clear();
    for (PlayerAdapter player : online()) {
      player.setBelowName("Blocks", 0);
    }
    WorldPosition spawn = world == null ? null : world.spawnPosition();
    if (spawn == null) {
      return;
    }
    placeSourceBlock(world, spawn, LEVEL_BLOCKS.get(0).get(0));
    setStateInt(KEY_PLACED, 1);
  }

  /**
   * Places the one block the mode stands on and makes sure the player can stand on it.
   *
   * <p>The head room is not decoration. A reward chest lives directly above this block while it is being
   * emptied, so a regeneration that catches the run mid-chest would otherwise rebuild the world with the
   * player's arrival column occupied — and there is nowhere else on the map to fall back to.</p>
   */
  private void placeSourceBlock(WorldAdapter world, WorldPosition spawn, ItemKey block) {
    BlockPosition source = sourcePos(spawn);
    world.setBlock(source, block);
    // Null footing: the block just placed IS the footing, and it is the mechanic — nothing may replace it.
    new StructureBuilder(world).reserveStandingSpot(spawn.worldName(),
        source.blockX(), source.blockY(), source.blockZ(), null, SPAWN_HEAD_ROOM);
  }

  @Override
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
    playerAdapter.setBelowName("Blocks", perPlayerBreaks.getOrDefault(playerAdapter.uniqueId(), 0));
  }

  @Override
  public void resetPlayer(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      playerAdapter.clearBelowName();
    }
  }

  @Override
  public void onBlockBreak(BlockBreakGameEvent event) {
    PlayerAdapter breaker = event.playerAdapter();
    if (!isParticipant(breaker)) {
      return;
    }
    WorldAdapter world = world();
    WorldPosition spawn = world == null ? null : world.spawnPosition();
    if (spawn == null) {
      return;
    }
    BlockPosition source = sourcePos(spawn);
    if (!source.equals(event.blockPosition())) {
      return; // only the one special block drives progression
    }

    // While a reward chest occupies the progress block, it is UNBREAKABLE: the player must empty it first.
    // The block-swap back to a mineable block only happens once the chest is emptied (see chestTick).
    if (stateInt(KEY_CHEST_ACTIVE, 0) == 1) {
      event.setCancelled(true);
      return;
    }

    int total = stateInt(KEY_BREAKS, 0) + 1;
    setStateInt(KEY_BREAKS, total);
    stats().add("randomskyblock.breaks", 1);

    int mine = perPlayerBreaks.merge(breaker.uniqueId(), 1, Integer::sum);
    breaker.setBelowName("Blocks", mine);

    // Players gain materials ONLY from the block itself (and from chests). Compute the block's TOOL-AWARE
    // natural loot BEFORE the vanilla break removes it (raw ore not the ore block; Silk Touch / Fortune /
    // tool tier respected), suppress the vanilla drop, and re-drop it straight down ON TOP of the block with
    // no scatter — so it lands on the regenerated block and is never flung off the tiny platform.
    List<ItemStackData> drops = world.naturalDrops(event.blockPosition(), breaker);
    event.setDropItems(false);
    WorldPosition dropAt = new WorldPosition(spawn.worldName(),
        source.blockX() + 0.5, source.blockY() + 1.2, source.blockZ() + 0.5, 0f, 0f);
    for (ItemStackData drop : drops) {
      world.dropItem(dropAt, drop, false);
    }
    breaker.playSound(new SoundKey("minecraft:entity.item.pickup"), 0.5f, 1.4f);

    if (total % blocksPerChest == 0) {
      // Threshold reached: the reward chest replaces the progress block. Place it ONE TICK LATER, after the
      // vanilla break has removed the block — placing it now would let Paper delete the fresh chest and drop
      // the player into the void. KEY_CHEST_ACTIVE is set only once the chest actually exists (so chestTick
      // never swaps a not-yet-placed chest).
      setStateInt(KEY_CHESTS, stateInt(KEY_CHESTS, 0) + 1);
      runLater(() -> {
        WorldAdapter live = world();
        if (live != null && live.spawnPosition() != null) {
          placeRewardChest(live, sourcePos(live.spawnPosition()));
          setStateInt(KEY_CHEST_ACTIVE, 1);
        }
      }, 1L);
      if (announceChests) {
        for (PlayerAdapter player : online()) {
          player.sendMiniMessage("<gold>✦ A loot chest appeared — empty it to keep mining!</gold>");
          player.playSound(new SoundKey("minecraft:block.chest.open"), 0.8f, 1.0f);
        }
      }
    } else {
      // Regenerate the one block (a progressively better block) one tick later, so the player lands on it
      // and the dropped reward rests on top instead of falling through.
      ItemKey next = levelBlock(total);
      runLater(() -> {
        WorldAdapter live = world();
        if (live != null && live.spawnPosition() != null) {
          live.setBlock(sourcePos(live.spawnPosition()), next);
        }
      }, 1L);
    }
  }

  /** Places the filled reward chest at the exact progress-block location, facing the player. */
  private void placeRewardChest(WorldAdapter world, BlockPosition source) {
    new StructureBuilder(world).chest(source.worldName(), source.blockX(), source.blockY(), source.blockZ(),
        chestLoot.roll(ThreadLocalRandom.current()), "west");
  }

  /** Once an active reward chest has been fully emptied, remove it and swap the mineable block back in. */
  private void chestTick() {
    if (stateInt(KEY_CHEST_ACTIVE, 0) != 1) {
      return;
    }
    WorldAdapter world = world();
    if (world == null || world.spawnPosition() == null) {
      return;
    }
    BlockPosition source = sourcePos(world.spawnPosition());
    if (world.chestEmpty(source)) {
      world.setBlock(source, levelBlock(stateInt(KEY_BREAKS, 0)));
      setStateInt(KEY_CHEST_ACTIVE, 0);
      for (PlayerAdapter player : online()) {
        player.playSound(new SoundKey("minecraft:block.chest.close"), 0.7f, 1.0f);
      }
    }
  }

  private void describeHud(HudContext context) {
    int total = stateInt(KEY_BREAKS, 0);
    int level = Math.min(total / blocksPerLevel, LEVEL_BLOCKS.size() - 1);
    int untilChest = blocksPerChest - (total % blocksPerChest);
    context.line("<gold>Level:</gold> <white>" + (level + 1) + "</white>");
    context.line("<gray>Blocks broken:</gray> <white>" + total + "</white>");
    if (stateInt(KEY_CHEST_ACTIVE, 0) == 1) {
      context.line("<gold>Empty the chest to keep mining!</gold>");
    } else {
      context.line("<gray>Next chest in:</gray> <white>" + untilChest + " blocks</white>");
    }
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Chest loot", chestLoot == null ? "—" : "loaded");
      context.debugStat("Blocks / level", blocksPerLevel);
      context.debugStat("Blocks / chest", blocksPerChest);
      context.debugStat("Chests spawned", stateInt(KEY_CHESTS, 0));
    }
  }

  private ItemKey levelBlock(int totalBreaks) {
    int level = Math.min(totalBreaks / blocksPerLevel, LEVEL_BLOCKS.size() - 1);
    List<ItemKey> palette = LEVEL_BLOCKS.get(level);
    if (!partyHasPickaxe()) {
      // No pickaxe yet: only spawn blocks the player can actually mine by hand (never bare stone/ore).
      List<ItemKey> handMineable = new ArrayList<>();
      for (ItemKey block : palette) {
        if (block != null && HAND_MINEABLE.contains(block.value())) {
          handMineable.add(block);
        }
      }
      // If this tier has nothing hand-mineable, give a log so a pickaxe can still be crafted.
      palette = handMineable.isEmpty() ? List.of(ItemKey.minecraft("oak_log")) : handMineable;
    }
    return palette.get(ThreadLocalRandom.current().nextInt(palette.size()));
  }

  /** True once any participant is holding a pickaxe of any tier (so stone-tier blocks are safe to spawn). */
  private boolean partyHasPickaxe() {
    for (PlayerAdapter player : online()) {
      if (player == null || player.inventory() == null) {
        continue;
      }
      for (ItemStackData stack : player.inventory().storageContents()) {
        if (stack != null && stack.itemKey() != null && stack.itemKey().value().endsWith("_pickaxe")) {
          return true;
        }
      }
    }
    return false;
  }

  private BlockPosition sourcePos(WorldPosition spawn) {
    return new BlockPosition(spawn.worldName(),
        (int) Math.floor(spawn.coordinateX()),
        (int) Math.floor(spawn.coordinateY()) - 1,
        (int) Math.floor(spawn.coordinateZ()));
  }

  // ----- loot pool -----------------------------------------------------------------------------

  private record LootRow(ItemKey item, int min, int max, int weight) {
  }

  private static LootRow row(String id, int min, int max, int weight) {
    return new LootRow(ItemKey.minecraft(id), min, max, weight);
  }

  private static List<ItemKey> blocks(String... ids) {
    java.util.List<ItemKey> list = new java.util.ArrayList<>();
    for (String id : ids) {
      list.add(ItemKey.minecraft(id));
    }
    return List.copyOf(list);
  }

  /** Reads the configured pool ("id:min:max:weight" rows) or the built-in 60-item default. */
  private List<LootRow> resolvePool() {
    List<String> configured = cfg().getStringList(configPath("item-pool"));
    if (configured == null || configured.isEmpty()) {
      return DEFAULT_POOL;
    }
    java.util.List<LootRow> pool = new java.util.ArrayList<>();
    for (String entry : configured) {
      LootRow parsed = parseRow(entry);
      if (parsed != null) {
        pool.add(parsed);
      }
    }
    return pool.isEmpty() ? DEFAULT_POOL : pool;
  }

  private static LootRow parseRow(String entry) {
    if (entry == null || entry.isBlank()) {
      return null;
    }
    String[] parts = entry.trim().split(":");
    ItemKey item = ItemKey.parse(parts[0]);
    if (item == null) {
      return null;
    }
    int min = parts.length > 1 ? parseInt(parts[1], 1) : 1;
    int max = parts.length > 2 ? parseInt(parts[2], min) : min;
    int weight = parts.length > 3 ? parseInt(parts[3], 10) : 10;
    return new LootRow(item, min, max, weight);
  }

  private static int parseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private LootTable buildLoot(List<LootRow> pool, int rollsMin, int rollsMax, boolean chest) {
    LootTable.Builder builder = LootTable.builder();
    for (LootRow row : pool) {
      builder.pool(row.item(), row.min(), row.max(), row.weight());
    }
    if (chest) {
      // Guarantee a couple of staples in every chest so a run can always progress.
      builder.guaranteed(ItemKey.minecraft("bread"), 3).guaranteed(ItemKey.minecraft("oak_log"), 4);
    }
    return builder.rolls(Math.max(1, rollsMin), Math.max(rollsMin, rollsMax)).build();
  }
}
