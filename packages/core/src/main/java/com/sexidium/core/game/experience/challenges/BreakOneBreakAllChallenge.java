package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.BlockChangeVeto;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DropSource;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.experience.compose.SweepLootSink;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.world.PlayerRadius;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.BrokenBlock;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Break One Break All: the first time anyone breaks a block of some type, that type becomes
 * "broken everywhere" — and from then on every block of that type vanishes around every player.
 *
 * <p>The set of broken types is <strong>shared and persistent</strong> (stored in the experience's
 * shared state as a CSV under {@code brokentypes}), so a type stays broken across disconnects and
 * restarts, and every player contributes to the same set. Rather than a single huge synchronous
 * sweep, each player runs a <em>budgeted, render-distance, ball-shaped sweep</em>: a per-tick timer
 * removes up to {@code blocks-per-tick} broken-type blocks, walking outward from the player in
 * expanding spherical shells ({@link SphereSweep}) until it reaches the player's render distance. The
 * sweep re-centres whenever the player crosses into a new chunk and restarts for everyone when a new
 * type is broken, so as players move around, blocks broken by anyone get cleaned up within each
 * player's render distance.</p>
 */
public final class BreakOneBreakAllChallenge extends Challenge {
  private static final String KEY_BROKEN = "brokentypes";

  /** Per-player progress of the expanding-ball sweep. Transient — rebuilt from movement/breaks. */
  private static final class SweepState {
    String worldName;
    int centerX;
    int centerY;
    int centerZ;
    int maxRadius;
    int shell;     // current Chebyshev shell being processed
    int index;     // next offset index within the current shell
    boolean done;
  }

  // The shared, in-memory broken-type set (mirrors the persisted CSV; one instance per experience).
  private final Set<String> brokenTypes = new LinkedHashSet<>();
  private final Map<UUID, SweepState> sweeps = new HashMap<>();
  private final Map<UUID, Long> lastChunk = new HashMap<>();
  private final int[] offset = new int[3];

  private int blocksPerTick;
  private int maxIterationsPerTick;
  private boolean dropItems;
  private boolean chunkStacksMode;
  private double chunkMergeRadius;
  private boolean playSound;
  private int maxRadiusBlocks;
  private SweepLootSink sink;

  public BreakOneBreakAllChallenge() {
    super("breakonebreakall", "Break One Break All");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    // A type that is broken-everywhere must not be re-placeable by a placer challenge (Walking
    // Blocks), or the floor would flicker as one challenge places what this one immediately sweeps.
    registry.blockVeto(new BlockChangeVeto() {
      @Override
      public boolean allowsPlace(WorldPosition position, ItemKey type) {
        return type == null || !brokenTypes.contains(type.value());
      }
    });
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gray>Broken types:</gray> <white>" + brokenTypes.size() + "</white>");
    context.line("<gray>Blocks cleared:</gray> <white>" + stats().get("breakonebreakall.cleared") + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Active sweeps", sweeps.size());
      context.debugStat("Blocks/tick", blocksPerTick + " (max iter " + maxIterationsPerTick + ")");
      context.debugStat("Loot", chunkStacksMode ? "chunk-stacks" : (dropItems ? "drop" : "inventory"));
      context.debugStat("Max radius", maxRadiusBlocks <= 0 ? "∞" : (maxRadiusBlocks + " blocks"));
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    blocksPerTick = Math.max(1, cfg().getInt(configPath("blocks-per-tick"), 256));
    maxIterationsPerTick = Math.max(blocksPerTick, cfg().getInt(configPath("max-iterations-per-tick"), blocksPerTick * 8));
    dropItems = cfg().getBoolean(configPath("drop-items"), true);
    String lootMode = cfg().getString(configPath("loot-mode"), "inventory");
    chunkStacksMode = lootMode != null
        && (lootMode.equalsIgnoreCase("chunk-stacks") || lootMode.equalsIgnoreCase("chunk")
            || lootMode.equalsIgnoreCase("chunkstacks"));
    chunkMergeRadius = Math.max(0.0, cfg().getDouble(configPath("chunk-merge-radius"), 4.0));
    playSound = cfg().getBoolean(configPath("play-sound"), true);
    maxRadiusBlocks = Math.max(0, cfg().getInt(configPath("max-radius-blocks"), 0));
    sink = new SweepLootSink(blocks(),
        gameContext() == null ? null : gameContext().stackMerge(), chunkMergeRadius);
    loadBrokenTypes();
    runTimer(this::sweepTick, 1L, 1L);
  }

  @Override
  public void onBlockBreak(BlockBreakGameEvent breakEvent) {
    if (isParticipant(breakEvent.playerAdapter())
        && breakEvent.blockKey() != null) {
      String value = breakEvent.blockKey().value();
      if (brokenTypes.add(value)) {
        // A newly broken type: persist it and restart every player's sweep so it propagates around all.
        saveBrokenTypes();
        for (PlayerAdapter player : online()) {
          restartSweep(player);
          if (playSound && player.position() != null) {
            player.playSound(new SoundKey("minecraft:entity.generic.explode"), 0.5f, 1.3f);
          }
        }
        breakEvent.playerAdapter().sendActionBar("<gray>Now breaking ALL <white>" + value + "</white>");
      }
    }
  }

  @Override
  public void onPlayerMove(PlayerMoveGameEvent moveEvent) {
    if (isParticipant(moveEvent.playerAdapter())) {
      WorldPosition to = moveEvent.toPosition();
      if (to == null) {
        return;
      }
      long chunk = packChunk(Math.floorDiv((int) Math.floor(to.coordinateX()), 16),
          Math.floorDiv((int) Math.floor(to.coordinateZ()), 16));
      UUID id = moveEvent.playerAdapter().uniqueId();
      Long previous = lastChunk.put(id, chunk);
      // Re-centre the sweep on a new chunk so the render-distance bubble follows the player.
      if (previous == null || previous != chunk) {
        restartSweep(moveEvent.playerAdapter());
      }
    }
  }

  @Override
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      sweeps.remove(playerAdapter.uniqueId());
      lastChunk.remove(playerAdapter.uniqueId());
    }
  }

  /** Advances every online player's expanding-ball sweep by one tick's budget. */
  private void sweepTick() {
    if (brokenTypes.isEmpty()) {
      return;
    }
    for (PlayerAdapter player : online()) {
      SweepState state = sweeps.get(player.uniqueId());
      if (state == null) {
        state = startSweep(player);
        if (state == null) {
          continue;
        }
      }
      if (!state.done) {
        sweepPlayer(player, state);
      }
    }
  }

  private void sweepPlayer(PlayerAdapter player, SweepState state) {
    WorldAdapter world = player.world();
    if (world == null) {
      return;
    }
    long radiusSquared = (long) state.maxRadius * state.maxRadius;
    // Accumulate this tick's removals so they can be GROUPED before being given back, instead of one
    // item entity per broken block. Loot is keyed by the BROKEN BLOCK TYPE (-> its natural drops as
    // dropItem -> count) so the drop pipeline (Randomizer remap, Double Drops) sees the broken block,
    // exactly as a manual break does. Single-point mode groups by block type; chunk-stacks mode groups
    // by (chunk, block type).
    Map<ItemKey, Map<ItemKey, Integer>> inventoryDrops = (dropItems && !chunkStacksMode) ? new HashMap<>() : null;
    Map<Long, Map<ItemKey, Map<ItemKey, Integer>>> chunkDrops = (dropItems && chunkStacksMode) ? new HashMap<>() : null;
    Map<Long, Integer> chunkDropY = chunkDrops != null ? new HashMap<>() : null;
    // Per (chunk, broken type) the position where that type was first broken in that chunk — its 25%
    // break-block drop lands there, tied to the type and kept inside its own chunk.
    Map<Long, Map<ItemKey, WorldPosition>> chunkBreakPos = chunkDrops != null ? new HashMap<>() : null;
    int removed = 0;
    int walked = 0;
    while (removed < blocksPerTick && walked < maxIterationsPerTick) {
      if (state.shell > state.maxRadius) {
        state.done = true;
        break;
      }
      int shellSize = SphereSweep.shellSize(state.shell);
      if (state.index >= shellSize) {
        state.shell++;
        state.index = 0;
        continue;
      }
      SphereSweep.offsetAt(state.shell, state.index, offset);
      state.index++;
      walked++;
      long distanceSquared = (long) offset[0] * offset[0]
          + (long) offset[1] * offset[1]
          + (long) offset[2] * offset[2];
      if (distanceSquared > radiusSquared) {
        continue; // outside the ball — keep the region spherical, not cubic
      }
      int blockX = state.centerX + offset[0];
      int blockY = state.centerY + offset[1];
      int blockZ = state.centerZ + offset[2];
      // Resolve the broken block's type + natural loot (stone -> cobblestone, ore -> resource) BEFORE
      // removal; null means no matching block was here (nothing removed).
      BrokenBlock broken = world.breakIfTypeNatural(
          new BlockPosition(state.worldName, blockX, blockY, blockZ), blocks().breakableTypes(brokenTypes));
      if (broken == null) {
        continue;
      }
      removed++;
      ItemKey blockType = broken.type();
      Map<ItemKey, Integer> typeBucket;
      if (inventoryDrops != null) {
        typeBucket = inventoryDrops.computeIfAbsent(blockType, key -> new HashMap<>());
      } else {
        long chunkKey = packChunk(blockX >> 4, blockZ >> 4);
        typeBucket = chunkDrops.computeIfAbsent(chunkKey, key -> new HashMap<>())
            .computeIfAbsent(blockType, key -> new HashMap<>());
        chunkDropY.put(chunkKey, blockY);
        // Remember where this type was first broken in this chunk — its 25% break-block share drops here.
        chunkBreakPos.computeIfAbsent(chunkKey, key -> new HashMap<>())
            .putIfAbsent(blockType, new WorldPosition(state.worldName,
                blockX + 0.5, blockY + 0.5, blockZ + 0.5, 0.0F, 0.0F));
      }
      for (ItemStackData stack : broken.drops()) {
        typeBucket.merge(stack.itemKey(), stack.amount(), Integer::sum);
      }
    }
    if (removed > 0) {
      stats().add("breakonebreakall.cleared", removed);
    }
    if (inventoryDrops != null) {
      sink.emitToGround(player, world, player.position(), inventoryDrops);
    } else if (chunkDrops != null && !chunkDrops.isEmpty()) {
      // Emit each swept chunk on its own: a type's 25% lands where that type was broken in that chunk and
      // the rest spreads across that same chunk's region points — no cross-chunk or cross-type mixing.
      for (Map.Entry<Long, Map<ItemKey, Map<ItemKey, Integer>>> chunkEntry : chunkDrops.entrySet()) {
        long chunkKey = chunkEntry.getKey();
        Map<ItemKey, WorldPosition> breakPositions = chunkBreakPos.getOrDefault(chunkKey, Map.of());
        Map<ItemKey, SweepLootSink.TypeDrop> byType = new HashMap<>();
        for (Map.Entry<ItemKey, Map<ItemKey, Integer>> typeEntry : chunkEntry.getValue().entrySet()) {
          byType.put(typeEntry.getKey(),
              new SweepLootSink.TypeDrop(breakPositions.get(typeEntry.getKey()), typeEntry.getValue()));
        }
        List<WorldPosition> regionPoints = SweepLootSink.chunkRegionPoints(state.worldName,
            (int) (chunkKey >> 32) << 4, (int) chunkKey << 4,
            chunkDropY.getOrDefault(chunkKey, 64));
        sink.emitChunkStacks(player, world, regionPoints, byType);
      }
    }
  }

  /** Creates and seeds a sweep for a player at their current position, or null if unplaceable. */
  private SweepState startSweep(PlayerAdapter player) {
    WorldPosition position = player.position();
    if (position == null || position.worldName() == null) {
      return null;
    }
    SweepState state = new SweepState();
    recentre(state, player, position);
    sweeps.put(player.uniqueId(), state);
    return state;
  }

  private void restartSweep(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    WorldPosition position = player.position();
    if (position == null || position.worldName() == null) {
      return;
    }
    SweepState state = sweeps.computeIfAbsent(player.uniqueId(), id -> new SweepState());
    recentre(state, player, position);
  }

  private void recentre(SweepState state, PlayerAdapter player, WorldPosition position) {
    state.worldName = position.worldName();
    state.centerX = (int) Math.floor(position.coordinateX());
    state.centerY = (int) Math.floor(position.coordinateY());
    state.centerZ = (int) Math.floor(position.coordinateZ());
    state.maxRadius = radiusFor(player);
    state.shell = 0;
    state.index = 0;
    state.done = false;
  }

  /**
   * The sweep's reach, from the shared {@link PlayerRadius} rules — the player's own render distance plus
   * the standard overscan — optionally capped by {@code max-radius-blocks}. Going through the shared API
   * is what keeps this sweep, the Omni-Chunk replication and the ground-item consolidation agreeing on
   * where a player's world ends.
   */
  private int radiusFor(PlayerAdapter player) {
    int radius = (int) Math.ceil(PlayerRadius.blockRadius(player, 1, Integer.MAX_VALUE));
    if (maxRadiusBlocks > 0) {
      radius = Math.min(radius, maxRadiusBlocks);
    }
    return Math.max(1, radius);
  }

  // ----- shared persistent broken-type set -------------------------------------------------------

  private void loadBrokenTypes() {
    brokenTypes.clear();
    String stored = stateString(KEY_BROKEN, "");
    if (stored != null && !stored.isBlank()) {
      for (String value : stored.split(",")) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
          brokenTypes.add(trimmed);
        }
      }
    }
  }

  private void saveBrokenTypes() {
    setStateString(KEY_BROKEN, String.join(",", brokenTypes));
  }

  private static long packChunk(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
  }
}
