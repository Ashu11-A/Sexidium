package com.sexidium.core.menu;

import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.game.experience.ExperienceSetup;
import com.sexidium.core.game.experience.ExperienceWorldType;
import com.sexidium.core.game.hardcore.HardcoreDeathOutcome;
import com.sexidium.core.game.hardcore.HardcoreDemand;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The composable-experience screens: the create builder and the edit-challenges grid,
 * the "My Experiences" list, the per-experience manage / rename screens, and the
 * public-experience browser.
 */
public final class ExperienceMenu extends SidebarScreen implements ConfirmableScreen {
  /**
   * One icon means hardcore, everywhere: the tile in "My Experiences", the toggle on the manage screen,
   * the create-builder toggle and the lost-world lock.
   */
  private static final ItemKey HARDCORE_ICON = ItemKey.minecraft("skeleton_skull");
  private final MenuSupport support;
  private final MenuService menus;

  public ExperienceMenu(MenuSupport support, MenuService menuService) {
    super(support, menuService);
    this.support = support;
    this.menus = menuService;
  }

  @Override
  public String title() {
    return "<gold><bold>Experiences</bold></gold>";
  }

  @Override
  public String backgroundArt() {
    return MenuArt.BG_EXPERIENCES;
  }

  @Override
  protected void buildSidebar(MenuView view, PlayerAdapter player) {
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.MY_EXPERIENCES);
  }

  @Override
  protected void buildContent(MenuView view, PlayerAdapter player) {
    openExperiences(player);
  }

  // ==============================================================================================
  // 1. EXPERIENCE BUILDER
  // ==============================================================================================

  /**
   * The composable experience builder: toggle any mix of challenges, pick the map type, then create the
   * experience.
   */
  public void openExperienceBuilder(PlayerAdapter player) {
    ExperienceService experienceService = support.experienceService;
    ServerAdapter serverAdapter = support.serverAdapter;
    Set<String> selected = support.builderSelectionFor(player.uniqueId());
    ExperienceSetup setup = support.builderSetupFor(player.uniqueId());
    ExperienceWorldType worldType = setup.worldType();

    MenuView view = new MenuView("<gold><bold>Build Experience</bold></gold>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_EXPERIENCES);

    fillSeparator(view);

    // Sidebar: Unified navigation rail with CREATE_EXPERIENCE marked active
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.CREATE_EXPERIENCE);

    // Content: Rows 0–3 (28 slots) challenge toggle tiles
    int contentIndex = 0;
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.selectable()) {
      if (contentIndex >= 28) {
        break;
      }
      boolean on = selected.contains(entry.id());
      String name = challengeRowName(entry, on);
      String disabledModel = MenuArt.challengeModelDisabled(entry.id());
      String model = on || disabledModel == null ? MenuArt.challengeModel(entry.id()) : disabledModel;

      int slot = ChestLayout.contentSlot(contentIndex++);
      view.set(slot, MenuButton.of(entry.icon(), name, challengeRowLore(entry, on),
          ctx -> {
            if (!selected.remove(entry.id())) {
              selected.add(entry.id());
            }
            menus.openExperienceBuilder(ctx.player());
          }).withModel(model));
    }


    // Bottom Navigation Row (Row 5, slots 47–53):
    // 1. Slot 47: Previous
    view.set(47, MenuButton.of(ItemKey.minecraft("arrow"),
        "<yellow><bold>← Previous</bold></yellow>",
        List.of("<gray>Return to previous menu</gray>"),
        ctx -> menus.openMain(ctx.player()))
        .withModel(MenuArt.model(MenuArt.ICON_BACK)));

    // 2. Slot 48: Next (pagination)
    view.set(48, MenuButton.of(ItemKey.minecraft("arrow"),
        "<yellow><bold>Next →</bold></yellow>",
        List.of("<gray>Next page</gray>"),
        ctx -> ctx.player().sendActionBar("<gray>You are on page 1 of 1</gray>"))
        .withModel(MenuArt.model(MenuArt.ICON_JOIN)));

    // 3. Slot 49: World Selector
    view.set(49, worldTypeButton(worldType,
        ctx -> menus.openExperienceWorldType(ctx.player(), null)));

    // 4. Slot 50: Keep Inventory
    view.set(50, keepInventoryButton(setup.keepInventory(), ctx -> {
      support.setBuilderKeepInventory(ctx.player().uniqueId(), !setup.keepInventory());
      menus.openExperienceBuilder(ctx.player());
    }));

    // 5. Slot 51: Hardcore
    HardcoreDemand demand = ChallengeCatalog.hardcoreDemand(List.copyOf(selected));
    view.set(51, hardcoreButton(demand.appliesTo(setup.hardcore()), false, demand, ctx -> {
      support.setBuilderHardcore(ctx.player().uniqueId(), !setup.hardcore());
      menus.openExperienceBuilder(ctx.player());
    }));

    // 6. Slot 52: Chaos Mode
    view.set(52, MenuButton.of(ItemKey.minecraft("nether_star"),
        "<light_purple><bold>🎲 Chaos Mode</bold></light_purple>",
        List.of("<gray>Skip picking — get random twists</gray>",
            "<gray>Reshuffled every few minutes</gray>",
            "<yellow>Click to start a Chaos game</yellow>"),
        ctx -> startChaos(ctx.player())));

    // 7. Slot 53: Create Experience (includes active twists in details)
    List<String> createLore = new ArrayList<>();
    createLore.add("<gray>Selected twists: <white>" + selected.size() + "</white></gray>");
    if (!selected.isEmpty()) {
      for (String id : selected) {
        ChallengeCatalog.Entry entry = ChallengeCatalog.get(id);
        if (entry != null) {
          createLore.add("<yellow>• </yellow><white>" + MenuSupport.escape(entry.displayName()) + "</white>");
        }
      }
    }
    createLore.add("<gray>World: <white>" + worldType.displayName() + "</white></gray>");
    createLore.add("<gray>Keep inventory: " + (setup.keepInventory() ? "<green>ON" : "<red>OFF") + "</green></gray>");
    createLore.add(demand.appliesTo(setup.hardcore())
        ? (demand.outcome() == HardcoreDeathOutcome.RESET_WORLD
            ? "<dark_red>HARDCORE — a death replaces the world</dark_red>"
            : "<dark_red>HARDCORE — one death ends it</dark_red>")
        : "<gray>Hardcore: <white>OFF</white></gray>");
    createLore.add("<gray>You always play in survival</gray>");
    createLore.add("<green>Click to create and launch!</green>");

    view.set(53, MenuButton.of(ItemKey.minecraft("lime_concrete"),
        "<green><bold>Create experience</bold></green>",
        createLore,
        ctx -> {
          if (selected.isEmpty() && !worldType.generatesMap()) {
            ctx.player().sendActionBar("<red>Select a challenge or a different world first</red>");
            return;
          }
          List<String> ids = new ArrayList<>(selected);
          support.resetBuilder(ctx.player().uniqueId());
          serverAdapter.menus().close(ctx.player());
          ctx.player().sendActionBar("<yellow>Creating your experience…</yellow>");
          support.announceEnter(ctx.player(),
              experienceService.createAndStart(ctx.player(), ids, setup, support.roster(ctx.player())));
        }).withModel(MenuArt.model(MenuArt.ICON_CREATE)));

    support.open(player, view);
  }

  // ==============================================================================================
  // 2. EXPERIENCE WORLD TYPE
  // ==============================================================================================

  /**
   * The single-choice map-type screen (normal / Nether / End / generated void maps).
   */
  public void openExperienceWorldType(PlayerAdapter player, String experienceId) {
    ExperienceManager.Experience experience = experienceId == null
        ? null : support.experienceService.registry().get(experienceId);
    if (experienceId != null && experience == null) {
      menus.openExperiences(player);
      return;
    }
    if (experience != null && experience.isLost()) {
      lostWorldRefusal(player, experienceId);
      return;
    }
    boolean creating = experience == null;
    ExperienceWorldType current = creating
        ? support.builderWorldTypeFor(player.uniqueId()) : experience.type();

    MenuView view = new MenuView("<aqua><bold>Choose World Type</bold></aqua>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_EXPERIENCES);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail
    SidebarNav.apply(view, player, menus, support,
        creating ? SidebarNav.NavSection.CREATE_EXPERIENCE : SidebarNav.NavSection.MY_EXPERIENCES);

    boolean generationLocked = !creating && current.fixedAtCreation();

    // Content: Row 0 -> Start dimensions (slots 3, 4, 5)
    List<ExperienceWorldType> startDims = ExperienceWorldType.startDimensions();
    for (int i = 0; i < startDims.size() && i < 3; i++) {
      view.set(3 + i, worldTypeChoice(startDims.get(i), current, generationLocked, experienceId));
    }

    // Row 1 -> Terrain presets (slots 12, 13, 14, 15, 16)
    List<ExperienceWorldType> terrainPresets = ExperienceWorldType.terrainPresets();
    for (int i = 0; i < terrainPresets.size() && i < 5; i++) {
      view.set(12 + i, worldTypeChoice(terrainPresets.get(i), current, !creating, experienceId));
    }

    // Row 2 -> Generated maps (slots 21, 22, 23, 24, 25)
    List<ExperienceWorldType> genMaps = ExperienceWorldType.generatedMaps();
    for (int i = 0; i < genMaps.size() && i < 5; i++) {
      view.set(21 + i, worldTypeChoice(genMaps.get(i), current, !creating, experienceId));
    }

    // Bottom Nav: Slot 47 Back
    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> {
      if (creating) {
        menus.openExperienceBuilder(ctx.player());
      } else {
        menus.openExperienceManage(ctx.player(), experienceId);
      }
    }));

    support.open(player, view);
  }

  // ==============================================================================================
  // 3. MY EXPERIENCES LIST
  // ==============================================================================================

  /**
   * 54-slot paginated screen showing owned worlds and backups with persistent global navigation.
   */
  public void openExperiences(PlayerAdapter player) {
    openExperiences(player, 0);
  }

  public void openExperiences(PlayerAdapter player, int page) {
    GameManager gameManager = support.gameManager;
    ExperienceService experienceService = support.experienceService;
    ServerAdapter serverAdapter = support.serverAdapter;

    MenuView view = new MenuView("<yellow><bold>My Experiences</bold></yellow>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_EXPERIENCES);

    fillSeparator(view);

    // Sidebar: Unified navigation rail with MY_EXPERIENCES marked active
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.MY_EXPERIENCES);

    List<ExperienceManager.Experience> owned = experienceService.own(player.uniqueId());
    List<ExperienceManager.Experience> tiles = tilesFor(owned, ChestLayout.CONTENT_CAPACITY);

    int worlds = 0;
    for (ExperienceManager.Experience experience : owned) {
      if (!experience.isBackup()) {
        worlds++;
      }
    }

    int contentIndex = 0;
    for (ExperienceManager.Experience experience : tiles) {
      if (contentIndex >= ChestLayout.CONTENT_CAPACITY) {
        break;
      }
      int slot = ChestLayout.contentSlot(contentIndex++);
      if (experience.isBackup()) {
        view.set(slot, backupTile(experience, owned));
        continue;
      }

      boolean chaos = experience.isChaos();
      boolean hardcore = experience.hardcore();
      boolean lost = experience.isLost();

      ItemKey icon = lost || hardcore ? HARDCORE_ICON
          : (chaos ? ItemKey.minecraft("nether_star") : ItemKey.minecraft("ender_eye"));
      String title = lost
          ? "<red><bold>" + MenuSupport.escape(experience.displayName()) + "</bold></red>"
          : hardcore
              ? "<dark_red><bold>" + MenuSupport.escape(experience.displayName()) + "</bold></dark_red>"
              : (chaos ? "<light_purple><bold>" : "<white><bold>")
                  + MenuSupport.escape(experience.displayName())
                  + (chaos ? "</bold></light_purple>" : "</bold></white>");
      String summary = chaos ? "🎲 Random chaos twists" : MenuSupport.escape(support.challengesSummary(experience));
      List<String> lore = lost
          ? List.of("<red>" + summary + "</red>",
              "<red>You died here. This world is over.</red>",
              "<red>Spectate or delete it.</red>",
              "<yellow>Click to manage</yellow>")
          : List.of(
              chaos ? "<light_purple>🎲 Random chaos twists</light_purple>" : "<gray>" + summary + "</gray>",
              hardcore ? "<dark_red>HARDCORE — one death ends it</dark_red>"
                  : (experience.isPublic() ? "<green>Public</green>" : "<dark_gray>Private</dark_gray>"),
              "<yellow>Click to manage</yellow>");

      view.set(slot, MenuButton.of(icon, title, lore,
          ctx -> menus.openExperienceManage(ctx.player(), experience.id()))
          .withModel(chaos || hardcore || lost ? null : MenuArt.model(MenuArt.ICON_EXPERIENCE_MINE)));
    }

    if (tiles.isEmpty()) {
      view.set(14, MenuButton.label(ItemKey.minecraft("paper"),
          "<gray><bold>No experiences created yet</bold></gray>",
          List.of("<gray>Click <green>'+ Create new experience'</green> below</gray>",
              "<gray>or select it in the sidebar to get started!</gray>")));
    }

    int max = experienceService.maxPerPlayer();
    // Bottom Nav: Slot 47 Back, Slot 53 Create
    view.set(ChestLayout.SLOT_BACK, support.backButton(() -> menus.openMain(player)));

    ActiveMatch current = gameManager.matchOf(player);
    if (current != null) {
      view.set(ChestLayout.SLOT_CHAOS, MenuButton.of(ItemKey.minecraft("barrier"),
          "<red><bold>Leave current experience</bold></red>",
          List.of("<gray>Runs /leave</gray>"),
          ctx -> {
            serverAdapter.menus().close(ctx.player());
            gameManager.removePlayer(ctx.player(), true);
          }).withModel(MenuArt.model(MenuArt.ICON_LEAVE)));
    }

    view.set(ChestLayout.SLOT_PRIMARY, MenuButton.of(ItemKey.minecraft("lime_concrete"),
        "<green><bold>+ Create new experience</bold></green>",
        List.of("<gray>Worlds used: <white>" + worlds + "/" + max + "</white></gray>",
            worlds >= max ? "<red>Limit reached — delete one first</red>" : "<yellow>Click to build a new world</yellow>"),
        ctx -> {
          support.resetBuilder(ctx.player().uniqueId());
          menus.openExperienceBuilder(ctx.player());
        }).withModel(MenuArt.model(MenuArt.ICON_CREATE)));

    support.open(player, view);
    support.trackLive(player, viewer -> menus.openExperiences(viewer));
  }

  // ==============================================================================================
  // 4. BACKUPS (MY BACKUPS SCREEN)
  // ==============================================================================================

  /**
   * Every copy taken of ONE world, on a 54-slot screen of its own — and the tile that takes another.
   */
  void openBackups(PlayerAdapter player, String sourceId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience experience = experienceService.registry().get(sourceId);
    if (experience == null) {
      menus.openExperiences(player);
      return;
    }
    String experienceId = sourceId;
    if (experience.isBackup()) {
      menus.openExperienceManage(player, experienceId);
      return;
    }
    List<ExperienceManager.Experience> backups = experienceService.registry().backupsOf(experienceId);
    int taken = backups.size();
    int room = experienceService.maxBackupsPerExperience();
    boolean full = room > 0 && taken >= room;

    MenuView view = new MenuView("<aqua><bold>Backups · "
        + MenuSupport.escape(experience.displayName()) + "</bold></aqua>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_EXPERIENCES);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.MY_EXPERIENCES);

    int[] rowSlots = {11, 12, 13, 14, 15, 16, 17};
    if (backups.size() > rowSlots.length) {
      view.set(7, MenuButton.label(ItemKey.minecraft("paper"),
          "<yellow>Only the newest are shown here</yellow>",
          List.of("<gray>Older copies are safely archived</gray>",
              "<gray>List all: <white>/sx admin backup list " + MenuSupport.escape(experienceId) + "</white></gray>")));
    }

    // Content: Show newest backups
    List<ExperienceManager.Experience> drawn = newestBackups(backups, rowSlots.length);
    for (int i = 0; i < drawn.size(); i++) {
      view.set(ChestLayout.contentSlot(i), backupTile(drawn.get(i), experience.displayName()));
    }

    if (drawn.isEmpty()) {
      view.set(14, MenuButton.label(ItemKey.minecraft("bundle"),
          "<gray><bold>No Backups Yet</bold></gray>",
          List.of("<gray>Take a snapshot to preserve this world's progress.</gray>",
              "<yellow>Click '+ Take Backup' in the bottom right.</yellow>")));
    }

    // Bottom Nav: Slot 47 Back, Slot 53 Take Backup (if room > 0)
    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      menus.openExperienceManage(ctx.player(), experienceId);
    }));

    if (room > 0 || taken > 0) {
      if (room > 0) {
        if (experience.isLost()) {
          view.set(ChestLayout.SLOT_PRIMARY, MenuButton.label(ItemKey.minecraft("barrier"),
              "<dark_gray><bold>No copy can be taken</bold></dark_gray>",
              List.of("<red>This world is lost — the run is over</red>",
                  "<gray>The copies below are still yours to enter</gray>")));
        } else if (experience.isBackup()) {
          view.set(ChestLayout.SLOT_PRIMARY, MenuButton.label(ItemKey.minecraft("barrier"),
              "<dark_gray><bold>No copy can be taken</bold></dark_gray>",
              List.of("<gray>This is already a copy</gray>",
                  "<gray>A copy of a copy is not offered — duplicate it instead</gray>")));
        } else if (full) {
          view.set(ChestLayout.SLOT_PRIMARY, MenuButton.label(ItemKey.minecraft("barrier"),
              "<dark_gray><bold>No room for another copy</bold></dark_gray>",
              List.of("<red>Kept: <white>" + taken + "/" + room + "</white> — that is the limit</red>",
                  "<gray>Delete one of the copies above to make room</gray>",
                  "<gray>Nothing is ever deleted for you: which one goes is your call</gray>")));
        } else {
          view.set(ChestLayout.SLOT_PRIMARY, support.confirmButton(player, ItemKey.minecraft("bundle"),
              MenuArt.model(MenuArt.ICON_RELOAD), "backup:" + experienceId,
              "<aqua><bold>+ Take a backup now</bold></aqua>",
              List.of("<gray>Takes an exact copy of this world</gray>",
                  "<gray>Terrain, everyone's stuff, every counter</gray>",
                  "<gray>Backups: <white>" + taken + "/" + room + "</white></gray>",
                  "<yellow>Tap, then tap again to confirm</yellow>"),
              "<aqua><bold>⧉ Tap again to back up</bold></aqua>",
              List.of("<gray>This copies the whole world — it can take a moment.</gray>",
                  "<gray>You can keep playing — it is copied where it stands.</gray>",
                  "<yellow>Tap once more to confirm</yellow>"),
              ctx -> {
                PlayerAdapter clicker = ctx.player();
                support.serverAdapter.menus().close(clicker);
                support.serverAdapter.messages().send(clicker,
                    LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_WORKING));
                experienceService.backup(clicker.uniqueId(), experienceId, outcome -> {
                  if (!clicker.online()) {
                    return;
                  }
                  support.serverAdapter.scheduler().runForPlayer(clicker, () -> {
                    if (!clicker.online()) {
                      return;
                    }
                    support.serverAdapter.messages().send(clicker, switch (outcome) {
                      case CREATED -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_DONE);
                      case QUEUED -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_QUEUED);
                      case BUSY -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_BUSY);
                      case LIMIT_REACHED -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_LIMIT,
                          MessageArg.text("max", experienceService.maxBackupsPerExperience()));
                      case NOT_OWNER -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_NOT_OWNER);
                      case NO_SPACE -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_NOSPACE);
                      case RESTORED, REFRESHED, DUPLICATED, GONE, FAILED ->
                          LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_FAILED);
                    });
                  }, null);
                });
              },
              viewer -> menus.openBackups(viewer, experienceId)));
        }
      }
    }

    support.open(player, view);
    support.trackLive(player, viewer -> menus.openBackups(viewer, experienceId));
  }

  // ==============================================================================================
  // 5. INDIVIDUAL BACKUP MANAGE SCREEN
  // ==============================================================================================

  public void openBackup(PlayerAdapter player, String backupId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience backup = experienceService.registry().get(backupId);
    if (backup == null) {
      menus.openExperiences(player);
      return;
    }
    if (!backup.isBackup()) {
      menus.openExperienceManage(player, backupId);
      return;
    }
    String sourceId = backup.backupOf();
    ExperienceManager.Experience source = experienceService.registry().get(sourceId);
    boolean orphan = source == null;

    MenuView view = new MenuView("<aqua><bold>Backup · "
        + MenuSupport.escape(backup.displayName()) + "</bold></aqua>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_EXPERIENCES);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.MY_EXPERIENCES);

    List<String> card = new ArrayList<>();
    card.add(orphan
        ? "<red>Copy of a world you have since deleted</red>"
        : "<aqua>Copy of <white>" + MenuSupport.escape(source.displayName()) + "</white></aqua>");
    card.add("<gray>Taken <white>" + takenAt(backup.createdAt()) + "</white></gray>");
    card.add("<gray>Twists: <white>" + MenuSupport.escape(support.challengesSummary(backup)) + "</white></gray>");
    card.add("<gray>World: <white>" + MenuSupport.escape(backup.type().displayName()) + "</white></gray>");
    card.add(backup.hardcore()
        ? "<dark_red>HARDCORE — one death ends it</dark_red>"
        : "<gray>Hardcore: <white>OFF</white></gray>");
    card.add(backup.isPublic() ? "<green>Public</green>" : "<dark_gray>Private</dark_gray>");

    // Content: Row 0 (Header & Enter)
    view.set(3, MenuButton.label(ItemKey.minecraft("paper"),
        "<aqua><bold>" + MenuSupport.escape(backup.displayName()) + "</bold></aqua>", card));

    view.set(5, MenuButton.of(ItemKey.minecraft("ender_pearl"),
        "<green><bold>Enter Backup</bold></green>",
        List.of("<gray>Open this copy as a world of its own</gray>",
            "<gray>Your live world is untouched</gray>",
            "<yellow>Click to teleport in</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          support.serverAdapter.menus().close(ctx.player());
          ctx.player().sendActionBar("<yellow>Entering…</yellow>");
          support.announceEnter(ctx.player(), experienceService.enter(ctx.player(), backupId));
        }).withModel(MenuArt.model(MenuArt.ICON_ENTER)));

    view.set(7, duplicateTile(player, backupId));

    // Content: Row 1 (Operations)
    if (!orphan) {
      view.set(12, restoreTile(player, backup, source, backupId));
      view.set(14, refreshTile(player, backup, source, backupId));
      view.set(16, MenuButton.of(ItemKey.minecraft("name_tag"),
          "<gold><bold>Rename Backup</bold></gold>",
          List.of("<gray>Give this copy a custom name</gray>",
              "<yellow>Click to choose</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            menus.openExperienceRename(ctx.player(), backupId);
          }).withModel(MenuArt.model(MenuArt.ICON_RENAME)));

      // Content: Row 2 (More Settings)
      view.set(23, MenuButton.of(ItemKey.minecraft("writable_book"),
          "<aqua><bold>More Settings</bold></aqua>",
          List.of("<gray>Visibility, keep-inventory, hardcore, twists</gray>",
              "<yellow>Click to open this copy's full settings</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            menus.openExperienceManage(ctx.player(), backupId);
          }).withModel(MenuArt.model(MenuArt.ICON_EDIT_CHALLENGES)));
    } else {
      view.set(13, MenuButton.of(ItemKey.minecraft("name_tag"),
          "<gold><bold>Rename Backup</bold></gold>",
          List.of("<gray>Give this copy a custom name</gray>",
              "<yellow>Click to choose</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            menus.openExperienceRename(ctx.player(), backupId);
          }).withModel(MenuArt.model(MenuArt.ICON_RENAME)));

      view.set(15, MenuButton.of(ItemKey.minecraft("writable_book"),
          "<aqua><bold>More Settings</bold></aqua>",
          List.of("<gray>Visibility, keep-inventory, hardcore, twists</gray>",
              "<yellow>Click to open this copy's full settings</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            menus.openExperienceManage(ctx.player(), backupId);
          }).withModel(MenuArt.model(MenuArt.ICON_EDIT_CHALLENGES)));
    }

    // Bottom Nav: Slot 47 Back, Slot 53 Delete
    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      if (orphan) {
        menus.openExperiences(ctx.player());
      } else {
        menus.openBackups(ctx.player(), sourceId);
      }
    }));

    view.set(ChestLayout.SLOT_PRIMARY, support.confirmButton(player, ItemKey.minecraft("tnt"),
        MenuArt.model(MenuArt.ICON_DELETE), "deletebackup:" + backupId,
        "<red><bold>Delete this copy</bold></red>",
        List.of("<red>Permanently deletes this copy!</red>",
            "<gray>The world it was copied from is not touched</gray>",
            "<gray>Tap, then tap again to confirm</gray>"),
        "<red><bold>⚠ Tap again to delete</bold></red>",
        List.of("<red>This permanently deletes the copy!</red>",
            "<yellow>Tap once more to confirm</yellow>"),
        ctx -> {
          PlayerAdapter clicker = ctx.player();
          support.serverAdapter.menus().close(clicker);
          support.serverAdapter.messages().send(clicker,
              LocalizedText.of(MessageKey.EXPERIENCE_DELETE_WORKING));
          experienceService.delete(clicker.uniqueId(), backupId, outcome -> {
            if (!clicker.online()) {
              return;
            }
            support.serverAdapter.scheduler().runForPlayer(clicker, () -> {
              if (!clicker.online()) {
                return;
              }
              support.serverAdapter.messages().send(clicker,
                  LocalizedText.of(switch (outcome) {
                    case DELETED -> MessageKey.EXPERIENCE_DELETE_DONE;
                    case QUEUED -> MessageKey.EXPERIENCE_DELETE_QUEUED;
                    case NOT_OWNER -> MessageKey.EXPERIENCE_DELETE_NOT_OWNER;
                    case REFUSED -> MessageKey.EXPERIENCE_DELETE_REFUSED;
                  }));
            }, null);
          });
        },
        viewer -> menus.openBackup(viewer, backupId)));

    support.open(player, view);
    support.trackLive(player, viewer -> menus.openBackup(viewer, backupId));
  }

  private MenuButton restoreTile(PlayerAdapter player, ExperienceManager.Experience backup,
      ExperienceManager.Experience source, String backupId) {
    ExperienceService experienceService = support.experienceService;
    String sourceName = MenuSupport.escape(source.displayName());
    return support.confirmButton(player, ItemKey.minecraft("recovery_compass"), null,
        "restore:" + backupId,
        "<gold><bold>Restore Live World</bold></gold>",
        List.of("<gray>Puts <white>" + sourceName + "</white> back to this copy</gray>",
            "<gray>Taken <white>" + takenAt(backup.createdAt()) + "</white></gray>",
            "<gray>Your current world is kept as a copy</gray>",
            "<yellow>Tap, then tap again to confirm</yellow>"),
        "<gold><bold>⧉ Tap again to restore</bold></gold>",
        List.of("<gray>Your current world is kept as a copy — nothing is thrown away.</gray>",
            "<gray>Anyone who joined after this was taken starts with nothing.</gray>",
            "<gray>Nobody may be inside either world.</gray>",
            "<yellow>Tap once more to confirm</yellow>"),
        ctx -> {
          PlayerAdapter clicker = ctx.player();
          support.serverAdapter.menus().close(clicker);
          support.serverAdapter.messages().send(clicker,
              LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_WORKING));
          experienceService.restore(clicker.uniqueId(), backupId, outcome -> {
            if (!clicker.online()) {
              return;
            }
            support.serverAdapter.scheduler().runForPlayer(clicker, () -> {
              if (!clicker.online()) {
                return;
              }
              support.serverAdapter.messages().send(clicker, switch (outcome) {
                case RESTORED -> LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_DONE);
                case QUEUED -> LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_QUEUED);
                case BUSY -> LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_BUSY);
                case GONE -> LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_GONE);
                case NOT_OWNER -> LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_NOT_OWNER);
                case NO_SPACE -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_NOSPACE);
                case CREATED, REFRESHED, DUPLICATED, LIMIT_REACHED, FAILED ->
                    LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_FAILED);
              });
            }, null);
          });
        },
        viewer -> menus.openBackup(viewer, backupId));
  }

  private MenuButton refreshTile(PlayerAdapter player, ExperienceManager.Experience backup,
      ExperienceManager.Experience source, String backupId) {
    ExperienceService experienceService = support.experienceService;
    String sourceName = MenuSupport.escape(source.displayName());
    return support.confirmButton(player, ItemKey.minecraft("bundle"),
        MenuArt.model(MenuArt.ICON_RELOAD), "refresh:" + backupId,
        "<aqua><bold>Update Snapshot</bold></aqua>",
        List.of("<gray>A FULL re-copy, not an update — it can take a while on a big world</gray>",
            "<gray>Takes this copy again from <white>" + sourceName + "</white></gray>",
            "<gray>This copy is replaced; the count stays the same</gray>",
            "<gray>Currently from <white>" + takenAt(backup.createdAt()) + "</white></gray>",
            "<yellow>Tap, then tap again to confirm</yellow>"),
        "<aqua><bold>⧉ Tap again to refresh</bold></aqua>",
        List.of("<gray>What this copy holds now is replaced and cannot be got back.</gray>",
            "<gray>This copies the whole world again from scratch.</gray>",
            "<gray>Nobody may be inside THIS copy — it is the one being replaced.</gray>",
            "<yellow>Tap once more to confirm</yellow>"),
        ctx -> {
          PlayerAdapter clicker = ctx.player();
          support.serverAdapter.menus().close(clicker);
          support.serverAdapter.messages().send(clicker,
              LocalizedText.of(MessageKey.EXPERIENCE_REFRESH_WORKING));
          experienceService.refresh(clicker.uniqueId(), backupId, outcome -> {
            if (!clicker.online()) {
              return;
            }
            support.serverAdapter.scheduler().runForPlayer(clicker, () -> {
              if (!clicker.online()) {
                return;
              }
              support.serverAdapter.messages().send(clicker, switch (outcome) {
                case REFRESHED -> LocalizedText.of(MessageKey.EXPERIENCE_REFRESH_DONE);
                case QUEUED -> LocalizedText.of(MessageKey.EXPERIENCE_REFRESH_QUEUED);
                case BUSY -> LocalizedText.of(MessageKey.EXPERIENCE_REFRESH_BUSY);
                case GONE -> LocalizedText.of(MessageKey.EXPERIENCE_REFRESH_GONE);
                case NOT_OWNER -> LocalizedText.of(MessageKey.EXPERIENCE_REFRESH_NOT_OWNER);
                case NO_SPACE -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_NOSPACE);
                case CREATED, RESTORED, DUPLICATED, LIMIT_REACHED, FAILED ->
                    LocalizedText.of(MessageKey.EXPERIENCE_REFRESH_FAILED);
              });
            }, null);
          });
        },
        viewer -> menus.openBackup(viewer, backupId));
  }

  private MenuButton duplicateTile(PlayerAdapter player, String backupId) {
    ExperienceService experienceService = support.experienceService;
    int slots = experienceService.maxPerPlayer();
    return support.confirmButton(player, ItemKey.minecraft("lime_concrete"),
        MenuArt.model(MenuArt.ICON_CREATE), "duplicate:" + backupId,
        "<green><bold>Duplicate as New World</bold></green>",
        List.of("<gray>Uses one of your <white>" + slots + "</white> world slots</gray>",
            "<gray>Makes a new world you can play, from this copy</gray>",
            "<gray>The new world is not a backup — it is yours to keep</gray>",
            "<yellow>Tap, then tap again to confirm</yellow>"),
        "<green><bold>⧉ Tap again to duplicate</bold></green>",
        List.of("<gray>It spends one of your " + slots + " world slots.</gray>",
            "<gray>This copies the whole world — it can take a moment.</gray>",
            "<gray>People may stay inside it; it is copied where it stands.</gray>",
            "<yellow>Tap once more to confirm</yellow>"),
        ctx -> {
          PlayerAdapter clicker = ctx.player();
          support.serverAdapter.menus().close(clicker);
          support.serverAdapter.messages().send(clicker,
              LocalizedText.of(MessageKey.EXPERIENCE_DUPLICATE_WORKING));
          experienceService.duplicate(clicker.uniqueId(), backupId, outcome -> {
            if (!clicker.online()) {
              return;
            }
            support.serverAdapter.scheduler().runForPlayer(clicker, () -> {
              if (!clicker.online()) {
                return;
              }
              support.serverAdapter.messages().send(clicker, switch (outcome) {
                case DUPLICATED -> LocalizedText.of(MessageKey.EXPERIENCE_DUPLICATE_DONE);
                case QUEUED -> LocalizedText.of(MessageKey.EXPERIENCE_DUPLICATE_QUEUED);
                case BUSY -> LocalizedText.of(MessageKey.EXPERIENCE_DUPLICATE_BUSY);
                case LIMIT_REACHED -> LocalizedText.of(MessageKey.EXPERIENCE_DUPLICATE_LIMIT,
                    MessageArg.text("max", experienceService.maxPerPlayer()));
                case NOT_OWNER -> LocalizedText.of(MessageKey.EXPERIENCE_DUPLICATE_NOT_OWNER);
                case NO_SPACE -> LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_NOSPACE);
                case CREATED, RESTORED, REFRESHED, GONE, FAILED ->
                    LocalizedText.of(MessageKey.EXPERIENCE_DUPLICATE_FAILED);
              });
            }, null);
          });
        },
        viewer -> menus.openBackup(viewer, backupId));
  }

  // ==============================================================================================
  // 6. EXPERIENCE MANAGE
  // ==============================================================================================

  public void openExperienceManage(PlayerAdapter player, String experienceId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience experience = experienceService.registry().get(experienceId);
    if (experience == null) {
      menus.openExperiences(player);
      return;
    }
    boolean lost = experience.isLost();
    boolean isBackup = experience.isBackup();

    MenuView view = new MenuView((lost ? "<red><bold>" : isBackup ? "<aqua><bold>" : "<yellow><bold>")
        + (isBackup ? "Backup · " : "")
        + MenuSupport.escape(experience.displayName())
        + (lost ? "</bold></red>" : isBackup ? "</bold></aqua>" : "</bold></yellow>"), ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_EXPERIENCES);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail with MY_EXPERIENCES marked active
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.MY_EXPERIENCES);

    // Backups doorway & Delete handling
    List<ExperienceManager.Experience> backups = isBackup
        ? List.of() : experienceService.registry().backupsOf(experienceId);

    // Content: Row 0 -> Header & Quick Action (slots 3, 5, 7)
    List<String> infoLore = new ArrayList<>();
    infoLore.add("<gray>World: <white>" + MenuSupport.escape(experience.type().displayName()) + "</white></gray>");
    infoLore.add("<gray>Twists: <white>" + MenuSupport.escape(support.challengesSummary(experience)) + "</white></gray>");
    infoLore.add("<gray>Keep Inventory: <white>" + (experience.keepInventory() ? "ON" : "OFF") + "</white></gray>");
    infoLore.add("<gray>Visibility: <white>" + (experience.isPublic() ? "Public" : "Private") + "</white></gray>");
    infoLore.add("<gray>Hardcore: <white>" + (experience.hardcore() ? "ON" : "OFF") + "</white></gray>");
    if (lost) {
      infoLore.add("<red><bold>Status: World Lost (Death occurred)</bold></red>");
    }

    view.set(3, MenuButton.label(ItemKey.minecraft("paper"),
        (lost ? "<red><bold>" : "<yellow><bold>") + MenuSupport.escape(experience.displayName()) + (lost ? "</bold></red>" : "</bold></yellow>"),
        infoLore));

    view.set(5, MenuButton.of(ItemKey.minecraft("ender_pearl"),
        lost ? "<gray><bold>Spectate</bold></gray>" : "<green><bold>▶ Enter World</bold></green>",
        lost
            ? List.of("<red>This world was lost.</red>", "<gray>You can only look around it</gray>",
                "<yellow>Click to spectate</yellow>")
            : List.of("<gray>Open this experience</gray>", "<yellow>Click to teleport in</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          support.serverAdapter.menus().close(ctx.player());
          ctx.player().sendActionBar(lost ? "<gray>Entering as a spectator…</gray>" : "<yellow>Entering…</yellow>");
          support.announceEnter(ctx.player(), experienceService.enter(ctx.player(), experienceId));
        }).withModel(MenuArt.model(MenuArt.ICON_ENTER)));

    if (!lost && !isBackup) {
      int taken = backups.size();
      int room = experienceService.maxBackupsPerExperience();
      view.set(7, MenuButton.of(ItemKey.minecraft("bundle"), "<aqua><bold>Backups</bold></aqua>",
          List.of("<gray>Kept: <white>" + taken + "/" + room + "</white></gray>",
              "<gray>Exact snapshots of this world</gray>",
              "<yellow>Tap to manage backups</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            menus.openBackups(ctx.player(), experienceId);
          }));
    }

    // Content: Row 1 -> Settings Toggles (slots 12, 13, 15, 16)
    if (lost) {
      view.set(12, lockedButton(ItemKey.minecraft("compass"), "World Type", experience.type().displayName()));
      view.set(13, lockedButton(ItemKey.minecraft("chest"), "Keep inventory", experience.keepInventory() ? "ON" : "OFF"));
    } else {
      view.set(12, worldTypeButton(experience.type(), ctx -> {
        support.clearConfirm(ctx.player().uniqueId());
        menus.openExperienceWorldType(ctx.player(), experienceId);
      }));

      view.set(13, keepInventoryButton(experience.keepInventory(), ctx -> {
        support.clearConfirm(ctx.player().uniqueId());
        boolean enabled = !experience.keepInventory();
        boolean ok = experienceService.setKeepInventory(ctx.player().uniqueId(), experienceId, enabled);
        ctx.player().sendActionBar(ok
            ? (enabled ? "<green>Deaths now keep your items.</green>" : "<yellow>Deaths now drop your items.</yellow>")
            : "<red>You do not own that experience.</red>");
        menus.openExperienceManage(ctx.player(), experienceId);
      }));
    }

    if (lost) {
      view.set(15, lockedButton(ItemKey.minecraft("gray_dye"), "Visibility",
          experience.isPublic() ? "Public" : "Private"));
    } else if (isBackup) {
      view.set(15, MenuButton.label(
          experience.isPublic() ? ItemKey.minecraft("lime_dye") : ItemKey.minecraft("gray_dye"),
          "<dark_gray><bold>Visibility: " + (experience.isPublic() ? "Public" : "Private")
              + "</bold></dark_gray>",
          List.of("<gray>A copy's visibility cannot be changed</gray>",
              "<gray>The browser is not meant to list copies — this one is named and pictured like"
                  + " the world it was taken from</gray>",
              "<gray>Duplicate it first: the new world is yours to share</gray>")));
    } else {
      view.set(15, MenuButton.of(
          experience.isPublic() ? ItemKey.minecraft("lime_dye") : ItemKey.minecraft("gray_dye"),
          experience.isPublic() ? "<green><bold>Public</bold></green>" : "<dark_gray><bold>Private</bold></dark_gray>",
          List.of(experience.isPublic()
                  ? "<green>Anyone can find and join this world</green>"
                  : "<gray>Only you, friends and party can join</gray>",
              "<yellow>Click to toggle visibility</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            boolean nowPublic = !experience.isPublic();
            experienceService.setVisibility(ctx.player().uniqueId(), experienceId, nowPublic);
            ctx.player().sendActionBar(nowPublic
                ? "<green>Experience is now public — others can join it.</green>"
                : "<gray>Experience is now private.</gray>");
            menus.openExperienceManage(ctx.player(), experienceId);
          }).withModel(MenuArt.model(experience.isPublic() ? MenuArt.ICON_PUBLIC : MenuArt.ICON_PRIVATE)));
    }

    HardcoreDemand manageDemand = ChallengeCatalog.hardcoreDemand(experience.challenges());
    view.set(16, hardcoreButton(manageDemand.appliesTo(experience.hardcore()), lost, manageDemand, ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      boolean enabled = !experience.hardcore();
      boolean ok = experienceService.setHardcore(ctx.player().uniqueId(), experienceId, enabled);
      ctx.player().sendActionBar(ok
          ? (enabled ? "<dark_red>Hardcore ON — one death ends this world.</dark_red>"
              : "<green>Hardcore off. Deaths are survivable again.</green>")
          : "<red>You cannot change that.</red>");
      menus.openExperienceManage(ctx.player(), experienceId);
    }));

    // Content: Row 2 -> Configuration & Tools (slots 22, 24)
    if (lost) {
      view.set(22, lockedButton(ItemKey.minecraft("writable_book"), "Challenges",
          MenuSupport.escape(support.challengesSummary(experience))));
    } else if (experience.isChaos()) {
      view.set(22, MenuButton.label(ItemKey.minecraft("nether_star"),
          "<light_purple><bold>Chaos twists</bold></light_purple>",
          List.of("<gray>Random twists, reshuffled often</gray>", "<dark_gray>Not editable</dark_gray>")));
    } else {
      view.set(22, MenuButton.of(ItemKey.minecraft("writable_book"),
          "<aqua><bold>Edit Challenges</bold></aqua>",
          List.of("<gray>" + MenuSupport.escape(support.challengesSummary(experience)) + "</gray>",
              "<yellow>Click to edit active twists</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            menus.openExperienceEdit(ctx.player(), experienceId);
          }).withModel(MenuArt.model(MenuArt.ICON_EDIT_CHALLENGES)));
    }

    view.set(24, MenuButton.of(ItemKey.minecraft("name_tag"), "<gold><bold>Rename World</bold></gold>",
        List.of("<gray>Pick a new name for this world</gray>", "<yellow>Click to choose</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          menus.openExperienceRename(ctx.player(), experienceId);
        }).withModel(MenuArt.model(MenuArt.ICON_RENAME)));

    // Row 5: Back at 47, Delete at 53
    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      menus.openExperiences(ctx.player());
    }));

    // Delete Button
    List<String> deleteIdle = new ArrayList<>(List.of("<red>Permanently deletes this map!</red>"));
    List<String> deleteArmed = new ArrayList<>(List.of("<red>This permanently deletes the world!</red>"));
    if (!backups.isEmpty()) {
      String kept = "<yellow>Its <white>" + backups.size() + "</white> "
          + (backups.size() == 1 ? "copy is kept and becomes a world of yours"
              : "copies are kept and each becomes a world of yours")
          + " — <white>" + backups.size() + "</white> more against your limit</yellow>";
      deleteIdle.add(0, kept);
      deleteArmed.add(0, kept);
    }
    deleteIdle.add("<gray>Tap, then tap again to confirm</gray>");
    deleteArmed.add("<yellow>Tap once more to confirm</yellow>");

    view.set(ChestLayout.SLOT_PRIMARY, support.confirmButton(player, ItemKey.minecraft("tnt"), MenuArt.model(MenuArt.ICON_DELETE), "delete:" + experienceId,
        "<red><bold>Delete</bold></red>",
        deleteIdle,
        "<red><bold>⚠ Tap again to delete</bold></red>",
        deleteArmed,
        ctx -> {
          PlayerAdapter clicker = ctx.player();
          support.serverAdapter.menus().close(clicker);
          support.serverAdapter.messages().send(clicker,
              LocalizedText.of(MessageKey.EXPERIENCE_DELETE_WORKING));
          experienceService.delete(clicker.uniqueId(), experienceId, outcome -> {
            if (!clicker.online()) {
              return;
            }
            support.serverAdapter.scheduler().runForPlayer(clicker, () -> {
              if (!clicker.online()) {
                return;
              }
              support.serverAdapter.messages().send(clicker,
                  LocalizedText.of(switch (outcome) {
                    case DELETED -> MessageKey.EXPERIENCE_DELETE_DONE;
                    case QUEUED -> MessageKey.EXPERIENCE_DELETE_QUEUED;
                    case NOT_OWNER -> MessageKey.EXPERIENCE_DELETE_NOT_OWNER;
                    case REFUSED -> MessageKey.EXPERIENCE_DELETE_REFUSED;
                  }));
            }, null);
          });
        },
        viewer -> menus.openExperienceManage(viewer, experienceId)));

    support.open(player, view);
    support.trackLive(player, viewer -> menus.openExperienceManage(viewer, experienceId));
  }

  // ==============================================================================================
  // 7. EXPERIENCE RENAME
  // ==============================================================================================

  public void openExperienceRename(PlayerAdapter player, String experienceId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience experience = experienceService.registry().get(experienceId);
    if (experience == null) {
      menus.openExperiences(player);
      return;
    }
    String currentName = experience.displayName();

    MenuView view = new MenuView("<gold><bold>Rename Experience</bold></gold>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_EXPERIENCES);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.MY_EXPERIENCES);

    // Content: Row 0 Header Plaque (slot 5)
    view.set(5, MenuButton.label(ItemKey.minecraft("name_tag"),
        "<white><bold>Current Name</bold></white>",
        List.of("<yellow>" + MenuSupport.escape(currentName) + "</yellow>",
            "<gray>Click any card below to rename immediately</gray>")));

    List<String> suggestions = renameSuggestions(player, experience);

    // Rows 1 & 2: Centered suggestion cards (slots 12..16 and 21..25)
    int[] suggestionSlots = {12, 13, 14, 15, 16, 21, 22, 23, 24, 25};
    for (int i = 0; i < suggestions.size() && i < suggestionSlots.length; i++) {
      String suggestion = suggestions.get(i);
      int slot = suggestionSlots[i];
      view.set(slot, MenuButton.of(ItemKey.minecraft("paper"),
          "<white><bold>" + MenuSupport.escape(suggestion) + "</bold></white>",
          List.of("<yellow>Click to rename to this</yellow>"),
          ctx -> {
            experienceService.rename(ctx.player().uniqueId(), experienceId, suggestion);
            ctx.player().sendActionBar("<green>Renamed to " + MenuSupport.escape(suggestion) + ".</green>");
            menus.openExperienceManage(ctx.player(), experienceId);
          }));
    }

    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> menus.openExperienceManage(ctx.player(), experienceId)));
    support.open(player, view);
  }

  private List<String> renameSuggestions(PlayerAdapter player, ExperienceManager.Experience experience) {
    List<String> names = new ArrayList<>();
    String owner = player.name();
    String challenges = ExperienceService.displayNameFor(experience.challenges());
    String firstChallenge = experience.challenges().isEmpty()
        ? "Survival" : ChallengeCatalog.displayNameFor(experience.challenges().get(0));
    addUnique(names, challenges);
    addUnique(names, owner + "'s World");
    addUnique(names, owner + "'s " + firstChallenge);
    addUnique(names, firstChallenge + " Madness");
    addUnique(names, owner + "'s Adventure");
    addUnique(names, "Survival Odyssey");
    addUnique(names, "Chaos Realm");
    addUnique(names, "Ultimate Challenge");
    addUnique(names, "Hardcore Run");
    addUnique(names, "Sky Kingdom");
    return names;
  }

  private static void addUnique(List<String> names, String candidate) {
    if (candidate != null && !candidate.isBlank() && !names.contains(candidate)) {
      names.add(candidate);
    }
  }

  // ==============================================================================================
  // 8. EDIT CHALLENGES
  // ==============================================================================================

  public void openExperienceEdit(PlayerAdapter player, String experienceId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience experience = experienceService.registry().get(experienceId);
    if (experience == null || experience.isLost() || experience.isChaos()) {
      menus.openExperiences(player);
      return;
    }

    Set<String> selected = support.builderSelectionFor(player.uniqueId());
    if (!experienceId.equals(support.editingExperience(player.uniqueId()))) {
      selected.clear();
      for (String id : experience.challenges()) {
        if (!ChallengeCatalog.isMapChallenge(id)) {
          selected.add(id);
        }
      }
      support.setEditingExperience(player.uniqueId(), experienceId);
    }

    MenuView view = new MenuView("<aqua><bold>Edit Challenges</bold></aqua>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_EXPERIENCES);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.MY_EXPERIENCES);

    // Content: 28 challenge toggle cards
    int cIdx = 0;
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.selectable()) {
      if (cIdx >= 28) break;
      boolean on = selected.contains(entry.id());
      String name = challengeRowName(entry, on);
      String disabledModel = MenuArt.challengeModelDisabled(entry.id());
      String model = on || disabledModel == null ? MenuArt.challengeModel(entry.id()) : disabledModel;

      int slot = ChestLayout.contentSlot(cIdx++);
      view.set(slot, MenuButton.of(entry.icon(), name, challengeRowLore(entry, on),
          ctx -> {
            if (!selected.remove(entry.id())) {
              selected.add(entry.id());
            }
            menus.openExperienceEdit(ctx.player(), experienceId);
          }).withModel(model));
    }

    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> {
      support.clearEditingExperience(ctx.player().uniqueId());
      menus.openExperienceManage(ctx.player(), experienceId);
    }));

    view.set(ChestLayout.SLOT_CHAOS, MenuButton.of(ItemKey.minecraft("barrier"),
        "<red><bold>Clear All Twists</bold></red>",
        List.of("<gray>Deselect all challenges</gray>"),
        ctx -> {
          selected.clear();
          menus.openExperienceEdit(ctx.player(), experienceId);
        }));

    view.set(ChestLayout.SLOT_PRIMARY, MenuButton.of(ItemKey.minecraft("emerald_block"),
        "<green><bold>✔ Confirm Changes</bold></green>",
        List.of("<gray>Selected: <white>" + selected.size() + " challenges</white></gray>",
            "<yellow>Click to apply changes</yellow>"),
        ctx -> {
          if (selected.isEmpty()) {
            ctx.player().sendActionBar("<red>Keep at least one challenge</red>");
            return;
          }
          experienceService.updateChallengesLive(ctx.player().uniqueId(), experienceId, List.copyOf(selected));
          support.clearEditingExperience(ctx.player().uniqueId());
          ctx.player().sendActionBar("<green>Updated challenges for " + MenuSupport.escape(experience.displayName()) + ".</green>");
          menus.openExperienceManage(ctx.player(), experienceId);
        }));

    support.open(player, view);
  }

  // ==============================================================================================
  // 9. BROWSE WORLDS
  // ==============================================================================================

  public void openBrowse(PlayerAdapter player) {
    openBrowse(player, 0);
  }

  public void openBrowse(PlayerAdapter player, int page) {
    ExperienceService experienceService = support.experienceService;
    List<ExperienceManager.Experience> worlds = experienceService != null
        ? experienceService.browsable(player.uniqueId()) : List.of();

    MenuView view = new MenuView("<blue><bold>Browse Worlds</bold></blue>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_BROWSE);

    fillSeparator(view);

    // Sidebar: Unified navigation rail with BROWSE_WORLDS marked active
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.BROWSE_WORLDS);

    int contentIdx = 0;
    for (ExperienceManager.Experience exp : worlds) {
      if (contentIdx >= ChestLayout.CONTENT_CAPACITY) break;
      int slot = ChestLayout.contentSlot(contentIdx++);

      boolean friendWorld = experienceService != null && experienceService.areFriends(player.uniqueId(), exp.owner());
      view.set(slot, MenuButton.of(
          exp.isChaos() ? ItemKey.minecraft("nether_star") : ItemKey.minecraft("ender_eye"),
          "<white><bold>" + MenuSupport.escape(exp.displayName()) + "</bold></white>",
          List.of("<gray>by <white>" + MenuSupport.escape(exp.ownerName()) + "</white></gray>",
              friendWorld ? "<green>Friend's world</green>" : "<aqua>Public</aqua>",
              "<gray>" + MenuSupport.escape(support.challengesSummary(exp)) + "</gray>",
              "<yellow>Click to join</yellow>"),
          ctx -> {
            support.serverAdapter.menus().close(ctx.player());
            support.announceEnter(ctx.player(), experienceService.enter(ctx.player(), exp.id()));
          }).withModel(MenuArt.model(MenuArt.ICON_JOIN)));
    }

    if (worlds.isEmpty()) {
      view.set(ChestLayout.contentSlot(17), MenuButton.label(ItemKey.minecraft("paper"),
          "<gray><bold>No friends' or public worlds right now</bold></gray>",
          List.of("<gray>Create your own and set it to Public</gray>",
              "<gray>or ask friends to share their worlds!</gray>")));
    }

    view.set(ChestLayout.SLOT_BACK, support.backButton(() -> menus.openMain(player)));
    view.set(ChestLayout.SLOT_PRIMARY, MenuButton.of(ItemKey.minecraft("compass"),
        "<aqua><bold>⟳ Refresh Worlds</bold></aqua>",
        List.of("<gray>Scan for new open experiences</gray>"),
        ctx -> menus.openBrowse(ctx.player()))
        .withModel(MenuArt.model(MenuArt.ICON_RELOAD)));

    support.open(player, view);
    support.trackLive(player, menuService::openBrowse);
  }

  // ==============================================================================================
  // HELPERS & TILE LOGIC
  // ==============================================================================================

  static List<ExperienceManager.Experience> tilesFor(List<ExperienceManager.Experience> owned, int capacity) {
    List<List<ExperienceManager.Experience>> groups = sourcesWithTheirBackups(owned);
    int total = 0;
    for (List<ExperienceManager.Experience> group : groups) {
      total += group.size();
    }
    while (total > capacity) {
      List<ExperienceManager.Experience> fattest = fattestGroup(groups, false);
      if (fattest == null) {
        fattest = fattestGroup(groups, true);
      }
      if (fattest == null) {
        break;
      }
      fattest.remove(fattest.get(0).isBackup() ? 0 : 1);
      total--;
    }
    List<ExperienceManager.Experience> tiles = new ArrayList<>(Math.min(total, capacity));
    for (List<ExperienceManager.Experience> group : groups) {
      for (ExperienceManager.Experience experience : group) {
        if (tiles.size() >= capacity) {
          return tiles;
        }
        tiles.add(experience);
      }
    }
    return tiles;
  }

  private static List<ExperienceManager.Experience> fattestGroup(
      List<List<ExperienceManager.Experience>> groups, boolean orphaned) {
    List<ExperienceManager.Experience> fattest = null;
    int most = 0;
    for (List<ExperienceManager.Experience> group : groups) {
      if (group.isEmpty() || group.get(0).isBackup() != orphaned) {
        continue;
      }
      int backups = orphaned ? group.size() : group.size() - 1;
      if (backups > 0 && backups >= most) {
        most = backups;
        fattest = group;
      }
    }
    return fattest;
  }

  private static List<List<ExperienceManager.Experience>> sourcesWithTheirBackups(
      List<ExperienceManager.Experience> owned) {
    java.util.Map<String, List<ExperienceManager.Experience>> copies = new java.util.LinkedHashMap<>();
    List<List<ExperienceManager.Experience>> grouped = new ArrayList<>();
    List<ExperienceManager.Experience> sources = new ArrayList<>();
    for (ExperienceManager.Experience experience : owned) {
      if (!experience.isBackup()) {
        sources.add(experience);
      } else {
        copies.computeIfAbsent(experience.backupOf(), ignored -> new ArrayList<>()).add(experience);
      }
    }
    for (ExperienceManager.Experience source : sources) {
      List<ExperienceManager.Experience> group = new ArrayList<>();
      group.add(source);
      List<ExperienceManager.Experience> mine = copies.remove(source.id());
      if (mine != null) {
        group.addAll(mine);
      }
      grouped.add(group);
    }
    for (List<ExperienceManager.Experience> orphaned : copies.values()) {
      grouped.add(new ArrayList<>(orphaned));
    }
    return grouped;
  }

  static List<ExperienceManager.Experience> newestBackups(List<ExperienceManager.Experience> backups, int count) {
    if (backups == null || backups.isEmpty() || count <= 0) {
      return List.of();
    }
    int start = Math.max(0, backups.size() - count);
    return backups.subList(start, backups.size());
  }

  private MenuButton backupTile(ExperienceManager.Experience backup, List<ExperienceManager.Experience> owned) {
    String source = null;
    for (ExperienceManager.Experience candidate : owned) {
      if (candidate.id().equals(backup.backupOf())) {
        source = candidate.displayName();
        break;
      }
    }
    return backupTile(backup, source);
  }

  private MenuButton backupTile(ExperienceManager.Experience backup, String sourceName) {
    return MenuButton.of(ItemKey.minecraft("bundle"),
        "<aqua><bold>" + MenuSupport.escape(backup.displayName()) + "</bold></aqua>",
        List.of("<aqua>Backup</aqua> <gray>of <white>"
                + MenuSupport.escape(sourceName == null ? "a world you have since deleted" : sourceName)
                + "</white></gray>",
            "<gray>Taken <white>" + takenAt(backup.createdAt()) + "</white></gray>",
            "<gray>A world of its own — enter it to go back</gray>",
            "<yellow>Click to manage</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          menus.openBackup(ctx.player(), backup.id());
        });
  }

  private MenuButton worldTypeChoice(ExperienceWorldType type, ExperienceWorldType current, boolean locked, String expId) {
    boolean selected = type == current;
    String name = (selected ? "<green>✔ " : "<white>") + type.displayName() + (selected ? "</green>" : "</white>");
    List<String> lore = new ArrayList<>(List.of("<gray>" + type.description() + "</gray>"));
    if (locked) {
      lore.add("<red>Locked — terrain cannot change after creation</red>");
    } else if (selected) {
      lore.add("<green>Currently selected</green>");
    } else {
      lore.add("<yellow>Click to select</yellow>");
    }
    return MenuButton.of(type.icon(), name, lore, ctx -> {
      if (locked) {
        ctx.player().sendActionBar("<red>Terrain type is locked for this world.</red>");
        return;
      }
      if (expId == null) {
        support.setBuilderWorldType(ctx.player().uniqueId(), type);
        menus.openExperienceBuilder(ctx.player());
      } else {
        ctx.player().sendActionBar("<gray>World terrain is set at creation.</gray>");
      }
    });
  }

  private MenuButton worldTypeButton(ExperienceWorldType type, Consumer<MenuContext> onClick) {
    return MenuButton.of(type.icon(), "<aqua><bold>World: " + type.displayName() + "</bold></aqua>",
        List.of("<gray>" + type.description() + "</gray>", "<yellow>Click to choose map / terrain</yellow>"),
        onClick);
  }

  private MenuButton keepInventoryButton(boolean on, Consumer<MenuContext> onClick) {
    return MenuButton.of(on ? ItemKey.minecraft("chest") : ItemKey.minecraft("hopper"),
        "<white>Keep inventory: " + (on ? "<green><bold>ON</bold></green>" : "<red><bold>OFF</bold></red>") + "</white>",
        List.of("<gray>When ON, you keep items on death</gray>", "<yellow>Click to toggle</yellow>"),
        onClick);
  }

  private MenuButton hardcoreButton(boolean hardcore, boolean lost, Consumer<MenuContext> onClick) {
    return hardcoreButton(hardcore, lost, HardcoreDemand.NONE, onClick);
  }

  private MenuButton hardcoreButton(boolean hardcore, boolean lost, HardcoreDemand demand,
      Consumer<MenuContext> onClick) {
    if (!lost && demand != null && demand.required()) {
      String reason = demand.reason() == null ? "This mode" : demand.reason();
      return MenuButton.of(HARDCORE_ICON,
          "<dark_red><bold>Hardcore: ON</bold></dark_red> <dark_gray>(locked)</dark_gray>",
          List.of("<red>" + reason + " requires hardcore.</red>",
              demand.outcome() == HardcoreDeathOutcome.RESET_WORLD
                  ? "<gray>A death does not end the run — it ends this WORLD,</gray>"
                  : "<gray>One death and this world is GONE.</gray>",
              demand.outcome() == HardcoreDeathOutcome.RESET_WORLD
                  ? "<gray>and a brand-new one takes its place.</gray>"
                  : "<gray>Hardcore hearts, hard difficulty, no second chances</gray>",
              "<dark_gray>Remove that challenge to get the choice back.</dark_gray>"),
          ctx -> ctx.player().sendActionBar(
              "<red>" + reason + " requires hardcore — it cannot be turned off.</red>"));
    }
    if (lost) {
      return MenuButton.of(HARDCORE_ICON,
          "<dark_red><bold>HARDCORE — WORLD LOST</bold></dark_red>",
          List.of("<red>You died here. This world is over.</red>",
              "<red>You can look around as a spectator,</red>",
              "<red>but it can never be played again.</red>",
              "<red>Deleting it is all that is left.</red>"),
          ctx -> ctx.player().sendActionBar("<red>This world was lost. Delete it to move on.</red>"));
    }
    return MenuButton.of(
        hardcore ? HARDCORE_ICON : ItemKey.minecraft("golden_apple"),
        hardcore ? "<dark_red><bold>Hardcore: ON</bold></dark_red>" : "<gray><bold>Hardcore: OFF</bold></gray>",
        List.of(hardcore
                ? "<red>One death and this world is GONE.</red>"
                : "<gray>Normal rules: dying costs you nothing permanent</gray>",
            hardcore
                ? "<gray>Hardcore hearts, hard difficulty, no second chances</gray>"
                : "<dark_gray>Turn on for hardcore hearts and hard difficulty</dark_gray>",
            "<yellow>Click to turn it " + (hardcore ? "off" : "on") + "</yellow>"),
        onClick);
  }

  private MenuButton lockedButton(ItemKey icon, String label, String value) {
    return MenuButton.of(icon, "<dark_gray><bold>" + label + ": " + MenuSupport.escape(value) + "</bold></dark_gray>",
        List.of("<red>This world was lost.</red>", "<dark_gray>Locked — nothing here can change</dark_gray>"),
        ctx -> ctx.player().sendActionBar("<red>This world was lost. Spectate, rename or delete it.</red>"));
  }

  private String challengeRowName(ChallengeCatalog.Entry entry, boolean on) {
    return on ? "<green><bold>✔ " + MenuSupport.escape(entry.displayName()) + "</bold></green>"
        : "<gray>" + MenuSupport.escape(entry.displayName()) + "</gray>";
  }

  private List<String> challengeRowLore(ChallengeCatalog.Entry entry, boolean on) {
    List<String> lines = new ArrayList<>();
    lines.add("<gray>" + MenuSupport.escape(entry.description()) + "</gray>");
    lines.add(on ? "<green>Active — click to remove</green>" : "<yellow>Click to select</yellow>");
    return lines;
  }

  private void startChaos(PlayerAdapter player) {
    support.serverAdapter.menus().close(player);
    player.sendActionBar("<yellow>Spawning Chaos world…</yellow>");
    support.announceEnter(player, support.experienceService.createAndStartChaos(player, support.roster(player)));
  }

  private void lostWorldRefusal(PlayerAdapter player, String id) {
    player.sendActionBar("<red>This world is lost — one death ended it.</red>");
    menus.openExperienceManage(player, id);
  }

  private static String takenAt(long timestamp) {
    return new SimpleDateFormat("MMM d, HH:mm").format(new Date(timestamp));
  }
}
