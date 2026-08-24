package com.sexidium.paper.adapter.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guard for the one call that must never come back to the world thread.
 *
 * <p>{@code getHighestBlockYAt} generates the column's chunk if it does not exist yet — a full
 * carvers→features→full pass, seconds on a fresh world. {@code backendAcquire} says so in an NB, and
 * {@code pinLandSpawn} used to do it anyway, four lines below that NB, once per pooled world. What the
 * fleet got for it was a watchdog dump with the server thread parked in {@code ServerChunkCache.syncLoad}
 * and a node that stopped one second later. The comment was right and the code did not follow it, which
 * is precisely the kind of drift a comment cannot prevent on its own.</p>
 *
 * <p>Source-level, like {@code PaperCloneStagingDesignTest}: driving {@code PaperWorldControl} needs a
 * live Bukkit server, and what is being asserted here is a threading property of the call graph rather
 * than a return value.</p>
 */
class PaperLandSpawnDesignTest {

  private static String source(String className) {
    Path path = Path.of("src/main/java/com/sexidium/paper/adapter/world/" + className + ".java");
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

  private static String bodyOf(String source, String signature) {
    int start = source.indexOf(signature);
    assertTrue(start > 0, "expected to find: " + signature);
    return source.substring(start, source.indexOf("\n  }", start));
  }

  /** The pin picks a column and stops; the height is somebody else's problem, on somebody else's tick. */
  @Test
  void pinningALandSpawnNeverProbesTerrainOnTheWorldThread() {
    String body = bodyOf(source("PaperWorldControl"),
        "private void pinLandSpawn(World world, WorldRequest request)");

    assertFalse(body.contains("getHighestBlockYAt"),
        "pinLandSpawn runs on the world thread during pool warm-up: a terrain probe here is a"
            + " synchronous chunk generation, which is what tripped the watchdog and killed the node");
    assertFalse(body.contains("locateLandSpawn"),
        "locateLandSpawn resolves the height synchronously — pinLandSpawn wants locateLandColumn");
    assertTrue(body.contains("locateLandColumn"),
        "pinLandSpawn must take the free, biome-source-only half of the search");
    assertTrue(body.contains("resolveSpawnHeight"),
        "the expensive half must be handed to the async resolver");
  }

  /** And the resolver must actually be async, not a blocking probe wearing a different name. */
  @Test
  void theHeightResolverWaitsOnAnAsyncChunkLoad() {
    String body = bodyOf(source("PaperWorldControl"),
        "private void resolveSpawnHeight(World world, int blockX, int blockZ)");

    assertTrue(body.contains("getChunkAtAsync"),
        "resolveSpawnHeight must wait on an async chunk load, never on syncLoad");
    assertTrue(body.contains("server.getWorld(key) != world"),
        "a pooled world can be disposed while its chunk generates: the callback must verify the world is"
            + " still the live one before touching its spawn");
    assertTrue(body.indexOf("getChunkAtAsync") < body.indexOf("getHighestBlockYAt"),
        "the height read must happen INSIDE the async completion, where the chunk is already loaded and"
            + " the call is a heightmap lookup rather than a generation");
  }

  /** The blocking variant may stay, but it must keep saying so. */
  @Test
  void theBlockingLocatorIsDocumentedAsBlocking() {
    String source = source("PaperWorldAdapter");

    assertTrue(source.contains("public int[] locateLandColumn(String worldName, int maxRadius)"),
        "the free, terrain-free half of the search must be callable on its own");
    int column = source.indexOf("public int[] locateLandColumn(String worldName, int maxRadius)");
    assertFalse(bodyOf(source, "public int[] locateLandColumn(String worldName, int maxRadius)")
            .contains("getHighestBlockYAt"),
        "locateLandColumn must generate nothing — that is the entire reason it exists");

    int spawn = source.indexOf("public WorldPosition locateLandSpawn(String worldName, int maxRadius)");
    assertTrue(spawn > column, "expected locateLandSpawn to follow locateLandColumn");
    String javadoc = source.substring(column, spawn);
    assertTrue(javadoc.contains("<strong>Blocks.</strong>"),
        "locateLandSpawn still generates a chunk on the calling thread; its javadoc must say so, or the"
            + " next caller repeats the outage");
  }
}
