package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Walking Blocks.
 *
 * <p>Rule: every time a participant walks onto a new horizontal block, a random block from a
 * configurable palette spawns directly beneath their feet. The result is a constantly mutating
 * floor that follows the player around as they move.
 *
 * <p>Mechanic: purely event driven (no timer). On every {@link PlayerMoveGameEvent} we detect when
 * the player has crossed into a new horizontal block column and, optionally only when they are not
 * rising (so an in-progress jump does not have its launch block replaced mid-air), we transform the
 * block directly beneath their feet. Two rules keep it well-behaved: it ONLY transforms a block that
 * is already there (air/liquid columns are left empty — never filled in), and a block, once changed,
 * is recorded and never changed again (permanent, like Random Chunks), so repeatedly walking the same
 * floor does not keep re-rolling it.
 */
public final class WalkingBlocksChallenge extends Challenge {

  // Empty config palette -> any block in the game can spawn under your feet (sand to netherite to tnt).
  private static final List<ItemKey> DEFAULT_PALETTE = ChallengePalettes.COMPREHENSIVE_BLOCKS;

  // Never replace these "non-solid" blocks beneath the feet — that would fill air/liquid with blocks.
  private static final Set<String> SKIP_BELOW =
      Set.of("air", "cave_air", "void_air", "water", "lava", "bubble_column");

  // Positions already transformed, so they are never changed twice (permanent). Stored per chunk: an
  // in-memory cache backed by one compact state key per chunk (CSV of local block indices), lazily
  // loaded on first touch so only chunks players actually walk materialize — mirrors Random Chunks.
  private final Map<Long, Set<Integer>> changedByChunk = new HashMap<>();

  private List<ItemKey> palette;
  private boolean onlyWhenNotRising;

  public WalkingBlocksChallenge() {
    super("walkingblocks", "Walking Blocks");
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    onlyWhenNotRising = cfg().getBoolean(configPath("only-when-not-rising"), true);
    palette = resolvePalette();
  }

  @Override
  public void onPlayerMove(PlayerMoveGameEvent event) {
    if (!isParticipant(event.playerAdapter())) {
      return;
    }
    WorldPosition from = event.fromPosition();
    WorldPosition to = event.toPosition();
    if (to == null) {
      return;
    }
    if (from != null && onlyWhenNotRising && to.coordinateY() > from.coordinateY() + 0.001) {
      return; // don't delete the floor mid-jump
    }
    int bx = (int) Math.floor(to.coordinateX());
    int bz = (int) Math.floor(to.coordinateZ());
    if (from != null
        && (int) Math.floor(from.coordinateX()) == bx
        && (int) Math.floor(from.coordinateZ()) == bz) {
      return; // same horizontal block, no new step
    }
    WorldAdapter world = event.playerAdapter().world();
    if (world == null || to.worldName() == null) {
      return;
    }
    int fy = (int) Math.floor(to.coordinateY()) - 1;
    BlockPosition feet = new BlockPosition(to.worldName(), bx, fy, bz);
    // Only transform a block that is already there — never fill air/liquid with a block.
    ItemKey below = world.blockTypeAt(feet);
    if (below == null || SKIP_BELOW.contains(below.value())) {
      return;
    }
    // Never change the same position twice (permanent transformation).
    int cx = bx >> 4;
    int cz = bz >> 4;
    int local = ((fy - world.minBuildHeight()) << 8) + ((bx & 15) << 4) + (bz & 15);
    Set<Integer> changed = changedColumn(cx, cz);
    if (changed.contains(local)) {
      return;
    }
    // The shared funnel decides whether this position may change at all — bedrock under a player's feet
    // and an End portal frame are off-limits to every mode, not just to this one.
    if (!blocks().mayModify(world, feet)) {
      return;
    }
    ItemKey block = pickAllowedBlock(to);
    if (block != null) {
      world.setBlock(feet, block);
      changed.add(local);
      saveChangedChunk(cx, cz, changed);
      stats().add("walkingblocks.placed", 1);
    }
  }

  /** Lazily loads (and caches) the set of already-transformed local block indices for a chunk. */
  private Set<Integer> changedColumn(int chunkX, int chunkZ) {
    long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    Set<Integer> cached = changedByChunk.get(key);
    if (cached != null) {
      return cached;
    }
    Set<Integer> set = new HashSet<>();
    String csv = stateString(chunkStateKey(chunkX, chunkZ), "");
    if (!csv.isBlank()) {
      for (String token : csv.split(",")) {
        if (!token.isBlank()) {
          try {
            set.add(Integer.parseInt(token.trim()));
          } catch (NumberFormatException ignored) {
            // skip a corrupt token
          }
        }
      }
    }
    changedByChunk.put(key, set);
    return set;
  }

  private void saveChangedChunk(int chunkX, int chunkZ, Set<Integer> set) {
    StringBuilder builder = new StringBuilder();
    for (int value : set) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(value);
    }
    setStateString(chunkStateKey(chunkX, chunkZ), builder.toString());
  }

  private static String chunkStateKey(int chunkX, int chunkZ) {
    return "c." + chunkX + "." + chunkZ;
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gray>Blocks placed:</gray> <white>" + stats().get("walkingblocks.placed") + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Palette", (palette == null ? DEFAULT_PALETTE : palette).size() + " blocks");
      context.debugStat("Only when not rising", onlyWhenNotRising);
    }
  }

  /**
   * Rolls a random palette block that the shared block-change vetoes allow placing here, so this
   * never lays down a type another challenge has deleted/broken forever (which would just flicker
   * away). Gives up after a few tries rather than fighting an exhausted palette.
   */
  private ItemKey pickAllowedBlock(WorldPosition at) {
    for (int attempt = 0; attempt < 8; attempt++) {
      ItemKey candidate = randomBlock();
      if (blocks().allowsPlace(at, candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private ItemKey randomBlock() {
    List<ItemKey> active = (palette == null || palette.isEmpty()) ? DEFAULT_PALETTE : palette;
    return active.get(ThreadLocalRandom.current().nextInt(active.size()));
  }

  private List<ItemKey> resolvePalette() {
    List<String> configured = cfg().getStringList(configPath("palette"));
    if (configured == null || configured.isEmpty()) {
      return DEFAULT_PALETTE;
    }
    List<ItemKey> parsed = new ArrayList<>();
    for (String entry : configured) {
      if (entry == null) {
        continue;
      }
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      parsed.add(parseItemKey(trimmed));
    }
    return parsed.isEmpty() ? DEFAULT_PALETTE : parsed;
  }

  private ItemKey parseItemKey(String entry) {
    int separator = entry.indexOf(':');
    if (separator >= 0) {
      String namespace = entry.substring(0, separator).trim().toLowerCase();
      String value = entry.substring(separator + 1).trim().toLowerCase();
      return new ItemKey(namespace, value);
    }
    return ItemKey.minecraft(entry.toLowerCase());
  }
}
