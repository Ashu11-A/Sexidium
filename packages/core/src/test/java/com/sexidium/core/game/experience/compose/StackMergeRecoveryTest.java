package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.ItemEntityHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the ways consolidation is stopped from silently giving up: the periodic re-validation around
 * players, the stuck-pile recovery, and the counters that make a non-merging hotspot visible instead of
 * a guess.
 */
class StackMergeRecoveryTest {
  private static final String W = "exp";
  private static final ItemKey STONE = ItemKey.minecraft("stone");
  private static final WorldPosition HERE = new WorldPosition(W, 8.5, 64.0, 8.5, 0f, 0f);

  // ----- periodic re-validation -----------------------------------------------------------------

  @Test
  void anAreaThatSettledIsStillWatchedAndWakesOnANewPile() {
    FakeWorld world = new FakeWorld();
    StackMergeService service = instant();
    spawn(world, 20);
    service.markActive(world, HERE);

    // Consolidate it away, then let it retire completely.
    for (int pass = 0; pass < 6; pass++) {
      service.tick();
    }
    assertEquals(1, world.live().size(), "the pile consolidated");
    assertEquals(0, service.activeChunkCount(), "…and the chunk was released");

    // A NEW, small pile lands. Far below the flood ceiling — the old code would never have looked again.
    spawn(world, 6);
    assertTrue(service.validateNear(world, HERE, 16.0) > 0, "re-validation must see mergeable work");
    assertEquals(1, service.activeChunkCount(), "a watched area wakes on a small pile");
    service.tick();
    assertTrue(world.live().size() < 7, "…and consolidation resumes: " + world.live().size());
  }

  @Test
  void aQuietAreaIsLeftAloneUntilItFloods() {
    // Nothing has ever been consolidated here, so ordinary survival play must not start merging: a brand
    // new area still has to cross the flood ceiling.
    FakeWorld world = new FakeWorld();
    StackMergeService service = instant();
    spawn(world, 30);

    service.validateNear(world, HERE, 16.0);
    assertEquals(0, service.activeChunkCount(), "a small pile in an untouched area is not our business");

    spawn(world, 300);
    service.validateNear(world, HERE, 16.0);
    assertEquals(1, service.activeChunkCount(), "…but a flood is");
  }

  @Test
  void theWatchWindowExpires() {
    FakeWorld world = new FakeWorld();
    StackMergeService service = instant();
    service.configure(new StackMergeService.MergeWatch(40L, 2, 3, 256));
    spawn(world, 10);
    service.markActive(world, HERE);
    for (int pass = 0; pass < 6; pass++) {
      service.tick();
    }
    // Let the watch lapse (pump drives the clock), then drop a small pile: it must NOT wake.
    for (int tick = 0; tick < 60; tick++) {
      service.pump();
    }
    spawn(world, 6);
    service.validateNear(world, HERE, 16.0);
    assertEquals(0, service.activeChunkCount(), "the watch window is bounded");
  }

  @Test
  void revalidationSurvivesAWorldThatThrows() {
    // A scan across an unloaded/foreign region must not break the sweep for the other players.
    FakeWorld world = new FakeWorld();
    world.throwOnScan = true;
    StackMergeService service = instant();
    assertEquals(0, service.validateNear(world, HERE, 16.0));
    assertEquals(0, service.activeChunkCount());
  }

  // ----- stuck recovery -------------------------------------------------------------------------

  @Test
  void aStuckPileIsResetAndMergedAnyway() {
    // Items accept a velocity but never actually move (wedged, or a platform that silently ignores it),
    // so the flights never land and the pile would sit there for ever.
    FakeWorld world = new FakeWorld();
    world.frozen = true;
    StackMergeService service = new StackMergeService();
    service.configure(new StackMergeService.MergeAnimation(true, 64, 4.0, 0.45, 0.8, 100_000, 100_000));
    service.configure(new StackMergeService.MergeWatch(1200L, 2, 3, 256));
    spawn(world, 24);
    service.markActive(world, HERE);

    // The first pass plans flights; each later pass sees the same pile with items still in flight, which
    // is the stall signal. Once stalled-passes is reached the next pass resets and force-merges.
    for (int pass = 0; pass < 6; pass++) {
      service.tick();
    }
    StackMergeService.Diagnostics diagnostics = service.diagnostics();
    assertTrue(diagnostics.stuckResets() > 0, "a stalled pile must be detected");
    assertEquals(0, diagnostics.inFlight(), "the stuck flights must be cancelled");
    assertEquals(1, world.live().size(), "…and the pile consolidated anyway");
    assertEquals(24, world.total(), "without losing an item");
  }

  @Test
  void aPileWaitingOnTheBudgetIsNotMistakenForStuck() {
    // Nothing is in flight HERE (the budget is spent elsewhere), so this pile is merely waiting its turn.
    // Waiting is not stuck, and must not trigger a reset.
    FakeWorld world = new FakeWorld();
    StackMergeService service = new StackMergeService();
    service.configure(new StackMergeService.MergeAnimation(true, 0, 4.0, 0.45, 0.8, 60, 100_000));
    spawn(world, 20);
    service.markActive(world, HERE);
    for (int pass = 0; pass < 8; pass++) {
      service.tick();
    }
    assertEquals(0, service.diagnostics().stuckResets(), "waiting on the budget is not a stall");
    assertEquals(20, world.live().size(), "…and nothing was force-merged behind its back");
  }

  @Test
  void aTransientScanFailureDoesNotRetireAPileForGood() {
    FakeWorld world = new FakeWorld();
    StackMergeService service = instant();
    spawn(world, 12);
    service.markActive(world, HERE);

    world.throwOnScan = true;
    service.tick();
    world.throwOnScan = false;
    // The chunk survived the blip, so the very next pass consolidates normally.
    service.tick();
    assertEquals(1, world.live().size(), "a transient failure must not lose the pile");
  }

  // ----- type agnosticism -----------------------------------------------------------------------

  @Test
  void everyItemTypeBundlesTheSameWay() {
    // The merger reads item types straight from the platform registry, so nothing is special-cased. Wood
    // must behave exactly like an apple — this pins that down across blocks, foods, tools and ores.
    List<String> types = List.of("oak_log", "birch_log", "oak_planks", "stripped_spruce_log",
        "apple", "stick", "dirt", "cobblestone", "raw_iron", "diamond_pickaxe", "water_bucket", "wheat_seeds");
    for (String type : types) {
      FakeWorld world = new FakeWorld();
      StackMergeService service = instant();
      for (int index = 0; index < 9; index++) {
        world.spawn(ItemKey.minecraft(type), 3, 6.0 + index * 0.4, 6.0);
      }
      service.markActive(world, HERE);
      service.tick();
      assertEquals(1, world.live().size(), type + " must consolidate like every other type");
      assertEquals(27, world.total(), type + " must keep every item");
    }
  }

  @Test
  void differentWoodTypesStayApartButEachKindStillBundles() {
    // Distinct types must NOT be forced together — mixing a jungle log into an oak stack would be wrong.
    // Each kind still collapses to a single stack of its own.
    FakeWorld world = new FakeWorld();
    StackMergeService service = instant();
    for (int index = 0; index < 5; index++) {
      world.spawn(ItemKey.minecraft("oak_log"), 2, 6.0 + index * 0.3, 6.0);
      world.spawn(ItemKey.minecraft("jungle_log"), 2, 7.0 + index * 0.3, 7.0);
      world.spawn(ItemKey.minecraft("oak_planks"), 2, 8.0 + index * 0.3, 8.0);
    }
    service.tick(); // no active chunk yet
    service.markActive(world, HERE);
    service.tick();

    assertEquals(3, world.live().size(), "one stack per distinct type");
    assertEquals(30, world.total());
  }

  @Test
  void aSmallPileInsideAMatchIsBundledWithoutWaitingForAFlood() {
    // The real "logs don't stack" report: a handful of items never crossed the flood ceiling, while a
    // payout that showered thousands did. Inside a match, ANY mergeable work is enough.
    FakeWorld world = new FakeWorld();
    StackMergeService service = instant();
    for (int index = 0; index < 8; index++) {
      world.spawn(ItemKey.minecraft("oak_log"), 1, 6.0 + index * 0.4, 6.0);
    }
    // Outside a match: untouched area, small pile → left alone, exactly as before.
    service.validateNear(world, HERE, 16.0, false);
    assertEquals(0, service.activeChunkCount());

    // Inside a match: bundling is the point.
    service.validateNear(world, HERE, 16.0, true);
    assertEquals(1, service.activeChunkCount(), "a match world bundles whatever is there");
    service.tick();
    assertEquals(1, world.live().size(), "the logs bundled: " + world.live().size());
    assertEquals(8, world.total());
  }

  // ----- counters -------------------------------------------------------------------------------

  @Test
  void countersExposeWhatTheMergerIsDoing() {
    FakeWorld world = new FakeWorld();
    StackMergeService service = instant();
    spawn(world, 16);
    service.markActive(world, HERE);
    service.validateNear(world, HERE, 16.0);
    service.tick();

    StackMergeService.Diagnostics diagnostics = service.diagnostics();
    assertTrue(diagnostics.itemsScanned() >= 16, "entities scanned: " + diagnostics.itemsScanned());
    assertTrue(diagnostics.stacksMerged() > 0, "stacks merged: " + diagnostics.stacksMerged());
    assertTrue(diagnostics.revalidations() > 0);
    assertTrue(diagnostics.watchedAreas() > 0, "an active area is watched");
    assertFalse(diagnostics.activeChunks() < 0);
  }

  @Test
  void aStackIsNeverPouredIntoItself() {
    // Duplication guard: same entity as donor and receiver must be a no-op, not a doubling.
    FakeWorld world = new FakeWorld();
    FakeItem item = world.spawn(STONE, 100, 8.5, 8.5);
    StackMergeService service = instant();
    service.markActive(world, HERE);
    service.tick();
    assertEquals(100, item.amount, "a lone stack must be left exactly as it was");
    assertEquals(100, world.total());
  }

  private static StackMergeService instant() {
    StackMergeService service = new StackMergeService();
    // Animation off: these tests are about the recovery/validation logic, not the flight.
    service.configure(new StackMergeService.MergeAnimation(false, 192, 4.0, 0.45, 0.8, 60, 400));
    return service;
  }

  private static void spawn(FakeWorld world, int count) {
    for (int index = 0; index < count; index++) {
      world.spawn(STONE, 1, (index % 12) * 0.5 + 4.0, ((index / 12) % 12) * 0.5 + 4.0);
    }
  }

  private static final class FakeWorld implements WorldAdapter {
    private final List<FakeItem> items = new ArrayList<>();
    boolean frozen;      // accepts velocity but never moves (the stuck case)
    boolean throwOnScan; // an unloaded / foreign region

    FakeItem spawn(ItemKey key, int amount, double blockX, double blockZ) {
      FakeItem item = new FakeItem(key, amount, blockX, blockZ, this);
      items.add(item);
      return item;
    }

    List<FakeItem> live() {
      return items.stream().filter(item -> item.alive).toList();
    }

    int total() {
      return live().stream().mapToInt(item -> item.amount).sum();
    }

    @Override
    public List<ItemEntityHandle> nearbyItems(WorldPosition position, double radius) {
      if (throwOnScan) {
        throw new IllegalStateException("chunk not loaded");
      }
      List<ItemEntityHandle> found = new ArrayList<>();
      for (FakeItem item : live()) {
        if (Math.abs(item.positionX - position.coordinateX()) <= radius
            && Math.abs(item.positionZ - position.coordinateZ()) <= radius) {
          found.add(item);
        }
      }
      return found;
    }

    @Override public String name() { return W; }
    @Override public WorldPosition spawnPosition() { return HERE; }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }

  private static final class FakeItem implements ItemEntityHandle {
    private final UUID id = UUID.randomUUID();
    private final ItemKey key;
    private final FakeWorld world;
    private int amount;
    private double positionX;
    private double positionZ;
    private boolean alive = true;

    FakeItem(ItemKey key, int amount, double positionX, double positionZ, FakeWorld world) {
      this.key = key;
      this.amount = amount;
      this.positionX = positionX;
      this.positionZ = positionZ;
      this.world = world;
    }

    @Override public UUID id() { return id; }
    @Override public boolean valid() { return alive; }
    @Override public ItemKey itemKey() { return key; }
    @Override public int amount() { return amount; }
    @Override public void setAmount(int amount) { this.amount = amount; }
    @Override public void remove() { alive = false; }
    @Override public String worldName() { return W; }

    @Override
    public WorldPosition position() {
      return new WorldPosition(W, positionX, 64.0, positionZ, 0f, 0f);
    }

    @Override
    public boolean setVelocity(double velocityX, double velocityY, double velocityZ) {
      if (world.frozen) {
        return true; // accepted, but the entity never actually moves
      }
      positionX += velocityX;
      positionZ += velocityZ;
      return true;
    }
  }
}
