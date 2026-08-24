package com.sexidium.core.world;

import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
 * The experience copy is all-or-nothing, and it has to be all-or-nothing on the path where it THREW.
 *
 * <p>Its caller ({@code ExperienceBackupService}) reads a false result as "nothing was kept" and does
 * not clean the destination up — it only reports FAILED. So a dimension folder that survives a failed
 * copy is an orphan no registry row names and no sweep collects, on a shared experiences tree where one
 * copy is around 290 MB. The cleanup used to infer "did we fail" from {@code published.size() !=
 * staged.size()}, and those two lists are balanced on exactly the path this test drives: a runtime
 * exception at the TOP of an iteration, after the previous one published and before this one staged
 * anything.</p>
 */
class ExperienceCopyRollbackTest {

  private static final WorldKey SOURCE = WorldKey.parse("diamond_hunt_ab12cd34");
  private static final WorldKey DESTINATION = WorldKey.parse("diamond_hunt_ff00ff00");

  @Test
  @DisplayName("a copy that throws AFTER publishing a dimension leaves none of them behind")
  void aThrowAfterAPublishStillRollsEverythingBack(@TempDir Path home) throws Exception {
    // The end folder is the third iteration, and resolving its SOURCE path blows up — which happens
    // after the overworld and the nether have both been renamed into place, and before the loop has
    // added a third staging folder. That is the shape the size comparison could not see.
    Control control = new Control(home, SOURCE.key() + "_the_end");
    Path experiences = sourceOnDisk(home);
    try {
      assertFalse(copy(control), "a copy that could not finish must never be reported as done");

      assertTrue(leftovers(experiences).isEmpty(),
          "every folder the copy published before it failed must be gone -- the caller treats false as"
              + " 'nothing was kept' and cleans up nothing, so whatever survives here is an orphan"
              + " nobody can see and nothing collects: " + leftovers(experiences));
    } finally {
      control.shutdown();
    }
  }

  @Test
  @DisplayName("the same copy, unimpeded, really does publish every dimension")
  void theControlRunPublishesAllThreeDimensions(@TempDir Path home) throws Exception {
    // Without this the test above would pass on a copy that never published anything at all.
    Control control = new Control(home, null);
    Path experiences = sourceOnDisk(home);
    try {
      assertTrue(copy(control), "nothing refuses this copy");

      assertEquals(List.of(DESTINATION.key(), DESTINATION.key() + "_nether",
              DESTINATION.key() + "_the_end"),
          leftovers(experiences),
          "all three dimensions travel, so the failing run above really did publish before it threw");
      assertEquals("blocks", Files.readString(
          experiences.resolve(DESTINATION.key() + "_nether").resolve("region/r.0.0.mca"),
          StandardCharsets.UTF_8));
    } finally {
      control.shutdown();
    }
  }

  // ===== harness ==============================================================================

  /** Runs one copy off the world thread and waits for the answer. */
  private static boolean copy(Control control) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean landed = new AtomicBoolean();
    control.copyExperienceWorld(SOURCE, DESTINATION, staging -> { }, result -> {
      landed.set(Boolean.TRUE.equals(result));
      done.countDown();
    });
    assertTrue(done.await(30, TimeUnit.SECONDS), "the copy must complete");
    return landed.get();
  }

  /** The source experience, overworld plus both linked dimensions. */
  private static Path sourceOnDisk(Path home) throws IOException {
    Path experiences = home.resolve("experiences");
    world(experiences.resolve(SOURCE.key()));
    world(experiences.resolve(SOURCE.key() + "_nether"));
    world(experiences.resolve(SOURCE.key() + "_the_end"));
    return experiences;
  }

  /** Every folder in the experiences root that is not the source — published or still staging. */
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

  /** A keyed dimension folder as it really is: no level.dat, no uid.dat, no session.lock. */
  private static Path world(Path folder) throws IOException {
    Files.createDirectories(folder.resolve("region"));
    Files.createDirectories(folder.resolve("sexidium/players"));
    Files.writeString(folder.resolve("region/r.0.0.mca"), "blocks", StandardCharsets.UTF_8);
    Files.writeString(folder.resolve("paper-world.yml"), "keep-spawn: false", StandardCharsets.UTF_8);
    Files.writeString(folder.resolve("sexidium/state.yml"), "deathresets.resets: \"7\"",
        StandardCharsets.UTF_8);
    return folder;
  }

  /**
   * The shared fake, given the two linked dimensions a real experience has and one key whose path
   * cannot be resolved — an {@link InvalidPathException} out of {@code experienceFolderFor} is one of
   * the real ways this loop leaves through its catch block with the two lists balanced.
   */
  private static final class Control extends FakeWorldControl {
    private final String unresolvableKey;

    private Control(Path home, String unresolvableKey) {
      super(new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("copy-rollback"), home);
      this.unresolvableKey = unresolvableKey;
    }

    @Override
    protected List<String> siblingKeySuffixes() {
      return List.of("_nether", "_the_end");
    }

    @Override
    protected Path experienceFolderFor(String key) {
      if (unresolvableKey != null && unresolvableKey.equals(key)) {
        throw new InvalidPathException(key, "Nul character not allowed");
      }
      return super.experienceFolderFor(key);
    }
  }
}
