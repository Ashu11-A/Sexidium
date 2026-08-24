package com.sexidium.paper.adapter.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guards for the "an experience is three worlds" rule on Paper.
 *
 * <p>An experience owns an Overworld and the {@code _nether} / {@code _end} siblings the world layer
 * provisions beside it. Every operation on the experience has to mean all three, and the one that
 * matters most is DELETION — because a half-deleted experience is not a tidy leftover, it is a
 * correctness bug with a long fuse. Death Resets replaces the world on every death, so an experience
 * that only deleted its Overworld would leave a Nether and an End on disk per reset, and the next
 * regeneration would then find its own name half-taken.</p>
 *
 * <p>These read the source rather than driving {@code PaperWorldControl}, which needs a live Bukkit
 * server (worlds, keys, a scheduler) that no unit test has. The trade is the same one
 * {@code ExperienceResetDesignTest} makes on the core side: a narrower check, but one that turns a
 * regression into a build failure instead of an orphaned folder somebody notices weeks later.</p>
 */
class PaperLinkedDimensionDesignTest {

  private static String source() {
    Path path = Path.of("src/main/java/com/sexidium/paper/adapter/world/PaperWorldControl.java");
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      // Fall back to the canonical path when the test runs from the repo root rather than the module root.
      try {
        return Files.readString(Path.of("packages/module-paper").resolve(path));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }

  /**
   * Deleting an experience deletes its Nether and its End too.
   *
   * <p>The siblings live in their OWN dimension folders, outside the Overworld's, so the base class's
   * single-folder delete does not cascade to them. Nothing else in the codebase would ever remove them:
   * a regenerated world never reuses its name, so the leftovers are never revisited — they simply
   * accumulate, one pair per death.</p>
   */
  @Test
  void deletingAnExperienceDeletesItsNetherAndEnd() {
    String source = source();
    int override = source.indexOf("public boolean deletePersistent(com.sexidium.core.world.WorldKey worldKey)");
    assertTrue(override > 0, "PaperWorldControl must override deletePersistent to reach the siblings");

    String body = source.substring(override);
    int end = body.indexOf("\n  }");
    body = end < 0 ? body : body.substring(0, end);

    assertTrue(body.contains("NETHER_SUFFIX") && body.contains("END_SUFFIX"),
        "both siblings must be swept, not just the Overworld folder");
    assertTrue(body.contains("deleteDirectory("),
        "a sibling's folder is its own — unloading it is not deleting it");
    assertTrue(body.contains("forgetRegistration("),
        "a Multiverse registration left behind becomes a WORLD_FOLDER_INVALID error on every later boot");
    assertTrue(body.contains("evacuateToOverworld("),
        "anyone still inside a doomed sibling has to be moved before it is unloaded");
    assertTrue(body.contains("super.deletePersistent(worldKey)"),
        "the siblings go first, then the base class takes the Overworld");
  }

  /**
   * A regeneration name is only free when every world it would claim is free.
   *
   * <p>{@code AbstractWorldControl.isFreeExperienceKey} probes the base key plus whatever this backend
   * declares here. Returning an empty list would let a reset pick {@code …_r2} while a {@code …_r2_nether}
   * survives from a teardown that crashed half way — and the adopt path then tries to move a pooled world
   * onto a folder that already exists, which fails and quietly costs the run its linked dimension.</p>
   */
  @Test
  void aRegenerationNameMustClaimAllThreeDimensions() {
    String source = source();
    int override = source.indexOf("protected List<String> siblingKeySuffixes()");
    assertTrue(override > 0, "Paper gives every experience linked dimensions, so it must declare them");

    String body = source.substring(override, Math.min(source.length(), override + 200));
    assertTrue(body.contains("NETHER_SUFFIX") && body.contains("END_SUFFIX"),
        "a name claims the Overworld AND both siblings; the availability probe has to know that");
  }

  /**
   * The siblings are provisioned on every acquire path, including the one a reset actually takes.
   *
   * <p>A regeneration's world almost always comes from the warm pool rather than being generated, and the
   * two paths are separate methods. If only the generating path linked the dimensions, the reset would
   * work perfectly on a cold server and silently produce a Nether-less world on a warm one — which is to
   * say, in practice, always.</p>
   */
  @Test
  void everyAcquiredExperienceOverworldGetsItsSiblings() {
    String source = source();
    int first = source.indexOf("ensureExperienceSiblings(existing, request)");
    int second = source.indexOf("ensureExperienceSiblings(world, request)");

    assertTrue(first > 0 && second > 0,
        "both the already-loaded and the freshly-created branches of backendAcquire must link dimensions");
    // Adoption deliberately routes back through backendAcquire rather than duplicating its finishing
    // work, which is what makes a pool-served world get siblings for free. Keep it that way.
    assertTrue(source.contains("backendAcquire(adoptionRequest(request, seed), false)"),
        "a pooled world is adopted by loading it through backendAcquire, so it is linked like any other");
  }

  /**
   * A sibling is born with the stakes of the experience it belongs to.
   *
   * <p>Death Resets is hardcore, and hardcore is a property of the RUN, not of one dimension. A sibling
   * created without the flag hands the player ordinary hearts the moment they step through a portal, and
   * the client only learns what a world is when it is sent one — so there is no fixing it afterwards.</p>
   */
  @Test
  void siblingsInheritTheExperiencesHardcoreFlag() {
    String source = source();
    int start = source.indexOf("private void ensureExperienceSiblings(");
    assertTrue(start > 0, "ensureExperienceSiblings is where an experience's dimensions are decided");

    String body = source.substring(start);
    int end = body.indexOf("\n  }");
    body = end < 0 ? body : body.substring(0, end);

    assertTrue(body.contains("settings().hardcore()"),
        "the siblings of a hardcore experience are hardcore too");
    assertTrue(body.contains("createOrLoadSibling(overworld, World.Environment.NETHER")
            && body.contains("createOrLoadSibling(overworld, World.Environment.THE_END"),
        "both dimensions are provisioned eagerly — a portal event is the wrong place to create a world");
    assertTrue(body.contains("isOverworldKey(request.keyPath())"),
        "only an Overworld grows siblings — without this guard, acquiring '…_nether' would try to give "
            + "it a '…_nether_nether' and recurse");
  }
}
