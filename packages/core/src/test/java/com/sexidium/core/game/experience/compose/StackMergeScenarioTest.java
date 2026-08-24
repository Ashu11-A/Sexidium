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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the day the merger "stopped grouping nearby items". The animated merge made a
 * planning pass that plans NOTHING — budget full, or everything already in flight — indistinguishable
 * from a settled chunk, and the retirement rule of the time ("did anything move this pass?") wrote those
 * chunks off after three passes. Planning stopped while items were still on the ground, so they were
 * never grouped again. The rule now asks whether there is still work in the radius instead.
 */
class StackMergeScenarioTest {
  private static final String W = "exp";
  private static final ItemKey STONE = ItemKey.minecraft("stone");

  @Test
  void aPourAtOneSpotGroups() {
    FakeWorld world = new FakeWorld();
    for (int i = 0; i < 300; i++) {
      world.spawn(STONE, 64, 8.5, 8.5); // exactly what DropPipeline's pour does
    }
    StackMergeService service = new StackMergeService();
    service.markActive(world, new WorldPosition(W, 8.5, 64, 8.5, 0f, 0f));
    for (int tick = 0; tick < 400; tick++) {
      service.pump();
    }
    System.out.println("[scenario] live=" + world.live().size() + " pressure=" + service.pressure()
        + " animating=" + service.animatingCount() + " total=" + world.total());
    assertTrue(world.live().size() < 300, "expected grouping, still " + world.live().size());
  }

  @Test
  void aBudgetStarvedChunkKeepsItsWorkInsteadOfRetiring() {
    // TWO piles far apart, so they are separate active chunks sharing ONE animation budget. The first
    // chunk planned soaks up every slot; the second must NOT be written off as "settled" just because it
    // could not start a pull this pass — that is what silently stopped items from ever being grouped.
    FakeWorld world = new FakeWorld();
    for (int i = 0; i < 60; i++) {
      world.spawn(STONE, 1, 8.5, 8.5);
      world.spawn(STONE, 1, 200.5, 200.5);
    }
    StackMergeService service = new StackMergeService();
    service.configure(new StackMergeService.MergeAnimation(true, 4, 4.0, 0.45, 0.8, 60, 100_000));
    service.markActive(world, new WorldPosition(W, 8.5, 64, 8.5, 0f, 0f));
    service.markActive(world, new WorldPosition(W, 200.5, 64, 200.5, 0f, 0f));

    // Plan repeatedly WITHOUT ever advancing the flight, so the budget stays permanently exhausted.
    for (int pass = 0; pass < 6; pass++) {
      service.tick();
    }
    assertEquals(2, service.activeChunkCount(),
        "a chunk that still has same-type items must stay active even when the budget is full");
  }

  @Test
  void aSettledChunkStillRetires() {
    // The opposite failure mode: keeping a chunk alive for ever. Two FULL stacks cannot be merged into
    // each other, so they are not work and their chunk must be released.
    FakeWorld world = new FakeWorld();
    world.spawn(STONE, StackMergeService.MAX_AMOUNT, 8.5, 8.5);
    world.spawn(STONE, StackMergeService.MAX_AMOUNT, 9.5, 8.5);
    StackMergeService service = new StackMergeService();
    service.markActive(world, new WorldPosition(W, 8.5, 64, 8.5, 0f, 0f));
    for (int pass = 0; pass < 4; pass++) {
      service.tick();
    }
    assertEquals(0, service.activeChunkCount(), "full stacks are not work — the chunk must be released");
    assertEquals(0, service.pressure(), "…and they must not report a permanent emergency either");
  }

  private static final class FakeWorld implements WorldAdapter {
    final List<FakeItem> items = new ArrayList<>();
    void spawn(ItemKey k, int a, double x, double z) { items.add(new FakeItem(k, a, x, z)); }
    List<FakeItem> live() { return items.stream().filter(i -> i.alive).toList(); }
    int total() { return live().stream().mapToInt(i -> i.amount).sum(); }
    @Override public List<ItemEntityHandle> nearbyItems(WorldPosition p, double r) {
      List<ItemEntityHandle> out = new ArrayList<>();
      for (FakeItem i : live()) {
        if (Math.abs(i.x - p.coordinateX()) <= r && Math.abs(i.z - p.coordinateZ()) <= r) out.add(i);
      }
      return out;
    }
    @Override public String name() { return W; }
    @Override public WorldPosition spawnPosition() { return new WorldPosition(W, 0, 64, 0, 0f, 0f); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition p, ItemStackData s) {}
    @Override public void playSound(WorldPosition p, SoundKey s, float v, float t) {}
    @Override public void setBorder(WorldBorderSpec s) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int x, int z, boolean g) {}
  }

  private static final class FakeItem implements ItemEntityHandle {
    final UUID id = UUID.randomUUID();
    final ItemKey key; int amount; double x; double z; boolean alive = true;
    FakeItem(ItemKey key, int amount, double x, double z) { this.key = key; this.amount = amount; this.x = x; this.z = z; }
    @Override public UUID id() { return id; }
    @Override public boolean valid() { return alive; }
    @Override public ItemKey itemKey() { return key; }
    @Override public int amount() { return amount; }
    @Override public void setAmount(int a) { amount = a; }
    @Override public void remove() { alive = false; }
    @Override public String worldName() { return W; }
    @Override public WorldPosition position() { return new WorldPosition(W, x, 64, z, 0f, 0f); }
    @Override public boolean setVelocity(double a, double b, double c) { return true; }
  }
}
