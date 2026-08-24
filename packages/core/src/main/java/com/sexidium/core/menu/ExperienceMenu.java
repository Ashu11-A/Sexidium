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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The composable-experience screens: the create builder and the edit-challenges grid (toggle any mix
 * of challenges), the "My Experiences" list, the per-experience manage / rename screens, and the
 * public-experience browser.
 */
final class ExperienceMenu {
  /**
   * One icon means hardcore, everywhere: the tile in "My Experiences", the toggle on the manage screen,
   * the create-builder toggle and the lost-world lock. A skull is the only item in these menus that says
   * "this run can end", so nothing else is allowed to use it.
   */
  private static final ItemKey HARDCORE_ICON = ItemKey.minecraft("skeleton_skull");

  private final MenuSupport support;
  private final MenuService menus;

  ExperienceMenu(MenuSupport support, MenuService menus) {
    this.support = support;
    this.menus = menus;
  }

  /**
   * The composable experience builder: toggle any mix of challenges, pick the map type, then create the
   * experience. Only the freely combinable twists live in the grid ({@link ChallengeCatalog#selectable()});
   * the map-generating ones (the SkyBlocks, Random Layers) are reached through the World tile, which is a
   * single-choice screen — so two maps can never be selected at once.
   */
  void openExperienceBuilder(PlayerAdapter player) {
    ExperienceService experienceService = support.experienceService;
    ServerAdapter serverAdapter = support.serverAdapter;
    Set<String> selected = support.builderSelectionFor(player.uniqueId());
    ExperienceSetup setup = support.builderSetupFor(player.uniqueId());
    ExperienceWorldType worldType = setup.worldType();
    MenuView view = new MenuView("<gold><bold>Build Experience</bold></gold>", 4).background(MenuArt.BG_EXPERIENCES);
    int slot = 0;
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.selectable()) {
      boolean on = selected.contains(entry.id());
      String name = challengeRowName(entry, on);
      // Selected (on) → full-colour icon; un-selected (off) → greyscale "_disabled" icon so the tile
      // reads as inactive. Falls back to the colour icon if no greyscale twin ships (e.g. legacy icons).
      String disabledModel = MenuArt.challengeModelDisabled(entry.id());
      String model = on || disabledModel == null ? MenuArt.challengeModel(entry.id()) : disabledModel;
      view.set(slot++, MenuButton.of(entry.icon(), name, challengeRowLore(entry, on),
          ctx -> {
            if (!selected.remove(entry.id())) {
              selected.add(entry.id());
            }
            menus.openExperienceBuilder(ctx.player());
          }).withModel(model));
    }
    // The map-type tile: which world is generated and where the player wakes up in it.
    view.set(view.size() - 7, worldTypeButton(worldType,
        ctx -> menus.openExperienceWorldType(ctx.player(), null)));
    // Keep-inventory: ON by default, one tap to flip before the experience is created.
    view.set(view.size() - 6, keepInventoryButton(setup.keepInventory(), ctx -> {
      support.setBuilderKeepInventory(ctx.player().uniqueId(), !setup.keepInventory());
      menus.openExperienceBuilder(ctx.player());
    }));
    // Hardcore: OFF unless deliberately turned on — one death ends the world for good. Unless a selected
    // challenge takes the choice away, in which case the tile shows ON and locked, live, as the twists
    // are picked: tick Death Resets and the apple turns into a skull in the same click.
    HardcoreDemand demand = ChallengeCatalog.hardcoreDemand(List.copyOf(selected));
    view.set(view.size() - 4, hardcoreButton(demand.appliesTo(setup.hardcore()), false, demand, ctx -> {
      support.setBuilderHardcore(ctx.player().uniqueId(), !setup.hardcore());
      menus.openExperienceBuilder(ctx.player());
    }));
    view.set(view.size() - 5, MenuButton.of(ItemKey.minecraft("lime_concrete"),
        "<green><bold>Create experience</bold></green>",
        List.of("<gray>Selected twists: <white>" + selected.size() + "</white></gray>",
            "<gray>World: <white>" + worldType.displayName() + "</white></gray>",
            "<gray>Keep inventory: " + (setup.keepInventory() ? "<green>ON" : "<red>OFF") + "</green></gray>",
            demand.appliesTo(setup.hardcore())
                ? (demand.outcome() == HardcoreDeathOutcome.RESET_WORLD
                    ? "<dark_red>HARDCORE — a death replaces the world</dark_red>"
                    : "<dark_red>HARDCORE — one death ends it</dark_red>")
                : "<gray>Hardcore: <white>OFF</white></gray>",
            "<gray>You always play in survival</gray>"),
        ctx -> {
          // A generated map (SkyBlock, …) is itself the experience, so it may be created with no twists;
          // a plain terrain world still needs at least one to be worth generating.
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
    // Chaos: skip hand-picking and start the random-twist mode instead (it has its own registered
    // descriptor but no menu of its own, so the experience builder is the natural place to surface it).
    // Ignores the current selection and starts a ChaosGame via the same path as /sx start chaos.
    view.set(view.size() - 3, MenuButton.of(ItemKey.minecraft("nether_star"),
        "<light_purple><bold>🎲 Chaos Mode</bold></light_purple>",
        List.of("<gray>Skip picking — get random twists</gray>",
            "<gray>Reshuffled every few minutes</gray>",
            "<yellow>Click to start a Chaos game</yellow>"),
        ctx -> startChaos(ctx.player())));
    view.set(view.size() - 9, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
  }

  /**
   * The map-type screen: the one place a world is chosen, and a SINGLE-choice one — the whole point of
   * lifting the map-generating twists out of the challenge grid, where two of them could be ticked at
   * once and would then fight over the same world.
   *
   * <p>Two groups: the start dimensions (a normal terrain world; you begin in the Overworld, the Nether
   * or The End of that same experience) and the generated maps (a void world an experience builds
   * itself). {@code experienceId} is null for the create builder; for an existing experience only the
   * start dimension can still be changed, because its terrain has already been generated.</p>
   */
  void openExperienceWorldType(PlayerAdapter player, String experienceId) {
    ExperienceManager.Experience experience = experienceId == null
        ? null : support.experienceService.registry().get(experienceId);
    if (experienceId != null && experience == null) {
      menus.openExperiences(player);
      return;
    }
    // A lost world has no settings screens at all. Guarded here as well as on the tiles that open it,
    // because a menu can be reached from a stale screen the player still had open when they died.
    if (experience != null && experience.isLost()) {
      lostWorldRefusal(player, experienceId);
      return;
    }
    boolean creating = experience == null;
    ExperienceWorldType current = creating
        ? support.builderWorldTypeFor(player.uniqueId()) : experience.type();
    MenuView view = new MenuView("<aqua><bold>Choose World</bold></aqua>", 5).background(MenuArt.BG_EXPERIENCES);
    view.set(4, MenuButton.label(ItemKey.minecraft("compass"), "<aqua><bold>Where do you start?</bold></aqua>",
        List.of("<gray>Pick ONE world for this experience</gray>",
            creating ? "<gray>How the world generates is fixed once it is created</gray>"
                : "<gray>Only the starting dimension can change now</gray>")));
    // Three groups, one row each, each with a label on the left so the choice reads on Bedrock too (a
    // flat tap-grid has no headers of its own). An experience whose world is already generated a certain
    // way locks EVERY row — its terrain cannot be re-rolled after the fact.
    boolean generationLocked = !creating && current.fixedAtCreation();
    groupRow(view, 9, ItemKey.minecraft("compass"), "<white><bold>Start dimension</bold></white>",
        "<gray>Normal terrain — pick where you wake up</gray>",
        ExperienceWorldType.startDimensions(), current, generationLocked, experienceId);
    groupRow(view, 18, ItemKey.minecraft("map"), "<white><bold>World generation</bold></white>",
        "<gray>Normal survival on differently shaped terrain</gray>",
        ExperienceWorldType.terrainPresets(), current, !creating, experienceId);
    groupRow(view, 27, ItemKey.minecraft("ender_eye"), "<white><bold>Generated maps</bold></white>",
        "<gray>A void world the challenge builds for you</gray>",
        ExperienceWorldType.generatedMaps(), current, !creating, experienceId);
    view.set(view.size() - 9, support.back(ctx -> {
      if (creating) {
        menus.openExperienceBuilder(ctx.player());
      } else {
        menus.openExperienceManage(ctx.player(), experienceId);
      }
    }));
    support.open(player, view);
  }

  /**
   * One labelled row of the chooser: a caption tile at {@code labelSlot} followed by the group's choices,
   * evenly spaced across that row.
   */
  private void groupRow(MenuView view, int labelSlot, ItemKey labelIcon, String labelName, String labelLore,
      List<ExperienceWorldType> types, ExperienceWorldType current, boolean locked, String experienceId) {
    view.set(labelSlot, MenuButton.label(labelIcon, labelName, List.of(labelLore)));
    int slot = labelSlot + 2;
    for (ExperienceWorldType type : types) {
      view.set(slot, worldTypeChoice(type, current, locked, experienceId));
      slot += 2;
    }
  }

  /** One selectable map type: ✔-marked when active, greyed out with a reason when it cannot be picked. */
  private MenuButton worldTypeChoice(ExperienceWorldType type, ExperienceWorldType current, boolean locked,
      String experienceId) {
    boolean active = type == current;
    List<String> lore = new ArrayList<>();
    lore.add("<gray>" + type.description() + "</gray>");
    if (active) {
      lore.add("<green>● SELECTED</green>");
    } else if (locked) {
      lore.add(type.fixedAtCreation()
          ? "<dark_gray>○ Only when creating a new experience</dark_gray>"
          : "<dark_gray>○ Locked — this world's terrain is already generated</dark_gray>");
    } else {
      lore.add("<yellow>○ Tap to choose this world</yellow>");
    }
    String name = active
        ? "<green><bold>✔ " + type.displayName() + "</bold></green>"
        : (locked ? "<dark_gray>" + type.displayName() + "</dark_gray>" : "<white>" + type.displayName() + "</white>");
    if (active || locked) {
      return MenuButton.label(type.icon(), name, lore).withModel(worldTypeModel(type));
    }
    return MenuButton.of(type.icon(), name, lore, ctx -> {
      if (experienceId == null) {
        support.setBuilderWorldType(ctx.player().uniqueId(), type);
        menus.openExperienceWorldType(ctx.player(), null);
        return;
      }
      boolean ok = support.experienceService.updateWorldType(ctx.player().uniqueId(), experienceId, type);
      ctx.player().sendActionBar(ok
          ? "<green>You will start in " + type.displayName() + " next time this experience opens.</green>"
          : "<red>That world cannot be changed on an existing experience.</red>");
      menus.openExperienceWorldType(ctx.player(), experienceId);
    }).withModel(worldTypeModel(type));
  }

  /** The builder/manage tile that shows the current map type and opens the chooser. */
  private MenuButton worldTypeButton(ExperienceWorldType worldType, Consumer<MenuContext> onClick) {
    return MenuButton.of(worldType.icon(), "<aqua><bold>World: " + worldType.displayName() + "</bold></aqua>",
        List.of("<gray>" + worldType.description() + "</gray>",
            "<yellow>Click to change the world</yellow>"),
        onClick).withModel(worldTypeModel(worldType));
  }

  /**
   * The keep-inventory tile, shown in the builder and on an existing experience's manage screen. ON is
   * the default (and what every experience did before the toggle existed); OFF means a death drops your
   * items the vanilla way. The rule covers every dimension of the experience, so it still holds in its
   * Nether and its End.
   */
  private MenuButton keepInventoryButton(boolean keepInventory, Consumer<MenuContext> onClick) {
    return MenuButton.of(
        // Deliberately NOT a skull: a skull means hardcore here, on every screen, and a second meaning
        // for the same icon is how a player ends up thinking they turned hardcore on by accident.
        keepInventory ? ItemKey.minecraft("chest") : ItemKey.minecraft("hopper"),
        keepInventory ? "<green><bold>Keep Inventory: ON</bold></green>" : "<red><bold>Keep Inventory: OFF</bold></red>",
        List.of(keepInventory
                ? "<gray>You keep your items and XP when you die</gray>"
                : "<gray>You drop your items when you die</gray>",
            "<dark_gray>Applies in the Overworld, Nether and End</dark_gray>",
            "<yellow>Click to turn it " + (keepInventory ? "off" : "on") + "</yellow>"),
        onClick);
  }

  /**
   * The HARDCORE tile — a SKELETON SKULL wherever hardcore appears, so the mode is recognisable at a
   * glance from the experience list without opening anything. OFF by default and deliberately loud when
   * ON: one death ends the world for good, so this must never be something a player can end up in
   * without having chosen it. A world that has already been lost shows the tile locked and inert —
   * hardcore that could be switched off after the fact would mean nothing.
   */
  private MenuButton hardcoreButton(boolean hardcore, boolean lost, Consumer<MenuContext> onClick) {
    return hardcoreButton(hardcore, lost, HardcoreDemand.NONE, onClick);
  }

  /**
   * As above, but aware that a selected challenge may have taken the choice away.
   *
   * <p>A forced experience renders ON and LOCKED, naming the challenge responsible. It used to render
   * from the owner's raw toggle, which for a mode like Death Resets meant the screen said
   * "Hardcore: OFF — click to turn it on" while the experience was already running hardcore and the
   * service would refuse to change it. Showing a switch that does nothing is worse than showing no
   * switch: the player concludes the mode is broken.</p>
   */
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

  /**
   * A control that is present but inert, for a world that has been LOST. Shown rather than hidden so the
   * owner can still read what the run was set up as — the settings are part of the record of it — while
   * being unable to change anything about a world that is over.
   */
  private MenuButton lockedButton(ItemKey icon, String label, String value) {
    return MenuButton.of(icon, "<dark_gray><bold>" + label + ": " + MenuSupport.escape(value) + "</bold></dark_gray>",
        List.of("<red>This world was lost.</red>", "<dark_gray>Locked — nothing here can change</dark_gray>"),
        ctx -> ctx.player().sendActionBar("<red>This world was lost. Spectate, rename or delete it.</red>"));
  }

  /** Sends a player who reached a settings screen for a lost world back to the one screen it still has. */
  private void lostWorldRefusal(PlayerAdapter player, String experienceId) {
    player.sendActionBar("<red>This world was lost. Spectate, rename or delete it.</red>");
    menus.openExperienceManage(player, experienceId);
  }

  /** A generated map re-uses its challenge's pack icon; the plain dimensions keep their vanilla item. */
  private static String worldTypeModel(ExperienceWorldType worldType) {
    return worldType.generatesMap() ? MenuArt.challengeModel(worldType.challengeId()) : null;
  }

  /**
   * Creates a persistent Chaos world for the player + their online group. Chaos worlds are saved in "My
   * Experiences", count against the owner's experience limit, and persist inventory + position exactly
   * like a hand-built experience (see {@link ExperienceService#createAndStartChaos}).
   */
  private void startChaos(PlayerAdapter player) {
    if (support.gameManager.matchOf(player) != null) {
      player.sendActionBar("<red>Leave your current game first (/leave).</red>");
      return;
    }
    support.resetBuilder(player.uniqueId());
    support.serverAdapter.menus().close(player);
    player.sendActionBar("<light_purple>Starting Chaos…</light_purple>");
    support.announceEnter(player, support.experienceService.createAndStartChaos(player, support.roster(player)));
  }

  void openExperiences(PlayerAdapter player) {
    GameManager gameManager = support.gameManager;
    ExperienceService experienceService = support.experienceService;
    ServerAdapter serverAdapter = support.serverAdapter;
    MenuView view = new MenuView("<yellow><bold>My Experiences</bold></yellow>", 4).background(MenuArt.BG_EXPERIENCES);
    ActiveMatch current = gameManager.matchOf(player);
    if (current != null) {
      view.set(4, MenuButton.of(ItemKey.minecraft("barrier"),
          "<red><bold>Leave current experience</bold></red>",
          List.of("<gray>Runs /leave</gray>"),
          ctx -> {
            serverAdapter.menus().close(ctx.player());
            gameManager.removePlayer(ctx.player(), true);
          }).withModel(MenuArt.model(MenuArt.ICON_LEAVE)));
    }
    List<ExperienceManager.Experience> owned = experienceService.own(player.uniqueId());
    // Slots 9..26 are the list body — the first row carries the header and the last one the footer, so
    // there are exactly 18 tiles to hand out. tilesFor decides who gets them, and never lets a copy
    // push the world it was copied from off the only screen that can enter, rename or delete it.
    List<ExperienceManager.Experience> tiles = tilesFor(owned, 18);
    int slot = 9;
    int worlds = 0;
    // Counted from the FULL list, not the drawn one: the footer states the per-player allowance, which
    // is spent by worlds that exist, not by worlds that happened to fit on screen. Backups are copies,
    // not worlds the player chose to make, so they do not spend it (`countByOwner` skips them too).
    for (ExperienceManager.Experience experience : owned) {
      if (!experience.isBackup()) {
        worlds++;
      }
    }
    for (ExperienceManager.Experience experience : tiles) {
      if (experience.isBackup()) {
        view.set(slot++, backupTile(experience, owned));
        continue;
      }
      // A Chaos world gets its own identifying item (a nether star) and label so it reads distinctly from
      // a hand-built experience in the same list.
      boolean chaos = experience.isChaos();
      boolean hardcore = experience.hardcore();
      boolean lost = experience.isLost();
      // A hardcore world is a SKULL on sight, and once it has been lost every line of it turns red —
      // an owner scanning their list should be able to tell a dead run from a living one without
      // opening it.
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
      view.set(slot++, MenuButton.of(icon, title, lore,
          ctx -> menus.openExperienceManage(ctx.player(), experience.id()))
          .withModel(chaos || hardcore || lost ? null : MenuArt.model(MenuArt.ICON_EXPERIENCE_MINE)));
    }
    if (tiles.isEmpty()) {
      view.set(22, MenuButton.label(ItemKey.minecraft("paper"), "<gray>No experiences yet</gray>",
          List.of("<gray>Create one from</gray>", "<white>Create Experience</white>")));
    }
    int max = experienceService.maxPerPlayer();
    view.set(view.size() - 5, MenuButton.of(ItemKey.minecraft("lime_concrete"),
        "<green><bold>Create new experience</bold></green>",
        List.of("<gray>Worlds used: <white>" + worlds + "/" + max + "</white></gray>",
            worlds >= max ? "<red>Limit reached — delete one first</red>" : "<yellow>Click to build a new one</yellow>"),
        ctx -> {
          support.resetBuilder(ctx.player().uniqueId());
          menus.openExperienceBuilder(ctx.player());
        }).withModel(MenuArt.model(MenuArt.ICON_CREATE)));
    view.set(view.size() - 9, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
    // Redrawn when any experience row changes: this list is where a delete carried out on another
    // node has to stop being offered, and where a rename has to show the new name.
    support.trackLive(player, menus::openExperiences);
  }

  /**
   * Which of the owner's rows the list draws, in which order, given how many tiles it has.
   *
   * <p>Order is sources first, each immediately followed by its own backups: a copy is only meaningful
   * next to the thing it is a copy of, and the list is a flat grid with no grouping of its own.</p>
   *
   * <p>The budget is the point of this being a function of its own. With the shipped defaults an owner
   * can hold ten worlds and thirty backups of them, which is forty rows for eighteen tiles — and a
   * backup that pushes a SOURCE off the list takes with it the only screen from which that world can be
   * entered, renamed or deleted. So copies are dropped, never sources, and within a group the OLDEST
   * goes first (the DB hands them over oldest first, so the ones kept are the ones most recently taken
   * — the same end of the list the Backups screen keeps when it runs out of row slots). Copies
   * are dropped from whichever group still has the most of them, so a single much-copied world cannot
   * spend the leftover tiles the other sources' copies would have used. Every dropped copy is still
   * reachable — it is listed on its source's manage screen.</p>
   *
   * <p>If the sources alone do not fit, the tail is cut: that is what the list did before backups
   * existed, and it is not this feature's problem to solve.</p>
   */
  static List<ExperienceManager.Experience> tilesFor(List<ExperienceManager.Experience> owned, int capacity) {
    List<List<ExperienceManager.Experience>> groups = sourcesWithTheirBackups(owned);
    int total = 0;
    for (List<ExperienceManager.Experience> group : groups) {
      total += group.size();
    }
    while (total > capacity) {
      // A copy whose source is gone goes LAST: no manage screen lists it, so dropping one is the only
      // drop here that would put a world out of reach altogether.
      List<ExperienceManager.Experience> fattest = fattestGroup(groups, false);
      if (fattest == null) {
        fattest = fattestGroup(groups, true);
      }
      if (fattest == null) {
        break; // nothing but sources left to give up — the truncation below is all that is left
      }
      // Within the group the OLDEST copy goes first, which is the one the Backups screen also gives up
      // when it runs out of row slots. The two screens have to agree on which copy matters: this used
      // to drop the newest, so the copy an owner had just taken — the one both "backup done" messages
      // promise will be in this list — was guaranteed a slot over there and was the very first row
      // thrown away here. In a group headed by its source that copy is index 1; an orphaned run is
      // copies all the way down, so it is index 0.
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

  /**
   * The group still holding the most copies to give up, or null when that half of the list has none
   * left. {@code orphaned} picks the half: groups headed by a source, or the leftover runs of copies
   * whose source has been deleted.
   */
  private static List<ExperienceManager.Experience> fattestGroup(
      List<List<ExperienceManager.Experience>> groups, boolean orphaned) {
    List<ExperienceManager.Experience> fattest = null;
    int most = 0;
    for (List<ExperienceManager.Experience> group : groups) {
      if (group.isEmpty() || group.get(0).isBackup() != orphaned) {
        continue;
      }
      int backups = orphaned ? group.size() : group.size() - 1;
      // >= so a tie drops from the LATER group: the worlds at the top of the list keep their copies
      // longest, and the top is where the owner's oldest worlds sit.
      if (backups > 0 && backups >= most) {
        most = backups;
        fattest = group;
      }
    }
    return fattest;
  }

  /**
   * The owner's rows split into groups: each source followed by the backups taken of it. Backups whose
   * source has since been deleted form groups of their own at the end rather than being dropped — they
   * are still complete, enterable worlds, and losing the original is exactly when one matters most.
   *
   * <p>Both the group lists and the outer list are mutable: {@link #tilesFor} trims them.</p>
   */
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

  /**
   * One backup row. Deliberately a different item and a different colour from the world it copies: the
   * two are otherwise identical worlds, and for every copy taken before the naming fix — which is most
   * of what is on the live server — the names are identical as well. Entering the wrong one is a
   * mistake the player only notices once they are standing in it.
   */
  private MenuButton backupTile(ExperienceManager.Experience backup,
      List<ExperienceManager.Experience> owned) {
    String source = null;
    for (ExperienceManager.Experience candidate : owned) {
      if (candidate.id().equals(backup.backupOf())) {
        source = candidate.displayName();
        break;
      }
    }
    return backupTile(backup, source);
  }

  /**
   * The same row, drawn where the source is already known — its own manage screen, which lists every
   * copy taken of it. One rendering for both places on purpose: a copy has to look like a copy wherever
   * the owner meets it, or the tile that opens a different world reads as the world they came from.
   *
   * <p>New copies do carry a {@code " (backup)"} suffix, but copies taken before that fix are name-
   * identical to their source and are on the live server today — for those the name is no help at all,
   * which is why the row leads with what the copy is a copy OF rather than with what it is called.</p>
   */
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
          // Not a confirm button, and the manage screen it can be clicked from may have a half-armed
          // Delete on it: navigating away has to disarm that, or the tap that comes back lands on it.
          support.clearConfirm(ctx.player().uniqueId());
          menus.openBackup(ctx.player(), backup.id());
        });
  }

  /**
   * The copies the Backups screen has room to draw: the {@code slots} MOST RECENT of {@code backups},
   * still oldest-first among themselves.
   *
   * <p>Taking the first {@code slots} instead — the OLDEST, since oldest-first is the order the registry
   * hands them over in — is what this screen used to do. With max-backups-per-experience raised above the
   * seven row slots, that drew every copy except the ones an owner had most recently taken, and nothing
   * else on any screen lists a copy: "My Experiences" spends its own tile budget on worlds first and
   * drops copies, so a copy that is not here is reachable by operator command only. The ones that still
   * do not fit are counted in the header, which names that command.</p>
   */
  static List<ExperienceManager.Experience> newestBackups(
      List<ExperienceManager.Experience> backups, int slots) {
    if (slots <= 0) {
      return List.of();
    }
    return backups.size() <= slots
        ? backups : backups.subList(backups.size() - slots, backups.size());
  }

  /** When a backup was taken, in the server's own time zone. Short: it shares a tooltip line. */
  private static String takenAt(long at) {
    return java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.ENGLISH));
  }

  /**
   * Every copy taken of ONE world, on a screen of its own — and the tile that takes another.
   *
   * <p>This exists because the copies used to be squatting on the source's manage screen (slots 19–26),
   * where seven of them left that screen with nothing free and still could not show what a copy IS.
   * Here there is room for the count, the age of each copy, and the one sentence an owner needs before
   * they tap: <b>nothing is ever deleted for them</b>, so a full list is a decision they have to make,
   * not a rotation the server performs behind their back.</p>
   */
  void openBackups(PlayerAdapter player, String sourceId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience experience = experienceService.registry().get(sourceId);
    if (experience == null) {
      menus.openExperiences(player);
      return;
    }
    // The confirm token below is the SOURCE's id. This is the same tile that used to sit at slot 15 of
    // the manage screen, moved here unchanged — token and all — so the gesture an owner already knows
    // is the gesture that still copies a world.
    String experienceId = sourceId;
    List<ExperienceManager.Experience> backups = experienceService.registry().backupsOf(experienceId);
    int taken = backups.size();
    int room = experienceService.maxBackupsPerExperience();
    boolean full = taken >= room;
    MenuView view = new MenuView("<aqua><bold>Backups · "
        + MenuSupport.escape(experience.displayName()) + "</bold></aqua>", 3)
        .background(MenuArt.BG_EXPERIENCES);
    // The seven MOST RECENT, still oldest-first among themselves — the order the registry hands them
    // over, so the run reads left to right in the order the copies were taken and the one taken last is
    // at the far end of it, slot 16. Which is also the order the copies of a world are drawn in on "My
    // Experiences", and the run is read by the "Taken <date>" line on each row rather than by position.
    // This screen used to draw the seven OLDEST, which with the cap raised above seven left the copy the
    // owner had just taken drawn nowhere at all: not here, and not in "My Experiences", which spends its
    // own tile budget on worlds first and drops copies.
    int[] rowSlots = {10, 11, 12, 13, 14, 15, 16};
    List<ExperienceManager.Experience> drawn = newestBackups(backups, rowSlots.length);
    // Decorative: a real name and a NULL onClick, which is the only signal MenuForms has for telling a
    // Bedrock form's body text apart from its buttons.
    List<String> header = new ArrayList<>(List.of(
        "<gray>Copies kept: <white>" + taken + "/" + room + "</white></gray>",
        "<gray>A copy is a world of its own — enter it to go back</gray>",
        "<gray>Nothing is ever deleted for you</gray>"));
    // max-backups-per-experience is configurable and this screen has seven slots for rows. Above that
    // the extra copies are still kept and still enterable, but nothing on any screen would say so --
    // "My Experiences" spends its own tile budget on worlds first and drops copies -- so the count at
    // the top is where the ones that did not fit have to be named.
    if (backups.size() > rowSlots.length) {
      header.add("<yellow>Only the <white>" + rowSlots.length + "</white> newest are shown here —"
          + " <white>" + (backups.size() - rowSlots.length) + "</white> older ones are kept and safe"
          + "</yellow>");
      header.add("<gray>An operator can list every one: <white>/sx admin backup list "
          + MenuSupport.escape(experienceId) + "</white></gray>");
    }
    view.set(4, MenuButton.label(ItemKey.minecraft("paper"),
        "<aqua><bold>Copies of " + MenuSupport.escape(experience.displayName()) + "</bold></aqua>",
        header));
    for (int i = 0; i < drawn.size() && i < rowSlots.length; i++) {
      view.set(rowSlots[i], backupTile(drawn.get(i), experience.displayName()));
    }
    // A cap of zero turns backups off entirely, so there is nothing to offer: the tile used to draw
    // itself "0/0 — no room", and confirming it answered "this experience keeps at most 0 backups.
    // Delete one to make room" — an instruction with no possible action behind it.
    if (room > 0) {
      // ExperienceBackupService refuses a LOST world and a copy-of-a-copy with LIMIT_REACHED, and
      // refuses a FULL one with the same constant, which the owner reads as "this experience keeps at
      // most N backups. Delete one to make room" — untrue for the first two, and for the third an
      // answer they only get after arming and confirming a tile that already knew it would fail. Every
      // one of those states is drawn as the same DECORATIVE tile, saying which state it is: a null
      // onClick is what MenuForms splits a Bedrock form's body text from its buttons on, so a no-op
      // lambda would render as a button that lies. This screen is REDRAWN on every EXPERIENCE_UPDATED
      // (see trackLive below), including the update that marks the world lost or fills its last slot
      // while its owner has this open, so every state is reachable with the tile on screen.
      String refusalName = null;
      List<String> refusal = null;
      if (experience.isLost()) {
        refusalName = "<dark_gray><bold>No copy can be taken</bold></dark_gray>";
        refusal = List.of("<red>This world is lost — the run is over</red>",
            "<gray>The copies below are still yours to enter</gray>");
      } else if (experience.isBackup()) {
        refusalName = "<dark_gray><bold>No copy can be taken</bold></dark_gray>";
        refusal = List.of("<gray>This is already a copy</gray>",
            "<gray>A copy of a copy is not offered — duplicate it instead</gray>");
      } else if (full) {
        refusalName = "<dark_gray><bold>No room for another copy</bold></dark_gray>";
        refusal = List.of("<red>Kept: <white>" + taken + "/" + room + "</white> — that is the limit"
                + "</red>",
            "<gray>Delete one of the copies above to make room</gray>",
            "<gray>Nothing is ever deleted for you: which one goes is your call</gray>");
      }
      if (refusal != null) {
        view.set(22, MenuButton.label(ItemKey.minecraft("barrier"), refusalName, refusal));
      } else {
        view.set(22, support.confirmButton(player, ItemKey.minecraft("bundle"),
            MenuArt.model(MenuArt.ICON_RELOAD), "backup:" + experienceId,
            "<aqua><bold>Take a backup now</bold></aqua>",
            List.of("<gray>Takes an exact copy of this world</gray>",
                "<gray>Terrain, everyone's stuff, every counter</gray>",
                "<gray>The copy is a world of its own — enter it to go back</gray>",
                "<gray>Backups: <white>" + taken + "/" + room + "</white></gray>",
                "<yellow>Tap, then tap again to confirm</yellow>"),
            "<aqua><bold>⧉ Tap again to back up</bold></aqua>",
            List.of("<gray>This copies the whole world — it can take a moment.</gray>",
                "<gray>You can keep playing — it is copied where it stands.</gray>",
                "<yellow>Tap once more to confirm</yellow>"),
            ctx -> {
              // Same shape as Delete on the manage screen, and for the same reasons: the node that can
              // answer this is the one holding the folder, the answer can arrive on the router's
              // recovery tick rather than on this click, and that tick runs on the global region.
              PlayerAdapter clicker = ctx.player();
              // Closed on the way out, exactly like Enter: a routed verb can be in flight until the ACK
              // times out, and until this screen is gone every tile on it is still clickable — two more
              // taps here would queue a second copy on top of the one already being taken.
              support.serverAdapter.menus().close(clicker);
              support.serverAdapter.messages().send(clicker,
                  LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_WORKING));
              experienceService.backup(clicker.uniqueId(), experienceId, outcome -> {
                if (!clicker.online()) {
                  return; // they left while the node holding the world was copying it
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
                    // A backup cannot restore, refresh or duplicate anything, and it cannot find its own
                    // source missing. Spelled out one by one rather than behind a `default`, because a
                    // default is exactly what would silently swallow the next constant added here.
                    case RESTORED, REFRESHED, DUPLICATED, GONE, FAILED ->
                        LocalizedText.of(MessageKey.EXPERIENCE_BACKUP_FAILED);
                  });
                  // Nothing is reopened. The screen was closed when the copy was fired, and the answer
                  // can be minutes behind it — the armed lore says in as many words that the owner can
                  // keep playing meanwhile, so a chest (a Cumulus popup, on Bedrock) opening over
                  // whatever they walked off to do is the one thing this must not do. The message is
                  // the whole answer; the new row is on the Backups screen when they next open it.
                }, null);
              });
            },
            viewer -> menus.openBackups(viewer, experienceId)));
      }
    }
    view.set(view.size() - 9, support.back(ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      menus.openExperienceManage(ctx.player(), experienceId);
    }));
    support.open(player, view);
    // A copy can be created, renamed or deleted from another node while this list sits open.
    support.trackLive(player, viewer -> menus.openBackups(viewer, experienceId));
  }

  /**
   * ONE copy, and everything that can be done to it: enter, restore, refresh, duplicate, rename, delete.
   *
   * <p>This screen exists because <b>a copy is byte-identical to its source, and for a long time its
   * name was too.</b> Copies taken from now on wear the {@code " (backup)"} suffix
   * {@code backupDisplayName} appends — the engine used to hand it the source's name verbatim, so the
   * suffix never ran. Those older rows are on the live database right now: two rows both called
   * "Death Resets", same owner, same twists, nothing on the tile telling them apart. So identity stays
   * this screen's first job, before any verb: which world this is a copy OF, and when it was taken.</p>
   *
   * <p>What it deliberately does NOT show: the in-game day count and the death count. Those live in
   * {@code experiences.challenge_state}, and on the live server that column is <em>stale</em> — it lags
   * the {@code state.yml} inside the folder by hours and can be missing the challenge's counters
   * altogether. A number that is wrong is worse than no number on a screen whose entire job is telling
   * two identical worlds apart. Same reason there is no member list: {@code experience_players} is not
   * copied, so every backup would read as empty.</p>
   */
  void openBackup(PlayerAdapter player, String backupId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience backup = experienceService.registry().get(backupId);
    if (backup == null) {
      menus.openExperiences(player);
      return;
    }
    if (!backup.isBackup()) {
      // Not (or no longer) a copy — a promoted orphan is a world in its own right, and its own manage
      // screen is the one that can change it.
      menus.openExperienceManage(player, backupId);
      return;
    }
    String sourceId = backup.backupOf();
    ExperienceManager.Experience source = experienceService.registry().get(sourceId);
    // An orphan has nothing to restore over and nothing to re-copy FROM, so both verbs are hidden
    // rather than shown refusing. Duplicate still works: the folder is right there.
    boolean orphan = source == null;
    MenuView view = new MenuView("<aqua><bold>Backup · "
        + MenuSupport.escape(backup.displayName()) + "</bold></aqua>", 3)
        .background(MenuArt.BG_EXPERIENCES);
    List<String> card = new ArrayList<>();
    card.add(orphan
        ? "<red>Copy of a world you have since deleted</red>"
        : "<aqua>Copy of <white>" + MenuSupport.escape(source.displayName()) + "</white></aqua>");
    card.add("<gray>Taken <white>" + takenAt(backup.createdAt()) + "</white></gray>");
    card.add("<gray>Twists: <white>" + MenuSupport.escape(support.challengesSummary(backup))
        + "</white></gray>");
    card.add("<gray>World: <white>" + MenuSupport.escape(backup.type().displayName()) + "</white></gray>");
    card.add(backup.hardcore()
        ? "<dark_red>HARDCORE — one death ends it</dark_red>"
        : "<gray>Hardcore: <white>OFF</white></gray>");
    card.add(backup.isPublic() ? "<green>Public</green>" : "<dark_gray>Private</dark_gray>");
    view.set(4, MenuButton.label(ItemKey.minecraft("paper"),
        "<aqua><bold>" + MenuSupport.escape(backup.displayName()) + "</bold></aqua>", card));
    view.set(10, MenuButton.of(ItemKey.minecraft("ender_pearl"), "<green><bold>Enter</bold></green>",
        List.of("<gray>Open this copy as a world of its own</gray>",
            "<gray>Your live world is untouched</gray>",
            "<yellow>Click to teleport in</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          support.serverAdapter.menus().close(ctx.player());
          ctx.player().sendActionBar("<yellow>Entering…</yellow>");
          support.announceEnter(ctx.player(), experienceService.enter(ctx.player(), backupId));
        }).withModel(MenuArt.model(MenuArt.ICON_ENTER)));
    if (!orphan) {
      view.set(11, restoreTile(player, backup, source, backupId));
      view.set(12, refreshTile(player, backup, source, backupId));
    }
    view.set(13, duplicateTile(player, backupId));
    view.set(14, MenuButton.of(ItemKey.minecraft("name_tag"), "<gold><bold>Rename</bold></gold>",
        List.of("<gray>Give this copy a name of its own</gray>",
            "<gray>Copies made now are named \"... (backup)\"; older ones share their"
                + " world's name</gray>",
            "<yellow>Click to choose</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          menus.openExperienceRename(ctx.player(), backupId);
        }).withModel(MenuArt.model(MenuArt.ICON_RENAME)));
    // Slot 15 stays empty on purpose: Delete never sits shoulder to shoulder with another verb.
    view.set(16, support.confirmButton(player, ItemKey.minecraft("tnt"),
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
          // Closed on the way out, exactly like Enter above: this screen's verbs stay clickable while a
          // routed delete is in flight, and the row every one of them describes is already gone.
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
              // Nothing is reopened: the screen was closed when the delete was fired, and a routed
              // answer can land long after the owner has walked away from the menu. The message says
              // what happened; the list they left is correct whenever they open it again.
            }, null);
          });
        },
        viewer -> menus.openBackup(viewer, backupId)));
    view.set(view.size() - 9, support.back(ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      if (orphan) {
        menus.openExperiences(ctx.player());
      } else {
        menus.openBackups(ctx.player(), sourceId);
      }
    }));
    // Visibility, keep-inventory and hardcore are the SAME controls a world has, so they are not
    // re-implemented here — the copy's own manage screen already draws every one of them.
    view.set(22, MenuButton.of(ItemKey.minecraft("writable_book"),
        "<aqua><bold>More settings</bold></aqua>",
        List.of("<gray>Visibility, keep-inventory, hardcore, twists</gray>",
            "<yellow>Click to open this copy's full settings</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          menus.openExperienceManage(ctx.player(), backupId);
        }).withModel(MenuArt.model(MenuArt.ICON_EDIT_CHALLENGES)));
    support.open(player, view);
    support.trackLive(player, viewer -> menus.openBackup(viewer, backupId));
  }

  /**
   * RESTORE — this copy becomes the live world again.
   *
   * <p>The armed lore says three things, in this order, because they are the three ways an owner can be
   * surprised: their current world is <em>kept</em> (nothing is thrown away); a player who joined after
   * the copy was taken has no saved state in it and arrives with nothing; and neither world may have
   * anybody standing in it.</p>
   */
  private MenuButton restoreTile(PlayerAdapter player, ExperienceManager.Experience backup,
      ExperienceManager.Experience source, String backupId) {
    ExperienceService experienceService = support.experienceService;
    String sourceName = MenuSupport.escape(source.displayName());
    return support.confirmButton(player, ItemKey.minecraft("recovery_compass"), null,
        "restore:" + backupId,
        "<gold><bold>Restore</bold></gold>",
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
          // Closed on the way out. A restore is a symmetric swap, so running it TWICE undoes it — and
          // this screen used to stay open the whole time the request was in flight (a routed one until
          // the ACK times out), with Restore two taps away from being fired again over itself.
          support.serverAdapter.menus().close(clicker);
          support.serverAdapter.messages().send(clicker,
              LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_WORKING));
          experienceService.restore(clicker.uniqueId(), backupId, outcome -> {
            if (!clicker.online()) {
              return; // they left while the node holding the folders was answering
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
                // A restore creates nothing and copies nothing, so these cannot reach it. Listed one
                // by one rather than behind a `default`, which is what would swallow the next constant
                // somebody adds to the shared outcome enum.
                case CREATED, REFRESHED, DUPLICATED, LIMIT_REACHED, FAILED ->
                    LocalizedText.of(MessageKey.EXPERIENCE_RESTORE_FAILED);
              });
              // Nothing is reopened: the menu was closed when the restore was fired, and this answer
              // can arrive minutes later, on the router's recovery tick. After a restore BOTH rows have
              // changed — the source now wears this world and the world it was wearing is a copy — so
              // the list is worth a look, but it is the owner who decides when to open it.
            }, null);
          });
        },
        viewer -> menus.openBackup(viewer, backupId));
  }

  /**
   * REFRESH — take this copy again, from the world it copies.
   *
   * <p>It is a <b>full re-copy</b>, not an incremental update, and the lore says so: the alternative
   * (skipping files whose size and timestamp match) silently produces a wrong copy when a same-length
   * rewrite lands inside one timestamp tick. A slow, correct copy beats a fast, wrong one — but the
   * owner has to be told which one they are getting.</p>
   */
  private MenuButton refreshTile(PlayerAdapter player, ExperienceManager.Experience backup,
      ExperienceManager.Experience source, String backupId) {
    ExperienceService experienceService = support.experienceService;
    String sourceName = MenuSupport.escape(source.displayName());
    return support.confirmButton(player, ItemKey.minecraft("bundle"),
        MenuArt.model(MenuArt.ICON_RELOAD), "refresh:" + backupId,
        "<aqua><bold>Refresh</bold></aqua>",
        // Line 0 carries the whole tile on Bedrock: PaperFormRenderer.buttonText appends lore.get(0)
        // to the button's name and DROPS every line after it. So the fact an owner must not miss goes
        // first — here, that this is a full re-copy rather than the cheap incremental update the word
        // "refresh" promises.
        List.of("<gray>A FULL re-copy, not an update — it can take a while on a big world</gray>",
            "<gray>Takes this copy again from <white>" + sourceName + "</white></gray>",
            "<gray>This copy is replaced; the count stays the same</gray>",
            "<gray>Currently from <white>" + takenAt(backup.createdAt()) + "</white></gray>",
            "<yellow>Tap, then tap again to confirm</yellow>"),
        "<aqua><bold>⧉ Tap again to refresh</bold></aqua>",
        // Same rule on the armed face, where the load-bearing fact is the one that cannot be undone.
        List.of("<gray>What this copy holds now is replaced and cannot be got back.</gray>",
            "<gray>This copies the whole world again from scratch.</gray>",
            "<gray>Nobody may be inside THIS copy — it is the one being replaced.</gray>",
            "<yellow>Tap once more to confirm</yellow>"),
        ctx -> {
          PlayerAdapter clicker = ctx.player();
          // Closed on the way out: a refresh replaces this copy's whole folder and cannot be got back,
          // and while it was in flight this screen stayed open with Refresh — and Restore beside it —
          // two taps from being fired again over the copy currently being rewritten.
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
              // Nothing is reopened: the menu was closed when the refresh was fired, and re-taking a
              // whole world can outlast the owner's interest in the screen by a long way.
            }, null);
          });
        },
        viewer -> menus.openBackup(viewer, backupId));
  }

  /**
   * DUPLICATE — a new, playable world made from this copy.
   *
   * <p>The one verb here that costs the owner something they can run out of, so the lore names the
   * price on both the idle and the armed face: the new world is not a backup, so it spends one of the
   * per-player world slots and counts against the limit forever after.</p>
   */
  private MenuButton duplicateTile(PlayerAdapter player, String backupId) {
    ExperienceService experienceService = support.experienceService;
    int slots = experienceService.maxPerPlayer();
    return support.confirmButton(player, ItemKey.minecraft("lime_concrete"),
        MenuArt.model(MenuArt.ICON_CREATE), "duplicate:" + backupId,
        "<green><bold>Duplicate</bold></green>",
        // The price goes first on BOTH faces: PaperFormRenderer.buttonText shows a Bedrock player the
        // button's name plus lore.get(0) and nothing else, so a slot cost placed second is a cost that
        // does not exist on mobile — and it is the one thing this tile spends that an owner runs out of.
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
          // Closed on the way out: duplicating spends a world slot, and the tile stayed clickable for
          // as long as the copy took — two more taps spent a second slot on an identical world.
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
              // Nothing is reopened: the menu was closed when the duplicate was fired, and copying a
              // whole world takes as long as it takes. The new world — a world of the owner's own, not
              // another copy of this one — is in their list the next time they open it.
            }, null);
          });
        },
        viewer -> menus.openBackup(viewer, backupId));
  }

  void openExperienceManage(PlayerAdapter player, String experienceId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience experience = experienceService.registry().get(experienceId);
    if (experience == null) {
      menus.openExperiences(player);
      return;
    }
    // A LOST world can only be looked at. Everything that would change it — visibility, the challenge
    // set, keep-inventory, hardcore itself — is shown locked rather than hidden, so the owner can see
    // what the run WAS. Entering (as a spectator), renaming and deleting stay open.
    boolean lost = experience.isLost();
    // A backup is a whole experience, so this screen is the ONLY place the owner can tell it apart from
    // the world it was copied from before entering one of them.
    boolean isBackup = experience.isBackup();
    MenuView view = new MenuView((lost ? "<red><bold>" : isBackup ? "<aqua><bold>" : "<yellow><bold>")
        + (isBackup ? "Backup · " : "")
        + MenuSupport.escape(experience.displayName())
        + (lost ? "</bold></red>" : isBackup ? "</bold></aqua>" : "</bold></yellow>"), 3)
        .background(MenuArt.BG_EXPERIENCES);
    // The world this map runs. Tapping it opens the chooser, which for an existing experience can only
    // re-point the starting dimension (the terrain is already generated). A lost world states what it is
    // instead — every line of it red, because the state of the run is the first thing the owner needs.
    view.set(4, lost
        ? MenuButton.of(HARDCORE_ICON, "<dark_red><bold>HARDCORE — WORLD LOST</bold></dark_red>",
            List.of("<red>You died in this world.</red>",
                "<red>World: " + MenuSupport.escape(experience.type().displayName()) + "</red>",
                "<red>Twists: " + MenuSupport.escape(support.challengesSummary(experience)) + "</red>",
                "<red>Nothing here can be changed any more.</red>",
                "<red>Spectate it, rename it, or delete it.</red>"),
            ctx -> ctx.player().sendActionBar("<red>This world was lost. Spectate, rename or delete it.</red>"))
        : worldTypeButton(experience.type(), ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            menus.openExperienceWorldType(ctx.player(), experienceId);
          }));
    view.set(10, MenuButton.of(ItemKey.minecraft("ender_pearl"),
        lost ? "<gray><bold>Spectate</bold></gray>" : "<green><bold>Enter</bold></green>",
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
    if (lost) {
      view.set(12, lockedButton(ItemKey.minecraft("gray_dye"), "Visibility",
          experience.isPublic() ? "Public" : "Private"));
    } else if (isBackup) {
      // A copy is not something to publish. The FRIENDS half of the browser already skips one
      // (ExperienceService.browsable), so making a copy public only ever puts it in front of STRANGERS
      // — wearing the same icon, and for every copy taken before the " (backup)" suffix the same name,
      // as the world it was taken from. One tap, no confirm, and nothing on the tile said any of it.
      // Shown rather than hidden, and decorative with a NULL onClick: that is the only signal MenuForms
      // has for telling a Bedrock form's body text from its buttons, so a no-op lambda would render as
      // a button that does nothing.
      view.set(12, MenuButton.label(
          experience.isPublic() ? ItemKey.minecraft("lime_dye") : ItemKey.minecraft("gray_dye"),
          "<dark_gray><bold>Visibility: " + (experience.isPublic() ? "Public" : "Private")
              + "</bold></dark_gray>",
          List.of("<gray>A copy's visibility cannot be changed</gray>",
              "<gray>The browser is not meant to list copies — this one is named and pictured like"
                  + " the world it was taken from</gray>",
              "<gray>Duplicate it first: the new world is yours to share</gray>")));
    } else {
      view.set(12, MenuButton.of(experience.isPublic() ? ItemKey.minecraft("lime_dye") : ItemKey.minecraft("gray_dye"),
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
    // A Chaos world has no fixed challenge set to edit — its twists are random and reshuffled — so its
    // manage screen shows an info tile instead of the "Change challenges" editor.
    if (lost) {
      view.set(14, lockedButton(ItemKey.minecraft("writable_book"), "Challenges",
          MenuSupport.escape(support.challengesSummary(experience))));
    } else if (experience.isChaos()) {
      view.set(14, MenuButton.label(ItemKey.minecraft("nether_star"), "<light_purple><bold>Chaos twists</bold></light_purple>",
          List.of("<gray>Random twists, reshuffled often</gray>", "<dark_gray>Not editable</dark_gray>")));
    } else {
      view.set(14, MenuButton.of(ItemKey.minecraft("writable_book"), "<aqua><bold>Change challenges</bold></aqua>",
          List.of("<gray>" + MenuSupport.escape(support.challengesSummary(experience)) + "</gray>", "<yellow>Click to edit the active twists</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            menus.openExperienceEdit(ctx.player(), experienceId);
          }).withModel(MenuArt.model(MenuArt.ICON_EDIT_CHALLENGES)));
    }
    if (lost) {
      view.set(13, lockedButton(ItemKey.minecraft("chest"), "Keep inventory",
          experience.keepInventory() ? "ON" : "OFF"));
    } else {
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
    // The live challenge list decides whether this is still the owner's choice at all — Death Resets and
    // anything else that requires the stakes shows the tile locked-ON instead of an off switch the
    // service would refuse.
    HardcoreDemand manageDemand = ChallengeCatalog.hardcoreDemand(experience.challenges());
    view.set(11, hardcoreButton(manageDemand.appliesTo(experience.hardcore()), lost, manageDemand, ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      boolean enabled = !experience.hardcore();
      boolean ok = experienceService.setHardcore(ctx.player().uniqueId(), experienceId, enabled);
      ctx.player().sendActionBar(ok
          ? (enabled ? "<dark_red>Hardcore ON — one death ends this world.</dark_red>"
              : "<green>Hardcore off. Deaths are survivable again.</green>")
          : "<red>You cannot change that.</red>");
      menus.openExperienceManage(ctx.player(), experienceId);
    }));
    // The doorway to this world's copies, immediately left of Rename. Hidden on a world that IS a
    // backup (a copy of a copy would be indistinguishable in the list, and its source is a world its
    // owner may delete) and on a LOST one, whose screen says in as many words that spectating,
    // renaming and deleting are all that is left.
    //
    // The copies themselves used to be drawn here, at slots 19–26. They are on a screen of their own
    // now — seven of them left this screen with nothing free, and still could not show what a copy IS.
    //
    // Read once, for two tiles: slot 15's count and the Delete lore below describe the same set, and
    // asking the registry twice is how they end up disagreeing with each other. A LOST world is read
    // too, and deliberately, even though its doorway is hidden and the read is a blocking query per
    // redraw: its copies outlive it, Delete is one of the three verbs its own screen says are all
    // that is left of it, and on Bedrock the line about them is the ONLY lore line that tile shows.
    // A copy has none of its own (a copy of a copy is never offered), so it is not asked.
    List<ExperienceManager.Experience> backups = isBackup
        ? List.of() : experienceService.registry().backupsOf(experienceId);
    if (!lost && !isBackup) {
      int taken = backups.size();
      int room = experienceService.maxBackupsPerExperience();
      // Drawn whenever there is room to take one OR a copy already exists: turning the cap down to zero
      // must not hide the copies an owner already has — nothing else lists them all.
      if (room > 0 || taken > 0) {
        view.set(15, MenuButton.of(ItemKey.minecraft("bundle"), "<aqua><bold>Backups</bold></aqua>",
            List.of("<gray>Kept: <white>" + taken + "/" + room + "</white></gray>",
                "<gray>An exact copy of this world, kept as a world of its own</gray>",
                "<gray>Enter one to go back to how things were</gray>",
                "<yellow>Tap to manage</yellow>"),
            ctx -> {
              support.clearConfirm(ctx.player().uniqueId());
              menus.openBackups(ctx.player(), experienceId);
            }));
      }
    }
    view.set(16, MenuButton.of(ItemKey.minecraft("name_tag"), "<gold><bold>Rename</bold></gold>",
        List.of("<gray>Pick a new name — no typing</gray>", "<yellow>Click to choose</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          menus.openExperienceRename(ctx.player(), experienceId);
        }).withModel(MenuArt.model(MenuArt.ICON_RENAME)));
    // Deleting a world does NOT delete the copies taken of it: the engine clears their backup_of, and a
    // row with no backup_of is a world of the owner's own — counted against the per-player world limit
    // from that moment on. Nowhere else on any screen says so, and it is the exact opposite of what the
    // rest of this feature promises ("Nothing is ever deleted for you", "The world it was copied from is
    // not touched"), so an owner deleting a world to make room can come back to a list further OVER the
    // cap than before. Named FIRST on BOTH faces, because PaperFormRenderer shows a Bedrock player the
    // button's name plus lore line 0 and nothing after it — and the idle face is the one an owner reads
    // while they are still deciding. What goes on that one visible line is the fact they cannot guess:
    // that the tile deletes the world is what the tile is called, while what happens to the copies is
    // stated on no other screen and is the opposite of what the rest of this feature promises.
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
    // Delete confirms with a second tap (Bedrock-safe), and with nothing but a second tap: a shift-click
    // shortcut is unreachable on Geyser and, in a chest, is the gesture for moving an item.
    view.set(22, support.confirmButton(player, ItemKey.minecraft("tnt"), MenuArt.model(MenuArt.ICON_DELETE), "delete:" + experienceId,
        "<red><bold>Delete</bold></red>",
        deleteIdle,
        "<red><bold>⚠ Tap again to delete</bold></red>",
        deleteArmed,
        ctx -> {
          // The answer can come from another node, so it cannot be assumed here. The button used to
          // discard the result entirely and say "Experience deleted." over a refusal -- which on the
          // lobby, the one place this menu exists, was every single delete.
          PlayerAdapter clicker = ctx.player();
          // Closed on the way out, like Enter: every tile left on this screen describes a world that is
          // being deleted, and a routed delete stays in flight until the node holding it answers.
          support.serverAdapter.menus().close(clicker);
          support.serverAdapter.messages().send(clicker,
              LocalizedText.of(MessageKey.EXPERIENCE_DELETE_WORKING));
          experienceService.delete(clicker.uniqueId(), experienceId, outcome -> {
            if (!clicker.online()) {
              return; // they left while the node holding the world was being asked
            }
            // The answer can arrive on the router's recovery tick rather than on this click, and that
            // tick runs on the global region — where opening this player's inventory is somebody
            // else's thread. Hop to theirs before touching them.
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
              // Nothing is reopened. The menu was closed when the delete was fired, and this answer can
              // arrive on the router's recovery tick — long enough after the click for the owner to be
              // somewhere else entirely, which is where a chest opening by itself does the damage.
            }, null);
          });
        },
        viewer -> menus.openExperienceManage(viewer, experienceId)));
    // Back goes where this screen was opened FROM. For a copy that is the copy's own screen (this one
    // is only ever reached from its "More settings" tile) — "My Experiences" only lists a copy if a
    // tile was left over, so sending a copy's Back there can land the player on a screen the world
    // they just left is not on. Guarded on the SOURCE still existing, which is exactly the condition
    // under which {@link #openBackup} renders rather than bouncing straight back here.
    String backupSource = isBackup && experienceService.registry().get(experience.backupOf()) != null
        ? experience.backupOf() : null;
    view.set(view.size() - 9, support.back(ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          if (backupSource != null) {
            menus.openBackup(ctx.player(), experienceId);
          } else {
            menus.openExperiences(ctx.player());
          }
        }));
    support.open(player, view);
    // Every tile on this screen is a copy of one registry row, and the owner can be looking at it from
    // the lobby while the world it describes is running somewhere else.
    support.trackLive(player, viewer -> menus.openExperienceManage(viewer, experienceId));
  }

  /**
   * Click-only rename: a short list of suggested names (Bedrock players have no chat picker and would
   * otherwise have to type the opaque experience id). Tapping a suggestion renames immediately.
   */
  void openExperienceRename(PlayerAdapter player, String experienceId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience experience = experienceService.registry().get(experienceId);
    if (experience == null) {
      menus.openExperiences(player);
      return;
    }
    MenuView view = new MenuView("<gold><bold>Rename Experience</bold></gold>", 3).background(MenuArt.BG_EXPERIENCES);
    int slot = 10;
    for (String suggestion : renameSuggestions(player, experience)) {
      if (slot > 16) {
        break;
      }
      view.set(slot++, MenuButton.of(ItemKey.minecraft("name_tag"), "<white><bold>" + MenuSupport.escape(suggestion) + "</bold></white>",
          List.of("<yellow>Click to use this name</yellow>"),
          ctx -> {
            experienceService.rename(ctx.player().uniqueId(), experienceId, suggestion);
            ctx.player().sendActionBar("<green>Renamed to " + MenuSupport.escape(suggestion) + ".</green>");
            menus.openExperienceManage(ctx.player(), experienceId);
          }).withModel(MenuArt.model(MenuArt.ICON_RENAME)));
    }
    view.set(view.size() - 9, support.back(ctx -> menus.openExperienceManage(ctx.player(), experienceId)));
    support.open(player, view);
  }

  /** A handful of distinct, sensible name suggestions for an experience (owner + challenge themed). */
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
    return names;
  }

  private static void addUnique(List<String> names, String candidate) {
    if (candidate != null && !candidate.isBlank() && !names.contains(candidate)) {
      names.add(candidate);
    }
  }

  /** Edit which challenges an existing experience runs (seeded with its current set). */
  void openExperienceEdit(PlayerAdapter player, String experienceId) {
    ExperienceService experienceService = support.experienceService;
    ExperienceManager.Experience experience = experienceService.registry().get(experienceId);
    if (experience == null) {
      menus.openExperiences(player);
      return;
    }
    // The challenge editor is closed for good on a lost world — the service refuses the change anyway,
    // and a grid that accepts taps and then refuses the confirm is a worse way to say so.
    if (experience.isLost()) {
      lostWorldRefusal(player, experienceId);
      return;
    }
    // Seed the working set from THIS experience the first time it is opened (keyed by id, so switching
    // from the create builder or a different experience always re-seeds and never leaks a stale set).
    Set<String> selected = support.builderSelectionFor(player.uniqueId());
    if (!experienceId.equals(support.editingExperience.get(player.uniqueId()))) {
      selected.clear();
      for (String id : experience.challenges()) {
        // The map generator is not a toggleable twist — it is the world itself, kept by the service on
        // every update — so it stays out of the working set and out of the grid.
        if (!ChallengeCatalog.isMapChallenge(id)) {
          selected.add(id);
        }
      }
      support.editingExperience.put(player.uniqueId(), experienceId);
    }
    MenuView view = new MenuView("<aqua><bold>Edit Challenges</bold></aqua>", 6).background(MenuArt.BG_EXPERIENCES);
    int slot = 0;
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.selectable()) {
      boolean on = selected.contains(entry.id());
      String name = challengeRowName(entry, on);
      // Active (on) → full-colour icon; inactive (off) → greyscale "_disabled" icon so the tile reads
      // as off. Falls back to the colour icon if no greyscale twin ships (e.g. legacy icons).
      String disabledModel = MenuArt.challengeModelDisabled(entry.id());
      String model = on || disabledModel == null ? MenuArt.challengeModel(entry.id()) : disabledModel;
      view.set(slot++, MenuButton.of(entry.icon(), name, challengeRowLore(entry, on),
          ctx -> {
            if (!selected.remove(entry.id())) {
              selected.add(entry.id());
            }
            menus.openExperienceEdit(ctx.player(), experienceId);
          }).withModel(model));
    }
    // The world tile is informational here — it shows what this experience runs and opens the chooser.
    view.set(view.size() - 7, worldTypeButton(experience.type(),
        ctx -> menus.openExperienceWorldType(ctx.player(), experienceId)));
    // Green CONFIRM block: a plain emerald block (no custom item-model) so it reads unmistakably as the
    // "confirm" tile. Applies the new twist set LIVE to a running experience (added/removed challenges
    // attach/detach on the spot) rather than ending the match.
    view.set(view.size() - 5, MenuButton.of(ItemKey.minecraft("emerald_block"), "<green><bold>✔ Confirm changes</bold></green>",
        List.of("<gray>Active twists: <white>" + selected.size() + "</white></gray>",
            "<gray>Applies instantly if the experience is running</gray>"),
        ctx -> {
          if (selected.isEmpty()) {
            ctx.player().sendActionBar("<red>Keep at least one challenge</red>");
            return;
          }
          List<String> ids = new ArrayList<>(selected);
          support.resetBuilder(ctx.player().uniqueId());
          boolean ok = experienceService.updateChallengesLive(ctx.player().uniqueId(), experienceId, ids);
          ctx.player().sendActionBar(ok
              ? "<green>Challenges updated and applied live.</green>"
              : "<red>Could not update the challenges.</red>");
          menus.openExperienceManage(ctx.player(), experienceId);
        }));
    view.set(view.size() - 9, support.back(ctx -> {
          support.resetBuilder(ctx.player().uniqueId());
          menus.openExperienceManage(ctx.player(), experienceId);
        }));
    support.open(player, view);
  }

  /** The bold, ✔-marked title used for a challenge row in the builder/edit grids. */
  private static String challengeRowName(ChallengeCatalog.Entry entry, boolean active) {
    return active
        ? "<green><bold>✔ " + entry.displayName() + "</bold></green>"
        : "<white>" + entry.displayName() + "</white>";
  }

  /** Shared challenge-row lore: a plain-text description plus an unambiguous ACTIVE/Inactive status. */
  private static List<String> challengeRowLore(ChallengeCatalog.Entry entry, boolean active) {
    return List.of("<gray>" + entry.description() + "</gray>",
        active ? "<green>● ACTIVE — tap to remove</green>" : "<dark_gray>○ Inactive — tap to add</dark_gray>");
  }

  /** Browse friends' worlds and other players' public experiences, and join them directly. */
  void openBrowse(PlayerAdapter player) {
    ExperienceService experienceService = support.experienceService;
    ServerAdapter serverAdapter = support.serverAdapter;
    MenuView view = new MenuView("<blue><bold>Friends' & Public Worlds</bold></blue>", 6).background(MenuArt.BG_BROWSE);
    int slot = 0;
    for (ExperienceManager.Experience experience : experienceService.browsable(player.uniqueId())) {
      if (slot >= 45) {
        break;
      }
      // Friends' worlds are joinable even when private, so flag them so the player knows why a non-public
      // world is listed; everything else here is public.
      boolean friendWorld = experienceService.areFriends(player.uniqueId(), experience.owner());
      view.set(slot++, MenuButton.of(ItemKey.minecraft("ender_eye"),
          "<white><bold>" + MenuSupport.escape(experience.displayName()) + "</bold></white>",
          List.of("<gray>by <white>" + MenuSupport.escape(experience.ownerName()) + "</white></gray>",
              friendWorld ? "<green>Friend's world</green>" : "<aqua>Public</aqua>",
              "<gray>" + MenuSupport.escape(support.challengesSummary(experience)) + "</gray>",
              "<yellow>Click to join</yellow>"),
          ctx -> {
            serverAdapter.menus().close(ctx.player());
            support.announceEnter(ctx.player(), experienceService.enter(ctx.player(), experience.id()));
          }).withModel(MenuArt.model(MenuArt.ICON_JOIN)));
    }
    if (slot == 0) {
      view.set(22, MenuButton.label(ItemKey.minecraft("barrier"),
          "<gray>No friends' or public worlds right now</gray>", List.of()));
    }
    view.set(view.size() - 9, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
    // The screen bug (2) is about: every row here belongs to somebody else, who may be renaming it,
    // making it private or deleting it from another node while this list sits open.
    support.trackLive(player, menus::openBrowse);
  }
}
