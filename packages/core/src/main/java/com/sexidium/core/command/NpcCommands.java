package com.sexidium.core.command;

import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.world.npc.NpcDefinition;
import com.sexidium.core.world.npc.NpcDefinitionStore;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;

import static com.sexidium.core.command.CommandText.blockCenter;
import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.joinFrom;
import static com.sexidium.core.command.CommandText.lower;

/** Handles {@code /sx admin npc} — create/edit/remove the lobby NPC definitions. */
final class NpcCommands {
  private final CommandContext ctx;

  NpcCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  void handle(CommandSource source, String[] args) {
    String subcommand = args.length > 1 ? lower(args[1]) : "";
    switch (subcommand) {
      case "create" -> {
        PlayerAdapter player = ctx.requirePlayer(source);
        if (player == null) {
          return;
        }
        if (args.length < 3) {
          source.sendMiniMessage("<red>Usage: <white>/sx admin npc create <id> [name...]</white>");
          return;
        }
        WorldPosition position = player.position();
        if (position == null) {
          source.sendMiniMessage("<red>Could not read your position.</red>");
          return;
        }
        String displayName = args.length > 3 ? joinFrom(args, 3) : args[2];
        saveNpc(source, new NpcDefinition(NpcDefinitionStore.sanitize(args[2]), position.worldName(),
            blockCenter(position.coordinateX()), position.coordinateY(), blockCenter(position.coordinateZ()),
            position.yaw(), position.pitch(), "", displayName, "", false, List.of(), ""));
      }
      case "remove", "delete" -> {
        if (args.length < 3) {
          source.sendMiniMessage("<red>Usage: <white>/sx admin npc remove <id></white>");
          return;
        }
        boolean removed = ctx.core.npcManager().remove(args[2]);
        source.sendMiniMessage(removed
            ? "<green>Removed NPC <white>" + args[2] + "</white>.</green>"
            : "<red>No NPC '<white>" + args[2] + "</white>'.</red>");
      }
      case "list" -> {
        var definitions = ctx.core.npcManager().definitions();
        if (definitions.isEmpty()) {
          source.sendMiniMessage("<gray>No NPCs configured.</gray>");
          return;
        }
        source.sendMiniMessage("<gold>NPCs (" + definitions.size() + "):</gold>");
        for (NpcDefinition definition : definitions) {
          source.sendMiniMessage("<gray>- <white>" + definition.id() + "</white> <dark_gray>@ " + definition.world()
              + " " + (int) definition.x() + "," + (int) definition.y() + "," + (int) definition.z() + "</dark_gray>");
        }
      }
      case "here" -> {
        PlayerAdapter player = ctx.requirePlayer(source);
        if (player == null) {
          return;
        }
        NpcDefinition definition = requireNpc(source, args);
        if (definition == null) {
          return;
        }
        WorldPosition position = player.position();
        saveNpc(source, new NpcDefinition(definition.id(), position.worldName(), blockCenter(position.coordinateX()),
            position.coordinateY(), blockCenter(position.coordinateZ()), position.yaw(), position.pitch(),
            definition.skin(), definition.name(), definition.clickCommand(), definition.followPlayerHead(),
            definition.hologram(), definition.minigameMode()));
      }
      case "command", "cmd" -> {
        NpcDefinition definition = requireNpc(source, args);
        if (definition == null) {
          return;
        }
        String command = args.length > 3 ? joinFrom(args, 3) : "";
        saveNpc(source, new NpcDefinition(definition.id(), definition.world(), definition.x(), definition.y(),
            definition.z(), definition.yaw(), definition.pitch(), definition.skin(), definition.name(), command,
            definition.followPlayerHead(), definition.hologram(), definition.minigameMode()));
      }
      case "name" -> {
        NpcDefinition definition = requireNpc(source, args);
        if (definition == null) {
          return;
        }
        String displayName = args.length > 3 ? joinFrom(args, 3) : "";
        saveNpc(source, new NpcDefinition(definition.id(), definition.world(), definition.x(), definition.y(),
            definition.z(), definition.yaw(), definition.pitch(), definition.skin(), displayName,
            definition.clickCommand(), definition.followPlayerHead(), definition.hologram(), definition.minigameMode()));
      }
      case "skin" -> {
        NpcDefinition definition = requireNpc(source, args);
        if (definition == null) {
          return;
        }
        String skin = args.length > 3 ? args[3] : "";
        saveNpc(source, new NpcDefinition(definition.id(), definition.world(), definition.x(), definition.y(),
            definition.z(), definition.yaw(), definition.pitch(), skin, definition.name(),
            definition.clickCommand(), definition.followPlayerHead(), definition.hologram(), definition.minigameMode()));
      }
      case "follow" -> {
        NpcDefinition definition = requireNpc(source, args);
        if (definition == null) {
          return;
        }
        boolean follow = args.length > 3 && Boolean.parseBoolean(args[3]);
        saveNpc(source, new NpcDefinition(definition.id(), definition.world(), definition.x(), definition.y(),
            definition.z(), definition.yaw(), definition.pitch(), definition.skin(), definition.name(),
            definition.clickCommand(), follow, definition.hologram(), definition.minigameMode()));
      }
      case "holo" -> {
        NpcDefinition definition = requireNpc(source, args);
        if (definition == null) {
          return;
        }
        String operation = args.length > 3 ? lower(args[3]) : "";
        List<String> hologram = new ArrayList<>(definition.hologram());
        if (operation.equals("add") && args.length > 4) {
          hologram.add(joinFrom(args, 4));
        } else if (operation.equals("clear")) {
          hologram.clear();
        } else {
          source.sendMiniMessage("<red>Usage: <white>/sx admin npc holo <id> add <line...></white> or <white>clear</white>");
          return;
        }
        saveNpc(source, new NpcDefinition(definition.id(), definition.world(), definition.x(), definition.y(),
            definition.z(), definition.yaw(), definition.pitch(), definition.skin(), definition.name(),
            definition.clickCommand(), definition.followPlayerHead(), hologram, definition.minigameMode()));
      }
      case "mode" -> {
        NpcDefinition definition = requireNpc(source, args);
        if (definition == null) {
          return;
        }
        if (args.length < 4) {
          source.sendMiniMessage("<red>Usage: <white>/sx admin npc mode <id> <minigame|none></white>");
          return;
        }
        String wanted = lower(args[3]);
        String resolvedMode;
        if (equalsAny(wanted, "none", "off", "clear", "manual")) {
          resolvedMode = "";
        } else {
          GameModeDescriptor descriptor = ctx.findMinigameDescriptor(wanted);
          if (descriptor == null) {
            source.sendMiniMessage("<red>'<white>" + wanted + "</white>' is not a minigame. Options: <white>"
                + String.join(", ", ctx.minigameIds()) + "</white></red>");
            return;
          }
          resolvedMode = descriptor.modeId();
        }
        saveNpc(source, new NpcDefinition(definition.id(), definition.world(), definition.x(), definition.y(),
            definition.z(), definition.yaw(), definition.pitch(), definition.skin(), definition.name(),
            definition.clickCommand(), definition.followPlayerHead(), definition.hologram(), resolvedMode));
      }
      case "edit" -> {
        PlayerAdapter player = ctx.requirePlayer(source);
        if (player == null) {
          return;
        }
        NpcDefinition definition = requireNpc(source, args);
        if (definition == null) {
          return;
        }
        ctx.core.menus().openNpcEditor(player, definition.id());
      }
      case "reload" -> {
        ctx.core.npcManager().reloadAndSpawn();
        source.sendMiniMessage("<green>Reloaded lobby NPCs.</green>");
      }
      default -> source.sendMiniMessage(
          "<gold>/sx admin npc <white>create|remove|list|here|command|name|skin|follow|holo|mode|edit|reload</white>");
    }
  }

  private NpcDefinition requireNpc(CommandSource source, String[] args) {
    if (args.length < 3) {
      source.sendMiniMessage("<red>Specify an NPC id.</red>");
      return null;
    }
    NpcDefinition definition = ctx.core.npcManager().get(args[2]);
    if (definition == null) {
      source.sendMiniMessage("<red>No NPC '<white>" + args[2] + "</white>'.</red>");
    }
    return definition;
  }

  private void saveNpc(CommandSource source, NpcDefinition definition) {
    try {
      ctx.core.npcManager().save(definition);
      source.sendMiniMessage("<green>Saved NPC <white>" + definition.id() + "</white>.</green>");
    } catch (java.io.IOException exception) {
      source.sendMiniMessage("<red>Could not save NPC: " + exception.getMessage() + "</red>");
    }
  }

  List<String> suggest(String[] args) {
    if (args.length == 2) {
      return ctx.filter(List.of("create", "remove", "list", "here", "command", "name", "skin", "follow", "holo",
          "mode", "edit", "reload"), args[1]);
    }
    if (args.length == 3 && !lower(args[1]).equals("create")) {
      List<String> ids = new ArrayList<>();
      for (NpcDefinition definition : ctx.core.npcManager().definitions()) {
        ids.add(definition.id());
      }
      return ctx.filter(ids, args[2]);
    }
    if (args.length == 4 && lower(args[1]).equals("mode")) {
      List<String> options = new ArrayList<>(ctx.minigameIds());
      options.add("none");
      return ctx.filter(options, args[3]);
    }
    return List.of();
  }
}
