package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.gen.StructureBuilder;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.gen.LayeredColumn;
import com.sexidium.core.world.gen.LayeredColumn.Feature;
import com.sexidium.core.world.gen.LayeredColumn.Layer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Layered Dimensions — one chunk, dug straight down, in every dimension.
 *
 * <h2>The shape</h2>
 * The Overworld is a single 16×16 column of {@code layers} stacked slabs, each {@code layer-height}
 * blocks tall. The Nether is the <b>same column in its own materials</b> — same chunk, same layer height,
 * the same descent — so the two read as one world seen twice rather than two unrelated maps. The End is
 * deliberately left as an ordinary End: after two dimensions of digging, the reward is somewhere that
 * behaves normally.
 *
 * <h2>What is down there</h2>
 * Layers are not just material. A share of them are hazards drawn from what each dimension has to hand —
 * monster spawners set into the slab, pools of water or lava, TNT under pressure plates, magma floors,
 * cobwebs that stall your descent right where the spawners are. The first layer is always plain, because
 * you have to be able to stand somewhere when you arrive.
 *
 * <p>The deepest layer of each dimension is its payoff, and in the <b>Nether that is a working End
 * portal</b> — so the way onward is at the bottom of a hundred layers of everything the Nether can throw
 * at you. Getting to the Nether at all is conventional: build an obsidian frame and light it. Nothing
 * here shortcuts that.</p>
 *
 * <h2>Dying</h2>
 * Death always returns you to the Overworld column, whichever dimension killed you — the descent is the
 * challenge, and being sent back to the top of it is the cost.
 *
 * <p>The columns are built once, deterministically from the experience's own seed, and remembered in the
 * shared state, so a restart never rebuilds or reshuffles what a player has already mined. See
 * {@code experiences.modes.layereddimensions}.</p>
 */
public final class LayeredDimensionsChallenge extends Challenge {
  private static final String KEY_BUILT = "built";

  /** Ordinary Overworld strata — recognisable rock and dirt, with ores worth stopping for. */
  private static final List<String> DEFAULT_OVERWORLD_PALETTE = List.of(
      "stone", "dirt", "gravel", "andesite", "diorite", "granite", "deepslate", "tuff", "sandstone",
      "coal_ore", "iron_ore", "copper_ore", "clay", "packed_ice", "moss_block", "calcite");
  /** The Nether's own blocks — the same descent, unmistakably somewhere else. */
  private static final List<String> DEFAULT_NETHER_PALETTE = List.of(
      "netherrack", "soul_sand", "soul_soil", "blackstone", "basalt", "nether_bricks", "warped_nylium",
      "crimson_nylium", "glowstone", "quartz_block", "nether_gold_ore", "nether_quartz_ore",
      "polished_blackstone", "gilded_blackstone");
  private static final List<String> DEFAULT_OVERWORLD_MOBS = List.of(
      "zombie", "skeleton", "spider", "creeper", "cave_spider", "witch", "silverfish");
  private static final List<String> DEFAULT_NETHER_MOBS = List.of(
      "blaze", "wither_skeleton", "magma_cube", "hoglin", "piglin_brute", "ghast", "zombified_piglin");

  private int layers;
  private int layerHeight;
  private double hazardChance;
  private int chunkX;
  private int chunkZ;
  private boolean built;

  public LayeredDimensionsChallenge() {
    super("layereddimensions", "Layered Dimensions");
  }

  /** The column is built into empty space, so the experience's Overworld must generate as pure void. */
  @Override
  public boolean requiresVoidWorld() {
    return true;
  }

  /** …and so must its Nether, which gets a column of its own rather than natural Nether terrain. */
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
    layers = Math.max(1, cfg().getInt(configPath("layers"), 100));
    layerHeight = Math.max(1, cfg().getInt(configPath("layer-height"), 3));
    hazardChance = Math.max(0.0, Math.min(1.0, cfg().getDouble(configPath("hazard-chance"), 0.35)));
    chunkX = cfg().getInt(configPath("chunk-x"), 0);
    chunkZ = cfg().getInt(configPath("chunk-z"), 0);
    built = stateHas(KEY_BUILT);
    if (!built) {
      buildAll();
    }
  }

  /**
   * A regeneration handed the run a brand-new VOID world, so both columns have to be built into it again.
   *
   * <p>This mode's world is nothing but the column it writes, so a replacement it never builds into is
   * not a harder start — it is empty space, and every player teleported in falls out of the world. Since
   * the mode that regenerates worlds is Death Resets, that fall is a death, and that death is another
   * reset.</p>
   *
   * <p>Built into the world we were HANDED, never {@link #world()}, which still points at the world being
   * thrown away. The marker is re-asserted rather than consulted: at this point it still holds the OLD
   * world's value, so a guarded build would skip every time, and the reset carries what a rebuild writes
   * — which is what stops the next start treating this column as unbuilt.</p>
   */
  @Override
  public void onWorldReset(WorldAdapter world) {
    if (world != null) {
      buildAll(world);
    }
  }

  /**
   * Builds the Overworld and Nether columns. Done once, eagerly at start rather than on arrival: a player
   * who lights a portal must find the Nether column already there, and building a world inside a portal
   * event is unsafe. The End is never touched — it stays an ordinary End.
   */
  private void buildAll() {
    buildAll(world());
  }

  /**
   * As {@link #buildAll()}, against a NAMED world — which is what a regeneration needs, since the match
   * still points at the old one while the replacement is being prepared. The seed is derived from that
   * world's name, so a regenerated world gets a genuinely different column rather than a copy of the one
   * that was just lost.
   */
  private void buildAll(WorldAdapter overworld) {
    if (overworld == null) {
      return;
    }
    long seed = seed(overworld);
    buildColumn(overworld, overworldSpec(overworld), seed);
    WorldAdapter nether = overworld.dimension(WorldDimension.NETHER);
    if (nether != null) {
      // The SAME seed, so the Nether descends through the same pattern of hazards in its own materials.
      buildColumn(nether, netherSpec(nether), seed);
    }
    setStateInt(KEY_BUILT, 1);
    built = true;
  }

  /** A stable per-world seed, so a rebuild is the same world and two dimensions stay in step. */
  private static long seed(WorldAdapter world) {
    String name = world == null || world.name() == null ? "layereddimensions" : world.name();
    return name.hashCode() * 0x9E3779B97F4A7C15L;
  }

  private LayeredColumn.Spec overworldSpec(WorldAdapter world) {
    // Leave a little headroom under the build ceiling so a player can stand (and build) on the surface.
    int topY = world.maxBuildHeight() - 8;
    return new LayeredColumn.Spec(layers, layerHeight, topY, world.minBuildHeight() + 1,
        keys(configPath("overworld-palette"), DEFAULT_OVERWORLD_PALETTE), ItemKey.minecraft("water"),
        ItemKey.minecraft("magma_block"), ItemKey.minecraft("bedrock"),
        strings(configPath("overworld-mobs"), DEFAULT_OVERWORLD_MOBS), hazardChance);
  }

  private LayeredColumn.Spec netherSpec(WorldAdapter world) {
    // The Nether is a SHORTER dimension (0…128 against the Overworld's −64…320), so the same 100 layers
    // cannot physically fit. LayeredColumn.Spec clamps to what the dimension has room for rather than
    // asking the platform to build outside its range, which is an error rather than a no-op.
    int topY = world.maxBuildHeight() - 8;
    return new LayeredColumn.Spec(layers, layerHeight, topY, world.minBuildHeight() + 1,
        keys(configPath("nether-palette"), DEFAULT_NETHER_PALETTE), ItemKey.minecraft("lava"),
        ItemKey.minecraft("magma_block"),
        // The payoff: the deepest layer of the Nether is a working End portal.
        ItemKey.minecraft(cfg().getString(configPath("nether-floor-block"), "end_portal")),
        strings(configPath("nether-mobs"), DEFAULT_NETHER_MOBS), hazardChance);
  }

  /**
   * The X of the column's WEST edge, so that the world spawn sits at its centre rather than on its corner.
   *
   * <p>This used to be {@code chunkX << 4} — the chunk's minimum corner. The world spawn of a void
   * experience is pinned to (0, 64, 0), which with the default {@code chunk-x: 0} is the corner block of
   * that chunk: the arrival stood one block in from two edges of a 16-wide column with a 300-block drop on
   * both sides, and a single step ended the run. It was also 8 blocks from where the mode's own
   * {@link #respawnPosition} put a player who died, so entering and respawning disagreed about where the
   * mode begins.</p>
   *
   * <p>Anchoring on the spawn instead makes both true at once, and matches how every other generated map
   * here is laid out — {@code StructureBuilder.slab} has always centred on the spawn column. {@code chunk-x}
   * / {@code chunk-z} keep their meaning as a whole-chunk offset from it.</p>
   */
  private int baseX(WorldAdapter world) {
    return spawnBlockX(world) + (chunkX << 4) - 8;
  }

  private int baseZ(WorldAdapter world) {
    return spawnBlockZ(world) + (chunkZ << 4) - 8;
  }

  private static int spawnBlockX(WorldAdapter world) {
    WorldPosition spawn = world == null ? null : world.spawnPosition();
    return spawn == null ? 0 : (int) Math.floor(spawn.coordinateX());
  }

  private static int spawnBlockZ(WorldAdapter world) {
    WorldPosition spawn = world == null ? null : world.spawnPosition();
    return spawn == null ? 0 : (int) Math.floor(spawn.coordinateZ());
  }

  /** Writes one dimension's planned layers into the world, block by block. */
  private void buildColumn(WorldAdapter world, LayeredColumn.Spec spec, long seed) {
    String worldName = world.name();
    int baseX = baseX(world);
    int baseZ = baseZ(world);
    world.loadChunk(baseX >> 4, baseZ >> 4, true);
    // The footprint straddles a chunk boundary now that it is centred on the spawn rather than aligned to
    // one, so the neighbours have to be resident too or the writes at the far edges are dropped.
    world.loadChunk((baseX + 15) >> 4, baseZ >> 4, true);
    world.loadChunk(baseX >> 4, (baseZ + 15) >> 4, true);
    world.loadChunk((baseX + 15) >> 4, (baseZ + 15) >> 4, true);
    int surfaceY = Integer.MIN_VALUE;
    for (Layer layer : LayeredColumn.plan(spec, seed)) {
      fill(world, worldName, baseX, baseZ, layer);
      decorate(world, worldName, baseX, baseZ, layer);
      surfaceY = Math.max(surfaceY, layer.topY());
    }
    if (surfaceY != Integer.MIN_VALUE) {
      // The arrival column, made standable. A player enters this mode on TOP of the column, and the
      // decoration pass is free to put a hazard block or a mob-trap plate on the surface — including on
      // the very column the spawn search settles on, which lands them inside it.
      new StructureBuilder(world).reserveStandingSpot(worldName, spawnBlockX(world), surfaceY,
          spawnBlockZ(world), null, StructureBuilder.STANDING_CLEARANCE);
    }
  }

  /** Fills a layer's whole 16×16×height volume with its block. */
  private void fill(WorldAdapter world, String worldName, int baseX, int baseZ, Layer layer) {
    // An End-portal floor is placed as-is: the blocks ARE the portal, already active, which is the point.
    for (int y = layer.bottomY(); y <= layer.topY(); y++) {
      for (int offsetX = 0; offsetX < 16; offsetX++) {
        for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
          world.setBlock(new BlockPosition(worldName, baseX + offsetX, y, baseZ + offsetZ), layer.block());
        }
      }
    }
  }

  /** Adds whatever makes a layer more than a slab: spawners, liquid pools, traps, webs. */
  private void decorate(WorldAdapter world, String worldName, int baseX, int baseZ, Layer layer) {
    int top = layer.topY();
    switch (layer.feature()) {
      case SPAWNER -> {
        // Set into the slab on a coarse grid, so the layer is dangerous everywhere rather than in one spot.
        for (int offsetX = 3; offsetX < 16; offsetX += 6) {
          for (int offsetZ = 3; offsetZ < 16; offsetZ += 6) {
            world.placeSpawner(new BlockPosition(worldName, baseX + offsetX, top, baseZ + offsetZ), layer.mob());
            // A spawner needs somewhere to put what it spawns: hollow the block above it.
            world.setBlock(new BlockPosition(worldName, baseX + offsetX, top + 1, baseZ + offsetZ), air());
          }
        }
      }
      case LIQUID -> fillTop(world, worldName, baseX, baseZ, top, liquidOf(layer));
      case TNT -> {
        for (int offsetX = 0; offsetX < 16; offsetX += 2) {
          for (int offsetZ = 0; offsetZ < 16; offsetZ += 2) {
            world.setBlock(new BlockPosition(worldName, baseX + offsetX, top, baseZ + offsetZ),
                ItemKey.minecraft("tnt"));
            world.setBlock(new BlockPosition(worldName, baseX + offsetX, top + 1, baseZ + offsetZ),
                ItemKey.minecraft("stone_pressure_plate"));
          }
        }
      }
      case TANGLED -> fillTop(world, worldName, baseX, baseZ, top + 1, ItemKey.minecraft("cobweb"));
      default -> { }
    }
  }

  private ItemKey liquidOf(Layer layer) {
    return layer.block() != null && layer.block().value().startsWith("nether")
        ? ItemKey.minecraft("lava") : ItemKey.minecraft("water");
  }

  private void fillTop(WorldAdapter world, String worldName, int baseX, int baseZ, int y, ItemKey block) {
    for (int offsetX = 0; offsetX < 16; offsetX++) {
      for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
        world.setBlock(new BlockPosition(worldName, baseX + offsetX, y, baseZ + offsetZ), block);
      }
    }
  }

  /**
   * Death always returns to the Overworld column, whichever dimension did the killing. The descent is the
   * challenge and losing it is the cost; being left where you died in the Nether would make dying cheap.
   */
  @Override
  public WorldPosition respawnPosition(PlayerAdapter playerAdapter) {
    WorldAdapter overworld = world();
    if (overworld == null) {
      return null;
    }
    // The same column the entry teleport lands on — the spawn column, at the centre of the build. These
    // two used to name different places, so dying moved you 11 blocks from where you started.
    return new WorldPosition(overworld.name(), spawnBlockX(overworld) + 0.5,
        LayeredColumn.surfaceY(overworldSpec(overworld)), spawnBlockZ(overworld) + 0.5, 0.0f, 0.0f);
  }

  private static ItemKey air() {
    return ItemKey.minecraft("air");
  }

  private List<ItemKey> keys(String path, List<String> fallback) {
    List<ItemKey> result = new ArrayList<>();
    for (String value : strings(path, fallback)) {
      result.add(ItemKey.minecraft(value));
    }
    return result;
  }

  private List<String> strings(String path, List<String> fallback) {
    List<String> configured = cfg().getStringList(path);
    List<String> source = configured == null || configured.isEmpty() ? fallback : configured;
    List<String> result = new ArrayList<>();
    for (String value : source) {
      if (value != null && !value.isBlank()) {
        result.add(value.trim().toLowerCase(Locale.ROOT));
      }
    }
    return result.isEmpty() ? fallback : result;
  }

  private void describeHud(HudContext context) {
    WorldAdapter world = world();
    PlayerAdapter viewer = context.player();
    WorldPosition at = viewer == null ? null : viewer.position();
    if (world != null && at != null) {
      // How deep they are, in layers — the only number that matters in this mode.
      LayeredColumn.Spec spec = viewer.world() != null && viewer.world().isNether()
          ? netherSpec(viewer.world()) : overworldSpec(world);
      int depth = Math.max(0, (spec.topY() - (int) Math.floor(at.coordinateY())) / spec.layerHeight() + 1);
      context.line("<gold>Layer:</gold> <white>" + Math.min(depth, spec.fittingLayers())
          + " / " + spec.fittingLayers() + "</white>");
    }
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Layers wanted", layers);
      context.debugStat("Layer height", layerHeight);
      context.debugStat("Hazard chance", hazardChance);
      context.debugStat("Built", built);
      if (world != null) {
        LayeredColumn.Spec overworld = overworldSpec(world);
        context.debugStat("Overworld layers", overworld.fittingLayers() + (overworld.isClamped() ? " (clamped)" : ""));
        WorldAdapter nether = world.dimension(WorldDimension.NETHER);
        if (nether != null) {
          LayeredColumn.Spec spec = netherSpec(nether);
          context.debugStat("Nether layers", spec.fittingLayers() + (spec.isClamped() ? " (clamped)" : ""));
        }
      }
    }
  }
}
