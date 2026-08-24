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
 * Guards the dynamic pass pacing: the 40-tick baseline holds while things are calm, an item pile
 * accelerates the passes toward the floor, and — crucially — a busy-looking area that the merger cannot
 * actually do anything with raises NO alarm.
 */
class StackMergePacingTest {
  private static final String WORLD = "exp";
  private static final ItemKey STONE = ItemKey.minecraft("stone");

  @Test
  void aCalmServerKeepsTheBaselineAndCostsNothing() {
    StackMergeService service = new StackMergeService();
    // No active chunk at all: the pump is a no-op and the interval stays at the baseline.
    service.pump();
    assertEquals(0, service.pressure());
    assertFalse(service.emergency());
    assertEquals(40L, service.nextIntervalTicks());
  }

  @Test
  void aSmallPileStaysAtTheBaseline() {
    FakeWorld world = new FakeWorld();
    spawnMergeable(world, 12);
    StackMergeService service = active(world);
    service.tick();

    assertTrue(service.pressure() <= 64, "pressure: " + service.pressure());
    assertEquals(40L, service.nextIntervalTicks(), "a handful of items needs no acceleration");
    assertFalse(service.emergency());
  }

  @Test
  void pressureAcceleratesThePassesProportionally() {
    // 256 mergeable items against a comfortable level of 64 = 4x the load, so 40 ticks -> 10.
    FakeWorld world = new FakeWorld();
    spawnMergeable(world, 256);
    StackMergeService service = active(world);
    service.tick();

    assertEquals(256, service.pressure());
    assertEquals(10L, service.nextIntervalTicks());
    assertTrue(service.emergency(), "256 mergeable items in one radius is the emergency threshold");
  }

  @Test
  void theIntervalIsFlooredSoAnExtremeFloodCannotBusyLoop() {
    FakeWorld world = new FakeWorld();
    spawnMergeable(world, 5000);
    StackMergeService service = active(world);
    service.tick();

    assertEquals(2L, service.nextIntervalTicks(), "the floor is min-period-ticks, never 0");
  }

  @Test
  void scatteredOneOffItemsRaiseNoFalseAlarm() {
    // 300 items in the radius, but every one is a DIFFERENT type: the merger can do nothing with them,
    // so they must not accelerate anything. This is the false-alarm case a naive entity count would hit.
    FakeWorld world = new FakeWorld();
    for (int index = 0; index < 300; index++) {
      world.spawn(ItemKey.minecraft("item_" + index), 1, index % 20 * 0.5, index / 20 * 0.5);
    }
    StackMergeService service = active(world);
    service.tick();

    assertEquals(0, service.pressure(), "unmergeable items are not pressure");
    assertEquals(40L, service.nextIntervalTicks());
    assertFalse(service.emergency());
  }

  @Test
  void itemsOutsideTheMergeRadiusAreNotCounted() {
    // The merger only ever works within its own radius, so pressure must be measured there too — a pile
    // two chunks away is somebody else's pass, not this one's emergency.
    FakeWorld world = new FakeWorld();
    spawnMergeable(world, 8);
    for (int index = 0; index < 400; index++) {
      world.spawn(STONE, 1, 200.0 + index * 0.1, 200.0);
    }
    StackMergeService service = active(world);
    service.tick();

    assertTrue(service.pressure() <= 8, "far-away items must not count: " + service.pressure());
    assertEquals(40L, service.nextIntervalTicks());
  }

  @Test
  void thePumpRunsAPassOnlyWhenTheDynamicIntervalElapses() {
    FakeWorld world = new FakeWorld();
    spawnMergeable(world, 6);
    StackMergeService service = active(world);

    // First pump runs a pass immediately (a fresh pile is not made to wait), then backs off to baseline.
    service.pump();
    assertTrue(world.scans > 0, "the first pump should run a pass");

    // The next 39 pumps must NOT scan again — nothing is touched until the interval elapses.
    world.scans = 0;
    for (int tick = 0; tick < 39; tick++) {
      service.pump();
    }
    assertEquals(0, world.scans, "no rescan before the interval elapses");
    service.pump();
    assertTrue(world.scans > 0, "…and a pass exactly when it does");
  }

  @Test
  void anEmergencyPumpsFarMoreOftenThanTheBaseline() {
    FakeWorld world = new FakeWorld();
    StackMergeService service = active(world);
    service.configure(new StackMergeService.MergeAnimation(false, 0, 4.0, 0.45, 0.8, 60, 400));
    spawnMergeable(world, 512);

    service.pump(); // the first pass measures the flood
    assertTrue(service.emergency());
    long emergencyInterval = service.nextIntervalTicks();
    assertTrue(emergencyInterval < 40L && emergencyInterval >= 2L, "interval: " + emergencyInterval);

    // A SUSTAINED flood (items keep landing) must keep the passes accelerated: over 40 ticks that is
    // many passes, where the old fixed schedule would have run exactly one.
    world.scans = 0;
    for (int tick = 0; tick < 40; tick++) {
      spawnMergeable(world, 64);
      service.pump();
    }
    assertTrue(world.scans > 4,
        "a sustained flood must run far more than the one baseline pass: " + world.scans);
  }

  @Test
  void aResolvedFloodReturnsToTheBaseline() {
    FakeWorld world = new FakeWorld();
    StackMergeService service = active(world);
    service.configure(new StackMergeService.MergeAnimation(false, 0, 4.0, 0.45, 0.8, 60, 400));
    spawnMergeable(world, 512);

    service.pump();
    assertTrue(service.emergency(), "the flood is detected");
    // One instant pass collapses it; the next pass finds nothing left to merge and the pacing relaxes.
    service.tick();
    assertFalse(service.emergency(), "pressure must fall once the pile is consolidated");
    assertEquals(40L, service.nextIntervalTicks(), "…and the interval returns to the baseline");
  }

  private static StackMergeService active(FakeWorld world) {
    StackMergeService service = new StackMergeService();
    service.markActive(world, new WorldPosition(WORLD, 0.5, 64.0, 0.5, 0f, 0f));
    return service;
  }

  /** {@code count} same-type items inside the merge radius, i.e. genuinely mergeable work. */
  private static void spawnMergeable(FakeWorld world, int count) {
    for (int index = 0; index < count; index++) {
      // Wrapped so every item lands inside the merge radius no matter how many there are — pressure is
      // only ever measured there, so a fixture that spills outside would measure something else.
      world.spawn(STONE, 1, (index % 24) * 0.5, ((index / 24) % 24) * 0.5);
    }
  }

  private static final class FakeWorld implements WorldAdapter {
    private final List<FakeItem> items = new ArrayList<>();
    int scans;

    void spawn(ItemKey key, int amount, double blockX, double blockZ) {
      items.add(new FakeItem(key, amount, blockX, blockZ));
    }

    List<FakeItem> live() {
      return items.stream().filter(item -> item.alive).toList();
    }

    @Override
    public List<ItemEntityHandle> nearbyItems(WorldPosition position, double radius) {
      scans++;
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
    @Override public WorldPosition spawnPosition() { return new WorldPosition(WORLD, 0, 64, 0, 0f, 0f); }
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
    private final double positionX;
    private final double positionZ;
    private int amount;
    private boolean alive = true;

    FakeItem(ItemKey key, int amount, double positionX, double positionZ) {
      this.key = key;
      this.amount = amount;
      this.positionX = positionX;
      this.positionZ = positionZ;
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
      return true; // pretend the platform can animate; the flight itself is covered elsewhere
    }
  }
}
