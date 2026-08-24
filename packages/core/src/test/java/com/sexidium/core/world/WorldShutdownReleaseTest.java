package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fourth stop-path data-loss channel: a claim released while the world was still open.
 *
 * <p>{@code shutdown()} drained the pool and stopped there. {@code dispose} early-returns on anything
 * that {@code persistsOnRelease()}, so an experience world was never unloaded at all on the way down —
 * its region files were written by the platform's own final save, at the very end of the stop — while
 * the network layer had released every claim seconds earlier, before any of this ran. In that window a
 * peer sees an idle placement with a lapsed lease over a folder it can see on the shared tree, adopts
 * it, and opens the same dimension folder this process is still writing. Two servers, one save.</p>
 */
class WorldShutdownReleaseTest {

  private static final WorldKey KEY = WorldKey.parse("diamond_hunt_ab12cd34");

  @TempDir
  Path tempDir;

  /** Records the order of the two things that must not be swapped: the unload and the release. */
  private static final class RecordingAuthority implements WorldLeaseAuthority {
    final List<String> released = new ArrayList<>();
    final List<String> log;

    RecordingAuthority(List<String> log) {
      this.log = log;
    }

    @Override public ClaimOutcome claim(WorldKey key, String ownerUuid) {
      return new ClaimOutcome.Unavailable("test");
    }

    @Override public boolean renew(WorldClaim claim, int players) { return true; }

    @Override public boolean confirmOpen(WorldClaim claim) { return true; }

    @Override public boolean release(WorldClaim claim) {
      released.add(claim.key().key());
      log.add("release:" + claim.key().key());
      return true;
    }

    @Override public boolean unclaim(WorldClaim claim) { return true; }

    @Override public WorldKey allocateNextGeneration(String experienceId, WorldKey current) {
      return current;
    }

    @Override public Optional<Placement> locate(WorldKey key) { return Optional.empty(); }

    @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }

    @Override public List<Placement> heldBy(String nodeId) { return List.of(); }
  }

  private FakeWorldControl control(List<String> log) {
    FakeWorldControl control = new FakeWorldControl(
        new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("test"), tempDir, log);
    control.start();
    return control;
  }

  /** A gate that grants, and hands out a fence the way the real decider does. */
  private static WorldPlacementGate granting(WorldClaim claim) {
    return new WorldPlacementGate() {
      @Override public Decision check(String worldKey) { return Decision.allow(); }
      @Override public WorldClaim lastClaim() { return claim; }
    };
  }

  private WorldLease acquire(FakeWorldControl control, WorldKey key) {
    AtomicReference<WorldLease> result = new AtomicReference<>();
    control.acquireOrCreatePersistent(key, List.of(), WorldGeneration.DEFAULT, result::set, () -> { });
    return result.get();
  }

  private WorldClaim claimOn(FakeWorldControl control, RecordingAuthority authority) {
    WorldClaim claim = new WorldClaim(KEY, "worker-1", 7L, 1234L, System.currentTimeMillis() + 60_000L);
    control.setLeaseAuthority(authority);
    control.setPlacementGate(granting(claim));
    assertNotNull(acquire(control, KEY));
    return claim;
  }

  @Test
  @DisplayName("shutdown saves and closes the persistent worlds this node holds")
  void shutdownClosesPersistentWorlds() {
    List<String> log = new ArrayList<>();
    FakeWorldControl control = control(log);
    claimOn(control, new RecordingAuthority(log));

    control.shutdown();

    assertEquals(List.of(KEY.runtimeName()), control.unloaded,
        "an experience world was left LOADED by every stop: nothing in the disposal policy touches a"
            + " world that persists on release");
    assertTrue(control.savedOnUnload.get(),
        "and it is saved on the way out -- this is an orderly stop, not an eviction, so the chunk"
            + " cache is ours to flush");
  }

  @Test
  @DisplayName("the claim is handed back only AFTER the world has really closed")
  void theClaimOutlivesTheClose() {
    List<String> log = new ArrayList<>();
    FakeWorldControl control = control(log);
    RecordingAuthority authority = new RecordingAuthority(log);
    claimOn(control, authority);

    control.shutdown();

    assertEquals(List.of(KEY.key()), authority.released, "a clean stop still hands the world back");
    assertEquals(List.of("unload:" + KEY.runtimeName(), "release:" + KEY.key()), log,
        "released first, closed later is the two-writer window: every gate on a peer says yes while"
            + " this node is still writing the same region files");
    assertTrue(control.heldClaims().isEmpty());
  }

  @Test
  @DisplayName("a world that will not close KEEPS its claim, so no peer may open it")
  void aFailedUnloadKeepsTheClaim() {
    List<String> log = new ArrayList<>();
    FakeWorldControl control = control(log);
    RecordingAuthority authority = new RecordingAuthority(log);
    claimOn(control, authority);
    control.unloadSucceeds = false;

    control.shutdown();

    assertTrue(authority.released.isEmpty(),
        "a held lease on a stopping node costs one lease period of nobody taking the world over; a"
            + " released lease on a world whose region files are still open costs the world");
    assertEquals(1, control.heldClaims().size());
  }

  // ----- the drain's handover, for a world with no match ------------------------------------------

  @Test
  @DisplayName("a world with no match is closed, saved and handed over on demand")
  void aWorldWithNoMatchCanStillBeHandedOver() {
    List<String> log = new ArrayList<>();
    FakeWorldControl control = control(log);
    RecordingAuthority authority = new RecordingAuthority(log);
    claimOn(control, authority);

    assertTrue(control.handOverPersistent(KEY),
        "the drain's only handover was to END A MATCH, and a world does not have to have one");

    assertEquals(List.of(KEY.runtimeName()), control.unloaded);
    assertTrue(control.savedOnUnload.get());
    assertEquals(List.of(KEY.key()), authority.released);
    assertTrue(control.openPersistentPlacementKeys().isEmpty(),
        "and the drain's own counter has to fall, or the node never reaches QUIESCENT");
  }

  @Test
  @DisplayName("a world that will not close is not handed over, and is retried rather than abandoned")
  void aWorldThatWillNotCloseIsRetried() {
    List<String> log = new ArrayList<>();
    FakeWorldControl control = control(log);
    RecordingAuthority authority = new RecordingAuthority(log);
    claimOn(control, authority);
    control.unloadSucceeds = false;

    assertFalse(control.handOverPersistent(KEY));
    assertTrue(authority.released.isEmpty(), "a world we could not close is still ours to hold");

    control.unloadSucceeds = true;
    assertTrue(control.handOverPersistent(KEY), "and the next drain tick closes it");
    assertEquals(List.of(KEY.key()), authority.released);
  }

  @Test
  @DisplayName("a key stuck in `closing` whose world is already gone stops blocking the drain")
  void aStuckClosingEntryIsForgotten() {
    List<String> log = new ArrayList<>();
    FakeWorldControl control = control(log);
    RecordingAuthority authority = new RecordingAuthority(log);
    claimOn(control, authority);
    // The shape that pinned worlds_left at 1 for ever: `closing` means "closing, OR FAILED TO
    // close", it feeds openPersistentPlacementKeys(), and nothing removed an entry whose world had
    // in fact gone. No match to end, no other handover path, and the drain STALLED at its deadline
    // on a node that was otherwise perfectly healthy.
    control.unloadSucceeds = false;
    control.handOverPersistent(KEY);
    control.live.clear();
    assertTrue(control.openPersistentPlacementKeys().contains(KEY.key()));

    assertTrue(control.handOverPersistent(KEY));

    assertTrue(control.openPersistentPlacementKeys().isEmpty());
    assertEquals(List.of(KEY.key()), authority.released,
        "the claim goes back too: the world it fenced does not exist here any more");
  }
}
