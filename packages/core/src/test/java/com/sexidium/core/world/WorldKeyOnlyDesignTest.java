package com.sexidium.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No {@code String} world name crosses the world/network seam any more.
 *
 * <p>This is the structural half of the one-spelling rule. {@link WorldKeyTest} proves the type is
 * correct; this proves nothing has quietly gone round it. A single missed call site reintroduces a
 * second spelling, and a second spelling is not a cosmetic problem — it is one world holding two
 * placement rows on two nodes, which is two servers writing the same region files.</p>
 *
 * <p>Source-reading, like {@code ExperienceResetDesignTest}: what is being asserted is the SHAPE of
 * the seam, and a signature cannot be interrogated for its parameter names at runtime.</p>
 */
class WorldKeyOnlyDesignTest {

  private static String source(String relative) {
    Path path = Path.of(relative);
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      try {
        return Files.readString(Path.of("packages/core").resolve(path));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }

  private static String worldLeaseService() {
    return source("src/main/java/com/sexidium/core/platform/WorldLeaseService.java");
  }

  @Test
  @DisplayName("every persistent-world door on WorldLeaseService takes a WorldKey")
  void thePersistentDoorsTakeAWorldKey() {
    String source = worldLeaseService();

    for (String signature : List.of(
        "acquireOrCreatePersistent(\n      WorldKey key,",
        "deletePersistent(WorldKey key)",
        "nextExperienceGeneration(WorldKey key)")) {
      assertTrue(source.contains(signature),
          "WorldLeaseService must declare " + signature + "; a String there is a second spelling"
              + " waiting to happen");
    }
  }

  @Test
  @DisplayName("reacquirePersistent no longer exists at all")
  void reacquirePersistentIsGone() {
    // The worst ungated door in the tree: it opened a persistent world with no placement check and no
    // capability check, and PendingMatchStore.rehydrate reached it on every single join off match rows
    // every node imported from every other node.
    assertFalse(worldLeaseService().contains("Optional<WorldLease> reacquirePersistent"),
        "there is nothing to replace it with: experience_players and ExperienceStateStore already"
            + " carry a returning player's world, through the gated entry path");
    assertFalse(source("src/main/java/com/sexidium/core/world/AbstractWorldControl.java")
            .contains("public Optional<WorldLease> reacquirePersistent"),
        "and no implementation may reintroduce it");
  }

  @Test
  @DisplayName("the three legacy acquireOrCreatePersistent arities are gone")
  void theLegacyAritiesAreGone() {
    String source = worldLeaseService();

    // One method, one WorldGeneration. The four-deep default-method chain was one live method behind
    // three unused ones, and each of the three took a world NAME.
    assertEquals(1, count(source, "acquireOrCreatePersistent(\n      "),
        "there must be exactly one acquireOrCreatePersistent declaration");
    assertFalse(source.contains("boolean voidWorld,"),
        "the void flags travel inside WorldGeneration, not as their own arity");
  }

  @Test
  @DisplayName("the two lossy normalisers are gone from the seam")
  void theLossyNormalisersAreGone() {
    String source = worldLeaseService();

    // experienceKeyOf was not idempotent and experienceIdentityKey existed only to paper over it.
    assertFalse(source.contains("experienceKeyOf"),
        "experienceKeyOf must not be reachable from the service seam; WorldKey.fromRuntime is");
    assertFalse(source.contains("experienceIdentityKey"),
        "experienceIdentityKey existed only because two spellings had to be bridged");
  }

  @Test
  @DisplayName("the tolerant registry scan is gone: a world resolves by index or not at all")
  void theRegistryResolvesByIndex() {
    String manager = source("src/main/java/com/sexidium/core/game/experience/ExperienceManager.java");

    assertFalse(manager.contains("byWorldCanonical"),
        "byWorldCanonical scanned the whole table on the main thread, on every start");
    assertTrue(manager.contains("public Experience byWorld(WorldKey key)"),
        "the one lookup takes a key and hits the unique index on world_key");
    assertFalse(manager.contains("world_name"),
        "the column is world_key; two names for it is how this started");
  }

  @Test
  @DisplayName("WorldNaming's non-idempotent normaliser is package-private, with WorldKey in front")
  void theNormaliserIsNotPublic() {
    String naming = source("src/main/java/com/sexidium/core/world/WorldNaming.java");

    assertFalse(naming.contains("public String experienceKeyOf("),
        "the instance normaliser must not be public; WorldKey.fromRuntime is the entry point");
    assertFalse(naming.contains("public static String experienceKeyOf("),
        "nor the static one");
    assertFalse(naming.contains("return lastSegment(normalized);"),
        "the owner-segment fallback is what made the normaliser non-idempotent");
  }

  @Test
  @DisplayName("the placement gate is asked about WorldKey.key() and nothing else")
  void thePlacementGateIsAskedByKey() {
    String control = source("src/main/java/com/sexidium/core/world/AbstractWorldControl.java");

    assertTrue(control.contains("String placementKey = key.key();"),
        "the gate takes the canonical key straight off the WorldKey — no re-derivation, no spelling");
    assertFalse(control.contains("scopePrefixOf"),
        "the owner-scope prefix a successor had to re-apply cannot exist when there is one key space");
  }

  private static int count(String haystack, String needle) {
    int total = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      total++;
    }
    return total;
  }

  private static void assertEquals(int expected, int actual, String message) {
    org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
  }
}
