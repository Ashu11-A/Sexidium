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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways a copy could be PUBLISHED while not being a copy of that world.
 *
 * <p>Both are worse than a failure, because both end with a folder recorded as a verified exact
 * replica — the one outcome {@code allowLiveCopy()} promises cannot happen, and the reason the whole
 * bracket-and-retry machinery exists.</p>
 *
 * <ul>
 *   <li><b>No overworld.</b> A missing SIBLING is deliberately not a failure: an experience that has
 *       never had a Nether has no {@code _nether} folder. The same {@code continue} covered the
 *       overworld, so a source whose main folder was absent while a stale sibling was not published
 *       that sibling alone and called it the world.</li>
 *   <li><b>Staging that could not be cleared.</b> The retry loop wipes staging between attempts
 *       because {@code TEMPLATE_CHANGED} means files may have DISAPPEARED. The wipe reported nothing,
 *       so a failure had the next attempt copy over the leftovers with REPLACE_EXISTING — and
 *       {@link WorldClone#firstMismatch} walks the SOURCE's inventory, never the destination's, so an
 *       extra file in the copy is invisible to the verification.</li>
 * </ul>
 */
class ExperienceCopyIntegrityTest {

  private static final WorldKey SOURCE = WorldKey.parse("diamond_hunt_ab12cd34");
  private static final WorldKey DESTINATION = WorldKey.parse("diamond_hunt_ff00ff00");

  @Test
  @DisplayName("a source with no overworld is refused outright, siblings or no siblings")
  void aCopyWithNoOverworldIsNeverPublished(@TempDir Path home) throws Exception {
    Control control = new Control(home, false);
    try {
      // Only the Nether is on disk: the shape a half-deleted experience, or one whose overworld never
      // landed, actually has. A backup of it is not a backup of anything.
      world(home.resolve("experiences").resolve(SOURCE.key() + "_nether"));

      assertFalse(copy(control), "there is no world here to copy");
      assertEquals(List.of(), leftovers(home.resolve("experiences")),
          "and nothing was published: a single dimension recorded as the whole world is a backup the"
              + " owner would restore and find empty");
    } finally {
      control.shutdown();
    }
  }

  @Test
  @DisplayName("staging that cannot be cleared between attempts FAILS the copy instead of merging")
  void stagingThatWillNotClearStopsTheCopy(@TempDir Path home) throws Exception {
    Control control = new Control(home, true);
    try {
      sourceOnDisk(home);

      assertFalse(copy(control),
          "the alternative is copying over what is still in staging and publishing the mixture as a"
              + " verified replica, which the verification cannot see because it only walks the source");
      assumeTrue(control.jammed.get(), "this test needs a staging folder it cannot delete");
      assertEquals(1, control.attempts.size(),
          "and it stops at the first attempt: a clearance that failed is a reason to stop, not a"
              + " reason to try again on top of the leftovers: " + control.attempts);
    } finally {
      control.unjam();
      control.shutdown();
    }
  }

  @Test
  @DisplayName("deleteDirectory reports what it could not remove, rather than swallowing it")
  void deleteDirectoryAnswers(@TempDir Path home) throws Exception {
    Path folder = home.resolve("locked");
    Files.createDirectories(folder);
    Files.writeString(folder.resolve("held.mca"), "blocks", StandardCharsets.UTF_8);
    assertTrue(WorldStorage.deleteDirectory(home.resolve("never-existed")),
        "a folder that is not there IS removed, as far as any caller is concerned");

    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeTrue(!Files.isWritable(folder), "this test needs a directory it cannot unlink from");
      assertFalse(WorldStorage.deleteDirectory(folder),
          "the rollback paths read this: reporting success over folders that are still there is how"
              + " ~290 MB stayed on the shared tree with not one line in the log");
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
    assertTrue(WorldStorage.deleteDirectory(folder), "and once it can, it says so");
  }

  // ===== harness ==============================================================================

  private static boolean copy(Control control) throws Exception {
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
   * A control that can report drift once and, when asked, leave the staging folder undeletable.
   *
   * <p>Injected rather than provoked, for the same reason {@code ExperienceCopyRetryTest} injects
   * drift: a real unlink failure is a permissions or filesystem state a test cannot arrange from the
   * outside of the class under test without racing it.</p>
   */
  private static final class Control extends FakeWorldControl {
    private final boolean jamStagingOnDrift;
    private final List<String> attempts = new ArrayList<>();
    private final AtomicBoolean jammed = new AtomicBoolean();
    private volatile Path jammedFolder;

    private Control(Path home, boolean jamStagingOnDrift) {
      super(new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("copy-integrity"), home);
      this.jamStagingOnDrift = jamStagingOnDrift;
    }

    private void unjam() throws IOException {
      Path folder = jammedFolder;
      if (folder != null && Files.isDirectory(folder)) {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
    }

    @Override
    protected List<String> siblingKeySuffixes() {
      return List.of("_nether", "_the_end");
    }

    @Override
    protected WorldClone.ChunkCopyResult copyOneWorldFolder(Path from, Path staging) {
      attempts.add(from.getFileName().toString());
      if (!jamStagingOnDrift) {
        return super.copyOneWorldFolder(from, staging);
      }
      // The source moved under the read AND the staging it left behind cannot be unlinked — which is
      // exactly the pair the retry loop has to notice, because it is about to write over it.
      try {
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("leftover.mca"), "stale", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("r-xr-xr-x"));
        jammedFolder = staging;
        jammed.set(!Files.isWritable(staging));
      } catch (IOException unexpected) {
        throw new IllegalStateException(unexpected);
      }
      return new WorldClone.ChunkCopyResult(WorldClone.CloneFailure.TEMPLATE_CHANGED,
          "'" + from + "' changed while it was being copied (region/r.0.0.mca was rewritten)");
    }
  }
}
