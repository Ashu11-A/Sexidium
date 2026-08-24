package com.sexidium.core.game.experience.events;

import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomEventsEngineTest {

  // ----- RandomEvent ---------------------------------------------------------------------------

  @Test
  void event_validatesAndClampsWeight() {
    RandomEvent event = new RandomEvent("id", "Name", 0, ctx -> { });
    assertEquals(1, event.weight()); // clamped up from 0
    assertEquals(10, RandomEvent.of("x", "X", ctx -> { }).weight());
    assertThrows(IllegalArgumentException.class, () -> new RandomEvent(null, "N", 1, ctx -> { }));
    assertThrows(IllegalArgumentException.class, () -> new RandomEvent(" ", "N", 1, ctx -> { }));
    assertThrows(IllegalArgumentException.class, () -> new RandomEvent("i", null, 1, ctx -> { }));
    assertThrows(IllegalArgumentException.class, () -> new RandomEvent("i", " ", 1, ctx -> { }));
    assertThrows(IllegalArgumentException.class, () -> new RandomEvent("i", "N", 1, null));
  }

  @Test
  void event_runInvokesTheAction() {
    AtomicInteger ran = new AtomicInteger();
    RandomEvent.of("i", "N", ctx -> ran.incrementAndGet()).run(context(2, world()));
    assertEquals(1, ran.get());
  }

  // ----- RandomEventEngine ---------------------------------------------------------------------

  @Test
  void engine_rejectsEmptyCatalog() {
    assertThrows(IllegalArgumentException.class, () -> new RandomEventEngine(List.of()));
    assertThrows(IllegalArgumentException.class, () -> new RandomEventEngine(null));
  }

  @Test
  void engine_firstPickIsWeightedOverFullWeights() {
    RandomEvent a = RandomEvent.of("a", "A", ctx -> { });
    RandomEvent b = RandomEvent.of("b", "B", ctx -> { });
    RandomEventEngine engine = new RandomEventEngine(List.of(a, b));
    assertEquals(2, engine.size());
    assertEquals(List.of(a, b), engine.events());
    assertEquals(0, engine.firedCount());
    // Nothing fired yet: full weights (10 each, total 20). target 0 -> a; target 19 -> last (b).
    assertEquals("a", new RandomEventEngine(List.of(a, b)).pick(new ScriptedRandom(0)).id());
    assertEquals("b", new RandomEventEngine(List.of(a, b)).pick(new ScriptedRandom(19)).id());
  }

  @Test
  void engine_downWeightsFiredEventsUntilAllFiredThenResetsTheBag() {
    RandomEvent a = RandomEvent.of("a", "A", ctx -> { });
    RandomEvent b = RandomEvent.of("b", "B", ctx -> { });
    RandomEventEngine engine = new RandomEventEngine(List.of(a, b));

    assertEquals("a", engine.pick(new ScriptedRandom(0)).id()); // fires a
    assertEquals(1, engine.firedCount());
    // a is now down-weighted to 1 (10/8), b full at 10 -> total 11. target 5 lands in b.
    assertEquals("b", engine.pick(new ScriptedRandom(5)).id());
    // Both have fired -> the bag resets.
    assertEquals(0, engine.firedCount());
  }

  @Test
  void engine_firedEventCanStillRecurRarely() {
    RandomEvent a = RandomEvent.of("a", "A", ctx -> { });
    RandomEvent b = RandomEvent.of("b", "B", ctx -> { });
    RandomEventEngine engine = new RandomEventEngine(List.of(a, b));
    assertEquals("a", engine.pick(new ScriptedRandom(0)).id()); // a fired, now weight 1
    // target 0 still lands in a's (small) bucket [0,1): a recurs.
    assertEquals("a", engine.pick(new ScriptedRandom(0)).id());
  }

  @Test
  void engine_singleEventAlwaysReturnsItAndResetsEachTime() {
    RandomEventEngine engine = new RandomEventEngine(List.of(RandomEvent.of("solo", "Solo", ctx -> { })));
    assertEquals("solo", engine.pick(new ScriptedRandom(0)).id());
    assertEquals(0, engine.firedCount()); // one event = bag resets immediately after each pick
    assertEquals("solo", engine.pick(new ScriptedRandom(0)).id());
  }

  @Test
  void engine_fireAnnouncesAndRunsTheEvent() {
    AtomicInteger ran = new AtomicInteger();
    RandomEventEngine engine = new RandomEventEngine(List.of(
        new RandomEvent("solo", "Solo", 5, ctx -> ran.incrementAndGet())));
    RecordingContext context = context(1, world());
    RandomEvent fired = engine.fire(context);
    assertEquals("solo", fired.id());
    assertEquals(1, ran.get());
    assertTrue(context.announced.stream().anyMatch(line -> line.contains("Solo")));
    // Single-event catalog: pick still works and records last-fired.
    assertEquals("solo", engine.pick(new Random(1)).id());
  }

  // ----- RandomEventCatalog --------------------------------------------------------------------

  @Test
  void catalog_hasAllEventsUniqueAndBalanced() {
    List<RandomEvent> events = RandomEventCatalog.defaults();
    // 24 original + 40 extended = 64.
    assertEquals(64, events.size());
    Set<String> ids = new HashSet<>();
    for (RandomEvent event : events) {
      assertTrue(ids.add(event.id()), "duplicate event id: " + event.id());
    }
    assertEquals(events.size(), RandomEventCatalog.engine().size());
  }

  @Test
  void catalog_everyEventRunsWithoutErrorForMultiplePlayersAndAWorld() {
    RecordingContext context = context(3, world()); // 3 players so shuffle has work to do
    for (RandomEvent event : RandomEventCatalog.defaults()) {
      event.run(context); // must not throw
    }
    // World-touching events actually reached the world.
    assertTrue(context.world().spawns > 0 || context.world().tnt > 0 || context.world().lightning > 0);
  }

  @Test
  void catalog_everyEventIsNullSafeWithOnePlayerAndNoWorld() {
    RecordingContext context = context(1, null); // no world, single player (shuffle is a no-op)
    for (RandomEvent event : RandomEventCatalog.defaults()) {
      event.run(context); // must not throw even without a world
    }
  }

  @Test
  void catalog_everyEventIsNullSafeWhenPlayersHaveNoPosition() {
    // The players have a world but report no position — exercises the per-player location guards
    // (item rain, tnt rain, lightning, shuffle) without throwing.
    RecordingWorld shared = world();
    List<PlayerAdapter> nullPos = List.of(new StubPlayer(shared, true), new StubPlayer(shared, true));
    RecordingContext context = new RecordingContext(nullPos, shared);
    for (RandomEvent event : RandomEventCatalog.defaults()) {
      event.run(context);
    }
  }

  // ----- helpers ------------------------------------------------------------------------------

  private static RecordingWorld world() {
    return new RecordingWorld();
  }

  private static RecordingContext context(int players, RecordingWorld world) {
    List<PlayerAdapter> list = new ArrayList<>();
    for (int index = 0; index < players; index++) {
      list.add(new StubPlayer(world, false)); // each player's own world is the shared recording world
    }
    return new RecordingContext(list, world);
  }

  private static final class RecordingContext implements RandomEventContext {
    private final List<PlayerAdapter> players;
    private final RecordingWorld world;
    private final List<String> announced = new ArrayList<>();

    RecordingContext(List<PlayerAdapter> players, RecordingWorld world) {
      this.players = players;
      this.world = world;
    }

    @Override public List<PlayerAdapter> players() { return players; }
    @Override public RecordingWorld world() { return world; }
    @Override public Random random() { return new Random(42); }
    @Override public void announce(String miniMessage) { announced.add(miniMessage); }
  }

  private static final class RecordingWorld implements WorldAdapter {
    private int spawns;
    private int tnt;
    private int lightning;

    @Override public void spawnMob(WorldPosition position, String entityType, int count) { spawns += count; }
    @Override public void spawnTnt(WorldPosition targetPosition, int fuseTicks) { tnt++; }
    @Override public void strikeLightning(WorldPosition position) { lightning++; }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) { }

    @Override public String name() { return "void"; }
    @Override public WorldPosition spawnPosition() { return new WorldPosition("void", 0, 64, 0, 0, 0); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void playSound(WorldPosition p, SoundKey s, float v, float pi) { }
    @Override public void setBorder(WorldBorderSpec b) { }
    @Override public void resetBorder() { }
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
  }

  private static final class StubPlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private final RecordingWorld world;
    private final boolean noPosition;

    StubPlayer(RecordingWorld world, boolean noPosition) {
      this.world = world;
      this.noPosition = noPosition;
    }

    @Override public WorldPosition position() { return noPosition ? null : new WorldPosition("void", 0, 64, 0, 0, 0); }
    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return "P"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String p) { return false; }
    @Override public void sendMiniMessage(String m) { }
    @Override public void sendPlainMessage(String m) { }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return world; }
    @Override public void teleport(WorldPosition p) { }
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType g) { }
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double h) { }
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int f) { }
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey s, float v, float p) { }
    @Override public void showTitle(TitleSpec t) { }
    @Override public void sendActionBar(String m) { }
    @Override public void setCompassTarget(WorldPosition p) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }

  /** A {@link Random} whose {@code nextInt(bound)} returns a scripted sequence (clamped into range). */
  private static final class ScriptedRandom extends Random {
    private final int[] values;
    private int index;

    ScriptedRandom(int... values) {
      this.values = values;
    }

    @Override
    public int nextInt(int bound) {
      int value = index < values.length ? values[index++] : 0;
      return Math.max(0, Math.min(bound - 1, value));
    }
  }
}
