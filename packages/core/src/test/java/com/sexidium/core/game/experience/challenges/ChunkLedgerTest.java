package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the commit log: order is preserved (it is what makes golems, TNT and fluids work), a slot's dead
 * history is squashed away, uses layer on top of the block they act on, and the whole thing round-trips
 * through the world-folder state.
 */
class ChunkLedgerTest {
  private static final ItemKey IRON = ItemKey.minecraft("iron_block");
  private static final ItemKey PUMPKIN = ItemKey.minecraft("carved_pumpkin");
  private static final ItemKey TNT = ItemKey.minecraft("tnt");
  private static final ItemKey FLINT = ItemKey.minecraft("flint_and_steel");
  private static final ItemKey PLANKS = ItemKey.minecraft("oak_planks");

  @Test
  void orderIsPreservedSoAStructureCompletesInTheRightSequence() {
    // The iron-golem case: the pumpkin must land AFTER the iron, or vanilla never spawns anything.
    ChunkLedger ledger = new ChunkLedger(64);
    ledger.append(0, 64, 0, ChunkLedger.Kind.PLACE, IRON);
    ledger.append(0, 65, 0, ChunkLedger.Kind.PLACE, IRON);
    ledger.append(1, 65, 0, ChunkLedger.Kind.PLACE, IRON);
    ledger.append(-1, 65, 0, ChunkLedger.Kind.PLACE, IRON);
    ledger.append(0, 66, 0, ChunkLedger.Kind.PLACE, PUMPKIN);

    List<ChunkLedger.Commit> commits = ledger.commits();
    assertEquals(5, commits.size());
    assertEquals(PUMPKIN, commits.get(4).payload(), "the trigger block must replay last");
    assertEquals(IRON, commits.get(0).payload());
  }

  @Test
  void aUseLayersOnTopOfTheBlockItActsOn() {
    // Placing TNT then lighting it must replay as BOTH, in that order — otherwise the copies are inert.
    ChunkLedger ledger = new ChunkLedger(64);
    ledger.append(4, 70, 4, ChunkLedger.Kind.PLACE, TNT);
    ledger.append(4, 70, 4, ChunkLedger.Kind.USE, FLINT);

    List<ChunkLedger.Commit> commits = ledger.commits();
    assertEquals(2, commits.size(), "a use must not squash the placement it acts on");
    assertEquals(ChunkLedger.Kind.PLACE, commits.get(0).kind());
    assertEquals(ChunkLedger.Kind.USE, commits.get(1).kind());
    assertEquals(FLINT, commits.get(1).payload());
  }

  @Test
  void breakingASlotDiscardsThatSlotsEarlierHistory() {
    ChunkLedger ledger = new ChunkLedger(64);
    ledger.append(2, 64, 3, ChunkLedger.Kind.PLACE, TNT);
    ledger.append(2, 64, 3, ChunkLedger.Kind.USE, FLINT);
    ledger.append(9, 64, 9, ChunkLedger.Kind.PLACE, PLANKS); // a different slot, must be untouched
    ledger.append(2, 64, 3, ChunkLedger.Kind.BREAK, null);

    List<ChunkLedger.Commit> commits = ledger.commits();
    assertEquals(2, commits.size(), "the broken slot keeps only its break: " + commits);
    assertEquals(PLANKS, commits.get(0).payload(), "another slot's history survives");
    assertEquals(ChunkLedger.Kind.BREAK, commits.get(1).kind());
  }

  @Test
  void rePlacingASlotSquashesItsOldCommitsToo() {
    ChunkLedger ledger = new ChunkLedger(64);
    ledger.append(1, 64, 1, ChunkLedger.Kind.PLACE, PLANKS);
    ledger.append(1, 64, 1, ChunkLedger.Kind.BREAK, null);
    ledger.append(1, 64, 1, ChunkLedger.Kind.PLACE, IRON);

    assertEquals(1, ledger.size(), "the shortest history that still reproduces the world");
    assertEquals(IRON, ledger.commits().get(0).payload());
    assertEquals(1, ledger.slotCount());
  }

  @Test
  void aDifferentHeightIsADifferentSlot() {
    ChunkLedger ledger = new ChunkLedger(64);
    ledger.append(1, 64, 1, ChunkLedger.Kind.PLACE, PLANKS);
    ledger.append(1, 65, 1, ChunkLedger.Kind.PLACE, PLANKS);
    assertEquals(2, ledger.size());
    assertEquals(2, ledger.slotCount());
  }

  @Test
  void theLogIsBoundedAndDropsTheOldestFirst() {
    ChunkLedger ledger = new ChunkLedger(3);
    for (int y = 60; y < 70; y++) {
      ledger.append(1, y, 1, ChunkLedger.Kind.PLACE, PLANKS);
    }
    assertEquals(3, ledger.size());
    List<ChunkLedger.Commit> commits = ledger.commits();
    assertEquals(67, commits.get(0).blockY(), "the far end of the branch is trimmed");
    assertEquals(69, commits.get(2).blockY());
  }

  @Test
  void aCommitResolvesIntoAnyChunk() {
    ChunkLedger ledger = new ChunkLedger(64);
    ChunkLedger.Commit commit = ledger.append(37, 64, -20, ChunkLedger.Kind.PLACE, PLANKS);
    assertEquals(5, commit.localX());
    assertEquals(12, commit.localZ(), "negative coordinates wrap, never go negative");
    assertEquals(new BlockPosition("w", 5, 64, 12), commit.at("w", 0, 0));
    assertEquals(new BlockPosition("w", -11, 64, -4), commit.at("w", -1, -1));
  }

  @Test
  void theLogRoundTripsThroughWorldState() {
    ChunkLedger original = new ChunkLedger(64);
    original.append(1, 64, 2, ChunkLedger.Kind.PLACE, TNT);
    original.append(1, 64, 2, ChunkLedger.Kind.USE, FLINT);
    original.append(5, 70, 9, ChunkLedger.Kind.BREAK, null);
    original.append(-3, 12, -7, ChunkLedger.Kind.PLACE, IRON);

    ChunkLedger restored = new ChunkLedger(64);
    restored.decode(original.encode());

    assertEquals(original.size(), restored.size());
    assertEquals(original.commits(), restored.commits(), "a restart must replay exactly the same history");
  }

  @Test
  void aDamagedEntryIsSkippedNotFatal() {
    ChunkLedger ledger = new ChunkLedger(64);
    // seq;localX;y;localZ;kind;payload — a good one, then junk, an empty field, a non-numeric coordinate,
    // a good break, and an unknown kind letter.
    ledger.decode("1;1;64;2;P;minecraft:tnt,GARBAGE,,2;4;x;6;P;minecraft:stone,"
        + "3;3;70;4;B;,4;5;5;5;?;minecraft:stone");
    // The two readable commits load; the unparseable and unknown-kind ones are dropped.
    assertEquals(2, ledger.size(), "readable commits survive a damaged neighbour: " + ledger.commits());
    assertEquals(TNT, ledger.commits().get(0).payload());
    assertEquals(ChunkLedger.Kind.BREAK, ledger.commits().get(1).kind());
    assertNull(ledger.commits().get(1).payload());

    ledger.decode("");
    assertEquals(0, ledger.size());
    ledger.decode(null);
    assertEquals(0, ledger.size());
  }

  // ----- versioning: is this chunk on the latest commit? -----------------------------------------

  @Test
  void theHeadAdvancesAndIdentifiesTheHistory() {
    ChunkLedger ledger = new ChunkLedger(64);
    ChunkLedger.Head empty = ledger.head();
    assertEquals(0L, empty.seq(), "an empty log has nothing to be at");

    ledger.append(1, 64, 1, ChunkLedger.Kind.PLACE, PLANKS);
    ChunkLedger.Head first = ledger.head();
    assertEquals(1L, first.seq());
    ledger.append(2, 64, 2, ChunkLedger.Kind.PLACE, IRON);
    assertEquals(2L, ledger.head().seq(), "each commit advances the head");
    assertNotEquals(first.hash(), ledger.head().hash(), "…and changes the log's identity");

    // The same history always hashes the same, so a chunk synced against it can be trusted.
    ChunkLedger twin = new ChunkLedger(64);
    twin.decode(ledger.encode());
    assertEquals(ledger.head(), twin.head(), "an identical history is an identical head");
  }

  @Test
  void aChunkBehindTheHeadGetsExactlyWhatItMissed() {
    ChunkLedger ledger = new ChunkLedger(64);
    ledger.append(1, 64, 1, ChunkLedger.Kind.PLACE, PLANKS);   // seq 1
    long synced = ledger.head().seq();
    ledger.append(2, 64, 2, ChunkLedger.Kind.PLACE, IRON);     // seq 2
    ledger.append(3, 64, 3, ChunkLedger.Kind.PLACE, TNT);      // seq 3

    List<ChunkLedger.Commit> delta = ledger.since(synced);
    assertEquals(2, delta.size(), "only the commits after the chunk's version");
    assertEquals(IRON, delta.get(0).payload());
    assertEquals(TNT, delta.get(1).payload());
    // A chunk already at the head has nothing pending.
    assertTrue(ledger.since(ledger.head().seq()).isEmpty());
  }

  @Test
  void aSquashedSlotStillReachesABehindChunkThroughItsReplacement() {
    // The subtle one: a chunk synced at seq 1 never sees the commit that got squashed away, but the
    // commit that REPLACED it carries a higher sequence id, so the delta still lands the chunk correctly.
    ChunkLedger ledger = new ChunkLedger(64);
    ledger.append(1, 64, 1, ChunkLedger.Kind.PLACE, PLANKS);   // seq 1
    long synced = ledger.head().seq();
    ledger.append(1, 64, 1, ChunkLedger.Kind.BREAK, null);     // seq 2, squashes seq 1

    List<ChunkLedger.Commit> delta = ledger.since(synced);
    assertEquals(1, delta.size());
    assertEquals(ChunkLedger.Kind.BREAK, delta.get(0).kind(), "the chunk is told to clear the slot");
  }

  @Test
  void aChunkTooFarBehindIsToldToReplayEverything() {
    ChunkLedger ledger = new ChunkLedger(3);
    for (int y = 60; y < 70; y++) {
      ledger.append(1, y, 1, ChunkLedger.Kind.PLACE, PLANKS);
    }
    // The log now starts at seq 8; a chunk stuck at seq 1 has missed commits that no longer exist.
    assertTrue(ledger.needsFullReplay(1L), "its missing commits were trimmed away — re-clone, not pull");
    assertFalse(ledger.needsFullReplay(ledger.baseSeq() - 1), "one behind the base can still fast-forward");
    assertFalse(ledger.needsFullReplay(ledger.head().seq()));
    assertFalse(new ChunkLedger(8).needsFullReplay(0L), "an empty log asks nothing of anybody");
  }

  @Test
  void sequenceIdsSurviveARestart() {
    ChunkLedger original = new ChunkLedger(64);
    original.append(1, 64, 1, ChunkLedger.Kind.PLACE, PLANKS);
    original.append(2, 64, 2, ChunkLedger.Kind.PLACE, IRON);

    ChunkLedger restored = new ChunkLedger(64);
    restored.decode(original.encode());
    // A commit made after the restart must not collide with an id a chunk has already recorded.
    restored.append(3, 64, 3, ChunkLedger.Kind.PLACE, TNT);
    assertEquals(3L, restored.head().seq(), "ids continue rather than restarting at 1");
    assertEquals(1, restored.since(2L).size());
  }

  @Test
  void onlyConfiguredItemUsesAreRecorded() {
    Set<String> recorded = Set.of("flint_and_steel", "bone_meal");
    assertTrue(ChunkLedger.recordableUse(FLINT, recorded));
    assertTrue(ChunkLedger.recordableUse(ItemKey.minecraft("BONE_MEAL"), recorded));
    assertFalse(ChunkLedger.recordableUse(ItemKey.minecraft("diamond_sword"), recorded),
        "swinging a sword is not a world edit");
    assertFalse(ChunkLedger.recordableUse(null, recorded));
  }

  /**
   * The head's hash is cached, and the cache must be exact — it identifies a version of the world's
   * edits, so a stale one would let a chunk that is genuinely behind report itself up to date.
   *
   * <p>Cached because {@code head()} is asked once per chunk, over every chunk in a player's radius,
   * for every block an edit touches. Recomputing there meant a single TNT explosion walked the whole
   * commit log thousands of times and froze the server thread past the watchdog — which cost the node
   * its world lease, and the players in it their session.</p>
   */
  @Test
  void theHeadHashIsCachedButNeverStale() {
    ChunkLedger ledger = new ChunkLedger(64);
    ledger.append(0, 64, 0, ChunkLedger.Kind.PLACE, IRON);

    String first = ledger.head().hash();
    assertEquals(first, ledger.head().hash(), "a log that has not moved must hash the same");
    assertEquals(first, ledger.hash(), "head() and hash() cannot disagree");

    ledger.append(0, 65, 0, ChunkLedger.Kind.PLACE, PUMPKIN);
    String second = ledger.head().hash();
    assertNotEquals(first, second, "an append moved the log; a cached hash here would be a lie");

    // A squash REPLACES a slot rather than growing the log, and a trim drops the oldest commit: both
    // change the history without necessarily changing its length, which is exactly the shape a
    // length-based cache check would miss.
    ledger.append(0, 65, 0, ChunkLedger.Kind.BREAK, null);
    assertNotEquals(second, ledger.head().hash(), "a squash changed the history");

    // Round-tripping through the world-folder state must land on the same hash, not a cached leftover.
    String encoded = ledger.encode();
    String live = ledger.head().hash();
    ChunkLedger restored = new ChunkLedger(64);
    restored.append(9, 9, 9, ChunkLedger.Kind.PLACE, PLANKS); // dirty its cache first
    restored.head().hash();
    restored.decode(encoded);
    assertEquals(live, restored.head().hash(), "decode must clear the cache it inherited");
  }
}
