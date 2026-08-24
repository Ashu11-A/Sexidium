package com.sexidium.core.game.experience;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one piece of a player's state that CANNOT live in the world folder: which world folder to open.
 *
 * <p>Everything else a run consists of — inventory, position, the challenge counters — is written by
 * {@link ExperienceStateStore} into the world itself, which is right and is enough now that the folder
 * tree is shared between the workers. It is not enough for the arrival: a player who lands on a worker
 * with no local session has nothing on that node telling anyone where they were, and until this row
 * existed the answer came from a handoff record that only lives for the seconds around a transfer.
 * Miss that window — a reconnect, a node restart, a reset that renamed the world while they were
 * offline — and the player is dropped into the node's default overworld with their run still on disk,
 * a metre away, unreachable.</p>
 *
 * <p>Everything here runs against a real {@link Database} (a temp file), because the failure this
 * table exists to prevent is a persistence failure: an in-memory stand-in would have agreed with the
 * broken version too.</p>
 */
class ExperiencePlayerPointerTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private ExperienceManager experiences;
  private ExperienceManager.Experience experience;
  private final UUID player = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    experiences = new ExperienceManager(SILENT, new Database(new File(tmp.toFile(), "pointer.db")));
    experience = experiences.create(UUID.randomUUID(), "Ashu11a", List.of("deathresets"),
        "Death Resets", System.currentTimeMillis());
    assertNotNull(experience, "the fixture needs a stored experience");
  }

  @Test
  @DisplayName("nothing is remembered about a player who has never entered an experience")
  void anUnknownPlayerHasNoRememberedWorld() {
    assertNull(experiences.rememberedWorldOf(player),
        "an invented answer here would teleport a fresh player into somebody else's world");
    assertNull(experiences.rememberedWorldOf(null), "a null id must not become a query");
  }

  @Test
  @DisplayName("the pointer round-trips through the database: remember, read back, forget")
  void thePointerRoundTrips() {
    experiences.rememberPlayerWorld(experience.id(), player, experience.key(), 1_000L);

    assertEquals(experience.key(), experiences.rememberedWorldOf(player),
        "this key is the only thing that can send an arriving player back to their own world");

    experiences.forgetPlayerWorld(experience.id(), player);
    assertNull(experiences.rememberedWorldOf(player),
        "a player who left must not be dragged back in on their next login");
  }

  @Test
  @DisplayName("re-entering overwrites the pointer instead of stacking a second row")
  void rememberingTwiceKeepsOneAnswer() {
    experiences.rememberPlayerWorld(experience.id(), player, experience.key(), 1_000L);
    experiences.rememberPlayerWorld(experience.id(), player, experience.key().nextGeneration(), 2_000L);

    assertEquals(experience.key().nextGeneration(), experiences.rememberedWorldOf(player),
        "the row states where the player is NOW; two answers is the same as none");
  }

  /**
   * The reset's half of the contract. {@code world_name} moving to the new generation is not enough —
   * the per-player pointers are a second copy of the same fact, one level down, and leaving them
   * behind reproduces the original bug exactly: a player offline across the reset is sent to the world
   * phase D is about to delete.
   */
  @Test
  @DisplayName("a reset re-homes every player of the experience onto the new generation")
  void aResetMovesThePointer() {
    UUID second = UUID.randomUUID();
    experiences.rememberPlayerWorld(experience.id(), player, experience.key(), 1_000L);
    experiences.rememberPlayerWorld(experience.id(), second, experience.key(), 1_000L);

    com.sexidium.core.world.WorldKey regenerated = experience.key().nextGeneration();
    experiences.rehomePlayers(experience.id(), regenerated, 2_000L);

    assertEquals(regenerated, experiences.rememberedWorldOf(player));
    assertEquals(regenerated, experiences.rememberedWorldOf(second),
        "a player OFFLINE across the reset is precisely who this row is for");
  }

  @Test
  @DisplayName("a re-home touches only its own experience's players")
  void aResetDoesNotMovePlayersOfAnotherExperience() {
    ExperienceManager.Experience other = experiences.create(UUID.randomUUID(), "Someone",
        List.of("randomdrops"), "Random Drops", System.currentTimeMillis());
    assertNotNull(other);
    UUID bystander = UUID.randomUUID();
    experiences.rememberPlayerWorld(experience.id(), player, experience.key(), 1_000L);
    experiences.rememberPlayerWorld(other.id(), bystander, other.key(), 1_000L);

    experiences.rehomePlayers(experience.id(), experience.key().nextGeneration(), 2_000L);

    assertEquals(other.key(), experiences.rememberedWorldOf(bystander),
        "a reset in one world must not re-point a player standing in a different one");
  }

  /**
   * Ten resets, the number that actually ran in production before anyone noticed. Each generation is
   * named the way the world layer names it, and the pointer has to name the LIVE one every single time
   * — a pointer that is right for the first reset and stale after the tenth is the bug with a delay.
   */
  @Test
  @DisplayName("the pointer names the living generation after ten consecutive resets")
  void thePointerSurvivesTenResets() {
    com.sexidium.core.world.WorldKey base = experience.key();
    experiences.rememberPlayerWorld(experience.id(), player, base, 1_000L);

    com.sexidium.core.world.WorldKey next = base;
    for (int generation = 1; generation <= 10; generation++) {
      next = next.nextGeneration();
      experiences.updateWorldKey(experience.id(), next, 1_000L + generation);
      experiences.rehomePlayers(experience.id(), next, 1_000L + generation);

      assertEquals(next, experiences.rememberedWorldOf(player),
          "after reset " + generation + " the pointer must name the world that now exists");
      assertEquals(next.key(), experiences.get(experience.id()).worldKey(),
          "and the registry must agree with it, or the two disagree about the same run");
      assertTrue(base.sameRun(next), "every generation stays the same run");
    }

    assertEquals(base.key() + "_r10", experiences.rememberedWorldOf(player).key());
    assertNotEquals(base, experiences.rememberedWorldOf(player),
        "generation 0 was deleted ten resets ago; sending anyone there generates an empty world");
  }

  /**
   * Two players of ONE experience can legitimately be on different generations: the reset re-homes the
   * row, but a player who is in a different experience entirely keeps their own answer. The pointer is
   * per (experience, player) and read most-recent-first, which is what makes "resume where I was" mean
   * the last place the player actually was.
   */
  @Test
  @DisplayName("a player in two experiences resumes the one they were in last")
  void theMostRecentEntryWins() {
    ExperienceManager.Experience other = experiences.create(UUID.randomUUID(), "Ashu11a",
        List.of("randommob"), "Random Mob", System.currentTimeMillis());
    assertNotNull(other);

    experiences.rememberPlayerWorld(experience.id(), player, experience.key(), 1_000L);
    experiences.rememberPlayerWorld(other.id(), player, other.key(), 5_000L);
    assertEquals(other.key(), experiences.rememberedWorldOf(player));

    // Back to the first one; the answer follows the clock, not the insert order.
    experiences.rememberPlayerWorld(experience.id(), player, experience.key(), 9_000L);
    assertEquals(experience.key(), experiences.rememberedWorldOf(player));

    // Leaving the newest one falls back to the other experience the player is still in, rather than
    // to nothing — the pointer is per experience, so forgetting one must not forget both.
    experiences.forgetPlayerWorld(experience.id(), player);
    assertEquals(other.key(), experiences.rememberedWorldOf(player));
  }

  @Test
  @DisplayName("deleting an experience forgets EVERY member's pointer, not just the owner's")
  void deletingAnExperienceForgetsEveryPointer() {
    UUID second = UUID.randomUUID();
    experiences.rememberPlayerWorld(experience.id(), player, experience.key(), 1_000L);
    experiences.rememberPlayerWorld(experience.id(), second, experience.key(), 1_000L);

    // Only the per-player form existed, so a deleted map left every OTHER member holding a durable
    // pointer into a world folder that no longer exists -- read back on their next join.
    experiences.forgetAllPlayers(experience.id());

    assertNull(experiences.rememberedWorldOf(player));
    assertNull(experiences.rememberedWorldOf(second));
    // Idempotent: the request that triggered it can be delivered twice.
    experiences.forgetAllPlayers(experience.id());
    experiences.forgetAllPlayers(null);
  }

  @Test
  @DisplayName("a manager with no database answers instead of throwing")
  void noDatabaseIsALegitimateConfiguration() {
    ExperienceManager none = new ExperienceManager(SILENT, null);
    none.rememberPlayerWorld("id", player, com.sexidium.core.world.WorldKey.parse("w_ab12"), 1L);
    none.rehomePlayers("id", com.sexidium.core.world.WorldKey.parse("w_ab12_r2"), 2L);
    none.forgetPlayerWorld("id", player);
    none.forgetAllPlayers("id");
    assertNull(none.rememberedWorldOf(player),
        "a transient (no-registry) server has nowhere to remember, and must say so quietly");
  }
}
