package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every chunk a participant steps into for the first time is rolled to a single random block type and
 * has its <em>existing</em> blocks replaced with it — the whole chunk, from the world floor to the
 * world ceiling. Only blocks that are already there are swapped; air is never filled, so the chunk
 * keeps its natural shape and just changes material. The roll is locked in shared persistent state,
 * so a chunk keeps the block it was assigned across disconnects/restarts and every player who later
 * enters sees the same conversion.
 *
 * <p>The roll is deterministic from the configured {@code seed} and the chunk coordinates. The fill
 * is done <strong>layer by layer across ticks</strong> (a self-cancelling timer advances a Y cursor
 * by {@code layers-per-tick} each tick from the bottom layer to the top) so converting a full-height
 * chunk never lands as a single synchronous lag spike. Blocks in the {@code blacklist} (by default
 * every kind of leaf plus flowers, grass, mushrooms and leaf litter) are never replaced, so trees
 * keep their canopies and decorative ground cover is left intact.</p>
 */
public final class RandomChunksChallenge extends Challenge {
  // Empty config palette -> a chunk can roll into essentially any block in the game (terrain, ores,
  // decorative, even tnt/water/lava for the lethal-chunk chaos the rule is known for).
  private static final List<ItemKey> DEFAULT_PALETTE = ChallengePalettes.COMPREHENSIVE_BLOCKS;

  // Never replaced by default. Leaves keep tree canopies; snow + ground plants (grass, flowers,
  // mushrooms, leaf litter) are kept because swapping a thin decorative layer for a FULL solid block
  // grows it taller and buries or traps the player standing there.
  private static final Set<String> DEFAULT_BLACKLIST = Set.of(
      "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves",
      "dark_oak_leaves", "mangrove_leaves", "cherry_leaves", "pale_oak_leaves", "azalea_leaves",
      "flowering_azalea_leaves",
      "snow", "snow_block", "short_grass", "grass", "tall_grass", "fern", "large_fern",
      // End Portal room — never convert, or the stronghold becomes unusable and Minecraft cannot be
      // completed. Frame holds the eyes; the lit portal is the exit to the End.
      "end_portal_frame", "end_portal", "end_gateway",
      // Leaf litter (Pale Garden ground cover).
      "leaf_litter",
      // Mushrooms / nether fungi.
      "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
      // Flowers (small + tall) and petal carpets.
      "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
      "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose",
      "torchflower", "closed_eyeblossom", "open_eyeblossom",
      "sunflower", "lilac", "rose_bush", "peony", "pink_petals", "wildflowers");

  // When the rolled block is one of these (damaging / physics-heavy / fluid), the fill is slowed to
  // {@code slow-layers-per-tick} so placing a whole chunk of them does not cause lag freezes.
  private static final Set<String> DESTRUCTIVE_BLOCKS = Set.of(
      "cactus", "tnt", "lava", "water", "fire", "soul_fire", "magma_block", "powder_snow",
      "pointed_dripstone", "sweet_berry_bush", "wither_rose", "campfire", "soul_campfire");

  private List<ItemKey> palette;
  private Set<String> blacklist = DEFAULT_BLACKLIST;
  private int layersPerTick;
  private int slowLayersPerTick;

  public RandomChunksChallenge() {
    super("randomchunks", "Random Chunks");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gray>Chunks converted:</gray> <white>" + stats().get("randomchunks.converted") + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Palette", (palette == null ? 0 : palette.size()) + " blocks");
      context.debugStat("Blacklist", blacklist.size());
      context.debugStat("Layers/tick", layersPerTick + " (slow " + slowLayersPerTick + ")");
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    palette = buildPalette();
    // The mode's own blacklist PLUS the shared world-integrity guard: a chunk rewrite must leave the
    // blocks no mode may destroy exactly where they are.
    blacklist = blocks().guard().with(buildBlacklist()).preservedValues();
    layersPerTick = Math.max(1, cfg().getInt(configPath("layers-per-tick"), 8));
    slowLayersPerTick = Math.max(1, cfg().getInt(configPath("slow-layers-per-tick"), 2));
  }

  @Override
  public void onPlayerMove(PlayerMoveGameEvent event) {
    if (isParticipant(event.playerAdapter())) {
      WorldPosition from = event.fromPosition();
      WorldPosition to = event.toPosition();
      if (to == null) {
        return;
      }
      int cx = Math.floorDiv((int) Math.floor(to.coordinateX()), 16);
      int cz = Math.floorDiv((int) Math.floor(to.coordinateZ()), 16);
      if (from != null) {
        int fx = Math.floorDiv((int) Math.floor(from.coordinateX()), 16);
        int fz = Math.floorDiv((int) Math.floor(from.coordinateZ()), 16);
        if (fx == cx && fz == cz && sameWorld(from, to)) {
          return; // still in the same chunk — nothing new to convert
        }
      }
      WorldAdapter world = event.playerAdapter().world();
      if (world == null) {
        return;
      }
      // The lock is per WORLD as well as per chunk: an experience is three worlds, and chunk (5, 5) of the
      // Nether is not the chunk (5, 5) that was already rolled in the Overworld. Sharing one key meant
      // every chunk in a sibling dimension arrived pre-locked and never converted.
      String key = "chunk." + to.worldName() + "." + cx + "." + cz;
      if (stateHas(key)) {
        return; // this chunk was already rolled and locked
      }
      WorldPosition rollRef = new WorldPosition(world.name(), cx * 16 + 8, 64, cz * 16 + 8, 0.0F, 0.0F);
      ItemKey block = rollBlock(cx, cz, rollRef);
      if (block == null) {
        // Every palette type is currently deleted/broken-forever by a sibling — leave the chunk in
        // its original material (don't lock it) so it can roll on a later entry, instead of painting a
        // type that would be re-cleared next tick.
        return;
      }
      setStateInt(key, 1);
      stats().add("randomchunks.converted", 1);
      startLayeredFill(event.playerAdapter(), cx, cz, block);
    }
  }

  /**
   * Replaces the freshly entered chunk's existing blocks with its rolled type. The fill starts at the
   * player's own Y layer and grows outward in BOTH directions — up toward the ceiling and down toward
   * the floor — converting a band of layers each tick on each side until both reach the world edge.
   * Starting at the player keeps the change visible/relevant where they stand and never lands as a
   * single big spike. Destructive rolls (lava, tnt, cactus, …) advance fewer layers per tick to avoid
   * lag freezes.
   */
  private void startLayeredFill(PlayerAdapter playerAdapter, int cx, int cz, ItemKey block) {
    WorldAdapter world = playerAdapter.world();
    if (world == null) {
      return;
    }
    int minY = world.minBuildHeight();
    int maxY = world.maxBuildHeight(); // exclusive top -> highest valid Y is maxY - 1
    WorldPosition position = playerAdapter.position();
    int center = clamp(position != null ? (int) Math.floor(position.coordinateY()) : (minY + maxY) / 2, minY, maxY - 1);
    int perTick = isDestructive(block) ? slowLayersPerTick : layersPerTick;
    // Convert the player's own layer immediately, then expand outward from it.
    world.convertChunk(cx, cz, block, center, center, blacklist);
    int[] up = {center + 1};
    int[] down = {center - 1};
    ScheduledTask[] task = new ScheduledTask[1];
    task[0] = runTimer(() -> {
      boolean moreUp = up[0] <= maxY - 1;
      boolean moreDown = down[0] >= minY;
      if (!moreUp && !moreDown) {
        cancel(task[0]);
        return;
      }
      if (moreUp) {
        int end = Math.min(maxY - 1, up[0] + perTick - 1);
        world.convertChunk(cx, cz, block, up[0], end, blacklist);
        up[0] = end + 1;
      }
      if (moreDown) {
        int start = Math.max(minY, down[0] - perTick + 1);
        world.convertChunk(cx, cz, block, start, down[0], blacklist);
        down[0] = start - 1;
      }
      if (up[0] > maxY - 1 && down[0] < minY) {
        cancel(task[0]);
      }
    }, 1L, 1L);
    if (cfg().getBoolean(configPath("play-sound"), true)) {
      playerAdapter.playSound(new SoundKey("minecraft:block.amethyst_block.chime"), 0.6f, 1.0f);
    }
  }

  private boolean isDestructive(ItemKey block) {
    return block != null && DESTRUCTIVE_BLOCKS.contains(block.value());
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static void cancel(ScheduledTask task) {
    if (task != null) {
      task.cancel();
    }
  }

  /**
   * Deterministically maps (seed, chunkX, chunkZ) onto a palette entry the block vetoes allow, or null
   * when every candidate is currently deleted/broken-forever by a sibling. The fallback is the broad
   * default palette filtered the SAME way — never an unfiltered list, which would hand back a type that
   * is immediately re-cleared.
   */
  private ItemKey rollBlock(int cx, int cz, WorldPosition refPos) {
    ItemKey chosen = pickAllowed(palette != null ? palette : buildPalette(), cx, cz, refPos);
    return chosen != null ? chosen : pickAllowed(DEFAULT_PALETTE, cx, cz, refPos);
  }

  /** Deterministic pick from {@code source} restricted to veto-allowed types, or null if none allow. */
  private ItemKey pickAllowed(List<ItemKey> source, int cx, int cz, WorldPosition refPos) {
    // Exclude types a sibling challenge removes forever (Break-One-Break-All broken, Block Deleter
    // deleted) so a freshly converted chunk is not immediately swept/cleared away again.
    List<ItemKey> allowed = new ArrayList<>(source.size());
    for (ItemKey candidate : source) {
      if (blocks().allowsPlace(refPos, candidate)) {
        allowed.add(candidate);
      }
    }
    if (allowed.isEmpty()) {
      return null;
    }
    long seed = cfg().getLong(configPath("seed"), 1337L);
    long h = seed * 0x9E3779B97F4A7C15L ^ (cx * 341873128712L) ^ (cz * 132897987541L);
    int idx = Math.floorMod((int) (h ^ (h >>> 32)), allowed.size());
    return allowed.get(idx);
  }

  /** Reads the configured palette, falling back to {@link #DEFAULT_PALETTE} when unset/empty. */
  private List<ItemKey> buildPalette() {
    List<String> configured = cfg().getStringList(configPath("palette"));
    if (configured == null || configured.isEmpty()) {
      return DEFAULT_PALETTE;
    }
    List<ItemKey> parsed = new ArrayList<>(configured.size());
    for (String entry : configured) {
      if (entry == null || entry.isBlank()) {
        continue;
      }
      parsed.add(parseItemKey(entry));
    }
    return parsed.isEmpty() ? DEFAULT_PALETTE : parsed;
  }

  /** Block values never replaced; empty config -> {@link #DEFAULT_BLACKLIST} (all leaves). */
  private Set<String> buildBlacklist() {
    List<String> configured = cfg().getStringList(configPath("blacklist"));
    if (configured == null || configured.isEmpty()) {
      return DEFAULT_BLACKLIST;
    }
    Set<String> values = new HashSet<>();
    for (String entry : configured) {
      if (entry != null && !entry.isBlank()) {
        values.add(parseItemKey(entry).value());
      }
    }
    return values.isEmpty() ? DEFAULT_BLACKLIST : values;
  }

  /** Whether two positions are in the same world — a portal keeps your chunk coordinates but not your world. */
  private static boolean sameWorld(WorldPosition from, WorldPosition to) {
    return from != null && to != null && from.worldName() != null && from.worldName().equals(to.worldName());
  }

  private ItemKey parseItemKey(String entry) {
    String trimmed = entry.trim();
    int colon = trimmed.indexOf(':');
    if (colon >= 0) {
      return new ItemKey(trimmed.substring(0, colon), trimmed.substring(colon + 1));
    }
    return ItemKey.minecraft(trimmed.toLowerCase(Locale.ROOT));
  }
}
