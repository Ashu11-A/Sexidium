package com.sexidium.core.game;

import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.game.experience.ExperienceSetup;
import com.sexidium.core.game.experience.UnknownChallengeException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch path may only hand STRIPPED challenge ids to the world-shaping queries.
 *
 * <p>This is a regression control for a live outage. {@code GameLauncher.startExperience} derived
 * {@code challengeIds = modeArgs} — the raw list, which carries the option tokens
 * ({@code world:}/{@code keepinv:}/{@code hardcore:}) alongside the challenge ids — and passed it to
 * {@code forChallenges} and {@code generationFor}. Both funnel into {@code ChallengeCatalog.requireKnown}.</p>
 *
 * <p>While {@code create} merely dropped what it could not read, that was harmless. Once the catalog
 * was made strict — deliberately, because silently dropping a world-shaping challenge generates normal
 * terrain over a void SkyBlock and destroys the save — the very same list became poison: every
 * experience entry died at the click with
 * {@code UnknownChallengeException: ... world:normal, keepinv:true, hardcore:false}, and players could
 * not get from the lobby into a worker at all.</p>
 *
 * <p>The first test states the trap in the small: the raw args are rejected and the stripped ids are
 * accepted. The second pins the call site itself, because that is the line that regressed and nothing
 * else in the suite reaches it — {@code GameLauncher} needs the whole manager/context graph to
 * construct, so the honest guard for one assignment is to read it.</p>
 */
class ExperienceLaunchArgsTest {

  private static final List<String> CHALLENGE_IDS = List.of("doubledrops");

  @Test
  void rawModeArgsAreRejectedByTheWorldShapingQueries() {
    List<String> modeArgs = ExperienceSetup.DEFAULT.toModeArgs(CHALLENGE_IDS);

    // Precondition: the encoder really does mix option tokens into the same list.
    assertTrue(modeArgs.stream().anyMatch(ExperienceSetup::isOption),
        "toModeArgs must carry the option tokens — otherwise this test proves nothing");

    assertThrows(UnknownChallengeException.class,
        () -> ChallengeCatalog.anyRequiresVoidWorld(modeArgs),
        "an option token is not a challenge id; a strict catalog must refuse the raw arg list");
  }

  @Test
  void strippedIdsAreAcceptedByTheWorldShapingQueries() {
    List<String> ids = ExperienceSetup.stripArgs(ExperienceSetup.DEFAULT.toModeArgs(CHALLENGE_IDS));

    assertFalse(ids.stream().anyMatch(ExperienceSetup::isOption),
        "stripArgs must leave only challenge ids");
    assertDoesNotThrow(() -> ChallengeCatalog.anyRequiresVoidWorld(ids));
    assertDoesNotThrow(() -> ChallengeCatalog.hardcoreDemand(ids));
  }

  @Test
  void theLauncherDerivesItsChallengeIdsByStripping() {
    String source = launcherSource();
    assertFalse(source.contains("List<String> challengeIds = modeArgs;"),
        "GameLauncher must not hand the raw mode args to the world-shaping queries: "
            + "the option tokens make requireKnown throw and no experience can be entered");
    assertTrue(source.contains("ExperienceSetup.stripArgs(modeArgs)"),
        "GameLauncher must derive challengeIds through ExperienceSetup.stripArgs");
  }

  /**
   * The SAME rescue must guard the minigame/quick-play path.
   *
   * <p>The first fix landed only on {@code startExperience}. Its sibling {@code start()} reserves the
   * same players for TNT War, Combat, quick-play and the lobby browser, and a throw from
   * {@code worldTemplate()}, {@code acquireReady()} or a synchronous acquire reproduced the incident
   * exactly — the player could not start ANY match again, and {@code state.starting} stayed true for
   * everyone. Fixing one method and not the other is how this bug survives its own postmortem.</p>
   *
   * <p>This replaced an earlier guard that was VACUOUS and green: it took
   * {@code indexOf("state.reservingPlayers.add")}, which finds the FIRST reservation — the unguarded
   * one in {@code start()} — and merely asserted that a {@code catch} appeared later in the file. That
   * is true no matter which method the catch belongs to, so the test pointed straight at the bug and
   * passed. Hence the per-block check below: counting occurrences across the whole file would also
   * pass with both rescues' cleanup crammed into one of them.</p>
   */
  @Test
  void bothLaunchPathsReleaseTheReservationOnAThrow() {
    String source = launcherSource();
    String marker = "catch (RuntimeException failure)";
    int found = 0;
    for (int at = source.indexOf(marker); at >= 0; at = source.indexOf(marker, at + marker.length())) {
      found++;
      // Cada bloco de resgate é conferido por si: contar ocorrências no arquivo inteiro
      // passaria com dois `catch` e a limpeza toda dentro de um só.
      String block = source.substring(at, Math.min(source.length(), at + 900));
      assertTrue(block.contains("state.reservingPlayers.remove"),
          "rescue #" + found + " must release every reserved player");
      assertTrue(block.contains("state.clearStarting()"),
          "rescue #" + found + " must clear the starting flag, or the mode stays wedged for everyone");
    }
    assertEquals(2, found,
        "start() and startExperience() must BOTH release the reservation on a throw");
  }

  private static String launcherSource() {
    Path relative = Path.of("src/main/java/com/sexidium/core/game/GameLauncher.java");
    try {
      return Files.readString(relative);
    } catch (IOException fromRepoRoot) {
      try {
        return Files.readString(Path.of("packages/core").resolve(relative));
      } catch (IOException exception) {
        throw new IllegalStateException("could not read GameLauncher.java", exception);
      }
    }
  }
}
