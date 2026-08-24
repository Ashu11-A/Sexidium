package com.sexidium.core.menu;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MenuAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A screen showing OTHER players' worlds is a photograph of rows those players keep changing.
 *
 * <p>The list itself was never built from a cache — it is a query, every time it opens — but an open
 * menu is drawn once and then never looks again, so a viewer standing in the browser watched an owner
 * rename, un-share or delete a map and saw none of it. On a network the owner is not even on the same
 * node, so nothing local could notice either.</p>
 *
 * <p>What is pinned here is the redraw and, just as importantly, its two refusals: a viewer who has
 * closed the menu is never re-opened into one, and a viewer who has moved on to a different screen
 * keeps the screen they moved to.</p>
 */
class OpenScreenRefreshTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  /** Records what was drawn, and answers whether the viewer is still looking at it. */
  private static final class RecordingMenus implements MenuAdapter {
    private final List<MenuView> drawn = new ArrayList<>();
    private boolean stillOpen = true;

    @Override public void open(PlayerAdapter player, MenuView view) {
      drawn.add(view);
    }

    @Override public boolean isOpen(PlayerAdapter player) {
      return stillOpen;
    }

    MenuView newest() {
      return drawn.get(drawn.size() - 1);
    }

    boolean shows(String text) {
      for (MenuButton button : newest().buttons().values()) {
        if (button.name() != null && button.name().contains(text)) {
          return true;
        }
      }
      return false;
    }
  }

  /** The viewer. Online, and resolvable by id — the redraw looks the player up by uuid. */
  private static final class Viewer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return "Viewer"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return false; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String message) { }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) { }
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) { }
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) { }
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) { }
    @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) { }
    @Override public void showTitle(TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }

  @TempDir
  Path tmp;

  private RecordingMenus drawnMenus;
  private MenuService menus;
  private ExperienceManager experiences;
  private ExperienceService experienceService;
  private ExperienceManager.Experience shared;
  private final Viewer viewer = new Viewer();
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    experiences = new ExperienceManager(SILENT, new Database(new File(tmp.toFile(), "screens.db")));
    shared = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Random Drops",
        System.currentTimeMillis());
    assertNotNull(shared, "the fixture needs a world somebody else owns");
    experiences.setVisibility(shared.id(), true, System.currentTimeMillis());

    drawnMenus = new RecordingMenus();
    TestServerAdapter server = new TestServerAdapter() {
      @Override public MenuAdapter menus() { return drawnMenus; }
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public Optional<PlayerAdapter> player(UUID id) {
        return viewer.uniqueId().equals(id) ? Optional.of(viewer) : Optional.empty();
      }
    };
    experienceService = new ExperienceService(server, null, experiences, null, null);
    menus = new MenuService(server, null, null, null, experienceService, null);
  }

  @Test
  @DisplayName("an open browse list is redrawn with the owner's new name")
  void theBrowseListFollowsTheOwner() {
    menus.openBrowse(viewer);
    assertTrue(drawnMenus.shows("Random Drops"));

    experiences.rename(shared.id(), "Shared Chaos", System.currentTimeMillis());
    menus.refreshExperienceScreens();

    assertEquals(2, drawnMenus.drawn.size(), "the list has to be drawn again to say anything new");
    assertTrue(drawnMenus.shows("Shared Chaos"),
        "the whole bug: a viewer sees the name the map had when they opened the list");
  }

  @Test
  @DisplayName("un-sharing a world takes it out of a list that is already open")
  void unSharingRemovesTheRow() {
    menus.openBrowse(viewer);
    assertTrue(drawnMenus.shows("Random Drops"));

    experiences.setVisibility(shared.id(), false, System.currentTimeMillis());
    menus.refreshExperienceScreens();

    assertTrue(drawnMenus.shows("No friends' or public worlds right now"),
        "a map the owner has taken private must stop being offered, not wait for a reopen");
  }

  @Test
  @DisplayName("a viewer who closed the menu is never re-opened into one")
  void aClosedMenuStaysClosed() {
    menus.openBrowse(viewer);
    drawnMenus.stillOpen = false;

    menus.refreshExperienceScreens();
    menus.refreshExperienceScreens();

    assertEquals(1, drawnMenus.drawn.size(),
        "shoving a chest GUI back into the face of somebody who closed it is worse than a stale list");
  }

  @Test
  @DisplayName("a viewer who moved on to another screen keeps the screen they moved to")
  void anotherScreenIsNotOverwritten() {
    menus.openBrowse(viewer);
    menus.openExperienceBuilder(viewer);

    menus.refreshExperienceScreens();

    assertEquals(2, drawnMenus.drawn.size(),
        "only the tracked screen a viewer is actually looking at may be redrawn");
    assertTrue(drawnMenus.newest().title().contains("Build Experience"));
  }
}
