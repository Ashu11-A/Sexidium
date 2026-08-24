package com.sexidium.core.menu;

import com.sexidium.core.command.CoreCommandService;
import com.sexidium.core.game.CoreGameRegistryInitializer;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.world.npc.NpcDefinition;
import com.sexidium.core.world.npc.NpcManager;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.List;
import java.util.UUID;

/**
 * The operator-only control panel and its sub-screens: the settings hub, the menu-art calibration
 * grid, and the full lobby-NPC editor (list / editor / minigame picker / skin picker). Every entry is
 * gated on {@code sexidium.admin} both at the hub and inside each privileged callback.
 */
final class AdminMenu {
  private final MenuSupport support;
  private final MenuService menus;

  AdminMenu(MenuSupport support, MenuService menus) {
    this.support = support;
    this.menus = menus;
  }

  /**
   * The operator-only settings hub reached from the main menu. The entry point itself is gated in
   * {@link HubMenu#openMain}, and every callback re-checks {@code sexidium.admin} so a stale/forged click on a
   * cached inventory cannot reach a privileged action.
   */
  void openAdminSettings(PlayerAdapter player) {
    NpcManager npcManager = support.npcManager;
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }
    MenuView view = new MenuView("<red><bold>Admin Settings</bold></red>", 3).background(MenuArt.BG_ADMIN);
    view.set(11, MenuButton.of(ItemKey.minecraft("armor_stand"), "<gold><bold>Manage NPCs</bold></gold>",
        List.of("<gray>Edit skin, minigame, hologram,</gray>", "<gray>position and deletion</gray>",
            "<gray>Configured: <white>" + npcManager.definitions().size() + "</white></gray>",
            "<yellow>Click to open</yellow>"),
        ctx -> menus.openNpcList(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_NPC)));
    view.set(13, MenuButton.of(ItemKey.minecraft("player_head"), "<green><bold>Create NPC Here</bold></green>",
        List.of("<gray>Spawns a new NPC at your position</gray>", "<gray>then opens its editor</gray>",
            "<yellow>Click to create</yellow>"),
        ctx -> createNpcHere(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_NPC_CREATE)));
    view.set(15, MenuButton.of(ItemKey.minecraft("lever"), "<aqua><bold>Reload NPCs</bold></aqua>",
        List.of("<gray>Re-read and respawn all NPCs</gray>", "<yellow>Click to reload</yellow>"),
        ctx -> {
          npcManager.reloadAndSpawn();
          ctx.player().sendActionBar("<green>Reloaded lobby NPCs.</green>");
          menus.openAdminSettings(ctx.player());
        }).withModel(MenuArt.model(MenuArt.ICON_RELOAD)));
    view.set(4, MenuButton.of(ItemKey.minecraft("item_frame"), "<gold><bold>Menu Art Calibration</bold></gold>",
        List.of("<gray>Overlay the vanilla slot grid + a ruler</gray>",
            "<gray>to align the menu art (Java pack only)</gray>",
            "<gray>Current nudge: <white>dx=" + MenuArt.calibrateDx() + " dy=" + MenuArt.calibrateDy() + "</white></gray>",
            "<yellow>Click to open the grid</yellow>"),
        ctx -> openMenuCalibration(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_EDIT_CHALLENGES)));
    view.set(view.size() - 9, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
  }

  /**
   * The menu-art calibration grid: a full chest whose background paints the EXACT vanilla slot grid plus a
   * 1px ruler, with a marker item in every slot as ground truth. A pack-loaded Java client should show each
   * cyan box framing the item in its slot; any consistent up/left (or down/right) gap is the miscalibration.
   * Read it off the ruler, set {@code ui.menu-art.calibrate-dx} (+ = right) and {@code -dy} (+ = down) in
   * config.yml, restart, and re-open (the client re-downloads the pack because its SHA changes with dy).
   */
  void openMenuCalibration(PlayerAdapter player) {
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }
    int dx = MenuArt.calibrateDx();
    int dy = MenuArt.calibrateDy();
    // background() (not screenArt()) keeps every slot item visible — the grid is compared against them.
    MenuView view = new MenuView("<gold><bold>Art Calibration</bold></gold> <gray>dx=" + dx + " dy=" + dy + "</gray>", 6)
        .background(MenuArt.CALIBRATION_GLYPH_ID);
    List<String> lore = List.of(
        "<gray>Cyan box = where the art thinks this slot is</gray>",
        "<gray>Aligned = box frames this item exactly</gray>",
        "<gray>Off up/left → raise calibrate-dx/-dy in config</gray>",
        "<dark_gray>Press Esc to close</dark_gray>");
    for (int slot = 0; slot < view.size(); slot++) {
      view.set(slot, MenuButton.label(ItemKey.minecraft("white_stained_glass_pane"),
          "<white>slot " + slot + "</white>", lore));
    }
    support.open(player, view);
    player.sendActionBar("<gold>Calibration grid: align the cyan boxes to the items, then set ui.menu-art.calibrate-dx/-dy.</gold>");
  }

  /** Creates a fresh NPC at the operator's position with an auto-generated id, then opens its editor. */
  private void createNpcHere(PlayerAdapter player) {
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }
    WorldPosition position = player.position();
    if (position == null) {
      player.sendActionBar("<red>Could not read your position.</red>");
      return;
    }
    String id = nextNpcId();
    NpcDefinition definition = new NpcDefinition(id, position.worldName(), position.coordinateX(),
        position.coordinateY(), position.coordinateZ(), position.yaw(), position.pitch(),
        "", id, "", false, List.of(), "");
    saveNpcFromMenu(player, definition, () -> {
      player.sendActionBar("<green>Created NPC <white>" + MenuSupport.escape(id) + "</white>.</green>");
      menus.openNpcEditor(player, id);
    });
  }

  /** First free {@code npc<N>} id, so GUI-created NPCs never collide with existing ones. */
  private String nextNpcId() {
    NpcManager npcManager = support.npcManager;
    int n = npcManager.definitions().size() + 1;
    while (npcManager.get("npc" + n) != null) {
      n++;
    }
    return "npc" + n;
  }

  /** A grid of every configured lobby NPC; clicking one opens its editor. */
  void openNpcList(PlayerAdapter player) {
    NpcManager npcManager = support.npcManager;
    MenuView view = new MenuView("<gold><bold>Lobby NPCs</bold></gold>", 6).background(MenuArt.BG_ADMIN);
    int slot = 0;
    for (NpcDefinition definition : npcManager.definitions()) {
      if (slot >= 45) {
        break;
      }
      String modeLabel = definition.minigameMode().isBlank() ? "Manual" : definition.minigameMode();
      List<String> lore = List.of(
          "<gray>World: <white>" + MenuSupport.escape(definition.world()) + "</white></gray>",
          "<gray>Mode: <white>" + MenuSupport.escape(modeLabel) + "</white></gray>",
          "<yellow>Click to edit</yellow>");
      String title = "<white><bold>" + MenuSupport.escape(definition.id()) + "</bold></white>";
      UUID headOwner = onlinePlayerIdByName(definition.skin());
      MenuButton button = headOwner != null
          ? MenuButton.head(headOwner, title, lore, ctx -> menus.openNpcEditor(ctx.player(), definition.id()))
          : MenuButton.of(ItemKey.minecraft("armor_stand"), title, lore, ctx -> menus.openNpcEditor(ctx.player(), definition.id()))
              .withModel(MenuArt.model(MenuArt.ICON_NPC));
      view.set(slot++, button);
    }
    if (slot == 0) {
      view.set(22, MenuButton.label(ItemKey.minecraft("barrier"), "<gray>No NPCs configured</gray>",
          List.of("<gray>Create one with <white>/sx admin npc create</white></gray>")));
    }
    view.set(view.size() - 9, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
  }

  /** The per-NPC editor: skin, minigame mode, look-at toggle, hologram status, move-here, delete. */
  void openNpcEditor(PlayerAdapter player, String npcId) {
    NpcManager npcManager = support.npcManager;
    NpcDefinition definition = npcManager.get(npcId);
    if (definition == null) {
      player.sendActionBar("<red>That NPC no longer exists.</red>");
      menus.openNpcList(player);
      return;
    }
    MenuView view = new MenuView("<gold><bold>Edit NPC</bold></gold> <gray>" + MenuSupport.escape(definition.id()) + "</gray>", 3)
        .background(MenuArt.BG_ADMIN);

    String skinLabel = definition.skin().isBlank() ? "default" : MenuSupport.escape(definition.skin());
    view.set(10, MenuButton.of(ItemKey.minecraft("player_head"), "<aqua><bold>Skin</bold></aqua>",
        List.of("<gray>Current: <white>" + skinLabel + "</white></gray>",
            "<yellow>Click to choose an online player</yellow>"),
        ctx -> menus.openNpcSkinPicker(ctx.player(), definition.id())));

    String modeLabel = definition.minigameMode().isBlank() ? "Manual (click command)" : MenuSupport.escape(definition.minigameMode());
    view.set(12, MenuButton.of(support.icon(definition.minigameMode()), "<green><bold>Minigame Mode</bold></green>",
        List.of("<gray>Current: <white>" + modeLabel + "</white></gray>",
            "<yellow>Click to choose a minigame</yellow>"),
        ctx -> menus.openNpcModePicker(ctx.player(), definition.id())).withModel(MenuArt.modeModel(definition.minigameMode())));

    boolean follow = definition.followPlayerHead();
    view.set(14, MenuButton.of(ItemKey.minecraft(follow ? "ender_eye" : "ender_pearl"),
        "<light_purple><bold>Look at Players</bold></light_purple>",
        List.of("<gray>Currently: " + (follow ? "<green>on</green>" : "<red>off</red>") + "</gray>",
            "<yellow>Click to toggle</yellow>"),
        ctx -> saveNpcFromMenu(ctx.player(), withFollow(definition, !follow), () -> menus.openNpcEditor(ctx.player(), definition.id()))));

    view.set(16, MenuButton.label(ItemKey.minecraft("oak_sign"), "<gold><bold>Hologram</bold></gold>",
        hologramStatusLore(definition)));

    view.set(20, MenuButton.of(ItemKey.minecraft("compass"), "<aqua><bold>Move NPC Here</bold></aqua>",
        List.of("<gray>Teleports the NPC to your position</gray>"),
        ctx -> {
          PlayerAdapter clicker = ctx.player();
          WorldPosition position = clicker.position();
          if (position == null) {
            clicker.sendActionBar("<red>Could not read your position.</red>");
            return;
          }
          NpcDefinition moved = new NpcDefinition(definition.id(), position.worldName(), position.coordinateX(),
              position.coordinateY(), position.coordinateZ(), position.yaw(), position.pitch(), definition.skin(),
              definition.name(), definition.clickCommand(), definition.followPlayerHead(), definition.hologram(),
              definition.minigameMode());
          saveNpcFromMenu(clicker, moved, () -> {
            clicker.sendActionBar("<green>Moved NPC <white>" + MenuSupport.escape(definition.id()) + "</white> here.</green>");
            menus.openNpcEditor(clicker, definition.id());
          });
        }));

    view.set(24, support.confirmButton(player, ItemKey.minecraft("tnt"), MenuArt.model(MenuArt.ICON_DELETE), "npc-delete:" + definition.id(),
        "<red><bold>Delete NPC</bold></red>", List.of("<gray>Tap to delete this NPC</gray>"),
        "<red><bold>Tap again to delete!</bold></red>", List.of("<red>This cannot be undone</red>"),
        ctx -> {
          npcManager.remove(definition.id());
          ctx.player().sendActionBar("<yellow>Deleted NPC <white>" + MenuSupport.escape(definition.id()) + "</white>.</yellow>");
          menus.openNpcList(ctx.player());
        },
        viewer -> menus.openNpcEditor(viewer, definition.id())));

    view.set(view.size() - 9, support.back(ctx -> menus.openNpcList(ctx.player())));
    support.open(player, view);
  }

  /** Picks which minigame an NPC queues for; "Manual / None" falls back to the NPC's click command. */
  void openNpcModePicker(PlayerAdapter player, String npcId) {
    NpcManager npcManager = support.npcManager;
    GameManager gameManager = support.gameManager;
    NpcDefinition definition = npcManager.get(npcId);
    if (definition == null) {
      player.sendActionBar("<red>That NPC no longer exists.</red>");
      menus.openNpcList(player);
      return;
    }
    MenuView view = new MenuView("<green><bold>Choose Minigame</bold></green>", 6).background(MenuArt.BG_ADMIN);
    int slot = 0;
    boolean manual = definition.minigameMode().isBlank();
    view.set(slot++, MenuButton.of(ItemKey.minecraft("barrier"),
        "<white><bold>Manual / None</bold></white>" + (manual ? " <green>✔</green>" : ""),
        List.of("<gray>Use the NPC's click command instead</gray>"),
        ctx -> saveNpcFromMenu(ctx.player(), withMode(definition, ""), () -> menus.openNpcEditor(ctx.player(), definition.id()))));
    for (GameModeDescriptor descriptor : gameManager.descriptors()) {
      if (!CoreGameRegistryInitializer.CATEGORY_MINIGAMES.equals(descriptor.category())) {
        continue;
      }
      if (slot >= 45) {
        break;
      }
      boolean active = descriptor.modeId().equalsIgnoreCase(definition.minigameMode());
      view.set(slot++, MenuButton.of(support.icon(descriptor.modeId()),
          "<white><bold>" + MenuSupport.escape(descriptor.displayName()) + "</bold></white>" + (active ? " <green>✔</green>" : ""),
          List.of("<gray>Click queues players for <white>" + MenuSupport.escape(descriptor.modeId()) + "</white></gray>"),
          ctx -> saveNpcFromMenu(ctx.player(), withMode(definition, descriptor.modeId()),
              () -> menus.openNpcEditor(ctx.player(), definition.id()))).withModel(MenuArt.modeModel(descriptor.modeId())));
    }
    view.set(view.size() - 9, support.back(ctx -> menus.openNpcEditor(ctx.player(), definition.id())));
    support.open(player, view);
  }

  /** Reuses the online-player picker to set an NPC's skin to a player's name (resolved via SkinsRestorer). */
  void openNpcSkinPicker(PlayerAdapter player, String npcId) {
    NpcManager npcManager = support.npcManager;
    NpcDefinition definition = npcManager.get(npcId);
    if (definition == null) {
      player.sendActionBar("<red>That NPC no longer exists.</red>");
      menus.openNpcList(player);
      return;
    }
    support.openPlayerPicker(player, "<aqua><bold>Choose Skin</bold></aqua>",
        candidate -> true,
        candidate -> saveNpcFromMenu(player, withSkin(definition, candidate.name()),
            () -> menus.openNpcEditor(player, definition.id())),
        support.back(ctx -> menus.openNpcEditor(ctx.player(), definition.id())));
  }

  private NpcDefinition withFollow(NpcDefinition d, boolean follow) {
    return new NpcDefinition(d.id(), d.world(), d.x(), d.y(), d.z(), d.yaw(), d.pitch(), d.skin(), d.name(),
        d.clickCommand(), follow, d.hologram(), d.minigameMode());
  }

  private NpcDefinition withMode(NpcDefinition d, String mode) {
    return new NpcDefinition(d.id(), d.world(), d.x(), d.y(), d.z(), d.yaw(), d.pitch(), d.skin(), d.name(),
        d.clickCommand(), d.followPlayerHead(), d.hologram(), mode);
  }

  private NpcDefinition withSkin(NpcDefinition d, String skin) {
    return new NpcDefinition(d.id(), d.world(), d.x(), d.y(), d.z(), d.yaw(), d.pitch(), skin, d.name(),
        d.clickCommand(), d.followPlayerHead(), d.hologram(), d.minigameMode());
  }

  private void saveNpcFromMenu(PlayerAdapter player, NpcDefinition definition, Runnable after) {
    NpcManager npcManager = support.npcManager;
    try {
      npcManager.save(definition);
      after.run();
    } catch (java.io.IOException exception) {
      player.sendActionBar("<red>Could not save NPC: " + exception.getMessage() + "</red>");
    }
  }

  private UUID onlinePlayerIdByName(String name) {
    ServerAdapter serverAdapter = support.serverAdapter;
    if (name == null || name.isBlank()) {
      return null;
    }
    for (PlayerAdapter candidate : serverAdapter.onlinePlayers()) {
      if (candidate.name().equalsIgnoreCase(name)) {
        return candidate.uniqueId();
      }
    }
    return null;
  }

  /** Mirrors {@code NpcManager.effectiveHologram} priority (manual &gt; mode &gt; none) for the editor label. */
  private List<String> hologramStatusLore(NpcDefinition definition) {
    if (!definition.hologram().isEmpty()) {
      return List.of("<gray>" + definition.hologram().size() + " manual line(s)</gray>",
          "<gray>Overrides the mode hologram</gray>");
    }
    if (!definition.minigameMode().isBlank()) {
      return List.of("<gray>Auto from mode</gray>", "<gray>Shows live player &amp; queue counts</gray>");
    }
    return List.of("<gray>None</gray>");
  }
}
