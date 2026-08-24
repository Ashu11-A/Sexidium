package com.sexidium.core.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guards for the Backup tile — the two ways it is cheap to break and impossible to notice
 * on a Java client.
 *
 * <p>These read the source rather than driving {@code ExperienceMenu}, which is package-private, built
 * by {@code MenuService} and needs the whole server/game/registry graph behind it. The trade is the one
 * {@code ExperienceResetDesignTest} already makes: a harness big enough to click this button would be
 * mostly fixture, while a regression in either invariant below is a build failure here.</p>
 *
 * <p>The invariants: a destructive-or-expensive action confirms with a SECOND TAP, and no menu code
 * anywhere may branch on the click type. Geyser delivers every Bedrock tap as a plain {@code LEFT}
 * click ({@code PaperFormRenderer}), so a shift-click or right-click gate is not "harder on mobile" —
 * it is unreachable, and the button silently does nothing for every phone player on the server.</p>
 */
class ExperienceBackupMenuDesignTest {

  private static final Path SOURCE =
      Path.of("src/main/java/com/sexidium/core/menu/ExperienceMenu.java");

  private static String source() {
    try {
      return Files.readString(SOURCE);
    } catch (IOException fromRepoRoot) {
      // The canonical path, for a run started at the repo root rather than the module root.
      try {
        return Files.readString(Path.of("packages/core").resolve(SOURCE));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + SOURCE, nested);
      }
    }
  }

  @Test
  @DisplayName("the Backup tile arms and confirms through the shared tap-again gesture")
  void backupUsesTheConfirmGesture() {
    String source = source();
    int token = source.indexOf("\"backup:\" + experienceId");
    assertTrue(token > 0, "the Backup tile must carry a confirm token of the form \"backup:<id>\"");

    // The token is an ARGUMENT of confirmButton, so the call opens shortly before it. Anything else --
    // a plain MenuButton.of that happens to mention the token -- is a one-tap destructive-cost action.
    String preceding = source.substring(Math.max(0, token - 400), token);
    assertTrue(preceding.contains("support.confirmButton("),
        "the Backup token must be handed to MenuSupport.confirmButton, which is the only gesture a"
            + " Bedrock player can perform; copying a whole world on a single stray tap is not"
            + " something the owner can take back");
    assertTrue(source.contains("viewer -> menus.openExperienceManage(viewer, experienceId)"),
        "an armed confirm has to re-render its own screen, or the label never flips to 'tap again'");
  }

  @Test
  @DisplayName("no experience screen branches on the click type")
  void nothingBranchesOnTheClickType() {
    String source = source();
    assertFalse(source.contains("clickType()"),
        "Geyser maps every Bedrock tap to ClickType.LEFT, so a right/shift branch is unreachable on"
            + " mobile -- the tile would simply do nothing, with no error to report");
    assertFalse(source.contains("MenuSupport.isShift"), "same reason: shift is not a mobile gesture");
    assertFalse(source.contains("ClickType."), "same reason");
  }

  @Test
  @DisplayName("the answer is reported in the player's language, on their own region, after it lands")
  void theAnswerIsReportedSafely() {
    String source = source();
    int click = source.indexOf("MessageKey.EXPERIENCE_BACKUP_WORKING");
    assertTrue(click > 0, "the click acknowledges immediately: a copy can take a while");
    // Bounded at the confirm button's re-render argument, which is the last thing after the handler.
    int end = source.indexOf("viewer -> menus.openBackups(viewer, experienceId)", click);
    assertTrue(end > click, "the Backup tile must re-render its own screen while arming");
    String handler = source.substring(click, end);

    assertTrue(handler.contains("experienceService.backup("));
    assertTrue(handler.contains("if (!clicker.online())"),
        "the answer can arrive after the player has left, and messaging a ghost throws");
    assertTrue(handler.contains("support.serverAdapter.scheduler().runForPlayer(clicker"),
        "the answer can arrive on the router's recovery tick, which runs on the GLOBAL region --"
            + " opening this player's inventory from there is somebody else's thread on Folia");
    assertTrue(handler.contains("MessageKey.EXPERIENCE_BACKUP_BUSY")
            && handler.contains("MessageKey.EXPERIENCE_BACKUP_LIMIT")
            && handler.contains("MessageKey.EXPERIENCE_BACKUP_QUEUED")
            && handler.contains("MessageKey.EXPERIENCE_BACKUP_FAILED")
            && handler.contains("MessageKey.EXPERIENCE_BACKUP_DONE"),
        "every outcome the owner can act on needs its own sentence; collapsing them loses the only"
            + " part of the answer that tells them what to do next");
    assertFalse(handler.contains("menus.open"),
        "the answer must not OPEN anything. The screen was closed when the copy was fired, and this"
            + " answer can be minutes behind it — the armed lore says in as many words that the owner"
            + " can keep playing meanwhile, so a chest (a Cumulus popup, on Bedrock) appearing over"
            + " whatever they walked off to do is exactly the surprise the message avoids");
  }

  @Test
  @DisplayName("firing the copy closes the screen before the request goes out")
  void firingACopyClosesTheScreen() {
    String source = source();
    int token = source.indexOf("\"backup:\" + experienceId");
    int working = source.indexOf("MessageKey.EXPERIENCE_BACKUP_WORKING", token);
    assertTrue(token > 0 && working > token);
    assertTrue(source.substring(token, working).contains("support.serverAdapter.menus().close("),
        "the screen has to go the moment the copy is fired, the way Enter already does it: a routed"
            + " verb stays in flight until the node holding the folder answers, and until this screen"
            + " is gone every tile on it is still clickable — two more taps queue a second copy on top"
            + " of the one already being taken");
  }

  @Test
  @DisplayName("a backup is never offered a Backup button, and a lost world is not offered one either")
  void aBackupIsNotBackedUp() {
    assertTrue(source().contains("if (!lost && !isBackup) {"),
        "a copy of a copy is indistinguishable in the list, and a lost world's own screen says that"
            + " spectating, renaming and deleting are all that is left of it");
  }

  @Test
  @DisplayName("no Backup tile at all when the cap says backups are switched off")
  void aCapOfZeroOffersNothing() {
    String source = source();
    int guard = source.indexOf("if (room > 0) {");
    assertTrue(guard > 0,
        "max-backups-per-experience: 0 turns backups off entirely, so the tile must not be drawn:"
            + " it used to read '0/0 -- no room, delete a backup first' and, once confirmed, answer"
            + " 'this experience keeps at most 0 backups. Delete one to make room' -- there is no"
            + " backup to delete and nothing the player can do about it");
    assertTrue(guard < source.indexOf("\"backup:\" + experienceId"),
        "the guard has to come BEFORE the tile it suppresses");
  }

  @Test
  @DisplayName("a world's manage screen lists the backups taken of it")
  void theManageScreenListsItsOwnBackups() {
    String source = source();
    assertTrue(source.contains("experienceService.registry().backupsOf(experienceId)"),
        "this screen is the only place a copy is GUARANTEED to be listed -- 'My Experiences' has a"
            + " fixed tile budget and hands it to worlds first -- so the rows have to be read here");
    assertFalse(source.contains("countBackupsOf("),
        "the count on the tile and the rows below it are the same answer; asking the database twice"
            + " for it can also disagree with itself between the two queries");
    // The ROWS themselves moved to the Backups screen (see ExperienceBackupManagerDesignTest); this
    // screen keeps the query because slot 15's doorway states the count, and a count that disagreed
    // with the list under it would be the same bug in a new place.
  }
}
