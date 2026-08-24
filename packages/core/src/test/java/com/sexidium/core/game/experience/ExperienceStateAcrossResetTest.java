package com.sexidium.core.game.experience;

import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import com.sexidium.core.world.WorldNaming;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The junction a run has to survive: a world RESET, which does not edit the world — it builds a new
 * generation beside it, moves everyone across and deletes the old folder.
 *
 * <p>Per-player state lives INSIDE that folder ({@link ExperienceStateStore}), so every generation has
 * its own copy and nothing is carried by the file layer itself. What carries it is the swap: the
 * registry row is re-pointed, the per-player pointers are re-homed, and each player is saved again —
 * into the new folder — as part of moving them. Each of those is a separate write, and the run is lost
 * if any one of them is skipped. The tests below pin what the store guarantees on either side of that
 * swap, and the last one documents the case where the carry does not happen at all.</p>
 */
class ExperienceStateAcrossResetTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  private static final String OWNER = "ashu11a";
  // The canonical key: <map>_<id>, no owner segment. The <nick>/ level this used to carry was never
  // created on disk by anything — it was only ever the launcher's spelling of the same world.
  private static final String KEY = "death_resets_ab12cd34";

  @TempDir
  Path worlds;

  private ExperienceStateStore store() {
    return new ExperienceStateStore(worlds, new StdoutLoggerAdapter("Test"));
  }

  /** Creates the folder a generation of the experience world occupies on disk. */
  private String generation(int number) throws IOException {
    String key = number == 0 ? KEY : WorldNaming.experienceKeyForGeneration(KEY, number);
    Files.createDirectories(worlds.resolve(key));
    return key;
  }

  /** What phase D does once the old world is empty: the folder, and everything in it, goes. */
  private void deleteGeneration(String key) throws IOException {
    Path folder = worlds.resolve(key);
    if (!Files.isDirectory(folder)) {
      return;
    }
    try (var walk = Files.walk(folder)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static PlayerSnapshot snapshotOf(UUID player, String world, double x) {
    PlayerSnapshot snapshot = new PlayerSnapshot(player, "Ashu11a", null);
    snapshot.worldName = world;
    snapshot.coordinateX = x;
    snapshot.coordinateY = 71.0;
    snapshot.coordinateZ = -14.0;
    snapshot.health = 12.5;
    snapshot.foodLevel = 13;
    snapshot.xp = 1234;
    snapshot.inventoryPayload = "the-run-so-far";
    return snapshot;
  }

  /**
   * Each generation is a separate folder, so a snapshot written before a reset is NOT visible after it
   * by itself. Not a defect on its own — it is the reason the swap has to write again — but it is the
   * fact every other test here depends on, so it is stated rather than assumed.
   */
  @Test
  @DisplayName("a snapshot does not cross a generation boundary on its own")
  void aSnapshotIsScopedToItsGeneration() throws IOException {
    ExperienceStateStore store = store();
    UUID player = UUID.randomUUID();
    String before = generation(0);
    String after = generation(1);

    store.savePlayerSnapshot(before, snapshotOf(player, before, 100.0));

    assertTrue(store.hasPlayerSnapshot(before, player));
    assertFalse(store.hasPlayerSnapshot(after, player),
        "state lives in the world folder, and the new generation is a different folder");
  }

  /**
   * The carry, exactly as the swap performs it for a player who is ONLINE: read the snapshot out of the
   * generation being replaced, write it into the one replacing it. Everything the run consists of has to
   * be on the far side — position aside, which the reset deliberately replaces with the new world's
   * entry spawn.
   */
  @Test
  @DisplayName("a snapshot written in one generation is readable in the next after the carry")
  void theCarryMovesTheRunOntoTheNewGeneration() throws IOException {
    ExperienceStateStore store = store();
    UUID player = UUID.randomUUID();
    String before = generation(0);
    store.savePlayerSnapshot(before, snapshotOf(player, before, 100.0));

    String after = generation(1);
    PlayerSnapshot carried = store.loadPlayerSnapshot(before, player, "Ashu11a");
    assertNotNull(carried, "nothing can be carried that could not first be read back");
    carried.worldName = after;
    store.savePlayerSnapshot(after, carried);

    // ...and only NOW is the old world torn down, which is the order the swap uses for a reason.
    deleteGeneration(before);

    PlayerSnapshot resumed = store.loadPlayerSnapshot(after, player, "Ashu11a");
    assertNotNull(resumed, "the player's run must exist in the generation they were moved into");
    assertEquals(after, resumed.worldName);
    assertEquals(1234, resumed.xp);
    assertEquals(12.5, resumed.health);
    assertEquals("the-run-so-far", resumed.inventoryPayload,
        "an inventory lost in a reset is the entire run, and the player was watching a countdown");
    assertFalse(store.persistent(before), "the replaced generation is gone from disk");
  }

  /**
   * Ten resets in a row, each one carrying the previous generation forward and deleting it. A carry
   * that works once and drifts on the fourth is the same bug with a delay — this is the shape the
   * production incident had, where ten resets ran before anybody noticed the run had stopped moving.
   */
  @Test
  @DisplayName("the run survives ten consecutive resets, each deleting the one before it")
  void theRunSurvivesTenResets() throws IOException {
    ExperienceStateStore store = store();
    UUID player = UUID.randomUUID();
    String current = generation(0);
    store.savePlayerSnapshot(current, snapshotOf(player, current, 100.0));

    for (int number = 1; number <= 10; number++) {
      String next = generation(number);
      PlayerSnapshot carried = store.loadPlayerSnapshot(current, player, "Ashu11a");
      assertNotNull(carried, "the run vanished at reset " + number);
      carried.worldName = next;
      carried.xp = carried.xp + 1;
      store.savePlayerSnapshot(next, carried);
      deleteGeneration(current);
      current = next;
    }

    assertEquals(KEY + "_r10", current);
    PlayerSnapshot resumed = store.loadPlayerSnapshot(current, player, "Ashu11a");
    assertNotNull(resumed, "ten resets later the player must still have their run");
    assertEquals(1244, resumed.xp, "every generation must have carried, not just the first");
    assertEquals("the-run-so-far", resumed.inventoryPayload);
  }

  /**
   * The whole point of the pointer table, end to end and on real storage: the pointer names the world,
   * the world folder holds the state. A worker that has never seen this player before has only these
   * two facts to go on, and they have to agree AFTER a reset, because a reset is precisely when they
   * are written by different code paths.
   */
  @Test
  @DisplayName("the pointer and the world folder still agree about a player after a reset")
  void thePointerAndTheFolderAgreeAfterAReset() throws Exception {
    ExperienceStateStore store = store();
    ExperienceManager experiences =
        new ExperienceManager(SILENT, new Database(new File(worlds.toFile(), "registry.db")));
    ExperienceManager.Experience experience = experiences.create(UUID.randomUUID(), OWNER,
        List.of("deathresets"), "Death Resets", System.currentTimeMillis());
    assertNotNull(experience);
    UUID player = UUID.randomUUID();

    String before = generation(0);
    experiences.updateWorldKey(experience.id(), com.sexidium.core.world.WorldKey.parse(before), 1_000L);
    experiences.rememberPlayerWorld(
        experience.id(), player, com.sexidium.core.world.WorldKey.parse(before), 1_000L);
    store.savePlayerSnapshot(before, snapshotOf(player, before, 100.0));

    // The swap: new generation, registry re-pointed, pointers re-homed, the player written across.
    String after = generation(1);
    experiences.updateWorldKey(experience.id(), com.sexidium.core.world.WorldKey.parse(after), 2_000L);
    experiences.rehomePlayers(experience.id(), com.sexidium.core.world.WorldKey.parse(after), 2_000L);
    PlayerSnapshot carried = store.loadPlayerSnapshot(before, player, "Ashu11a");
    carried.worldName = after;
    store.savePlayerSnapshot(after, carried);
    deleteGeneration(before);

    // What a worker does when this player connects to it: ask where they were, then open that folder.
    String remembered = experiences.rememberedWorldOf(player).key();
    assertEquals(after, remembered, "the pointer must name a world that still exists");
    assertTrue(store.persistent(remembered), "...and the folder it names must be on this disk");
    assertNotNull(store.loadPlayerSnapshot(remembered, player, "Ashu11a"),
        "the pointer and the state store are two halves of one resume; either alone is a lost run");
    assertEquals(remembered, experiences.get(experience.id()).worldKey(),
        "the registry must name the same generation the player was sent to");
  }

  /**
   * OPEN BUG (documented, not fixed): a player who is OFFLINE across a reset loses everything.
   *
   * <p>The carry above is not performed by the reset — it is a side effect of MOVING each player, which
   * saves them into the world they arrive in. {@code ExperienceWorldReset.swap()} iterates
   * {@code game.online()}, so a player who disconnected before the countdown is never written into the
   * new generation, and phase D then deletes the only folder their snapshot was ever in. Their pointer
   * is re-homed correctly by {@code rehomePlayers} — so they are sent to a world that exists, arrive
   * with an empty inventory at the entry spawn, and their run is not recoverable from anywhere.</p>
   *
   * <p>Disabled because it asserts the behaviour the code does NOT have. Enable it with the fix: the
   * swap has to migrate the {@code sexidium/players/} folder wholesale from the replaced generation to
   * the new one, before phase D removes it — the online players' own saves then simply overwrite their
   * entries. This test is written from the outside (save, reset, resume) so it will pass with any
   * correct implementation of that carry, and is deliberately silent about which one.</p>
   */
  @Test
  @DisplayName("an offline player's run survives a reset somebody else triggered")
  void anOfflinePlayerLosesTheirRunAcrossAReset() throws IOException {
    ExperienceStateStore store = store();
    UUID online = UUID.randomUUID();
    UUID offline = UUID.randomUUID();
    String before = generation(0);
    store.savePlayerSnapshot(before, snapshotOf(online, before, 100.0));
    store.savePlayerSnapshot(before, snapshotOf(offline, before, 250.0));

    // The reset, as production performs it: the ONLINE players are written across as they are moved,
    // and carryPlayerSnapshots rescues everybody else BEFORE the old generation is deleted. Without
    // that call, "was online when somebody else died" silently decided who kept their inventory.
    String after = generation(1);
    PlayerSnapshot carried = store.loadPlayerSnapshot(before, online, "Ashu11a");
    carried.worldName = after;
    store.savePlayerSnapshot(after, carried);
    assertEquals(1, store.carryPlayerSnapshots(before, after),
        "only the offline player needs carrying; the online one is already there and fresher");
    deleteGeneration(before);

    assertNotNull(store.loadPlayerSnapshot(after, online, "Ashu11a"), "the online player is fine");
    assertNotNull(store.loadPlayerSnapshot(after, offline, "Ashu11a"),
        "a player who was merely logged out must find their run where the pointer sends them");
  }

  /**
   * Why the disabled test above cannot be fixed in the store: the store has NO cross-generation
   * fallback, by design. Asked about a generation, it answers about that generation and nothing else —
   * so an unmigrated player reads as a player with no run at all, silently, with no error for anyone to
   * act on. The carry therefore has to be performed by whoever deletes the folder (the reset), which is
   * what the disabled twin above describes. If someone ever makes the store search sibling generations
   * instead, this test fails and that decision gets made deliberately rather than by accident.
   */
  @Test
  @DisplayName("the store answers about ONE generation and never falls back to a sibling")
  void theStoreHasNoCrossGenerationFallback() throws IOException {
    ExperienceStateStore store = store();
    UUID offline = UUID.randomUUID();
    String before = generation(0);
    String after = generation(1);
    store.savePlayerSnapshot(before, snapshotOf(offline, before, 250.0));

    assertNull(store.loadPlayerSnapshot(after, offline, "Ashu11a"),
        "the previous generation is still on disk here, and the store must still not read from it");
    assertFalse(store.hasPlayerSnapshot(after, offline));
    assertNotNull(store.loadPlayerSnapshot(before, offline, "Ashu11a"),
        "...while the generation it WAS written to still answers, or this proves nothing");
  }

  @Test
  @DisplayName("a wiping carry brings the player across with nothing, exactly like the wipe does online")
  void wipingCarryStripsContents() throws IOException {
    ExperienceStateStore store = store();
    UUID player = UUID.randomUUID();
    String before = generation(0);
    String after = generation(1);
    store.savePlayerSnapshot(before, snapshotOf(player, before, 100.0));

    assertEquals(1, store.carryPlayerSnapshots(before, after, ExperienceStateStore.Carry.WIPE_CONTENTS));

    PlayerSnapshot carried = store.loadPlayerSnapshot(after, player, "Ashu11a");
    assertNotNull(carried, "the player still has to be known in the world they are sent to");
    // Blank rather than null: the store writes an absent payload as "", and PlayerSnapshot.applyTo
    // skips deserialising a blank one (PlayerSnapshot:75), so blank IS "no inventory" here.
    assertTrue(carried.inventoryPayload == null || carried.inventoryPayload.isBlank(),
        "an inventory carried across a wipe IS the bug");
    assertEquals(0, carried.xp);
    assertTrue(carried.effects.isEmpty());
    assertEquals(20.0, carried.health, 0.0001);
    assertEquals(20, carried.foodLevel);
    assertEquals("Ashu11a", carried.playerName, "the player still has to be recognisable in the new world");
  }

  @Test
  @DisplayName("the ordinary carry still brings the run's contents across")
  void keepingCarryIsUnchanged() throws IOException {
    ExperienceStateStore store = store();
    UUID player = UUID.randomUUID();
    String before = generation(0);
    String after = generation(1);
    store.savePlayerSnapshot(before, snapshotOf(player, before, 100.0));

    assertEquals(1, store.carryPlayerSnapshots(before, after));

    PlayerSnapshot carried = store.loadPlayerSnapshot(after, player, "Ashu11a");
    assertNotNull(carried);
    assertEquals("the-run-so-far", carried.inventoryPayload,
        "a mode that is not wiping must keep behaving exactly as it did");
    assertEquals(1234, carried.xp);
  }

  @Test
  @DisplayName("an unreadable snapshot is left behind by a wipe rather than copied across intact")
  void wipingCarrySkipsUnreadableSnapshots() throws IOException {
    ExperienceStateStore store = store();
    String before = generation(0);
    String after = generation(1);
    // A file whose name is not a UUID cannot be parsed, and the KEEP path copies it verbatim rather than
    // dropping somebody's run on the floor. Under a wipe that same kindness would smuggle a full
    // inventory past the wipe through the error path -- the same hole, by a quieter route.
    Path unreadable = worlds.resolve(before).resolve("sexidium").resolve("players").resolve("not-a-uuid.yml");
    Files.createDirectories(unreadable.getParent());
    Files.writeString(unreadable, "inv: \"a-whole-shulker\"\n");

    assertEquals(0, store.carryPlayerSnapshots(before, after, ExperienceStateStore.Carry.WIPE_CONTENTS));
    assertFalse(Files.exists(worlds.resolve(after).resolve("sexidium").resolve("players").resolve("not-a-uuid.yml")));

    assertEquals(1, store.carryPlayerSnapshots(before, generation(2)));
  }
}
