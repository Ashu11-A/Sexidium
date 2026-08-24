package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.gen.StructureBuilder;
import com.sexidium.core.world.gen.TreeSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static com.sexidium.core.game.experience.challenges.SkyblockIslands.stack;

/**
 * Classic Skyblock — the standard vanilla SkyBlock island in a VOID world. On first start it builds, into
 * the void, the iconic L-shaped grass island (grass over ~81 blocks of dirt) with one oak tree, a starter
 * chest (a block of ice, a lava bucket and 10 obsidian), and a short bridge to a distant sand island that
 * carries a cactus and a second chest of seeds and Nether-portal blocks.
 *
 * <p>Its linked <b>Nether mirrors the Overworld</b>: the sibling is generated void too (see
 * {@link #requiresVoidNether()}) and gets the same L island, the same distant supply island, and a
 * ready-lit portal beside the island — all built <b>eagerly at start</b>, before anyone travels, so
 * vanilla links to that portal instead of improvising one in mid-air over the island. The shapes and loot
 * live in {@link SkyblockIslands}; this class owns only the lifecycle and the void-arrival rescue.</p>
 */
public final class ClassicSkyblockChallenge extends Challenge {
  private static final String KEY_BUILT = "built";
  private static final String KEY_NETHER_BUILT = "netherbuilt";
  // Layout marker for the Nether additions (walled lava basin, ground-level portal, distant island). Kept
  // separate from KEY_NETHER_BUILT so an experience built by the older layout is REPAIRED in place rather
  // than rebuilt over its players' work.
  private static final String KEY_NETHER_EXTRAS = "netherextras";
  /** Air kept above the spawn footing — tall enough to clear a canopy, not just a player. */
  private static final int SPAWN_HEAD_ROOM = StructureBuilder.STANDING_CLEARANCE;

  private static final ItemKey GRASS = ItemKey.minecraft("grass_block");
  private static final ItemKey DIRT = ItemKey.minecraft("dirt");
  private static final ItemKey SAND = ItemKey.minecraft("sand");

  /** How far below a player we look for ground before treating a Nether arrival as a void drop. */
  private static final int GROUND_SEARCH_DEPTH = 8;

  // Players whose current Nether visit has already been checked for a void arrival.
  private final Set<UUID> netherSeated = new HashSet<>();

  public ClassicSkyblockChallenge() {
    super("classicskyblock", "Classic Skyblock");
  }

  @Override
  public boolean requiresVoidWorld() {
    return true;
  }

  @Override
  public boolean requiresVoidNether() {
    return true;
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    WorldAdapter world = world();
    if (world != null && world.spawnPosition() != null && !stateHas(KEY_BUILT)) {
      buildOverworld(world);
      setStateInt(KEY_BUILT, 1);
    }
    // Build the Nether mirror EAGERLY, before anyone travels. The linked sibling already exists (it is
    // provisioned with the experience world), and building now is what puts a real, ground-level portal
    // in the Nether ahead of the first trip: vanilla then LINKS to it instead of carving its own portal
    // out of thin air at the arrival altitude — which is how portals ended up floating over the island.
    buildNetherIfNeeded(netherWorld());
    // Fallback + safety net: build the mirror if the sibling was not resolvable at start, and rescue any
    // player who still arrives over the void.
    runTimer(this::netherTick, 20L, 20L);
  }

  /**
   * A regeneration handed the run a brand-new VOID world, so the island has to be built into it again.
   *
   * <p>Without this the mode composes with Death Resets exactly once: the first world gets an island and
   * every world after it is empty space, so the reset that follows a death drops everybody into the void
   * — and, because dying in the void triggers another reset, keeps doing it.</p>
   *
   * <p>Unconditional, and it has to be. The markers still hold the OLD world's values at this point (the
   * swap that clears them has not happened yet), so a build guarded the way {@link #onStart} guards it
   * would skip every time. They are re-asserted rather than left alone because the reset carries whatever
   * the rebuild writes — which is what stops the next resume treating this island as unbuilt.</p>
   *
   * <p>The Nether mirror is built here too, and its sibling is reached through the world we were HANDED
   * rather than through {@link #world()} — which still points at the world being thrown away. The
   * replacement's linked dimensions are provisioned during the acquire that precedes this callback, so
   * they already exist. If one does not, the markers are left untouched, the swap drops them, and
   * {@code netherTick} builds it on a later pass; that is the same fallback a first start has.</p>
   */
  @Override
  public void onWorldReset(WorldAdapter world) {
    netherSeated.clear();
    if (world == null || world.spawnPosition() == null) {
      return;
    }
    buildOverworld(world);
    setStateInt(KEY_BUILT, 1);
    WorldAdapter nether = world.dimension(WorldDimension.NETHER);
    if (nether != null && nether.spawnPosition() != null) {
      buildNetherMirror(nether);
    }
  }

  /** The experience's own linked Nether, or null when the platform has no sibling for this world. */
  private WorldAdapter netherWorld() {
    WorldAdapter world = world();
    return world == null ? null : world.dimension(WorldDimension.NETHER);
  }

  // ----- Overworld island ----------------------------------------------------------------------

  private void buildOverworld(WorldAdapter world) {
    WorldPosition spawn = world.spawnPosition();
    int cx = (int) Math.floor(spawn.coordinateX());
    int cz = (int) Math.floor(spawn.coordinateZ());
    int topY = (int) Math.floor(spawn.coordinateY()) - 1; // grass one below the (0,64,0) spawn
    StructureBuilder builder = new StructureBuilder(world);
    String w = spawn.worldName();

    // Make sure every chunk the island + distant island touch is loaded first, so a freshly generated void
    // world never drops blocks (which looked like "missing blocks" on the island).
    SkyblockIslands.ensureChunks(world, cx - 3, cz - 3, cx + SkyblockIslands.DISTANT_OFFSET_X + 4, cz + 3);

    // The L-shaped island: a solid 27-cell grass top over dirt, centred so the player's spawn column is
    // grass. Nothing else sits on it except the tree and the starter chest.
    SkyblockIslands.lIsland(world, w, cx, cz, topY, GRASS, DIRT);

    // One oak tree on the long arm, at cz + 3 rather than cz + 2. That one block is the whole fix for
    // "I spawn inside the tree": an oak's canopy has radius 2, so from cz + 2 it draped leaves directly
    // over the spawn column, and the spawn search — which stands a player on the topmost solid block, and
    // counts leaves as solid — put every arrival on top of the canopy, six blocks above the island. Still
    // on the L's long arm, so the island reads exactly as before.
    builder.tree(w, cx - 1, topY, cz + 3, TreeSpec.oak());

    // Starter chest: ONLY a block of ice and a lava bucket — the true classic start (make water + cobble
    // from these; obsidian for the portal is in the distant chest).
    builder.chest(w, cx + 2, topY + 1, cz - 1, List.of(
        stack("ice", 1), stack("lava_bucket", 1)), "west");

    // A SEPARATE, distant island — no bridge, so the player has to find their own way across (bridge over,
    // pearl, etc.), like the Random-Skyblock map. It holds the second chest with the seeds and the
    // Nether-portal blocks (obsidian + flint & steel).
    int islandX = cx + SkyblockIslands.DISTANT_OFFSET_X;
    int islandZ = cz;
    // Sandstone, not sand: a floating island of sand would gravity-fall straight into the void. Sandstone
    // is the "harder sand" that stays put and needs a pickaxe to break.
    ItemKey sandstone = ItemKey.minecraft("sandstone");
    for (int dx = 0; dx <= 2; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        world.setBlock(new BlockPosition(w, islandX + dx, topY, islandZ + dz), sandstone);
        world.setBlock(new BlockPosition(w, islandX + dx, topY - 1, islandZ + dz), sandstone);
      }
    }
    // A cactus needs a sand block beneath it; sand supported by sandstone below never falls.
    world.setBlock(new BlockPosition(w, islandX, topY, islandZ - 1), SAND);
    world.setBlock(new BlockPosition(w, islandX, topY + 1, islandZ - 1), ItemKey.minecraft("cactus"));
    builder.chest(w, islandX + 2, topY + 1, islandZ + 1, List.of(
        stack("melon_seeds", 1), stack("pumpkin_seeds", 1), stack("wheat_seeds", 3), stack("sugar_cane", 2),
        stack("cocoa_beans", 2), stack("red_mushroom", 2), stack("brown_mushroom", 2), stack("oak_sapling", 2),
        stack("obsidian", 10), stack("flint_and_steel", 1)), "west");

    // Last: confirm the column the player is dropped into is grass with two clear blocks over it. The
    // island is laid out FROM the spawn on the assumption they land on it, but the tree, the chest and
    // anything a composed challenge adds are placed afterwards and are free to land on top of that
    // assumption. This is the only line that turns it into a fact.
    builder.reserveStandingSpot(w, cx, topY, cz, GRASS, SPAWN_HEAD_ROOM);
  }

  // ----- Nether mirror -------------------------------------------------------------------------

  private void netherTick() {
    if (!stateHas(KEY_NETHER_EXTRAS)) {
      buildNetherIfNeeded(netherWorld());
    }
    for (PlayerAdapter player : online()) {
      WorldAdapter world = player.world();
      if (world != null && world.isNether()) {
        buildNetherIfNeeded(world);
        seatInNether(player, world);
      } else {
        netherSeated.remove(player.uniqueId()); // left the Nether — check again on the next entry
      }
    }
  }

  private void buildNetherIfNeeded(WorldAdapter world) {
    if (world == null || world.spawnPosition() == null || stateHas(KEY_NETHER_EXTRAS)) {
      return;
    }
    if (stateHas(KEY_NETHER_BUILT)) {
      // An experience whose Nether was built by the old layout: seal the spilling lava and add the portal
      // and the distant island in place, without touching anything its players have built.
      WorldPosition spawn = world.spawnPosition();
      SkyblockIslands.netherRepair(world, spawn.worldName(), (int) Math.floor(spawn.coordinateX()),
          (int) Math.floor(spawn.coordinateZ()), (int) Math.floor(spawn.coordinateY()) - 1, new Random());
      setStateInt(KEY_NETHER_EXTRAS, 1);
      return;
    }
    buildNetherMirror(world);
  }

  /**
   * Builds the mirror into a Nether that has never had one, and records that it has.
   *
   * <p>Separated from {@link #buildNetherIfNeeded} because a regeneration needs exactly this and none of
   * the guards around it: at that point the markers still describe the world being thrown away, so asking
   * them would skip the build the new world actually needs, and the repair branch would run against
   * terrain that has nothing to repair.</p>
   */
  private void buildNetherMirror(WorldAdapter world) {
    WorldPosition spawn = world.spawnPosition();
    SkyblockIslands.netherMirror(world, spawn.worldName(), (int) Math.floor(spawn.coordinateX()),
        (int) Math.floor(spawn.coordinateZ()), (int) Math.floor(spawn.coordinateY()) - 1, new Random());
    setStateInt(KEY_NETHER_BUILT, 1);
    setStateInt(KEY_NETHER_EXTRAS, 1);
  }

  /**
   * Rescues a player who arrives in the Nether over the void. Unlike the old unconditional seat-on-entry,
   * this only fires when there is genuinely no ground beneath them — now that a real portal stands next to
   * the island, a normal arrival must be left exactly where the portal put them.
   */
  private void seatInNether(PlayerAdapter player, WorldAdapter world) {
    if (!netherSeated.add(player.uniqueId())) {
      return; // already checked this visit
    }
    if (hasGroundBelow(world, player.position())) {
      return;
    }
    WorldPosition safe = world.safeSpawnPosition();
    if (safe != null) {
      player.teleport(safe);
    }
  }

  /** Whether there is a solid block within {@link #GROUND_SEARCH_DEPTH} below the position. */
  private static boolean hasGroundBelow(WorldAdapter world, WorldPosition position) {
    if (position == null) {
      return false;
    }
    int blockX = (int) Math.floor(position.coordinateX());
    int blockZ = (int) Math.floor(position.coordinateZ());
    int fromY = (int) Math.floor(position.coordinateY());
    for (int y = fromY; y > fromY - GROUND_SEARCH_DEPTH; y--) {
      ItemKey block = world.blockTypeAt(new BlockPosition(position.worldName(), blockX, y, blockZ));
      if (block != null && !"air".equals(block.value()) && !"cave_air".equals(block.value())
          && !"void_air".equals(block.value())) {
        return true;
      }
    }
    return false;
  }

  private void describeHud(HudContext context) {
    context.line("<green>Classic Skyblock</green>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Overworld built", stateHas(KEY_BUILT));
      context.debugStat("Nether built", stateHas(KEY_NETHER_BUILT));
      context.debugStat("Nether portal + far island", stateHas(KEY_NETHER_EXTRAS));
      context.debugStat("Seated in nether", netherSeated.size());
    }
  }

}
