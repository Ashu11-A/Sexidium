package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DropSource;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.experience.compose.SweepLootSink;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.BrokenBlock;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Chunk Break: a chunk-scoped cousin of {@link BreakOneBreakAllChallenge}. When a participant breaks a
 * block of some type, every OTHER block of that type in the SAME chunk vanishes too — but only that
 * chunk, not the world. This is a one-shot reaction per break (there is no persistent "broken forever"
 * set and no placement veto): the chunk is swept once and the loot collected, then it is over.
 *
 * <p>Each break enqueues a {@link ChunkJob} that a per-tick budget loop ({@code blocks-per-tick} /
 * {@code max-iterations-per-tick}) walks over the chunk's full Y column, mirroring the budgeting of
 * Break-One-Break-All so a large chunk never stalls the server. Swept loot is routed through the shared
 * drop pipeline (so Double Drops / Randomizer still apply) and delivered either to the breaker's
 * inventory or as merged ground stacks. Open-ended: never ends the match.</p>
 */
public final class ChunkBreakChallenge extends Challenge {
  /** One queued chunk sweep for a single block type, walked across ticks by a linear cursor. */
  private static final class ChunkJob {
    final String worldName;
    final int chunkX;
    final int chunkZ;
    final Set<String> typeValues;
    final PlayerAdapter cause;
    final int breakX; // the block the player broke to trigger this sweep — 25% of loot lands here
    final int breakY;
    final int breakZ;
    int cursor; // linear index: layer = cursor/256 (Y), within-layer = cursor%256 ((dx<<4)|dz)

    ChunkJob(String worldName, int chunkX, int chunkZ, String typeValue, PlayerAdapter cause,
        int breakX, int breakY, int breakZ) {
      this.worldName = worldName;
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.typeValues = Set.of(typeValue);
      this.cause = cause;
      this.breakX = breakX;
      this.breakY = breakY;
      this.breakZ = breakZ;
    }
  }

  private final Deque<ChunkJob> jobs = new ArrayDeque<>();
  private final Set<Long> inFlight = new HashSet<>(); // dedupe (chunk + type) while a job runs

  private int blocksPerTick;
  private int maxIterationsPerTick;
  private boolean dropItems;
  private boolean chunkStacksMode;
  private double chunkMergeRadius;
  private boolean playSound;
  private SweepLootSink sink;

  public ChunkBreakChallenge() {
    super("chunkbreak", "Chunk Break");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gray>Chunks swept:</gray> <white>" + stats().get("chunkbreak.chunks") + "</white>");
    context.line("<gray>Blocks cleared:</gray> <white>" + stats().get("chunkbreak.cleared") + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Queued jobs", jobs.size());
      context.debugStat("Blocks/tick", blocksPerTick + " (max iter " + maxIterationsPerTick + ")");
      context.debugStat("Loot", chunkStacksMode ? "chunk-stacks" : (dropItems ? "inventory" : "destroy"));
    }
  }

  @Override
  public void onStart(java.util.List<PlayerAdapter> participants) {
    blocksPerTick = Math.max(1, cfg().getInt(configPath("blocks-per-tick"), 256));
    maxIterationsPerTick = Math.max(blocksPerTick, cfg().getInt(configPath("max-iterations-per-tick"), blocksPerTick * 8));
    dropItems = cfg().getBoolean(configPath("drop-items"), true);
    String lootMode = cfg().getString(configPath("loot-mode"), "inventory");
    chunkStacksMode = lootMode != null
        && (lootMode.equalsIgnoreCase("chunk-stacks") || lootMode.equalsIgnoreCase("chunk")
            || lootMode.equalsIgnoreCase("chunkstacks"));
    chunkMergeRadius = Math.max(0.0, cfg().getDouble(configPath("chunk-merge-radius"), 4.0));
    playSound = cfg().getBoolean(configPath("play-sound"), true);
    sink = new SweepLootSink(blocks(),
        gameContext() == null ? null : gameContext().stackMerge(), chunkMergeRadius);
    runTimer(this::tick, 1L, 1L);
  }

  @Override
  public void onBlockBreak(BlockBreakGameEvent breakEvent) {
    if (!isParticipant(breakEvent.playerAdapter())
        || breakEvent.blockKey() == null
        || breakEvent.blockPosition() == null) {
      return;
    }
    String value = breakEvent.blockKey().value();
    BlockPosition at = breakEvent.blockPosition();
    int chunkX = Math.floorDiv(at.blockX(), 16);
    int chunkZ = Math.floorDiv(at.blockZ(), 16);
    long dedupe = jobKey(chunkX, chunkZ, value);
    if (!inFlight.add(dedupe)) {
      return; // a sweep of this type in this chunk is already queued/running
    }
    jobs.add(new ChunkJob(at.worldName(), chunkX, chunkZ, value, breakEvent.playerAdapter(),
        at.blockX(), at.blockY(), at.blockZ()));
    if (playSound && breakEvent.playerAdapter().position() != null) {
      breakEvent.playerAdapter().playSound(new SoundKey("minecraft:entity.generic.explode"), 0.4f, 1.4f);
    }
  }

  /** Advances the queued chunk sweeps by one tick's shared budget (across all queued jobs). */
  private void tick() {
    if (jobs.isEmpty()) {
      return;
    }
    WorldAdapter experienceWorld = world();
    if (experienceWorld == null) {
      return;
    }
    int removed = 0;
    int walked = 0;
    while (removed < blocksPerTick && walked < maxIterationsPerTick && !jobs.isEmpty()) {
      ChunkJob job = jobs.peekFirst();
      // Each job sweeps ITS OWN world, and the dimensions are different heights: the Nether is 0…128 and
      // the End 0…256, against the Overworld's -64…320. Sweeping a Nether chunk over the Overworld's range
      // walked two and a half times more slots than the chunk has, almost all of them out of bounds.
      WorldAdapter world = experienceWorld.inWorld(job.worldName);
      int minY = world.minBuildHeight();
      int height = Math.max(1, world.maxBuildHeight() - minY);
      int span = height * 256;
      // Loot keyed by BROKEN BLOCK TYPE (-> natural drops as dropItem -> count) so the drop pipeline
      // (Randomizer remap, Double Drops) sees the broken block, exactly as a manual break does.
      Map<ItemKey, Map<ItemKey, Integer>> drops = (dropItems && !chunkStacksMode) ? new HashMap<>() : null;
      Map<ItemKey, Map<ItemKey, Integer>> chunkBucket = (dropItems && chunkStacksMode) ? new HashMap<>() : null;
      int baseX = job.chunkX << 4;
      int baseZ = job.chunkZ << 4;
      while (job.cursor < span && removed < blocksPerTick && walked < maxIterationsPerTick) {
        int index = job.cursor++;
        walked++;
        int y = minY + (index >> 8);
        int local = index & 255;
        int blockX = baseX + (local >> 4);
        int blockZ = baseZ + (local & 15);
        // The shared guard filters the swept type set, so "break every block of this type" can never
        // include one no mode is allowed to destroy.
        BrokenBlock broken = world.breakIfTypeNatural(
            new BlockPosition(job.worldName, blockX, y, blockZ), blocks().breakableTypes(job.typeValues));
        if (broken == null) {
          continue;
        }
        removed++;
        Map<ItemKey, Map<ItemKey, Integer>> target = drops != null ? drops : chunkBucket;
        if (target != null) {
          Map<ItemKey, Integer> typeBucket = target.computeIfAbsent(broken.type(), key -> new HashMap<>());
          for (ItemStackData stack : broken.drops()) {
            typeBucket.merge(stack.itemKey(), stack.amount(), Integer::sum);
          }
        }
      }
      // All swept loot lands at the exact block that triggered the sweep, not scattered across the chunk.
      WorldPosition breakBlock = new WorldPosition(job.worldName,
          job.breakX + 0.5, job.breakY + 0.5, job.breakZ + 0.5, 0.0F, 0.0F);
      if (drops != null) {
        sink.emitToGround(job.cause, world, breakBlock, drops);
      } else if (chunkBucket != null && !chunkBucket.isEmpty()) {
        Map<ItemKey, SweepLootSink.TypeDrop> byType = new HashMap<>();
        for (Map.Entry<ItemKey, Map<ItemKey, Integer>> typeEntry : chunkBucket.entrySet()) {
          byType.put(typeEntry.getKey(), new SweepLootSink.TypeDrop(breakBlock, typeEntry.getValue()));
        }
        // Empty region points -> 100% lands at the break block instead of spreading across quadrants.
        sink.emitChunkStacks(job.cause, world, java.util.List.of(), byType);
      }
      if (job.cursor >= span) {
        jobs.pollFirst();
        inFlight.remove(jobKey(job.chunkX, job.chunkZ, firstType(job)));
        stats().add("chunkbreak.chunks", 1);
      }
    }
    if (removed > 0) {
      stats().add("chunkbreak.cleared", removed);
    }
  }

  private static String firstType(ChunkJob job) {
    return job.typeValues.iterator().next();
  }

  private static long jobKey(int chunkX, int chunkZ, String typeValue) {
    long base = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    return base * 31 + typeValue.hashCode();
  }
}
