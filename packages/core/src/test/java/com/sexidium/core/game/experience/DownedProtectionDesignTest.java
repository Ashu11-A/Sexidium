package com.sexidium.core.game.experience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guards for the protection of players nobody is driving.
 *
 * <p>Source-text assertions, for the same reason {@code ExperienceResetDesignTest} uses them:
 * {@link ExperienceGame} is {@code final} and needs the whole manager/context graph, so the invariants
 * that matter here are ones about ORDER inside one method — and order is exactly what a live harness
 * would be least able to pin down and most likely to break silently.</p>
 *
 * <p>Every guard below corresponds to something that actually went wrong, or that would silently undo
 * the fix if a later edit moved it.</p>
 */
class DownedProtectionDesignTest {

  private static final Path PACKAGE_ROOT =
      Path.of("src/main/java/com/sexidium/core/game/experience");

  private static String read(Path path) {
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

  private static String experienceGame() {
    return read(PACKAGE_ROOT.resolve("ExperienceGame.java"));
  }

  /**
   * The file with its comments taken out.
   *
   * <p>Needed because these guards talk about code, and the comments beside that code necessarily quote
   * the very things the guards forbid — a javadoc explaining why {@code setInvulnerable(true)} is the
   * wrong tool would otherwise fail the test that forbids it.</p>
   */
  private static String codeOnly(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
  }

  private static String worldReset() {
    return read(PACKAGE_ROOT.resolve("ExperienceWorldReset.java"));
  }

  @Test
  @DisplayName("the death guard runs before the death is counted or costs the world")
  void deathGuardComesFirst() {
    String source = experienceGame();
    int guard = source.indexOf("playerControl().downed(death.playerAdapter().uniqueId()");
    int counted = source.indexOf("experienceStats.recordRunDeath(");
    // The CALL, not the declaration -- handleHardcoreDeath is defined far earlier in the file.
    int hardcore = source.indexOf("handleHardcoreDeath(death.playerAdapter())");

    assertTrue(guard > 0, "the death guard has to exist");
    assertTrue(counted > 0 && hardcore > 0, "both of the things it guards have to be found");
    assertTrue(guard < counted,
        "a death nobody was present for must not be counted; the guard has to come first");
    assertTrue(guard < hardcore,
        "and it must come before the hardcore branch, which is what destroys the world");
  }

  @Test
  @DisplayName("nothing in the downed path writes the persistent invulnerability flag")
  void neverWritesTheInvulnerabilityFlag() {
    // setInvulnerable(true) is persistent NBT that nothing expires. It is only safe paired with a revoke
    // scheduled at grant time, and this state has NO known duration to schedule against -- it ends when
    // the player acts. A flag written here would be a flag nothing is guaranteed to clear, which is the
    // "players are not taking damage" bug ExperienceWorldReset.grantGrace already documents causing.
    assertFalse(codeOnly(experienceGame()).contains("setInvulnerable(true)"),
        "protection here must be derived per hit, never written onto the player");
  }

  @Test
  @DisplayName("being hit is not treated as evidence that somebody is at the controls")
  void damageIsNotInput() {
    String source = experienceGame();
    int inputBlock = source.indexOf("playerControl().sawInput(");
    assertTrue(inputBlock > 0, "the instant-lift path has to exist");

    String block = codeOnly(source);
    block = block.substring(0, block.indexOf("playerControl().sawInput("));
    block = block.substring(Math.max(0, block.length() - 700));
    assertFalse(block.contains("PlayerDamageGameEvent event -> event.victim()"),
        "counting damage as input would lift the protection at the exact moment it is needed");
  }

  @Test
  @DisplayName("the reset checks for an empty world at BOTH the request and the swap")
  void resetRefusesToRunForNobody() {
    String source = worldReset();
    int reset = source.indexOf("public void reset(");
    int swap = source.indexOf("private void swap()");
    assertTrue(reset > 0 && swap > reset, "both methods have to be found, in that order");

    String resetBody = source.substring(reset, swap);
    String swapBody = source.substring(swap);

    assertTrue(resetBody.contains("game.online().isEmpty()"),
        "a reset asked for with nobody inside should never start");
    // The load-bearing one: everybody can drop DURING the countdown, which is exactly what happened.
    // A check only at the request would have let the swap go through to an empty world, as it did.
    assertTrue(swapBody.contains("game.online().isEmpty()"),
        "and a swap must not hand a fresh world to nobody");
    assertTrue(swapBody.contains("abandonNobodyHere()"),
        "the empty path has to route through the shared abort, so finish(onDone, false) still runs and"
            + " the mode takes its reset counter back");
  }

  @Test
  @DisplayName("the offline carry asks the mode whether contents travel")
  void carryIsDecidedByTheMode() {
    String source = worldReset();
    assertTrue(source.contains("carryPlayerSnapshots(oldWorldName, newWorldName, carry)"),
        "the carry must be told what to do rather than always keeping contents");
    assertTrue(source.contains("HardcoreDeathOutcome.RESET_WORLD"),
        "and the answer has to come from the mode's own death outcome, not a literal");
  }

  @Test
  @DisplayName("a disconnect marks the player on the live path, not only on a restart")
  void disconnectMarksOnTheLivePath() {
    String source = read(Path.of("src/main/java/com/sexidium/core/game/PlayerSessionCoordinator.java"));
    int quit = source.indexOf("void handleQuit(");
    assertTrue(quit > 0);
    String body = source.substring(quit, source.indexOf("\n  }", quit));

    assertTrue(body.contains("markDisconnected"),
        "markDisconnected was dead on this path for its whole life: only the restart rehydration in"
            + " AbstractGame.restore ever called it, so isDisconnected(...) answered false for every"
            + " player who dropped in a live session");
    assertTrue(body.contains("playerControl().markDisconnected"),
        "and the control watch has to hear about it too");
  }
}
