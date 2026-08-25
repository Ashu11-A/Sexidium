package com.sexidium.core.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guards for the two backup screens — the copy list and the copy itself.
 *
 * <p>These read the source rather than driving {@code ExperienceMenu}, which is package-private, built
 * by {@code MenuService} and needs the whole server/game/registry graph behind it. Same trade
 * {@code ExperienceBackupMenuDesignTest} already makes: a harness big enough to click these buttons
 * would be almost entirely fixture, while every invariant below is a build failure here.</p>
 *
 * <p>The invariants are the ones that are cheap to break and impossible to notice on a Java client:
 * the four confirm verbs must all differ (there is exactly <b>one</b> {@code pendingConfirm} slot per
 * player, so a shared token lets arming one verb disarm another <em>across screens</em>), every
 * destructive verb must go through the tap-again gesture, every routed answer must be reported after
 * an {@code online()} check on the clicker's own region, and no new {@code MenuArt.ICON_*} constant
 * may appear — {@code MenuArt.model} validates nothing, so an unregistered constant compiles, passes
 * the whole build and renders as a broken model in game.</p>
 */
class ExperienceBackupManagerDesignTest {

  private static final Path SOURCE =
      Path.of("src/main/java/com/sexidium/core/menu/ExperienceMenu.java");

  private static String source() {
    try {
      return Files.readString(SOURCE);
    } catch (IOException fromRepoRoot) {
      try {
        return Files.readString(Path.of("packages/core").resolve(SOURCE));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + SOURCE, nested);
      }
    }
  }

  @Test
  @DisplayName("both screens exist, and MenuService exposes each of them")
  void bothScreensExist() {
    String source = source();
    assertTrue(source.contains("void openBackups(PlayerAdapter player, String sourceId)"),
        "every copy of ONE world needs a screen of its own: seven of them inline on the manage"
            + " screen left it with nothing free and still could not show what a copy IS");
    assertTrue(source.contains("void openBackup(PlayerAdapter player, String backupId)"),
        "a copy is byte-identical to its source, name included, so it needs a screen that says what"
            + " it is a copy OF before it offers a single verb");

    String menus = menuService();
    assertTrue(menus.contains("public void openBackups(PlayerAdapter player, String sourceId)"),
        "MenuService is the stable facade; a screen with no delegate is unreachable from adapters");
    assertTrue(menus.contains("public void openBackup(PlayerAdapter player, String backupId)"));
  }

  @Test
  @DisplayName("the manage screen's slot 15 is a doorway, and the copy rows are no longer inline")
  void slotFifteenIsADoorway() {
    String source = source();
    assertTrue(source.contains("menus.openBackups(ctx.player(), experienceId)"),
        "slot 15 stops being the confirm tile and becomes the way in to the copies");
    assertFalse(source.contains("int[] freeSlots = {19, 20, 21, 23, 24, 25, 26}"),
        "slots 19-26 are freed: the copy rows live on their own screen now");
    assertTrue(source.contains("if (room > 0 || taken > 0) {"),
        "a cap of 0 must switch off TAKING copies without hiding the ones an owner already has --"
            + " nothing else lists them all, so hiding the doorway would strand every one of them");
  }

  @Test
  @DisplayName("every copy row opens the copy's own screen, wherever it is drawn")
  void copyRowsOpenTheCopyScreen() {
    String source = source();
    assertTrue(source.contains("menus.openBackup(ctx.player(), backup.id())"),
        "one rendering of a copy for both places it appears, and one destination: a tile that led"
            + " somewhere else from the list than from the manage screen is how an owner ends up"
            + " standing in the wrong world");
    assertTrue(source.contains("backupTile(drawn.get(i), experience.displayName())"),
        "the Backups screen draws each copy with its source's name, because the two names are"
            + " byte-identical on the live database and the source is the only thing that differs");
  }

  @Test
  @DisplayName("the four confirm verbs are all distinct, and none of them is the manage screen's delete:")
  void everyConfirmTokenIsDistinct() {
    String source = source();
    // One pendingConfirm slot per player: two tiles sharing a token means arming Restore disarms a
    // half-armed Delete on the screen behind it, and the next tap lands on whichever won.
    Matcher matcher = Pattern.compile("\"([a-z]+):\" \\+ (?:experienceId|backupId|sourceId)")
        .matcher(source);
    List<String> tokens = new ArrayList<>();
    while (matcher.find()) {
      if (!tokens.contains(matcher.group(1))) {
        tokens.add(matcher.group(1));
      }
    }
    assertTrue(tokens.containsAll(List.of("backup", "delete", "restore", "refresh", "duplicate",
            "deletebackup")),
        "expected every backup verb to carry a confirm token of its own; found " + tokens);
    assertEquals(6, tokens.size(),
        "exactly six confirm verbs are expected, all distinct; found " + tokens);
    assertFalse(source.contains("\"delete:\" + backupId"),
        "a copy's Delete must NOT reuse the manage screen's delete: verb -- arming one would disarm"
            + " the other, and the tap that came back would delete the wrong world");
  }

  @Test
  @DisplayName("every destructive or expensive verb arms through the shared tap-again gesture")
  void everyVerbConfirms() {
    String source = source();
    for (String verb : List.of("restore", "refresh", "duplicate", "deletebackup")) {
      int token = source.indexOf("\"" + verb + ":\" + backupId");
      assertTrue(token > 0, "no confirm token for " + verb);
      String preceding = source.substring(Math.max(0, token - 400), token);
      assertTrue(preceding.contains("support.confirmButton("),
          verb + " must be handed to MenuSupport.confirmButton: it is the only gesture a Bedrock"
              + " player can perform, and none of these is something the owner can take back");
    }
  }

  @Test
  @DisplayName("every verb closes the screen before it fires, and reopens nothing when it answers")
  void everyVerbClosesTheScreenAndReopensNothing() {
    String source = source();
    // The screen used to stay open for the whole flight of the request -- which on a routed verb runs
    // until the ACK times out. Restore is a symmetric swap, so the second run UNDOES the first and
    // re-stamps created_at on the row; the "Taken <date>" line an owner picks between identical copies
    // with then lies. Two taps was all it took, on a screen that never went away.
    for (String verb : List.of("RESTORE", "REFRESH", "DUPLICATE", "DELETE", "BACKUP")) {
      String marker = "MessageKey.EXPERIENCE_" + verb + "_WORKING";
      int click = source.indexOf(marker);
      assertTrue(click > 0, verb + " must acknowledge on the click");
      // Every occurrence: DELETE is fired from two screens (a copy's, and the world's own).
      while (click > 0) {
        String fired = source.substring(Math.max(0, click - 400), click);
        assertTrue(fired.contains("support.serverAdapter.menus().close(clicker)"),
            verb + " has to close the menu before it fires, the way Enter already does: until the"
                + " screen is gone, every tile on it is still clickable while the request is in"
                + " flight");
        click = source.indexOf(marker, click + 1);
      }
    }
    // And nothing reopens on the answer. It can arrive on the router's recovery tick, minutes after
    // the click, by which time the owner is walking around a world -- on Bedrock a reopen is a Cumulus
    // popup over the game. MenuSupport.redrawLiveScreens is the one thing that may draw unasked, and
    // only because it checks isOpen() first.
    for (String reopen : List.of("menus.openExperiences(clicker)", "menus.openBackups(clicker,",
        "menus.openBackup(clicker,", "menus.openExperienceManage(clicker,")) {
      assertFalse(source.contains(reopen),
          "no outcome callback may open a screen (" + reopen + "): the menu was closed when the verb"
              + " was fired, and the answer can land long after the owner moved on. The message is the"
              + " whole answer");
    }
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
  @DisplayName("restore, refresh and duplicate each report their answer safely, after it lands")
  void everyAnswerIsReportedSafely() {
    String source = source();
    for (String verb : List.of("RESTORE", "REFRESH", "DUPLICATE")) {
      int click = source.indexOf("MessageKey.EXPERIENCE_" + verb + "_WORKING");
      assertTrue(click > 0, verb + " must acknowledge on the click: the answer can take a while");
      // Bounded at this tile's own re-render argument, the last thing after its handler -- the way
      // firingACopyClosesTheScreen bounds its window too. A fixed character count instead is a window
      // that quietly grows into the NEXT verb's tile whenever a handler shrinks, and then it is the
      // neighbour's online() check satisfying this assertion.
      int end = source.indexOf("viewer -> menus.openBackup(viewer, backupId)", click);
      assertTrue(end > click, verb + " must re-render its own screen while arming");
      String handler = source.substring(click, end);
      assertTrue(handler.contains("if (!clicker.online())"),
          verb + ": the answer can arrive after the player has left, and messaging a ghost throws");
      assertTrue(handler.contains("support.serverAdapter.scheduler().runForPlayer(clicker"),
          verb + ": the answer can arrive on the router's recovery tick, which runs on the GLOBAL"
              + " region -- opening this player's inventory from there is somebody else's thread"
              + " on Folia");
    }
  }

  @Test
  @DisplayName("every outcome switch is exhaustive, with no default to swallow the next constant")
  void noOutcomeSwitchHasADefault() {
    String source = source();
    // The outcome enum is shared by backup, restore, refresh and duplicate on purpose: a twelfth
    // constant then stops the build until every screen has decided what to say about it. A `default`
    // arm anywhere in this file would give that away silently.
    assertFalse(source.contains("default ->"),
        "no switch in ExperienceMenu may carry a `default`: the shared ExperienceBackup.Outcome enum"
            + " is what makes a new constant a COMPILE error rather than a screen that quietly says"
            + " the wrong thing");
    for (String constant : List.of("CREATED", "RESTORED", "REFRESHED", "DUPLICATED", "QUEUED",
        "BUSY", "LIMIT_REACHED", "NOT_OWNER", "GONE", "NO_SPACE", "FAILED")) {
      assertTrue(source.contains(constant + " ->") || source.contains(constant + ","),
          "outcome " + constant + " is not handled anywhere in ExperienceMenu");
    }
  }

  @Test
  @DisplayName("the copy screen states what it is a copy of, and never a run statistic")
  void theIdentityCardIsTruthful() {
    String source = source();
    int card = source.indexOf("void openBackup(PlayerAdapter player, String backupId)");
    assertTrue(card > 0);
    String screen = source.substring(card, Math.min(source.length(), card + 4000));
    assertTrue(screen.contains("MenuButton.label(ItemKey.minecraft(\"paper\")"),
        "the identity card is DECORATIVE: a null onClick is the only signal MenuForms has for"
            + " splitting a Bedrock form's buttons from its body text, so a no-op lambda would turn"
            + " an information panel into a button that lies");
    assertTrue(screen.contains("takenAt(backup.createdAt())"),
        "a copy's display name is byte-identical to its source's on the live database, so WHEN it"
            + " was taken is one of only two things that tell them apart");
    assertFalse(source.contains("challengeState("),
        "the day count and the death count would have to be decoded from experiences.challenge_state,"
            + " which on the live server LAGS the state.yml inside the folder and can be missing a"
            + " challenge's counters entirely -- a wrong number is worse than no number on the one"
            + " screen whose job is telling two identical worlds apart");
    assertFalse(source.contains("loadPlayerState("),
        "experience_players is not copied, so a roster or member list read from it would show every"
            + " copy as empty");
  }

  @Test
  @DisplayName("the tile lore is truthful about what each verb costs")
  void theLoreIsTruthful() {
    String source = source();
    assertTrue(source.contains("A FULL re-copy, not an update"),
        "calling Refresh incremental would be a lie: it re-takes the whole world every time, and the"
            + " copy dominates the wall clock");
    assertTrue(source.contains("Uses one of your <white>\" + slots + \"</white> world slots"),
        "Duplicate is the one verb that spends something the owner can run out of, so the price has"
            + " to be on the tile");
    assertTrue(source.contains("Your current world is kept as a copy — nothing is thrown away."),
        "restore's first promise: the world being replaced is KEPT");
    assertTrue(source.contains("Anyone who joined after this was taken starts with nothing."),
        "a player with no snapshot inside the copy arrives empty-handed; that is correct restore"
            + " semantics and it belongs in the confirm lore verbatim");
    assertTrue(source.contains("Nobody may be inside either world."),
        "BOTH worlds are refused BUSY while loaded, not just the one being restored");
  }

  @Test
  @DisplayName("a lost world, a copy and a FULL one are told WHY, instead of a tile that refuses")
  void takingACopyIsNotOfferedWhereItCannotWork() {
    String source = source();
    int cap = source.indexOf("if (room > 0) {");
    assertTrue(cap > 0, "the cap guard must still be the outer one");
    int refusal = source.indexOf("if (experience.isLost()) {", cap);
    assertTrue(refusal > cap,
        "ExperienceBackupService refuses a lost world and a copy-of-a-copy with LIMIT_REACHED, which"
            + " this menu reports as 'this experience keeps at most N backups. Delete one to make"
            + " room' -- an owner whose hardcore world was lost with ZERO copies is told to delete a"
            + " copy that does not exist");
    int tile = source.indexOf("\"backup:\" + experienceId");
    assertTrue(tile > refusal,
        "the state check has to come BEFORE the confirm tile it replaces, inside the cap guard");

    String branch = source.substring(refusal, tile);
    assertTrue(branch.contains("MenuButton.label("),
        "the refusal is DECORATIVE: a null onClick is the only signal MenuForms has for splitting a"
            + " Bedrock form's buttons from its body text, so a no-op lambda would render as a button"
            + " that does nothing and says nothing");
    assertTrue(branch.contains("lost") || branch.contains("Lost"),
        "the refusal has to say the run is over -- that is the fact the owner is missing");
    assertTrue(branch.contains("} else if (experience.isBackup()) {"),
        "a copy of a copy is the second state the service refuses; it gets its own sentence, not the"
            + " lost world's");
    // The full case used to render the SAME lore ("No room -- delete a backup first") on a tile that
    // was still a live confirmButton: two taps sent the request anyway and the answer came back
    // LIMIT_REACHED. It is a refusal the screen already knows about before the first tap.
    assertTrue(branch.contains("} else if (full) {"),
        "a full experience cannot take another copy, and the screen knows it while it is drawing the"
            + " tile -- arming and confirming a request that is guaranteed to come back LIMIT_REACHED"
            + " is the same bug the lost world had");
    assertFalse(source.contains("full ? \"<red>No room — delete a backup first</red>\""),
        "the live tile must not carry the no-room wording any more: the no-room case never reaches it");
    assertTrue(source.contains("support.trackLive(player, viewer -> menus.openBackups(viewer,"),
        "this screen is redrawn on every EXPERIENCE_UPDATED, including the update that marks the"
            + " world lost while its owner is standing on it -- which is how the tile is reachable"
            + " on a lost world at all");
  }

  @Test
  @DisplayName("deleting a world says what becomes of the copies taken of it")
  void deletingASourceNamesTheCopiesItPromotes() {
    String source = source();
    // promoteOrphanBackups clears backup_of on every copy of a deleted world, and countByOwner counts
    // exactly the rows with backup_of NULL -- so the copies become WORLDS, against the per-player cap.
    // Every other screen says the opposite ("Nothing is ever deleted for you", "The world it was copied
    // from is not touched"); the reverse direction was stated nowhere, and an owner at 9/10 worlds who
    // deletes a world with three copies to make room comes back to 11/10 and a locked list.
    assertTrue(source.contains("List<String> deleteIdle = new ArrayList<>(")
            && source.contains("List<String> deleteArmed = new ArrayList<>("),
        "the world's Delete lore has to be built, not a constant: what it must say depends on how many"
            + " copies exist");
    assertTrue(source.contains("becomes a world of yours"),
        "the lore has to say the copies are PROMOTED, not deleted -- that is the surprise");
    assertTrue(source.contains("against your limit"),
        "and that each of them then counts against the world limit, which is the reason the owner was"
            + " deleting a world in the first place");
    assertTrue(source.contains("deleteArmed.add(0, kept)") && source.contains("deleteIdle.add(0, kept)"),
        "it goes FIRST on BOTH faces: PaperFormRenderer shows a Bedrock player the button's name plus"
            + " lore line 0 and nothing after it, and the idle face is the one the owner reads while"
            + " they are still deciding -- the rule that put it first when armed applies there too");
    int manage = source.indexOf("void openExperienceManage(PlayerAdapter player, String experienceId)");
    assertTrue(manage > 0);
    assertEquals(1, count(source.substring(manage), "registry().backupsOf(experienceId)"),
        "slot 15's count and the Delete lore describe the same set, so this screen reads it ONCE:"
            + " asking the registry twice for the same answer is how the two end up disagreeing");
  }

  @Test
  @DisplayName("a copy is not offered the visibility toggle its source has")
  void aCopyCannotBeMadePublic() {
    String source = source();
    // "More settings" on a copy opens the ordinary manage screen, whose visibility tile was guarded on
    // `lost` only. One tap, no confirm, and the copy is public -- while ExperienceService.browsable
    // skips copies for the FRIENDS half of the browser, so the only people it reaches are strangers,
    // who see it under the same icon and (for copies older than the " (backup)" suffix) the same name
    // as the world it copies.
    int lostBranch = source.indexOf("lockedButton(ItemKey.minecraft(\"gray_dye\"), \"Visibility\"");
    assertTrue(lostBranch > 0, "the lost world's visibility tile is the anchor for this one");
    int backupBranch = source.indexOf("} else if (isBackup) {", lostBranch);
    int toggle = source.indexOf("Click to toggle visibility", lostBranch);
    assertTrue(backupBranch > 0 && backupBranch < toggle,
        "a copy has to be handled BEFORE the live toggle, or it gets the toggle");
    String branch = source.substring(backupBranch, toggle);
    assertTrue(branch.contains("MenuButton.label("),
        "decorative, like the lost world's tile and the refusal on the Backups screen: a null onClick"
            + " is what keeps it body text rather than a button on a Bedrock form");
    assertTrue(branch.contains("cannot be changed"),
        "shown rather than hidden, with the reason on it -- the owner can still read what the copy is");
  }

  @Test
  @DisplayName("the copies drawn are the NEWEST ones, not the oldest seven")
  void theNewestCopiesAreTheOnesDrawn() {
    String source = source();
    // backupsOf hands them over oldest-first and this screen took the first seven, so with the cap
    // raised above seven the copy an owner had just taken was drawn NOWHERE: not here, and not in "My
    // Experiences", which spends its tile budget on worlds first and drops copies.
    assertTrue(source.contains("newestBackups(backups, rowSlots.length)"),
        "the seven row slots go to the seven most recent copies");
    assertFalse(source.contains("oldest are shown here"),
        "and the header has to say so: it used to promise the oldest, which was true and useless");
    assertTrue(source.contains("newest are shown here"),
        "the overflow note names which end of the list is drawn");
  }

  /** How many times {@code needle} occurs in {@code source}. */
  private static int count(String source, String needle) {
    int total = 0;
    for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
      total++;
    }
    return total;
  }

  @Test
  @DisplayName("Rename no longer promises a copy is called exactly what its source is called")
  void theRenameLoreIsTruthfulAboutTheCopysName() {
    String source = source();
    assertFalse(source.contains("It is called exactly what the world it copies is called"),
        "backupDisplayName appends \" (backup)\" and the engine now actually calls it with a null"
            + " name, so every copy taken from now on is named '<source> (backup)'. Only the rows"
            + " written before that fix are byte-identical -- telling an owner otherwise is a lie"
            + " about the one field this screen exists to disambiguate");
    assertTrue(source.contains("(backup)"),
        "the truthful replacement has to name the suffix new copies actually wear; an owner comparing"
            + " two tiles needs to know which naming era each row comes from");
  }

  @Test
  @DisplayName("copies past the seven drawn slots are counted, not silently dropped off the screen")
  void copiesBeyondTheDrawnSlotsAreAccountedFor() {
    String source = source();
    assertTrue(source.contains("int[] rowSlots = {11, 12, 13, 14, 15, 16, 17}"),
        "the copy rows have exactly seven slots on a three-row view");
    assertTrue(source.contains("backups.size() > rowSlots.length"),
        "max-backups-per-experience is configurable and defaults to 3; raise it above seven and the"
            + " eighth copy onward is drawn NOWHERE -- 'My Experiences' spends its own tile budget on"
            + " worlds first and drops copies, so nothing else would ever mention them");
    int overflow = source.indexOf("backups.size() > rowSlots.length");
    String note = source.substring(overflow, Math.min(source.length(), overflow + 800));
    assertTrue(note.contains("/sx admin backup list "),
        "the operator surface that CAN list every copy belongs on the count, or the owner's only"
            + " recourse is to guess");
    assertTrue(note.contains("MenuButton.label(ItemKey.minecraft(\"paper\")"),
        "the overflow note rides the slot-4 header, which must stay a decorative label: a null"
            + " onClick is what keeps it body text rather than a button on a Bedrock form");
  }

  @Test
  @DisplayName("the fact a player must not miss is lore line 0: Bedrock never shows a second line")
  void theLoadBearingLoreLineComesFirst() {
    String source = source();
    // PaperFormRenderer.buttonText builds a Bedrock button as name + lore.get(0) and DROPS the rest,
    // so a price or a warning placed second is not "further down the tooltip" on mobile -- it does not
    // exist. Every clickable tile whose lore states something irreversible or expensive has to lead
    // with it. (Decorative MenuButton.label tiles are exempt: PaperFormRenderer.body joins their whole
    // lore into the form's body text.)
    String duplicate = "private MenuButton duplicateTile(PlayerAdapter player, String backupId) {";
    assertTrue(firstLoreLine(source, duplicate, 0).contains("Uses one of your"),
        "Duplicate is the one verb that spends a world slot, and the idle face has to say so on the"
            + " only line a Geyser player is shown");
    assertTrue(firstLoreLine(source, duplicate, 1).contains("It spends one of your"),
        "the armed face is the last thing before the world is spent; on Bedrock it is one line long,"
            + " and that line cannot be the wall-clock cost while the price is invisible");

    String refresh = "private MenuButton refreshTile(PlayerAdapter player, ExperienceManager.Experience backup,";
    assertTrue(firstLoreLine(source, refresh, 0).contains("A FULL re-copy, not an update"),
        "the word 'Refresh' promises a cheap incremental update; the correction is the whole point of"
            + " the tile and must not sit on a line mobile never renders");
    assertTrue(firstLoreLine(source, refresh, 1)
            .contains("What this copy holds now is replaced and cannot be got back."),
        "Refresh is the verb an owner cannot take back, so the armed face leads with what is lost --"
            + " not with 'nobody may be inside', which only says the attempt might be refused");

    String restore = "private MenuButton restoreTile(PlayerAdapter player, ExperienceManager.Experience backup,";
    assertTrue(firstLoreLine(source, restore, 1)
            .contains("Your current world is kept as a copy — nothing is thrown away."),
        "Restore already leads with its promise; this pins it there, because the fear it answers is"
            + " the reason an owner does not press the button");
  }

  /**
   * The first string literal of the {@code nth} {@code List.of(} lore block inside {@code signature}'s
   * method — i.e. exactly the line a Bedrock player sees under the button's name, and the only one.
   */
  private static String firstLoreLine(String source, String signature, int nth) {
    int method = source.indexOf(signature);
    assertTrue(method > 0, "no method matching " + signature);
    int list = method;
    for (int i = 0; i <= nth; i++) {
      list = source.indexOf("List.of(", list + 1);
      assertTrue(list > 0, signature + " has fewer than " + (nth + 1) + " lore lists");
    }
    int open = source.indexOf('"', list);
    int close = source.indexOf('"', open + 1);
    assertTrue(open > 0 && close > open, "no string literal opens the lore list in " + signature);
    return source.substring(open + 1, close);
  }

  @Test
  @DisplayName("no new MenuArt icon constant: an unregistered one renders broken and nothing catches it")
  void noNewIconConstants() {
    String source = source();
    Matcher matcher = Pattern.compile("MenuArt\\.(ICON_[A-Z_]+)").matcher(source);
    String art = read(Path.of("src/main/java/com/sexidium/core/menu/MenuArt.java"));
    while (matcher.find()) {
      assertTrue(art.contains("String " + matcher.group(1) + " ="),
          matcher.group(1) + " is not declared in MenuArt. MenuArt.model() is string concatenation"
              + " and validates nothing, and no test asserts a constant is registered in"
              + " MenuArtIcons -- so a new one compiles, passes the build and renders broken");
    }
    assertTrue(source.contains("ItemKey.minecraft(\"recovery_compass\"), null,"),
        "Restore ships model-less over a vanilla item, which is the established pattern (the copy"
            + " rows already do) and is what keeps MenuArtAssetsTest and SexidiumResourcePackTest"
            + " from needing a new committed PNG");
  }

  private static String menuService() {
    return read(Path.of("src/main/java/com/sexidium/core/menu/MenuService.java"));
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException fromRepoRoot) {
      try {
        return Files.readString(Path.of("packages/core").resolve(path));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }
}
