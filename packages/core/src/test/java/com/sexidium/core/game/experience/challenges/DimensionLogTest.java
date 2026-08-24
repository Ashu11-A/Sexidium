package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.model.ItemKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the rule that makes Omni Chunk safe to play across dimensions: each one keeps its OWN history.
 * Mining a Nether corridor must not carve the same holes through what you built in the Overworld, and a
 * dimension entered for the first time must not arrive pre-filled with edits made somewhere else.
 */
class DimensionLogTest {
  private static final String OVERWORLD = "experiences/ashu/dig_ab12";
  private static final String NETHER = "experiences/ashu/dig_ab12_nether";

  @Test
  void aChangeInOneDimensionIsInvisibleToTheOthers() {
    DimensionLog overworld = new DimensionLog(OVERWORLD, 4096);
    DimensionLog nether = new DimensionLog(NETHER, 4096);

    overworld.ledger().append(10, 64, 10, ChunkLedger.Kind.PLACE, ItemKey.minecraft("oak_planks"));

    assertEquals(1, overworld.ledger().size());
    assertEquals(0, nether.ledger().size(), "the Nether must not have gained a change it never saw");
    assertNotEquals(overworld.ledger().hash(), nether.ledger().hash());
  }

  @Test
  void chunkVersionsAreNotSharedEither() {
    // The subtler half: even with separate histories, a shared version map would make the Nether's chunk
    // (5, 5) believe the Overworld's work was its own and skip it for ever.
    DimensionLog overworld = new DimensionLog(OVERWORLD, 4096);
    DimensionLog nether = new DimensionLog(NETHER, 4096);
    long chunk = DimensionLog.chunkKey(5, 5);

    overworld.setVersion(chunk, 7L);

    assertEquals(7L, overworld.versionOf(chunk));
    assertEquals(0L, nether.versionOf(chunk), "the same coordinates name a DIFFERENT chunk here");
  }

  @Test
  void aQueuedChunkIsQueuedOnlyOncePerDimension() {
    DimensionLog overworld = new DimensionLog(OVERWORLD, 4096);
    DimensionLog nether = new DimensionLog(NETHER, 4096);
    long chunk = DimensionLog.chunkKey(1, 2);

    assertTrue(overworld.enqueue(chunk));
    assertFalse(overworld.enqueue(chunk), "a sweep must not queue the same chunk twice");
    assertTrue(nether.enqueue(chunk), "…but the Nether's own chunk (1, 2) is unrelated work");

    overworld.dequeue(chunk);
    assertTrue(overworld.enqueue(chunk), "once finished it may be queued again");
  }

  @Test
  void eachDimensionPersistsUnderItsOwnKey() {
    // World names are slashed (and sometimes colon-namespaced), which would nest a state key into a tree.
    DimensionLog overworld = new DimensionLog(OVERWORLD, 4096);
    DimensionLog nether = new DimensionLog(NETHER, 4096);

    assertNotEquals(overworld.slug(), nether.slug(), "the two must never share a state key");
    for (DimensionLog log : new DimensionLog[] {overworld, nether}) {
      assertFalse(log.slug().contains("/"), log.slug());
      assertFalse(log.slug().contains("."), log.slug());
      assertFalse(log.slug().contains(":"), log.slug());
    }
  }

  @Test
  void chunkVersionsSurviveARoundTrip() {
    DimensionLog log = new DimensionLog(OVERWORLD, 4096);
    log.setVersion(DimensionLog.chunkKey(3, -7), 12L);
    log.setVersion(DimensionLog.chunkKey(-100, 250), 4L);

    DimensionLog restored = new DimensionLog(OVERWORLD, 4096);
    restored.decodeVersions(log.encodeVersions());

    assertEquals(12L, restored.versionOf(DimensionLog.chunkKey(3, -7)), "negative coordinates too");
    assertEquals(4L, restored.versionOf(DimensionLog.chunkKey(-100, 250)));
    assertEquals(2, restored.trackedChunks());
  }

  @Test
  void aDamagedVersionEntryCostsOnlyThatChunk() {
    DimensionLog log = new DimensionLog(OVERWORLD, 4096);
    log.decodeVersions("3|4|9,not-a-version,7|8|2");

    assertEquals(9L, log.versionOf(DimensionLog.chunkKey(3, 4)));
    assertEquals(2L, log.versionOf(DimensionLog.chunkKey(7, 8)));
    assertEquals(2, log.trackedChunks(), "the damaged entry is skipped, never fatal");
  }

  @Test
  void versionsAreOnlyTrustedWhenTheyMatchTheHistoryTheyWereRecordedAgainst() {
    DimensionLog log = new DimensionLog(OVERWORLD, 4096);
    log.ledger().append(1, 64, 1, ChunkLedger.Kind.PLACE, ItemKey.minecraft("stone"));
    String versions = "3|4|9";

    log.restoreVersions(versions, log.ledger().hash());
    assertEquals(9L, log.versionOf(DimensionLog.chunkKey(3, 4)), "recorded against THIS history");

    // A restored or hand-edited world: those chunks were synced against different changes, so every one
    // of them has to be re-verified rather than trusted.
    log.restoreVersions(versions, "a-different-history");
    assertEquals(0L, log.versionOf(DimensionLog.chunkKey(3, 4)));
    assertEquals(0, log.trackedChunks());
  }

  @Test
  void eachDimensionGetsItsOwnChangeBudget() {
    // max-changes is per dimension, so a busy Overworld cannot squeeze the Nether's history out.
    DimensionLog overworld = new DimensionLog(OVERWORLD, 2);
    DimensionLog nether = new DimensionLog(NETHER, 2);
    for (int index = 0; index < 5; index++) {
      overworld.ledger().append(index, 64, 0, ChunkLedger.Kind.PLACE, ItemKey.minecraft("stone"));
    }
    nether.ledger().append(0, 32, 0, ChunkLedger.Kind.PLACE, ItemKey.minecraft("netherrack"));

    assertEquals(2, overworld.ledger().size(), "trimmed to its own budget");
    assertEquals(1, nether.ledger().size(), "and the Nether keeps every one of its own");
  }
}
