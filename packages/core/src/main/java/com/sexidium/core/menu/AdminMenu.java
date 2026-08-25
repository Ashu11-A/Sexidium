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

import java.util.ArrayList;
import java.util.Collection;
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
   * The operator-only settings hub reached from the main menu. Gated on {@code sexidium.admin}.
   */
  void openAdminSettings(PlayerAdapter player) {
    NpcManager npcManager = support.npcManager;
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }

    MenuView view = new MenuView("<red><bold>Admin Settings</bold></red>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_ADMIN);

    ChestLayout.fillSeparators(view);

    // Sidebar: Unified persistent navigation rail with ADMIN_SETTINGS marked active
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.ADMIN_SETTINGS);

    // Content: Balanced 2x2 Grid (slots 13, 15 and 22, 24)
    int npcCount = npcManager != null ? npcManager.definitions().size() : 0;
    view.set(13, MenuButton.of(ItemKey.minecraft("armor_stand"), "<gold><bold>Manage NPCs</bold></gold>",
        List.of("<gray>Edit skin, minigame, hologram,</gray>", "<gray>position and deletion</gray>",
            "<gray>Configured: <white>" + npcCount + " NPCs</white></gray>",
            "<yellow>Click to open NPC list</yellow>"),
        ctx -> menus.openNpcList(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_NPC)));

    view.set(15, MenuButton.of(ItemKey.minecraft("player_head"), "<green><bold>+ Create NPC Here</bold></green>",
        List.of("<gray>Spawns a new NPC at your exact location</gray>", "<gray>then opens its editor</gray>",
            "<yellow>Click to create</yellow>"),
        ctx -> createNpcHere(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_NPC_CREATE)));

    view.set(22, MenuButton.of(ItemKey.minecraft("lever"), "<aqua><bold>Reload All NPCs</bold></aqua>",
        List.of("<gray>Re-read and respawn all NPCs from storage</gray>", "<yellow>Click to reload</yellow>"),
        ctx -> {
          if (npcManager != null) {
            npcManager.reloadAndSpawn();
          }
          ctx.player().sendActionBar("<green>Reloaded lobby NPCs.</green>");
          menus.openAdminSettings(ctx.player());
        }).withModel(MenuArt.model(MenuArt.ICON_RELOAD)));

    view.set(24, MenuButton.of(ItemKey.minecraft("item_frame"), "<gold><bold>Menu Art Calibration</bold></gold>",
        List.of("<gray>Overlay the vanilla slot grid + 1px ruler</gray>",
            "<gray>to visually align menu art (Java pack only)</gray>",
            "<gray>Current: <white>dx=" + MenuArt.calibrateDx() + " dy=" + MenuArt.calibrateDy() + "</white></gray>",
            "<yellow>Click to open alignment grid</yellow>"),
        ctx -> openMenuCalibration(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_EDIT_CHALLENGES)));

    // Bottom Navigation
    view.set(ChestLayout.SLOT_BACK, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
  }

  /**
   * The menu-art calibration grid: a 54-slot chest whose background paints the EXACT vanilla slot grid plus a
   * 1px ruler, with 35 slot markers in the content area as ground truth.
   */
  void openMenuCalibration(PlayerAdapter player) {
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }
    int dx = MenuArt.calibrateDx();
    int dy = MenuArt.calibrateDy();

    MenuView view = new MenuView("<gold><bold>Art Calibration</bold></gold> <gray>dx=" + dx + " dy=" + dy + "</gray>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.CALIBRATION_GLYPH_ID);

    ChestLayout.fillSeparators(view);

    List<String> lore = List.of(
        "<gray>Cyan box = where the art thinks this slot is</gray>",
        "<gray>Aligned = box frames this item exactly</gray>",
        "<gray>Off up/left → raise calibrate-dx/-dy in config</gray>",
        "<dark_gray>Press Esc to close</dark_gray>");

    for (int index = 0; index < ChestLayout.CONTENT_CAPACITY; index++) {
      int slot = ChestLayout.contentSlot(index);
      view.set(slot, MenuButton.label(ItemKey.minecraft("white_stained_glass_pane"),
          "<aqua><bold>Content Slot " + index + "</bold></aqua>", lore));
    }

    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> menus.openAdminSettings(ctx.player())));
    support.open(player, view);
  }

  void openNpcList(PlayerAdapter player) {
    openNpcList(player, 0);
  }

  void openNpcList(PlayerAdapter player, int page) {
    NpcManager npcManager = support.npcManager;
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }

    Collection<NpcDefinition> defs = npcManager != null ? npcManager.definitions() : List.of();
    List<NpcDefinition> definitions = new ArrayList<>(defs);

    MenuView view = PaginatedScreen.<NpcDefinition>of("<gold><bold>Lobby NPCs</bold></gold>")
        .background(MenuArt.BG_ADMIN)
        .items(definitions)
        .page(page)
        .emptyIndicator(MenuButton.label(ItemKey.minecraft("paper"), "<gray><bold>No lobby NPCs configured</bold></gray>",
            List.of("<gray>Click <green>'+ Create NPC Here'</green> below</gray>")))
        .itemMapper(npc -> {
          UUID skinOwner = onlinePlayerIdByName(npc.skin());
          String title = "<gold><bold>" + MenuSupport.escape(npc.id()) + "</bold></gold>"
              + (npc.name().isBlank() ? "" : " <gray>(" + MenuSupport.escape(npc.name()) + ")</gray>");
          List<String> lore = List.of(
              "<gray>World: <white>" + npc.world() + "</white></gray>",
              "<gray>Position: <white>" + (int) npc.x() + ", " + (int) npc.y() + ", " + (int) npc.z() + "</white></gray>",
              npc.minigameMode().isBlank() ? "<dark_gray>No minigame</dark_gray>" : "<aqua>Mode: " + npc.minigameMode() + "</aqua>",
              "<yellow>Click to edit NPC</yellow>");
          return skinOwner == null
              ? MenuButton.of(ItemKey.minecraft("armor_stand"), title, lore, ctx -> menus.openNpcEditor(ctx.player(), npc.id()))
              : MenuButton.head(skinOwner, title, lore, ctx -> menus.openNpcEditor(ctx.player(), npc.id()));
        })
        .onPageChange(p -> openNpcList(player, p))
        .back(support.back(ctx -> menus.openAdminSettings(ctx.player())))
        .primaryAction(MenuButton.of(ItemKey.minecraft("player_head"), "<green><bold>+ Create NPC Here</bold></green>",
            List.of("<gray>Spawns a new NPC at your coordinates</gray>"),
            ctx -> createNpcHere(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_NPC_CREATE)))
        .build();

    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.ADMIN_SETTINGS);
    support.open(player, view);
  }

  void openNpcEditor(PlayerAdapter player, String npcId) {
    NpcManager npcManager = support.npcManager;
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }
    NpcDefinition definition = npcManager != null ? npcManager.get(npcId) : null;
    if (definition == null) {
      player.sendActionBar("<red>NPC '" + npcId + "' no longer exists.</red>");
      menus.openNpcList(player);
      return;
    }

    MenuView view = new MenuView("<gold><bold>Edit NPC · " + MenuSupport.escape(definition.id()) + "</bold></gold>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_ADMIN);

    ChestLayout.fillSeparators(view);

    // Sidebar: Unified global navigation rail with ADMIN_SETTINGS marked active
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.ADMIN_SETTINGS);

    // Content: Row 0 Header Plaque (slot 5)
    view.set(5, MenuButton.label(ItemKey.minecraft("armor_stand"),
        "<gold><bold>NPC: " + MenuSupport.escape(definition.id()) + "</bold></gold>",
        List.of("<gray>World: <white>" + definition.world() + "</white></gray>",
            "<gray>X: <white>" + (int) definition.x() + "</white> Y: <white>" + (int) definition.y() + "</white> Z: <white>" + (int) definition.z() + "</white></gray>",
            "<gray>Skin: <white>" + (definition.skin().isBlank() ? "default" : definition.skin()) + "</white></gray>",
            "<gray>Mode: <white>" + (definition.minigameMode().isBlank() ? "none" : definition.minigameMode()) + "</white></gray>"))
        .withModel(MenuArt.model(MenuArt.ICON_NPC)));

    // Content: Row 1 (Visual & Mode Controls - slots 12, 14, 16)
    UUID skinOwner = onlinePlayerIdByName(definition.skin());
    MenuButton skinTile = skinOwner == null
        ? MenuButton.of(ItemKey.minecraft("player_head"),
            "<aqua><bold>Skin: " + (definition.skin().isBlank() ? "(default)" : definition.skin()) + "</bold></aqua>",
            List.of("<gray>Click to pick an online player's skin</gray>"),
            ctx -> menus.openNpcSkinPicker(ctx.player(), definition.id()))
        : MenuButton.head(skinOwner,
            "<aqua><bold>Skin: " + definition.skin() + "</bold></aqua>",
            List.of("<gray>Click to pick a different skin</gray>"),
            ctx -> menus.openNpcSkinPicker(ctx.player(), definition.id()));
    view.set(12, skinTile);

    String mode = definition.minigameMode();
    GameModeDescriptor descriptor = support.descriptorOf(mode);
    ItemKey modeIcon = descriptor != null ? support.icon(mode) : ItemKey.minecraft("diamond_sword");
    String modeLabel = descriptor != null ? descriptor.displayName() : (mode.isBlank() ? "(none)" : mode);
    view.set(14, MenuButton.of(modeIcon,
        "<green><bold>Mode: " + modeLabel + "</bold></green>",
        List.of("<gray>Click to pick the minigame</gray>", "<gray>this NPC opens on click</gray>"),
        ctx -> menus.openNpcModePicker(ctx.player(), definition.id())));

    view.set(16, MenuButton.of(
        definition.followPlayerHead() ? ItemKey.minecraft("ender_eye") : ItemKey.minecraft("ender_pearl"),
        "<yellow><bold>Look at Players: " + (definition.followPlayerHead() ? "<green>ON</green>" : "<red>OFF</red>") + "</bold></yellow>",
        List.of("<gray>Click to toggle head rotation</gray>"),
        ctx -> {
          NpcDefinition updated = new NpcDefinition(definition.id(), definition.world(), definition.x(),
              definition.y(), definition.z(), definition.yaw(), definition.pitch(), definition.skin(),
              definition.name(), definition.clickCommand(), !definition.followPlayerHead(),
              definition.hologram(), definition.minigameMode());
          saveNpcFromMenu(ctx.player(), updated, () -> menus.openNpcEditor(ctx.player(), definition.id()));
        }));

    // Content: Row 2 (Position & Display Controls - slots 22, 24)
    view.set(22, MenuButton.of(ItemKey.minecraft("name_tag"),
        "<light_purple><bold>Hologram: " + (definition.hologram().isEmpty() ? "<red>OFF</red>" : "<green>ON</green>") + "</bold></light_purple>",
        List.of("<gray>Click to toggle title floating text</gray>"),
        ctx -> {
          List<String> holo = definition.hologram().isEmpty()
              ? List.of("<yellow><bold>" + (descriptor != null ? descriptor.displayName() : definition.id()) + "</bold></yellow>", "<gray>Click to Play</gray>")
              : List.of();
          NpcDefinition updated = new NpcDefinition(definition.id(), definition.world(), definition.x(),
              definition.y(), definition.z(), definition.yaw(), definition.pitch(), definition.skin(),
              definition.name(), definition.clickCommand(), definition.followPlayerHead(),
              holo, definition.minigameMode());
          saveNpcFromMenu(ctx.player(), updated, () -> menus.openNpcEditor(ctx.player(), definition.id()));
        }));

    view.set(24, MenuButton.of(ItemKey.minecraft("compass"),
        "<gold><bold>Move NPC Here</bold></gold>",
        List.of("<gray>Relocates the NPC to your exact position</gray>", "<yellow>Click to move</yellow>"),
        ctx -> {
          WorldPosition pos = ctx.player().position();
          if (pos == null) {
            ctx.player().sendActionBar("<red>Could not read your position.</red>");
            return;
          }
          NpcDefinition moved = new NpcDefinition(definition.id(), pos.worldName(), pos.coordinateX(), pos.coordinateY(), pos.coordinateZ(),
              pos.yaw(), pos.pitch(), definition.skin(), definition.name(), definition.clickCommand(),
              definition.followPlayerHead(), definition.hologram(), definition.minigameMode());
          saveNpcFromMenu(ctx.player(), moved, () -> {
            ctx.player().sendActionBar("<green>Moved NPC '" + definition.id() + "' to your position.</green>");
            menus.openNpcEditor(ctx.player(), definition.id());
          });
        }));

    // Bottom Navigation: Slot 47 Back, Slot 53 Delete
    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      menus.openNpcList(ctx.player());
    }));

    view.set(ChestLayout.SLOT_PRIMARY, support.confirmButton(player, ItemKey.minecraft("barrier"), MenuArt.model(MenuArt.ICON_DECLINE),
        "npc-delete:" + definition.id(),
        "<red><bold>Delete NPC</bold></red>",
        List.of("<gray>Permanently removes this NPC</gray>", "<yellow>Tap, then tap again to confirm</yellow>"),
        "<red><bold>⚠ Tap again to delete</bold></red>",
        List.of("<red>This deletes the NPC from the world!</red>", "<yellow>Tap once more to delete</yellow>"),
        ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          if (npcManager != null) {
            npcManager.remove(definition.id());
          }
          ctx.player().sendActionBar("<yellow>Deleted NPC '" + definition.id() + "'.</yellow>");
          menus.openNpcList(ctx.player());
        },
        viewer -> menus.openNpcEditor(viewer, definition.id())));

    support.open(player, view);
  }

  void openNpcModePicker(PlayerAdapter player, String npcId) {
    openNpcModePicker(player, npcId, 0);
  }

  void openNpcModePicker(PlayerAdapter player, String npcId, int page) {
    NpcManager npcManager = support.npcManager;
    GameManager gameManager = support.gameManager;
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }
    NpcDefinition definition = npcManager != null ? npcManager.get(npcId) : null;
    if (definition == null) {
      menus.openNpcList(player);
      return;
    }
    String current = definition.minigameMode();

    List<GameModeDescriptor> descriptors = new ArrayList<>();
    for (GameModeDescriptor descriptor : gameManager.descriptors()) {
      descriptors.add(descriptor);
    }

    MenuView view = PaginatedScreen.<GameModeDescriptor>of("<green><bold>Choose Minigame</bold></green>")
        .background(MenuArt.BG_ADMIN)
        .items(descriptors)
        .page(page)
        .itemMapper(descriptor -> {
          boolean selected = descriptor.modeId().equals(current);
          return MenuButton.of(support.icon(descriptor.modeId()),
              (selected ? "<green>✔ " : "<white>") + descriptor.displayName() + (selected ? "</green>" : "</white>"),
              List.of("<gray>Min players: <white>" + descriptor.minPlayers() + "</white></gray>",
                  selected ? "<green>Currently selected</green>" : "<yellow>Click to select</yellow>"),
              ctx -> {
                NpcDefinition updated = withMode(definition, descriptor.modeId());
                saveNpcFromMenu(ctx.player(), updated, () -> menus.openNpcEditor(ctx.player(), npcId));
              });
        })
        .onPageChange(p -> openNpcModePicker(player, npcId, p))
        .back(support.back(ctx -> menus.openNpcEditor(ctx.player(), npcId)))
        .primaryAction(MenuButton.of(ItemKey.minecraft("barrier"), "<red>Clear Minigame</red>",
            List.of("<gray>Disconnects minigame click actions</gray>"),
            ctx -> {
              NpcDefinition updated = withMode(definition, "");
              saveNpcFromMenu(ctx.player(), updated, () -> menus.openNpcEditor(ctx.player(), npcId));
            }))
        .build();

    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.ADMIN_SETTINGS);
    support.open(player, view);
  }

  void openNpcSkinPicker(PlayerAdapter player, String npcId) {
    openNpcSkinPicker(player, npcId, 0);
  }

  void openNpcSkinPicker(PlayerAdapter player, String npcId, int page) {
    if (!player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      player.sendActionBar("<red>You don't have permission for that.</red>");
      return;
    }
    NpcManager npcManager = support.npcManager;
    NpcDefinition definition = npcManager != null ? npcManager.get(npcId) : null;
    if (definition == null) {
      menus.openNpcList(player);
      return;
    }
    support.openPlayerPicker(player, "<aqua><bold>Choose Skin · " + MenuSupport.escape(npcId) + "</bold></aqua>",
        page,
        candidate -> true,
        target -> {
          NpcDefinition updated = withSkin(definition, target.name());
          saveNpcFromMenu(player, updated, () -> {
            player.sendActionBar("<green>Applied " + MenuSupport.escape(target.name()) + "'s skin to '" + npcId + "'.</green>");
            menus.openNpcEditor(player, npcId);
          });
        },
        support.back(ctx -> menus.openNpcEditor(ctx.player(), npcId)));
  }

  private void createNpcHere(PlayerAdapter player) {
    NpcManager npcManager = support.npcManager;
    WorldPosition pos = player.position();
    if (pos == null) {
      player.sendActionBar("<red>Could not read your position.</red>");
      return;
    }
    String id = "npc_" + System.currentTimeMillis() % 100_000;
    NpcDefinition created = new NpcDefinition(id, pos.worldName(), pos.coordinateX(), pos.coordinateY(), pos.coordinateZ(), pos.yaw(),
        pos.pitch(), player.name(), id, "", true, List.of(), "");
    try {
      if (npcManager != null) {
        npcManager.save(created);
      }
      player.sendActionBar("<green>Spawned NPC '" + id + "'.</green>");
      menus.openNpcEditor(player, id);
    } catch (java.io.IOException exception) {
      player.sendActionBar("<red>Could not create NPC: " + exception.getMessage() + "</red>");
    }
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
    if (npcManager == null) {
      after.run();
      return;
    }
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
}
