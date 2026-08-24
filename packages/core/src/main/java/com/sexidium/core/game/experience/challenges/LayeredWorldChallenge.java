package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.challenges.LayerDeck.Layer;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.gen.LootTable;
import com.sexidium.core.world.gen.StructureBuilder;
import com.sexidium.core.world.gen.TreeSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random Layers ("Random Layer One Chunk").
 *
 * <p>Rule: the one-chunk world is built out of random horizontal layers, and it keeps growing — every
 * Minecraft day {@code layers-per-day} new layers are added, deepening the pit. Each layer is a random
 * block slab, a TNT-and-pressure-plate trap, or a mob-spawn slice, chosen by the shared {@link LayerDeck}.
 * The choice of layer, the running layer/day counters and the build frontier are shared, persisted state,
 * so the column keeps growing across disconnects and restarts and never regenerates what it placed.</p>
 *
 * <p>UI: the right-side info panel shows the number of layers added, the current day, and the time
 * remaining until the next layers appear (read from the live world clock).</p>
 */
public final class LayeredWorldChallenge extends Challenge {
  private static final String KEY_LAYERS = "layers";
  private static final String KEY_DAYS = "days";
  private static final String KEY_DAY_INDEX = "dayindex";
  private static final String KEY_FRONTIER = "frontier";
  private static final ItemKey PRESSURE_PLATE = ItemKey.minecraft("stone_pressure_plate");
  private static final ItemKey MOB_FLOOR_FALLBACK = ItemKey.minecraft("stone");
  // The starting island (grass over stone) and the distant chest island (sand over sandstone), top-down.
  private static final List<ItemKey> DEFAULT_ISLAND_LAYERS = blocks("grass_block", "stone", "stone");
  private static final List<ItemKey> DEFAULT_CHEST_ISLAND_LAYERS = blocks("sand", "sandstone", "sandstone");
  /** The Nether's own starting platform: what you land on when your portal comes out in the void. */
  private static final List<ItemKey> DEFAULT_NETHER_ISLAND_LAYERS =
      blocks("netherrack", "netherrack", "soul_soil");
  /** Blocks the Nether column's layers are drawn from — the same descent, unmistakably somewhere else. */
  private static final List<String> DEFAULT_NETHER_PALETTE = List.of(
      "netherrack", "soul_sand", "soul_soil", "blackstone", "basalt", "nether_bricks", "warped_nylium",
      "crimson_nylium", "glowstone", "quartz_block", "magma_block", "nether_gold_ore",
      "nether_quartz_ore", "polished_blackstone", "gilded_blackstone", "crying_obsidian");
  private static final List<String> DEFAULT_NETHER_MOBS = List.of(
      "blaze", "wither_skeleton", "magma_cube", "hoglin", "zombified_piglin", "piglin", "ghast");
  /** State-key suffix per dimension, so the two columns never share a frontier or a layer count. */
  private static final String OVERWORLD_SUFFIX = "";
  private static final String NETHER_SUFFIX = ".nether";
  /**
   * How far from the spawn column a tree TRUNK must stay.
   *
   * <p>The canopy is what does the damage, not the trunk, so this is the leaf radius plus one: a trunk two
   * blocks away is nowhere near the player and still drapes leaves directly over their column, and the
   * spawn search then stands them on top of those leaves instead of on the island.</p>
   */
  private static final int SPAWN_KEEP_OUT = TreeSpec.oak().leafRadius() + 1;
  /** Air kept above the spawn footing — tall enough to clear a canopy, not just a player. */
  private static final int SPAWN_HEAD_ROOM = StructureBuilder.STANDING_CLEARANCE;

  private LayerDeck deck;
  private LayerDeck netherDeck;
  private List<ItemKey> netherIslandLayers;
  private LootTable chestLoot;
  private List<ItemKey> islandLayers;
  private List<ItemKey> chestIslandLayers;
  private int layersPerDay;
  private long dayLengthTicks;
  private int layerSize;
  private int islandSize;
  private int chestDistance;
  private int minTrees;
  private int maxTrees;
  private int maxCatchUpDays;
  private boolean announceNewLayers;
  private boolean playSound;

  public LayeredWorldChallenge() {
    super("randomlayers", "Random Layers");
  }

  /**
   * The Nether is generated VOID too, and gets a layer column of its own.
   *
   * <p>Without this the experience's Nether was ordinary Nether terrain with no layers in it at all — the
   * mode simply stopped existing the moment you stepped through a portal. A SkyBlock whose Nether is a
   * normal world is not a SkyBlock.</p>
   */
  @Override
  public boolean requiresVoidNether() {
    return true;
  }

  @Override
  public boolean requiresVoidWorld() {
    // The experience generates as pure void; this challenge builds the SkyBlock island + layers into it.
    return true;
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    layersPerDay = Math.max(1, cfg().getInt(configPath("layers-per-day"), 3));
    // Default 12000 ticks = half a Minecraft day (10 minutes real): a batch of layers appears each cycle.
    dayLengthTicks = Math.max(20L, cfg().getLong(configPath("day-length-ticks"), 12000L));
    layerSize = Math.max(1, cfg().getInt(configPath("layer-size"), 16));
    islandSize = Math.max(1, cfg().getInt(configPath("island-size"), 16));
    chestDistance = Math.max(4, cfg().getInt(configPath("chest-island-distance"), 60));
    minTrees = Math.max(0, cfg().getInt(configPath("min-trees"), 2));
    maxTrees = Math.max(minTrees, cfg().getInt(configPath("max-trees"), 3));
    maxCatchUpDays = Math.max(1, cfg().getInt(configPath("max-catch-up-days"), 20));
    announceNewLayers = cfg().getBoolean(configPath("announce-new-layers"), true);
    playSound = cfg().getBoolean(configPath("play-sound"), true);
    deck = buildDeck();
    netherDeck = buildNetherDeck();
    islandLayers = resolveLayers("island-layers", DEFAULT_ISLAND_LAYERS);
    netherIslandLayers = resolveLayers("nether-island-layers", DEFAULT_NETHER_ISLAND_LAYERS);
    chestIslandLayers = resolveLayers("chest-island-layers", DEFAULT_CHEST_ISLAND_LAYERS);
    chestLoot = buildChestLoot();

    WorldAdapter world = world();
    int initial = Math.max(0, cfg().getInt(configPath("initial-layers"), 3));
    if (!stateHas(KEY_FRONTIER)) {
      // First run in this (void) world: build the SkyBlock starting structure and the distant loot chest,
      // then seat the build frontier just below the island and lay any starter layers. Subsequent runs
      // resume from the persisted frontier and never rebuild (the world already holds it all).
      int islandTopY = spawnBlock(world) - 1; // island surface one below the (0,64,0) spawn so players stand on it
      StructureBuilder builder = world == null ? null : new StructureBuilder(world);
      if (builder != null) {
        buildStartingIsland(world, builder, islandTopY);
        buildChestIsland(world, builder, islandTopY);
      }
      setStateInt(KEY_FRONTIER, islandTopY - islandLayers.size()); // first daily layer sits under the island
      setStateInt(KEY_LAYERS, 0);
      setStateInt(KEY_DAYS, 0);
      setStateLong(KEY_DAY_INDEX, currentDayIndex(world));
      for (int index = 0; index < initial; index++) {
        addLayer(world, OVERWORLD_SUFFIX);
      }
    }
    // The Nether column is built separately and lazily-but-eagerly: it has its own frontier, so an
    // experience created before the Nether had layers still grows one the next time it starts.
    buildNetherColumnIfMissing(initial);
    long period = Math.max(20L, cfg().getLong(configPath("check-interval-ticks"), 40L));
    runTimer(this::checkForNewDay, period, period);
  }

  /**
   * A regeneration handed the run a brand-new VOID world, so the island and the layer column have to be
   * built into it again.
   *
   * <p>This mode's world is nothing but what it builds, so a replacement it never builds into is not a
   * harder start — it is empty space, and every player teleported in falls out of the world. Composing
   * with Death Resets is the only way this method is reached, and there the fall is a death, and the
   * death is another reset.</p>
   *
   * <p>Everything is re-seeded from zero rather than continued: the frontier, the layer and day counts
   * and the day index all describe how deep a COLUMN got, and that column is in a world that no longer
   * exists. Carrying a frontier of −40 into fresh void would start the next column forty blocks below an
   * island nobody can reach. The re-seed is deliberately the same arithmetic as {@link #onStart}'s
   * first-run branch, because that is what "a brand-new world" means here.</p>
   *
   * <p>The Nether column is re-seeded through the world we were HANDED rather than through
   * {@link #world()}, which still points at the world being thrown away. The replacement's linked
   * dimensions are provisioned during the acquire that precedes this callback, so its Nether already
   * exists; if it somehow does not, its markers are left untouched, the swap drops them, and the next
   * start builds the column — the same fallback the mode already has.</p>
   */
  @Override
  public void onWorldReset(WorldAdapter world) {
    if (world == null) {
      return;
    }
    int initial = Math.max(0, cfg().getInt(configPath("initial-layers"), 3));
    seedColumn(world, OVERWORLD_SUFFIX, islandLayers, initial, topY -> {
      StructureBuilder builder = new StructureBuilder(world);
      buildStartingIsland(world, builder, topY);
      buildChestIsland(world, builder, topY);
    });
    setStateInt(KEY_DAYS, 0);
    setStateLong(KEY_DAY_INDEX, currentDayIndex(world));

    WorldAdapter nether = world.dimension(WorldDimension.NETHER);
    if (nether != null && nether.spawnPosition() != null) {
      seedColumn(nether, NETHER_SUFFIX, netherIslandLayers, initial,
          topY -> buildNetherPlatform(nether, topY));
    }
  }

  /**
   * Builds one dimension's starting platform and lays its first layers, seating the frontier under it.
   *
   * <p>Unconditional by design: this is the regeneration path, where the frontier still holds the depth
   * the OLD world's column reached. Honouring it would start the new column that far below an island
   * nobody can reach — so it is re-seated from the island that has just been built instead.</p>
   */
  private void seedColumn(WorldAdapter world, String suffix, List<ItemKey> platformLayers,
      int initialLayers, java.util.function.IntConsumer platform) {
    int topY = spawnBlock(world) - 1;
    platform.accept(topY);
    setStateInt(KEY_FRONTIER + suffix, topY - platformLayers.size());
    setStateInt(KEY_LAYERS + suffix, 0);
    for (int index = 0; index < initialLayers; index++) {
      addLayer(world, suffix);
    }
  }

  /** The experience's own (void) Nether, or null when it has none yet. */
  private WorldAdapter netherWorld() {
    WorldAdapter world = world();
    return world == null ? null : world.dimension(WorldDimension.NETHER);
  }

  /**
   * Builds the Nether's starting platform and its first layers, once. The platform matters more here than
   * in the Overworld: a portal lit in a void Nether comes out over nothing, so without something to land
   * on the first trip through would simply be a fall.
   */
  private void buildNetherColumnIfMissing(int initialLayers) {
    if (stateHas(KEY_FRONTIER + NETHER_SUFFIX)) {
      return;
    }
    WorldAdapter nether = netherWorld();
    if (nether == null) {
      return; // no linked Nether yet — it will be built the next time this experience starts
    }
    int topY = spawnBlock(nether) - 1;
    buildNetherPlatform(nether, topY);
    setStateInt(KEY_FRONTIER + NETHER_SUFFIX, topY - netherIslandLayers.size());
    setStateInt(KEY_LAYERS + NETHER_SUFFIX, 0);
    for (int index = 0; index < initialLayers; index++) {
      addLayer(nether, NETHER_SUFFIX);
    }
  }

  /**
   * The Nether's landing platform: the same slab stack as the Overworld island, with the arrival column
   * reserved. A portal lit in a void Nether comes out over nothing, so this platform is the only thing
   * between a first trip through and a fall — which makes a blocked or unfooted arrival column here worse
   * than it is in the Overworld, not better.
   */
  private void buildNetherPlatform(WorldAdapter nether, int topY) {
    int centerX = centerX(nether);
    int centerZ = centerZ(nether);
    StructureBuilder builder = new StructureBuilder(nether);
    builder.stack(nether.name(), centerX, centerZ, topY, islandSize, netherIslandLayers);
    builder.reserveStandingSpot(nether.name(), centerX, topY, centerZ,
        netherIslandLayers.isEmpty() ? null : netherIslandLayers.get(0), SPAWN_HEAD_ROOM);
  }

  private static int centerX(WorldAdapter world) {
    WorldPosition spawn = world.spawnPosition();
    return spawn == null ? 0 : (int) Math.floor(spawn.coordinateX());
  }

  private static int centerZ(WorldAdapter world) {
    WorldPosition spawn = world.spawnPosition();
    return spawn == null ? 0 : (int) Math.floor(spawn.coordinateZ());
  }

  /**
   * Builds the SkyBlock starting island centred on world spawn — grass on top (players stand on it) over
   * two layers of stone — and scatters 2–3 starter trees onto the grass.
   */
  private void buildStartingIsland(WorldAdapter world, StructureBuilder builder, int topY) {
    WorldPosition spawn = world.spawnPosition();
    if (spawn == null) {
      return;
    }
    int centerX = (int) Math.floor(spawn.coordinateX());
    int centerZ = (int) Math.floor(spawn.coordinateZ());
    builder.stack(spawn.worldName(), centerX, centerZ, topY, islandSize, islandLayers);
    int span = maxTrees - minTrees;
    int trees = minTrees + (span > 0 ? ThreadLocalRandom.current().nextInt(span + 1) : 0);
    // Fenced off the spawn column: a trunk's first log sits at topY + 1, which is exactly where the
    // player's feet go, so a tree drawn onto that column puts them inside it.
    builder.scatterTrees(spawn.worldName(), centerX, centerZ, topY, islandSize, trees,
        TreeSpec.oak(), ThreadLocalRandom.current(), SPAWN_KEEP_OUT);
    // …and then check rather than assume. Everything above computes where to build FROM the spawn and
    // trusts the player to arrive at it; this is the one line that makes that true whatever was placed.
    builder.reserveStandingSpot(spawn.worldName(), centerX, topY, centerZ,
        islandLayers.isEmpty() ? null : islandLayers.get(0), SPAWN_HEAD_ROOM);
  }

  /**
   * Builds the classic distant island — a small platform of sand over sandstone {@code chest-island-distance}
   * blocks away — carrying a chest whose loot is rolled from the {@link LootTable}.
   */
  private void buildChestIsland(WorldAdapter world, StructureBuilder builder, int topY) {
    WorldPosition spawn = world.spawnPosition();
    if (spawn == null) {
      return;
    }
    int islandX = (int) Math.floor(spawn.coordinateX()) + chestDistance;
    int islandZ = (int) Math.floor(spawn.coordinateZ());
    builder.stack(spawn.worldName(), islandX, islandZ, topY, 3, chestIslandLayers);
    builder.chest(spawn.worldName(), islandX, topY + 1, islandZ, chestLoot.roll(ThreadLocalRandom.current()));
  }

  /**
   * The SkyBlock starter loot table: a guaranteed lava/water/ice bootstrap plus a weighted pool of
   * seeds/saplings rolled a few times. Guaranteed items and roll counts are config-overridable.
   */
  private LootTable buildChestLoot() {
    LootTable.Builder builder = LootTable.builder();
    List<String> guaranteed = cfg().getStringList(configPath("chest-guaranteed"));
    if (guaranteed != null && !guaranteed.isEmpty()) {
      for (String entry : guaranteed) {
        ItemStackData parsed = parseChestItem(entry);
        if (parsed != null) {
          builder.guaranteed(parsed.itemKey(), parsed.amount());
        }
      }
    } else {
      builder.guaranteed(ItemKey.minecraft("lava_bucket"), 1)
          .guaranteed(ItemKey.minecraft("water_bucket"), 1)
          .guaranteed(ItemKey.minecraft("ice"), 2);
    }
    builder.pool(ItemKey.minecraft("oak_sapling"), 1, 2, 10)
        .pool(ItemKey.minecraft("wheat_seeds"), 1, 3, 10)
        .pool(ItemKey.minecraft("sugar_cane"), 1, 2, 8)
        .pool(ItemKey.minecraft("melon_seeds"), 1, 1, 6)
        .pool(ItemKey.minecraft("pumpkin_seeds"), 1, 1, 6)
        .pool(ItemKey.minecraft("red_mushroom"), 1, 1, 5)
        .pool(ItemKey.minecraft("brown_mushroom"), 1, 1, 5)
        .pool(ItemKey.minecraft("cactus"), 1, 1, 5)
        .pool(ItemKey.minecraft("bone_meal"), 1, 3, 7);
    int rollsMin = Math.max(0, cfg().getInt(configPath("chest-rolls-min"), 3));
    int rollsMax = Math.max(rollsMin, cfg().getInt(configPath("chest-rolls-max"), 5));
    return builder.rolls(rollsMin, rollsMax).build();
  }

  private static List<ItemKey> blocks(String... ids) {
    List<ItemKey> list = new ArrayList<>();
    for (String id : ids) {
      list.add(ItemKey.minecraft(id));
    }
    return List.copyOf(list);
  }

  /** Resolves a top-down layer list from config ("id" per layer), or the default when unset/empty. */
  private List<ItemKey> resolveLayers(String key, List<ItemKey> fallback) {
    List<String> configured = cfg().getStringList(configPath(key));
    if (configured == null || configured.isEmpty()) {
      return fallback;
    }
    List<ItemKey> parsed = new ArrayList<>();
    for (String entry : configured) {
      ItemKey block = ItemKey.parse(entry);
      if (block != null) {
        parsed.add(block);
      }
    }
    return parsed.isEmpty() ? fallback : parsed;
  }

  private static ItemStackData parseChestItem(String entry) {
    if (entry == null || entry.isBlank()) {
      return null;
    }
    String value = entry.trim();
    int amount = 1;
    int at = value.lastIndexOf(':');
    // Only treat a trailing ":<number>" as the amount, so a namespaced id (ns:path) still parses.
    if (at > 0 && value.indexOf(':') == at) {
      try {
        amount = Math.max(1, Integer.parseInt(value.substring(at + 1).trim()));
        value = value.substring(0, at);
      } catch (NumberFormatException ignored) {
        // no trailing amount — the whole token is the id
      }
    }
    ItemKey key = ItemKey.parse(value);
    return key == null ? null : new ItemStackData(key, amount, java.util.Map.of());
  }

  /** Polls the world clock; when the in-game day rolls over, adds a day's worth of layers (bounded). */
  private void checkForNewDay() {
    WorldAdapter world = world();
    if (world == null) {
      return;
    }
    long nowDay = currentDayIndex(world);
    long lastDay = stateLong(KEY_DAY_INDEX, nowDay);
    if (nowDay <= lastDay) {
      return;
    }
    long newDays = Math.min(nowDay - lastDay, maxCatchUpDays);
    int added = 0;
    // Both columns deepen on the same clock, so the two dimensions stay in step rather than the Nether
    // being a shallower afterthought a player outgrows.
    WorldAdapter nether = netherWorld();
    for (long day = 0; day < newDays; day++) {
      for (int index = 0; index < layersPerDay; index++) {
        if (addLayer(world, OVERWORLD_SUFFIX)) {
          added++;
        }
        if (nether != null) {
          addLayer(nether, NETHER_SUFFIX);
        }
      }
    }
    setStateLong(KEY_DAY_INDEX, nowDay);
    setStateInt(KEY_DAYS, stateInt(KEY_DAYS, 0) + (int) newDays);
    if (added > 0) {
      onLayersAdded(added);
    }
  }

  private void onLayersAdded(int added) {
    for (PlayerAdapter player : online()) {
      if (announceNewLayers) {
        player.sendActionBar("<gold>" + added + " new layer" + (added == 1 ? "" : "s")
            + " appeared below!</gold>");
      }
      if (playSound) {
        player.playSound(new SoundKey("minecraft:block.deepslate_bricks.place"), 0.7f, 0.8f);
      }
    }
  }

  /**
   * Places one layer at the current frontier and steps the frontier down. Returns false (without
   * consuming the frontier) once the column has bottomed out at the world floor.
   */
  private boolean addLayer(WorldAdapter world, String suffix) {
    if (world == null) {
      return false;
    }
    WorldPosition spawn = world.spawnPosition();
    if (spawn == null) {
      return false;
    }
    String frontierKey = KEY_FRONTIER + suffix;
    String layersKey = KEY_LAYERS + suffix;
    int y = stateInt(frontierKey, spawnBlock(world) - 3);
    // Each dimension bottoms out at ITS OWN floor. The Nether's is 0 against the Overworld's -64, so a
    // shared limit would have kept driving the Nether column past the bottom of its world.
    if (y <= world.minBuildHeight() + 1) {
      return false; // hit the world floor — stop deepening
    }
    Layer layer = deckFor(suffix).roll(ThreadLocalRandom.current());
    int centerX = (int) Math.floor(spawn.coordinateX());
    int centerZ = (int) Math.floor(spawn.coordinateZ());
    ItemKey floor = layer.block() == null ? MOB_FLOOR_FALLBACK : layer.block();
    StructureBuilder builder = new StructureBuilder(world);
    builder.slab(spawn.worldName(), centerX, centerZ, y, layerSize, floor);
    if (layer.kind() == LayerDeck.Kind.TNT_TRAP) {
      // Stone pressure plates resting on the TNT slab: stepping in powers the block below and detonates it.
      builder.slab(spawn.worldName(), centerX, centerZ, y + 1, layerSize, PRESSURE_PLATE);
    }
    if (layer.kind() == LayerDeck.Kind.MOB_SPAWN && layer.mob() != null) {
      WorldPosition mobAt = new WorldPosition(spawn.worldName(), centerX + 0.5, y + 1, centerZ + 0.5, 0f, 0f);
      world.spawnMob(mobAt, layer.mob(), layer.mobCount());
    }
    setStateInt(frontierKey, y - 1);
    setStateInt(layersKey, stateInt(layersKey, 0) + 1);
    stats().add("randomlayers.count", 1);
    return true;
  }

  /** Each dimension rolls its layers from its own deck, so the Nether is made of Nether blocks. */
  private LayerDeck deckFor(String suffix) {
    return NETHER_SUFFIX.equals(suffix) ? netherDeck : deck;
  }

  private void describeHud(HudContext context) {
    // The count is the column the viewer is standing in: the two dimensions deepen independently, so a
    // shared total would tell a player in the Nether about layers that are somewhere else entirely.
    String suffix = viewerSuffix(context);
    context.line("<gold>Layers added:</gold> <white>" + stateInt(KEY_LAYERS + suffix, 0)
        + (NETHER_SUFFIX.equals(suffix) ? "</white> <dark_red>(Nether)</dark_red>" : "</white>"));
    context.line("<gray>Day:</gray> <white>" + stateInt(KEY_DAYS, 0) + "</white>");
    context.line("<gray>Next layers in:</gray> <white>" + nextLayersIn() + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Layers / day", layersPerDay);
      context.debugStat("Day length", dayLengthTicks + "t");
      context.debugStat("Layer size", layerSize + "x" + layerSize);
      context.debugStat("Frontier Y (overworld)", stateInt(KEY_FRONTIER, 0));
      context.debugStat("Frontier Y (nether)", stateInt(KEY_FRONTIER + NETHER_SUFFIX, 0));
      context.debugStat("Nether layers", stateInt(KEY_LAYERS + NETHER_SUFFIX, 0));
      context.debugStat("Nether built", stateHas(KEY_FRONTIER + NETHER_SUFFIX));
      context.debugStat("Palette / mobs", paletteSize + " / " + mobTypeCount);
    }
  }

  /** Which column's numbers to show: the one the viewer is actually standing in. */
  private String viewerSuffix(HudContext context) {
    PlayerAdapter viewer = context.player();
    WorldAdapter viewerWorld = viewer == null ? null : viewer.world();
    return viewerWorld != null && viewerWorld.isNether() ? NETHER_SUFFIX : OVERWORLD_SUFFIX;
  }

  /** {@code m:ss} until the next in-game day begins, from the live world clock ("—" without a world). */
  private String nextLayersIn() {
    WorldAdapter world = world();
    if (world == null) {
      return "—";
    }
    long into = Math.floorMod(world.fullTimeTicks(), dayLengthTicks);
    long ticksLeft = dayLengthTicks - into;
    long totalSeconds = Math.max(0L, ticksLeft / 20L);
    return totalSeconds / 60L + ":" + String.format(Locale.ROOT, "%02d", totalSeconds % 60L);
  }

  private long currentDayIndex(WorldAdapter world) {
    return world == null ? 0L : Math.floorDiv(world.fullTimeTicks(), dayLengthTicks);
  }

  private int spawnBlock(WorldAdapter world) {
    if (world == null || world.spawnPosition() == null) {
      return 64;
    }
    return (int) Math.floor(world.spawnPosition().coordinateY());
  }

  // Cached for the debug HUD readout so it does not re-resolve config on every render pass.
  private int paletteSize;
  private int mobTypeCount;

  private LayerDeck buildDeck() {
    List<ItemKey> palette = resolvePalette();
    List<String> mobTypes = resolveMobTypes();
    paletteSize = palette.size();
    mobTypeCount = mobTypes.size();
    double tntChance = cfg().getDouble(configPath("tnt-layer-chance"), 0.12);
    double mobChance = cfg().getDouble(configPath("mob-layer-chance"), 0.15);
    int mobMin = Math.max(1, cfg().getInt(configPath("mob-count-min"), 4));
    int mobMax = Math.max(mobMin, cfg().getInt(configPath("mob-count-max"), 10));
    return new LayerDeck(palette, mobTypes, tntChance, mobChance, mobMin, mobMax);
  }

  /**
   * The Nether's deck: its own palette and its own mobs, but the same TNT/mob-layer odds, so the descent
   * feels like the same mode in a different place rather than a different mode.
   */
  private LayerDeck buildNetherDeck() {
    List<ItemKey> palette = resolveKeys("nether-palette", DEFAULT_NETHER_PALETTE);
    List<String> mobs = resolveStrings("nether-mob-types", DEFAULT_NETHER_MOBS);
    double tntChance = cfg().getDouble(configPath("tnt-layer-chance"), 0.12);
    double mobChance = cfg().getDouble(configPath("mob-layer-chance"), 0.15);
    int mobMin = Math.max(1, cfg().getInt(configPath("mob-count-min"), 4));
    int mobMax = Math.max(mobMin, cfg().getInt(configPath("mob-count-max"), 10));
    return new LayerDeck(palette, mobs, tntChance, mobChance, mobMin, mobMax);
  }

  /** A config block-id list, falling back to the given defaults when unset or unusable. */
  private List<ItemKey> resolveKeys(String key, List<String> fallback) {
    List<ItemKey> parsed = new ArrayList<>();
    for (String entry : resolveStrings(key, fallback)) {
      ItemKey block = ItemKey.parse(entry);
      if (block != null) {
        parsed.add(block);
      }
    }
    if (parsed.isEmpty()) {
      for (String entry : fallback) {
        parsed.add(ItemKey.minecraft(entry));
      }
    }
    return parsed;
  }

  private List<String> resolveStrings(String key, List<String> fallback) {
    List<String> configured = cfg().getStringList(configPath(key));
    List<String> source = configured == null || configured.isEmpty() ? fallback : configured;
    List<String> values = new ArrayList<>();
    for (String entry : source) {
      if (entry != null && !entry.isBlank()) {
        values.add(entry.trim().toLowerCase(Locale.ROOT));
      }
    }
    return values.isEmpty() ? fallback : values;
  }

  private List<ItemKey> resolvePalette() {
    List<String> configured = cfg().getStringList(configPath("palette"));
    if (configured == null || configured.isEmpty()) {
      return ChallengePalettes.COMPREHENSIVE_BLOCKS;
    }
    List<ItemKey> parsed = new ArrayList<>();
    for (String entry : configured) {
      ItemKey key = ItemKey.parse(entry);
      if (key != null) {
        parsed.add(key);
      }
    }
    return parsed.isEmpty() ? ChallengePalettes.COMPREHENSIVE_BLOCKS : parsed;
  }

  private List<String> resolveMobTypes() {
    List<String> configured = cfg().getStringList(configPath("mob-types"));
    if (configured != null && !configured.isEmpty()) {
      List<String> mobs = new ArrayList<>();
      for (String entry : configured) {
        if (entry != null && !entry.isBlank()) {
          mobs.add(entry.trim().toLowerCase(Locale.ROOT));
        }
      }
      return mobs;
    }
    return List.of("zombie", "skeleton", "spider", "creeper");
  }
}
