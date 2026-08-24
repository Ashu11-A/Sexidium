package com.sexidium.core.menu;

import com.sexidium.core.game.experience.ExperienceManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What "My Experiences" draws when the owner has more rows than the screen has tiles.
 *
 * <p>The list body is eighteen slots, and the shipped defaults let one player hold ten worlds and three
 * backups of each — forty rows. A copy that takes a tile from the world it was copied FROM does not just
 * hide it: that tile is the only route to entering, renaming or deleting that world, and there is no
 * paging behind it. So the one rule this checks is that a backup never costs a source its place.</p>
 */
class ExperienceMenuTilesTest {

  private static final UUID OWNER = UUID.randomUUID();

  private static ExperienceManager.Experience source(String id) {
    return experience(id, null);
  }

  private static ExperienceManager.Experience backup(String id, String of) {
    return experience(id, of);
  }

  private static ExperienceManager.Experience experience(String id, String backupOf) {
    return new ExperienceManager.Experience(id, OWNER, "Owner", "sx:experience/" + id, id,
        List.of("doubledrops"), false, 1_000L, ExperienceManager.Experience.MODE_EXPERIENCE,
        "normal", true, false, false, backupOf);
  }

  /** Ten worlds, each with the maximum three copies, in the order the registry hands them over. */
  private static List<ExperienceManager.Experience> tenWorldsWithThreeBackupsEach() {
    List<ExperienceManager.Experience> owned = new ArrayList<>();
    for (int world = 0; world < 10; world++) {
      owned.add(source("world" + world));
    }
    for (int world = 0; world < 10; world++) {
      for (int copy = 0; copy < 3; copy++) {
        owned.add(backup("world" + world + "-copy" + copy, "world" + world));
      }
    }
    return owned;
  }

  @Test
  @DisplayName("every world the owner has keeps its tile, however many backups they took")
  void backupsNeverDisplaceAWorld() {
    List<ExperienceManager.Experience> tiles = ExperienceMenu.tilesFor(tenWorldsWithThreeBackupsEach(), 18);

    assertTrue(tiles.size() <= 18, "the list body is 18 slots; the 19th tile would overwrite the footer");
    for (int world = 0; world < 10; world++) {
      String id = "world" + world;
      assertTrue(tiles.stream().anyMatch(tile -> tile.id().equals(id)),
          id + " was pushed off the list by copies, and no other screen can reach it: it can no longer"
              + " be entered, renamed or deleted");
    }
  }

  @Test
  @DisplayName("a surviving backup still sits directly under the world it was copied from")
  void groupingSurvivesTheBudget() {
    List<ExperienceManager.Experience> tiles = ExperienceMenu.tilesFor(tenWorldsWithThreeBackupsEach(), 18);

    String group = null;
    for (ExperienceManager.Experience tile : tiles) {
      if (!tile.isBackup()) {
        group = tile.id();
        continue;
      }
      assertEquals(group, tile.backupOf(),
          tile.id() + " is drawn away from its source; a copy read next to a different world is one"
              + " the owner enters by mistake");
    }
    assertNotNull(group);
  }

  @Test
  @DisplayName("copies are shared out, so one much-copied world cannot spend everybody's leftovers")
  void theLeftoverTilesAreShared() {
    List<ExperienceManager.Experience> tiles = ExperienceMenu.tilesFor(tenWorldsWithThreeBackupsEach(), 18);

    assertEquals(18, tiles.size(), "there are eight tiles left over once the ten worlds are drawn");
    long copies = tiles.stream().filter(ExperienceManager.Experience::isBackup).count();
    assertEquals(8, copies);
    long worldsShowingACopy = tiles.stream().filter(ExperienceManager.Experience::isBackup)
        .map(ExperienceManager.Experience::backupOf).distinct().count();
    assertEquals(8, worldsShowingACopy, "one world took more than its share of the leftover tiles");
  }

  @Test
  @DisplayName("the copy of a world that is kept is the NEWEST one, as on the Backups screen")
  void theCopiesKeptAreTheMostRecentOnes() {
    List<ExperienceManager.Experience> owned = new ArrayList<>();
    owned.add(source("world"));
    // The registry returns backups oldest first, which is the order they are drawn in.
    owned.add(backup("oldest", "world"));
    owned.add(backup("newer", "world"));
    owned.add(backup("newest", "world"));

    // The two screens have to agree on which copy matters. The Backups screen guarantees the newest a
    // slot (newestBackups); this used to throw the newest away FIRST, so for an owner over the tile
    // budget the copy they had just taken was the one guaranteed there and the one dropped here --
    // while the "backup done" message promises it will be in this list.
    assertEquals(List.of("world", "newest"),
        ExperienceMenu.tilesFor(owned, 2).stream().map(ExperienceManager.Experience::id).toList());
  }

  @Test
  @DisplayName("the copy just taken survives both screens, whichever end of the list it is dropped from")
  void theNewestCopyIsOnBothScreens() {
    List<ExperienceManager.Experience> owned = new ArrayList<>();
    for (int world = 0; world < 10; world++) {
      owned.add(source("world" + world));
    }
    for (int world = 0; world < 10; world++) {
      for (int copy = 0; copy < 3; copy++) {
        owned.add(backup("world" + world + "-copy" + copy, "world" + world));
      }
    }
    // Forty rows for eighteen tiles: every group gives copies up, so this is the owner for whom the
    // two screens disagreeing actually costs something.
    List<ExperienceManager.Experience> tiles = ExperienceMenu.tilesFor(owned, 18);

    for (ExperienceManager.Experience tile : tiles) {
      if (!tile.isBackup()) {
        continue;
      }
      assertTrue(tile.id().endsWith("-copy2"),
          tile.id() + " is drawn instead of the newest copy of that world; the Backups screen keeps"
              + " the newest, and a list that keeps a different one leaves the copy the owner just"
              + " took drawn on one screen and dropped from the other");
    }
  }

  @Test
  @DisplayName("with more worlds than tiles the list is cut at the end, exactly as it always was")
  void tooManyWorldsAloneAreTruncated() {
    List<ExperienceManager.Experience> owned = new ArrayList<>();
    for (int world = 0; world < 5; world++) {
      owned.add(source("world" + world));
      owned.add(backup("world" + world + "-copy", "world" + world));
    }

    List<ExperienceManager.Experience> tiles = ExperienceMenu.tilesFor(owned, 3);

    assertEquals(List.of("world0", "world1", "world2"),
        tiles.stream().map(ExperienceManager.Experience::id).toList());
  }

  @Test
  @DisplayName("the Backups screen draws the NEWEST copies, so the one just taken is on it")
  void theBackupsScreenDrawsTheNewestCopies() {
    List<ExperienceManager.Experience> backups = new ArrayList<>();
    for (int copy = 0; copy < 10; copy++) {
      backups.add(backup("copy" + copy, "world"));
    }

    // Seven row slots, ten copies. It used to take the first seven -- the OLDEST, since that is the
    // order the registry hands them over -- so an owner with the cap raised above seven took a copy
    // and it appeared on no screen at all: "My Experiences" spends its tile budget on worlds first.
    assertEquals(List.of("copy3", "copy4", "copy5", "copy6", "copy7", "copy8", "copy9"),
        ExperienceMenu.newestBackups(backups, 7).stream()
            .map(ExperienceManager.Experience::id).toList(),
        "the copy taken most recently has to be one of the seven that are drawn");
  }

  @Test
  @DisplayName("fewer copies than slots are all drawn, in the order the registry hands them over")
  void everyCopyIsDrawnWhileTheyFit() {
    List<ExperienceManager.Experience> backups =
        List.of(backup("oldest", "world"), backup("newest", "world"));

    assertEquals(List.of("oldest", "newest"), ExperienceMenu.newestBackups(backups, 7).stream()
        .map(ExperienceManager.Experience::id).toList());
    assertTrue(ExperienceMenu.newestBackups(backups, 0).isEmpty(),
        "no slots, nothing drawn — and no exception on the way there");
  }

  @Test
  @DisplayName("a copy whose world was deleted is the last one dropped — nothing else lists it")
  void orphanedCopiesAreDroppedLast() {
    List<ExperienceManager.Experience> owned = new ArrayList<>();
    owned.add(source("world"));
    owned.add(backup("copy1", "world"));
    owned.add(backup("copy2", "world"));
    owned.add(backup("orphan", "a-world-since-deleted"));

    List<ExperienceManager.Experience> tiles = ExperienceMenu.tilesFor(owned, 3);

    assertTrue(tiles.stream().anyMatch(tile -> tile.id().equals("orphan")),
        "an orphaned copy has no source manage screen to be listed on, so dropping it from the list"
            + " is the one drop that leaves a world with no route to it at all");
  }
}
