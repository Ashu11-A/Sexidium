package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.ItemEntityHandle;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-wide consolidator of dropped item entities. It works on ANY item lying on the ground in a
 * recently-active region — not only the over-cap stacks a sweep spawned — so vanilla break drops, TNT
 * loot, mob drops and sweep loot all collapse into the fewest entities possible (each up to
 * {@link #MAX_AMOUNT}).
 *
 * <p>{@link #add} pours loot into a nearby same-type entity (growing it past the vanilla 64-cap) or
 * spawns one, and marks the chunk <em>active</em>. Every {@code tick()} each active chunk is rescanned:
 * its item entities are grouped by type and each group is merged into the fewest stacks (the fullest
 * absorbs the emptier ones). A chunk that yields no further merges for a few passes is retired.</p>
 *
 * <p>Platforms that cannot hand back an {@link ItemEntityHandle} (see
 * {@link WorldAdapter#spawnItemEntity}/{@link WorldAdapter#nearbyItems}) degrade gracefully: drops fall
 * back to {@link WorldAdapter#dropItem} and the rescan finds nothing.</p>
 */
public final class StackMergeService {
  /** Hard ceiling for a single merged stack/entity. Past this it is "full" and cannot receive more. */
  public static final int MAX_AMOUNT = 65_536;

  /** Fallback rescan half-extent (blocks) for a chunk activated WITHOUT a caller-supplied merge radius
   * — only the flood safety valve ({@link #markActive}). Chunk-wide so a runaway item pile collapses
   * fully. Sweep-driven chunks instead carry the configured {@code chunk-merge-radius} (see {@link #add}),
   * so the tick rescan never pulls loot in from farther than a drop's own immediate merge did. */
  private static final double DEFAULT_SCAN_RADIUS = 14.0;

  /** Drop a chunk from the active set after this many consecutive rescans with nothing left to merge. */
  private static final int MAX_IDLE_PASSES = 3;

  private final Map<String, ActiveChunk> active = new LinkedHashMap<>();
  // Item entities currently flying toward the stack they are being merged into, keyed by donor id.
  private final Map<UUID, Pull> pulls = new LinkedHashMap<>();
  // How many pulls are inbound to a given receiver, so a stack that is still collecting its neighbours
  // is not itself sent off to stage 2 mid-gather.
  private final Map<UUID, Integer> inbound = new HashMap<>();
  // Areas recently consolidated, kept under close observation so the player-proximity re-validation can
  // wake them again the moment more mergeable items land — chunkKey -> tick the watch expires.
  private final Map<String, Long> watched = new LinkedHashMap<>();
  private MergeAnimation animation = MergeAnimation.DEFAULT;
  private MergePacing pacing = MergePacing.DEFAULT;
  private MergeWatch watch = MergeWatch.DEFAULT;
  private LoggerAdapter logger;
  // Monotonic pump counter, the clock the watch window runs on.
  private long ticks;
  // Diagnostics counters — the answer to "why is this pile not merging?".
  private long itemsScanned;
  private long stacksMerged;
  private long pullsStarted;
  private long pullsCompleted;
  private long pullsTimedOut;
  private long stuckResets;
  private long revalidations;
  // Mergeable items found in the BUSIEST active chunk on the last pass — the pressure the pacing reads.
  private int pressure;
  // Ticks left before the next planning pass; recomputed from the pressure after every pass.
  private long ticksUntilPass;

  /**
   * How fast the consolidation passes run. A fixed interval is wrong in both directions: 40 ticks wastes
   * work on a quiet server and is far too slow when a mode is dumping thousands of items a second, where
   * entities pile up faster than they are collapsed. So the interval STARTS at {@link #baselineTicks} and
   * shortens as pressure rises, down to {@link #minTicks} in an outright emergency.
   *
   * <p>Pressure is deliberately measured as the number of items the merger could ACTUALLY act on: the
   * count comes from the merge scan itself — same centre, same radius the merger works in — and only
   * counts items that have at least one same-type partner in that radius. Items scattered too far apart to
   * merge, or a heap of one-off types, therefore raise no alarm, which is what stops a busy-looking area
   * from accelerating passes that would have nothing to do.</p>
   *
   * @param baselineTicks    the calm interval, and the slowest it ever runs
   * @param minTicks         the fastest interval, used at full emergency
   * @param comfortableItems mergeable items one chunk can hold at the baseline rate; the scale of the ramp
   * @param emergencyItems   pressure at which {@link #emergency()} reports true (for logs/HUD)
   */
  public record MergePacing(long baselineTicks, long minTicks, int comfortableItems, int emergencyItems) {
    public static final MergePacing DEFAULT = new MergePacing(40L, 2L, 64, 256);

    public MergePacing {
      baselineTicks = Math.max(1L, baselineTicks);
      minTicks = Math.max(1L, Math.min(minTicks, baselineTicks));
      comfortableItems = Math.max(1, comfortableItems);
      emergencyItems = Math.max(comfortableItems, emergencyItems);
    }
  }

  /** Replaces the pacing settings (read from config at startup). */
  public void configure(MergePacing pacing) {
    this.pacing = pacing == null ? MergePacing.DEFAULT : pacing;
  }

  /**
   * How areas are kept under observation and how a stuck consolidation is detected.
   *
   * <p>Consolidation used to be able to stop for good: a chunk retires after a few quiet passes, and the
   * only thing that ever woke one again was a player standing next to {@link #floodItems} ground items.
   * A leftover pile below that bar — or a chunk retired by a transient scan failure — was never looked at
   * again. So an area that has recently been consolidated stays <em>watched</em> for {@link #watchTicks},
   * and while watched the player-proximity re-validation wakes it as soon as it holds
   * {@link #minMergeable} mergeable stacks. Areas that were never busy keep the high flood bar, so
   * ordinary survival play is left alone exactly as before.</p>
   *
   * @param watchTicks    how long an area stays under close observation after it was last active
   * @param minMergeable  mergeable stacks that re-wake a WATCHED area (a new area still needs {@code floodItems})
   * @param stalledPasses consecutive passes with items in flight and no progress before the pulls are reset
   * @param floodItems    ground items near a player that wake a brand-new area
   */
  public record MergeWatch(long watchTicks, int minMergeable, int stalledPasses, int floodItems) {
    public static final MergeWatch DEFAULT = new MergeWatch(1200L, 2, 3, 256);

    public MergeWatch {
      watchTicks = Math.max(20L, watchTicks);
      minMergeable = Math.max(2, minMergeable);
      stalledPasses = Math.max(1, stalledPasses);
      floodItems = Math.max(2, floodItems);
    }
  }

  /** A snapshot of what the consolidator is doing and has done — for {@code /sx} debug and tests. */
  public record Diagnostics(int activeChunks, int watchedAreas, int inFlight, int pressure,
      long itemsScanned, long stacksMerged, long pullsStarted, long pullsCompleted,
      long pullsTimedOut, long stuckResets, long revalidations) {
  }

  /** Replaces the watch/stuck-detection settings (read from config at startup). */
  public void configure(MergeWatch watch) {
    this.watch = watch == null ? MergeWatch.DEFAULT : watch;
  }

  /** Optional logger; when set, a stuck area is reported with its location and counts. */
  public void logger(LoggerAdapter logger) {
    this.logger = logger;
  }

  /** Current counters — entity totals included, so a non-merging hotspot is visible rather than guessed. */
  public Diagnostics diagnostics() {
    return new Diagnostics(active.size(), watched.size(), pulls.size(), pressure,
        itemsScanned, stacksMerged, pullsStarted, pullsCompleted, pullsTimedOut, stuckResets, revalidations);
  }

  /**
   * Re-validates the area around a player: counts what is on the ground there and wakes the merger when
   * there is real work. Called on a fixed interval for every online player, which is what guarantees a
   * pile can never be forgotten — a brand-new area still needs {@link MergeWatch#floodItems} items (so
   * ordinary play is untouched), but an area that was recently being consolidated wakes again at
   * {@link MergeWatch#minMergeable}. Returns the mergeable count it found.
   */
  public int validateNear(WorldAdapter world, WorldPosition at, double radius) {
    return validateNear(world, at, radius, false);
  }

  /**
   * As {@link #validateNear(WorldAdapter, WorldPosition, double)}, but {@code consolidating} says the
   * player is somewhere consolidation is the POINT — inside a match or experience world. There the bar is
   * simply "is there anything to merge" ({@link MergeWatch#minMergeable}), because a mode that showers
   * items expects them bundled no matter how few land at once.
   *
   * <p>This is what fixes the "it works for apples but not for logs" class of report: the merger was never
   * type-specific — item types are read straight from the platform registry, so any block or item groups
   * with its own kind — but activation used to need {@link MergeWatch#floodItems} entities on the ground.
   * A payout that showers thousands of leaves or dirt crossed that bar instantly, while a handful of logs
   * from a tree never did, so identical code looked broken for one item and fine for another. Volume no
   * longer decides <em>whether</em> to bundle, only how urgently (see {@link MergePacing}).</p>
   */
  public int validateNear(WorldAdapter world, WorldPosition at, double radius, boolean consolidating) {
    if (world == null || at == null || at.worldName() == null) {
      return 0;
    }
    revalidations++;
    List<ItemEntityHandle> items;
    try {
      items = world.nearbyItems(at, radius);
    } catch (RuntimeException exception) {
      return 0; // an unloaded/foreign region must never break the validation sweep for other players
    }
    itemsScanned += items.size();
    int mergeable = mergeableTotal(groupByType(items));
    boolean watchedArea = watched.containsKey(chunkKeyOf(at));
    // Wake on real work wherever bundling is the point (a match/experience world) or where we were
    // already working; anywhere else still needs a genuine flood, so ordinary survival play is untouched.
    boolean hasWork = mergeable >= watch.minMergeable();
    if (items.size() >= watch.floodItems() || ((consolidating || watchedArea) && hasWork)) {
      activate(world, at, DEFAULT_SCAN_RADIUS);
    }
    return mergeable;
  }

  /**
   * The every-tick driver: advances anything in flight and runs a planning pass whenever the dynamic
   * interval elapses. With no active chunk it is two map checks, so it is free on a calm server.
   */
  public void pump() {
    ticks++;
    if (!watched.isEmpty()) {
      watched.values().removeIf(expiry -> expiry <= ticks);
    }
    animateTick();
    if (active.isEmpty()) {
      ticksUntilPass = 0L;
      pressure = 0;
      return;
    }
    // Decrement first, so a pass set to N ticks runs exactly N pumps later (and a freshly activated
    // chunk, whose counter is still 0, is handled on this very pump instead of waiting a full interval).
    if (--ticksUntilPass > 0L) {
      return;
    }
    tick();
    ticksUntilPass = nextIntervalTicks();
  }

  /**
   * The interval until the next pass, from the last measured pressure: the baseline while calm, scaled
   * down in proportion to how far past {@code comfortableItems} the busiest chunk is, floored at
   * {@code minTicks}. 64 mergeable items → 40 ticks, 128 → 20, 256 → 10, 1280 → 2.
   */
  public long nextIntervalTicks() {
    if (pressure <= pacing.comfortableItems()) {
      return pacing.baselineTicks();
    }
    long scaled = Math.round(pacing.baselineTicks() * (pacing.comfortableItems() / (double) pressure));
    return Math.max(pacing.minTicks(), Math.min(pacing.baselineTicks(), scaled));
  }

  /** Mergeable items in the busiest active chunk as of the last pass. */
  public int pressure() {
    return pressure;
  }

  /** Whether the last pass found an item pile big enough to count as an emergency (for logs/HUD). */
  public boolean emergency() {
    return pressure >= pacing.emergencyItems();
  }

  /**
   * How the consolidation is animated. Merging used to be instantaneous, which read as items <em>blinking
   * out of existence</em>; instead each stack now flies to the pile it joins and merges on arrival, so a
   * player can see where their loot went.
   *
   * <p>It runs in TWO STAGES because a mode like Double Drops can put an absurd number of entities on the
   * ground per second, and animating all of them at once would cost more than the merge saves:</p>
   * <ol>
   *   <li><b>Stage 1 — cluster.</b> Items are bucketed into {@link #cellSize}-block cells and each cell's
   *       stacks fly to the fullest stack in that same cell. Short hops, and the count collapses fast.</li>
   *   <li><b>Stage 2 — consolidate.</b> The surviving cell heads — far fewer entities — fly to the single
   *       fullest stack in the chunk. Long hops, but only a handful of them are ever in flight.</li>
   * </ol>
   *
   * <p>Both stages share one {@link #maxConcurrent} budget. Under the flood ceiling the overflow simply
   * waits for a later pass (nothing pops); at or above it the overflow merges instantly, because at that
   * point the entity count itself is the problem.</p>
   *
   * @param enabled        animate at all; false restores the original instant merge
   * @param maxConcurrent  hard ceiling on item entities in flight at once, across both stages
   * @param cellSize       stage-1 cell size in blocks — how far apart two items can be and still be "close"
   * @param speed          travel speed in blocks per tick
   * @param arriveDistance distance at which a flying stack is absorbed by its target
   * @param maxTravelTicks give up and absorb anyway after this long (a stack stuck on terrain still lands)
   * @param floodThreshold item count in one chunk scan at which the overflow stops waiting and merges instantly
   */
  public record MergeAnimation(boolean enabled, int maxConcurrent, double cellSize, double speed,
      double arriveDistance, int maxTravelTicks, int floodThreshold) {
    public static final MergeAnimation DEFAULT =
        new MergeAnimation(true, 192, 4.0, 0.45, 0.8, 60, 400);

    public MergeAnimation {
      maxConcurrent = Math.max(0, maxConcurrent);
      cellSize = Math.max(1.0, cellSize);
      speed = Math.max(0.05, speed);
      arriveDistance = Math.max(0.1, arriveDistance);
      maxTravelTicks = Math.max(1, maxTravelTicks);
      floodThreshold = Math.max(1, floodThreshold);
    }
  }

  /** One item entity in flight toward the stack that will absorb it. */
  private static final class Pull {
    final ItemEntityHandle donor;
    ItemEntityHandle receiver;
    int ticksLeft;

    Pull(ItemEntityHandle donor, ItemEntityHandle receiver, int ticksLeft) {
      this.donor = donor;
      this.receiver = receiver;
      this.ticksLeft = ticksLeft;
    }
  }

  /** Replaces the animation settings (read from config at startup). */
  public void configure(MergeAnimation animation) {
    this.animation = animation == null ? MergeAnimation.DEFAULT : animation;
  }

  /** Item entities currently in flight (for HUD/debug). */
  public int animatingCount() {
    return pulls.size();
  }

  /** A chunk that recently received loot and is still being consolidated. */
  private static final class ActiveChunk {
    final WorldAdapter world;
    final WorldPosition center;
    double scanRadius; // tick-rescan half-extent; set from the activating caller's merge radius
    int idlePasses;
    // Mergeable count from the previous pass, and how many consecutive passes have failed to reduce it
    // WHILE items were in flight here — the signature of a stuck animation (see tick()).
    int lastMergeable = -1;
    int stalledPasses;

    ActiveChunk(WorldAdapter world, WorldPosition center, double scanRadius) {
      this.world = world;
      this.center = center;
      this.scanRadius = scanRadius;
    }
  }

  /**
   * Emit {@code amount} of {@code itemKey} at {@code at} as mergeable loot: pour into a nearby same-type
   * entity (up to {@link #MAX_AMOUNT}) or spawn a new over-cap entity, then mark the chunk active so
   * {@link #tick} keeps merging everything that lands there. {@code mergeRadius} bounds the immediate
   * nearby search.
   */
  public void add(WorldAdapter world, WorldPosition at, ItemKey itemKey, int amount, double mergeRadius) {
    if (world == null || at == null || itemKey == null || amount <= 0) {
      return;
    }
    double radius = Math.max(0.0, mergeRadius);
    // Unify the tick rescan with the immediate merge: this chunk is rescanned at the same radius the
    // drop merged at, so consolidation never reaches farther than chunk-merge-radius.
    activate(world, at, radius);
    int remaining = amount;
    for (ItemEntityHandle handle : world.nearbyItems(at, radius)) {
      if (remaining <= 0) {
        break;
      }
      if (!handle.valid() || !itemKey.equals(handle.itemKey())) {
        continue;
      }
      int room = MAX_AMOUNT - handle.amount();
      if (room <= 0) {
        continue;
      }
      int moved = Math.min(room, remaining);
      handle.setAmount(handle.amount() + moved);
      remaining -= moved;
    }
    while (remaining > 0) {
      int slice = Math.min(MAX_AMOUNT, remaining);
      ItemEntityHandle handle = world.spawnItemEntity(at, itemKey, slice);
      if (handle == null) {
        world.dropItem(at, new ItemStackData(itemKey, slice, Map.of())); // platform without handles
      }
      remaining -= slice;
    }
  }

  /** Mark the chunk containing {@code at} as active (or refresh it) so its items keep being merged. No
   * caller radius here (flood safety valve) -> the chunk-wide {@link #DEFAULT_SCAN_RADIUS}. */
  public void markActive(WorldAdapter world, WorldPosition at) {
    activate(world, at, DEFAULT_SCAN_RADIUS);
  }

  /** The active-set key of the chunk containing {@code at}. */
  private static String chunkKeyOf(WorldPosition at) {
    int chunkX = Math.floorDiv((int) Math.floor(at.coordinateX()), 16);
    int chunkZ = Math.floorDiv((int) Math.floor(at.coordinateZ()), 16);
    return at.worldName() + ">" + chunkX + "," + chunkZ;
  }

  private void activate(WorldAdapter world, WorldPosition at, double scanRadius) {
    if (world == null || at == null || at.worldName() == null) {
      return;
    }
    int chunkX = Math.floorDiv((int) Math.floor(at.coordinateX()), 16);
    int chunkZ = Math.floorDiv((int) Math.floor(at.coordinateZ()), 16);
    String key = at.worldName() + ">" + chunkX + "," + chunkZ;
    // Keep this area under observation even after it settles and retires, so the re-validation sweep can
    // wake it again the moment more items land instead of waiting for a full-blown flood.
    watched.put(key, ticks + watch.watchTicks());
    ActiveChunk existing = active.get(key);
    if (existing != null) {
      existing.idlePasses = 0;
      existing.scanRadius = scanRadius; // latest activating caller's radius wins
      return;
    }
    WorldPosition center = new WorldPosition(at.worldName(),
        (chunkX << 4) + 8.0, at.coordinateY(), (chunkZ << 4) + 8.0, 0.0F, 0.0F);
    active.put(key, new ActiveChunk(world, center, scanRadius));
  }

  /** Number of chunks currently being consolidated (for HUD/debug). */
  public int activeChunkCount() {
    return active.size();
  }

  /**
   * One consolidation pass over every active chunk; chunks idle for too long are dropped. Also records the
   * pressure — the largest number of MERGEABLE items any one chunk still holds — which is what paces the
   * next pass (see {@link MergePacing}).
   */
  public void tick() {
    if (active.isEmpty()) {
      pressure = 0;
      return;
    }
    int peak = 0;
    for (ActiveChunk chunk : List.copyOf(active.values())) {
      // A pile that has items in flight but has not shrunk for several passes is STUCK: something is
      // holding the animation (a wedged entity, a receiver that keeps moving, a platform that accepted a
      // velocity it never applied). Cancel those pull records and collapse the pile instantly this pass,
      // so consolidation always finishes even when the pretty path cannot.
      boolean stuck = chunk.stalledPasses >= watch.stalledPasses();
      if (stuck) {
        resetStuck(chunk);
      }
      int mergeable;
      try {
        mergeable = mergeChunk(chunk.world, chunk.center, chunk.scanRadius, stuck);
      } catch (RuntimeException exception) {
        // One bad chunk (unloaded / cross-region scan) must not abort consolidation everywhere else.
        // Treated as "no reading", NOT as "settled": a transient scan failure used to retire a chunk that
        // was still full of items, and nothing ever looked at it again.
        mergeable = -1;
      }
      // No progress WHILE this chunk has items flying is the stall signal. A chunk merely waiting its
      // turn for the shared animation budget has nothing in flight here, so it never counts as stuck.
      if (mergeable > 0 && chunk.lastMergeable >= 0 && mergeable >= chunk.lastMergeable
          && pullsIn(chunk) > 0) {
        chunk.stalledPasses++;
      } else {
        chunk.stalledPasses = 0;
      }
      chunk.lastMergeable = mergeable;
      // Retire on "is there still work here?", NOT on "did this pass move anything?". With the animated
      // merge a pass that plans nothing — because the concurrency budget is full, or because every
      // candidate is already in flight — looks exactly like a settled chunk. Retiring on that stopped the
      // planning while items were still lying on the ground, and they were then never grouped again.
      chunk.idlePasses = mergeable > 0 ? 0 : chunk.idlePasses + 1;
      peak = Math.max(peak, mergeable);
    }
    active.values().removeIf(chunk -> chunk.idlePasses >= MAX_IDLE_PASSES);
    pressure = peak;
  }

  /**
   * Merge every item entity in the chunk around {@code center}, grouped by type, into the fewest stacks.
   * Returns how many items in that radius are MERGEABLE — i.e. have at least one same-type partner there,
   * so the merger has real work to do on them. Zero means the chunk is settled (and can be retired); the
   * value doubles as the pacing signal, and because it ignores lone items it cannot raise a false alarm
   * over a crowd of unmergeable one-offs.
   */
  private int mergeChunk(WorldAdapter world, WorldPosition center, double scanRadius, boolean forceInstant) {
    List<ItemEntityHandle> items = world.nearbyItems(center, scanRadius);
    itemsScanned += items.size();
    Map<ItemKey, List<ItemEntityHandle>> byType = groupByType(items);
    int total = 0;
    for (List<ItemEntityHandle> group : byType.values()) {
      total += group.size();
    }
    // Past the flood ceiling the entity count itself is the emergency, so whatever does not fit in the
    // animation budget is merged on the spot: a brief pop beats keeping tens of thousands of entities.
    boolean flooded = total >= animation.floodThreshold();
    int mergeable = 0;
    for (List<ItemEntityHandle> group : byType.values()) {
      if (group.size() > 1) {
        int count = mergeableCount(group);
        mergeable += count;
        if (count > 0) {
          stacksMerged += mergeGroup(group, flooded, forceInstant) ? count : 0;
        }
      }
    }
    return mergeable;
  }

  /** Groups the live, non-empty handles by item type. */
  private static Map<ItemKey, List<ItemEntityHandle>> groupByType(List<ItemEntityHandle> items) {
    Map<ItemKey, List<ItemEntityHandle>> byType = new HashMap<>();
    for (ItemEntityHandle handle : items) {
      if (handle.valid() && handle.amount() > 0) {
        byType.computeIfAbsent(handle.itemKey(), key -> new ArrayList<>()).add(handle);
      }
    }
    return byType;
  }

  /** Total mergeable stacks across every same-type group (see {@link #mergeableCount}). */
  private static int mergeableTotal(Map<ItemKey, List<ItemEntityHandle>> byType) {
    int mergeable = 0;
    for (List<ItemEntityHandle> group : byType.values()) {
      if (group.size() > 1) {
        mergeable += mergeableCount(group);
      }
    }
    return mergeable;
  }

  /** Pulls currently in flight whose donor sits inside {@code chunk}'s scan area. */
  private int pullsIn(ActiveChunk chunk) {
    int count = 0;
    for (Pull pull : pulls.values()) {
      if (within(chunk, pull.donor)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Cancels every in-flight pull belonging to a stuck area and forgets their inbound bookkeeping, so the
   * next pass re-plans those items from scratch instead of waiting on flights that are never going to
   * land. This is the "reset the events and carry on" half of the stuck recovery; the caller pairs it
   * with a forced instant merge so the pile actually collapses.
   */
  private void resetStuck(ActiveChunk chunk) {
    List<UUID> cancelled = new ArrayList<>();
    for (Pull pull : pulls.values()) {
      if (within(chunk, pull.donor)) {
        cancelled.add(pull.donor.id());
        releaseInbound(pull.receiver);
      }
    }
    for (UUID id : cancelled) {
      pulls.remove(id);
    }
    stuckResets++;
    chunk.stalledPasses = 0;
    if (logger != null) {
      logger.warning("[stack-merge] stuck pile at " + describe(chunk.center)
          + " — " + chunk.lastMergeable + " mergeable items would not consolidate after "
          + watch.stalledPasses() + " passes; reset " + cancelled.size()
          + " in-flight stacks and merged them on the spot.");
    }
  }

  private static boolean within(ActiveChunk chunk, ItemEntityHandle handle) {
    WorldPosition at = handle.valid() ? handle.position() : null;
    if (at == null || at.worldName() == null || !at.worldName().equals(chunk.center.worldName())) {
      return false;
    }
    return Math.abs(at.coordinateX() - chunk.center.coordinateX()) <= chunk.scanRadius
        && Math.abs(at.coordinateZ() - chunk.center.coordinateZ()) <= chunk.scanRadius;
  }

  private static String describe(WorldPosition at) {
    return at.worldName() + " " + Math.round(at.coordinateX()) + "," + Math.round(at.coordinateY())
        + "," + Math.round(at.coordinateZ());
  }

  /**
   * How many stacks in a same-type group the merger could still do something with: those that are not
   * already full, and only when at least two of them are, since one lone partial stack has nothing to
   * pour into. Two 65,536-stacks sitting side by side are therefore NOT work — counting them would keep
   * their chunk active for ever and report a permanent false emergency.
   */
  private static int mergeableCount(List<ItemEntityHandle> group) {
    int withRoom = 0;
    for (ItemEntityHandle handle : group) {
      if (handle.amount() < MAX_AMOUNT) {
        withRoom++;
      }
    }
    return withRoom > 1 ? withRoom : 0;
  }

  // ===== animation ==============================================================================

  /**
   * Advances every in-flight stack by one tick: push it toward its target, absorb it on arrival (or when
   * it has been travelling too long), and drop the pull when either end is gone. Called every tick, and
   * returns immediately when nothing is flying — the planning pass ({@link #tick}) runs far less often.
   */
  public void animateTick() {
    if (pulls.isEmpty()) {
      return;
    }
    List<Pull> finished = new ArrayList<>();
    for (Pull pull : pulls.values()) {
      if (advance(pull)) {
        finished.add(pull);
      }
    }
    for (Pull pull : finished) {
      pulls.remove(pull.donor.id());
      releaseInbound(pull.receiver);
      pullsCompleted++;
    }
  }

  /** One tick of travel. Returns true when this pull is done (absorbed, or abandoned). */
  private boolean advance(Pull pull) {
    ItemEntityHandle donor = pull.donor;
    ItemEntityHandle receiver = pull.receiver;
    if (!donor.valid() || donor.amount() <= 0) {
      return true; // picked up or already gone — nothing to deliver
    }
    if (!receiver.valid()) {
      return true; // the target vanished; the donor simply stays put and is re-planned next pass
    }
    WorldPosition from = donor.position();
    WorldPosition to = receiver.position();
    if (from == null || to == null || from.worldName() == null
        || !from.worldName().equals(to.worldName())) {
      return true;
    }
    double deltaX = to.coordinateX() - from.coordinateX();
    double deltaY = to.coordinateY() - from.coordinateY();
    double deltaZ = to.coordinateZ() - from.coordinateZ();
    double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    if (distance <= animation.arriveDistance() || --pull.ticksLeft <= 0) {
      if (pull.ticksLeft <= 0) {
        pullsTimedOut++; // it never got there; the merge still happens, but the count exposes the pattern
      }
      absorb(donor, receiver);
      return true;
    }
    double scale = animation.speed() / distance;
    // A touch of lift so a stack crawling across the ground still visibly travels rather than snagging.
    if (!donor.setVelocity(deltaX * scale, deltaY * scale + 0.06, deltaZ * scale)) {
      absorb(donor, receiver); // platform cannot move items: merge now instead of waiting forever
      return true;
    }
    return false;
  }

  /**
   * Pours the donor into the receiver at the end of its flight. A receiver that filled up in the meantime
   * takes what it can and the remainder stays on the ground as its own stack, to be re-planned later.
   */
  private void absorb(ItemEntityHandle donor, ItemEntityHandle receiver) {
    // Hard guard against pouring a stack into ITSELF: that would read its own amount, add it to itself
    // and then write the remainder back — duplicating items. The planner's invariants make it
    // unreachable today, but retarget() rewires receivers at runtime, so this stays as a safety net.
    if (donor.id().equals(receiver.id()) || !donor.valid() || !receiver.valid()) {
      return;
    }
    int room = MAX_AMOUNT - receiver.amount();
    if (room <= 0) {
      return;
    }
    int moved = Math.min(room, donor.amount());
    if (moved <= 0) {
      return;
    }
    receiver.setAmount(receiver.amount() + moved);
    int left = donor.amount() - moved;
    donor.setAmount(left);
    if (left <= 0) {
      // Anything that was flying toward the donor now follows it into the receiver, so a stage-1 cluster
      // whose head reached the big pile does not strand its stragglers mid-air.
      retarget(donor.id(), receiver);
      donor.remove();
    }
  }

  /** Re-points every pull aimed at {@code goneId} to {@code newReceiver}. */
  private void retarget(UUID goneId, ItemEntityHandle newReceiver) {
    int moved = 0;
    for (Pull pull : pulls.values()) {
      if (pull.receiver.id().equals(goneId)) {
        pull.receiver = newReceiver;
        moved++;
      }
    }
    if (moved > 0) {
      inbound.remove(goneId);
      inbound.merge(newReceiver.id(), moved, Integer::sum);
    }
  }

  /**
   * Plans the two stages for one same-type group. Stage 1 pulls each cell's stacks into that cell's
   * fullest stack; stage 2 pulls the cell heads into the fullest stack of the whole group. Returns true
   * when anything was started or merged, so the chunk stays active while the animation plays out.
   */
  private boolean planGroup(List<ItemEntityHandle> group, boolean flooded) {
    List<ItemEntityHandle> free = new ArrayList<>();
    for (ItemEntityHandle handle : group) {
      if (!pulls.containsKey(handle.id())) {
        free.add(handle);
      }
    }
    if (free.size() < 2) {
      return !pulls.isEmpty(); // still busy delivering; keep the chunk alive
    }
    // ---- Stage 1: close items cluster into their local head -------------------------------------
    Map<String, List<ItemEntityHandle>> cells = new LinkedHashMap<>();
    for (ItemEntityHandle handle : free) {
      cells.computeIfAbsent(cellKey(handle), key -> new ArrayList<>()).add(handle);
    }
    boolean changed = false;
    List<ItemEntityHandle> heads = new ArrayList<>();
    for (List<ItemEntityHandle> cell : cells.values()) {
      cell.sort((a, b) -> Integer.compare(b.amount(), a.amount()));
      ItemEntityHandle head = cell.get(0);
      heads.add(head);
      for (int index = 1; index < cell.size(); index++) {
        changed |= startPull(cell.get(index), head, flooded);
      }
    }
    // ---- Stage 2: the (few) cell heads consolidate into one pile --------------------------------
    if (heads.size() > 1) {
      heads.sort((a, b) -> Integer.compare(b.amount(), a.amount()));
      ItemEntityHandle target = heads.get(0);
      for (int index = 1; index < heads.size(); index++) {
        ItemEntityHandle head = heads.get(index);
        // A head still gathering its own cell keeps gathering; it flies on once its cluster has landed.
        if (inbound.getOrDefault(head.id(), 0) == 0) {
          changed |= startPull(head, target, flooded);
        }
      }
    }
    return changed;
  }

  /** The stage-1 bucket a stack belongs to: its position rounded down to the cell grid. */
  private String cellKey(ItemEntityHandle handle) {
    WorldPosition position = handle.position();
    if (position == null) {
      return handle.id().toString(); // unknown position: its own cell, so it is never mis-clustered
    }
    double cell = animation.cellSize();
    return (long) Math.floor(position.coordinateX() / cell) + ","
        + (long) Math.floor(position.coordinateY() / cell) + ","
        + (long) Math.floor(position.coordinateZ() / cell);
  }

  /**
   * Sends {@code donor} flying toward {@code receiver}, or merges them instantly when the animation
   * budget is exhausted AND the chunk is flooded. Below the flood ceiling an over-budget pair is simply
   * left for a later pass, so nothing ever disappears without being seen to travel.
   */
  private boolean startPull(ItemEntityHandle donor, ItemEntityHandle receiver, boolean flooded) {
    if (donor.id().equals(receiver.id()) || !donor.valid() || !receiver.valid()) {
      return false;
    }
    if (receiver.amount() >= MAX_AMOUNT) {
      return false; // full: leave the donor as its own stack
    }
    if (pulls.size() >= animation.maxConcurrent()) {
      if (!flooded) {
        return false;
      }
      absorb(donor, receiver);
      return true;
    }
    pulls.put(donor.id(), new Pull(donor, receiver, animation.maxTravelTicks()));
    inbound.merge(receiver.id(), 1, Integer::sum);
    pullsStarted++;
    return true;
  }

  private void releaseInbound(ItemEntityHandle receiver) {
    inbound.computeIfPresent(receiver.id(), (id, count) -> count <= 1 ? null : count - 1);
  }

  /**
   * Collapse one same-type group, animating the stacks toward the pile they join when
   * {@link MergeAnimation#enabled()} — otherwise the instant merge below.
   */
  private boolean mergeGroup(List<ItemEntityHandle> group, boolean flooded, boolean forceInstant) {
    if (forceInstant || !animation.enabled()) {
      return mergeGroup(group);
    }
    return planGroup(group, flooded);
  }

  /**
   * Collapse one same-type group into the fewest entities: the fullest absorbs the emptiest until each
   * surviving stack is at {@link #MAX_AMOUNT} or nothing is left to give. Drained entities are removed.
   */
  private boolean mergeGroup(List<ItemEntityHandle> group) {
    group.sort((a, b) -> Integer.compare(b.amount(), a.amount()));
    boolean changed = false;
    int head = 0;
    int tail = group.size() - 1;
    while (head < tail) {
      ItemEntityHandle receiver = group.get(head);
      ItemEntityHandle donor = group.get(tail);
      int room = MAX_AMOUNT - receiver.amount();
      if (room <= 0) {
        head++;
        continue;
      }
      if (donor.amount() <= 0) {
        tail--;
        continue;
      }
      int moved = Math.min(room, donor.amount());
      receiver.setAmount(receiver.amount() + moved);
      int left = donor.amount() - moved;
      donor.setAmount(left);
      changed = true;
      if (left <= 0) {
        donor.remove();
        tail--;
      }
      if (receiver.amount() >= MAX_AMOUNT) {
        head++;
      }
    }
    return changed;
  }
}
