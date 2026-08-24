package com.sexidium.paper.adapter.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Third-party world registrations are dropped on RELEASE, and pruned by CLAIM at boot. (F-A4.)
 *
 * <p>Multiverse autoloads what is on its books before Sexidium is even enabled, so its registry
 * outranks every other source of truth at the worst possible moment. The only cleanup that existed
 * dropped a registration when the world's FOLDER was missing — which on a shared tree is never true,
 * because the bytes are visible from every node. So each of N workers accumulated a registration for
 * every world it had ever touched, for ever, and on the next boot every one of them opened the whole
 * park concurrently with no placement claim in sight.</p>
 *
 * <p>Source-reading, in the idiom of {@code PaperLinkedDimensionDesignTest}: what is under test is the
 * SHAPE of the lifecycle — where the calls sit relative to the unload and to the boot — and neither
 * can be interrogated at runtime without a live server and a live Multiverse.</p>
 */
class WorldRegistrarDesignTest {

  private static String source(String relative) {
    Path path = Path.of(relative);
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      try {
        return Files.readString(Path.of("packages/module-paper").resolve(path));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }

  private static String paper() {
    return source("src/main/java/com/sexidium/paper/adapter/world/PaperWorldControl.java");
  }

  private static String core() {
    return source("../core/src/main/java/com/sexidium/core/world/AbstractWorldControl.java");
  }

  @Test
  @DisplayName("the prune is keyed on WHAT THIS NODE HOLDS, not on whether the folder exists")
  void thePruneIsKeyedOnTheClaim() {
    String source = paper();
    int prune = source.indexOf("protected void backendPruneForeignRegistrations(");
    assertTrue(prune > 0, "the boot-time prune must exist");
    String body = source.substring(prune);
    body = body.substring(0, body.indexOf("\n  }"));

    assertTrue(body.contains("keysThisNodeHolds.contains("),
        "the question is whether this node has any business opening the world, not whether the bytes"
            + " are there — on one shared tree they always are, which made the old check a permanent"
            + " no-op");
    assertFalse(body.contains("Files.exists("),
        "a folder test here is exactly the check that never fired");
  }

  @Test
  @DisplayName("the prune touches only OUR namespace, never an operator's own worlds")
  void thePruneIsScopedToOurNamespace() {
    String source = paper();
    int prune = source.indexOf("protected void backendPruneForeignRegistrations(");
    String body = source.substring(prune);
    body = body.substring(0, body.indexOf("\n  }"));

    assertTrue(body.contains("naming.experiencesNamespace()") && body.contains("continue;"),
        "anything outside the experiences namespace belongs to the operator and is left alone");
  }

  @Test
  @DisplayName("a released world is unregistered, not only a deleted one")
  void releaseUnregisters() {
    String source = core();
    int release = source.indexOf("private void releasePersistent(WorldHandle handle, int attemptsLeft)");
    assertTrue(release > 0);
    String body = source.substring(release);
    body = body.substring(0, body.indexOf("\n  }"));

    assertTrue(body.contains("backendForgetRegistration("),
        "registering on open and forgetting only on DELETE is what let a node accumulate the park");
    assertTrue(body.contains("leaseAuthority.release("),
        "and the claim goes back at the same moment: a released world is one any node may take");
  }

  @Test
  @DisplayName("an EVICTED world is unregistered too")
  void evictionUnregisters() {
    String source = core();
    int evict = source.indexOf("public void onLeaseLost(");
    assertTrue(evict > 0);
    String body = source.substring(evict);
    body = body.substring(0, body.indexOf("\n  }"));

    // Follows the call rather than assuming the statement is inline. The unregister moved into
    // unloadEvicted when eviction gained a retry loop -- a world with players still in it cannot be
    // unloaded, and the single unattempted unload left the evicted node holding the region files
    // open. The guarantee is unchanged; only where it is written down moved.
    assertTrue(body.contains("unloadEvicted("),
        "eviction must go through the retrying unload, not a single attempt whose result is dropped");

    int unload = source.indexOf("private void unloadEvicted(");
    assertTrue(unload > 0, "unloadEvicted is where the eviction unload now lives");
    String unloadBody = source.substring(unload);
    unloadBody = unloadBody.substring(0, unloadBody.indexOf("\n  }"));

    assertTrue(unloadBody.contains("backendForgetRegistration("),
        "a world we have been evicted from must not stay on this node's autoload list; the next boot"
            + " would open it again, before the plugin exists and with no claim of any kind");
    assertTrue(unloadBody.contains("backendUnload(handle, false)"),
        "and it never saves: another node may already be writing these region files");
  }

  @Test
  @DisplayName("the boot prune runs BEFORE anything is opened")
  void thePruneRunsBeforeAnythingIsOpened() {
    String core = source("../core/src/main/java/com/sexidium/core/SexidiumCore.java");
    int prune = core.indexOf("control.pruneForeignRegistrations(");
    int reconcile = core.indexOf("reconcilePlacements();");
    assertTrue(prune > 0 && reconcile > 0);
    assertTrue(prune < reconcile,
        "Multiverse acts on its books before Sexidium is enabled, so this is the only moment the"
            + " question can be asked at all");
  }

  @Test
  @DisplayName("forgetting a world retires its linked dimensions with it")
  void siblingsAreRetiredToo() {
    String source = paper();
    int forget = source.indexOf("protected void backendForgetRegistration(");
    assertTrue(forget > 0);
    String body = source.substring(forget);
    body = body.substring(0, body.indexOf("\n  }"));

    assertTrue(body.contains("siblingKeySuffixes()"),
        "the Nether and End travel with their overworld and were never unregistered at all");
  }
}
