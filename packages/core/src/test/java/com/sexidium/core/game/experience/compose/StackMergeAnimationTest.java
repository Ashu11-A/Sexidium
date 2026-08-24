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
 * Guards the two-stage merge animation: stacks must be seen TRAVELLING to the pile they join instead of
 * blinking out of existence, the number of entities animated at once must stay bounded, and no item may
 * ever be lost on the way.
 */
class StackMergeAnimationTest {
  private static final ItemKey STONE = ItemKey.minecraft("stone");
  private static final String WORLD = "exp";

  @Test
  void stageOnePullsCloseItemsIntoTheirLocalHeadInsteadOfMergingInstantly() {
    FakeWorld world = new FakeWorld();
    // Two tight clusters in separate stage-1 cells (cell size 4): one around x=0, one around x=16.
    for (int index = 0; index < 4; index++) {
      world.spawn(STONE, index == 0 ? 10 : 1, 0.5 + index * 0.5, 0.5);
      world.spawn(STONE, index == 0 ? 10 : 1, 16.5 + index * 0.5, 0.5);
    }
    StackMergeService service = new StackMergeService();
    service.markActive(world, at(0, 0));
    service.tick();

    // Nothing has merged yet — the stacks are in flight, and every item is still on the ground.
    assertEquals(8, world.live().size(), "stage 1 must not delete anything on the planning pass");
    assertTrue(service.animatingCount() > 0, "items should be travelling");
    assertEquals(26, world.totalItems());
    // …and one flight tick later they are actually being pushed, not teleported. (tick() only PLANS;
    // animateTick() is what moves them, which is why it runs on its own every-tick timer.)
    service.animateTick();
    assertTrue(world.live().stream().anyMatch(item -> item.pushed), "a pulled stack must be given motion");
  }

  @Test
  void everyItemArrivesAndNothingIsLost() {
    FakeWorld world = new FakeWorld();
    for (int index = 0; index < 6; index++) {
      world.spawn(STONE, 5, 0.5 + index * 0.6, 0.5);
      world.spawn(STONE, 5, 16.5 + index * 0.6, 0.5);
    }
    int expected = world.totalItems();

    StackMergeService service = new StackMergeService();
    service.markActive(world, at(0, 0));
    // Alternate planning passes with the per-tick flight until it settles.
    for (int pass = 0; pass < 12; pass++) {
      service.tick();
      for (int flightTick = 0; flightTick < 80; flightTick++) {
        service.animateTick();
        world.step();
      }
    }
    assertEquals(expected, world.totalItems(), "items must never be lost in transit");
    assertEquals(1, world.live().size(), "everything should end up in one pile");
    assertEquals(0, service.animatingCount());
  }

  @Test
  void stageTwoOnlyMovesTheClusterHeads() {
    FakeWorld world = new FakeWorld();
    // Three separate stage-1 cells (cell size 4) of three items each, all inside the scan radius.
    for (int cell = 0; cell < 3; cell++) {
      for (int index = 0; index < 3; index++) {
        world.spawn(STONE, 1, cell * 8 + 0.5 + index * 0.4, 0.5);
      }
    }
    StackMergeService service = new StackMergeService();
    service.markActive(world, at(0, 0));
    service.tick();

    // Stage 1 pulls 2 per cell (6). Stage 2 does NOT move the heads yet — each is still gathering.
    assertEquals(6, service.animatingCount(), "only the non-head stacks fly in the first pass");

    // Once the clusters have landed, the (few) heads travel — that is stage 2.
    for (int flightTick = 0; flightTick < 200; flightTick++) {
      service.animateTick();
      world.step();
    }
    assertEquals(3, world.live().size(), "one head per cell survives stage 1");
    service.tick();
    assertTrue(service.animatingCount() > 0 && service.animatingCount() <= 2,
        "stage 2 moves at most the heads, not every original item: " + service.animatingCount());
  }

  @Test
  void theNumberOfAnimatedEntitiesIsBounded() {
    FakeWorld world = new FakeWorld();
    for (int index = 0; index < 400; index++) {
      world.spawn(STONE, 1, (index % 20) * 0.9 + 0.5, (index / 20) * 0.9 + 0.5);
    }
    StackMergeService service = new StackMergeService();
    service.configure(new StackMergeService.MergeAnimation(true, 24, 4.0, 0.45, 0.8, 60, 100_000));
    service.markActive(world, at(0, 0));
    service.tick();

    assertTrue(service.animatingCount() <= 24,
        "the budget is the cost knob: " + service.animatingCount());
    // Under the flood ceiling the overflow WAITS rather than popping, so nothing has vanished.
    assertEquals(400, world.live().size());
  }

  @Test
  void aFloodedChunkMergesTheOverflowInstantlyRatherThanKeepingTheEntities() {
    FakeWorld world = new FakeWorld();
    for (int index = 0; index < 300; index++) {
      world.spawn(STONE, 1, (index % 20) * 0.9 + 0.5, (index / 20) * 0.9 + 0.5);
    }
    StackMergeService service = new StackMergeService();
    // Budget 8, flood ceiling 50 — the 300 items are far past it.
    service.configure(new StackMergeService.MergeAnimation(true, 8, 4.0, 0.45, 0.8, 60, 50));
    service.markActive(world, at(0, 0));
    service.tick();

    assertTrue(world.live().size() < 300, "past the flood ceiling the overflow must collapse now");
    assertEquals(300, world.totalItems(), "…but still without losing a single item");
  }

  @Test
  void disablingTheAnimationRestoresTheInstantMerge() {
    FakeWorld world = new FakeWorld();
    for (int index = 0; index < 10; index++) {
      world.spawn(STONE, 3, index * 0.5 + 0.5, 0.5);
    }
    StackMergeService service = new StackMergeService();
    service.configure(new StackMergeService.MergeAnimation(false, 192, 4.0, 0.45, 0.8, 60, 400));
    service.markActive(world, at(0, 0));
    service.tick();

    assertEquals(1, world.live().size());
    assertEquals(30, world.totalItems());
    assertEquals(0, service.animatingCount());
  }

  @Test
  void aPlatformThatCannotMoveItemsFallsBackToMergingOnTheSpot() {
    FakeWorld world = new FakeWorld();
    world.motionSupported = false;
    for (int index = 0; index < 5; index++) {
      world.spawn(STONE, 2, index * 0.5 + 0.5, 0.5);
    }
    StackMergeService service = new StackMergeService();
    service.markActive(world, at(0, 0));
    service.tick();
    service.animateTick(); // the first flight tick discovers motion is unsupported

    assertEquals(1, world.live().size(), "no animation available: merge rather than hang forever");
    assertEquals(10, world.totalItems());
  }

  private static WorldPosition at(double blockX, double blockZ) {
    return new WorldPosition(WORLD, blockX, 64.0, blockZ, 0f, 0f);
  }

  /** A world of item entities that can be pushed around, so a whole flight can be simulated. */
  private static final class FakeWorld implements WorldAdapter {
    private final List<FakeItem> items = new ArrayList<>();
    boolean motionSupported = true;

    FakeItem spawn(ItemKey key, int amount, double blockX, double blockZ) {
      FakeItem item = new FakeItem(key, amount, blockX, blockZ, this);
      items.add(item);
      return item;
    }

    List<FakeItem> live() {
      return items.stream().filter(item -> item.alive).toList();
    }

    int totalItems() {
      return live().stream().mapToInt(item -> item.amount).sum();
    }

    /** Applies one tick of the velocity each pulled item was given. */
    void step() {
      for (FakeItem item : live()) {
        item.positionX += item.velocityX;
        item.positionZ += item.velocityZ;
        item.velocityX = 0;
        item.velocityZ = 0;
      }
    }

    @Override
    public List<ItemEntityHandle> nearbyItems(WorldPosition position, double radius) {
      List<ItemEntityHandle> found = new ArrayList<>();
      for (FakeItem item : live()) {
        if (Math.abs(item.positionX - position.coordinateX()) <= radius
            && Math.abs(item.positionZ - position.coordinateZ()) <= radius) {
          found.add(item);
        }
      }
      return found;
    }

    @Override public String name() { return WORLD; }
    @Override public WorldPosition spawnPosition() { return at(0, 0); }
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
    private double velocityX;
    private double velocityZ;
    private boolean alive = true;
    private boolean pushed;

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
    @Override public String worldName() { return WORLD; }

    @Override
    public WorldPosition position() {
      return new WorldPosition(WORLD, positionX, 64.0, positionZ, 0f, 0f);
    }

    @Override
    public boolean setVelocity(double velocityX, double velocityY, double velocityZ) {
      if (!world.motionSupported) {
        return false;
      }
      this.velocityX = velocityX;
      this.velocityZ = velocityZ;
      this.pushed = true;
      return true;
    }
  }

  @Test
  void anEmptyServiceDoesNothing() {
    StackMergeService service = new StackMergeService();
    service.animateTick(); // must not throw with nothing in flight
    assertEquals(0, service.animatingCount());
    assertFalse(service.activeChunkCount() > 0);
  }
}
