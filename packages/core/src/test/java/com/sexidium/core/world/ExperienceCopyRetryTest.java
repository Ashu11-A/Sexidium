package com.sexidium.core.world;

import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when the source will not hold still — the failure a LIVE copy actually has.
 *
 * <p>Copies are allowed to read a world people are inside ({@code worlds.experiences.allow-live-copy}),
 * so {@code TEMPLATE_CHANGED} stopped being an exotic race with a map re-extract and became the
 * ordinary consequence of somebody breaking a block while their world is copied. Two properties have to
 * hold at once, and they pull against each other:</p>
 *
 * <ul>
 *   <li>a source that settles must be retried rather than refused, or every busy world becomes
 *       un-backup-able in practice;</li>
 *   <li>a source that never settles must be REFUSED, bounded, and quickly. A world under active play
 *       may genuinely never be quiet, and a copy that keeps trying holds a backup thread and a staging
 *       tree for the length of the session — while publishing it anyway would be the one outcome this
 *       whole feature exists to prevent.</li>
 * </ul>
 *
 * <p>The retry lives in the world layer, once, at the point the drift is detected — not around the
 * whole verb. A second loop in {@code ExperienceBackupService} would multiply with this one and would
 * re-run the two dimensions that already copied cleanly to fix the third.</p>
 */
class ExperienceCopyRetryTest {

  private static final WorldKey SOURCE = WorldKey.parse("diamond_hunt_ab12cd34");
  private static final WorldKey DESTINATION = WorldKey.parse("diamond_hunt_ff00ff00");

  @Test
  @DisplayName("a source that settles on a later attempt is copied, not refused")
  void driftThatSettlesIsRetried(@TempDir Path home) throws Exception {
    // Two failures then a success: a player who moved while the first read ran and then stood still.
    Control control = new Control(home, AbstractWorldControl.COPY_ATTEMPTS - 1);
    try {
      assertTrue(copy(control), "a world that stops moving must end up copied");

      assertEquals(AbstractWorldControl.COPY_ATTEMPTS + 2, control.attempts.size(),
          "the overworld took all three attempts, then each of the two siblings took one: "
              + control.attempts);
      assertTrue(Files.isDirectory(home.resolve("experiences").resolve(DESTINATION.key())));
    } finally {
      control.shutdown();
    }
  }

  @Test
  @DisplayName("a source that NEVER settles is refused, after a bounded number of attempts")
  void driftThatNeverSettlesIsRefused(@TempDir Path home) throws Exception {
    Control control = new Control(home, Integer.MAX_VALUE);
    try {
      assertFalse(copy(control),
          "publishing a folder whose source moved under the read is the one thing this must never"
              + " do: it would be recorded as an exact replica of a world it is not");

      assertEquals(AbstractWorldControl.COPY_ATTEMPTS, control.attempts.size(),
          "bounded, and bounded HERE. A world under active play can keep moving for hours, so an"
              + " unbounded retry would pin a backup thread and a staging tree for the session, and"
              + " a second retry loop at the service layer would multiply into nine tree copies");
      assertTrue(leftovers(home.resolve("experiences")).isEmpty(),
          "and a refusal leaves nothing behind — no staging, no published dimension: "
              + leftovers(home.resolve("experiences")));
    } finally {
      control.shutdown();
    }
  }

  // ===== harness ==============================================================================

  private static boolean copy(Control control) throws Exception {
    sourceOnDisk(control.home());
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean landed = new AtomicBoolean();
    control.copyExperienceWorld(SOURCE, DESTINATION, staging -> { }, result -> {
      landed.set(Boolean.TRUE.equals(result));
      done.countDown();
    });
    assertTrue(done.await(30, TimeUnit.SECONDS), "the copy must complete");
    return landed.get();
  }

  private static void sourceOnDisk(Path home) throws IOException {
    world(home.resolve("experiences").resolve(SOURCE.key()));
    world(home.resolve("experiences").resolve(SOURCE.key() + "_nether"));
    world(home.resolve("experiences").resolve(SOURCE.key() + "_the_end"));
  }

  private static List<String> leftovers(Path experiences) throws IOException {
    List<String> names = new ArrayList<>();
    try (var entries = Files.list(experiences)) {
      entries.map(path -> path.getFileName().toString())
          .filter(name -> !name.startsWith(SOURCE.key()))
          .sorted()
          .forEach(names::add);
    }
    return names;
  }

  private static void world(Path folder) throws IOException {
    Files.createDirectories(folder.resolve("region"));
    Files.createDirectories(folder.resolve("sexidium"));
    Files.writeString(folder.resolve("region/r.0.0.mca"), "blocks", StandardCharsets.UTF_8);
    Files.writeString(folder.resolve("sexidium/state.yml"), "deathresets.resets: \"7\"",
        StandardCharsets.UTF_8);
  }

  /**
   * A control whose verified copy reports drift for the first {@code driftingReads} calls.
   *
   * <p>Injected rather than provoked: real drift is a race with another thread inside a live server,
   * so the alternative is a test that writes into the source folder from a second thread and hopes the
   * timing lands — flaky in exactly the direction that hides a regression (a passing run would prove
   * nothing about the bound).</p>
   */
  private static final class Control extends FakeWorldControl {
    private final Path home;
    private final int driftingReads;
    private final List<String> attempts = new ArrayList<>();

    private Control(Path home, int driftingReads) {
      super(new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("copy-retry"), home);
      this.home = home;
      this.driftingReads = driftingReads;
    }

    private Path home() {
      return home;
    }

    @Override
    protected List<String> siblingKeySuffixes() {
      return List.of("_nether", "_the_end");
    }

    @Override
    protected WorldClone.ChunkCopyResult copyOneWorldFolder(Path from, Path staging) {
      attempts.add(from.getFileName().toString());
      // Only the OVERWORLD drifts, so the sibling copies also prove the counter is per-folder rather
      // than per-verb: a budget shared across dimensions would let one busy dimension exhaust it.
      long soFar = attempts.stream().filter(name -> name.equals(SOURCE.key())).count();
      if (from.getFileName().toString().equals(SOURCE.key()) && soFar <= driftingReads) {
        return new WorldClone.ChunkCopyResult(WorldClone.CloneFailure.TEMPLATE_CHANGED,
            "'" + from + "' changed while it was being copied (region/r.0.0.mca was rewritten)");
      }
      return super.copyOneWorldFolder(from, staging);
    }
  }
}
