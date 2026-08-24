package com.sexidium.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two structural facts about the experience copy path that cannot be observed from outside it.
 *
 * <p>Source text, because both are about which THREAD and which LOCK, and neither is visible in any
 * result the class returns. A regression in either is silent for months: the first shows up as match
 * starts that occasionally take minutes, and the second as an operator trusting a lock file that
 * excludes nothing.</p>
 */
class BackupStagingSeparationTest {

  private static String source() {
    Path file = Path.of("src/main/java/com/sexidium/core/world/AbstractWorldControl.java");
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException fromModuleRoot) {
      // The same fallback ExperienceResetDesignTest uses: the test runs from either root.
      try {
        return Files.readString(Path.of("packages/core").resolve(file), StandardCharsets.UTF_8);
      } catch (IOException unreadable) {
        throw new AssertionError("Could not read " + file, unreadable);
      }
    }
  }

  @Test
  @DisplayName("a backup copy runs on its OWN thread, never the queue a match start waits on")
  void backupsDoNotShareTheCloneStagingQueue() {
    String text = source();
    int copy = text.indexOf("public void copyExperienceWorld(");
    assertTrue(copy > 0, "copyExperienceWorld moved; re-point this guard rather than deleting it");
    String body = text.substring(copy, text.indexOf("private boolean copyExperienceFolders("));

    assertTrue(body.contains("backupExecutor()"),
        "a 290 MB experience copy at the head of the single-threaded clone queue blocks every match"
            + " start behind it for as long as the disk takes. Two threads contending for one disk is"
            + " strictly better than head-of-line blocking");
    assertFalse(body.contains("stagingExecutor()"),
        "they must be different executors, not the same one under two names");
    assertTrue(text.contains("private volatile ExecutorService backupStaging;")
            && text.contains("private volatile ExecutorService cloneStaging;"),
        "two distinct fields, so shutdown can drain both");
    assertTrue(text.contains("\"sexidium-backup-staging\""),
        "named, because the reason to look for this thread is always a stall somebody is timing");
  }

  @Test
  @DisplayName("the experience copy takes no lock file, because the one it took excluded nothing")
  void theExperienceCopyDoesNotTakeAFalseLock() {
    String text = source();
    String body = text.substring(text.indexOf("private boolean copyExperienceFolders("),
        text.indexOf("private List<String> keyAndSiblingSuffixes()"));

    assertFalse(body.contains("underSharedWorldRootLock("),
        "it took the shared-map lock in SHARED mode, and POSIX read locks do not exclude each other;"
            + " the only exclusive taker is bundled-map seeding, which never runs against the"
            + " experiences root at all. So it excluded nothing while writing an operator-visible"
            + " .map-bundle.lock into the shared experiences tree that implied it did -- which is"
            + " worse than no lock, because it is what the next reader trusts instead of looking");
    assertTrue(body.contains("copyWorldFolderChecked"),
        "what actually protects the copy is the before/after inventory bracket, and it must stay");
    assertTrue(body.contains("TEMPLATE_CHANGED"),
        "a source that moved under the read has to FAIL the copy, not publish a torn replica");
  }
}
