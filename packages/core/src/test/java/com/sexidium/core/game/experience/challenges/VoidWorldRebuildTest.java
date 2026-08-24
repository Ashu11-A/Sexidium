package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.platform.WorldAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mode whose world is nothing but what it builds must build it again when the world is replaced.
 *
 * <h2>The bug this pins</h2>
 * Death Resets throws the world away on every death and hands the run a brand-new one. For a mode with
 * natural terrain that is a fresh start; for a VOID mode it is empty space. Classic Skyblock, Random
 * Skyblock, Random Layers and Layered Dimensions all built their island, block or column from
 * {@link Challenge#onStart} alone, guarded by an "already built" marker — so the first world got a map
 * and every world after it got nothing. Players were teleported into the void, fell, died, and that
 * death triggered the next reset: the mode composed with Death Resets exactly once and then looped.
 *
 * <h2>Why the check is structural</h2>
 * Building a world takes a platform. What can be checked without one is the property that actually
 * failed — that a challenge declaring {@link Challenge#requiresVoidWorld()} also overrides
 * {@link Challenge#onWorldReset}. It is derived from the live catalog rather than a hand-written list,
 * so the fifth void mode somebody adds is covered on the day it is registered rather than on the day
 * somebody remembers this file exists.
 */
class VoidWorldRebuildTest {

  @Test
  void everyVoidWorldChallengeRebuildsIntoARegeneratedWorld() throws NoSuchMethodException {
    List<String> offenders = new ArrayList<>();
    List<String> checked = new ArrayList<>();

    for (ChallengeCatalog.Entry entry : ChallengeCatalog.available()) {
      Challenge challenge = entry.factory().get();
      if (!challenge.requiresVoidWorld()) {
        continue;
      }
      checked.add(entry.id());
      if (declaresOwnWorldReset(challenge)) {
        continue;
      }
      offenders.add(entry.id());
    }

    assertFalse(checked.isEmpty(), "the catalog should still hold void-world challenges to check");
    assertTrue(offenders.isEmpty(), () -> "These challenges generate their own map into a void world but"
        + " do not override onWorldReset, so a Death Resets regeneration leaves them nothing to stand on"
        + " and drops every player into the void: " + offenders);
  }

  /**
   * The Nether half of the same rule. A mode that asks for a void Nether has to re-seed that column too,
   * and the only place it can is {@code onWorldReset} — the replacement's linked dimensions are
   * provisioned during the acquire that runs just before it, so they are there to be built into.
   */
  @Test
  void aVoidNetherIsTheSameObligation() throws NoSuchMethodException {
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.available()) {
      Challenge challenge = entry.factory().get();
      if (!challenge.requiresVoidNether()) {
        continue;
      }
      assertTrue(declaresOwnWorldReset(challenge),
          entry.id() + " asks for a void Nether but never rebuilds one after a regeneration");
    }
  }

  /**
   * Every generated map must reserve the column it drops the player into.
   *
   * <p>These modes compute where to build FROM the world spawn and then trust the player to arrive on it
   * — but those are two separate calculations, and whatever the build places afterwards is free to land
   * on the assumption. Random Layers scattered its starter trees over the whole island including that
   * column, and a trunk's first log sits exactly where the player's feet go, so the draw that hit the
   * centre spawned them inside the tree.</p>
   *
   * <p>{@code reserveStandingSpot} is the fix and this is what keeps it applied: it is one line at the
   * end of a build, which is precisely the kind of line a later edit drops without noticing.</p>
   */
  @Test
  void everyGeneratedMapReservesTheColumnItDropsThePlayerInto() {
    List<String> offenders = new ArrayList<>();
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.available()) {
      Challenge challenge = entry.factory().get();
      if (!challenge.requiresVoidWorld()) {
        continue;
      }
      if (!source(challenge).contains("reserveStandingSpot(")) {
        offenders.add(entry.id());
      }
    }

    assertTrue(offenders.isEmpty(), () -> "These challenges build their own map but never assert that the"
        + " spawn column is standable, so anything they place there arrives on top of the player: "
        + offenders);
  }

  /** The challenge's own source, read from either the repo root or the module root. */
  private static String source(Challenge challenge) {
    Path path = Path.of("src/main/java",
        challenge.getClass().getName().replace('.', '/') + ".java");
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

  /** Whether the challenge declares {@code onWorldReset} itself, rather than inheriting the no-op. */
  private static boolean declaresOwnWorldReset(Challenge challenge) throws NoSuchMethodException {
    return !challenge.getClass()
        .getMethod("onWorldReset", WorldAdapter.class)
        .getDeclaringClass()
        .equals(Challenge.class);
  }
}
