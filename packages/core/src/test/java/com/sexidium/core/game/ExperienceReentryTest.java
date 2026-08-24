package com.sexidium.core.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the lobby ⇄ worker ping-pong that lives in the experience layer, not the network.
 *
 * <p>The live trace was one player transferred eight times in 45 seconds. The network layer sustained
 * it — an untargeted arrival gate ate the expectation and opened nothing — but the loop CLOSED here,
 * in two places:</p>
 *
 * <ol>
 *   <li>{@code SexidiumCore.resumeTransferredExperience} treated every outcome that was not
 *       {@code ENTERED}/{@code STARTED} as "send them back to the lobby". From the second pass onwards
 *       the reachable outcome is {@code ALREADY_IN_MATCH}, because {@code handleQuit} keeps a player in
 *       the match index for {@code reconnect.timeout-seconds} (120 by default) after they disconnect.
 *       So the arrival path itself guaranteed a bounce, for two minutes, every single time.</li>
 *   <li>{@code PlayerSessionCoordinator.handleChangedWorld} evicted with {@code voluntary=true}, which
 *       also ran {@code forgetRememberedExperience} — deleting the durable {@code experience_players}
 *       pointer, the one piece of state that could have put the player back. A bounce became
 *       unrecoverable rather than merely annoying.</li>
 * </ol>
 *
 * <p>Source-reading, in the idiom of {@code ExperienceResetDesignTest}: the behaviour under test is a
 * control-flow decision inside a class that needs a live server and a live network to instantiate, and
 * a structural guard turns a regression into a build failure rather than a production loop nobody can
 * see from either node's log.</p>
 */
class ExperienceReentryTest {

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

  private static String resumeBody() {
    String core = source("src/main/java/com/sexidium/core/SexidiumCore.java");
    int start = core.indexOf("private void resumeTransferredExperience(");
    assertTrue(start > 0, "resumeTransferredExperience is the arrival half of a transfer");
    String body = core.substring(start);
    int end = body.indexOf("\n  }");
    return end < 0 ? body : body.substring(0, end);
  }

  @Test
  @DisplayName("ALREADY_IN_MATCH requests ZERO transfers — the loop's closing half")
  void alreadyInMatchDoesNotBounce() {
    String body = resumeBody();

    int alreadyInMatch = body.indexOf("case ALREADY_IN_MATCH ->");
    assertTrue(alreadyInMatch > 0,
        "ALREADY_IN_MATCH must be handled explicitly; it is the outcome every pass after the first"
            + " produces, and lumping it into a catch-all bounce is what closed the loop");

    // Everything between this case label and the next one must not route.
    String arm = body.substring(alreadyInMatch);
    int nextCase = arm.indexOf("case ", "case ALREADY_IN_MATCH ->".length());
    int defaultCase = arm.indexOf("default ->");
    int armEnd = Math.min(nextCase < 0 ? arm.length() : nextCase,
        defaultCase < 0 ? arm.length() : defaultCase);
    arm = arm.substring(0, armEnd);

    assertFalse(arm.contains("routeToLobbyNode"),
        "a player who is already in the match they were sent to must STAY. Routing them is the bounce:"
            + " they land on the lobby, the lobby re-runs the entry, and pass N+1 is indistinguishable"
            + " from pass 1.");
  }

  @Test
  @DisplayName("the outcomes that DO bounce tell the player something first")
  void anExplicableRefusalIsExplained() {
    String body = resumeBody();

    assertTrue(body.contains("case HOST_OFFLINE, REQUEST_SENT, NOT_FOUND ->"),
        "these three are real, explicable answers and must be handled as a group");
    int arm = body.indexOf("case HOST_OFFLINE, REQUEST_SENT, NOT_FOUND ->");
    String explained = body.substring(arm, body.indexOf("default ->", arm));
    assertTrue(explained.contains("messages().send("),
        "a silent bounce is exactly what made this loop invisible from inside the game");
    assertTrue(explained.contains("routeToLobbyNode"),
        "...and they do still go back, because a worker has no lobby, no NPC and no menu");
  }

  @Test
  @DisplayName("a failure is SEVERE, not a warning lost in the noise")
  void anUnexplainedFailureIsSevere() {
    String body = resumeBody();
    int fallback = body.indexOf("default ->");
    assertTrue(fallback > 0);
    assertTrue(body.substring(fallback).contains("logger().severe("),
        "FAILED and OFFLINE mean something is wrong with this node, not with the player");
  }

  @Test
  @DisplayName("a success is logged, so an operator can see an arrival land")
  void aSuccessIsLogged() {
    String body = resumeBody();
    int success = body.indexOf("case ENTERED, STARTED ->");
    assertTrue(success > 0);
    assertTrue(body.substring(success, body.indexOf("case ALREADY_IN_MATCH ->")).contains("logger().info("),
        "the destination logged NOTHING on the happy path, which is why eight transfers in 45 seconds"
            + " left no trace on the worker at all");
  }

  @Test
  @DisplayName("an evicting world change is INVOLUNTARY, so the recovery pointer survives")
  void aWorldChangeEvictionKeepsThePointer() {
    String coordinator =
        source("src/main/java/com/sexidium/core/game/PlayerSessionCoordinator.java");
    int start = coordinator.indexOf("void handleChangedWorld(");
    assertTrue(start > 0);
    String body = coordinator.substring(start);
    int end = body.indexOf("\n  }");
    body = end < 0 ? body : body.substring(0, end);

    assertTrue(body.contains("removePlayer(playerAdapter.uniqueId(), false)"),
        "a player teleported out of their match world did not CHOOSE to leave; voluntary=true also"
            + " runs forgetRememberedExperience, which deletes the durable experience_players pointer"
            + " — the one piece of state that could have put them back");
    assertFalse(body.contains("removePlayer(playerAdapter.uniqueId(), true)"));
  }

  @Test
  @DisplayName("/leave and the menu exit stay VOLUNTARY — the pointer should go for those")
  void anExplicitLeaveStillForgets() {
    // The distinction is the whole point: an involuntary removal keeps the pointer because as far as
    // that player knows they are still inside the experience, while a deliberate exit must not drag
    // them back in on their next login.
    String coordinator =
        source("src/main/java/com/sexidium/core/game/PlayerSessionCoordinator.java");
    assertTrue(coordinator.contains("if (voluntary) {")
            && coordinator.contains("forgetRememberedExperience(playerId, activeMatch);"),
        "removePlayer must still forget the pointer when the player chose to go");

    // And the callers that mean "the player chose this" still pass true.
    String manager = source("src/main/java/com/sexidium/core/game/GameManager.java");
    assertTrue(manager.contains("public void removePlayer(UUID playerId, boolean voluntary)"),
        "the caller decides, and /leave and the menu exit both pass true");
  }

  @Test
  @DisplayName("the modes whose world outlives the match keep no reconnect row")
  void persistentWorldModesAreNotPersisted() {
    // A `matches` row is a reconnect snapshot for a DISPOSABLE world. For an experience it was a
    // second, weaker copy of a pointer that already exists in experience_players, and rehydrating it
    // re-opened a shared world folder through a door with no placement check at all.
    assertTrue(MatchLifecycle.isPersistentWorldMode(
        com.sexidium.core.game.experience.ExperienceGame.MODE_ID));
    assertTrue(MatchLifecycle.isPersistentWorldMode(
        com.sexidium.core.game.chaos.ChaosGame.MODE_ID));
    for (String minigame : List.of("tntwar", "combat", "race", "fugitive", "gather")) {
      assertFalse(MatchLifecycle.isPersistentWorldMode(minigame),
          minigame + " runs in a disposable world and DOES still need its reconnect snapshot");
    }
  }

  @Test
  @DisplayName("the rehydration path can no longer open a persistent world")
  void rehydrationCannotOpenAPersistentWorld() {
    String store = source("src/main/java/com/sexidium/core/game/PendingMatchStore.java");

    assertFalse(store.contains("reacquirePersistent"),
        "rehydrate ran on EVERY join, off match rows every node imported from every other node, and"
            + " reacquirePersistent had no placement check and no capability check");
  }
}
